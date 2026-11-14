//! Spotify's mixed playlists (docs §6.8), as the official client reads them:
//!
//! * A playlist is mixed when extended metadata `MIX_STATE` (225) says `MixState{1: is_mixed}`
//!   (the client's own check is the format attributes `mix=true` and `has-custom-transitions=true`).
//! * Each song's transition into the next is the item format attribute
//!   `automix.auto_transition_recipe`: base64 of `spotify.automix.proto.Transition{1: Overlap{3:
//!   start_a_ms, 4: start_b_ms, 5: duration_ms, 6: float speed_a, 7: float speed_b, 8:
//!   duration_bars, 9: is_beatmatched, 10: track_a_uri, 11: track_b_uri, 16: float bpm_a, 17: float
//!   bpm_b}, 2: Preset{1: id, 2: type (1 fade, 2 cut, 3 beat-matched fade, 4 beat-matched cut)}}`.
//!   Songs without one get Spotify's automatic transition (Vibe DJ plans it).

use super::metadata;
use super::showextras::fields;
use base64::Engine as _;
use librespot_core::Session;
use parking_lot::Mutex;
use serde::Serialize;
use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;
use std::time::{Duration, Instant};

pub(crate) const MIX_STATE: i32 = 225;
pub(crate) const VOCAL_ACTIVITY: i32 = 218;
pub(crate) const RECIPE_KEY: &str = "automix.auto_transition_recipe";

/// How the songs meet (Spotify's `PresetType`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum Preset {
    Fade,
    Cut,
    BeatmatchedFade,
    BeatmatchedCut,
}

/// One stored transition: song A (`a_uri`) into song B.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Recipe {
    pub a_uri: String,
    pub b_uri: String,
    pub start_a_ms: f64,
    pub start_b_ms: f64,
    pub duration_ms: f64,
    pub speed_a: f64,
    pub speed_b: f64,
    pub bpm_a: f32,
    pub bpm_b: f32,
    pub beatmatched: bool,
    pub preset: Preset,
    pub preset_id: u32,
}

/// Decodes an `automix.auto_transition_recipe` value.
pub(crate) fn decode_recipe(value: &str) -> Option<Recipe> {
    let v = value.trim();
    let raw = base64::engine::general_purpose::STANDARD
        .decode(v)
        .or_else(|_| base64::engine::general_purpose::URL_SAFE.decode(v))
        .or_else(|_| base64::engine::general_purpose::STANDARD_NO_PAD.decode(v.trim_end_matches('=')))
        .ok()?;
    let top = fields(&raw);
    let overlap = fields(top.iter().find(|f| f.0 == 1)?.2);
    let preset = top.iter().find(|f| f.0 == 2).map(|f| fields(f.2)).unwrap_or_default();
    let num = |n: u32| overlap.iter().find(|x| x.0 == n).map_or(0.0, |x| x.1 as u32 as i32 as f64);
    let float = |n: u32, d: f32| overlap.iter().find(|x| x.0 == n).map_or(d, |x| f32::from_bits(x.1 as u32));
    let text = |n: u32| overlap.iter().find(|x| x.0 == n).map(|x| String::from_utf8_lossy(x.2).into_owned()).unwrap_or_default();
    let beatmatched = overlap.iter().any(|x| x.0 == 9 && x.1 != 0);
    let kind = preset.iter().find(|x| x.0 == 2).map_or(0, |x| x.1);
    let preset_kind = match kind {
        2 => Preset::Cut,
        3 => Preset::BeatmatchedFade,
        4 => Preset::BeatmatchedCut,
        1 => Preset::Fade,
        _ if beatmatched => Preset::BeatmatchedFade,
        _ => Preset::Fade,
    };
    let speed = |n: u32| f64::from(float(n, 1.0)).clamp(0.5, 2.0);
    Some(Recipe {
        a_uri: text(10),
        b_uri: text(11),
        start_a_ms: num(3),
        start_b_ms: num(4).max(0.0),
        duration_ms: num(5).max(0.0),
        speed_a: speed(6),
        speed_b: speed(7),
        bpm_a: float(16, 0.0),
        bpm_b: float(17, 0.0),
        beatmatched,
        preset: preset_kind,
        preset_id: preset.iter().find(|x| x.0 == 1).map_or(0, |x| x.1 as u32),
    })
}

/// Stored transitions by (A, B) song URIs, learned whenever a mixed playlist's items are read.
static RECIPES: LazyLock<Mutex<HashMap<(String, String), Recipe>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

pub(crate) fn remember(r: &Recipe) {
    if !r.a_uri.is_empty() && !r.b_uri.is_empty() {
        let mut m = RECIPES.lock();
        if m.len() > 4_000 {
            m.clear();
        }
        m.insert((r.a_uri.clone(), r.b_uri.clone()), r.clone());
    }
}

/// The stored transition from `a` into `b`, if any.
pub(crate) fn recipe(a: &str, b: &str) -> Option<Recipe> {
    RECIPES.lock().get(&(a.to_string(), b.to_string())).cloned()
}

static MIXED: LazyLock<Mutex<HashMap<String, (Instant, bool)>>> = LazyLock::new(|| Mutex::new(HashMap::new()));
const MIXED_TTL: Duration = Duration::from_secs(600);

/// Marks `uri` as (not) mixed right away (after "Mix" was switched).
pub(crate) fn set_known(uri: &str, mixed: bool) {
    MIXED.lock().insert(uri.to_string(), (Instant::now(), mixed));
}

/// Which of the playlists `uris` are mixed (cached for 10 minutes; unknown ones in one request).
pub(crate) async fn mixed(session: &Session, uris: &[String]) -> HashSet<String> {
    let mut out = HashSet::new();
    let mut ask = Vec::new();
    {
        let m = MIXED.lock();
        for u in uris {
            match m.get(u) {
                Some((at, on)) if at.elapsed() < MIXED_TTL => {
                    if *on {
                        out.insert(u.clone());
                    }
                }
                _ => ask.push(u.clone()),
            }
        }
    }
    if ask.is_empty() {
        return out;
    }
    let Ok(found) = metadata::fetch_extension_raw(session, MIX_STATE, &ask).await.map(|p| p.found) else {
        return out;
    };
    let mut m = MIXED.lock();
    for u in ask {
        let on = found.get(&u).is_some_and(|b| fields(b).iter().any(|f| f.0 == 1 && f.1 != 0));
        m.insert(u.clone(), (Instant::now(), on));
        if on {
            out.insert(u);
        }
    }
    out
}

/// A song's vocals: per window, the chance someone sings (0–1), and the window step in ms.
/// Extended metadata `VOCAL_ACTIVITY` (218): `VocalActivity{1: float source_sample_rate_hz, 2:
/// smoothing_window_size, 3: first_window_sample_start, 4: samples_between_windows, 5: repeated
/// int32 vocal_activity_probabilities (0–100)}`.
#[derive(Debug, Clone, Default, PartialEq)]
pub(crate) struct Vocals {
    pub start_ms: f64,
    pub step_ms: f64,
    pub prob: Vec<u8>,
}

impl Vocals {
    /// Average chance of vocals between `from` and `to` (ms); 0.5 when unknown.
    pub(crate) fn between(&self, from: f64, to: f64) -> f32 {
        if self.prob.is_empty() || self.step_ms <= 0.0 {
            return 0.5;
        }
        let i = ((from - self.start_ms) / self.step_ms).floor().max(0.0) as usize;
        let j = (((to - self.start_ms) / self.step_ms).ceil().max(0.0) as usize).min(self.prob.len());
        if i >= j {
            return 0.0;
        }
        self.prob[i..j].iter().map(|p| f32::from(*p)).sum::<f32>() / ((j - i) as f32 * 100.0)
    }
}

pub(crate) fn parse_vocals(b: &[u8]) -> Option<Vocals> {
    let f = fields(b);
    let rate = f.iter().find(|x| x.0 == 1).map(|x| f32::from_bits(x.1 as u32)).filter(|r| *r > 0.0)?;
    let num = |n: u32| f.iter().find(|x| x.0 == n).map_or(0.0, |x| x.1 as f64);
    let mut prob = Vec::new();
    for x in f.iter().filter(|x| x.0 == 5) {
        if x.2.is_empty() {
            prob.push(x.1.min(100) as u8);
        } else {
            // Packed.
            let mut rest = x.2;
            while !rest.is_empty() {
                let mut v = 0u64;
                let mut shift = 0;
                while let Some((&byte, r)) = rest.split_first() {
                    rest = r;
                    v |= u64::from(byte & 0x7f) << shift;
                    shift += 7;
                    if byte & 0x80 == 0 {
                        break;
                    }
                }
                prob.push(v.min(100) as u8);
            }
        }
    }
    let ms = |samples: f64| samples * 1000.0 / f64::from(rate);
    Some(Vocals { start_ms: ms(num(3)), step_ms: ms(num(4)), prob })
}

static VOCALS: LazyLock<Mutex<HashMap<String, Option<Vocals>>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

/// A song's vocal activity (asked once per song; `None` when Spotify has none).
pub(crate) async fn vocals(session: &Session, uri: &str) -> Option<Vocals> {
    if let Some(v) = VOCALS.lock().get(uri) {
        return v.clone();
    }
    let raw = metadata::fetch_extension_raw(session, VOCAL_ACTIVITY, &[uri.to_string()]).await.ok()?;
    let v = raw.found.get(uri).and_then(|b| parse_vocals(b));
    let mut m = VOCALS.lock();
    if m.len() > 2_000 {
        m.clear();
    }
    m.insert(uri.to_string(), v.clone());
    v
}

/// The cached vocal activity of `uri` (no request).
pub(crate) fn vocals_cached(uri: &str) -> Option<Vocals> {
    VOCALS.lock().get(uri).cloned().flatten()
}

/// Extended-metadata 222 (the APK's AudioAttributesV2): `{1: bpm (double), 2: Key{1: key, 2:
/// mode, 3: Camelot{1: value ("8A"), 2: color}}}`.
const AUDIO_ATTRIBUTES: i32 = 222;

/// Spotify's own tempo and key of a song: enough to rank songs before they're analyzed.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Attrs {
    pub bpm: f32,
    /// Camelot number (1–12) and whether it's the minor (A) side.
    pub camelot: Option<(u8, bool)>,
}

static ATTRS: LazyLock<Mutex<HashMap<String, Option<Attrs>>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

fn parse_attrs(b: &[u8]) -> Option<Attrs> {
    let f = fields(b);
    let bpm = f.iter().find(|x| x.0 == 1 && x.2.len() == 8).map(|x| f64::from_bits(x.1) as f32).filter(|b| (40.0..=250.0).contains(b))?;
    let camelot = f.iter().find(|x| x.0 == 2).and_then(|k| fields(k.2).into_iter().find(|x| x.0 == 3)).and_then(|c| {
        let v = String::from_utf8_lossy(fields(c.2).into_iter().find(|x| x.0 == 1)?.2).trim().to_ascii_uppercase();
        let (n, side) = v.split_at(v.len().checked_sub(1)?);
        let n: u8 = n.parse().ok().filter(|n| (1..=12).contains(n))?;
        matches!(side, "A" | "B").then_some((n, side == "A"))
    });
    Some(Attrs { bpm, camelot })
}

/// Fetches (in batches) the attributes of the songs not asked for yet.
pub(crate) async fn attributes(session: &Session, uris: &[String]) {
    let missing: Vec<String> = {
        let m = ATTRS.lock();
        uris.iter().filter(|u| u.starts_with("spotify:track:") && !m.contains_key(*u)).cloned().collect()
    };
    if missing.is_empty() {
        return;
    }
    let Ok(raw) = metadata::fetch_extension_raw(session, AUDIO_ATTRIBUTES, &missing).await else { return };
    let mut m = ATTRS.lock();
    if m.len() > 5_000 {
        m.clear();
    }
    for u in missing {
        let a = raw.found.get(&u).and_then(|b| parse_attrs(b));
        m.insert(u, a);
    }
}

/// The cached attributes of `uri` (no request).
pub(crate) fn attributes_cached(uri: &str) -> Option<Attrs> {
    ATTRS.lock().get(uri).copied().flatten()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_audio_attributes() {
        let mut b = vec![0x09];
        b.extend(124.5f64.to_bits().to_le_bytes());
        // Key{1: "A", 2: 2, 3: Camelot{1: "11B"}}
        b.extend([0x12, 0x0c, 0x0a, 0x01, b'A', 0x10, 0x02, 0x1a, 0x05, 0x0a, 0x03, b'1', b'1', b'B']);
        assert_eq!(parse_attrs(&b), Some(Attrs { bpm: 124.5, camelot: Some((11, false)) }));
        assert_eq!(parse_attrs(&[0x12, 0x00]), None);
    }

    #[test]
    fn decodes_a_recipe() {
        let pb = |f: u8, b: &[u8]| [vec![f << 3 | 2, b.len() as u8], b.to_vec()].concat();
        let mut overlap = vec![0x18, 0xe8, 0x07, 0x20, 0x90, 0x4e, 0x28, 0x88, 0x27, 0x35];
        overlap.extend(1.02f32.to_bits().to_le_bytes());
        overlap.extend([0x48, 0x01]);
        overlap.extend(pb(10, b"spotify:track:a"));
        overlap.extend(pb(11, b"spotify:track:b"));
        let preset = [0x08, 0x07, 0x10, 0x03];
        let t = [pb(1, &overlap), pb(2, &preset)].concat();
        let r = decode_recipe(&base64::engine::general_purpose::STANDARD.encode(t)).unwrap();
        assert_eq!(r.start_a_ms, 1000.0);
        assert_eq!(r.start_b_ms, 10000.0);
        assert_eq!(r.duration_ms, 5000.0);
        assert!((r.speed_a - 1.02).abs() < 1e-6);
        assert!(r.beatmatched);
        assert_eq!(r.preset, Preset::BeatmatchedFade);
        assert_eq!(r.preset_id, 7);
        assert_eq!(r.b_uri, "spotify:track:b");
    }

    #[test]
    fn vocals_average() {
        // rate 1000 Hz, start 0, step 500 samples (500 ms), probabilities 0, 100, 100 packed.
        let mut b = vec![0x0d];
        b.extend(1000.0f32.to_bits().to_le_bytes());
        b.extend([0x20, 0xf4, 0x03, 0x2a, 0x03, 0x00, 0x64, 0x64]);
        let v = parse_vocals(&b).unwrap();
        assert_eq!(v.step_ms, 500.0);
        assert_eq!(v.prob, vec![0, 100, 100]);
        assert!((v.between(500.0, 1500.0) - 1.0).abs() < 1e-6);
        assert!((v.between(0.0, 500.0)).abs() < 1e-6);
    }
}
