//! Vibe DJ planning: which song comes next, where the current one is mixed out, where the next
//! one comes in, how (style, length, tempo match), and routes to a requested target song.
//! Pure functions over [`Analysis`] results.

use super::analysis::{Analysis, PhraseKind};
use crate::catalog::mix::Vocals;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::sync::Arc;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum Style {
    Crossfade,
    BassSwap,
    FilterSweep,
    EchoOut,
    Cut,
}

impl Style {
    #[cfg(test)]
    pub(crate) const ALL: [Style; 5] = [Style::BassSwap, Style::Crossfade, Style::FilterSweep, Style::EchoOut, Style::Cut];
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", tag = "kind")]
pub(crate) enum Length {
    /// Play songs out, mixing in their outros.
    Full,
    /// Play a share of every song (25–50 % by default) and mix at a phrase in that window.
    Club { min: f32, max: f32 },
    /// About 30–60 s of each song.
    Quick,
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum EnergyArc {
    /// Stay near the energy of the song playing.
    Steady,
    /// Rise through the session.
    Build,
    /// Warm up, peak at 60 % of the session, cool down.
    Journey,
    /// Keep it high.
    Peak,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub(crate) struct Prefs {
    pub length: Length,
    pub harmonic: bool,
    pub key_lock: bool,
    pub styles: Vec<Style>,
    pub energy_arc: EnergyArc,
    pub session_minutes: u32,
    pub no_repeat: usize,
    /// Smallest beat-matched tempo change allowed (a ratio, 0.08 = ±8 %).
    pub max_tempo_shift: f32,
    /// Effects the mixes may lead in with.
    pub fx: Vec<OutFx>,
}

impl Default for Prefs {
    fn default() -> Self {
        Self {
            length: Length::Club { min: 0.25, max: 0.5 },
            harmonic: true,
            // Pitch follows tempo (like a turntable): both songs glide to meet, no WSOLA artifacts.
            key_lock: false,
            // Like a DJ: blends where the tempos meet, cuts on the phrase where they don't (or where
            // both songs are at full energy).
            styles: vec![Style::BassSwap, Style::Crossfade, Style::FilterSweep, Style::EchoOut, Style::Cut],
            energy_arc: EnergyArc::Journey,
            session_minutes: 60,
            no_repeat: 50,
            max_tempo_shift: 0.08,
            fx: OutFx::ALL.to_vec(),
        }
    }
}

#[derive(Debug, Clone)]
pub(crate) struct Candidate {
    pub uri: String,
    pub name: String,
    pub artist: String,
    pub analysis: Option<Arc<Analysis>>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Transition {
    pub style: Style,
    /// Position in the current song where the mix starts (a downbeat).
    pub mix_start_ms: f64,
    /// Length of the overlap, in the current song's time.
    pub length_ms: f64,
    pub beats: u32,
    /// Position in the next song when the mix starts (a downbeat).
    pub entry_ms: f64,
    /// Playback speed of the next song during the mix (1 = untouched).
    pub speed: f64,
    /// Speed the current song eases to before the mix (the tempos meet halfway), and over how
    /// long (in the current song's time) it eases.
    pub a_speed: f64,
    pub pre_glide_ms: f64,
    /// After the mix, the next song eases back to its own tempo over this time.
    pub settle_ms: f64,
    /// Gain applied to the next song to match loudness, eased to 0 dB by the handoff.
    pub gain_db: f32,
    /// Beat length of the current song (for echo and sweeps).
    pub beat_ms: f64,
    /// Semitones the next song is pitched by during the mix (keys meet), easing back after it.
    #[serde(default)]
    pub pitch_semitones: f32,
    /// The current song's pitch (semitones from its own) by the mix, reached over the pre-glide.
    #[serde(default)]
    pub a_pitch_semitones: f32,
    /// Whether the current song's pitch is set apart from its tempo (time-stretched).
    #[serde(default)]
    pub a_key_lock: bool,
    /// The effect leading the current song into the mix, and over how long before it.
    #[serde(default)]
    pub fx: OutFx,
    #[serde(default)]
    pub fx_ms: f64,
    /// Blends: where in it the bass changes hands (0–1, on a phrase downbeat).
    #[serde(default)]
    pub swap_at: f32,
}

/// What the outgoing song does in its last bars before a cut (the cheat sheet's FX).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum OutFx {
    /// A clean cut.
    #[default]
    None,
    /// Echo out: the last beat repeats and fades over the next song's instrumental start.
    Echo,
    /// Reverb swelling over the last two bars (a build-up's tension).
    Reverb,
    /// High-pass sweep over the last two bars (the bass and vocals thin out).
    Filter,
    /// Loop roll: the last bar's first beat repeats, then halves, then quarters.
    Loop,
    /// Riser: a bar of the playing song loops, then rolls in halves to sixteenths while it pitches
    /// up and thins out, landing on the next song's downbeat (for a lift, or notes that clash).
    Riser,
}

impl OutFx {
    pub(crate) const ALL: [OutFx; 5] = [OutFx::Filter, OutFx::Reverb, OutFx::Echo, OutFx::Loop, OutFx::Riser];
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Pick {
    pub uri: String,
    pub score: f32,
    pub transition: Transition,
    pub reasons: Vec<String>,
}

pub(crate) struct Context<'a> {
    pub current: &'a Candidate,
    pub position_ms: f64,
    pub history: &'a [String],
    pub recent_artists: &'a [String],
    /// 0–1 through the session, for the energy arc.
    pub session_progress: f32,
    /// Mix as soon as possible (route mode "As soon as possible", a skip).
    pub asap: bool,
}

/// Tempo factor to apply to `b` to reach `a`, trying double and half time; (speed, deviation).
pub(crate) fn tempo_match(a_bpm: f32, b_bpm: f32) -> (f64, f64) {
    [1.0, 2.0, 0.5]
        .iter()
        .map(|m| {
            let s = f64::from(a_bpm) / (f64::from(b_bpm) * m);
            (s, (s - 1.0).abs())
        })
        .min_by(|x, y| x.1.total_cmp(&y.1))
        .unwrap_or((1.0, 1.0))
}

/// Camelot wheel compatibility 0–1 (`shift` semitones applied to `b` first).
pub(crate) fn key_score(a: &Analysis, b: &Analysis, shift: i32) -> f32 {
    let num = |key: u8, minor: bool| -> i32 {
        let major_pc = if minor { (i32::from(key) + 3).rem_euclid(12) } else { i32::from(key) };
        (major_pc * 7).rem_euclid(12)
    };
    let (na, nb) = (num(a.key, a.minor), num(((i32::from(b.key) + shift).rem_euclid(12)) as u8, b.minor));
    let d = (na - nb).rem_euclid(12).min((nb - na).rem_euclid(12));
    let base = match (d, a.minor == b.minor) {
        (0, true) => 1.0,
        (0, false) => 0.85,
        (1, true) => 0.9,
        (1, false) => 0.6,
        (2, true) => 0.6,
        _ => 0.25,
    };
    let conf = a.key_confidence.min(b.key_confidence).max(0.3);
    1.0 - (1.0 - base) * conf
}

fn cosine(a: &[f32], b: &[f32]) -> f32 {
    let (mut n, mut x, mut y) = (0.0, 0.0, 0.0);
    for (p, q) in a.iter().zip(b) {
        n += p * q;
        x += p * p;
        y += q * q;
    }
    if x <= 0.0 || y <= 0.0 {
        return 0.5;
    }
    n / (x * y).sqrt()
}

fn groove(a: &Analysis, phrase: usize) -> Vec<f32> {
    a.groove_of(phrase).map(|g| g.iter().map(|v| f32::from(*v)).collect()).unwrap_or_default()
}

fn phrase_at_bar(a: &Analysis, bar: usize) -> usize {
    a.phrases.iter().rposition(|p| p.start_bar as usize <= bar).unwrap_or(0)
}

fn body_energy(a: &Analysis) -> f32 {
    let body: Vec<f32> = a
        .phrases
        .iter()
        .filter(|p| !matches!(p.kind, PhraseKind::Intro | PhraseKind::Outro))
        .map(|p| f32::from(p.intensity) / 255.0)
        .collect();
    if body.is_empty() {
        return a.intensity_between(0.0, f64::MAX);
    }
    body.iter().sum::<f32>() / body.len() as f32
}

pub(crate) fn target_energy(arc: EnergyArc, progress: f32, current: f32) -> f32 {
    let p = progress.clamp(0.0, 1.0);
    match arc {
        EnergyArc::Steady => current,
        EnergyArc::Build => 0.45 + 0.5 * p,
        EnergyArc::Peak => 0.88,
        EnergyArc::Journey if p < 0.6 => 0.45 + 0.45 * p / 0.6,
        EnergyArc::Journey => 0.9 - 0.4 * (p - 0.6) / 0.4,
    }
}

/// The mix-out window in the current song, as (from, to) in ms.
fn window(a: &Analysis, prefs: &Prefs, position_ms: f64, asap: bool) -> (f64, f64) {
    let d = f64::from(a.duration_ms);
    let end = f64::from(a.audio_end_ms).min(d);
    let (from, to) = match prefs.length {
        Length::Full => (d * 0.6, end),
        Length::Club { min, max } => (d * f64::from(min.min(max)), d * f64::from(max.max(min))),
        Length::Quick => (30_000.0f64.min(d * 0.3), 60_000.0f64.min(d * 0.6)),
    };
    let soonest = position_ms + if asap { 6_000.0 } else { 12_000.0 };
    if asap {
        return (soonest, end);
    }
    (from.max(soonest), to.max(soonest + 1.0).min(end))
}

/// Chooses the style, mix-out point and entry point for `a` → `b`. `None` when the current song
/// has no room left for a mix.
pub(crate) fn plan(a: &Analysis, b: &Analysis, prefs: &Prefs, position_ms: f64, asap: bool) -> Option<(Transition, f32)> {
    plan_with(a, b, prefs, position_ms, asap, (None, None))
}

/// [`plan`] knowing where each song has vocals (Spotify's vocal activity; the analysis' own lead
/// estimate where Spotify has none).
///
/// How club DJs do it (docs §6.9):
/// * Everything on the phrase: the mix starts on a phrase boundary of the playing song, the next
///   song comes in at one of its phrase starts, and the big moves (the bass swap, the cut) land on
///   a phrase downbeat.
/// * The default is the EQ blend: the next song's intro under the playing song's outro (or a
///   breakdown), its lows killed, highs then mids crossing over, the bass changing hands in one
///   beat on a phrase downbeat (when the next song's bassline arrives), then the playing song
///   leaving. 16 bars (8 when in a hurry, 32 when the grooves and keys are alike).
/// * One bass at a time and never two vocals / leads together: a blend whose overlap would put
///   leads on top of each other is dropped. Keys that clash only mildly get a filter blend (the
///   filter takes the melodic parts away).
/// * Otherwise a drop swap: a cut on the one where the playing song's section closes (after its
///   drop, into a break, or right after a momentary bass break), into the next song's drop or the
///   phrase that carries the energy on; most seamless when the drum patterns match. Tempos that
///   can't meet are echoed out. Effects only where they help (seasoning, not every mix).
/// * The playing song eases to the next one's tempo (and key, where they clash) before the mix;
///   the next song always plays as it is.
pub(crate) fn plan_with(
    a: &Analysis,
    b: &Analysis,
    prefs: &Prefs,
    position_ms: f64,
    asap: bool,
    vocals: (Option<&Vocals>, Option<&Vocals>),
) -> Option<(Transition, f32)> {
    let (speed, dev) = tempo_match(a.bpm, b.bpm);
    let matchable = dev <= f64::from(prefs.max_tempo_shift);
    let a_speed = if matchable { 1.0 / speed } else { 1.0 };
    // Pitch of the current song: with key lock its own, else following its tempo (a turntable);
    // a semitone or two more where that makes the keys meet (shifting A by s ~ B by -s).
    let base = if prefs.key_lock || !matchable { 0.0 } else { 12.0 * a_speed.log2() };
    let score_at = |s: f64| key_score(a, b, -(s.round() as i32));
    let mut a_pitch = base;
    let mut keys = score_at(base);
    let mut extra = 0;
    if prefs.harmonic && keys < 0.8 && matchable {
        for e in [-1i32, 1, -2, 2] {
            let k = score_at(base + f64::from(e));
            if k >= keys + 0.15 && (base + f64::from(e)).abs() <= 2.5 {
                keys = k;
                a_pitch = base + f64::from(e);
                extra = e;
            }
        }
    }
    let a_key_lock = prefs.key_lock || extra != 0;
    let a_bars = a.bar_times_ms();
    let b_bars = b.bar_times_ms();
    if a_bars.len() < 8 || b_bars.len() < 8 {
        return None;
    }
    let a_bar_ms = (a_bars[a_bars.len() - 1] - a_bars[0]) / (a_bars.len() - 1) as f64;
    let meter = u32::from(a.meter.max(1));
    let beat_ms = a_bar_ms / f64::from(meter);
    let (from, to) = window(a, prefs, position_ms, asap);
    let adjusting = (a_speed - 1.0).abs() > 0.0005 || a_pitch.abs() > 0.05;
    let pre_glide = if adjusting { (8.0 * a_bar_ms).clamp(4_000.0, 16_000.0) } else { 0.0 };
    let end = f64::from(a.audio_end_ms).min(f64::from(a.duration_ms));
    // The riser came after the other effects: preferences saved before it follow the loop roll.
    let fx_ok = |f: OutFx| prefs.fx.contains(&f) || (f == OutFx::Riser && prefs.fx.contains(&OutFx::Loop));
    // How well the notes meet (0–1; 0.6 unknown): bars of the playing song (as pitched) against
    // bars of the next. Pitch-class profiles correlated: the same notes agree, foreign ones clash.
    let (ca, cb) = (a.chroma_bars(), b.chroma_bars());
    let shift = a_pitch.round() as i32;
    let harmony = |ar: std::ops::Range<usize>, br: std::ops::Range<usize>| -> f32 {
        let mean = |c: &[[f32; 12]], r: std::ops::Range<usize>, rot: i32| -> Option<[f32; 12]> {
            let bars = c.get(r.start.min(c.len())..r.end.min(c.len()))?;
            (!bars.is_empty()).then(|| std::array::from_fn(|i| bars.iter().map(|b| b[(i as i32 - rot).rem_euclid(12) as usize]).sum::<f32>()))
        };
        let (Some(x), Some(y)) = (mean(&ca, ar, shift), mean(&cb, br, 0)) else { return 0.6 };
        let (mx, my) = (x.iter().sum::<f32>() / 12.0, y.iter().sum::<f32>() / 12.0);
        let (mut num, mut dx, mut dy) = (0.0, 0.0, 0.0);
        for i in 0..12 {
            num += (x[i] - mx) * (y[i] - my);
            dx += (x[i] - mx).powi(2);
            dy += (y[i] - my).powi(2);
        }
        if dx < 1e-9 || dy < 1e-9 { 0.6 } else { (num / (dx * dy).sqrt() + 1.0) / 2.0 }
    };
    // How alike the instruments are where the songs meet (0.75 unknown).
    let texture = |ar: std::ops::Range<usize>, br: std::ops::Range<usize>| {
        let v = |x: &Analysis, r| x.texture_over(r).map_or_else(Vec::new, |t| t.to_vec());
        super::analysis::texture_similarity(&v(a, ar), &v(b, br))
    };
    let blend_ok = |s: Style| prefs.styles.contains(&s);
    // Leads per bar: Spotify's vocal activity where known, else the analysis' estimate.
    let lead = |x: &Analysis, bars: &[f64], v: Option<&Vocals>, bar: usize| -> f32 {
        match (v, bars.get(bar), bars.get(bar + 1)) {
            (Some(v), Some(s), Some(e)) => v.between(*s, *e).max(x.lead_at(bar) * 0.5),
            _ => x.lead_at(bar),
        }
    };
    let a_lead = |bar: usize| lead(a, &a_bars, vocals.0, bar);
    let b_lead = |bar: usize| lead(b, &b_bars, vocals.1, bar);
    let mean_bass = |x: &Analysis, p: &super::analysis::Phrase| {
        (0..p.bars as usize).map(|k| x.bass_at(p.start_bar as usize + k)).sum::<f32>() / p.bars.max(1) as f32
    };
    // Where the next song's bassline is in for good, and its first drop.
    let b_main = b
        .phrases
        .iter()
        .find(|p| matches!(p.kind, PhraseKind::Body | PhraseKind::Drop) && mean_bass(b, p) > 0.45)
        .or_else(|| b.phrases.iter().find(|p| p.kind != PhraseKind::Intro))
        .map_or(0, |p| p.start_bar as usize);
    let b_drop = b.phrases.iter().find(|p| p.kind == PhraseKind::Drop).map_or(b_main, |p| p.start_bar as usize);
    let b_starts: Vec<usize> = b.phrases.iter().map(|p| p.start_bar as usize).filter(|s| *s < b_bars.len()).collect();
    let groove_sim = |a_bar: usize, b_bar: usize| cosine(&groove(a, phrase_at_bar(a, a_bar)), &groove(b, phrase_at_bar(b, b_bar))).clamp(0.0, 1.0);
    let lens: &[usize] = if asap {
        &[8]
    } else if matches!(prefs.length, Length::Quick) {
        &[8, 16]
    } else {
        &[16, 8, 32]
    };
    let mk = |style: Style, t: f64, length_ms: f64, bars: usize, entry_ms: f64, fx: OutFx, fx_ms: f64, swap_at: f32| Transition {
        style,
        mix_start_ms: t,
        length_ms,
        beats: bars as u32 * meter,
        entry_ms,
        speed: 1.0,
        a_speed,
        pre_glide_ms: pre_glide,
        settle_ms: 0.0,
        gain_db: (a.loudness_db - b.loudness_db).clamp(-6.0, 6.0),
        beat_ms,
        pitch_semitones: 0.0,
        a_pitch_semitones: a_pitch as f32,
        a_key_lock,
        fx,
        fx_ms,
        swap_at,
    };
    let mut best: Option<(Transition, f32)> = None;
    let keep = |t: Transition, q: f32, best: &mut Option<(Transition, f32)>| {
        if best.as_ref().is_none_or(|b| q > b.1) {
            *best = Some((t, q));
        }
    };
    for p in &a.phrases {
        let t_bar = p.start_bar as usize;
        let Some(&t) = a_bars.get(t_bar) else { continue };
        if t_bar == 0 || t < from || t > to || t > end - 2_000.0 {
            continue;
        }
        if t - pre_glide < position_ms + if asap { 1_000.0 } else { 3_000.0 } {
            continue;
        }
        let next_kind = p.kind;
        let prev_kind = a.phrase_of(t_bar - 1).map_or(PhraseKind::Body, |q| q.kind);
        let e_before = a.intensity_between(t - 4.0 * a_bar_ms, t);
        let e_after = a.intensity_between(t, t + 8.0 * a_bar_ms);
        // The playing song winds down from here (its outro or a breakdown).
        let winding = matches!(next_kind, PhraseKind::Outro | PhraseKind::Breakdown) || e_after < e_before - 0.1;
        // Later in the window is better for full songs; earlier for ASAP.
        let place = if asap { -((t - from) / 60_000.0) as f32 } else { ((t - from) / (to - from).max(1.0)) as f32 * 0.1 };

        // 1. The EQ blend.
        if matchable && (blend_ok(Style::BassSwap) || blend_ok(Style::FilterSweep)) {
            for &len in lens {
                let filter = keys < 0.6 || !blend_ok(Style::BassSwap);
                let len = if filter { len.min(8) } else { len };
                if t + len as f64 * a_bar_ms > end - 500.0 {
                    continue;
                }
                let half = len / 2;
                // The next song comes in at a phrase start so its bassline lands on a phrase
                // downbeat inside the blend (at the latest as the blend ends: its intro under the
                // current song's last phrase), as near halfway as it can.
                let e = b_starts
                    .iter()
                    .copied()
                    .filter(|s| *s <= b_main && b_main - s <= len)
                    .min_by_key(|s| (b_main - s).abs_diff(half))
                    .unwrap_or(0);
                let swap_rel = if b_main > e { ((b_main - e) / 4 * 4).clamp(4, len) } else { half.max(4) };
                // One bass at all times: the next song has its bass at the swap.
                if b.bass_at(e + swap_rel) < 0.3 {
                    continue;
                }
                // Two leads never at once while both are up in the mix.
                let both = (swap_rel * 3 / 10..(swap_rel + (len - swap_rel) * 7 / 10).min(len))
                    .filter(|k| a_lead(t_bar + k) > 0.5 && b_lead(e + k) > 0.5)
                    .count();
                if both >= 2 {
                    continue;
                }
                let overlap = (0..len).map(|k| a_lead(t_bar + k) * b_lead(e + k)).sum::<f32>() / len as f32;
                // Notes that clash under a long EQ blend: only a short filter blend hides them.
                let h = harmony(t_bar..t_bar + len, e..e + len);
                let tx = texture(t_bar..t_bar + len, e..e + len);
                let filter = filter || h < 0.4;
                if filter && len > 8 {
                    continue;
                }
                let gs = groove_sim(t_bar, e);
                let fit = 1.0
                    + if winding { 0.25 } else { 0.0 }
                    + if next_kind == PhraseKind::Drop { -0.45 } else { 0.0 }
                    + 0.25 * gs
                    + 0.2 * (keys - 0.7)
                    + 0.4 * (h - 0.6)
                    + 0.3 * (tx - 0.75)
                    - 0.5 * overlap
                    + match len {
                        16 => 0.05,
                        32 if gs > 0.8 && keys > 0.8 => 0.1,
                        32 => -0.1,
                        _ => 0.0,
                    };
                let style = if filter { Style::FilterSweep } else { Style::BassSwap };
                let t_out = mk(style, t, len as f64 * a_bar_ms, len, b_bars[e], OutFx::None, 0.0, swap_rel as f32 / len as f32);
                keep(t_out, fit + place, &mut best);
            }
        }

        // 2. The drop swap: a cut on the one.
        // Into the next song's drop after a high-energy section, else the phrase that carries the
        // energy on; either way where its first notes agree with the last ones playing.
        let entry_fit = |q: &super::analysis::Phrase| {
            let at = q.start_bar as usize;
            let energy = if e_before > 0.6 { if at == b_drop { 0.0 } else { 0.25 } } else { (f32::from(q.intensity) / 255.0 - (e_before + 0.05)).abs() };
            0.5 * harmony(t_bar.saturating_sub(2)..t_bar, at..at + 2) + 0.4 * texture(t_bar.saturating_sub(4)..t_bar, at..at + 4) - 2.0 * energy
        };
        let entry_bar = b
            .phrases
            .iter()
            .filter(|q| q.kind != PhraseKind::Outro)
            .max_by(|x, y| entry_fit(x).total_cmp(&entry_fit(y)))
            .map_or(0, |q| q.start_bar as usize);
        let Some(&entry_ms) = b_bars.get(entry_bar) else { continue };
        let h = harmony(t_bar.saturating_sub(2)..t_bar, entry_bar..entry_bar + 2);
        let tx = texture(t_bar.saturating_sub(4)..t_bar, entry_bar..entry_bar + 4);
        // Notes that clash, or a jump in instruments (a synth wall into a piano): masked.
        let clash = h < 0.42 || tx < 0.45;
        let b_e = b.intensity_between(entry_ms, entry_ms + 8.0 * a_bar_ms);
        // A momentary break (the bass out for the bar before) or a section closing: natural cuts.
        let break_before = a.bass_at(t_bar - 1) < 0.3 && a.bass_at(t_bar.saturating_sub(3)) > 0.5;
        let closing = winding || prev_kind == PhraseKind::Drop;
        let gs = groove_sim(t_bar - 1, entry_bar);
        let lead_cut = a_lead(t_bar - 1) > 0.6 && b_lead(entry_bar) > 0.5;
        let intense = e_before > 0.65 && b_e > 0.6;
        let lift = b_e > e_before + 0.15;
        let fx = if !matchable {
            if b_lead(entry_bar) < 0.4 && !clash && fx_ok(OutFx::Echo) { OutFx::Echo } else if fx_ok(OutFx::Filter) { OutFx::Filter } else { OutFx::None }
        } else if (clash || (lift && entry_bar == b_drop)) && b_e + 0.05 >= e_before && fx_ok(OutFx::Riser) {
            // A riser makes the change of notes the build-up; it lands on the next song's one.
            OutFx::Riser
        } else if (lead_cut || clash) && fx_ok(OutFx::Filter) {
            OutFx::Filter
        } else if b_e > e_before + 0.2 && fx_ok(OutFx::Reverb) {
            OutFx::Reverb
        } else if intense && gs < 0.5 && fx_ok(OutFx::Loop) {
            OutFx::Loop
        } else {
            OutFx::None
        };
        let fx_ms = match fx {
            OutFx::None => 0.0,
            OutFx::Echo => beat_ms,
            OutFx::Loop => a_bar_ms,
            OutFx::Filter | OutFx::Reverb => 2.0 * a_bar_ms,
            OutFx::Riser => 4.0 * a_bar_ms,
        };
        let fit = 0.8
            + if closing { 0.15 } else { -0.1 }
            + if break_before { 0.2 } else { 0.0 }
            + 0.2 * gs
            + 0.1 * (keys - 0.7)
            + 0.35 * (h - 0.6) * if fx == OutFx::None { 1.0 } else { 0.5 }
            + 0.4 * (tx - 0.75)
            - if e_before < 0.4 && b_e < 0.4 { 0.25 } else { 0.0 }
            + if !matchable { 0.2 } else { 0.0 };
        keep(mk(Style::Cut, t, 30.0, 0, entry_ms, fx, fx_ms, 0.5), fit + place, &mut best);
    }
    best.or_else(|| {
        // No phrase start left (played in late, or past the window): a cut on the next 4-bar line
        // of the phrase grid, the playing song at its own tempo and key, echoing out where allowed.
        let soonest = position_ms + if asap { 1_500.0 } else { 3_000.0 };
        let on_line = |i: usize| a.phrase_of(i).is_none_or(|p| (i - p.start_bar as usize) % 4 == 0);
        let ahead = || (1..a_bars.len()).filter(|&i| a_bars[i] >= soonest && a_bars[i] <= end - 1_000.0);
        let bar = ahead().find(|&i| on_line(i)).or_else(|| ahead().next())?;
        let t = a_bars[bar];
        let entry = if a.intensity_between(t - 4.0 * a_bar_ms, t) > 0.6 { b_drop } else { 0 };
        let echo = fx_ok(OutFx::Echo) && t - beat_ms > position_ms;
        let mut tr = mk(Style::Cut, t, 30.0, 0, *b_bars.get(entry)?, if echo { OutFx::Echo } else { OutFx::None }, if echo { beat_ms } else { 0.0 }, 0.5);
        tr.a_speed = 1.0;
        tr.pre_glide_ms = 0.0;
        tr.a_pitch_semitones = 0.0;
        tr.a_key_lock = prefs.key_lock;
        Some((tr, 0.2))
    })
}

/// How well `b` follows `a` (0–1), with the reasons a listener would care about.
pub(crate) fn score(a: &Analysis, b: &Analysis, prefs: &Prefs, target_energy: f32) -> (f32, Vec<String>) {
    let (speed, dev) = tempo_match(a.bpm, b.bpm);
    let tempo = (-(dev / 0.04).powi(2)).exp() as f32;
    let shift = if !prefs.key_lock { (12.0 * speed.log2()).round() as i32 } else { 0 };
    let keys = key_score(a, b, shift);
    let energy = 1.0 - (body_energy(b) - target_energy).abs().min(1.0);
    let timbre = cosine(&a.timbre, &b.timbre).clamp(0.0, 1.0);
    let last = a.phrases.len().saturating_sub(1);
    let first = b.phrases.iter().position(|p| p.kind != PhraseKind::Intro).unwrap_or(0);
    let groove = cosine(&groove(a, last), &groove(b, first)).clamp(0.0, 1.0);
    // An intro without leads to blend over; and on a drastic change (tempo or energy), a matching
    // drum pattern carries it so the cut isn't heard.
    let intro = 1.0 - (0..16).map(|k| b.lead_at(k)).sum::<f32>() / 16.0;
    let groove_w = if tempo < 0.5 || energy < 0.7 { 0.3 } else { 0.17 };
    let key_w = if prefs.harmonic { 0.22 } else { 0.05 };
    // The kind of instruments (synth pads against piano or mallets): a change of mood if it jumps.
    let texture = super::analysis::texture_similarity(&a.texture_mean, &b.texture_mean);
    let total = 0.28 * tempo + key_w * keys + 0.15 * energy + 0.06 * timbre + 0.14 * texture + groove_w * groove + 0.08 * intro;
    let score = total / (0.71 + groove_w + key_w);
    let mut reasons = Vec::new();
    if tempo > 0.8 {
        reasons.push(format!("{:.0} → {:.0} BPM", a.bpm, b.bpm));
    }
    if keys >= 0.85 {
        reasons.push(format!("{} → {} on the Camelot wheel", a.camelot(), b.camelot()));
    }
    if groove > 0.8 {
        reasons.push("similar beat pattern".into());
    }
    if intro > 0.85 {
        reasons.push("clean intro to mix over".into());
    }
    if texture > 0.88 {
        reasons.push("same kind of instruments".into());
    } else if timbre > 0.95 {
        reasons.push("same sound".into());
    }
    if energy > 0.85 {
        reasons.push(if body_energy(b) > body_energy(a) + 0.05 { "lifts the energy".into() } else { "keeps the energy".into() });
    }
    (score, reasons)
}

/// A first guess from Spotify's own tempo and key (0–1), before a song is analyzed: which songs
/// to analyze first, and what to cut to when the playing song runs out before any is.
pub(crate) fn quick_score(a_bpm: f32, a_camelot: Option<(u8, bool)>, b: crate::catalog::mix::Attrs, prefs: &Prefs) -> f32 {
    let (_, dev) = tempo_match(a_bpm, b.bpm);
    let max = f64::from(prefs.max_tempo_shift).max(0.01);
    let tempo = if dev <= max { 1.0 - 0.4 * dev / max } else { (0.5 - 4.0 * (dev - max)).max(0.0) } as f32;
    let keys = match (a_camelot, b.camelot) {
        (Some((na, ma)), Some((nb, mb))) => {
            let d = (i32::from(na) - i32::from(nb)).rem_euclid(12).min((i32::from(nb) - i32::from(na)).rem_euclid(12));
            match (d, ma == mb) {
                (0, true) => 1.0,
                (0, false) | (1, true) => 0.85,
                (1, false) | (2, true) => 0.55,
                _ => 0.25,
            }
        }
        _ => 0.6,
    };
    0.6 * tempo + 0.4 * keys
}

/// How many of the best-sounding songs get a full mix plan (the slow part) per pick.
const PLAN_TOP: usize = 12;

/// The best next song from `pool` (only analyzed candidates can be mixed).
pub(crate) fn pick_next(ctx: &Context, pool: &[Candidate], prefs: &Prefs) -> Option<Pick> {
    let a = ctx.current.analysis.as_ref()?;
    // Never a song already played this session; only when the pool has nothing else left, the
    // ones not heard in the last `no_repeat` songs.
    let played: HashSet<&str> = ctx.history.iter().map(String::as_str).collect();
    let recent: HashSet<&str> = ctx.history.iter().rev().take(prefs.no_repeat.max(1)).map(String::as_str).collect();
    pick_from(ctx, pool, prefs, a, &played).or_else(|| pick_from(ctx, pool, prefs, a, &recent))
}

fn pick_from(ctx: &Context, pool: &[Candidate], prefs: &Prefs, a: &Arc<Analysis>, exclude: &HashSet<&str>) -> Option<Pick> {
    let target = target_energy(prefs.energy_arc, ctx.session_progress, body_energy(a));
    // Two passes: the song-to-song score for everyone (cheap), then a full mix plan for the best
    // few (phrase by phrase, the slow part).
    let mut ranked: Vec<(&Candidate, &Arc<Analysis>, f32, Vec<String>)> = pool
        .iter()
        .filter(|c| c.uri != ctx.current.uri && !exclude.contains(c.uri.as_str()))
        .filter_map(|c| {
            let b = c.analysis.as_ref()?;
            let (mut s, reasons) = score(a, b, prefs, target);
            if c.artist == ctx.current.artist || ctx.recent_artists.iter().rev().take(2).any(|x| *x == c.artist) {
                s -= 0.12;
            }
            Some((c, b, s, reasons))
        })
        .collect();
    ranked.sort_by(|x, y| y.2.total_cmp(&x.2));
    let va = crate::catalog::mix::vocals_cached(&ctx.current.uri);
    ranked
        .into_iter()
        .take(PLAN_TOP)
        .filter_map(|(c, b, s, reasons)| {
            let vb = crate::catalog::mix::vocals_cached(&c.uri);
            let (transition, quality) = plan_with(a, b, prefs, ctx.position_ms, ctx.asap, (va.as_ref(), vb.as_ref()))?;
            // How well the mix itself works (phrases, notes meeting, no clashes) counts toward the pick.
            Some(Pick { uri: c.uri.clone(), score: s + 0.12 * quality.min(1.5), transition, reasons })
        })
        .max_by(|x, y| x.score.total_cmp(&y.score))
}

/// The songs to play from `from` to reach `target` (both included). `asap` keeps it short (few
/// songs, rougher mixes); otherwise the smoothest chain of at most 8 songs.
pub(crate) fn route(from: &Candidate, target: &Candidate, pool: &[Candidate], prefs: &Prefs, asap: bool) -> Vec<String> {
    let mut nodes: Vec<&Candidate> = vec![from];
    nodes.extend(pool.iter().filter(|c| c.analysis.is_some() && c.uri != from.uri && c.uri != target.uri));
    nodes.push(target);
    let n = nodes.len();
    let (Some(a0), Some(_)) = (from.analysis.as_ref(), target.analysis.as_ref()) else {
        return vec![from.uri.clone(), target.uri.clone()];
    };
    let hop = if asap { 1.0 } else { 0.12 };
    let max_hops = if asap { 3 } else { 8 };
    let mut dist = vec![f32::INFINITY; n];
    let mut hops = vec![0usize; n];
    let mut prev = vec![usize::MAX; n];
    let mut done = vec![false; n];
    dist[0] = 0.0;
    let _ = a0;
    for _ in 0..n {
        let Some(u) = (0..n).filter(|&i| !done[i] && dist[i].is_finite()).min_by(|&x, &y| dist[x].total_cmp(&dist[y])) else { break };
        done[u] = true;
        if u == n - 1 {
            break;
        }
        if hops[u] >= max_hops {
            continue;
        }
        let ua = nodes[u].analysis.as_ref().expect("analysed");
        for v in 0..n {
            if done[v] || v == u {
                continue;
            }
            let va = nodes[v].analysis.as_ref().expect("analysed");
            let e = target_energy(EnergyArc::Steady, 0.0, body_energy(va));
            let (s, _) = score(ua, va, prefs, e);
            let d = dist[u] + (1.0 - s).powi(2) * 3.0 + hop;
            if d < dist[v] {
                dist[v] = d;
                prev[v] = u;
                hops[v] = hops[u] + 1;
            }
        }
    }
    let mut path = Vec::new();
    let mut v = n - 1;
    if prev[v] == usize::MAX {
        return vec![from.uri.clone(), target.uri.clone()];
    }
    while v != usize::MAX {
        path.push(nodes[v].uri.clone());
        v = prev[v];
    }
    path.reverse();
    path
}

#[cfg(test)]
mod tests {
    use super::super::analysis::tests::{analyze, synth};
    use super::*;

    fn cand(uri: &str, bpm: f64, root: f64) -> Candidate {
        let a = analyze(&synth(bpm, 120.0, root));
        Candidate { uri: uri.into(), name: uri.into(), artist: uri.into(), analysis: Some(Arc::new(a)) }
    }

    #[test]
    fn prefs_from_the_app() {
        let p: Prefs = serde_json::from_str(
            r#"{"length":{"kind":"full","min":0.25,"max":0.5},"harmonic":false,"keyLock":true,"styles":["bassSwap","echoOut"],"energyArc":"build","sessionMinutes":45,"noRepeat":30,"maxTempoShift":0.06}"#,
        )
        .unwrap();
        assert_eq!(p.length, Length::Full);
        assert_eq!(p.styles, vec![Style::BassSwap, Style::EchoOut]);
        let c: Prefs = serde_json::from_str(r#"{"length":{"kind":"club","min":0.3,"max":0.6}}"#).unwrap();
        assert_eq!(c.length, Length::Club { min: 0.3, max: 0.6 });
        assert!(c.harmonic);
    }

    #[test]
    fn tempo_match_tries_double_time() {
        let (s, dev) = tempo_match(128.0, 64.0);
        assert!((s - 1.0).abs() < 1e-9 && dev < 1e-9);
        let (s, _) = tempo_match(126.0, 124.0);
        assert!((s - 126.0 / 124.0).abs() < 1e-9);
    }

    #[test]
    fn picks_the_compatible_song_and_plans_on_a_downbeat() {
        let a = cand("a", 126.0, 220.0);
        let pool = vec![cand("close", 124.0, 220.0), cand("far", 92.0, 233.08)];
        let ctx = Context { current: &a, position_ms: 0.0, history: &[], recent_artists: &[], session_progress: 0.3, asap: false };
        let pick = pick_next(&ctx, &pool, &Prefs::default()).expect("pick");
        assert_eq!(pick.uri, "close");
        let t = &pick.transition;
        let bars = a.analysis.as_ref().unwrap().bar_times_ms();
        assert!(bars.iter().any(|b| (b - t.mix_start_ms).abs() < 1.0), "mix starts on a bar");
        assert!(t.mix_start_ms >= 0.25 * 120_000.0 - 1.0 && t.mix_start_ms <= 0.5 * 120_000.0 + 1.0);
        // The current song eases to the next one's tempo; the next comes in as it is, with a cut
        // (no vocal data: a blend could put two vocals together).
        assert!((t.speed / t.a_speed - 126.0 / 124.0).abs() < 0.01, "speed {} / {}", t.speed, t.a_speed);
        assert!(t.a_speed < 1.0 && t.speed == 1.0 && t.pre_glide_ms > 0.0);
        assert_eq!(t.style, Style::Cut);
    }

    #[test]
    fn blends_on_the_phrase_by_default() {
        let a = cand("a", 125.0, 220.0);
        let b = cand("b", 125.0, 220.0);
        let (aa, bb) = (a.analysis.as_ref().unwrap(), b.analysis.as_ref().unwrap());
        let prefs = Prefs { length: Length::Full, ..Prefs::default() };
        let (t, _) = plan(aa, bb, &prefs, 0.0, false).unwrap();
        assert_eq!(t.style, Style::BassSwap, "{t:?}");
        // On A's phrase boundary, into one of B's phrase starts, the bass swapping on a 4-bar line.
        let at = |bars: &[f64], ms: f64| bars.iter().position(|x| (x - ms).abs() < 1.0).unwrap();
        let i = at(&aa.bar_times_ms(), t.mix_start_ms);
        assert!(aa.phrases.iter().any(|p| p.start_bar as usize == i));
        let j = at(&bb.bar_times_ms(), t.entry_ms);
        assert!(bb.phrases.iter().any(|p| p.start_bar as usize == j));
        let swap_bar = t.swap_at * (t.beats / u32::from(aa.meter)) as f32;
        assert!((swap_bar - swap_bar.round()).abs() < 1e-3 && swap_bar.round() as u32 % 4 == 0, "swap at bar {swap_bar}");
        // B's bass is in by the swap (one bass at all times).
        assert!(bb.bass_at(j + swap_bar.round() as usize) >= 0.3);
        assert_eq!(t.speed, 1.0);
    }

    #[test]
    fn a_late_start_still_finds_a_mix() {
        let a = cand("a", 125.0, 220.0);
        let b = cand("b", 125.0, 220.0);
        let (aa, bb) = (a.analysis.as_ref().unwrap(), b.analysis.as_ref().unwrap());
        // Past the last phrase start: a cut on the next 4-bar line, as the song is.
        let late = f64::from(aa.audio_end_ms) - 12_000.0;
        let (t, q) = plan(aa, bb, &Prefs::default(), late, false).unwrap();
        assert_eq!(t.style, Style::Cut);
        assert!(t.mix_start_ms > late && t.mix_start_ms < f64::from(aa.audio_end_ms), "{t:?}");
        assert!(q < 0.5 && t.a_speed == 1.0);
        // Nothing at all once the song is out.
        assert!(plan(aa, bb, &Prefs::default(), f64::from(aa.audio_end_ms), false).is_none());
    }

    #[test]
    fn quick_score_prefers_close_tempo_and_key() {
        use crate::catalog::mix::Attrs;
        let p = Prefs::default();
        let near = quick_score(124.0, Some((8, true)), Attrs { bpm: 126.0, camelot: Some((9, true)) }, &p);
        let far = quick_score(124.0, Some((8, true)), Attrs { bpm: 90.0, camelot: Some((2, false)) }, &p);
        assert!(near > 0.8 && far < 0.4, "{near} {far}");
        // Double time counts as a match.
        assert!(quick_score(70.0, None, Attrs { bpm: 140.0, camelot: None }, &p) > 0.75);
    }

    #[test]
    fn clashing_notes_never_blend_long() {
        let a = cand("a", 125.0, 220.0);
        // A semitone away: every note rubs.
        let b = cand("b", 125.0, 233.08);
        let (aa, bb) = (a.analysis.as_ref().unwrap(), b.analysis.as_ref().unwrap());
        let prefs = Prefs { length: Length::Full, harmonic: false, ..Prefs::default() };
        let (t, _) = plan(aa, bb, &prefs, 0.0, false).unwrap();
        match t.style {
            Style::Cut => assert!(matches!(t.fx, OutFx::Riser | OutFx::Filter), "{t:?}"),
            Style::FilterSweep => assert!(t.beats <= 8 * u32::from(aa.meter)),
            s => panic!("{s:?}: {t:?}"),
        }
        // With harmonic mixing the playing song pitches a semitone to meet it, and they blend.
        let (t, _) = plan(aa, bb, &Prefs { length: Length::Full, ..Prefs::default() }, 0.0, false).unwrap();
        assert!(t.a_pitch_semitones.abs() > 0.5, "{t:?}");
    }

    #[test]
    fn blends_only_where_one_song_is_instrumental() {
        let a = cand("a", 125.0, 220.0);
        let b = cand("b", 125.0, 220.0);
        let (aa, bb) = (a.analysis.as_ref().unwrap(), b.analysis.as_ref().unwrap());
        let prefs = Prefs { length: Length::Full, ..Prefs::default() };
        let sung = Vocals { start_ms: 0.0, step_ms: 500.0, prob: vec![90; 400] };
        let quiet = Vocals { start_ms: 0.0, step_ms: 500.0, prob: vec![0; 400] };
        let (t, _) = plan_with(aa, bb, &prefs, 0.0, false, (Some(&sung), Some(&sung))).unwrap();
        assert_eq!(t.style, Style::Cut, "two vocals never overlap");
        let blend = plan_with(aa, bb, &prefs, 0.0, false, (Some(&quiet), Some(&sung))).unwrap().0;
        // A blend is allowed now (an outro into the next song), and if so it swaps the bass.
        assert!(matches!(blend.style, Style::Cut | Style::BassSwap | Style::FilterSweep));
        assert_eq!(blend.speed, 1.0);
    }

    #[test]
    fn full_songs_mix_in_the_outro() {
        let a = cand("a", 125.0, 220.0);
        let b = cand("b", 125.0, 220.0);
        let prefs = Prefs { length: Length::Full, ..Prefs::default() };
        let (t, _) = plan(a.analysis.as_ref().unwrap(), b.analysis.as_ref().unwrap(), &prefs, 0.0, false).unwrap();
        assert!(t.mix_start_ms > 60_000.0);
        assert!(t.mix_start_ms + t.length_ms <= 120_000.0);
    }

    #[test]
    fn routes_through_a_bridge_song() {
        let a = cand("a", 100.0, 220.0);
        let bridge = cand("bridge", 112.0, 220.0);
        let target = cand("target", 124.0, 220.0);
        let path = route(&a, &target, std::slice::from_ref(&bridge), &Prefs::default(), false);
        assert_eq!(path.first().map(String::as_str), Some("a"));
        assert_eq!(path.last().map(String::as_str), Some("target"));
        let asap = route(&a, &target, std::slice::from_ref(&bridge), &Prefs::default(), true);
        assert!(asap.len() <= path.len());
    }
}
