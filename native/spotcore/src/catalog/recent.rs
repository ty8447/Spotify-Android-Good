//! `catalog.recentlyPlayed` via spclient `recently-played/v3` (the endpoint and query the web
//! player uses: `?format=json&offset=0&limit=N&filter=default,collection-new-episodes`).

use super::http::{self, JSON};
use super::refs;
use super::util::encode_component;
use crate::engine;
use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::MediaRef;
use crate::rpc::parse_args;
use librespot_core::Session;
use serde::Deserialize;
use serde_json::{json, Value};

#[derive(Deserialize)]
struct Args {
    #[serde(default = "default_limit")]
    limit: u32,
}

fn default_limit() -> u32 {
    50
}

/// Context URIs (newest first) from a recently-played response. Accepts `playContexts`
/// (current) and the older `contexts`/`items` arrays; entries without a URI are skipped.
pub(crate) fn parse_contexts(body: &[u8]) -> AppResult<Vec<String>> {
    let v: Value = http::json(body)?;
    let arr = ["playContexts", "play_contexts", "contexts", "items"]
        .iter()
        .find_map(|k| v.get(*k).and_then(Value::as_array))
        .cloned()
        .unwrap_or_default();
    let mut entries: Vec<(i64, usize, String)> = arr
        .iter()
        .enumerate()
        .filter_map(|(i, c)| {
            let uri = c.get("uri").or_else(|| c.get("contextUri")).and_then(Value::as_str)?.to_string();
            let ts = c
                .get("lastPlayedTime")
                .or_else(|| c.get("last_played_time"))
                .or_else(|| c.get("timestamp"))
                .and_then(|t| t.as_i64().or_else(|| t.as_str().and_then(|s| s.parse().ok())))
                .unwrap_or(0);
            (!uri.is_empty()).then_some((ts, i, uri))
        })
        .collect();
    // Newest first; keep server order for equal/missing timestamps.
    entries.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
    Ok(entries.into_iter().map(|(_, _, u)| u).collect())
}

// Diagnostics only. Never logs account identifiers, credentials, or individual URIs.
/*fn log_recent_response(source: &str, requested_limit: u32, body: &[u8]) {
    let Ok(v) = serde_json::from_slice::<Value>(body) else {
        log::warn!("[recent-diagnostic] {source}: response is not valid JSON");
        return;
    };
    let selected = ["playContexts", "play_contexts", "contexts", "items"]
        .iter()
        .find_map(|key| v.get(*key).and_then(Value::as_array).map(|items| (*key, items)));
    let Some((field, contexts)) = selected else {
        log::info!("[recent-diagnostic] {source}: requested_limit={requested_limit} no recognized context array");
        return;
    };
    let mut with_uri = 0usize;
    let mut with_timestamp = 0usize;
    let mut oldest: Option<i64> = None;
    let mut newest: Option<i64> = None;
    for c in contexts {
        let valid_uri = c.get("uri").or_else(|| c.get("contextUri"))
            .and_then(Value::as_str).is_some_and(|uri| !uri.is_empty());
        if !valid_uri { continue; }
        with_uri += 1;
        let timestamp = c.get("lastPlayedTime")
            .or_else(|| c.get("last_played_time"))
            .or_else(|| c.get("timestamp"))
            .and_then(|t| t.as_i64().or_else(|| t.as_str().and_then(|s| s.parse::<i64>().ok())));
        if let Some(ts) = timestamp {
            with_timestamp += 1;
            oldest = Some(oldest.map_or(ts, |previous| previous.min(ts)));
            newest = Some(newest.map_or(ts, |previous| previous.max(ts)));
        }
    }
    log::info!(
        "[recent-diagnostic] {source}: requested_limit={requested_limit} field={field} raw_count={} valid_uri_count={with_uri} timestamp_count={with_timestamp} oldest_timestamp={oldest:?} newest_timestamp={newest:?}",
        contexts.len()
    );
}*/

// One diagnostic pagination probe per application process; never affects returned items.
//static PAGINATION_PROBED: std::sync::atomic::AtomicBool =
//    std::sync::atomic::AtomicBool::new(false);

/*async fn probe_recent_pagination(session: &Session, user: &str, first_body: &[u8]) {
    use std::sync::atomic::Ordering;
    if PAGINATION_PROBED.swap(true, Ordering::Relaxed) {
        return;
    }
    let first = match parse_contexts(first_body) {
        Ok(items) => items,
        Err(error) => {
            log::warn!("[recent-pagination] cannot parse first page: {error}");
            return;
        }
    };
    let offset = first.len();
    if offset == 0 {
        log::info!("[recent-pagination] first page empty; skipping probe");
        return;
    }
    let endpoint = format!(
        "/recently-played/v3/user/{}/recently-played?format=json&offset={offset}&limit=50&filter=default,collection-new-episodes",
        encode_component(user)
    );
    log::info!("[recent-pagination] requesting second page: offset={offset} limit=50");
    match http::spc_get(session, &endpoint, Some(JSON)).await {
        Ok(body) => {
            log_recent_response("pagination_page_2", 50, &body);
            match parse_contexts(&body) {
                Ok(second) => {
                    let first_set: std::collections::HashSet<&str> =
                        first.iter().map(String::as_str).collect();
                    let overlap = second.iter()
                        .filter(|uri| first_set.contains(uri.as_str())).count();
                    log::info!(
                        "[recent-pagination] first_count={} second_count={} overlapping_uris={} new_uris={}",
                        first.len(), second.len(), overlap, second.len() - overlap
                    );
                }
                Err(error) => log::warn!("[recent-pagination] cannot parse second page: {error}"),
            }
        }
        Err(error) => log::warn!("[recent-pagination] second-page request failed: {error:?}"),
    }
}*/

pub(crate) async fn recently_played(session: &Session, limit: u32) -> AppResult<Vec<MediaRef>> {
    let user = engine::username().unwrap_or_else(|| session.username());
    if user.is_empty() {
        return Err(AppError::new(ErrorCode::NotLoggedIn, "no username"));
    }
    let limit = limit.clamp(1, 50);
    let endpoint = format!(
        "/recently-played/v3/user/{}/recently-played?format=json&offset=0&limit={limit}&filter=default,collection-new-episodes",
        encode_component(&user)
    );
    let body = http::spc_get(session, &endpoint, Some(JSON)).await?;
    //log_recent_response("recently_played", limit, &body);
    let uris = parse_contexts(&body)?;
    //let parsed_count = uris.len();
    let resolved = refs::resolve(session, &uris).await;
    //log::info!("[recent-diagnostic] recently_played: parsed_count={parsed_count} resolved_count={}", resolved.len());
    Ok(resolved)
}

/// Context-level history with server timestamps. The v3 endpoint does not provide a full
/// sequence of plays, so do not synthesize per-track counts or playlist-add events.
pub(crate) async fn history_rpc(args: Value) -> AppResult<Value> {
    let a: Args = parse_args(args)?;
    let session = engine::session()?;
    let user = engine::username().unwrap_or_else(|| session.username());
    if user.is_empty() { return Err(AppError::new(ErrorCode::NotLoggedIn, "no username")); }
    let limit = a.limit.clamp(1, 50);
    let endpoint = format!(
        "/recently-played/v3/user/{}/recently-played?format=json&offset=0&limit={limit}&filter=default,collection-new-episodes",
        encode_component(&user)
    );
    let body = http::spc_get(&session, &endpoint, Some(JSON)).await?;
    //log_recent_response("history_rpc", limit, &body);
    //probe_recent_pagination(&session, &user, &body).await;
    let v: Value = http::json(&body)?;
    let contexts = ["playContexts", "play_contexts", "contexts", "items"].iter()
        .find_map(|k| v.get(*k).and_then(Value::as_array)).cloned().unwrap_or_default();
    let mut entries = Vec::new();
    for c in contexts {
        let Some(uri) = c.get("uri").or_else(|| c.get("contextUri")).and_then(Value::as_str) else { continue; };
        let timestamp = c.get("lastPlayedTime").or_else(|| c.get("last_played_time"))
            .or_else(|| c.get("timestamp"))
            .and_then(|v| v.as_i64().or_else(|| v.as_str().and_then(|s| s.parse::<i64>().ok())))
            .unwrap_or(0);
        let track = c.get("lastPlayedTrackUri").or_else(|| c.get("last_played_track_uri"))
            .and_then(Value::as_str).map(str::to_owned);
        entries.push((uri.to_owned(), timestamp, track));
    }
    entries.sort_by(|a, b| b.1.cmp(&a.1));
    let uris: Vec<String> = entries.iter().map(|e| e.0.clone()).collect();
    //let parsed_count = uris.len();
    let media = refs::resolve(&session, &uris).await;
    //let resolved_count = media.len();
    let mut items = Vec::new();
    for (uri, timestamp, track) in entries {
        if let Some(context) = media.iter().find(|m| m.uri == uri) {
            items.push(json!({ "context": context, "timestampMs": timestamp, "lastTrackUri": track }));
        } else if let Some(context) = collection_ref(&uri) {
            // Liked Songs and Your Episodes aren't catalog entities: they were dropped (missing
            // from Recents).
            items.push(json!({ "context": context, "timestampMs": timestamp, "lastTrackUri": track }));
        }
    }
    //log::info!("[recent-diagnostic] history_rpc: parsed_count={parsed_count} resolved_count={resolved_count} returned_count={}", items.len());
    Ok(json!({ "items": items }))
}

/// A Recents row for the user's own collections (`spotify:user:<u>:collection[:your-episodes]`).
fn collection_ref(uri: &str) -> Option<Value> {
    let name = if uri.ends_with(":collection:your-episodes") {
        "Your Episodes"
    } else if uri.ends_with(":collection") || uri.contains(":collection:tracks") {
        "Liked Songs"
    } else {
        return None;
    };
    Some(json!({ "type": "collection", "uri": uri, "name": name }))
}

/// `catalog.refs {uris}`: rows (name, type, images) for any entity URIs; the user's collections
/// (Liked Songs, Your Episodes) included.
pub(crate) async fn refs_rpc(args: Value) -> AppResult<Value> {
    #[derive(serde::Deserialize)]
    struct Uris {
        uris: Vec<String>,
    }
    let a: Uris = parse_args(args)?;
    let session = engine::session()?;
    let uris: Vec<String> = a.uris.into_iter().take(200).collect();
    let mut items: Vec<Value> = refs::resolve(&session, &uris).await.iter().filter_map(|r| serde_json::to_value(r).ok()).collect();
    items.extend(uris.iter().filter_map(|u| collection_ref(u)));
    Ok(json!({ "items": items }))
}

pub(crate) async fn rpc(args: Value) -> AppResult<Value> {
    let a: Args = parse_args(args)?;
    let session = engine::session()?;
    Ok(json!({ "items": recently_played(&session, a.limit).await? }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn collections_get_a_row() {
        assert_eq!(collection_ref("spotify:user:me:collection").unwrap()["name"], "Liked Songs");
        assert_eq!(collection_ref("spotify:user:me:collection:your-episodes").unwrap()["name"], "Your Episodes");
        assert!(collection_ref("spotify:album:x").is_none());
    }

    #[test]
    fn parses_play_contexts() {
        let body = br#"{"playContexts":[
            {"uri":"spotify:playlist:37i9dQZF1DXcBWIGoYBM5M","lastPlayedTime":1700000000000,"lastPlayedTrackUri":"spotify:track:x"},
            {"uri":"spotify:album:6XhjNHCyCDyyGJRM5mg40G","lastPlayedTime":1700000500000},
            {"lastPlayedTime":1},
            {"uri":"spotify:user:alice:collection","lastPlayedTime":"1600000000000"}
        ]}"#;
        assert_eq!(
            parse_contexts(body).unwrap(),
            vec!["spotify:album:6XhjNHCyCDyyGJRM5mg40G", "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", "spotify:user:alice:collection"]
        );
        assert!(parse_contexts(br#"{"somethingElse":[]}"#).unwrap().is_empty());
        assert_eq!(parse_contexts(br#"{"contexts":[{"uri":"spotify:show:x"}]}"#).unwrap(), vec!["spotify:show:x"]);
    }
}

/// `catalog.playHistory`: the account's last played songs, newest first, each with when and
/// where (context) it played. Web API `me/player/recently-played`, which keeps only the last 50.
/// Short-lived history cache and cooldown, scoped to the logged-in account.
static HISTORY_STATE: std::sync::Mutex<Option<(String, std::time::Instant, Value, std::time::Instant)>> =
    std::sync::Mutex::new(None);

pub(crate) async fn play_history(_args: Value) -> AppResult<Value> {
    let session = engine::session()?;
    let username = engine::username().unwrap_or_else(|| session.username());
    let now = std::time::Instant::now();
    {
        let guard = HISTORY_STATE.lock().unwrap_or_else(|e| e.into_inner());
        if let Some((owner, fetched, cached, retry_at)) = guard.as_ref() {
            if owner == &username {
                if now < *retry_at || now.duration_since(*fetched) < std::time::Duration::from_secs(90) {
                    return Ok(cached.clone());
                }
            }
        }
    }
    let token = match engine::oauth_token() {
        Some((token, _)) => token,
        None => http::timed(session.login5().auth_token()).await?.access_token,
    };
    let req = ::http::Request::builder().uri("https://api.spotify.com/v1/me/player/recently-played?limit=50")
        .header(::http::header::AUTHORIZATION, format!("Bearer {token}"))
        .header(::http::header::ACCEPT, JSON)
        .body(bytes::Bytes::new()).map_err(|e| AppError::invalid(format!("invalid request: {e}")))?;
    match http::send(Some(&session), req, http::TIMEOUT).await {
        Ok(body) => {
            let result = json!({ "items": parse_play_history(&body)? });
            let mut guard = HISTORY_STATE.lock().unwrap_or_else(|e| e.into_inner());
            *guard = Some((username, now, result.clone(), now));
            Ok(result)
        }
        Err(e) => {
            if e.status == Some(429) {
                log::warn!("Play history rate limited; backing off for 120 seconds");
                let mut guard = HISTORY_STATE.lock().unwrap_or_else(|e| e.into_inner());
                if let Some((owner, _, cached, retry_at)) = guard.as_mut() {
                    if owner == &username {
                        *retry_at = std::time::Instant::now() + std::time::Duration::from_secs(120);
                        return Ok(cached.clone());
                    }
                }
            }
            Err(e.error)
        }
    }
}

fn parse_play_history(body: &[u8]) -> AppResult<Vec<Value>> {
    let v: Value = http::json(body)?;
    Ok(v["items"].as_array().into_iter().flatten().filter_map(|i| {
        let t = &i["track"];
        let artists: Vec<&str> = t["artists"].as_array()?.iter().filter_map(|a| a["name"].as_str()).collect();
        Some(json!({
            "track": { "type": "track", "uri": t["uri"].as_str()?, "name": t["name"].as_str()?,
                       "subtitle": artists.join(", "), "images": t["album"]["images"] },
            "playedAt": i["played_at"].as_str()?,
            "contextUri": i["context"]["uri"],
        }))
    }).collect())
}

#[cfg(test)]
mod play_history_tests {
    #[test]
    fn parses_plays() {
        let body = br#"{"items":[{"played_at":"2026-10-14T12:00:00Z","context":{"uri":"spotify:album:a"},
            "track":{"uri":"spotify:track:t","name":"Song","artists":[{"name":"X"},{"name":"Y"}],"album":{"images":[]}}},
            {"played_at":"2026-10-14T11:00:00Z","context":null,"track":{"uri":"spotify:track:u","name":"Loose","artists":[]}}]}"#;
        let p = super::parse_play_history(body).unwrap();
        assert_eq!(p.len(), 2);
        assert_eq!(p[0]["track"]["subtitle"], "X, Y");
        assert!(p[1]["contextUri"].is_null());
    }
}

/// Spotify's Recents page as the official app reads it: the list-platform list
/// `spotify:list:recents:page` (`POST /playlist/spotify.list.v1.ListReadService/GetLists`,
/// `GetListsRequest{1: GetListRequest{1: ListIdentifier{1: uri}, 2: RequestOptions{4:
/// RequestedFields{1: all}, 6: Pagination{1: length, 3: offset}}}}`). Each item is a context (or a
/// song, an episode): `Attributes{1: uri, 5|8|11|18: Timestamp, 24: FormatListAttributes{1: {1: key,
/// 2: value}}}`; the format attribute `group_metadata` is a base64 `GroupMetadata{1: items_count,
/// 2: repeated entity_uris (what played), 5: group_name}`, `recent_type_saved|shared|auto_generated`
/// mark rows that were not plays.
const RECENTS_LIST: &str = "spotify:list:recents:page";
pub(crate) const LISTS_PATH: &str = "/playlist/spotify.list.v1.ListReadService/GetLists";

/// A `GetListsRequest` for one list-platform list (all fields, a window of it).
pub(crate) fn list_request(list: &str, offset: u32, limit: u32) -> Vec<u8> {
    use super::messaging::{append_pb_message, append_pb_string, append_pb_varint};
    let varint = |buf: &mut Vec<u8>, field: u8, v: u64| {
        buf.push(field << 3);
        append_pb_varint(buf, v);
    };
    let mut id = Vec::new();
    append_pb_string(&mut id, 1, list);
    let mut page = Vec::new();
    varint(&mut page, 1, u64::from(limit));
    varint(&mut page, 3, u64::from(offset));
    let mut opts = Vec::new();
    append_pb_message(&mut opts, 4, &[0x08, 0x01]);
    append_pb_message(&mut opts, 6, &page);
    let mut get = Vec::new();
    append_pb_message(&mut get, 1, &id);
    append_pb_message(&mut get, 2, &opts);
    let mut req = Vec::new();
    append_pb_message(&mut req, 1, &get);
    req
}

/// (items the server sent, the rows: `{uri, timestampMs, entityUris, itemsCount, kind}`).
pub(crate) fn parse_recents(b: &[u8]) -> (usize, Vec<Value>) {
    use super::showextras::fields;
    use base64::Engine as _;
    let text = |s: &[u8]| String::from_utf8_lossy(s).into_owned();
    let mut sent = 0;
    let mut out: Vec<Value> = Vec::new();
    let entities = fields(b).into_iter().filter(|f| f.0 == 1).flat_map(|(_, _, r)| fields(r).into_iter().filter(|f| f.0 == 1).map(|f| f.2).collect::<Vec<_>>());
    for entity in entities {
        for (_, _, item) in fields(entity).into_iter().filter(|f| f.0 == 2) {
            sent += 1;
            let (mut uri, mut at, mut keys) = (String::new(), 0u64, Vec::<(String, String)>::new());
            for (_, _, attrs) in fields(item).into_iter().filter(|f| f.0 == 2) {
                for (f, _, v) in fields(attrs) {
                    match f {
                        1 => uri = text(v),
                        5 | 8 | 11 | 18 => at = at.max(fields(v).iter().find(|x| x.0 == 1).map_or(0, |x| x.1) * 1000),
                        24 => keys = fields(v).into_iter().filter(|x| x.0 == 1).map(|(_, _, e)| {
                            let kv = fields(e);
                            let get = |n| kv.iter().find(|x| x.0 == n).map(|x| text(x.2)).unwrap_or_default();
                            (get(1), get(2))
                        }).collect(),
                        _ => {}
                    }
                }
            }
            if uri.is_empty() {
                continue;
            }
            let attr = |k: &str| keys.iter().find(|(key, _)| key == k).map(|(_, v)| v.as_str());
            // A play time in the format attributes (seconds or ms) wins over the list timestamps.
            if let Some(t) = keys.iter().find(|(k, _)| k.contains("played_at") || k == "timestamp").and_then(|(_, v)| v.parse::<u64>().ok()) {
                at = if t < 10_000_000_000 { t * 1000 } else { t };
            }
            let (mut played, mut count) = (Vec::new(), 0u64);
            if let Some(meta) = attr("group_metadata").and_then(|s| base64::engine::general_purpose::STANDARD.decode(s).ok()) {
                for (f, v, s) in fields(&meta) {
                    match f {
                        1 => count = v,
                        2 => played.push(text(s)),
                        _ => {}
                    }
                }
            }
            if played.is_empty() && (uri.starts_with("spotify:track:") || uri.starts_with("spotify:episode:")) {
                played.push(uri.clone());
            }
            let kind = ["shared", "saved", "auto_generated"].into_iter().find(|k| attr(&format!("recent_type_{k}")).is_some()).unwrap_or("played");
            // Consecutive items with the same `group_id_<x>` key (x ≠ 0) are one Recents row: the
            // context, and the songs played in it (listed again as their own items).
            let group = keys.iter().map(|(k, _)| k).find(|k| k.starts_with("group_id_") && *k != "group_id_0").cloned();
            let row = json!({ "uri": uri, "timestampMs": at, "entityUris": played, "itemsCount": count, "kind": kind, "group": group });
            if out.len() < 3 {
                log::debug!("recents item: keys={:?}", keys.iter().map(|(k, _)| k.as_str()).collect::<Vec<_>>());
            }
            // A song right after its context's row is one of the songs played there (listed in the
            // row's group, or within its count): it folds into that row instead of standing alone.
            let song_of_last = |last: &Value| {
                let listed = last["entityUris"].as_array().is_some_and(|a| a.iter().any(|u| u == &row["uri"]));
                let room = last["itemsCount"].as_u64().unwrap_or(0) > last["entityUris"].as_array().map_or(0, |a| a.len() as u64);
                is_song(&uri) && !is_song(last["uri"].as_str().unwrap_or_default()) && (listed || room)
            };
            match out.last_mut() {
                Some(last) if (group.is_some() && last["group"] == row["group"]) || song_of_last(last) => merge_group(last, row),
                _ => out.push(row),
            }
        }
    }
    (sent, out)
}

fn is_song(uri: &str) -> bool {
    uri.starts_with("spotify:track:") || uri.starts_with("spotify:episode:")
}

/// Folds [row] into its group's row; the context (not a song) heads the group.
fn merge_group(head: &mut Value, mut row: Value) {
    if is_song(head["uri"].as_str().unwrap_or_default()) && !is_song(row["uri"].as_str().unwrap_or_default()) {
        std::mem::swap(head, &mut row);
    }
    let mut songs: Vec<Value> = head["entityUris"].as_array().cloned().unwrap_or_default();
    let extra = row["entityUris"].as_array().cloned().unwrap_or_default();
    for u in extra.into_iter().chain([row["uri"].clone()]) {
        if is_song(u.as_str().unwrap_or_default()) && !songs.contains(&u) {
            songs.push(u);
        }
    }
    head["itemsCount"] = json!(head["itemsCount"].as_u64().unwrap_or(0).max(songs.len() as u64));
    head["entityUris"] = json!(songs);
    head["timestampMs"] = json!(head["timestampMs"].as_u64().unwrap_or(0).max(row["timestampMs"].as_u64().unwrap_or(0)));
}

/// `catalog.recents {offset, limit}` → `{items, next}` (`next` is null at the end).
pub(crate) async fn recents_rpc(args: Value) -> AppResult<Value> {
    #[derive(Deserialize)]
    struct Page {
        #[serde(default)]
        offset: u32,
        #[serde(default = "default_limit")]
        limit: u32,
    }
    let a: Page = parse_args(args)?;
    let limit = a.limit.clamp(1, 50);
    let session = engine::session()?;
    let raw = http::spc_post_idempotent(&session, LISTS_PATH, http::PROTOBUF, http::PROTOBUF, &list_request(RECENTS_LIST, a.offset, limit))
        .await
        .map_err(|e| e.error)?;
    let (sent, items) = parse_recents(&raw);
    log::info!("recents: offset={} sent={sent} rows={} bytes={}", a.offset, items.len(), raw.len());
    let next = (sent as u32 >= limit).then(|| a.offset + sent as u32);
    Ok(json!({ "items": items, "next": next }))
}

#[cfg(test)]
mod recents_tests {
    use super::*;
    use base64::Engine as _;

    #[test]
    fn builds_and_parses_the_recents_page() {
        let req = list_request(RECENTS_LIST, 50, 50);
        assert!(req.windows(RECENTS_LIST.len()).any(|w| w == RECENTS_LIST.as_bytes()));
        // GroupMetadata{1: 2, 2: "spotify:track:a", 2: "spotify:track:b"}
        let mut meta = vec![0x08, 0x02];
        for t in ["spotify:track:a", "spotify:track:b"] {
            meta.extend([0x12, t.len() as u8]);
            meta.extend(t.as_bytes());
        }
        let b64 = base64::engine::general_purpose::STANDARD.encode(&meta);
        let pb = |field: u8, body: &[u8]| {
            let mut v = Vec::new();
            super::super::messaging::append_pb_varint(&mut v, u64::from(field) << 3 | 2);
            super::super::messaging::append_pb_varint(&mut v, body.len() as u64);
            v.extend(body);
            v
        };
        let entry = [pb(1, b"group_metadata"), pb(2, b64.as_bytes())].concat();
        let attrs = [pb(1, b"spotify:playlist:x"), pb(18, &[0x08, 0x64]), pb(24, &pb(1, &entry))].concat();
        let item = pb(2, &attrs);
        let resp = pb(1, &pb(1, &pb(2, &item)));
        let (sent, items) = parse_recents(&resp);
        assert_eq!(sent, 1);
        assert_eq!(items[0]["uri"], "spotify:playlist:x");
        assert_eq!(items[0]["timestampMs"], 100_000);
        assert_eq!(items[0]["entityUris"][1], "spotify:track:b");
        assert_eq!(items[0]["itemsCount"], 2);
        assert_eq!(items[0]["kind"], "played");
        // The group's songs, listed after it with the same group key, fold into its row.
        let grouped = |uri: &[u8]| {
            let entry = [pb(1, b"group_id_7"), pb(2, b"1")].concat();
            pb(2, &pb(2, &[pb(1, uri), pb(24, &pb(1, &entry))].concat()))
        };
        let resp = pb(1, &pb(1, &[grouped(b"spotify:playlist:x"), grouped(b"spotify:track:c"), grouped(b"spotify:track:a")].concat()));
        let (sent, items) = parse_recents(&resp);
        assert_eq!((sent, items.len()), (3, 1));
        assert_eq!(items[0]["uri"], "spotify:playlist:x");
        assert_eq!(items[0]["entityUris"], json!(["spotify:track:c", "spotify:track:a"]));
        // No group keys: a song right after a context row with room in its count joins it.
        let meta = base64::engine::general_purpose::STANDARD.encode([0x08, 0x01]);
        let entry = [pb(1, b"group_metadata"), pb(2, meta.as_bytes())].concat();
        let head = pb(2, &pb(2, &[pb(1, b"spotify:album:z"), pb(24, &pb(1, &entry))].concat()));
        let song = pb(2, &pb(2, &pb(1, b"spotify:track:s")));
        let other = pb(2, &pb(2, &pb(1, b"spotify:track:t")));
        let (_, items) = parse_recents(&pb(1, &pb(1, &[head, song, other].concat())));
        assert_eq!(items.len(), 2);
        assert_eq!(items[0]["entityUris"], json!(["spotify:track:s"]));
    }
}
