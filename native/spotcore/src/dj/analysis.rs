//! Vibe DJ track analysis: tempo, beat grid, downbeats/meter, key (Camelot), loudness, per-bar
//! intensity, 8-bar phrases (intro / body / drop / breakdown / outro), a per-phrase groove (kick
//! and hi-hat pattern over 16 steps) and a coarse timbre vector, all from the PCM the player
//! decodes (44.1 kHz stereo f32), so it works offline on downloaded tracks.
//!
//! Cost: the signal is decimated to 11 025 Hz mono, then one 512-point FFT every 128 samples
//! (onsets, 86 fps) and one 4096-point FFT every 2048 samples (chroma, timbre). A 4-minute track
//! is about 0.15 s of CPU on a phone core on top of its decoding, and the result is ~0.5–3 KB.

use super::fft::Fft;
use base64::Engine as _;
use serde::{Deserialize, Serialize};

pub(crate) const RATE: f32 = 44_100.0;
const DECIM: usize = 4;
const SR: f32 = RATE / DECIM as f32;
const ON_N: usize = 512;
const ON_HOP: usize = 128;
const FPS: f32 = SR / ON_HOP as f32;
const CH_N: usize = 4096;
const CH_HOP: usize = 2048;
const TAPS: usize = 31;
/// Bump when the analysis changes: cached results of older versions are redone.
pub(crate) const VERSION: u32 = 1;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub(crate) enum Beats {
    /// A steady grid: beat `i` is at `offset_ms + i * period_ms`.
    Grid { offset_ms: f64, period_ms: f64, count: u32 },
    /// Beats that drift (live drums): deltas in ms from the previous beat (the first from 0).
    List(Vec<u32>),
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub(crate) enum PhraseKind {
    Intro,
    Body,
    Drop,
    Breakdown,
    Outro,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Phrase {
    pub start_bar: u32,
    pub bars: u32,
    pub kind: PhraseKind,
    /// Mean intensity 0–255.
    pub intensity: u8,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Analysis {
    pub v: u32,
    pub duration_ms: u32,
    pub bpm: f32,
    pub beats: Beats,
    /// Beats per bar (3 or 4).
    pub meter: u8,
    /// Index of the first downbeat in the beat list.
    pub first_downbeat: u32,
    /// Pitch class of the tonic (0 = C) and mode.
    pub key: u8,
    pub minor: bool,
    pub key_confidence: f32,
    pub loudness_db: f32,
    /// Per bar (from the first downbeat), 0–255.
    pub intensity: Vec<u8>,
    pub phrases: Vec<Phrase>,
    /// Per phrase, 16 kick steps then 16 hi-hat steps (0–255), base64.
    pub groove: String,
    pub timbre: Vec<f32>,
    pub audio_start_ms: u32,
    pub audio_end_ms: u32,
}

impl Analysis {
    pub(crate) fn beat_times_ms(&self) -> Vec<f64> {
        match &self.beats {
            Beats::Grid { offset_ms, period_ms, count } => {
                (0..*count).map(|i| offset_ms + f64::from(i) * period_ms).collect()
            }
            Beats::List(deltas) => {
                let mut t = 0.0;
                deltas.iter().map(|d| {
                    t += f64::from(*d);
                    t
                }).collect()
            }
        }
    }

    /// Start of each bar in ms (bar 0 starts at the first downbeat).
    pub(crate) fn bar_times_ms(&self) -> Vec<f64> {
        let beats = self.beat_times_ms();
        beats.iter().skip(self.first_downbeat as usize).step_by(self.meter.max(1) as usize).copied().collect()
    }

    pub(crate) fn camelot(&self) -> String {
        camelot(self.key, self.minor)
    }

    pub(crate) fn groove_of(&self, phrase: usize) -> Option<Vec<u8>> {
        let bytes = base64::engine::general_purpose::STANDARD_NO_PAD.decode(&self.groove).ok()?;
        bytes.get(phrase * 32..phrase * 32 + 32).map(<[u8]>::to_vec)
    }

    /// Mean intensity 0–1 between two times.
    pub(crate) fn intensity_between(&self, from_ms: f64, to_ms: f64) -> f32 {
        let bars = self.bar_times_ms();
        let picked: Vec<f32> = bars
            .iter()
            .enumerate()
            .filter(|(_, t)| **t >= from_ms - 1.0 && **t < to_ms)
            .filter_map(|(i, _)| self.intensity.get(i).map(|v| f32::from(*v) / 255.0))
            .collect();
        if picked.is_empty() {
            let all: f32 = self.intensity.iter().map(|v| f32::from(*v)).sum();
            return all / (self.intensity.len().max(1) as f32 * 255.0);
        }
        picked.iter().sum::<f32>() / picked.len() as f32
    }
}

/// Camelot wheel notation (C major = 8B, A minor = 8A).
pub(crate) fn camelot(key: u8, minor: bool) -> String {
    let major_pc = if minor { (key + 3) % 12 } else { key % 12 };
    let n = (usize::from(major_pc) * 7 % 12 + 7) % 12 + 1;
    format!("{n}{}", if minor { 'A' } else { 'B' })
}

const NAMES: [&str; 12] = ["C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"];

pub(crate) fn key_name(key: u8, minor: bool) -> String {
    format!("{}{}", NAMES[usize::from(key % 12)], if minor { "m" } else { "" })
}

struct OnsetFrame {
    flux: f32,
    low: f32,
    high: f32,
    rms: f32,
}

pub(crate) struct Analyzer {
    fir: Vec<f32>,
    hist: Vec<f32>,
    hist_pos: usize,
    phase: usize,
    mono: Vec<f32>,
    on_pos: usize,
    ch_pos: usize,
    on_fft: Fft,
    ch_fft: Fft,
    on_prev: Vec<f32>,
    on_mag: Vec<f32>,
    ch_mag: Vec<f32>,
    frames: Vec<OnsetFrame>,
    chroma: [f64; 12],
    bands: [f64; 8],
    centroid: f64,
    weight: f64,
    frames_in: u64,
    pc_of_bin: Vec<i8>,
    band_of_bin: Vec<i8>,
}

impl Default for Analyzer {
    fn default() -> Self {
        Self::new()
    }
}

impl Analyzer {
    pub(crate) fn new() -> Self {
        // Windowed-sinc low pass at 4.6 kHz for the 4:1 decimation.
        let fc = 4_600.0 / RATE;
        let m = (TAPS - 1) as f32 / 2.0;
        let mut fir: Vec<f32> = (0..TAPS)
            .map(|i| {
                let x = i as f32 - m;
                let sinc = if x == 0.0 { 2.0 * fc } else { (std::f32::consts::TAU * fc * x).sin() / (std::f32::consts::PI * x) };
                sinc * (0.54 - 0.46 * (std::f32::consts::TAU * i as f32 / (TAPS - 1) as f32).cos())
            })
            .collect();
        let sum: f32 = fir.iter().sum();
        fir.iter_mut().for_each(|v| *v /= sum);
        let bin_hz = SR / CH_N as f32;
        let pc_of_bin = (0..=CH_N / 2)
            .map(|k| {
                let f = k as f32 * bin_hz;
                if !(65.0..=2_100.0).contains(&f) {
                    return -1;
                }
                let midi = 69.0 + 12.0 * (f / 440.0).log2();
                (midi.round() as i32).rem_euclid(12) as i8
            })
            .collect();
        const EDGES: [f32; 9] = [40.0, 100.0, 200.0, 400.0, 800.0, 1_600.0, 3_200.0, 4_600.0, 5_512.0];
        let band_of_bin = (0..=CH_N / 2)
            .map(|k| {
                let f = k as f32 * bin_hz;
                EDGES.windows(2).position(|w| f >= w[0] && f < w[1]).map_or(-1, |b| b as i8)
            })
            .collect();
        Self {
            fir,
            hist: vec![0.0; TAPS],
            hist_pos: 0,
            phase: 0,
            mono: Vec::with_capacity(CH_N * 4),
            on_pos: 0,
            ch_pos: 0,
            on_fft: Fft::new(ON_N),
            ch_fft: Fft::new(CH_N),
            on_prev: vec![0.0; ON_N / 2 + 1],
            on_mag: vec![0.0; ON_N / 2 + 1],
            ch_mag: vec![0.0; CH_N / 2 + 1],
            frames: Vec::new(),
            chroma: [0.0; 12],
            bands: [0.0; 8],
            centroid: 0.0,
            weight: 0.0,
            frames_in: 0,
            pc_of_bin,
            band_of_bin,
        }
    }

    /// Duration of the PCM pushed so far.
    pub(crate) fn pushed_ms(&self) -> u64 {
        self.frames_in * 1000 / RATE as u64
    }

    /// Interleaved stereo 44.1 kHz samples.
    pub(crate) fn push(&mut self, interleaved: &[f32]) {
        for frame in interleaved.chunks_exact(2) {
            self.hist[self.hist_pos] = 0.5 * (frame[0] + frame[1]);
            self.hist_pos = (self.hist_pos + 1) % TAPS;
            self.phase += 1;
            if self.phase == DECIM {
                self.phase = 0;
                let mut acc = 0.0;
                for (i, c) in self.fir.iter().enumerate() {
                    acc += c * self.hist[(self.hist_pos + i) % TAPS];
                }
                self.mono.push(acc);
            }
        }
        self.frames_in += (interleaved.len() / 2) as u64;
        self.process();
    }

    fn process(&mut self) {
        while self.on_pos + ON_N <= self.mono.len() {
            let start = self.on_pos;
            self.on_fft.magnitudes(&self.mono[start..start + ON_N], &mut self.on_mag);
            let rms = (self.mono[start..start + ON_N].iter().map(|v| v * v).sum::<f32>() / ON_N as f32).sqrt();
            // Flux per octave band, averaged within the band so the many high bins (hats) don't
            // outweigh the few low ones (kick) in the beat envelope.
            let (mut flux, mut low, mut high) = (0.0, 0.0, 0.0);
            let mut band_sum = 0.0;
            let mut band_end = 4;
            let mut band_len = 0;
            for k in 1..self.on_mag.len() {
                let v = (1.0 + 100.0 * self.on_mag[k]).ln();
                let d = (v - self.on_prev[k]).max(0.0);
                self.on_prev[k] = v;
                band_sum += d;
                band_len += 1;
                if k + 1 == band_end || k + 1 == self.on_mag.len() {
                    flux += band_sum / band_len as f32;
                    band_sum = 0.0;
                    band_len = 0;
                    band_end *= 2;
                }
                // 21.5 Hz bins: kick below 150 Hz, hats above 3.5 kHz (Nyquist is 5.5 kHz).
                if k <= 7 {
                    low += d;
                } else if k >= 163 {
                    high += d;
                }
            }
            self.frames.push(OnsetFrame { flux, low, high, rms });
            self.on_pos += ON_HOP;
        }
        while self.ch_pos + CH_N <= self.mono.len() {
            let start = self.ch_pos;
            self.ch_fft.magnitudes(&self.mono[start..start + CH_N], &mut self.ch_mag);
            let energy: f32 = self.ch_mag.iter().map(|m| m * m).sum();
            if energy > 1e-6 {
                let w = f64::from(energy.sqrt());
                let mut chroma = [0.0f64; 12];
                let mut bands = [0.0f64; 8];
                let (mut num, mut den) = (0.0f64, 0.0f64);
                for (k, m) in self.ch_mag.iter().enumerate() {
                    let m = f64::from(*m);
                    if let Ok(pc) = usize::try_from(self.pc_of_bin[k]) {
                        chroma[pc] += m;
                    }
                    if let Ok(b) = usize::try_from(self.band_of_bin[k]) {
                        bands[b] += m * m;
                    }
                    num += k as f64 * m;
                    den += m;
                }
                let cs: f64 = chroma.iter().sum::<f64>().max(1e-9);
                let bs: f64 = bands.iter().sum::<f64>().max(1e-12);
                for i in 0..12 {
                    self.chroma[i] += w * chroma[i] / cs;
                }
                for i in 0..8 {
                    self.bands[i] += w * bands[i] / bs;
                }
                self.centroid += w * (num / den.max(1e-9)) / (CH_N / 2) as f64;
                self.weight += w;
            }
            self.ch_pos += CH_HOP;
        }
        // Keep what the next frames of both transforms still need.
        let keep_from = self.on_pos.min(self.ch_pos);
        if keep_from > CH_N * 2 {
            self.mono.drain(..keep_from);
            self.on_pos -= keep_from;
            self.ch_pos -= keep_from;
        }
    }

    /// The analysis of everything pushed, `None` for audio too short or silent to analyse.
    pub(crate) fn finish(self, duration_ms: u32) -> Option<Analysis> {
        let n = self.frames.len();
        if n < (FPS * 20.0) as usize {
            return None;
        }
        let frame_ms = |i: f64| (i * ON_HOP as f64 + ON_N as f64 / 2.0) * 1000.0 / f64::from(SR);
        let audible: Vec<usize> = (0..n).filter(|&i| self.frames[i].rms > 0.003).collect();
        let (first, last) = (*audible.first()?, *audible.last()?);
        let loud_power: f32 = audible.iter().map(|&i| self.frames[i].rms.powi(2)).sum::<f32>() / audible.len() as f32;
        let loudness_db = 10.0 * loud_power.max(1e-10).log10();

        // Onset envelope, mean-removed and scaled to unit deviation.
        let raw: Vec<f32> = self.frames.iter().map(|f| f.flux).collect();
        let env = normalized(&highpass(&raw, (FPS * 0.5) as usize));

        let period = tempo_period(&env)?;
        let beats = track_beats(&env, period);
        if beats.len() < 16 {
            return None;
        }
        let beat_ms: Vec<f64> = beats.iter().map(|b| frame_ms(*b as f64)).collect();
        let (offset, step, resid) = fit_grid(&beat_ms);
        let bpm = (60_000.0 / step) as f32;
        let beats_out = if resid < 15.0 {
            // Extend the grid over the audible part (the tracker may stop short of the edges).
            let mut off = offset;
            while off - step >= frame_ms(first as f64) - step * 0.5 {
                off -= step;
            }
            let count = ((frame_ms(last as f64) - off) / step).floor().max(0.0) as u32 + 1;
            Beats::Grid { offset_ms: off, period_ms: step, count }
        } else {
            let mut prev = 0.0;
            Beats::List(beat_ms.iter().map(|t| {
                let d = (t - prev).round().max(0.0) as u32;
                prev = *t;
                d
            }).collect())
        };
        let times = match &beats_out {
            Beats::Grid { offset_ms, period_ms, count } => (0..*count).map(|i| offset_ms + f64::from(i) * period_ms).collect(),
            Beats::List(_) => beat_ms.clone(),
        };
        let at = |t: f64| -> usize { ((t * f64::from(SR) / 1000.0 - ON_N as f64 / 2.0) / ON_HOP as f64).round().clamp(0.0, (n - 1) as f64) as usize };
        let low_at: Vec<f32> = times
            .iter()
            .map(|t| {
                let c = at(*t);
                (c.saturating_sub(2)..=(c + 2).min(n - 1)).map(|i| self.frames[i].low).fold(0.0, f32::max)
            })
            .collect();
        let meter = if contrast(&low_at, 3).0 > 1.3 * contrast(&low_at, 4).0 { 3 } else { 4 };
        let first_downbeat = contrast(&low_at, meter).1 as u32;

        // Bars, intensity and phrases.
        let bars: Vec<f64> = times.iter().skip(first_downbeat as usize).step_by(meter).copied().collect();
        let bar_len = step * f64::from(meter as u32);
        let mut flux_sorted: Vec<f32> = audible.iter().map(|&i| self.frames[i].flux).collect();
        flux_sorted.sort_by(f32::total_cmp);
        let flux_ref = flux_sorted[flux_sorted.len() * 9 / 10].max(1e-6);
        let mut intensity = Vec::with_capacity(bars.len());
        let mut grooves = Vec::with_capacity(bars.len());
        for t in &bars {
            let (a, b) = (at(*t), at(t + bar_len).max(at(*t) + 1));
            let span = &self.frames[a..b.min(n)];
            if span.is_empty() {
                break;
            }
            let p = span.iter().map(|f| f.rms * f.rms).sum::<f32>() / span.len() as f32;
            let db = 10.0 * p.max(1e-10).log10();
            let loud = ((db + 32.0) / 26.0).clamp(0.0, 1.0);
            let dens = (span.iter().map(|f| f.flux).sum::<f32>() / span.len() as f32 / flux_ref).clamp(0.0, 1.0);
            intensity.push(((0.65 * loud + 0.35 * dens) * 255.0).round() as u8);
            let mut g = [0.0f32; 32];
            for s in 0..16 {
                let st = at(t + bar_len * s as f64 / 16.0);
                let en = at(t + bar_len * (s + 1) as f64 / 16.0).max(st + 1).min(n);
                for f in &self.frames[st.min(n - 1)..en] {
                    g[s] = g[s].max(f.low);
                    g[16 + s] = g[16 + s].max(f.high);
                }
            }
            grooves.push(g);
        }
        let phrases = phrases(&intensity);
        let mut groove = Vec::with_capacity(phrases.len() * 32);
        for p in &phrases {
            let mut acc = [0.0f32; 32];
            for g in grooves.iter().skip(p.start_bar as usize).take(p.bars as usize) {
                acc.iter_mut().zip(g).for_each(|(a, v)| *a += v);
            }
            for half in acc.chunks_mut(16) {
                let max = half.iter().copied().fold(1e-9, f32::max);
                groove.extend(half.iter().map(|v| (v / max * 255.0).round() as u8));
            }
        }

        let (key, minor, key_confidence) = detect_key(&self.chroma);
        let w = self.weight.max(1e-9);
        let mut timbre: Vec<f32> = self.bands.iter().map(|b| (b / w) as f32).collect();
        timbre.push((self.centroid / w) as f32);
        timbre.push((raw.iter().sum::<f32>() / n as f32 / flux_ref).min(2.0));
        Some(Analysis {
            v: VERSION,
            duration_ms,
            bpm,
            beats: beats_out,
            meter: meter as u8,
            first_downbeat,
            key,
            minor,
            key_confidence,
            loudness_db,
            intensity,
            phrases,
            groove: base64::engine::general_purpose::STANDARD_NO_PAD.encode(groove),
            timbre,
            audio_start_ms: frame_ms(first as f64) as u32,
            audio_end_ms: (frame_ms(last as f64) as u32).min(duration_ms.max(1)),
        })
    }
}

fn highpass(x: &[f32], win: usize) -> Vec<f32> {
    let mut out = Vec::with_capacity(x.len());
    let mut sum = 0.0f64;
    let half = win / 2;
    let mut lo = 0;
    let mut hi = 0;
    for i in 0..x.len() {
        while hi < x.len() && hi <= i + half {
            sum += f64::from(x[hi]);
            hi += 1;
        }
        while lo + half < i {
            sum -= f64::from(x[lo]);
            lo += 1;
        }
        out.push((x[i] - (sum / (hi - lo) as f64) as f32).max(0.0));
    }
    out
}

fn normalized(x: &[f32]) -> Vec<f32> {
    let mean = x.iter().sum::<f32>() / x.len().max(1) as f32;
    let sd = (x.iter().map(|v| (v - mean).powi(2)).sum::<f32>() / x.len().max(1) as f32).sqrt().max(1e-9);
    x.iter().map(|v| v / sd).collect()
}

/// Beat period in onset frames: the autocorrelation peak weighted around 120 BPM, folded into
/// the 78–175 BPM range DJs count in.
fn tempo_period(env: &[f32]) -> Option<f64> {
    let (min_lag, max_lag) = ((FPS * 60.0 / 200.0) as usize, (FPS * 60.0 / 55.0) as usize + 1);
    if env.len() < max_lag * 4 {
        return None;
    }
    let ac: Vec<f64> = (0..=max_lag + 1)
        .map(|lag| env.iter().zip(&env[lag..]).map(|(a, b)| f64::from(a * b)).sum::<f64>() / (env.len() - lag) as f64)
        .collect();
    let weight = |lag: f64| {
        let bpm = 60.0 * f64::from(FPS) / lag;
        (-0.5 * ((bpm / 120.0).log2() / 1.0).powi(2)).exp()
    };
    let best = (min_lag..=max_lag).max_by(|&a, &b| (ac[a] * weight(a as f64)).total_cmp(&(ac[b] * weight(b as f64))))?;
    let (y0, y1, y2) = (ac[best - 1], ac[best], ac[best + 1]);
    let denom = y0 - 2.0 * y1 + y2;
    let mut lag = best as f64 + if denom.abs() > 1e-12 { 0.5 * (y0 - y2) / denom } else { 0.0 };
    let bpm = |lag: f64| 60.0 * f64::from(FPS) / lag;
    while bpm(lag) < 78.0 {
        lag /= 2.0;
    }
    while bpm(lag) > 175.0 {
        lag *= 2.0;
    }
    Some(lag)
}

/// Dynamic-programming beat tracker (Ellis 2007): beats on strong onsets spaced close to the
/// period.
fn track_beats(env: &[f32], period: f64) -> Vec<usize> {
    let n = env.len();
    let mut score = vec![0.0f64; n];
    let mut back = vec![usize::MAX; n];
    let (lo, hi) = ((period / 2.0).round() as usize, (period * 2.0).round() as usize);
    for t in 0..n {
        let mut best = 0.0;
        let mut arg = usize::MAX;
        for p in t.saturating_sub(hi)..t.saturating_sub(lo) {
            let d = (t - p) as f64 / period;
            let v = score[p] - 100.0 * d.ln().powi(2);
            if arg == usize::MAX || v > best {
                best = v;
                arg = p;
            }
        }
        score[t] = f64::from(env[t]) + if arg == usize::MAX { 0.0 } else { best };
        back[t] = arg;
    }
    let tail = n.saturating_sub(period.ceil() as usize);
    let mut t = (tail..n).max_by(|&a, &b| score[a].total_cmp(&score[b])).unwrap_or(n - 1);
    let mut beats = vec![t];
    while back[t] != usize::MAX {
        t = back[t];
        beats.push(t);
    }
    beats.reverse();
    beats
}

/// Least squares grid `offset + i * step` through the beats; (offset, step, rms residual) in ms.
fn fit_grid(beats: &[f64]) -> (f64, f64, f64) {
    let n = beats.len() as f64;
    let mean_i = (n - 1.0) / 2.0;
    let mean_t = beats.iter().sum::<f64>() / n;
    let (mut num, mut den) = (0.0, 0.0);
    for (i, t) in beats.iter().enumerate() {
        num += (i as f64 - mean_i) * (t - mean_t);
        den += (i as f64 - mean_i).powi(2);
    }
    let step = num / den.max(1e-9);
    let offset = mean_t - step * mean_i;
    let resid = (beats.iter().enumerate().map(|(i, t)| (t - offset - step * i as f64).powi(2)).sum::<f64>() / n).sqrt();
    (offset, step, resid)
}

/// How much one beat phase of `meter` stands out in `low` (the kick), and that phase.
fn contrast(low: &[f32], meter: usize) -> (f32, usize) {
    let mean = low.iter().sum::<f32>() / low.len().max(1) as f32;
    (0..meter)
        .map(|ph| {
            let v: Vec<f32> = low.iter().skip(ph).step_by(meter).copied().collect();
            (v.iter().sum::<f32>() / v.len().max(1) as f32 - mean, ph)
        })
        .max_by(|a, b| a.0.total_cmp(&b.0))
        .unwrap_or((0.0, 0))
}

fn phrases(intensity: &[u8]) -> Vec<Phrase> {
    let mut out: Vec<Phrase> = intensity
        .chunks(8)
        .enumerate()
        .map(|(i, c)| Phrase {
            start_bar: (i * 8) as u32,
            bars: c.len() as u32,
            kind: PhraseKind::Body,
            intensity: (c.iter().map(|v| u32::from(*v)).sum::<u32>() / c.len() as u32) as u8,
        })
        .collect();
    if out.is_empty() {
        return out;
    }
    let mut sorted: Vec<u8> = out.iter().map(|p| p.intensity).collect();
    sorted.sort_unstable();
    let peak = f32::from(sorted[sorted.len() * 9 / 10]).max(1.0);
    let rel = |p: &Phrase| f32::from(p.intensity) / peak;
    let n = out.len();
    let mut i = 0;
    while i < n.saturating_sub(1) && rel(&out[i]) < 0.8 && i < 3 {
        out[i].kind = PhraseKind::Intro;
        i += 1;
    }
    let mut j = n - 1;
    while j > i && rel(&out[j]) < 0.8 && n - j <= 3 {
        out[j].kind = PhraseKind::Outro;
        j -= 1;
    }
    for k in 1..n {
        if out[k].kind != PhraseKind::Body {
            continue;
        }
        let (prev, cur) = (rel(&out[k - 1]), rel(&out[k]));
        if cur >= 0.88 && cur - prev >= 0.1 {
            out[k].kind = PhraseKind::Drop;
        } else if cur <= 0.75 && prev >= 0.85 {
            out[k].kind = PhraseKind::Breakdown;
        }
    }
    out
}

/// Krumhansl–Kessler key profiles against the track's chroma: (tonic, minor, confidence 0–1).
fn detect_key(chroma: &[f64; 12]) -> (u8, bool, f32) {
    const MAJOR: [f64; 12] = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88];
    const MINOR: [f64; 12] = [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17];
    let pearson = |profile: &[f64; 12], tonic: usize| {
        let xs: Vec<f64> = (0..12).map(|i| chroma[(i + tonic) % 12]).collect();
        let (mx, my) = (xs.iter().sum::<f64>() / 12.0, profile.iter().sum::<f64>() / 12.0);
        let (mut n, mut dx, mut dy) = (0.0, 0.0, 0.0);
        for i in 0..12 {
            n += (xs[i] - mx) * (profile[i] - my);
            dx += (xs[i] - mx).powi(2);
            dy += (profile[i] - my).powi(2);
        }
        n / (dx * dy).sqrt().max(1e-12)
    };
    let mut all: Vec<(f64, u8, bool)> = (0..12)
        .flat_map(|t| [(pearson(&MAJOR, t), t as u8, false), (pearson(&MINOR, t), t as u8, true)])
        .collect();
    all.sort_by(|a, b| b.0.total_cmp(&a.0));
    let conf = ((all[0].0 - all[1].0) * 4.0).clamp(0.0, 1.0) as f32;
    (all[0].1, all[0].2, conf)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// A synthetic dance loop: kick on every beat, hat on the off-beats, accent on beat 1 and an
    /// A-minor triad pad; quiet intro and outro.
    pub(crate) fn synth(bpm: f64, seconds: f64, root_hz: f64) -> Vec<f32> {
        let n = (seconds * f64::from(RATE)) as usize;
        let beat = 60.0 / bpm;
        let mut out = Vec::with_capacity(n * 2);
        let mut noise = 12345u32;
        for i in 0..n {
            let t = i as f64 / f64::from(RATE);
            let b = t / beat;
            let ph = b.fract() * beat;
            let bar_beat = (b.floor() as u64) % 4;
            let level = if t < 16.0 || t > seconds - 16.0 { 0.35 } else { 1.0 };
            let accent = if bar_beat == 0 { 1.0 } else { 0.55 };
            let kick = (std::f64::consts::TAU * 55.0 * ph).sin() * (-ph * 18.0).exp() * accent;
            noise = noise.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            let hph = (b + 0.5).fract() * beat;
            let hat = (f64::from(noise >> 8) / f64::from(1u32 << 24) - 0.5) * (-hph * 60.0).exp() * 0.5;
            let pad: f64 = [1.0, 1.189_207, 1.498_307]
                .iter()
                .map(|r| (std::f64::consts::TAU * root_hz * r * t).sin() * 0.12)
                .sum();
            let s = ((kick * 0.8 + hat + pad) * level * 0.5) as f32;
            out.push(s);
            out.push(s);
        }
        out
    }

    pub(crate) fn analyze(pcm: &[f32]) -> Analysis {
        let mut a = Analyzer::new();
        for chunk in pcm.chunks(4096) {
            a.push(chunk);
        }
        let ms = ((pcm.len() / 2) as u64 * 1000 / RATE as u64) as u32;
        a.finish(ms).expect("analysis")
    }

    #[test]
    fn finds_tempo_downbeat_and_key() {
        let a = analyze(&synth(128.0, 90.0, 220.0));
        assert!((a.bpm - 128.0).abs() < 1.0, "bpm {}", a.bpm);
        assert!(matches!(a.beats, Beats::Grid { .. }));
        assert_eq!(a.meter, 4);
        // The first downbeat lands on an accented beat (a multiple of 4 beats from t=0).
        let first = a.beat_times_ms()[a.first_downbeat as usize];
        let beats_from_zero = first / (60_000.0 / 128.0);
        assert!((beats_from_zero.round() as i64) % 4 == 0, "downbeat at beat {beats_from_zero}");
        assert_eq!(key_name(a.key, a.minor), "Am");
        assert_eq!(a.camelot(), "8A");
        assert!(a.phrases.first().is_some_and(|p| p.kind == PhraseKind::Intro));
        assert!(a.phrases.last().is_some_and(|p| p.kind == PhraseKind::Outro));
        assert!(a.groove_of(0).is_some());
    }

    #[test]
    fn half_time_is_folded_into_range() {
        let a = analyze(&synth(174.0, 60.0, 261.63));
        assert!((a.bpm - 174.0).abs() < 1.5 || (a.bpm - 87.0).abs() < 1.0, "bpm {}", a.bpm);
    }

    #[test]
    fn camelot_wheel() {
        assert_eq!(camelot(0, false), "8B");
        assert_eq!(camelot(7, false), "9B");
        assert_eq!(camelot(9, true), "8A");
        assert_eq!(camelot(4, true), "9A");
    }

    #[test]
    fn serialises_small() {
        let a = analyze(&synth(124.0, 60.0, 220.0));
        let json = serde_json::to_string(&a).unwrap();
        assert!(json.len() < 4_000, "{} bytes", json.len());
        assert_eq!(serde_json::from_str::<Analysis>(&json).unwrap(), a);
    }
}
