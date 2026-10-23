//! Controlling another Connect device through connect-state (docs/ARCHITECTURE.md §8):
//! `POST /connect-state/v1/player/command/from/{me}/to/{target}` with
//! `{"command":{"endpoint":…,…,"logging_params":{"command_id":…}}}`, volume via
//! `PUT /connect-state/v1/connect/volume/from/{me}/to/{target}` (throttled, latest wins) and
//! transfers via `SpClient::transfer`.
//!
//! The JSON builders are pure; the field set matches what librespot targets deserialize
//! (`core/src/dealer/protocol/request.rs`) and what official clients send.

use super::args::LoadArgs;
use super::snapshot::is_hidden_provided;
use super::uri;
use crate::error::{AppError, AppResult};
use crate::models::RepeatMode;
use crate::{engine, runtime};
use http::header::CONTENT_TYPE;
use http::{HeaderMap, HeaderValue, Method};
use librespot_core::dealer::protocol::TransferOptions;
use librespot_core::spclient::{RequestOptions, TransferRequest};
use librespot_protocol::player::ProvidedTrack;
use parking_lot::Mutex;
use percent_encoding::{utf8_percent_encode, AsciiSet, NON_ALPHANUMERIC};
use serde_json::{json, Map, Value};
use std::time::Duration;

/// Bound of every remote request (spclient retries internally).
const REQUEST_TIMEOUT: Duration = Duration::from_secs(10);
/// Minimum spacing of volume PUTs.
const VOLUME_INTERVAL: Duration = Duration::from_millis(200);

const PATH_SEGMENT: &AsciiSet = &NON_ALPHANUMERIC.remove(b'-').remove(b'_').remove(b'.');

fn seg(s: &str) -> String {
    utf8_percent_encode(s, PATH_SEGMENT).to_string()
}

// ---------------------------------------------------------------------------------------------
// JSON builders (pure)
// ---------------------------------------------------------------------------------------------

/// Wraps endpoint fields into a command body.
pub(crate) fn command(endpoint: &str, mut fields: Map<String, Value>, command_id: &str) -> Value {
    fields.insert("endpoint".into(), Value::String(endpoint.into()));
    fields.insert("logging_params".into(), json!({ "command_id": command_id }));
    json!({ "command": Value::Object(fields) })
}

pub(crate) fn simple(endpoint: &str, command_id: &str) -> Value {
    command(endpoint, Map::new(), command_id)
}

pub(crate) fn seek_to(position_ms: u64, command_id: &str) -> Value {
    let ms = position_ms.min(u32::MAX as u64);
    let mut f = Map::new();
    // librespot targets read `value` and require `position`; both carry the absolute target.
    f.insert("value".into(), json!(ms));
    f.insert("position".into(), json!(ms));
    command("seek_to", f, command_id)
}

pub(crate) fn set_value(endpoint: &str, value: bool, command_id: &str) -> Value {
    let mut f = Map::new();
    f.insert("value".into(), Value::Bool(value));
    command(endpoint, f, command_id)
}

/// ProvidedTrack as protobuf-JSON (only the fields Connect clients use).
pub(crate) fn provided_track_json(t: &ProvidedTrack) -> Value {
    let mut m = Map::new();
    m.insert("uri".into(), Value::String(t.uri.clone()));
    if !t.uid.is_empty() {
        m.insert("uid".into(), Value::String(t.uid.clone()));
    }
    if !t.provider.is_empty() {
        m.insert("provider".into(), Value::String(t.provider.clone()));
    }
    let mut metadata: Vec<(&String, &String)> = t.metadata.iter().collect();
    metadata.sort();
    let metadata: Map<String, Value> = metadata.into_iter().map(|(k, v)| (k.clone(), Value::String(v.clone()))).collect();
    m.insert("metadata".into(), Value::Object(metadata));
    Value::Object(m)
}

pub(crate) fn add_to_queue(track_uri: &str, command_id: &str) -> Value {
    let mut f = Map::new();
    f.insert(
        "track".into(),
        json!({ "uri": track_uri, "metadata": { "is_queued": "true" }, "provider": "queue" }),
    );
    command("add_to_queue", f, command_id)
}

pub(crate) fn skip_next_to(track: &ProvidedTrack, command_id: &str) -> Value {
    let mut f = Map::new();
    f.insert("track".into(), provided_track_json(track));
    command("skip_next", f, command_id)
}

pub(crate) fn set_queue(next: &[ProvidedTrack], prev: &[ProvidedTrack], revision: &str, command_id: &str) -> Value {
    let mut f = Map::new();
    f.insert("next_tracks".into(), Value::Array(next.iter().map(provided_track_json).collect()));
    f.insert("prev_tracks".into(), Value::Array(prev.iter().map(provided_track_json).collect()));
    f.insert("queue_revision".into(), Value::String(revision.into()));
    command("set_queue", f, command_id)
}

/// `play` with a context (or a plain list of tracks as context pages).
pub(crate) fn play(args: &LoadArgs, command_id: &str) -> Value {
    let context = match (&args.context_uri, &args.track_uris) {
        (Some(ctx), _) if uri::is_resolvable_context(ctx) => {
            json!({ "uri": ctx, "url": format!("context://{ctx}"), "metadata": {} })
        }
        (_, Some(tracks)) => {
            let tracks: Vec<Value> = tracks.iter().map(|u| json!({ "uri": u })).collect();
            json!({ "metadata": {}, "pages": [{ "tracks": tracks }] })
        }
        (Some(item), None) => json!({ "metadata": {}, "pages": [{ "tracks": [{ "uri": item }] }] }),
        (None, None) => json!({ "metadata": {} }),
    };

    let mut options = Map::new();
    let mut skip_to = Map::new();
    if let Some(uid) = args.start_uid.as_ref().filter(|s| !s.is_empty()) {
        skip_to.insert("track_uid".into(), json!(uid));
    }
    if let Some(u) = args.start_uri.as_ref().filter(|s| !s.is_empty()) {
        skip_to.insert("track_uri".into(), json!(u));
    }
    if let Some(i) = args.start_index {
        skip_to.insert("track_index".into(), json!(i));
    }
    if !skip_to.is_empty() {
        options.insert("skip_to".into(), Value::Object(skip_to));
    }
    let mut overrides = Map::new();
    let shuffle = args.shuffle.unwrap_or(false) || args.smart_shuffle.unwrap_or(false);
    if args.shuffle.is_some() || args.smart_shuffle.is_some() {
        overrides.insert("shuffling_context".into(), Value::Bool(shuffle));
    }
    if let Some(repeat) = args.repeat {
        overrides.insert("repeating_context".into(), Value::Bool(repeat == RepeatMode::Context));
        overrides.insert("repeating_track".into(), Value::Bool(repeat == RepeatMode::Track));
    }
    if !overrides.is_empty() {
        options.insert("player_options_override".into(), Value::Object(overrides));
    }
    if args.position_ms > 0 {
        options.insert("seek_to".into(), json!(args.position_ms.min(u32::MAX as u64)));
    }
    if !args.play {
        options.insert("initially_paused".into(), Value::Bool(true));
    }

    let mut f = Map::new();
    f.insert("context".into(), context);
    f.insert(
        "play_origin".into(),
        json!({ "feature_identifier": "spotifygood", "feature_version": env!("CARGO_PKG_VERSION") }),
    );
    f.insert("options".into(), Value::Object(options));
    command("play", f, command_id)
}

fn is_queued(t: &ProvidedTrack) -> bool {
    t.provider == "queue" || t.metadata.get("is_queued").is_some_and(|v| v == "true")
}

/// `next_tracks` without the entry `uid`; `None` if it isn't there.
pub(crate) fn queue_without(next: &[ProvidedTrack], uid: &str) -> Option<Vec<ProvidedTrack>> {
    let pos = next.iter().position(|t| t.uid == uid && !is_hidden_provided(t))?;
    let mut out = next.to_vec();
    out.remove(pos);
    Some(out)
}

/// `next_tracks` with `uid` moved to the displayed index `to_visible` (hidden entries are not
/// counted). A user-initiated move promotes a context track to the protected manual queue,
/// matching Jam's Move up behavior. Existing queued entries retain their metadata.
pub(crate) fn queue_moved(next: &[ProvidedTrack], uid: &str, to_visible: usize) -> Option<Vec<ProvidedTrack>> {
    let mut rest = next.to_vec();
    if uid.is_empty() { return None; }
    let pos = rest.iter().position(|t| t.uid == uid && !is_hidden_provided(t))?;
    let mut item = rest.remove(pos);
    if !is_queued(&item) {
        item.provider = "queue".to_owned();
        item.metadata.insert("is_queued".to_owned(), "true".to_owned());
    }
    // Right after the displayed entry that will precede it.
    let mut insert_at = if to_visible == 0 { 0 } else { rest.len() };
    if to_visible > 0 {
        let mut seen = 0usize;
        for (i, t) in rest.iter().enumerate() {
            if is_hidden_provided(t) {
                continue;
            }
            seen += 1;
            if seen == to_visible {
                insert_at = i + 1;
                break;
            }
        }
    }
    rest.insert(insert_at, item);
    Some(rest)
}

/// Builds a single set_queue update for multiple selected tracks. All selected UIDs must
/// be present; otherwise return None without partially modifying the supplied snapshot.
/// The selected order is exactly the order received from the Jam editor.
pub(crate) fn queue_promoted(next: &[ProvidedTrack], uids: &[String]) -> Option<Vec<ProvidedTrack>> {
    if uids.is_empty() || uids.len() > 80 { return None; }
    let mut rest = next.to_vec();
    let mut promoted = Vec::with_capacity(uids.len());
    for uid in uids {
        if uid.is_empty() { return None; }
        let pos = rest.iter().position(|t| &t.uid == uid && !is_hidden_provided(t))?;
        let mut item = rest.remove(pos);
        if !is_queued(&item) {
            item.provider = "queue".to_owned();
            item.metadata.insert("is_queued".to_owned(), "true".to_owned());
        }
        promoted.push(item);
    }
    promoted.extend(rest);
    Some(promoted)
}

/** A recorded pre-promotion position. A track without a verified origin cannot be reset. */
#[derive(Debug, Clone, serde::Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct RestoreMove {
    pub uid: String,
    pub context_index: usize,
    pub provider: String,
    #[serde(default)]
    pub before_uid: Option<String>,
    #[serde(default)]
    pub after_uid: Option<String>,
}

/// Restore only known originally-context tracks to their earlier neighborhood.
/// Other manually queued tracks and all current context tracks are preserved.
/// Anchors win when still present; otherwise fall back to the original context index.
pub(crate) fn queue_restored(next: &[ProvidedTrack], moves: &[RestoreMove]) -> Option<Vec<ProvidedTrack>> {
    if moves.is_empty() || moves.len() > 80 { return None; }
    let mut rest = next.to_vec();
    let mut pulled = Vec::with_capacity(moves.len());
    for (seq, mv) in moves.iter().enumerate() {
        if mv.uid.is_empty() || mv.uid.len() > 256 || mv.context_index > 10_000 ||
           !(mv.provider == "context" || mv.provider == "autoplay") ||
           mv.before_uid.as_ref().is_some_and(|s| s.len() > 256) ||
           mv.after_uid.as_ref().is_some_and(|s| s.len() > 256) ||
           moves[..seq].iter().any(|earlier| earlier.uid == mv.uid) { return None; }
        let pos = rest.iter().position(|t| t.uid == mv.uid && !is_hidden_provided(t) && is_queued(t))?;
        let mut track = rest.remove(pos);
        track.provider = mv.provider.clone();
        track.metadata.remove("is_queued");
        pulled.push((seq, mv, track));
    }
    // Recreate original context order when multiple tracks were promoted together.
    pulled.sort_by_key(|(seq, mv, _)| (mv.context_index, *seq));
    for (_, mv, track) in pulled {
        let after = mv.after_uid.as_ref().and_then(|uid| rest.iter().position(|t|
            &t.uid == uid && !is_hidden_provided(t) && !is_queued(t)));
        let before = mv.before_uid.as_ref().and_then(|uid| rest.iter().position(|t|
            &t.uid == uid && !is_hidden_provided(t) && !is_queued(t)));
        let fallback = rest.iter().enumerate()
            .filter(|(_, t)| !is_hidden_provided(t) && !is_queued(t))
            .nth(mv.context_index).map(|(i, _)| i).unwrap_or(rest.len());
        let index = after.or_else(|| before.map(|i| i + 1)).unwrap_or(fallback);
        rest.insert(index, track);
    }
    Some(rest)
}

/// `next_tracks` without the user queue.
/// Reorder only playback-context tracks. Manually queued and hidden entries keep
/// their exact slots and metadata; no track is added, dropped, or restarted.
/// The caller supplies an action-specific seed, so two taps can produce different orders.
pub(crate) fn queue_reshuffled(next: &[ProvidedTrack], seed: u64) -> Option<Vec<ProvidedTrack>> {
    let slots: Vec<usize> = next.iter().enumerate().filter_map(|(i, track)| {
        (!is_hidden_provided(track) && !is_queued(track)).then_some(i)
    }).collect();
    if slots.len() < 2 { return None; }
    let mut songs: Vec<ProvidedTrack> = slots.iter().map(|&i| next[i].clone()).collect();
    // Xorshift64; randomization is for presentation, not security.
    let mut value = if seed == 0 { 0x9E3779B97F4A7C15 } else { seed };
    for i in (1..songs.len()).rev() {
        value ^= value << 13;
        value ^= value >> 7;
        value ^= value << 17;
        let j = (value % ((i + 1) as u64)) as usize;
        songs.swap(i, j);
    }
    // Never acknowledge a reshuffle that left the context in identical order.
    if songs.iter().zip(slots.iter()).all(|(track, &i)| track.uid == next[i].uid) {
        songs.rotate_left(1);
    }
    let mut result = next.to_vec();
    for (position, song) in slots.into_iter().zip(songs) {
        result[position] = song;
    }
    Some(result)
}

pub(crate) fn queue_cleared(next: &[ProvidedTrack]) -> Vec<ProvidedTrack> {
    next.iter().filter(|t| !is_queued(t)).cloned().collect()
}

// ---------------------------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------------------------

fn json_headers() -> HeaderMap {
    let mut headers = HeaderMap::new();
    headers.insert(CONTENT_TYPE, HeaderValue::from_static("application/json"));
    headers
}

/// Sends a player command to `target`.
pub(crate) async fn send(target: &str, body: Value) -> AppResult<()> {
    let session = engine::session()?;
    let endpoint =
        format!("/connect-state/v1/player/command/from/{}/to/{}", seg(session.device_id()), seg(target));
    let body = body.to_string();
    let options = RequestOptions::new(false, false, None);
    let request =
        session.spclient().request_with_options(&Method::POST, &endpoint, Some(json_headers()), Some(body.as_bytes()), &options);
    tokio::time::timeout(REQUEST_TIMEOUT, request).await??;
    Ok(())
}

/// Sends several commands in order, stopping at the first failure.
pub(crate) async fn send_all(target: &str, bodies: Vec<Value>) -> AppResult<()> {
    for body in bodies {
        send(target, body).await?;
    }
    Ok(())
}

async fn put_volume(target: &str, volume: u16) -> AppResult<()> {
    let session = engine::session()?;
    let endpoint = format!("/connect-state/v1/connect/volume/from/{}/to/{}", seg(session.device_id()), seg(target));
    let body = json!({ "volume": volume }).to_string();
    let options = RequestOptions::new(false, false, None);
    let request =
        session.spclient().request_with_options(&Method::PUT, &endpoint, Some(json_headers()), Some(body.as_bytes()), &options);
    tokio::time::timeout(REQUEST_TIMEOUT, request).await??;
    Ok(())
}

struct VolumeState {
    pending: Option<(String, u16)>,
    running: bool,
}

static VOLUME: Mutex<VolumeState> = parking_lot::const_mutex(VolumeState { pending: None, running: false });

/// Schedules a remote volume change. At most one PUT every 200 ms; intermediate values are
/// dropped (the latest wins). Never blocks.
pub(crate) fn set_volume(target: &str, volume: u16) {
    {
        let mut st = VOLUME.lock();
        st.pending = Some((target.to_string(), volume));
        if st.running {
            return;
        }
        st.running = true;
    }
    runtime::handle().spawn(async move {
        loop {
            let next = {
                let mut st = VOLUME.lock();
                match st.pending.take() {
                    Some(p) => p,
                    None => {
                        st.running = false;
                        return;
                    }
                }
            };
            if let Err(e) = put_volume(&next.0, next.1).await {
                log::warn!("remote volume failed: {e}");
            }
            tokio::time::sleep(VOLUME_INTERVAL).await;
        }
    });
}

/// Moves playback to `target` (this device included: from == to == me pulls).
pub(crate) async fn transfer(target: &str, play: bool) -> AppResult<()> {
    let session = engine::session()?;
    let request = TransferRequest {
        transfer_options: TransferOptions {
            restore_paused: Some(if play { "restore" } else { "pause" }.to_string()),
            ..Default::default()
        },
    };
    let call = session.spclient().transfer(session.device_id(), target, Some(&request));
    tokio::time::timeout(REQUEST_TIMEOUT, call).await??;
    Ok(())
}

/// Maps a remote failure (e.g. "device not found") to a user-facing error.
pub(crate) fn remote_error(e: AppError) -> AppError {
    log::warn!("remote command failed: {e}");
    e.with_context("connect")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pt(uri: &str, uid: &str, provider: &str) -> ProvidedTrack {
        ProvidedTrack { uri: uri.into(), uid: uid.into(), provider: provider.into(), ..Default::default() }
    }

    #[test]
    fn simple_commands() {
        let v = simple("pause", "abc");
        assert_eq!(v, json!({"command": {"endpoint": "pause", "logging_params": {"command_id": "abc"}}}));
        let v = seek_to(42_000, "id");
        assert_eq!(v["command"]["endpoint"], "seek_to");
        assert_eq!(v["command"]["value"], 42_000);
        assert_eq!(v["command"]["position"], 42_000);
        let v = set_value("set_shuffling_context", true, "id");
        assert_eq!(v["command"]["value"], true);
    }

    #[test]
    fn add_to_queue_body() {
        let v = add_to_queue("spotify:track:x", "id");
        assert_eq!(v["command"]["endpoint"], "add_to_queue");
        assert_eq!(v["command"]["track"]["uri"], "spotify:track:x");
        assert_eq!(v["command"]["track"]["provider"], "queue");
        assert_eq!(v["command"]["track"]["metadata"]["is_queued"], "true");
    }

    #[test]
    fn play_context_body() {
        let args: LoadArgs = serde_json::from_str(
            r#"{"contextUri":"spotify:album:abc","startUri":"spotify:track:t","startIndex":2,"positionMs":3000,"shuffle":true,"repeat":"context","play":false}"#,
        )
        .expect("parse");
        let v = play(&args, "id");
        let c = &v["command"];
        assert_eq!(c["endpoint"], "play");
        assert_eq!(c["context"]["uri"], "spotify:album:abc");
        assert_eq!(c["context"]["url"], "context://spotify:album:abc");
        assert_eq!(c["play_origin"]["feature_identifier"], "spotifygood");
        assert_eq!(c["options"]["skip_to"]["track_uri"], "spotify:track:t");
        assert_eq!(c["options"]["skip_to"]["track_index"], 2);
        assert_eq!(c["options"]["seek_to"], 3000);
        assert_eq!(c["options"]["initially_paused"], true);
        assert_eq!(c["options"]["player_options_override"]["shuffling_context"], true);
        assert_eq!(c["options"]["player_options_override"]["repeating_context"], true);
        assert_eq!(c["options"]["player_options_override"]["repeating_track"], false);
        assert_eq!(c["logging_params"]["command_id"], "id");
    }

    #[test]
    fn play_tracks_body() {
        let args: LoadArgs = serde_json::from_str(r#"{"trackUris":["spotify:track:a","spotify:track:b"]}"#).expect("parse");
        let v = play(&args, "id");
        let c = &v["command"];
        assert!(c["context"].get("uri").is_none(), "track lists have no context uri");
        assert_eq!(c["context"]["pages"][0]["tracks"][1]["uri"], "spotify:track:b");
        assert!(c["options"].get("player_options_override").is_none());
        assert!(c["options"].get("initially_paused").is_none());
    }

    #[test]
    fn restores_promoted_tracks_without_discarding_other_queued_items() {
        let q = pt("spotify:track:added", "q", "queue");
        let mut b = pt("spotify:track:b", "b", "queue");
        b.metadata.insert("is_queued".into(), "true".into());
        let next = vec![q, b, pt("spotify:track:a", "a", "context"),
                        pt("spotify:track:c", "c", "context")];
        let restored = queue_restored(&next, &[RestoreMove {
            uid: "b".into(), context_index: 1, provider: "context".into(),
            before_uid: Some("a".into()), after_uid: Some("c".into()),
        }]).expect("restore");
        assert_eq!(restored.iter().map(|t| t.uid.as_str()).collect::<Vec<_>>(),
            vec!["q", "a", "b", "c"]);
        assert_eq!(restored[2].provider, "context");
        assert!(restored[2].metadata.get("is_queued").is_none());
        assert!(queue_restored(&next, &[RestoreMove { uid: "missing".into(),
            context_index: 0, provider: "context".into(), before_uid: None,
            after_uid: None }]).is_none());
    }

    #[test]
    fn reshuffle_keeps_manual_queue_and_hidden_slots() {
        let mut manual = pt("spotify:track:manual", "q", "queue");
        manual.metadata.insert("is_queued".into(), "true".into());
        let next = vec![
            manual,
            pt("spotify:track:a", "a", "context"),
            pt("spotify:delimiter", "delimiter0", "context"),
            pt("spotify:track:b", "b", "context"),
            pt("spotify:track:c", "c", "context"),
        ];
        let result = queue_reshuffled(&next, 123456).expect("context can be shuffled");
        assert_eq!(result[0].uid, "q");
        assert_eq!(result[0].provider, "queue");
        assert_eq!(result[2].uid, "delimiter0");
        let mut a: Vec<_> = next.iter().map(|t| t.uid.as_str()).collect();
        let mut b: Vec<_> = result.iter().map(|t| t.uid.as_str()).collect();
        a.sort(); b.sort();
        assert_eq!(a, b, "all tracks remain in the queue");
        assert_ne!(result[1].uid, next[1].uid, "reshuffle must alter context order");
        assert!(queue_reshuffled(&next[..2], 42).is_none());
    }

    #[test]
    fn queue_edits() {
        let mut q = pt("spotify:track:q", "q0", "queue");
        q.metadata.insert("is_queued".into(), "true".into());
        let next = vec![
            q,
            pt("spotify:track:c1", "c1", "context"),
            pt("spotify:delimiter", "delimiter0", "context"),
            pt("spotify:track:c2", "c2", "context"),
        ];
        let removed = queue_without(&next, "c1").expect("found");
        assert_eq!(removed.iter().map(|t| t.uid.as_str()).collect::<Vec<_>>(), vec!["q0", "delimiter0", "c2"]);
        assert!(queue_without(&next, "nope").is_none());
        assert!(queue_without(&next, "delimiter0").is_none(), "delimiters can't be removed");

        let moved = queue_moved(&next, "c2", 0).expect("found");
        assert_eq!(moved.iter().map(|t| t.uid.as_str()).collect::<Vec<_>>(), vec!["c2", "q0", "c1", "delimiter0"]);
        assert_eq!(moved[0].provider, "queue");
        assert_eq!(moved[0].metadata.get("is_queued").map(String::as_str), Some("true"));
        assert!(queue_moved(&next, "", 0).is_none());
        let promoted = queue_promoted(&next, &["c1".into(), "c2".into()]).expect("found");
        assert_eq!(promoted.iter().map(|t| t.uid.as_str()).collect::<Vec<_>>(),
            vec!["c1", "c2", "q0", "delimiter0"]);
        assert_eq!(promoted[0].provider, "queue");
        assert_eq!(promoted[1].metadata.get("is_queued").map(String::as_str), Some("true"));
        assert!(queue_promoted(&next, &["c1".into(), "missing".into()]).is_none());
        let moved = queue_moved(&next, "q0", 1).expect("found");
        assert_eq!(moved.iter().map(|t| t.uid.as_str()).collect::<Vec<_>>(), vec!["c1", "q0", "delimiter0", "c2"]);
        let moved = queue_moved(&next, "q0", 99).expect("found");
        assert_eq!(moved.last().map(|t| t.uid.as_str()), Some("q0"));
        assert_eq!(moved[0].uid, "c1");

        let cleared = queue_cleared(&next);
        assert_eq!(cleared.len(), 3);
        assert!(cleared.iter().all(|t| t.provider != "queue"));

        let body = set_queue(&cleared, &[], "rev1", "id");
        assert_eq!(body["command"]["endpoint"], "set_queue");
        assert_eq!(body["command"]["queue_revision"], "rev1");
        assert_eq!(body["command"]["next_tracks"][0]["uid"], "c1");
        assert_eq!(body["command"]["prev_tracks"], json!([]));
    }

    #[test]
    fn provided_track_keeps_metadata() {
        let mut t = pt("spotify:track:x", "u", "queue");
        t.metadata.insert("is_queued".into(), "true".into());
        let v = provided_track_json(&t);
        assert_eq!(v, json!({"uri":"spotify:track:x","uid":"u","provider":"queue","metadata":{"is_queued":"true"}}));
    }

    #[test]
    fn path_segments_are_encoded() {
        assert_eq!(seg("abc-123_x.y"), "abc-123_x.y");
        assert_eq!(seg("a/b c"), "a%2Fb%20c");
    }
}
