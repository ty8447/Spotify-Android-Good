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
}

impl Default for Prefs {
    fn default() -> Self {
        Self {
            length: Length::Club { min: 0.25, max: 0.5 },
            harmonic: true,
            // Pitch follows tempo (like a turntable): both songs glide to meet, no WSOLA artefacts.
            key_lock: false,
            // Like a DJ: blends where the tempos meet, cuts on the phrase where they don't (or where
            // both songs are at full energy).
            styles: vec![Style::BassSwap, Style::Crossfade, Style::FilterSweep, Style::EchoOut, Style::Cut],
            energy_arc: EnergyArc::Journey,
            session_minutes: 60,
            no_repeat: 50,
            max_tempo_shift: 0.08,
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

/// Bars of overlap for a style.
fn bars_for(style: Style, prefs: &Prefs, asap: bool) -> u32 {
    let base = match (style, prefs.length) {
        (Style::Cut, _) => 0,
        (Style::EchoOut, _) => 2,
        (Style::FilterSweep, Length::Quick) => 8,
        (Style::FilterSweep, _) => 16,
        (Style::BassSwap, Length::Full) => 32,
        (_, Length::Full) => 16,
        (_, Length::Club { .. }) => 16,
        (_, Length::Quick) => 8,
    };
    if asap { base / 2 } else { base }
}

/// Chooses the style, mix-out point and entry point for `a` → `b`. `None` when the current song
/// has no room left for a mix.
pub(crate) fn plan(a: &Analysis, b: &Analysis, prefs: &Prefs, position_ms: f64, asap: bool) -> Option<(Transition, f32)> {
    plan_with(a, b, prefs, position_ms, asap, (None, None))
}

/// [`plan`] knowing where each song has vocals (Spotify's vocal activity): two vocals never
/// overlap in a blend.
pub(crate) fn plan_with(
    a: &Analysis,
    b: &Analysis,
    prefs: &Prefs,
    position_ms: f64,
    asap: bool,
    vocals: (Option<&Vocals>, Option<&Vocals>),
) -> Option<(Transition, f32)> {
    let (speed, dev) = tempo_match(a.bpm, b.bpm);
    let beatmatch = dev <= f64::from(prefs.max_tempo_shift);
    let shift = if beatmatch && !prefs.key_lock { (12.0 * speed.log2()).round() as i32 } else { 0 };
    let mut keys = key_score(a, b, shift);
    // Keys that clash: pitch the next song a semitone or two so they meet (only in blends, where
    // both play; a cut needs none). The pitch is then applied apart from the tempo.
    let mut extra: i32 = 0;
    if beatmatch && prefs.harmonic && keys < 0.8 {
        if let Some((e, k)) = [-1, 1, -2, 2].into_iter().map(|e| (e, key_score(a, b, shift + e))).max_by(|x, y| x.1.total_cmp(&y.1)) {
            if k >= keys + 0.15 {
                extra = e;
                keys = k;
            }
        }
    }
    // B's pitch from its own key: A already moves by half the tempo change (turntable style), so
    // B takes the other half plus the extra semitones; key-locked, only the extra.
    let pitch = match extra {
        0 => 0,
        e if prefs.key_lock => e,
        e => (6.0 * speed.log2()).round() as i32 + e,
    };
    let a_bars = a.bar_times_ms();
    let b_bars = b.bar_times_ms();
    if a_bars.len() < 4 || b_bars.len() < 4 {
        return None;
    }
    let a_bar_ms = (a_bars[a_bars.len() - 1] - a_bars[0]) / (a_bars.len() - 1) as f64;
    let beat_ms = a_bar_ms / f64::from(a.meter.max(1));
    let (from, to) = window(a, prefs, position_ms, asap);

    // The entry: bar 0 of the next song for full songs; otherwise so that its first drop (or
    // first body phrase) lands when the mix ends.
    let first_hit = b
        .phrases
        .iter()
        .find(|p| p.kind == PhraseKind::Drop)
        .or_else(|| b.phrases.iter().find(|p| p.kind == PhraseKind::Body))
        .map_or(0, |p| p.start_bar as usize);

    let allowed = |s: Style| prefs.styles.is_empty() || prefs.styles.contains(&s);
    let mut best: Option<(Transition, f32)> = None;
    for (i, &t) in a_bars.iter().enumerate() {
        if t < from || t > to {
            continue;
        }
        // Phrase boundaries (every 8 bars from the first downbeat) score higher than other bars.
        let phrase_bonus = if i % 8 == 0 { 0.3 } else if i % 4 == 0 { 0.12 } else { 0.0 };
        let ending_phrase = a.phrases.get(phrase_at_bar(a, i)).map(|p| p.kind);
        let a_energy = a.intensity_between(t, t + 8.0 * a_bar_ms);
        // Tempos that can't meet leave a cut on the phrase as the clean way across.
        for style in Style::ALL.into_iter().filter(|s| allowed(*s) || (*s == Style::Cut && !beatmatch)) {
            let beat_styles = matches!(style, Style::BassSwap | Style::Crossfade | Style::FilterSweep);
            if beat_styles && !beatmatch && style != Style::Crossfade {
                continue;
            }
            let bars = bars_for(style, prefs, asap);
            let mut length = f64::from(bars) * a_bar_ms;
            if !beatmatch && style == Style::Crossfade {
                length = length.min(4_000.0);
            }
            if t + length > f64::from(a.audio_end_ms).min(f64::from(a.duration_ms)) - 500.0 {
                continue;
            }
            // Tempos meet halfway: the current song eases to it over 8 bars before the mix, the
            // next one comes in there and eases back to its own tempo over 16 bars after it.
            let matched = beatmatch && !matches!(style, Style::EchoOut | Style::Cut);
            let (a_speed, b_speed, pre_glide) = if !matched {
                (1.0, 1.0, 0.0)
            } else if prefs.key_lock {
                (1.0, speed, 0.0)
            } else {
                (1.0 / speed.sqrt(), speed.sqrt(), (8.0 * a_bar_ms).min(16_000.0))
            };
            if t - pre_glide < position_ms + if asap { 1_000.0 } else { 3_000.0 } {
                continue;
            }
            let entry_bar = match prefs.length {
                Length::Full if !asap => 0,
                _ => first_hit.saturating_sub(bars.max(1) as usize),
            };
            let Some(&entry_ms) = b_bars.get(entry_bar.min(b_bars.len() - 1)) else { continue };
            let b_energy = b.intensity_between(entry_ms, entry_ms + 8.0 * a_bar_ms / speed);
            let jump = b_energy - a_energy;
            // Both songs going full out: a fade would muddy it, a cut on the phrase hits harder.
            let intense = a_energy > 0.65 && b_energy > 0.6;
            // Two vocals must never play over each other (where Spotify knows the vocals).
            let overlap_b = length * speed / a_speed.max(0.01);
            let clash = match vocals {
                (Some(va), Some(vb)) => va.between(t, t + length) > 0.45 && vb.between(entry_ms, entry_ms + overlap_b) > 0.45,
                _ => false,
            };
            // How well the style suits this moment.
            let mut fit = match style {
                Style::BassSwap => 0.8 + if keys > 0.8 { 0.1 } else { -0.2 } - if intense { 0.3 } else { 0.0 },
                Style::Crossfade if beatmatch => 0.6 + (keys - 0.7) * 0.5 - if intense { 0.35 } else { 0.0 },
                Style::Crossfade => 0.25,
                Style::FilterSweep => 0.6 + if keys < 0.6 { 0.25 } else { 0.0 } + if jump > 0.15 { 0.1 } else { 0.0 } - if intense { 0.2 } else { 0.0 },
                Style::EchoOut => (if beatmatch { 0.45 } else { 0.7 }) - if intense { 0.1 } else { 0.0 },
                Style::Cut => {
                    0.6 + if beatmatch { 0.0 } else { 0.35 } + if intense { 0.25 } else { 0.0 } + if jump > 0.25 { 0.1 } else { 0.0 }
                }
            };
            if clash {
                fit -= match style {
                    Style::BassSwap | Style::Crossfade => 0.6,
                    // The filter takes the outgoing vocal down.
                    Style::FilterSweep => 0.25,
                    _ => 0.0,
                };
            }
            let outro_bonus = match (prefs.length, ending_phrase) {
                (Length::Full, Some(PhraseKind::Outro)) => 0.25,
                (_, Some(PhraseKind::Breakdown)) => 0.1,
                _ => 0.0,
            };
            // Later in the window is better for full songs; earlier for ASAP.
            let place = if asap { -((t - from) / 60_000.0) as f32 } else { ((t - from) / (to - from).max(1.0)) as f32 * 0.1 };
            let quality = fit + phrase_bonus + outro_bonus + place;
            if best.as_ref().is_none_or(|b| quality > b.1) {
                let pitched = if matched { pitch } else { 0 };
                let settle = if (b_speed - 1.0).abs() > 0.0005 || pitched != 0 {
                    if prefs.key_lock || pitched != 0 { (8.0 * a_bar_ms).min(20_000.0) } else { (16.0 * a_bar_ms).min(30_000.0) }
                } else {
                    0.0
                };
                best = Some((
                    Transition {
                        style,
                        mix_start_ms: t,
                        length_ms: length.max(if style == Style::Cut { 30.0 } else { 0.0 }),
                        beats: bars * u32::from(a.meter),
                        entry_ms,
                        speed: b_speed,
                        a_speed,
                        pre_glide_ms: pre_glide,
                        settle_ms: settle,
                        gain_db: (a.loudness_db - b.loudness_db).clamp(-6.0, 6.0),
                        beat_ms,
                        pitch_semitones: pitched as f32,
                    },
                    quality,
                ));
            }
        }
    }
    best
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
    let key_w = if prefs.harmonic { 0.22 } else { 0.05 };
    let total = 0.28 * tempo + key_w * keys + 0.15 * energy + 0.18 * timbre + 0.17 * groove;
    let score = total / (0.78 + key_w);
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
    if timbre > 0.95 {
        reasons.push("same sound".into());
    }
    if energy > 0.85 {
        reasons.push(if body_energy(b) > body_energy(a) + 0.05 { "lifts the energy".into() } else { "keeps the energy".into() });
    }
    (score, reasons)
}

/// The best next song from `pool` (only analysed candidates can be mixed).
pub(crate) fn pick_next(ctx: &Context, pool: &[Candidate], prefs: &Prefs) -> Option<Pick> {
    let a = ctx.current.analysis.as_ref()?;
    let recent: HashSet<&str> = ctx.history.iter().rev().take(prefs.no_repeat).map(String::as_str).collect();
    let target = target_energy(prefs.energy_arc, ctx.session_progress, body_energy(a));
    pool.iter()
        .filter(|c| c.uri != ctx.current.uri && !recent.contains(c.uri.as_str()))
        .filter_map(|c| {
            let b = c.analysis.as_ref()?;
            let (mut s, reasons) = score(a, b, prefs, target);
            if c.artist == ctx.current.artist || ctx.recent_artists.iter().rev().take(2).any(|x| *x == c.artist) {
                s -= 0.12;
            }
            let (va, vb) = (crate::catalog::mix::vocals_cached(&ctx.current.uri), crate::catalog::mix::vocals_cached(&c.uri));
            let (transition, quality) = plan_with(a, b, prefs, ctx.position_ms, ctx.asap, (va.as_ref(), vb.as_ref()))?;
            Some(Pick { uri: c.uri.clone(), score: s + 0.05 * quality.min(1.0), transition, reasons })
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
        // The tempos meet halfway: A slows a little, B speeds up a little, beats still in line.
        assert!((t.speed / t.a_speed - 126.0 / 124.0).abs() < 0.01, "speed {} / {}", t.speed, t.a_speed);
        assert!(t.a_speed < 1.0 && t.speed > 1.0 && t.pre_glide_ms > 0.0);
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
