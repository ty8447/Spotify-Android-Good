//! Vibe DJ's extra players: deck B (plays the incoming song into the mixer's ring B) and the
//! analysis deck (decodes songs as fast as allowed into the [`Analyzer`]). Both are ordinary
//! librespot players on the main player's session (or an offline one), so downloaded songs come
//! from disk and streamed ones reuse the process's audio keys.

use super::analysis::Analyzer;
use super::{mixer, store};
use crate::engine::player_host;
use librespot_core::SpotifyUri;
use librespot_playback::audio_backend::{Sink, SinkError, SinkResult};
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;
use librespot_playback::player::{Player, PlayerEvent};
use parking_lot::Mutex;
use std::sync::{Arc, Weak};
use std::time::{Duration, Instant};

type Slot = Arc<Mutex<Weak<Player>>>;

fn samples(packet: AudioPacket, converter: &mut Converter) -> SinkResult<Vec<f32>> {
    match packet {
        AudioPacket::Samples(s) => Ok(converter.f64_to_f32(&s)),
        AudioPacket::Raw(_) => Err(SinkError::InvalidParams("raw packets are not supported".into())),
    }
}

fn position(slot: &Slot) -> (Arc<str>, f64) {
    slot.lock()
        .upgrade()
        .and_then(|p| p.last_decoded())
        .map(|d| (Arc::<str>::from(d.track_id.to_uri().unwrap_or_default()), f64::from(d.position_ms)))
        .unwrap_or_else(|| (Arc::from(""), 0.0))
}

struct DeckSink {
    slot: Slot,
}

impl Sink for DeckSink {
    fn start(&mut self) -> SinkResult<()> {
        Ok(())
    }
    fn stop(&mut self) -> SinkResult<()> {
        Ok(())
    }
    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        let pcm = samples(packet, converter)?;
        let Some(m) = mixer::current() else { return Ok(()) };
        let (track, pos) = position(&self.slot);
        m.ring_b.push(track, pos, pcm);
        Ok(())
    }
}

/// Deck B.
pub(crate) struct DeckB {
    player: Arc<Player>,
}

impl DeckB {
    pub(crate) fn new() -> Self {
        let slot: Slot = Arc::new(Mutex::new(Weak::new()));
        let s = slot.clone();
        let player = player_host::new_deck_player(move || Box::new(DeckSink { slot: s }) as Box<dyn Sink>);
        *slot.lock() = Arc::downgrade(&player);
        Self { player }
    }

    /// Loads `uri` a little before `entry_ms` and lets it fill ring B (the mixer drops what comes
    /// before the entry).
    pub(crate) fn cue(&self, uri: &str, entry_ms: f64) {
        if let Some(m) = mixer::current() {
            m.ring_b.clear();
        }
        match SpotifyUri::from_uri(uri) {
            Ok(id) => self.player.load(id, true, (entry_ms - 300.0).max(0.0) as u32),
            Err(e) => log::warn!("vibe dj: bad uri {uri}: {e}"),
        }
    }

    pub(crate) fn pause(&self) {
        self.player.pause();
    }

    pub(crate) fn play(&self) {
        self.player.play();
    }

    pub(crate) fn stop(&self) {
        self.player.stop();
        if let Some(m) = mixer::current() {
            m.ring_b.clear();
        }
    }
}

impl Drop for DeckB {
    fn drop(&mut self) {
        self.player.stop();
    }
}

struct AnalysisSink {
    analyzer: Arc<Mutex<Option<Analyzer>>>,
    /// Decoding is capped at this multiple of real time (0 = uncapped) so it never competes with
    /// playback or drains the battery.
    cap: Arc<Mutex<f32>>,
}

impl Sink for AnalysisSink {
    fn start(&mut self) -> SinkResult<()> {
        Ok(())
    }
    fn stop(&mut self) -> SinkResult<()> {
        Ok(())
    }
    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        let pcm = samples(packet, converter)?;
        let frames = pcm.len() / 2;
        let started = Instant::now();
        if let Some(a) = self.analyzer.lock().as_mut() {
            a.push(&pcm);
        }
        let cap = *self.cap.lock();
        if cap > 0.0 {
            let budget = Duration::from_secs_f32(frames as f32 / 44_100.0 / cap);
            if let Some(rest) = budget.checked_sub(started.elapsed()) {
                std::thread::sleep(rest);
            }
        }
        Ok(())
    }
}

/// The analysis deck: one track at a time.
pub(crate) struct AnalysisDeck {
    player: Arc<Player>,
    analyzer: Arc<Mutex<Option<Analyzer>>>,
    cap: Arc<Mutex<f32>>,
    events: tokio::sync::mpsc::UnboundedReceiver<PlayerEvent>,
}

impl AnalysisDeck {
    pub(crate) fn new() -> Self {
        let analyzer = Arc::new(Mutex::new(None));
        let cap = Arc::new(Mutex::new(20.0));
        let (a, c) = (analyzer.clone(), cap.clone());
        let player = player_host::new_deck_player(move || Box::new(AnalysisSink { analyzer: a, cap: c }) as Box<dyn Sink>);
        let events = player.get_player_event_channel();
        Self { player, analyzer, cap, events }
    }

    /// Analyses `uri` (stored in the cache); false if it can't be played or analyzed.
    /// `background`: uncapped (charging and idle); otherwise 20× real time at most.
    pub(crate) async fn analyze(&mut self, uri: &str, background: bool) -> bool {
        let Ok(id) = SpotifyUri::from_uri(uri) else { return false };
        *self.cap.lock() = if background { 0.0 } else { 20.0 };
        *self.analyzer.lock() = Some(Analyzer::new());
        while self.events.try_recv().is_ok() {}
        self.player.load(id, true, 0);
        let deadline = tokio::time::Instant::now() + Duration::from_secs(if background { 120 } else { 90 });
        let ok = loop {
            match tokio::time::timeout_at(deadline, self.events.recv()).await {
                Ok(Some(PlayerEvent::EndOfTrack { .. })) => break true,
                Ok(Some(PlayerEvent::Unavailable { .. })) => break false,
                Ok(Some(_)) => continue,
                Ok(None) | Err(_) => break false,
            }
        };
        self.player.stop();
        let analyzer = self.analyzer.lock().take();
        let result = ok
            .then(|| analyzer.and_then(|a| {
                let ms = a.pushed_ms() as u32;
                a.finish(ms)
            }))
            .flatten();
        match result {
            Some(a) => {
                store::put(uri, a);
                true
            }
            None => {
                store::mark_failed(uri);
                false
            }
        }
    }
}

impl Drop for AnalysisDeck {
    fn drop(&mut self) {
        self.player.stop();
    }
}
