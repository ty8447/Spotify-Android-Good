//! Vibe DJ mixer. While Vibe DJ (or crossfade) is on, the main player's sink pushes its PCM,
//! tagged with track and position, into ring A instead of the AudioTrack; deck B (a second
//! player) pushes the incoming song into ring B. One mixer thread renders both into the single
//! AudioTrack, so Bluetooth, the equalizer, ducking and Android Auto keep working unchanged.
//!
//! A transition (armed by the controller) starts sample-exactly when song A reaches the planned
//! downbeat. During the mix A is the clock; afterwards B is: B eases to its own tempo
//! ("settle"), then the main player is told to play B's song from where B is ("handoff"); its
//! frames are dropped until they line up with B's, and a 30 ms fade hands the output back to
//! the main player. Spotify Connect keeps showing the song the main player plays throughout.

use super::dsp::{Echo, Feed, Fx, Strip, Stretch};
use super::planner::{Style, Transition};
use crate::bridge;
use parking_lot::{Condvar, Mutex};
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

const FRAMES_PER_MS: f64 = 44.1;
const BLOCK: usize = 512;
const JOIN_FRAMES: usize = 1_323;
/// Ring A holds ~93 ms (latency added while the mixer runs); ring B primes ~3 s of the next song.
const RING_A_FRAMES: usize = 4_096;
const RING_B_FRAMES: usize = 44_100 * 3;

#[derive(Debug, Clone)]
pub(crate) struct Chunk {
    pub track: Arc<str>,
    pub pos_ms: f64,
    pub samples: Vec<f32>,
}

impl Chunk {
    fn frames(&self) -> usize {
        self.samples.len() / 2
    }
    fn end_ms(&self) -> f64 {
        self.pos_ms + self.frames() as f64 / FRAMES_PER_MS
    }
    /// Splits off and returns the first `frames`.
    fn take_front(&mut self, frames: usize) -> Chunk {
        let frames = frames.min(self.frames());
        let head = Chunk { track: self.track.clone(), pos_ms: self.pos_ms, samples: self.samples.drain(..frames * 2).collect() };
        self.pos_ms += frames as f64 / FRAMES_PER_MS;
        head
    }
}

/// Bounded PCM queue between a player thread (blocking writer) and the mixer thread.
pub(crate) struct Ring {
    inner: Mutex<RingInner>,
    cond: Condvar,
}

struct RingInner {
    q: VecDeque<Chunk>,
    frames: usize,
    cap: usize,
    closed: bool,
}

impl Ring {
    pub(crate) fn new(cap: usize) -> Arc<Self> {
        Arc::new(Self { inner: Mutex::new(RingInner { q: VecDeque::new(), frames: 0, cap, closed: false }), cond: Condvar::new() })
    }

    /// Queues a packet, waiting while the ring is full; false once it is closed.
    pub(crate) fn push(&self, track: Arc<str>, pos_ms: f64, samples: Vec<f32>) -> bool {
        let mut g = self.inner.lock();
        while g.frames >= g.cap && !g.closed {
            self.cond.wait_for(&mut g, Duration::from_millis(100));
        }
        if g.closed {
            return false;
        }
        g.frames += samples.len() / 2;
        g.q.push_back(Chunk { track, pos_ms, samples });
        self.cond.notify_all();
        true
    }

    pub(crate) fn pop(&self, max: usize, wait: Duration) -> Option<Chunk> {
        let mut g = self.inner.lock();
        if g.q.is_empty() && !wait.is_zero() && !g.closed {
            self.cond.wait_for(&mut g, wait);
        }
        let front = g.q.front_mut()?;
        let chunk = if front.frames() > max { front.take_front(max) } else { g.q.pop_front()? };
        g.frames -= chunk.frames();
        self.cond.notify_all();
        Some(chunk)
    }

    pub(crate) fn clear(&self) {
        let mut g = self.inner.lock();
        g.q.clear();
        g.frames = 0;
        self.cond.notify_all();
    }

    pub(crate) fn close(&self) {
        let mut g = self.inner.lock();
        g.closed = true;
        g.q.clear();
        g.frames = 0;
        self.cond.notify_all();
    }

    pub(crate) fn buffered_frames(&self) -> usize {
        self.inner.lock().frames
    }
}

/// Where the mixer reads a deck from.
pub(crate) trait Source: Send {
    fn pull(&mut self, max: usize, wait: Duration) -> Option<Chunk>;
}

impl Source for Arc<Ring> {
    fn pull(&mut self, max: usize, wait: Duration) -> Option<Chunk> {
        self.pop(max, wait)
    }
}

/// Deck B's frames for the stretcher, from the planned entry point on.
struct BFeed<'a> {
    src: &'a mut dyn Source,
    entry_ms: f64,
    started: bool,
}

impl Feed for BFeed<'_> {
    fn feed(&mut self, want: usize) -> Option<Vec<f32>> {
        loop {
            let mut c = self.src.pull(want.max(256), Duration::from_millis(30))?;
            if !self.started {
                if c.end_ms() <= self.entry_ms {
                    continue;
                }
                let skip = ((self.entry_ms - c.pos_ms) * FRAMES_PER_MS).round().max(0.0) as usize;
                c.take_front(skip);
                self.started = true;
            }
            return Some(c.samples);
        }
    }
}

/// The main player's frames for its stretcher: one track, contiguous. A different track or a jump
/// (a skip, a seek) marks the feed broken and is kept for the plain path.
struct AFeed<'a> {
    src: &'a mut dyn Source,
    pending: &'a mut Option<Chunk>,
    track: &'a str,
    next_ms: &'a mut f64,
    broken: &'a mut bool,
    wait: Duration,
}

impl Feed for AFeed<'_> {
    fn feed(&mut self, want: usize) -> Option<Vec<f32>> {
        let c = match self.pending.take() {
            Some(c) => c,
            None => self.src.pull(want.max(256), self.wait)?,
        };
        if *c.track != *self.track || (c.pos_ms - *self.next_ms).abs() > 40.0 {
            *self.broken = true;
            *self.pending = Some(c);
            return None;
        }
        *self.next_ms = c.end_ms();
        Some(c.samples)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum Phase {
    /// The main player plays straight through (a mix may be armed, waiting for its glide).
    Pass,
    /// The current song eases toward the meeting tempo before the mix.
    Armed,
    Mix,
    /// Key lock: the next song alone eases back to its own tempo before the handoff.
    Settle,
    /// The next song alone (deck B) while the main player is told to play it.
    Handoff,
    /// Deck B → main player, in line at the same tempo and phase.
    Join,
    /// The main player eases back to its own tempo.
    Glide,
}

#[derive(Debug, Clone)]
pub(crate) struct Armed {
    pub a_track: Arc<str>,
    pub b_track: Arc<str>,
    pub t: Transition,
    pub key_lock: bool,
}

/// What the mixer tells the controller (read under the same lock as the plan).
#[derive(Debug, Clone, Default)]
pub(crate) struct Report {
    pub progress: f32,
    pub a_pos_ms: f64,
    pub b_pos_ms: f64,
    /// The mix is over: play B's song on the main player from `b_pos_ms`.
    pub handoff_ready: bool,
    pub handoff_since: Option<Instant>,
    /// The main player took over (deck B can be stopped).
    pub done: bool,
    /// The plan could not run (A was past its start, A changed song): plan again.
    pub missed: bool,
}

/// One transition's worth of DSP state.
pub(crate) struct Engine {
    pub phase: Phase,
    pub armed: Option<Armed>,
    pub report: Report,
    /// The main player's stream while it is time-stretched (resampler).
    sa: Stretch,
    a_track: Arc<str>,
    a_base_ms: f64,
    a_next_ms: f64,
    a_pending: Option<Chunk>,
    a_broken: bool,
    /// A main player chunk the plain path plays first (left over by a cancelled stretch).
    carry: Option<Chunk>,
    sb: Stretch,
    b_started: bool,
    a: Strip,
    b: Strip,
    echo: Echo,
    echo_send: f32,
    post_frames: u64,
    join_done: usize,
}

impl Default for Engine {
    fn default() -> Self {
        Self {
            phase: Phase::Pass,
            armed: None,
            report: Report::default(),
            sa: Stretch::new(false),
            a_track: Arc::from(""),
            a_base_ms: 0.0,
            a_next_ms: 0.0,
            a_pending: None,
            a_broken: false,
            carry: None,
            sb: Stretch::new(false),
            b_started: false,
            a: Strip::default(),
            b: Strip::default(),
            echo: Echo::default(),
            echo_send: 0.0,
            post_frames: 0,
            join_done: 0,
        }
    }
}

fn smooth(x: f32) -> f32 {
    let x = x.clamp(0.0, 1.0);
    x * x * (3.0 - 2.0 * x)
}

fn smooth64(x: f64) -> f64 {
    let x = x.clamp(0.0, 1.0);
    x * x * (3.0 - 2.0 * x)
}

fn fade_in(x: f32) -> f32 {
    (x.clamp(0.0, 1.0) * std::f32::consts::FRAC_PI_2).sin()
}

fn fade_out(x: f32) -> f32 {
    (x.clamp(0.0, 1.0) * std::f32::consts::FRAC_PI_2).cos()
}

/// The decks' settings at progress `p` (0–1) of a transition, and the echo send of A. `beat` is
/// one beat as a share of the transition.
pub(crate) fn automation(style: Style, p: f32, beat: f32) -> (Fx, Fx, f32) {
    let beat = beat.clamp(0.01, 1.0);
    match style {
        Style::Crossfade => {
            // One bass at a time: it changes hands over two beats in the middle.
            let swap = smooth((p - 0.5) / (2.0 * beat) + 0.5);
            (
                Fx { gain: fade_out(p), low_db: -24.0 * swap, ..Fx::FLAT },
                Fx { gain: fade_in(p), low_db: -24.0 * (1.0 - swap), ..Fx::FLAT },
                0.0,
            )
        }
        Style::BassSwap => {
            // The bass changes hands over two beats around the middle, the rest blends smoothly.
            let swap = smooth((p - 0.5) / (2.0 * beat) + 0.5);
            let a = Fx {
                gain: if p < 0.55 { 1.0 } else { fade_out((p - 0.55) / 0.45) },
                low_db: -24.0 * swap,
                high_db: -6.0 * smooth((p - 0.4) / 0.6),
                ..Fx::FLAT
            };
            let b = Fx {
                gain: fade_in(p / 0.45),
                low_db: -24.0 * (1.0 - swap),
                high_db: -6.0 * (1.0 - smooth(p / 0.45)),
                ..Fx::FLAT
            };
            (a, b, 0.0)
        }
        Style::FilterSweep => {
            let a = Fx { gain: if p < 0.7 { 1.0 } else { fade_out((p - 0.7) / 0.3) }, hp: 20.0 * 60f32.powf(smooth(p)), ..Fx::FLAT };
            let open = smooth(p / 0.75);
            // B's bass waits until A's high-pass has taken A's out.
            let b = Fx {
                gain: fade_in(p / 0.35),
                lp: if open >= 1.0 { 0.0 } else { 300.0 * 66f32.powf(open) },
                low_db: -24.0 * (1.0 - smooth((p - 0.4) / 0.2)),
                ..Fx::FLAT
            };
            (a, b, 0.0)
        }
        Style::EchoOut => {
            let a = Fx { gain: if p < beat { fade_out(p / beat) } else { 0.0 }, ..Fx::FLAT };
            let b = Fx { gain: fade_in(p / (2.0 * beat)), low_db: -12.0 * (1.0 - smooth(p / 0.5)), ..Fx::FLAT };
            (a, b, if p < beat { 1.0 } else { 0.0 })
        }
        Style::Cut => (Fx { gain: 1.0 - p, ..Fx::FLAT }, Fx { gain: p, ..Fx::FLAT }, 0.0),
    }
}

impl Engine {
    /// Arms a transition (replacing one that hasn't started).
    pub(crate) fn arm(&mut self, armed: Armed) {
        if self.phase == Phase::Pass {
            log::debug!("mixer: armed {} into {}", armed.a_track, armed.b_track);
            self.sb = Stretch::new(armed.key_lock);
            self.b_started = false;
            self.echo.set_delay_ms(armed.t.beat_ms * 0.5);
            self.armed = Some(armed);
            self.report = Report::default();
        }
    }

    /// Drops the transition: A plays on as it is (deck B is stopped by the controller).
    pub(crate) fn cancel(&mut self) {
        if let Some(c) = self.a_pending.take() {
            self.carry.get_or_insert(c);
        }
        self.reset();
    }

    fn reset(&mut self) {
        // The handover is over: a stale `handoff_ready` made the controller skip ahead again.
        let report = Report { handoff_ready: false, handoff_since: None, ..std::mem::take(&mut self.report) };
        let carry = self.carry.take();
        *self = Engine { report, carry, ..Engine::default() };
    }

    /// Source position of the next frame of the main player's stretched stream.
    pub(crate) fn a_src_ms(&self) -> f64 {
        self.a_base_ms + self.sa.source_frame() / FRAMES_PER_MS
    }

    fn settle_len_frames(&self) -> u64 {
        let Some(armed) = self.armed.as_ref() else { return 0 };
        let mut ms = armed.t.settle_ms;
        if armed.t.gain_db.abs() > 0.5 {
            ms = ms.max(4_000.0);
        }
        if armed.t.style == Style::EchoOut {
            ms = ms.max(armed.t.beat_ms * 8.0);
        }
        (ms * FRAMES_PER_MS) as u64
    }

    /// The main player's speed while it is the clock before and during the mix.
    fn a_speed(&self) -> f64 {
        let Some(t) = self.armed.as_ref().map(|a| &a.t) else { return 1.0 };
        if self.phase == Phase::Mix || t.pre_glide_ms <= 0.0 {
            return t.a_speed;
        }
        let glide_start = t.mix_start_ms - t.pre_glide_ms;
        1.0 + (t.a_speed - 1.0) * smooth64((self.a_src_ms() - glide_start) / t.pre_glide_ms)
    }

    /// The incoming song's speed: matched during the mix, easing back to 1 after it.
    fn b_speed(&self) -> f64 {
        let Some(t) = self.armed.as_ref().map(|a| &a.t) else { return 1.0 };
        if self.phase == Phase::Mix {
            return t.speed;
        }
        let len = (t.settle_ms * FRAMES_PER_MS).max(1.0);
        let k = self.post_frames as f64 / len;
        if t.settle_ms <= 0.0 || k >= 1.0 {
            1.0
        } else {
            t.speed + (1.0 - t.speed) * smooth64(k)
        }
    }

    /// The level match of the incoming song, easing to unity after the mix.
    fn post_gain(&self) -> f32 {
        let target = 10f32.powf(self.armed.as_ref().map_or(0.0, |x| x.t.gain_db) / 20.0);
        if self.phase == Phase::Mix {
            return target;
        }
        let k = (self.post_frames as f32 / self.settle_len_frames().max(1) as f32).min(1.0);
        target + (1.0 - target) * k
    }

    /// The incoming song's pitch ratio: shifted during the mix, easing back with its tempo after it.
    fn b_pitch(&self) -> f64 {
        let Some(t) = self.armed.as_ref().map(|a| &a.t) else { return 1.0 };
        let shifted = 2f64.powf(f64::from(t.pitch_semitones) / 12.0);
        if matches!(self.phase, Phase::Armed | Phase::Mix) {
            return shifted;
        }
        let k = self.post_frames as f64 / (t.settle_ms * FRAMES_PER_MS).max(1.0);
        if t.settle_ms <= 0.0 || k >= 1.0 { 1.0 } else { shifted + (1.0 - shifted) * smooth64(k) }
    }

    fn b_frames(&mut self, b: &mut dyn Source, speed: f64, out: &mut [f32]) {
        let entry_ms = self.armed.as_ref().map_or(0.0, |a| a.t.entry_ms);
        let pitch = self.b_pitch();
        self.sb.set_pitch(pitch);
        let mut feed = BFeed { src: b, entry_ms, started: self.b_started };
        self.sb.next(&mut feed, speed, out);
        self.b_started = feed.started;
        self.report.b_pos_ms = entry_ms + self.sb.source_frame() / FRAMES_PER_MS;
    }

    /// Fetches the main player's source for `frames` more at `speed`; false if it has none now.
    fn a_ready(&mut self, a: &mut dyn Source, frames: usize, speed: f64) -> bool {
        let mut f = AFeed {
            src: a,
            pending: &mut self.a_pending,
            track: &self.a_track,
            next_ms: &mut self.a_next_ms,
            broken: &mut self.a_broken,
            wait: Duration::from_millis(50),
        };
        self.sa.ensure(&mut f, frames, speed)
    }

    fn a_frames(&mut self, a: &mut dyn Source, speed: f64, out: &mut [f32]) {
        let mut f = AFeed {
            src: a,
            pending: &mut self.a_pending,
            track: &self.a_track,
            next_ms: &mut self.a_next_ms,
            broken: &mut self.a_broken,
            wait: Duration::ZERO,
        };
        self.sa.next(&mut f, speed, out);
    }

    /// Starts stretching the main player at `chunk` (its first frame is `frac` frames late).
    fn stretch_a_from(&mut self, chunk: Chunk, frac: f64) {
        self.sa = Stretch::new(false);
        self.sa.set_phase(frac);
        self.a_track = chunk.track.clone();
        self.a_base_ms = chunk.pos_ms;
        self.a_next_ms = chunk.pos_ms;
        self.a_pending = Some(chunk);
        self.a_broken = false;
    }

    /// Renders the next block into `out` (cleared first). False when nothing was rendered (the
    /// clock has no data yet).
    pub(crate) fn render(&mut self, a: &mut dyn Source, b: &mut dyn Source, out: &mut Vec<f32>) -> bool {
        out.clear();
        match self.phase {
            Phase::Pass => self.render_pass(a, out),
            Phase::Armed | Phase::Mix => self.render_mix(a, b, out),
            Phase::Settle | Phase::Handoff => {
                self.render_b(a, b, out);
                true
            }
            Phase::Join | Phase::Glide => self.render_main(a, b, out),
        }
    }

    fn render_pass(&mut self, a: &mut dyn Source, out: &mut Vec<f32>) -> bool {
        let Some(mut chunk) = self.carry.take().or_else(|| a.pull(BLOCK, Duration::from_millis(20))) else { return false };
        self.report.a_pos_ms = chunk.end_ms();
        if let Some(armed) = self.armed.as_ref() {
            if *chunk.track == *armed.a_track {
                let glide_start = armed.t.mix_start_ms - armed.t.pre_glide_ms;
                if chunk.pos_ms > armed.t.mix_start_ms + 250.0 {
                    self.reset();
                    self.report.missed = true;
                } else if chunk.end_ms() > glide_start {
                    let k = ((glide_start - chunk.pos_ms) * FRAMES_PER_MS).ceil().max(0.0) as usize;
                    out.extend_from_slice(&chunk.take_front(k).samples);
                    self.stretch_a_from(chunk, 0.0);
                    self.phase = Phase::Armed;
                    return true;
                }
            }
        }
        out.extend_from_slice(&chunk.samples);
        true
    }

    /// Before and during the mix: the main player is the clock, through its stretcher.
    fn render_mix(&mut self, a: &mut dyn Source, b: &mut dyn Source, out: &mut Vec<f32>) -> bool {
        let Some(armed) = self.armed.clone() else { return false };
        let t = &armed.t;
        let beat = (t.beat_ms / t.length_ms.max(1.0)) as f32;
        const SUB: usize = 32;
        let mut abuf = [0.0f32; SUB * 2];
        let mut bbuf = [0.0f32; SUB * 2];
        while out.len() < BLOCK * 2 {
            let speed_a = self.a_speed();
            let ready = self.a_ready(a, SUB, speed_a);
            if self.a_broken {
                // A skip or a seek: drop the mix, the new audio plays as it is.
                out.extend_from_slice(&self.sa.drain());
                self.cancel();
                self.report.missed = true;
                return true;
            }
            if !ready {
                break;
            }
            let a_src = self.a_src_ms();
            if self.phase == Phase::Armed && a_src >= t.mix_start_ms - 0.01 {
                self.phase = Phase::Mix;
            }
            if self.phase == Phase::Mix {
                let p = ((a_src - t.mix_start_ms) / t.length_ms.max(1.0)) as f32;
                if p >= 1.0 {
                    self.end_mix();
                    break;
                }
                let (fa, fb, send) = automation(t.style, p, beat);
                self.a.set(fa);
                self.b.set(fb);
                self.echo_send = send;
                self.report.progress = p;
                let g = self.post_gain();
                self.b_frames(b, t.speed, &mut bbuf);
                bbuf.iter_mut().for_each(|v| *v *= g);
            } else {
                bbuf.fill(0.0);
            }
            self.a_frames(a, speed_a, &mut abuf);
            for i in 0..SUB {
                let (al, ar) = (abuf[i * 2], abuf[i * 2 + 1]);
                let (el, er) = self.echo.run((al * self.echo_send, ar * self.echo_send), 0.6);
                let (xl, xr) = self.a.run(al, ar);
                let (yl, yr) = if self.phase == Phase::Mix { self.b.run(bbuf[i * 2], bbuf[i * 2 + 1]) } else { (0.0, 0.0) };
                out.push((xl + yl + el).clamp(-1.0, 1.0));
                out.push((xr + yr + er).clamp(-1.0, 1.0));
            }
            self.report.a_pos_ms = self.a_src_ms();
        }
        !out.is_empty()
    }

    fn end_mix(&mut self) {
        self.post_frames = 0;
        self.a.set(Fx::MUTE);
        self.b.set(Fx::FLAT);
        self.report.progress = 1.0;
        self.sa = Stretch::new(false);
        self.a_pending = None;
        let key_lock = self.armed.as_ref().is_some_and(|x| x.key_lock);
        if key_lock {
            // WSOLA can't be continued sample exactly: B returns to its own tempo first.
            self.phase = Phase::Settle;
        } else {
            self.phase = Phase::Handoff;
            self.report.handoff_ready = true;
            self.report.handoff_since = Some(Instant::now());
        }
    }

    /// After the mix, deck B alone; the main player is dropped until it plays B in line.
    fn render_b(&mut self, a: &mut dyn Source, b: &mut dyn Source, out: &mut Vec<f32>) {
        let mut buf = vec![0.0f32; BLOCK * 2];
        let speed = self.b_speed();
        let g = self.post_gain();
        self.b_frames(b, speed, &mut buf);
        for f in buf.chunks_exact(2) {
            let (bl, br) = self.b.run(f[0] * g, f[1] * g);
            let (el, er) = self.echo.run((0.0, 0.0), 0.6);
            out.push((bl + el).clamp(-1.0, 1.0));
            out.push((br + er).clamp(-1.0, 1.0));
        }
        self.post_frames += BLOCK as u64;
        match self.phase {
            Phase::Settle => {
                // A plays on underneath until the handoff: keep it moving (dropped).
                let _ = a.pull(BLOCK, Duration::ZERO);
                if self.post_frames >= self.settle_len_frames() && self.b_speed() == 1.0 {
                    self.phase = Phase::Handoff;
                    self.report.handoff_ready = true;
                    self.report.handoff_since = Some(Instant::now());
                }
            }
            Phase::Handoff => self.line_up(a),
            _ => {}
        }
    }

    /// Drops the main player's frames until they reach B's position; then the join starts.
    fn line_up(&mut self, a: &mut dyn Source) {
        // B is whatever the main player plays after A (a relinked copy of B has another URI).
        let Some(a_track) = self.armed.as_ref().map(|x| x.a_track.clone()) else { return };
        let now = self.report.b_pos_ms;
        for _ in 0..64 {
            let Some(mut c) = self.carry.take().or_else(|| a.pull(8_192, Duration::ZERO)) else { return };
            if *c.track == *a_track || c.end_ms() <= now {
                continue;
            }
            if c.pos_ms <= now + 0.5 {
                let exact = ((now - c.pos_ms) * FRAMES_PER_MS).max(0.0);
                c.take_front(exact.floor() as usize);
                self.stretch_a_from(c, exact.fract());
                self.phase = Phase::Join;
                self.join_done = 0;
                return;
            }
            // Ahead of B: wait for B to get there.
            self.carry = Some(c);
            return;
        }
    }

    /// The main player as the clock again: the join from deck B, then the glide back to its own
    /// tempo; plain once at 1×.
    fn render_main(&mut self, a: &mut dyn Source, b: &mut dyn Source, out: &mut Vec<f32>) -> bool {
        let speed = self.b_speed();
        let g = self.post_gain();
        if !self.a_ready(a, BLOCK, speed) && !self.a_broken {
            return false;
        }
        if self.a_broken {
            out.extend_from_slice(&self.sa.drain());
            self.cancel();
            self.report.done = true;
            return true;
        }
        let mut abuf = vec![0.0f32; BLOCK * 2];
        self.a_frames(a, speed, &mut abuf);
        if self.phase == Phase::Join {
            let mut bbuf = vec![0.0f32; BLOCK * 2];
            self.b_frames(b, speed, &mut bbuf);
            for i in 0..BLOCK {
                let w = (self.join_done as f32 / JOIN_FRAMES as f32).min(1.0);
                let (bl, br) = self.b.run(bbuf[i * 2], bbuf[i * 2 + 1]);
                let (el, er) = self.echo.run((0.0, 0.0), 0.6);
                out.push(((abuf[i * 2] * w + bl * (1.0 - w)) * g + el).clamp(-1.0, 1.0));
                out.push(((abuf[i * 2 + 1] * w + br * (1.0 - w)) * g + er).clamp(-1.0, 1.0));
                self.join_done += 1;
            }
            if self.join_done >= JOIN_FRAMES {
                self.phase = Phase::Glide;
                self.report.done = true;
            }
        } else {
            for f in abuf.chunks_exact(2) {
                let (el, er) = self.echo.run((0.0, 0.0), 0.6);
                out.push((f[0] * g + el).clamp(-1.0, 1.0));
                out.push((f[1] * g + er).clamp(-1.0, 1.0));
            }
        }
        self.post_frames += BLOCK as u64;
        self.report.a_pos_ms = self.a_src_ms();
        if self.phase == Phase::Glide && speed == 1.0 && self.post_frames >= self.settle_len_frames() {
            // Back at 1×: the rest of the stretcher's source, then the plain path.
            out.extend_from_slice(&self.sa.drain());
            if let Some(c) = self.a_pending.take() {
                self.carry = Some(c);
            }
            let done = self.report.done;
            self.reset();
            self.report.done = done;
        }
        true
    }
}

// ---------------------------------------------------------------------------------------------
// The running mixer (process-wide)
// ---------------------------------------------------------------------------------------------

pub(crate) struct Mixer {
    pub ring_a: Arc<Ring>,
    pub ring_b: Arc<Ring>,
    pub engine: Mutex<Engine>,
    stop: AtomicBool,
    /// The AudioTrack runs (the main player started it; the mixer stops it only when idle).
    running: AtomicBool,
    a_stopped: AtomicBool,
    paused: AtomicBool,
    thread: Mutex<Option<std::thread::JoinHandle<()>>>,
}

static MIXER: Mutex<Option<Arc<Mixer>>> = parking_lot::const_mutex(None);
static EXITED: OnceLock<(Mutex<bool>, Condvar)> = OnceLock::new();

fn exited() -> &'static (Mutex<bool>, Condvar) {
    EXITED.get_or_init(|| (Mutex::new(true), Condvar::new()))
}

pub(crate) fn current() -> Option<Arc<Mixer>> {
    MIXER.lock().clone()
}

/// Starts the mixer thread (no-op if it runs).
pub(crate) fn start() -> Arc<Mixer> {
    let mut slot = MIXER.lock();
    if let Some(m) = slot.as_ref() {
        return m.clone();
    }
    let mixer = Arc::new(Mixer {
        ring_a: Ring::new(RING_A_FRAMES),
        ring_b: Ring::new(RING_B_FRAMES),
        engine: Mutex::new(Engine::default()),
        stop: AtomicBool::new(false),
        running: AtomicBool::new(true),
        a_stopped: AtomicBool::new(false),
        paused: AtomicBool::new(false),
        thread: Mutex::new(None),
    });
    *exited().0.lock() = false;
    let m = mixer.clone();
    let handle = std::thread::Builder::new().name("vibe-dj-mixer".into()).spawn(move || m.run());
    match handle {
        Ok(h) => *mixer.thread.lock() = Some(h),
        Err(e) => log::error!("mixer thread: {e}"),
    }
    *slot = Some(mixer.clone());
    log::info!("vibe dj mixer started");
    mixer
}

/// Stops the mixer: the main player's sink writes to the AudioTrack directly again.
pub(crate) fn stop() {
    let Some(m) = MIXER.lock().clone() else { return };
    m.stop.store(true, Ordering::Release);
    m.ring_a.close();
    m.ring_b.close();
    let handle = m.thread.lock().take();
    if let Some(h) = handle {
        let _ = h.join();
    }
    *MIXER.lock() = None;
    let (lock, cond) = exited();
    *lock.lock() = true;
    cond.notify_all();
    log::info!("vibe dj mixer stopped");
}

/// Main player sink: `true` when the mixer took the packet.
pub(crate) fn sink_write(pcm: Vec<f32>) -> Result<bool, Vec<f32>> {
    let Some(m) = current() else { return Err(pcm) };
    let (track, pos) = crate::engine::player_host::player()
        .and_then(|p| p.last_decoded())
        .map(|d| (Arc::<str>::from(d.track_id.to_uri().unwrap_or_default()), f64::from(d.position_ms)))
        .unwrap_or_else(|| (Arc::from(""), 0.0));
    m.a_stopped.store(false, Ordering::Release);
    if m.ring_a.push(track, pos, pcm) {
        return Ok(true);
    }
    // Closed while stopping: wait (bounded) for the mixer thread to stop writing first.
    let (lock, cond) = exited();
    let mut done = lock.lock();
    if !*done {
        cond.wait_for(&mut done, Duration::from_millis(300));
    }
    Ok(false)
}

/// Main player sink start / stop while the mixer runs (`true`: handled).
pub(crate) fn sink_started() -> bool {
    let Some(m) = current() else { return false };
    m.a_stopped.store(false, Ordering::Release);
    if !m.running.swap(true, Ordering::AcqRel) {
        if let Some(audio) = bridge::audio() {
            audio.start();
        }
    }
    true
}

pub(crate) fn sink_stopped() -> bool {
    let Some(m) = current() else { return false };
    m.a_stopped.store(true, Ordering::Release);
    true
}

impl Mixer {
    /// Pauses / resumes the output (the controller follows the main player's play state while B
    /// is the clock).
    pub(crate) fn set_paused(&self, paused: bool) {
        self.paused.store(paused, Ordering::Release);
        if !paused && !self.running.swap(true, Ordering::AcqRel) {
            if let Some(audio) = bridge::audio() {
                audio.start();
            }
        }
    }

    fn b_is_clock(&self) -> bool {
        matches!(self.engine.lock().phase, Phase::Settle | Phase::Handoff)
    }

    fn run(self: Arc<Self>) {
        let mut a: Arc<Ring> = self.ring_a.clone();
        let mut b: Arc<Ring> = self.ring_b.clone();
        let mut out = Vec::with_capacity(BLOCK * 4);
        while !self.stop.load(Ordering::Acquire) {
            let b_clock = self.b_is_clock();
            let idle = self.paused.load(Ordering::Acquire)
                || (!b_clock && self.a_stopped.load(Ordering::Acquire) && self.ring_a.buffered_frames() == 0);
            if idle {
                if self.running.swap(false, Ordering::AcqRel) {
                    if let Some(audio) = bridge::audio() {
                        audio.stop();
                    }
                }
                std::thread::sleep(Duration::from_millis(15));
                continue;
            }
            let rendered = self.engine.lock().render(&mut a, &mut b, &mut out);
            if !rendered || out.is_empty() {
                continue;
            }
            if !self.running.swap(true, Ordering::AcqRel) {
                if let Some(audio) = bridge::audio() {
                    audio.start();
                }
            }
            if let Some(audio) = bridge::audio() {
                if !audio.write_f32(&out) {
                    std::thread::sleep(Duration::from_millis(10));
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Tone {
        track: Arc<str>,
        pos_frames: u64,
        hz: f32,
        limit: u64,
    }
    impl Source for Tone {
        fn pull(&mut self, max: usize, _: Duration) -> Option<Chunk> {
            if self.pos_frames >= self.limit {
                return None;
            }
            let n = (max as u64).min(self.limit - self.pos_frames) as usize;
            let samples = (0..n)
                .flat_map(|i| {
                    let t = (self.pos_frames + i as u64) as f32 / 44_100.0;
                    let v = (std::f32::consts::TAU * self.hz * t).sin() * 0.3;
                    [v, v]
                })
                .collect();
            let c = Chunk { track: self.track.clone(), pos_ms: self.pos_frames as f64 / 44.1, samples };
            self.pos_frames += n as u64;
            Some(c)
        }
    }

    fn plan(style: Style, speed: f64, key_lock: bool) -> Transition {
        let (a_speed, b_speed, pre) = if key_lock { (1.0, speed, 0.0) } else { (1.0 / speed.sqrt(), speed.sqrt(), 2_000.0) };
        Transition {
            style,
            mix_start_ms: 3_000.0,
            length_ms: 2_000.0,
            beats: 4,
            entry_ms: 500.0,
            speed: b_speed,
            a_speed,
            pre_glide_ms: pre,
            settle_ms: 1_000.0,
            gain_db: 0.0,
            beat_ms: 500.0,
            pitch_semitones: 0.0,
        }
    }

    /// Runs a whole transition: the main player switches to B's song (from 0, behind deck B) once
    /// the handoff is asked for; returns how far apart the two streams were at the join.
    fn run_through(style: Style, key_lock: bool) -> f64 {
        run_through_as(style, key_lock, "b")
    }

    /// The main player may play B under a relinked URI: it must still line up, and end the handover.
    #[test]
    fn relinked_b_lines_up_and_clears_handoff() {
        assert!(run_through_as(Style::ALL[0], false, "b-relinked").is_finite());
    }

    fn run_through_as(style: Style, key_lock: bool, main_b_track: &str) -> f64 {
        let mut eng = Engine::default();
        eng.arm(Armed { a_track: "a".into(), b_track: "b".into(), t: plan(style, 1.03, key_lock), key_lock });
        let mut a = Tone { track: "a".into(), pos_frames: 0, hz: 220.0, limit: u64::MAX };
        let mut b = Tone { track: "b".into(), pos_frames: 0, hz: 330.0, limit: u64::MAX };
        let mut main_b = Tone { track: main_b_track.into(), pos_frames: 0, hz: 330.0, limit: u64::MAX };
        let mut out = Vec::new();
        let mut gap = f64::NAN;
        let mut seen_mix = false;
        for _ in 0..20_000 {
            let src: &mut dyn Source = if eng.report.handoff_ready { &mut main_b } else { &mut a };
            let before = eng.phase;
            eng.render(src, &mut b, &mut out);
            seen_mix |= eng.phase == Phase::Mix;
            if before == Phase::Handoff && eng.phase == Phase::Join {
                gap = (eng.a_src_ms() - eng.report.b_pos_ms).abs();
            }
            if eng.report.done && eng.phase == Phase::Pass {
                assert!(seen_mix, "{style:?}");
                assert!(!eng.report.handoff_ready, "{style:?}: handoff still flagged after the mix");
                return gap;
            }
        }
        panic!("{style:?} (key lock {key_lock}) never finished: {:?}", eng.phase);
    }

    #[test]
    fn mixes_glides_and_hands_off_in_line() {
        for style in Style::ALL {
            for key_lock in [false, true] {
                let gap = run_through(style, key_lock);
                assert!(gap < 0.05, "{style:?} key lock {key_lock}: {gap} ms apart");
            }
        }
    }

    #[test]
    fn a_skip_during_the_mix_cancels_it() {
        let mut eng = Engine::default();
        eng.arm(Armed { a_track: "a".into(), b_track: "b".into(), t: plan(Style::Crossfade, 1.0, false), key_lock: false });
        let mut a = Tone { track: "a".into(), pos_frames: 44_100 * 3, hz: 220.0, limit: u64::MAX };
        let mut b = Tone { track: "b".into(), pos_frames: 0, hz: 330.0, limit: u64::MAX };
        let mut out = Vec::new();
        eng.render(&mut a, &mut b, &mut out);
        eng.render(&mut a, &mut b, &mut out);
        assert_eq!(eng.phase, Phase::Mix);
        let mut other = Tone { track: "c".into(), pos_frames: 0, hz: 100.0, limit: u64::MAX };
        eng.render(&mut other, &mut b, &mut out);
        assert_eq!(eng.phase, Phase::Pass);
        assert!(eng.report.missed);
        // The new song's audio isn't lost.
        assert!(eng.render(&mut other, &mut b, &mut out));
    }

    #[test]
    fn the_current_song_eases_into_the_meeting_tempo() {
        let mut eng = Engine::default();
        let t = plan(Style::Crossfade, 1.04, false);
        let target = t.a_speed;
        eng.arm(Armed { a_track: "a".into(), b_track: "b".into(), t, key_lock: false });
        let mut a = Tone { track: "a".into(), pos_frames: 0, hz: 220.0, limit: u64::MAX };
        let mut b = Tone { track: "b".into(), pos_frames: 0, hz: 330.0, limit: u64::MAX };
        let mut out = Vec::new();
        let mut last = 1.0;
        while eng.phase != Phase::Mix {
            eng.render(&mut a, &mut b, &mut out);
            let s = eng.a_speed();
            assert!(s <= last + 1e-9, "the speed only eases one way");
            last = s;
        }
        assert!((eng.a_speed() - target).abs() < 1e-6);
    }

    #[test]
    fn automation_ends_on_b() {
        for style in Style::ALL {
            let (a, b, _) = automation(style, 1.0, 0.125);
            assert!(a.gain < 0.01, "{style:?}");
            assert!(b.gain > 0.99 && b.low_db.abs() < 0.01, "{style:?}");
        }
    }
}
