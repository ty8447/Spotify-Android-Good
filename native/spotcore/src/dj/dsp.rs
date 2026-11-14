//! Vibe DJ signal processing at 44.1 kHz stereo: biquad EQ/filters (RBJ cookbook), a beat echo,
//! and the time stretcher that tempo-matches the incoming song (WSOLA with key lock, or a plain
//! resampler that shifts pitch like a turntable).

use std::collections::VecDeque;
use std::f32::consts::PI;

pub(crate) const SR: f32 = 44_100.0;

#[derive(Clone, Copy, PartialEq)]
enum Kind {
    LowShelf,
    HighShelf,
    /// A wide bell around the mids (the DJ mixer's MID knob).
    Peak,
    HighPass,
    LowPass,
}

#[derive(Clone)]
pub(crate) struct Biquad {
    kind: Kind,
    param: (f32, f32),
    b: [f32; 3],
    a: [f32; 2],
    z: [[f32; 2]; 2],
    active: bool,
}

impl Biquad {
    fn new(kind: Kind) -> Self {
        Self { kind, param: (f32::NAN, f32::NAN), b: [1.0, 0.0, 0.0], a: [0.0, 0.0], z: [[0.0; 2]; 2], active: false }
    }

    /// `freq` in Hz; `gain_db` for shelves. A flat setting (0 dB, or a pass filter at 0 Hz) is
    /// bypassed.
    fn set(&mut self, freq: f32, gain_db: f32) {
        if self.param == (freq, gain_db) {
            return;
        }
        self.param = (freq, gain_db);
        let flat = match self.kind {
            Kind::LowShelf | Kind::HighShelf | Kind::Peak => gain_db.abs() < 0.05,
            Kind::HighPass => freq < 15.0,
            Kind::LowPass => freq <= 0.0 || freq >= 19_000.0,
        };
        if flat {
            if self.active {
                self.z = [[0.0; 2]; 2];
            }
            self.active = false;
            return;
        }
        self.active = true;
        let w0 = 2.0 * PI * freq.clamp(10.0, 20_000.0) / SR;
        let (cw, sw) = (w0.cos(), w0.sin());
        let (b0, b1, b2, a0, a1, a2);
        match self.kind {
            Kind::HighPass | Kind::LowPass => {
                let alpha = sw / (2.0 * 0.707);
                let s = if self.kind == Kind::HighPass { 1.0 + cw } else { 1.0 - cw };
                let sign = if self.kind == Kind::HighPass { -1.0 } else { 1.0 };
                b0 = s / 2.0;
                b1 = sign * s;
                b2 = s / 2.0;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cw;
                a2 = 1.0 - alpha;
            }
            Kind::Peak => {
                let a = 10f32.powf(gain_db / 40.0);
                let alpha = sw / (2.0 * 0.6);
                b0 = 1.0 + alpha * a;
                b1 = -2.0 * cw;
                b2 = 1.0 - alpha * a;
                a0 = 1.0 + alpha / a;
                a1 = -2.0 * cw;
                a2 = 1.0 - alpha / a;
            }
            Kind::LowShelf | Kind::HighShelf => {
                let a = 10f32.powf(gain_db / 40.0);
                let alpha = sw / 2.0 * (2.0f32).sqrt();
                let k = 2.0 * a.sqrt() * alpha;
                let s = if self.kind == Kind::LowShelf { 1.0 } else { -1.0 };
                b0 = a * ((a + 1.0) - s * (a - 1.0) * cw + k);
                b1 = s * 2.0 * a * ((a - 1.0) - s * (a + 1.0) * cw);
                b2 = a * ((a + 1.0) - s * (a - 1.0) * cw - k);
                a0 = (a + 1.0) + s * (a - 1.0) * cw + k;
                a1 = -s * 2.0 * ((a - 1.0) + s * (a + 1.0) * cw);
                a2 = (a + 1.0) + s * (a - 1.0) * cw - k;
            }
        }
        self.b = [b0 / a0, b1 / a0, b2 / a0];
        self.a = [a1 / a0, a2 / a0];
    }

    #[inline]
    fn run(&mut self, ch: usize, x: f32) -> f32 {
        if !self.active {
            return x;
        }
        let z = &mut self.z[ch];
        let y = self.b[0] * x + z[0];
        z[0] = self.b[1] * x - self.a[0] * y + z[1];
        z[1] = self.b[2] * x - self.a[1] * y;
        y
    }
}

/// Mixer settings of one deck at a moment of a transition.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Fx {
    pub gain: f32,
    pub low_db: f32,
    /// The mids (around 1 kHz: voices, leads, chords).
    pub mid_db: f32,
    pub high_db: f32,
    /// High-pass cutoff in Hz (0 = off).
    pub hp: f32,
    /// Low-pass cutoff in Hz (0 = off).
    pub lp: f32,
}

impl Fx {
    pub(crate) const FLAT: Fx = Fx { gain: 1.0, low_db: 0.0, mid_db: 0.0, high_db: 0.0, hp: 0.0, lp: 0.0 };
    pub(crate) const MUTE: Fx = Fx { gain: 0.0, ..Fx::FLAT };
}

/// A deck's channel strip: a DJ mixer's three-band EQ (low and high shelves, a mid bell) and the
/// sweep filters.
#[derive(Clone)]
pub(crate) struct Strip {
    low: Biquad,
    mid: Biquad,
    high: Biquad,
    hp: Biquad,
    lp: Biquad,
    gain: f32,
    target: f32,
}

impl Default for Strip {
    fn default() -> Self {
        Self {
            low: Biquad::new(Kind::LowShelf),
            mid: Biquad::new(Kind::Peak),
            high: Biquad::new(Kind::HighShelf),
            hp: Biquad::new(Kind::HighPass),
            lp: Biquad::new(Kind::LowPass),
            gain: 1.0,
            target: 1.0,
        }
    }
}

impl Strip {
    pub(crate) fn set(&mut self, fx: Fx) {
        self.low.set(220.0, fx.low_db);
        self.mid.set(1_000.0, fx.mid_db);
        self.high.set(3_000.0, fx.high_db);
        self.hp.set(fx.hp, 0.0);
        self.lp.set(if fx.lp <= 0.0 { 20_000.0 } else { fx.lp }, 0.0);
        self.target = fx.gain;
    }

    #[inline]
    pub(crate) fn run(&mut self, l: f32, r: f32) -> (f32, f32) {
        // ~5 ms smoothing of gain steps (no zipper noise between automation updates).
        self.gain += (self.target - self.gain) * 0.005;
        let mut out = [l, r];
        for (ch, x) in out.iter_mut().enumerate() {
            let mut v = self.low.run(ch, *x);
            v = self.mid.run(ch, v);
            v = self.high.run(ch, v);
            v = self.hp.run(ch, v);
            v = self.lp.run(ch, v);
            *x = v * self.gain;
        }
        (out[0], out[1])
    }

    #[cfg(test)]
    pub(crate) fn snap_gain(&mut self) {
        self.gain = self.target;
    }
}

/// A small room reverb (Schroeder/Freeverb style: 4 damped combs and 2 all-passes per side),
/// wet only: the outgoing song's build-up wash.
pub(crate) struct Reverb {
    combs: [Vec<(Vec<f32>, usize, f32)>; 2],
    alls: [Vec<(Vec<f32>, usize)>; 2],
}

impl Default for Reverb {
    fn default() -> Self {
        let side = |spread: usize| {
            (
                [1116, 1188, 1277, 1356].iter().map(|n| (vec![0.0; n + spread], 0, 0.0)).collect::<Vec<_>>(),
                [556, 441].iter().map(|n| (vec![0.0; n + spread], 0)).collect::<Vec<_>>(),
            )
        };
        let (cl, al) = side(0);
        let (cr, ar) = side(23);
        Self { combs: [cl, cr], alls: [al, ar] }
    }
}

impl Reverb {
    /// The reverb of one input frame (wet only).
    #[inline]
    pub(crate) fn run(&mut self, (l, r): (f32, f32)) -> (f32, f32) {
        const FEEDBACK: f32 = 0.84;
        const DAMP: f32 = 0.25;
        let mut out = [0.0f32; 2];
        for (ch, x) in [l, r].into_iter().enumerate() {
            let x = x * 0.03;
            let mut acc = 0.0;
            for (buf, i, store) in self.combs[ch].iter_mut() {
                let y = buf[*i];
                *store = y * (1.0 - DAMP) + *store * DAMP;
                buf[*i] = x + *store * FEEDBACK;
                *i = (*i + 1) % buf.len();
                acc += y;
            }
            for (buf, i) in self.alls[ch].iter_mut() {
                let b = buf[*i];
                let y = b - acc;
                buf[*i] = acc + b * 0.5;
                *i = (*i + 1) % buf.len();
                acc = y;
            }
            out[ch] = acc * 2.5;
        }
        (out[0], out[1])
    }
}

/// Feedback delay for echo-outs (delay in frames, feedback, the tail high-passed at 250 Hz).
pub(crate) struct Echo {
    line: Vec<f32>,
    pos: usize,
    delay: usize,
    hp: Biquad,
}

impl Default for Echo {
    fn default() -> Self {
        let mut hp = Biquad::new(Kind::HighPass);
        hp.set(250.0, 0.0);
        Self { line: vec![0.0; 2 * 2 * SR as usize], pos: 0, delay: SR as usize / 4, hp }
    }
}

impl Echo {
    pub(crate) fn set_delay_ms(&mut self, ms: f64) {
        self.delay = ((ms / 1000.0 * f64::from(SR)) as usize).clamp(256, self.line.len() / 2 - 1);
    }

    #[inline]
    pub(crate) fn run(&mut self, send: (f32, f32), feedback: f32) -> (f32, f32) {
        let frames = self.line.len() / 2;
        let read = (self.pos + frames - self.delay) % frames;
        let (dl, dr) = (self.line[read * 2], self.line[read * 2 + 1]);
        let (fl, fr) = (self.hp.run(0, dl), self.hp.run(1, dr));
        self.line[self.pos * 2] = send.0 + fl * feedback;
        self.line[self.pos * 2 + 1] = send.1 + fr * feedback;
        self.pos = (self.pos + 1) % frames;
        (dl, dr)
    }
}

/// Supplies the stretcher with the incoming song's frames (interleaved), `None` when there are
/// none right now.
pub(crate) trait Feed {
    fn feed(&mut self, want_frames: usize) -> Option<Vec<f32>>;
}

const N: usize = 1024;
const HS: usize = N / 2;
const DELTA: usize = 256;

/// Plays the incoming song at a speed: WSOLA (overlap-add of windowed segments chosen to line up
/// with what was just played) keeps the pitch; without key lock a Catmull-Rom resampler shifts
/// it. At speed 1 both are sample exact, so the handoff can line the song up to the frame.
pub(crate) struct Stretch {
    key_lock: bool,
    buf: Vec<f32>,
    /// Source frame index of `buf[0]`.
    origin: u64,
    read: f64,
    nominal: f64,
    prev_start: Option<usize>,
    acc: Vec<f32>,
    window: Vec<f32>,
    out: VecDeque<f32>,
    /// Source frame of each emitted hop: (frames left in it, source index of its next frame).
    out_src: VecDeque<(usize, u64)>,
    pub underruns: u64,
    /// Key-locked only: pitch ratio applied on top (a resampler after WSOLA), and its read phase
    /// in the WSOLA output.
    pitch: f64,
    read2: f64,
}

impl Stretch {
    pub(crate) fn new(key_lock: bool) -> Self {
        let window = (0..N).map(|i| 0.5 - 0.5 * (2.0 * PI * i as f32 / N as f32).cos()).collect();
        Self {
            key_lock,
            buf: Vec::new(),
            origin: 0,
            read: 0.0,
            nominal: 0.0,
            prev_start: None,
            acc: vec![0.0; N * 2],
            window,
            out: VecDeque::new(),
            out_src: VecDeque::new(),
            underruns: 0,
            pitch: 1.0,
            read2: 0.0,
        }
    }

    /// Key-locked only: shifts the pitch by `ratio` (2^(semitones/12)) without changing the tempo.
    pub(crate) fn set_pitch(&mut self, ratio: f64) {
        self.pitch = ratio.clamp(0.7, 1.4);
    }

    /// Source frame index (from the first frame fed) of the next frame [`Stretch::next`] returns.
    pub(crate) fn source_frame(&self) -> f64 {
        if self.key_lock {
            self.out_src.front().map_or(self.origin as f64 + self.nominal, |(_, s)| *s as f64)
        } else {
            self.origin as f64 + self.read
        }
    }

    fn frames(&self) -> usize {
        self.buf.len() / 2
    }

    fn fill(&mut self, feed: &mut dyn Feed, need: usize) -> bool {
        while self.frames() < need {
            match feed.feed(need - self.frames()) {
                Some(v) if !v.is_empty() => self.buf.extend_from_slice(&v),
                _ => return false,
            }
        }
        true
    }

    fn trim(&mut self, keep_from: usize) {
        if keep_from > 4 * N {
            let cut = keep_from - 2 * N;
            self.buf.drain(..cut * 2);
            self.origin += cut as u64;
            self.read -= cut as f64;
            self.nominal -= cut as f64;
            if let Some(p) = self.prev_start.as_mut() {
                *p -= cut;
            }
        }
    }

    /// Resampler only: the fraction of a frame the next output frame lies past the first frame
    /// fed (to continue another stretcher's phase exactly).
    pub(crate) fn set_phase(&mut self, frac: f64) {
        self.read = frac.clamp(0.0, 0.999);
    }

    /// Whether the feed has the source for `frames` more output frames at `speed`
    /// (fetching it); false when it can't supply them now (nothing is consumed).
    pub(crate) fn ensure(&mut self, feed: &mut dyn Feed, frames: usize, speed: f64) -> bool {
        if self.key_lock {
            // Key-locked: the WSOLA output (before the pitch resampler) for `frames` more.
            let need = if (self.pitch - 1.0).abs() > 1e-6 || self.read2 > 0.0 {
                (self.read2 + frames as f64 * self.pitch).floor() as usize + 3
            } else {
                frames
            };
            self.produce(feed, speed / self.pitch, need * 2);
            return self.out.len() >= need * 2;
        }
        let need = (self.read + frames as f64 * speed).floor() as usize + 3;
        self.fill(feed, need)
    }

    /// The audio not played yet, emptying it (resampler: from the next whole source frame).
    pub(crate) fn drain(&mut self) -> Vec<f32> {
        if self.key_lock {
            // What was stretched already (the rest of the fetched source is dropped).
            self.buf.clear();
            return self.out.drain(..).collect();
        }
        let from = (self.read.ceil() as usize).min(self.frames());
        let rest = self.buf[from * 2..].to_vec();
        self.buf.clear();
        rest
    }

    /// Writes `out.len() / 2` frames at `speed`; frames the feed can't supply are silence.
    pub(crate) fn next(&mut self, feed: &mut dyn Feed, speed: f64, out: &mut [f32]) {
        if self.key_lock && ((self.pitch - 1.0).abs() > 1e-6 || self.read2 > 0.0) {
            self.next_pitched(feed, speed, out);
        } else if self.key_lock {
            self.next_wsola(feed, speed, out);
        } else {
            self.next_resample(feed, speed, out);
        }
    }

    fn next_resample(&mut self, feed: &mut dyn Feed, speed: f64, out: &mut [f32]) {
        for frame in out.chunks_exact_mut(2) {
            let i = self.read.floor() as usize;
            if !self.fill(feed, i + 3) {
                self.underruns += 1;
                frame.fill(0.0);
                continue;
            }
            let t = (self.read - i as f64) as f32;
            for ch in 0..2 {
                let p = |k: usize| self.buf[k * 2 + ch];
                let p0 = if i == 0 { p(0) } else { p(i - 1) };
                let (p1, p2, p3) = (p(i), p(i + 1), p(i + 2));
                frame[ch] = p1
                    + 0.5 * t * (p2 - p0 + t * (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3 + t * (3.0 * (p1 - p2) + p3 - p0)));
            }
            self.read += speed;
        }
        self.trim(self.read as usize);
    }

    /// WSOLA at `speed / pitch`, then a resampler at `pitch`: the pitch moves, the tempo is `speed`.
    fn next_pitched(&mut self, feed: &mut dyn Feed, speed: f64, out: &mut [f32]) {
        let pitch = self.pitch;
        let frames = out.len() / 2;
        let need = (self.read2 + frames as f64 * pitch).floor() as usize + 3;
        self.produce(feed, speed / pitch, need * 2);
        for frame in out.chunks_exact_mut(2) {
            let i = self.read2.floor() as usize;
            if (i + 3) * 2 > self.out.len() {
                self.underruns += 1;
                frame.fill(0.0);
                continue;
            }
            let t = (self.read2 - i as f64) as f32;
            for ch in 0..2 {
                let p = |k: usize| self.out[k * 2 + ch];
                let p0 = if i == 0 { p(0) } else { p(i - 1) };
                let (p1, p2, p3) = (p(i), p(i + 1), p(i + 2));
                frame[ch] = p1
                    + 0.5 * t * (p2 - p0 + t * (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3 + t * (3.0 * (p1 - p2) + p3 - p0)));
            }
            self.read2 += pitch;
        }
        // Keep one frame behind the read point for the next interpolation.
        let used = (self.read2.floor() as usize).saturating_sub(1).min(self.out.len() / 2);
        self.out.drain(..used * 2);
        self.read2 -= used as f64;
        self.consumed(used);
        if (self.pitch - 1.0).abs() <= 1e-6 && self.read2 < 1.0 + 1e-6 {
            // Back to plain WSOLA output (whole frames, nothing behind).
            self.out.drain(..(self.read2.floor() as usize * 2).min(self.out.len()));
            self.consumed(self.read2.floor() as usize);
            self.read2 = 0.0;
        }
    }

    /// Runs WSOLA at `speed` until `want` samples are waiting (or the feed runs dry).
    fn produce(&mut self, feed: &mut dyn Feed, speed: f64, want: usize) {
        while self.out.len() < want {
            let exact = (speed - 1.0).abs() < 1e-6;
            let target = self.nominal.round().max(0.0) as usize;
            let start = match self.prev_start {
                Some(prev) if exact => prev + HS,
                Some(prev) => {
                    if !self.fill(feed, target + DELTA + N) {
                        break;
                    }
                    self.best_offset(prev + HS, target)
                }
                None => target,
            };
            if !self.fill(feed, start + N) {
                break;
            }
            // The first segment starts at full level (no fade-in where nothing overlaps it).
            let first = self.prev_start.is_none();
            for i in 0..N {
                let w = if first && i < HS { 1.0 } else { self.window[i] };
                self.acc[i * 2] += w * self.buf[(start + i) * 2];
                self.acc[i * 2 + 1] += w * self.buf[(start + i) * 2 + 1];
            }
            self.out.extend(&self.acc[..HS * 2]);
            self.out_src.push_back((HS, self.origin + start as u64));
            self.acc.copy_within(HS * 2.., 0);
            self.acc[HS * 2..].iter_mut().for_each(|v| *v = 0.0);
            self.prev_start = Some(start);
            self.nominal = if exact { (start + HS) as f64 } else { self.nominal + speed * HS as f64 };
            self.trim(start);
        }
    }

    fn next_wsola(&mut self, feed: &mut dyn Feed, speed: f64, out: &mut [f32]) {
        let want = out.len();
        self.produce(feed, speed, want);
        let have = self.out.len().min(want);
        for (o, v) in out.iter_mut().zip(self.out.drain(..have)) {
            *o = v;
        }
        if have < want {
            self.underruns += 1;
            out[have..].fill(0.0);
        }
        self.consumed(have / 2);
    }

    /// `frames` of the WSOLA output were played: the source position moves on.
    fn consumed(&mut self, mut frames: usize) {
        while frames > 0 {
            let Some(front) = self.out_src.front_mut() else { break };
            let take = front.0.min(frames);
            front.0 -= take;
            front.1 += take as u64;
            frames -= take;
            if front.0 == 0 {
                self.out_src.pop_front();
            }
        }
    }

    /// The segment start near `target` that best continues the audio at `natural`.
    fn best_offset(&self, natural: usize, target: usize) -> usize {
        let mono = |i: usize| self.buf[i * 2] + self.buf[i * 2 + 1];
        let corr = |c: usize| {
            let (mut n, mut e) = (0.0f32, 1e-9f32);
            for k in (0..HS).step_by(4) {
                let x = mono(c + k);
                n += mono(natural + k) * x;
                e += x * x;
            }
            n / e.sqrt()
        };
        let lo = target.saturating_sub(DELTA);
        let mut best = (target, f32::MIN);
        for c in (lo..=target + DELTA).step_by(4) {
            let v = corr(c);
            if v > best.1 {
                best = (c, v);
            }
        }
        let (c0, mut top) = best;
        let mut arg = c0;
        for c in c0.saturating_sub(3)..=c0 + 3 {
            let v = corr(c);
            if v > top {
                top = v;
                arg = c;
            }
        }
        arg
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Vecs(VecDeque<f32>);
    impl Feed for Vecs {
        fn feed(&mut self, want: usize) -> Option<Vec<f32>> {
            let n = (want * 2).min(self.0.len()).max(if self.0.is_empty() { 0 } else { 2 });
            (n > 0).then(|| self.0.drain(..n).collect())
        }
    }

    fn tone(frames: usize) -> VecDeque<f32> {
        (0..frames).flat_map(|i| {
            let v = (2.0 * PI * 440.0 * i as f32 / SR).sin() * 0.5;
            [v, v]
        }).collect()
    }

    #[test]
    fn unit_speed_is_exact_in_both_modes() {
        for key_lock in [true, false] {
            let src = tone(20_000);
            let mut feed = Vecs(src.clone());
            let mut s = Stretch::new(key_lock);
            let mut out = vec![0.0; 4096 * 2];
            s.next(&mut feed, 1.0, &mut out);
            s.next(&mut feed, 1.0, &mut out);
            // The second block is frames 4096.. of the source (WSOLA's first hop is windowed in).
            let src: Vec<f32> = src.into_iter().collect();
            for i in 0..4096 {
                assert!((out[i * 2] - src[(4096 + i) * 2]).abs() < 1e-4, "key_lock {key_lock} frame {i}");
            }
            assert_eq!(s.source_frame() as usize, 8192);
        }
    }

    #[test]
    fn pitch_shift_keeps_the_tempo() {
        let mut feed = Vecs(tone(300_000));
        let mut s = Stretch::new(true);
        s.set_pitch(2f64.powf(1.0 / 12.0));
        let mut out = vec![0.0; 44_100 * 2];
        s.next(&mut feed, 1.0, &mut out);
        s.next(&mut feed, 1.0, &mut out);
        let src = s.source_frame();
        assert!((src / 88_200.0 - 1.0).abs() < 0.02, "{src}");
        assert_eq!(s.underruns, 0);
        // The pitch is higher: more zero crossings than the 440 Hz source in the same time.
        let crossings = out.chunks_exact(2).zip(out.chunks_exact(2).skip(1)).filter(|(a, b)| a[0] < 0.0 && b[0] >= 0.0).count();
        assert!((crossings as f64 / 440.0 - 2f64.powf(1.0 / 12.0)).abs() < 0.03, "{crossings}");
        // Back to its own pitch, then plain WSOLA again.
        s.set_pitch(1.0);
        s.next(&mut feed, 1.0, &mut out);
        s.next(&mut feed, 1.0, &mut out);
        assert_eq!(s.underruns, 0);
        assert!((s.source_frame() / 176_400.0 - 1.0).abs() < 0.02);
    }

    #[test]
    fn faster_speed_consumes_more_source() {
        for key_lock in [true, false] {
            let mut feed = Vecs(tone(200_000));
            let mut s = Stretch::new(key_lock);
            let mut out = vec![0.0; 44_100 * 2];
            s.next(&mut feed, 1.05, &mut out);
            let src = s.source_frame();
            assert!((src / 44_100.0 - 1.05).abs() < 0.02, "key_lock {key_lock}: {src}");
            assert_eq!(s.underruns, 0);
        }
    }

    #[test]
    fn shelves_cut_the_bass() {
        let mut strip = Strip::default();
        strip.set(Fx { low_db: -24.0, ..Fx::FLAT });
        strip.snap_gain();
        let mut peak = 0.0f32;
        for i in 0..44_100 {
            let x = (2.0 * PI * 60.0 * i as f32 / SR).sin();
            let (l, _) = strip.run(x, x);
            if i > 22_050 {
                peak = peak.max(l.abs());
            }
        }
        assert!(peak < 0.15, "{peak}");
    }
}
