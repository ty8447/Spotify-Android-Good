//! Vibe DJ: a listening mode that mixes songs like a club DJ, and the app's crossfade.
//!
//! * [`analysis`] measures tempo, beats, key, energy and phrases of every song it plays (once;
//!   cached by [`store`]), on the phone, from the decoded audio, so it works offline.
//! * [`planner`] picks the next song from a pool the app supplies (a playlist, the queue,
//!   downloads, radio), the moment to leave the current one and the style of the mix, and finds a
//!   route of songs to a target song.
//! * [`decks`] plays the incoming song on a second player; [`mixer`] blends both into the one
//!   AudioTrack and hands back to the main player, which Spotify Connect keeps following.
//!
//! RPC (`dj.*`, docs/ARCHITECTURE.md §6.7): `start`, `stop`, `setPool`, `setPrefs`, `setTarget`,
//! `lockNext`, `swapNext`, `mixNow`, `crossfade`, `analyze`, `trackInfo`, `cacheInfo`,
//! `clearCache`, `state`. Events: `dj.state` (often, small) and `dj.plan` (on change, with the
//! songs' energy curves for the transition view).

pub(crate) mod analysis;
mod decks;
mod dsp;
mod fft;
pub(crate) mod mixer;
pub(crate) mod planner;
mod store;

use crate::catalog::mix::{self, Preset, Recipe};
use crate::connect;
use crate::error::{AppError, AppResult};
use crate::events;
use crate::models::{PlaybackSnapshot, PlaybackSource, PlaybackStatus};
use analysis::{key_name, Analysis};
use decks::{AnalysisDeck, DeckB};
use mixer::{Armed, Phase};
use parking_lot::Mutex;
use planner::{Candidate, Context, Length, Pick, Prefs, Style, Transition};
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::{HashSet, VecDeque};
use std::sync::{Arc, LazyLock};
use std::time::{Duration, Instant};

/// How long before the mix (and its tempo glide) deck B starts loading the next song.
const CUE_LEAD_MS: f64 = 10_000.0;
/// Streamed songs analysed ahead at a time (each costs an audio key and a download).
const ONLINE_LOOKAHEAD: usize = 4;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PoolTrack {
    uri: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    artist: String,
    /// Downloaded (analysed without the network).
    #[serde(default)]
    local: bool,
}

#[derive(Debug, Clone, Copy, PartialEq)]
enum Mode {
    Off,
    Dj,
    Crossfade { seconds: f32, smart: bool },
}

#[derive(Debug, Clone)]
struct Target {
    uri: String,
    asap: bool,
    path: Vec<String>,
}

#[derive(Debug, Clone)]
struct Planned {
    a_uri: String,
    pick: Pick,
    b_name: String,
    b_artist: String,
    cued: bool,
    armed: bool,
    handoff_at: Option<Instant>,
    handoff_tries: u8,
    seek_sent: bool,
}

struct State {
    mode: Mode,
    crossfade: Option<(f32, bool)>,
    prefs: Prefs,
    pool: Vec<PoolTrack>,
    source: Value,
    history: Vec<String>,
    artists: Vec<String>,
    target: Option<Target>,
    locked: Option<String>,
    skipped: HashSet<String>,
    planned: Option<Planned>,
    current: Option<String>,
    started: Instant,
    queue: VecDeque<String>,
    analyzing: Option<String>,
    status: String,
    asap: bool,
    b_paused: bool,
    plan_rev: u64,
    sent_rev: u64,
    last_emit: Instant,
    /// A mixed playlist playing (its context URI): Spotify's transitions play through the mixer.
    mixed: Option<String>,
}

impl Default for State {
    fn default() -> Self {
        Self {
            mode: Mode::Off,
            crossfade: None,
            prefs: Prefs::default(),
            pool: Vec::new(),
            source: Value::Null,
            history: Vec::new(),
            artists: Vec::new(),
            target: None,
            locked: None,
            skipped: HashSet::new(),
            planned: None,
            current: None,
            started: Instant::now(),
            queue: VecDeque::new(),
            analyzing: None,
            status: String::new(),
            asap: false,
            b_paused: false,
            plan_rev: 0,
            sent_rev: u64::MAX,
            last_emit: Instant::now(),
            mixed: None,
        }
    }
}

static STATE: LazyLock<Mutex<State>> = LazyLock::new(|| Mutex::new(State::default()));
static LOOP: Mutex<Option<tokio::task::JoinHandle<()>>> = parking_lot::const_mutex(None);
static ANALYSIS: tokio::sync::Mutex<Option<AnalysisDeck>> = tokio::sync::Mutex::const_new(None);

fn args<T: serde::de::DeserializeOwned>(v: Value) -> AppResult<T> {
    serde_json::from_value(v).map_err(|e| AppError::invalid(format!("bad arguments: {e}")))
}

pub async fn handle(method: &str, a: Value) -> AppResult<Value> {
    match method {
        "dj.start" => {
            #[derive(Deserialize)]
            #[serde(rename_all = "camelCase")]
            struct Start {
                #[serde(default)]
                pool: Vec<PoolTrack>,
                #[serde(default)]
                source: Value,
                prefs: Option<Prefs>,
            }
            let s: Start = args(a)?;
            {
                let mut st = STATE.lock();
                st.mode = Mode::Dj;
                st.pool = s.pool;
                st.source = s.source;
                if let Some(p) = s.prefs {
                    st.prefs = p;
                }
                st.started = Instant::now();
                st.history.clear();
                st.skipped.clear();
                st.target = None;
                st.locked = None;
                st.planned = None;
                st.plan_rev += 1;
            }
            ensure_loop();
            Ok(state_json(true))
        }
        "dj.stop" => {
            let mut st = STATE.lock();
            st.mode = st.crossfade.map_or(Mode::Off, |(seconds, smart)| Mode::Crossfade { seconds, smart });
            st.planned = None;
            st.plan_rev += 1;
            drop(st);
            ensure_loop();
            Ok(state_json(true))
        }
        "dj.setPool" => {
            #[derive(Deserialize)]
            struct P {
                pool: Vec<PoolTrack>,
                #[serde(default)]
                append: bool,
            }
            let p: P = args(a)?;
            let mut st = STATE.lock();
            if p.append {
                let have: HashSet<String> = st.pool.iter().map(|t| t.uri.clone()).collect();
                let new: Vec<PoolTrack> = p.pool.into_iter().filter(|t| !have.contains(&t.uri)).collect();
                st.pool.extend(new);
            } else {
                st.pool = p.pool;
            }
            Ok(json!({ "size": st.pool.len() }))
        }
        "dj.setPrefs" => {
            let prefs: Prefs = args(a)?;
            let mut st = STATE.lock();
            st.prefs = prefs;
            if !st.planned.as_ref().is_some_and(|p| p.armed) {
                st.planned = None;
            }
            st.plan_rev += 1;
            Ok(Value::Null)
        }
        "dj.setTarget" => {
            #[derive(Deserialize)]
            struct T {
                uri: Option<String>,
                #[serde(default)]
                asap: bool,
                name: Option<String>,
                artist: Option<String>,
            }
            let t: T = args(a)?;
            let mut st = STATE.lock();
            st.target = t.uri.map(|uri| Target { uri, asap: t.asap, path: Vec::new() });
            if let Some(tg) = st.target.clone() {
                if !st.pool.iter().any(|p| p.uri == tg.uri) {
                    st.pool.push(PoolTrack {
                        uri: tg.uri.clone(),
                        name: t.name.unwrap_or_default(),
                        artist: t.artist.unwrap_or_default(),
                        local: false,
                    });
                }
                st.queue.retain(|u| *u != tg.uri);
                st.queue.push_front(tg.uri);
            }
            replan_unarmed(&mut st);
            Ok(Value::Null)
        }
        "dj.lockNext" => {
            #[derive(Deserialize)]
            struct L {
                uri: Option<String>,
            }
            let l: L = args(a)?;
            let mut st = STATE.lock();
            st.locked = l.uri;
            replan_unarmed(&mut st);
            Ok(Value::Null)
        }
        "dj.swapNext" => {
            let mut st = STATE.lock();
            if let Some(p) = st.planned.as_ref().filter(|p| !p.armed) {
                let uri = p.pick.uri.clone();
                st.skipped.insert(uri);
                st.locked = None;
                replan_unarmed(&mut st);
            }
            Ok(Value::Null)
        }
        "dj.mixNow" => {
            let mut st = STATE.lock();
            st.asap = true;
            replan_unarmed(&mut st);
            Ok(Value::Null)
        }
        "dj.crossfade" => {
            #[derive(Deserialize)]
            struct C {
                seconds: f32,
                #[serde(default)]
                smart: bool,
            }
            let c: C = args(a)?;
            let mut st = STATE.lock();
            st.crossfade = (c.seconds > 0.0).then_some((c.seconds.clamp(1.0, 12.0), c.smart));
            if st.mode != Mode::Dj {
                st.mode = st.crossfade.map_or(Mode::Off, |(seconds, smart)| Mode::Crossfade { seconds, smart });
                if !st.planned.as_ref().is_some_and(|p| p.armed) {
                    st.planned = None;
                }
            }
            drop(st);
            ensure_loop();
            Ok(Value::Null)
        }
        "dj.analyze" => {
            #[derive(Deserialize)]
            struct An {
                uris: Vec<String>,
                #[serde(default)]
                background: bool,
            }
            let an: An = args(a)?;
            let (mut done, mut failed) = (0, 0);
            for uri in an.uris.iter().take(50) {
                if store::get(uri).is_some() || store::failed_recently(uri) {
                    continue;
                }
                if analyze(uri, an.background).await {
                    done += 1;
                } else {
                    failed += 1;
                }
            }
            if STATE.lock().mode == Mode::Off {
                *ANALYSIS.lock().await = None;
            }
            Ok(json!({ "analyzed": done, "failed": failed }))
        }
        "dj.trackInfo" => {
            #[derive(Deserialize)]
            struct U {
                uri: String,
            }
            let u: U = args(a)?;
            Ok(store::get(&u.uri).map_or(Value::Null, |a| track_json(&u.uri, "", "", &a, true)))
        }
        "dj.cacheInfo" => {
            let (count, bytes) = tokio::task::spawn_blocking(store::info).await.unwrap_or((0, 0));
            Ok(json!({ "tracks": count, "bytes": bytes }))
        }
        "dj.clearCache" => {
            tokio::task::spawn_blocking(store::clear).await.ok();
            Ok(Value::Null)
        }
        "dj.state" => Ok(state_json(true)),
        // The playing context changed: a mixed playlist's songs flow into each other as Spotify
        // mixed them (its stored transitions, else an automatic beat-matched one).
        "dj.context" => {
            #[derive(Deserialize)]
            struct C {
                uri: Option<String>,
            }
            let c: C = args(a)?;
            let Some(uri) = c.uri.filter(|u| u.starts_with("spotify:playlist:")) else {
                STATE.lock().mixed = None;
                return Ok(json!({ "mixed": false }));
            };
            let mixed = crate::catalog::handle("playlist.mixInfo", json!({ "uri": uri }))
                .await
                .ok()
                .and_then(|v| v["mixed"].as_bool())
                .unwrap_or(false);
            log::info!("vibe dj: context {} mixed={mixed}", uri.rsplit(':').next().unwrap_or_default());
            {
                let mut st = STATE.lock();
                st.mixed = mixed.then_some(uri);
                if !st.planned.as_ref().is_some_and(|p| p.armed) {
                    st.planned = None;
                    st.plan_rev += 1;
                }
            }
            ensure_loop();
            Ok(json!({ "mixed": mixed }))
        }
        _ => Err(AppError::invalid(format!("unknown method {method}"))),
    }
}

fn replan_unarmed(st: &mut State) {
    if !st.planned.as_ref().is_some_and(|p| p.cued) {
        st.planned = None;
        st.plan_rev += 1;
    }
}

async fn analyze(uri: &str, background: bool) -> bool {
    let mut deck = ANALYSIS.lock().await;
    if deck.is_none() {
        *deck = Some(AnalysisDeck::new());
    }
    STATE.lock().analyzing = Some(uri.to_string());
    let ok = match deck.as_mut() {
        Some(d) => d.analyze(uri, background).await,
        None => false,
    };
    STATE.lock().analyzing = None;
    ok
}

fn ensure_loop() {
    let mut slot = LOOP.lock();
    if slot.as_ref().is_some_and(|h| !h.is_finished()) {
        return;
    }
    if !active(&STATE.lock()) {
        return;
    }
    *slot = Some(crate::runtime::handle().spawn(run()));
}

/// Whether the mixer loop has work: Vibe DJ, crossfade or a mixed playlist.
fn active(st: &State) -> bool {
    st.mode != Mode::Off || st.mixed.is_some()
}

/// Spotify's vocal activity of these songs, fetched in the background for the planner.
fn prefetch_vocals(uris: Vec<String>) {
    crate::runtime::handle().spawn(async move {
        let Ok(s) = crate::engine::session() else { return };
        for u in uris.into_iter().filter(|u| u.starts_with("spotify:track:")) {
            let _ = mix::vocals(&s, &u).await;
        }
    });
}

/// A mixed playlist's stored transition, as the mixer plays it: Spotify's overlap (where A and B
/// meet, for how long, at which speeds) with its preset's shape (cut or fade; beat-matched fades
/// swap the bass on the beat).
fn from_recipe(r: &Recipe) -> Transition {
    let beat_ms = if r.bpm_a > 40.0 { 60_000.0 / f64::from(r.bpm_a) } else { 500.0 };
    let cut = matches!(r.preset, Preset::Cut | Preset::BeatmatchedCut);
    let style = if cut {
        Style::Cut
    } else if r.beatmatched || r.preset == Preset::BeatmatchedFade {
        Style::BassSwap
    } else {
        Style::Crossfade
    };
    let (a_speed, b_speed) = if r.beatmatched { (r.speed_a, r.speed_b) } else { (1.0, 1.0) };
    // A cut lands in the middle of Spotify's overlap.
    let half = if cut { r.duration_ms / 2.0 } else { 0.0 };
    Transition {
        style,
        mix_start_ms: r.start_a_ms + half,
        length_ms: if cut { 30.0 } else { r.duration_ms.max(500.0) },
        beats: (r.duration_ms / beat_ms).round() as u32,
        entry_ms: r.start_b_ms + half * b_speed / a_speed.max(0.01),
        speed: b_speed,
        a_speed,
        pre_glide_ms: if (a_speed - 1.0).abs() > 0.001 { (8.0 * beat_ms).min(8_000.0) } else { 0.0 },
        settle_ms: if (b_speed - 1.0).abs() > 0.001 { (16.0 * beat_ms).min(16_000.0) } else { 0.0 },
        gain_db: 0.0,
        beat_ms,
        pitch_semitones: 0.0,
    }
}

fn candidate(t: &PoolTrack) -> Candidate {
    Candidate { uri: t.uri.clone(), name: t.name.clone(), artist: t.artist.clone(), analysis: store::get(&t.uri) }
}

/// Where the main player is now (estimated between snapshots).
fn position(snap: &PlaybackSnapshot) -> f64 {
    let mut p = snap.position_ms as f64;
    if snap.status == PlaybackStatus::Playing {
        p += (connect::now_ms() - snap.position_timestamp_ms).max(0) as f64 * snap.playback_speed;
    }
    p
}

/// The plain crossfade: the last `seconds` of A over the start of B.
fn simple_crossfade(a_duration: u32, seconds: f32) -> Transition {
    let len = f64::from(seconds) * 1000.0;
    Transition {
        style: Style::Crossfade,
        mix_start_ms: (f64::from(a_duration) - len - 400.0).max(0.0),
        length_ms: len,
        beats: 0,
        entry_ms: 0.0,
        speed: 1.0,
        a_speed: 1.0,
        pre_glide_ms: 0.0,
        settle_ms: 0.0,
        gain_db: 0.0,
        beat_ms: 500.0,
        pitch_semitones: 0.0,
    }
}

/// Decides the next song and the mix for the playing one (`None` = not yet).
fn decide(st: &mut State, snap: &PlaybackSnapshot, a_uri: &str, pos: f64) -> Option<Planned> {
    let track = snap.track.as_ref()?;
    let a_duration = snap.duration_ms as u32;
    let a_artist = track.artists.first().map(|x| x.name.clone()).unwrap_or_default();
    let current = Candidate {
        uri: a_uri.to_string(),
        name: track.name.clone().unwrap_or_default(),
        artist: a_artist,
        analysis: store::get(a_uri),
    };
    let next_up = snap.next_tracks.iter().find(|t| !t.is_episode && t.uri.starts_with("spotify:track:"));
    let named = |uri: &str, st: &State| -> (String, String) {
        st.pool
            .iter()
            .find(|p| p.uri == uri)
            .map(|p| (p.name.clone(), p.artist.clone()))
            .or_else(|| {
                snap.next_tracks
                    .iter()
                    .find(|t| t.uri == uri)
                    .map(|t| (t.name.clone().unwrap_or_default(), t.artists.first().map(|a| a.name.clone()).unwrap_or_default()))
            })
            .unwrap_or_default()
    };
    let mk = |pick: Pick, st: &State| {
        let (b_name, b_artist) = named(&pick.uri, st);
        Planned { a_uri: a_uri.to_string(), pick, b_name, b_artist, cued: false, armed: false, handoff_at: None, handoff_tries: 0, seek_sent: false }
    };
    let mixed_here = st.mixed.as_deref().is_some_and(|m| snap.context.as_ref().is_some_and(|c| c.uri == m));
    match st.mode {
        _ if mixed_here && st.mode != Mode::Dj => {
            let b = next_up?;
            if let Some(r) = mix::recipe(a_uri, &b.uri) {
                let t = from_recipe(&r);
                if t.mix_start_ms - t.pre_glide_ms > pos + 300.0 && t.mix_start_ms < f64::from(a_duration) {
                    return Some(mk(Pick { uri: b.uri.clone(), score: 1.0, transition: t, reasons: vec!["Spotify's mix".into()] }, st));
                }
            }
            // Spotify's automatic transition: beat-matched near the end where both songs are known.
            if let (Some(a), Some(bb)) = (current.analysis.as_ref(), store::get(&b.uri)) {
                let prefs = Prefs { length: Length::Full, ..st.prefs.clone() };
                let (va, vb) = (mix::vocals_cached(a_uri), mix::vocals_cached(&b.uri));
                if let Some((t, _)) = planner::plan_with(a, &bb, &prefs, pos, false, (va.as_ref(), vb.as_ref())) {
                    if t.mix_start_ms >= f64::from(a_duration) - 45_000.0 {
                        return Some(mk(Pick { uri: b.uri.clone(), score: 1.0, transition: t, reasons: vec!["auto mix".into()] }, st));
                    }
                }
            }
            if current.analysis.is_none() {
                queue_front(st, a_uri);
            }
            if store::get(&b.uri).is_none() {
                queue_back(st, &b.uri);
            }
            let remaining = f64::from(a_duration) - pos;
            (remaining < 30_000.0 && remaining > 8_000.0).then(|| {
                mk(Pick { uri: b.uri.clone(), score: 0.0, transition: simple_crossfade(a_duration, 6.0), reasons: vec!["auto mix".into()] }, st)
            })
        }
        Mode::Off => None,
        Mode::Crossfade { seconds, smart } => {
            let b = next_up?;
            if smart {
                if let (Some(a), Some(bb)) = (current.analysis.as_ref(), store::get(&b.uri)) {
                    let prefs = Prefs {
                        length: Length::Full,
                        styles: vec![Style::Crossfade, Style::BassSwap],
                        ..st.prefs.clone()
                    };
                    if let Some((t, _)) = planner::plan(a, &bb, &prefs, pos, false) {
                        let start_min = f64::from(a_duration) - 30_000.0;
                        if t.mix_start_ms >= start_min {
                            return Some(mk(Pick { uri: b.uri.clone(), score: 1.0, transition: t, reasons: vec!["beat-matched".into()] }, st));
                        }
                    }
                }
                if current.analysis.is_none() {
                    queue_front(st, a_uri);
                }
                if store::get(&b.uri).is_none() {
                    queue_back(st, &b.uri);
                }
            }
            (a_duration > 20_000).then(|| {
                mk(Pick { uri: b.uri.clone(), score: 1.0, transition: simple_crossfade(a_duration, seconds), reasons: Vec::new() }, st)
            })
        }
        Mode::Dj => {
            let Some(a) = current.analysis.clone() else {
                queue_front(st, a_uri);
                st.status = format!("Listening to {}…", current.name);
                return None;
            };
            // Keep a few unanalysed songs of the pool in the analysis queue.
            let wanted: Vec<String> = {
                let mut v: Vec<&PoolTrack> = st
                    .pool
                    .iter()
                    .filter(|p| p.uri != a_uri && !st.queue.contains(&p.uri) && store::get(&p.uri).is_none() && !store::failed_recently(&p.uri))
                    .collect();
                // Downloaded songs first (no key, no data); a stable shuffle of the rest.
                v.sort_by_key(|p| (!p.local, p.uri.bytes().fold(0u32, |h, b| h.wrapping_mul(31).wrapping_add(u32::from(b)))));
                let online_pending = st.queue.len();
                let mut out = Vec::new();
                for p in v {
                    if !p.local && online_pending + out.len() >= ONLINE_LOOKAHEAD {
                        break;
                    }
                    out.push(p.uri.clone());
                    if out.len() >= 16 {
                        break;
                    }
                }
                out
            };
            for u in wanted {
                queue_back(st, &u);
            }
            let asap = st.asap;
            let history: Vec<String> = st.history.clone();
            let pool: Vec<Candidate> = st.pool.iter().filter(|p| !st.skipped.contains(&p.uri)).map(candidate).collect();
            let progress = st.started.elapsed().as_secs_f32() / (st.prefs.session_minutes.max(1) as f32 * 60.0);
            // A requested target: route to it, then follow the route's first hop.
            if let Some(tg) = st.target.clone() {
                if let Some(target) = pool.iter().find(|c| c.uri == tg.uri).cloned() {
                    if target.analysis.is_none() {
                        queue_front(st, &tg.uri);
                    } else {
                        let path = planner::route(&current, &target, &pool, &st.prefs, tg.asap);
                        if let Some(t) = st.target.as_mut() {
                            t.path = path.clone();
                        }
                        if let Some(hop) = path.get(1).and_then(|u| pool.iter().find(|c| c.uri == *u)) {
                            let b = hop.analysis.clone()?;
                            let (va, vb) = (mix::vocals_cached(a_uri), mix::vocals_cached(&hop.uri));
                            let (t, _) = planner::plan_with(&a, &b, &st.prefs, pos, asap || tg.asap, (va.as_ref(), vb.as_ref()))?;
                            let reasons = if hop.uri == tg.uri { vec!["your pick".to_string()] } else { vec![format!("on the way to {}", target.name)] };
                            return Some(mk(Pick { uri: hop.uri.clone(), score: 1.0, transition: t, reasons }, st));
                        }
                    }
                }
            }
            if let Some(locked) = st.locked.clone() {
                if let Some(b) = store::get(&locked) {
                    let (va, vb) = (mix::vocals_cached(a_uri), mix::vocals_cached(&locked));
                    if let Some((t, _)) = planner::plan_with(&a, &b, &st.prefs, pos, asap, (va.as_ref(), vb.as_ref())) {
                        return Some(mk(Pick { uri: locked, score: 1.0, transition: t, reasons: vec!["locked in".into()] }, st));
                    }
                } else {
                    queue_front(st, &locked);
                }
            }
            let ctx = Context {
                current: &current,
                position_ms: pos,
                history: &history,
                recent_artists: &st.artists,
                session_progress: progress,
                asap,
            };
            let pick = planner::pick_next(&ctx, &pool, &st.prefs);
            if pick.is_none() {
                st.status = if st.queue.is_empty() { "Nothing in the pool fits yet: add songs".into() } else { "Checking songs…".into() };
                // Out of room in this song: fall back to the queue's next song with a crossfade.
                let remaining = f64::from(a_duration) - pos;
                if remaining < 25_000.0 && remaining > 8_000.0 {
                    if let Some(b) = next_up {
                        let t = simple_crossfade(a_duration, 6.0);
                        return Some(mk(Pick { uri: b.uri.clone(), score: 0.0, transition: t, reasons: vec!["next in the queue".into()] }, st));
                    }
                }
            }
            pick.map(|p| mk(p, st))
        }
    }
}

fn queue_front(st: &mut State, uri: &str) {
    if st.analyzing.as_deref() == Some(uri) || store::failed_recently(uri) {
        return;
    }
    st.queue.retain(|u| u != uri);
    st.queue.push_front(uri.to_string());
}

fn queue_back(st: &mut State, uri: &str) {
    if st.analyzing.as_deref() == Some(uri) || st.queue.iter().any(|u| u == uri) || store::failed_recently(uri) {
        return;
    }
    st.queue.push_back(uri.to_string());
}

async fn drop_deck(deck: Option<DeckB>) {
    if let Some(d) = deck {
        let _ = tokio::task::spawn_blocking(move || drop(d)).await;
    }
}

async fn run() {
    let mut deck_b: Option<DeckB> = None;
    let analysis = crate::runtime::handle().spawn(analysis_loop());
    let mut tick = tokio::time::interval(Duration::from_millis(250));
    loop {
        tick.tick().await;
        if !active(&STATE.lock()) {
            break;
        }
        let snap = connect::playback_snapshot();
        let local = snap.source == PlaybackSource::Local || snap.offline;
        let Some(track) = snap.track.clone().filter(|t| !t.is_episode && local) else {
            if !local && snap.source == PlaybackSource::Remote {
                STATE.lock().status = "Vibe DJ mixes on this phone: play here to use it".into();
            } else if snap.track.as_ref().is_some_and(|t| t.is_episode) {
                STATE.lock().status = "Vibe DJ mixes music: play a song to carry on".into();
            }
            if let Some(m) = mixer::current() {
                if m.engine.lock().phase == Phase::Pass {
                    mixer::stop();
                }
            }
            emit(false);
            continue;
        };
        let m = mixer::current().unwrap_or_else(mixer::start);
        let a_uri = track.uri.clone();
        let pos = position(&snap);

        // The engine's news.
        let (phase, report) = {
            let mut e = m.engine.lock();
            let r = e.report.clone();
            e.report.missed = false;
            e.report.done = false;
            (e.phase, r)
        };
        let mut actions: Vec<(&'static str, Value)> = Vec::new();
        {
            let mut st = STATE.lock();
            if report.missed {
                log::info!("vibe dj: mix missed (skip, seek or late), planning again");
                st.planned = None;
                st.plan_rev += 1;
                if let Some(d) = deck_b.as_ref() {
                    d.stop();
                }
            }
            if report.done {
                log::info!("vibe dj: handoff done, the main player plays {a_uri}");
                if let Some(p) = st.planned.take() {
                    st.history.push(p.pick.uri.clone());
                    st.artists.push(p.b_artist.clone());
                }
                st.plan_rev += 1;
                st.asap = false;
                if let Some(d) = deck_b.as_ref() {
                    d.stop();
                }
                if st.target.as_ref().is_some_and(|t| t.uri == a_uri) {
                    st.target = None;
                }
                st.locked = st.locked.take().filter(|l| *l != a_uri);
            }
            // A song change we didn't make (a skip, the queue, the context ended).
            if st.current.as_deref() != Some(a_uri.as_str()) {
                let ours = st.planned.as_ref().is_some_and(|p| p.pick.uri == a_uri || (p.handoff_tries > 0 && a_uri != p.a_uri));
                if !ours {
                    if matches!(phase, Phase::Armed | Phase::Mix) {
                        m.engine.lock().cancel();
                        if let Some(d) = deck_b.as_ref() {
                            d.stop();
                        }
                    }
                    if !matches!(phase, Phase::Settle | Phase::Handoff | Phase::Join | Phase::Glide) {
                        st.planned = None;
                        st.plan_rev += 1;
                    }
                    if st.history.last() != Some(&a_uri) {
                        st.history.push(a_uri.clone());
                        st.artists.push(track.artists.first().map(|a| a.name.clone()).unwrap_or_default());
                    }
                }
                st.current = Some(a_uri.clone());
                // Where the vocals are, for the next mixes (two never play at once).
                prefetch_vocals(std::iter::once(a_uri.clone()).chain(snap.next_tracks.iter().take(2).map(|t| t.uri.clone())).collect());
            }

            // Follow pause / play while deck B is the clock.
            if matches!(phase, Phase::Settle | Phase::Handoff) {
                let paused = snap.status == PlaybackStatus::Paused;
                if paused != st.b_paused {
                    st.b_paused = paused;
                    m.set_paused(paused);
                    if let Some(d) = deck_b.as_ref() {
                        if paused {
                            d.pause();
                        } else {
                            d.play();
                        }
                    }
                }
            } else if st.b_paused {
                st.b_paused = false;
                m.set_paused(false);
            }

            // Hand the song over to the main player, right as the mix ends (Spotify Connect shows the
            // current song until then): first skip to B (queued at the cue), then, once the main
            // player is on it, seek to where deck B is. Never a plain "next": it could land on the
            // song after.
            // Only while this mix is handing over: the engine keeps `handoff_ready` set through the
            // glide back to 1×, and acting on it then skipped straight to the next planned song.
            if report.handoff_ready && matches!(phase, Phase::Settle | Phase::Handoff) {
                if let Some(p) = st.planned.as_mut().filter(|p| p.armed) {
                    // On B once the song changed after our skip (Spotify may play a relinked copy
                    // of B under another URI).
                    let on_b = a_uri == p.pick.uri || (p.handoff_tries > 0 && a_uri != p.a_uri);
                    if !on_b {
                        let due = p.handoff_at.is_none_or(|t| t.elapsed() > Duration::from_secs(3));
                        if due && p.handoff_tries < 5 {
                            p.handoff_at = Some(Instant::now());
                            p.handoff_tries += 1;
                            match snap.next_tracks.iter().find(|t| t.uri == p.pick.uri) {
                                Some(t) => {
                                    log::info!("vibe dj: handoff, skipping to {} (try {})", p.pick.uri, p.handoff_tries);
                                    actions.push(("queue.skipTo", json!({ "uid": t.uid })));
                                }
                                None => {
                                    log::info!("vibe dj: handoff, {} isn't queued: queueing it", p.pick.uri);
                                    actions.push(("queue.add", json!({ "uri": p.pick.uri })));
                                }
                            }
                        }
                    } else if !p.seek_sent {
                        p.seek_sent = true;
                        let to = (report.b_pos_ms + 300.0).max(0.0) as u64;
                        log::info!("vibe dj: handoff, seeking {} to {to} ms", p.pick.uri);
                        actions.push(("player.seek", json!({ "positionMs": to })));
                    }
                }
            }

            // Plan the next mix.
            if st.planned.is_none() && phase == Phase::Pass && snap.status == PlaybackStatus::Playing {
                if let Some(p) = decide(&mut st, &snap, &a_uri, pos) {
                    let t = &p.pick.transition;
                    log::info!(
                        "vibe dj: next {} via {:?} at {:.1}s for {:.1}s, entry {:.1}s, speeds {:.3}/{:.3}",
                        p.pick.uri, t.style, t.mix_start_ms / 1000.0, t.length_ms / 1000.0, t.entry_ms / 1000.0, t.a_speed, t.speed
                    );
                    st.status = if p.pick.reasons.is_empty() { "Mix planned".into() } else { p.pick.reasons.join(" · ") };
                    st.planned = Some(p);
                    st.plan_rev += 1;
                }
            }

            // Cue deck B, then arm the mixer.
            let key_lock = st.prefs.key_lock;
            if let Some(p) = st.planned.as_mut().filter(|p| p.a_uri == a_uri) {
                let t = p.pick.transition.clone();
                if !p.cued && pos >= t.mix_start_ms - t.pre_glide_ms - CUE_LEAD_MS {
                    if t.mix_start_ms - t.pre_glide_ms < pos + 300.0 {
                        // Too late for this point: plan again.
                        st.planned = None;
                        st.plan_rev += 1;
                    } else {
                        let deck = deck_b.get_or_insert_with(DeckB::new);
                        deck.cue(&p.pick.uri, t.entry_ms);
                        p.cued = true;
                        // Queued only now (a swap before this leaves nothing behind in the queue), so
                        // the main player can take it over at the handoff.
                        if !snap.next_tracks.iter().any(|x| x.uri == p.pick.uri) {
                            actions.push(("queue.add", json!({ "uri": p.pick.uri })));
                        }
                    }
                } else if p.cued && !p.armed {
                    let primed = m.ring_b.buffered_frames() >= 44_100;
                    if primed || pos >= t.mix_start_ms - t.pre_glide_ms - 1_500.0 {
                        log::info!("vibe dj: armed (deck B primed: {primed})");
                        // A pitched song needs the key-locked stretcher (pitch apart from tempo).
                        let key_lock = key_lock || t.pitch_semitones != 0.0;
                        m.engine.lock().arm(Armed {
                            a_track: Arc::from(p.a_uri.as_str()),
                            b_track: Arc::from(p.pick.uri.as_str()),
                            t,
                            key_lock,
                        });
                        p.armed = true;
                    }
                }
            }
        }
        for (method, args) in actions {
            if let Err(e) = connect::handle(method, args).await {
                log::warn!("vibe dj: {method} failed: {e}");
            }
        }
        emit(false);
    }
    // Off: hand the output back to the main player.
    if let Some(m) = mixer::current() {
        m.engine.lock().cancel();
    }
    mixer::stop();
    drop_deck(deck_b.take()).await;
    analysis.abort();
    *ANALYSIS.lock().await = None;
    emit(true);
}

async fn analysis_loop() {
    loop {
        let next = {
            let mut st = STATE.lock();
            if !active(&st) {
                return;
            }
            st.queue.pop_front()
        };
        match next {
            Some(uri) if store::get(&uri).is_none() => {
                if analyze(&uri, false).await {
                    prefetch_vocals(vec![uri]);
                }
            }
            Some(_) => {}
            None => tokio::time::sleep(Duration::from_millis(500)).await,
        }
    }
}

fn track_json(uri: &str, name: &str, artist: &str, a: &Analysis, full: bool) -> Value {
    let mut v = json!({
        "uri": uri,
        "name": name,
        "artist": artist,
        "durationMs": a.duration_ms,
        "bpm": (a.bpm * 10.0).round() / 10.0,
        "key": key_name(a.key, a.minor),
        "camelot": a.camelot(),
        "meter": a.meter,
        "loudnessDb": a.loudness_db,
    });
    if full {
        v["barsMs"] = json!(a.bar_times_ms().iter().map(|t| t.round() as i64).collect::<Vec<_>>());
        v["energy"] = json!(a.intensity);
        let bars = a.bar_times_ms();
        v["phrases"] = json!(a
            .phrases
            .iter()
            .map(|p| json!({ "startMs": bars.get(p.start_bar as usize).map_or(0, |t| t.round() as i64), "bars": p.bars, "kind": p.kind }))
            .collect::<Vec<_>>());
    }
    v
}

fn state_json(full: bool) -> Value {
    let st = STATE.lock();
    let (phase, report) = mixer::current().map_or((Phase::Pass, mixer::Report::default()), |m| {
        let e = m.engine.lock();
        (e.phase, e.report.clone())
    });
    let mode = match st.mode {
        Mode::Off => "off",
        Mode::Dj => "dj",
        Mode::Crossfade { .. } => "crossfade",
    };
    let mut v = json!({
        "mode": mode,
        "status": st.status,
        "phase": phase,
        "progress": report.progress,
        "aPosMs": report.a_pos_ms.round() as i64,
        "bPosMs": report.b_pos_ms.round() as i64,
        "current": st.current,
        "next": st.planned.as_ref().map(|p| p.pick.uri.clone()),
        "analyzing": st.analyzing,
        "pending": st.queue.len(),
        "poolSize": st.pool.len(),
        "target": st.target.as_ref().map(|t| json!({ "uri": t.uri, "asap": t.asap, "path": t.path })),
        "locked": st.locked,
        "elapsedMs": st.started.elapsed().as_millis() as u64,
    });
    if full {
        v["prefs"] = serde_json::to_value(&st.prefs).unwrap_or(Value::Null);
        v["source"] = st.source.clone();
        v["plan"] = plan_json(&st);
    }
    v
}

fn plan_json(st: &State) -> Value {
    let Some(p) = st.planned.as_ref() else { return Value::Null };
    let snap = connect::playback_snapshot();
    let (a_name, a_artist) = snap
        .track
        .as_ref()
        .filter(|t| t.uri == p.a_uri)
        .map(|t| (t.name.clone().unwrap_or_default(), t.artists.first().map(|a| a.name.clone()).unwrap_or_default()))
        .unwrap_or_default();
    let a = store::get(&p.a_uri).map(|a| track_json(&p.a_uri, &a_name, &a_artist, &a, true));
    let b = store::get(&p.pick.uri).map(|b| track_json(&p.pick.uri, &p.b_name, &p.b_artist, &b, true));
    json!({
        "current": a.unwrap_or_else(|| json!({ "uri": p.a_uri, "name": a_name, "artist": a_artist, "durationMs": snap.duration_ms })),
        "next": b.unwrap_or_else(|| json!({ "uri": p.pick.uri, "name": p.b_name, "artist": p.b_artist })),
        "transition": p.pick.transition,
        "reasons": p.pick.reasons,
        "score": p.pick.score,
    })
}

/// `dj.state` four times a second while a mix runs (once a second otherwise), and `dj.plan`
/// whenever the plan changes.
fn emit(force: bool) {
    let (send_plan, due) = {
        let mut st = STATE.lock();
        let mixing = mixer::current().is_some_and(|m| m.engine.lock().phase != Phase::Pass);
        let due = force || mixing || st.last_emit.elapsed() >= Duration::from_secs(1);
        if due {
            st.last_emit = Instant::now();
        }
        let send_plan = st.sent_rev != st.plan_rev || force;
        st.sent_rev = st.plan_rev;
        (send_plan, due)
    };
    if send_plan {
        let plan = plan_json(&STATE.lock());
        events::emit("dj.plan", &plan);
    }
    if due || send_plan {
        events::emit("dj.state", &state_json(false));
    }
}
