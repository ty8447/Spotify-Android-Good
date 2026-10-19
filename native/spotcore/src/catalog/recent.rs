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
    //let uris = parse_contexts(&body)?;
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
        }
    }
    //log::info!("[recent-diagnostic] history_rpc: parsed_count={parsed_count} resolved_count={resolved_count} returned_count={}", items.len());
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
