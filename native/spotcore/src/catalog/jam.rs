//! Jam service status, verified session summary, and explicit start/leave mutations.
//! No join, invitation, queue or playback changes are performed here.
//! Sensitive session identifiers stay in Rust and are never returned to the UI.
use super::http;
use crate::engine;
use crate::error::{AppError, AppResult};
use librespot_core::Session;
use serde_json::{json, Value};

const MAX_REPLY_BYTES: usize = 512 * 1024;
const CURRENT_SESSION_PATH: &str = "/social-connect/v2/sessions/current";
const START_SESSION_PATH: &str = "/social-connect/v3/sessions/new";
const LEAVE_SESSION_PREFIX: &str = "/social-connect/v3/sessions/";
const DEVICE_STATUS_PREFIX: &str = "/social-connect/v2/devices/";
// Verified in the official android-jam-endpoints-impl hk9 Retrofit interface.
// Retrofit hk9.e uses the string literals "enabled" / "disabled", not JSON booleans.
// This is a PUT-style endpoint, not the v3 session-creation API.
const QUEUE_ONLY_MODE_PREFIX: &str = "/social-connect/v2/sessions/current/queue_only_mode/";

fn queue_only_mode_path(enabled: bool) -> String {
    // The official hk9 API declares "enabled" and "disabled" for this path parameter.
    format!("{QUEUE_ONLY_MODE_PREFIX}{}", if enabled { "enabled" } else { "disabled" })
}

fn valid_device_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 128 && id.bytes().all(|b|
        b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_'))
}

/// The official Jam status response is not yet decoded into a session model.
/// Only safe JSON field *names* and selected boolean flags are reported;
/// session identifiers, join tokens and any free-form values are never copied.
fn reply_format(body: &[u8]) -> &'static str {
    if body.is_empty() { "empty" }
    else if serde_json::from_slice::<Value>(body).is_ok() { "json" }
    else { "other" }
}

/// A bounded, safe summary of an actual v2 current-session reply (official dt21).
/// Session IDs, member identities and invitation/link tokens are deliberately excluded.
/// A session is only considered verified when Spotify reports a valid ID and
/// an explicit `active` boolean. Never infer activity from HTTP 200 alone.
fn session_summary(body: &[u8]) -> Option<Value> {
    let doc: Value = serde_json::from_slice(body).ok()?;
    let obj = doc.as_object()?;
    let session_id = obj.get("session_id")?.as_str()?;
    if !valid_device_id(session_id) { return None; }
    let active = obj.get("active")?.as_bool()?;
    let members = obj.get("session_members").and_then(Value::as_array)
        .and_then(|rows| u64::try_from(rows.len()).ok()).filter(|n| *n <= 500);
    let max_members = obj.get("maxMemberCount").and_then(Value::as_u64)
        .filter(|n| *n <= 500);
    Some(json!({
        "active": active,
        "isSessionOwner": obj.get("is_session_owner").and_then(Value::as_bool),
        "isControlling": obj.get("is_controlling").and_then(Value::as_bool),
        "isListening": obj.get("is_listening").and_then(Value::as_bool),
        "isPaused": obj.get("is_paused").and_then(Value::as_bool),
        "queueOnlyMode": obj.get("queue_only_mode").and_then(Value::as_bool),
        "memberCount": members,
        "maxMemberCount": max_members,
    }))
}

// A Jam join URI comes directly from a fresh active Spotify session; never
// generate a random token, log it, or return it in background/global status.
fn invitation_url(value: &Value) -> Option<String> {
    if value.get("active")?.as_bool()? != true { return None; }
    if value.get("user_capabilities").and_then(|v| v.get("invite"))
        .and_then(Value::as_bool) == Some(false) { return None; }
    let uri = value.get("join_session_uri")?.as_str()?;
    let token = uri.strip_prefix("spotify:socialsession:")?;
    if token.len() != 22 || !token.bytes().all(|b| b.is_ascii_alphanumeric()) {
        return None;
    }
    Some(format!("https://open.spotify.com/socialsession/{token}"))
}

// Spotify's active-session response may include a server-created short link.
// Do not call an unverified shortening API, derive Branch codes from the token,
// or accept a URL from arbitrary nested user/member fields.
fn invitation_short_url(value: &Value) -> Option<&str> {
    let fields = ["join_session_short_url", "join_session_short_link", "short_link",
        "short_url", "share_link", "share_url"];
    for field in fields {
        if let Some(url) = value.get(field).and_then(Value::as_str) {
            if let Some(code) = url.strip_prefix("https://spotify.link/") {
                if url.len() <= 84 && (6..=55).contains(&code.len()) &&
                    code.bytes().all(|b| b.is_ascii_alphanumeric()) {
                    return Some(url);
                }
            }
        }
    }
    None
}

/// User-triggered, read-only invitation retrieval. The resulting link is a
/// bearer invitation: the caller must send it only to the intended conversation.
pub(super) async fn invitation_link() -> AppResult<Value> {
    let session = engine::session()?;
    let device_id = session.device_id();
    if !valid_device_id(device_id) {
        return Ok(json!({"result": "unverified"}));
    }
    let path = format!("{CURRENT_SESSION_PATH}?local_device_id={device_id}");
    let data = match http::spc_get_plain(&session, &path, Some(http::JSON)).await {
        Ok(data) if data.len() <= MAX_REPLY_BYTES => data,
        _ => return Ok(json!({"result": "unverified"})),
    };
    let Ok(doc) = serde_json::from_slice::<Value>(&data) else {
        return Ok(json!({"result": "unverified"}));
    };
    if doc.get("active").and_then(Value::as_bool) != Some(true) ||
        doc.get("session_id").and_then(Value::as_str)
            .filter(|v| valid_device_id(v)).is_none() {
        return Ok(json!({"result": "no-active-session"}));
    }
    match invitation_url(&doc) {
        Some(url) => Ok(json!({"result": "ready", "url": url,
            "shortUrl": invitation_short_url(&doc)})),
        None => Ok(json!({"result": "unavailable"})),
    }
}

// The official hk9.Q Retrofit method uses POST /approval_request/join and
// fp6/dp6 serialize exactly: join_token, join_type, playback_control,
// join_attempt_id. For a link opened in Messages, ug80.DEEPLINKING is
// "share-link"; jf80 is CONTROL or LISTEN_AND_CONTROL.
const ASK_TO_JOIN_PATH: &str = "/social-connect/v2/sessions/approval_request/join";
// Verified official hk9.k and l2d0/w2d0/z2d0 serializers: username +
// discovery_method inside lookup_requests, with tokens returned in the reply.
// This queries the *conversation peer*, not our own current-session endpoint.
const LOOKUP_PEER_TOKENS_PATH: &str = "/social-connect/v1/tokens/lookup";

fn valid_peer_username(value: &str) -> bool {
    !value.is_empty() && value.len() <= 128 && value.bytes().all(|b|
        b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-' | b'.'))
}

// The official lookup reply is {tokens:[...]}. Until the obfuscated fws item
// serializer is available, recognize only exact peer-bound, explicit tokens.
// Never light up the button based on a token for a different user or a bare
// 200 response. Both flat and nested token representations are supported.
fn peer_lookup_token(doc: &Value, username: &str) -> Option<String> {
    let tokens = doc.get("tokens")?.as_array()?;
    for item in tokens {
        let owner = item.get("username").or_else(|| item.get("user_name"))
            .or_else(|| item.get("user_id")).and_then(Value::as_str);
        if owner != Some(username) { continue; }
        let token = ["token", "join_token", "join_session_token", "join_session_uri", "link_token"]
            .iter().filter_map(|field| item.get(*field)).find_map(|value| {
                value.as_str().or_else(|| value.get("token").and_then(Value::as_str))
                    .or_else(|| value.get("join_token").and_then(Value::as_str))
            })?;
        let canonical = if token.len() == 22 && token.bytes().all(|b| b.is_ascii_alphanumeric()) {
            format!("https://open.spotify.com/socialsession/{token}")
        } else if let Some(raw) = token.strip_prefix("spotify:socialsession:") {
            format!("https://open.spotify.com/socialsession/{raw}")
        } else { token.to_owned() };
        if parse_canonical_join_token(&canonical).is_some() { return Some(canonical); }
    }
    None
}

/// Read-only peer-specific Jam lookup for a direct Messages conversation.
/// No token is logged. A valid join invitation is only returned when Spotify
/// explicitly associates it with the requested username.
pub(super) async fn peer_lookup(args: Value) -> AppResult<Value> {
    let username = args.get("username").and_then(Value::as_str)
        .filter(|value| valid_peer_username(value))
        .ok_or_else(|| AppError::unavailable("A valid peer username is required"))?;
    let session = engine::session()?;
    if username == session.username() { return Ok(json!({"result":"inactive"})); }
    let body = serde_json::to_vec(&json!({"lookup_requests":[{
        "username": username, "discovery_method": "listening_activity"
    }]})).map_err(|_| AppError::unavailable("Could not prepare Jam lookup"))?;
    match http::spc_send_once(&session, ::http::Method::POST,
            LOOKUP_PEER_TOKENS_PATH, http::JSON, http::JSON, body).await {
        Ok(bytes) if bytes.len() <= MAX_REPLY_BYTES => {
            let Ok(doc) = serde_json::from_slice::<Value>(&bytes) else {
                return Ok(json!({"result":"unverified"}));
            };
            if let Some(url) = peer_lookup_token(&doc, username) {
                log::info!("SpotifyGoodJam: operation=peer-lookup result=active");
                return Ok(json!({"result":"active","invitationUrl":url}));
            }
            if doc.get("tokens").is_some_and(Value::is_array) {
                log::info!("SpotifyGoodJam: operation=peer-lookup result=inactive");
                Ok(json!({"result":"inactive"}))
            } else {
                log::info!("SpotifyGoodJam: operation=peer-lookup result=unverified-schema");
                Ok(json!({"result":"unverified"}))
            }
        }
        Ok(_) => Ok(json!({"result":"unverified"})),
        Err(error) => {
            log::info!("SpotifyGoodJam: operation=peer-lookup result=error status={:?}", error.status);
            Ok(json!({"result": if matches!(error.status, Some(404)) {"inactive"} else {"unverified"}}))
        }
    }
}


fn parse_canonical_join_token(url: &str) -> Option<&str> {
    let token = url.strip_prefix("https://open.spotify.com/socialsession/")
        .or_else(|| url.strip_prefix("spotify:socialsession:"))?;
    if token.len() == 22 && token.bytes().all(|b| b.is_ascii_alphanumeric()) {
        Some(token)
    } else { None }
}

fn valid_join_attempt_id(id: &str) -> bool {
    id.len() == 36 && id.bytes().enumerate().all(|(i, b)| {
        if matches!(i, 8 | 13 | 18 | 23) { b == b'-' }
        else { b.is_ascii_hexdigit() }
    })
}

/// One user-confirmed ask-to-join attempt. This is an approval request, NOT
/// an automatic direct join/Connect transfer. The caller must present the
/// two playback options and obtain an explicit button press first.
/// The token is never included in logs or the RPC result.
pub(super) async fn ask_to_join(args: Value) -> AppResult<Value> {
    let url = args.get("invitationUrl").and_then(Value::as_str)
        .ok_or_else(|| AppError::unavailable("A Jam invitation URL is required"))?;
    let token = parse_canonical_join_token(url)
        .ok_or_else(|| AppError::unavailable("A verified canonical Jam link is required"))?;
    let on_this_phone = args.get("listenOnThisPhone").and_then(Value::as_bool)
        .ok_or_else(|| AppError::unavailable("A listening-device selection is required"))?;
    let attempt_id = args.get("joinAttemptId").and_then(Value::as_str)
        .filter(|v| valid_join_attempt_id(v))
        .ok_or_else(|| AppError::unavailable("A fresh UUID join attempt is required"))?;
    let body = serde_json::to_vec(&json!({
        "join_token": token,
        "join_type": match args.get("discoveryMethod").and_then(Value::as_str) {
            Some("listening_activity") => "listening_activity",
            _ => "share-link",
        },
        "playback_control": if on_this_phone { "LISTEN_AND_CONTROL" } else { "CONTROL" },
        "join_attempt_id": attempt_id,
    })).map_err(|_| AppError::unavailable("Could not serialize join request"))?;
    let session = engine::session()?;
    match http::spc_send_once(&session, ::http::Method::POST,
            ASK_TO_JOIN_PATH, http::JSON, http::JSON, body).await {
        Ok(bytes) if bytes.len() <= MAX_REPLY_BYTES => {
            // Spotify's AskToJoinResponse includes an active_join_request.
            // A generic HTTP 200 alone does not prove the host received it.
            let state = match serde_json::from_slice::<Value>(&bytes) {
                Ok(doc) if doc.get("active_join_request").is_some_and(Value::is_object) => "pending",
                _ => "server-acknowledged",
            };
            log::info!("SpotifyGoodJam: operation=ask-to-join result={state}");
            Ok(json!({"result": state, "httpStatus": 200}))
        }
        Ok(_) => Ok(json!({"result": "unverified"})),
        Err(error) => {
            let state = if matches!(error.status, Some(404 | 410)) { "ended" }
                else if error.status.is_some() { "request-rejected" }
                else { "unverified" };
            log::info!("SpotifyGoodJam: operation=ask-to-join result={} status={:?}",
                state, error.status);
            Ok(json!({"result": state, "httpStatus": error.status}))
        }
    }
}

fn safe_field_name(key: &str) -> bool {
    if key.is_empty() || key.len() > 48 ||
        !key.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-') {
        return false;
    }
    let lower = key.to_ascii_lowercase();
    !["token", "secret", "credential", "password", "bearer", "auth", "user", "account",
      "email", "name", "deviceid", "sessionid", "joinid", "invite", "url", "uri", "link"]
        .iter().any(|fragment| lower.contains(fragment))
}

fn jam_flag_name(key: &str) -> bool {
    matches!(key.to_ascii_lowercase().as_str(),
        "active" | "isactive" | "is_active" | "injam" | "in_jam" | "isinjam" | "is_in_jam" |
        "jamactive" | "jam_active" | "isjamactive" | "jam_enabled" | "jamenabled" |
        "isjamenabled" | "broadcasting" | "isbroadcasting" | "is_broadcasting" |
        "host" | "ishost" | "is_host" | "connected" | "isconnected" | "is_connected" |
        "eligible" | "iseligible" | "joinable" | "isjoinable" | "canjoin" | "canstart" |
        "joined" | "isjoined")
}

fn status_shape(body: &[u8]) -> (Vec<String>, std::collections::BTreeMap<String, bool>) {
    const MAX_FIELDS: usize = 24;
    const MAX_FLAGS: usize = 12;
    fn visit(
        value: &Value,
        parent: &str,
        depth: u8,
        fields: &mut Vec<String>,
        flags: &mut std::collections::BTreeMap<String, bool>,
    ) {
        if depth >= 3 || fields.len() >= MAX_FIELDS { return; }
        let Some(map) = value.as_object() else { return; };
        let mut keys: Vec<&String> = map.keys().filter(|key| safe_field_name(key)).collect();
        keys.sort();
        for key in keys {
            if fields.len() >= MAX_FIELDS { break; }
            let path = if parent.is_empty() { key.to_owned() }
                else { format!("{parent}.{key}") };
            fields.push(path.clone());
            let child = map.get(key.as_str()).expect("key from object");
            if let Some(flag) = child.as_bool() {
                if jam_flag_name(key) && flags.len() < MAX_FLAGS { flags.insert(path, flag); }
            } else if child.is_object() {
                visit(child, &path, depth + 1, fields, flags);
            }
        }
    }
    let mut fields = Vec::new();
    let mut flags = std::collections::BTreeMap::new();
    if let Ok(value) = serde_json::from_slice::<Value>(body) {
        visit(&value, "", 0, &mut fields, &mut flags);
    }
    (fields, flags)
}

async fn check(session: &Session, path: &str, operation: &str) -> Value {
    // The official hk9 Webgate interface names both read-only GET routes.
    // Avoid librespot's implicit product/country/salt query parameters here.
    match http::spc_get_plain(session, path, Some(http::JSON)).await {
        Ok(body) if body.len() > MAX_REPLY_BYTES => {
            log::warn!("SpotifyGoodJam: operation={} result=oversized", operation);
            json!({"result": "oversized", "httpStatus": 200, "bytes": 0, "format": "unknown"})
        }
        Ok(body) => {
            let format = reply_format(&body);
            let (field_paths, boolean_flags) = if format == "json" {
                status_shape(&body)
            } else { (Vec::new(), std::collections::BTreeMap::new()) };
            let session = if operation.starts_with("current-") {
                session_summary(&body)
            } else { None };
            log::info!("SpotifyGoodJam: operation={} result=reply status=200 bytes={} format={} active={:?} members={:?}",
                operation, body.len(), format,
                session.as_ref().and_then(|v| v.get("active")).and_then(Value::as_bool),
                session.as_ref().and_then(|v| v.get("memberCount")).and_then(Value::as_u64));
            json!({"result": "reply", "httpStatus": 200, "bytes": body.len(),
                "format": format, "fieldPaths": field_paths, "booleanFlags": boolean_flags,
                "session": session})
        }
        Err(error) => {
            // A missing current session can be normal; don't claim it proves inactivity.
            // Never log a URL, session ID, join token, bearer or response contents.
            if operation == "current-session" && error.status == Some(404) {
                log::info!("SpotifyGoodJam: operation=current-session result=not-found status=404");
                json!({"result": "not-found", "httpStatus": 404,
                    "bytes": 0, "format": "unknown"})
            } else {
                log::warn!("SpotifyGoodJam: operation={} result=request-error status={:?}",
                    operation, error.status);
                json!({"result": "request-error", "httpStatus": error.status,
                    "bytes": 0, "format": "unknown"})
            }
        }
    }
}

/// A host-only mutation: queue_only_mode=true limits guests to adding songs.
/// Perform a fresh owner check before the single PUT and verify by reading again.
/// Returning HTTP 200 alone does not mean the setting was applied.
pub(super) async fn set_queue_only_mode(args: Value) -> AppResult<Value> {
    let enabled = args.get("enabled").and_then(Value::as_bool)
        .ok_or_else(|| AppError::unavailable("A boolean guest restriction is required"))?;
    let session = engine::session()?;
    let device_id = session.device_id();
    if !valid_device_id(device_id) {
        return Err(AppError::unavailable("Jam guest controls need a valid device"));
    }
    let current_path = format!("{CURRENT_SESSION_PATH}?local_device_id={device_id}");
    let body = match http::spc_get_plain(&session, &current_path, Some(http::JSON)).await {
        Ok(body) if body.len() <= MAX_REPLY_BYTES => body,
        _ => return Ok(json!({"result": "unverified"})),
    };
    let Some(summary) = session_summary(&body) else {
        return Ok(json!({"result": "unverified"}));
    };
    if summary["active"] != true || summary["isSessionOwner"] != true {
        return Ok(json!({"result": "not-host"}));
    }
    if summary["queueOnlyMode"].as_bool() == Some(enabled) {
        return Ok(json!({"result": "already-set", "current": {
            "result": "reply", "session": summary
        }}));
    }
    let path = queue_only_mode_path(enabled);
    match http::spc_send_once(&session, ::http::Method::PUT, &path,
        http::JSON, http::JSON, Vec::new()).await {
        Ok(_) => {
            // The GET can briefly lag a successful write. Retry the *read only* once;
            // never issue a second non-idempotent PUT automatically.
            let mut current = check(&session, &current_path, "current-after-guest-change").await;
            let confirmed = |v: &Value| v["session"]["active"] == true &&
                v["session"]["isSessionOwner"] == true &&
                v["session"]["queueOnlyMode"].as_bool() == Some(enabled);
            if !confirmed(&current) {
                tokio::time::sleep(std::time::Duration::from_millis(350)).await;
                current = check(&session, &current_path, "current-after-guest-change").await;
            }
            let verified = confirmed(&current);
            log::info!("SpotifyGoodJam: operation=guest-queue-only result={} enabled={}",
                if verified { "verified" } else { "unverified" }, enabled);
            Ok(json!({"result": if verified { "verified" } else { "unverified" },
                "current": current}))
        }
        Err(error) => {
            log::warn!("SpotifyGoodJam: operation=guest-queue-only result=request-error status={:?}",
                error.status);
            Ok(json!({"result": if error.status.is_some() { "request-rejected" }
                else { "unverified" }, "httpStatus": error.status}))
        }
    }
}

pub(super) async fn service_status() -> AppResult<Value> {
    let session = engine::session()?;
    let device_id = session.device_id();
    if !valid_device_id(device_id) {
        return Err(AppError::unavailable("Jam status cannot use this device identifier"));
    }
    let current_path = format!("{CURRENT_SESSION_PATH}?local_device_id={device_id}");
    let device_path = format!("{DEVICE_STATUS_PREFIX}{device_id}/jam_status");
    let (current, device) = tokio::join!(
        check(&session, &current_path, "current-session"),
        check(&session, &device_path, "device-status"),
    );
    Ok(json!({"current": current, "device": device}))
}

/// v3NewSessionRequest is a Kotlin-serialized JSON model (official jee1):
/// `activate` and `local_device_id` are mandatory; `origin` and
/// `configuration` are optional. The official convenience constructor defaults
/// `activate` to true. Do not guess the optional origin or configuration.
fn start_payload(device_id: &str) -> AppResult<Vec<u8>> {
    if !valid_device_id(device_id) {
        return Err(AppError::unavailable("Jam creation cannot use this device identifier"));
    }
    serde_json::to_vec(&json!({"activate": true, "local_device_id": device_id}))
        .map_err(|_| AppError::unavailable("Could not prepare Jam creation request"))
}

/// Submit at most one non-idempotent POST per explicit UI action.
/// The response is never exposed raw: it may contain session IDs, join tokens
/// or other private details. We perform a separate read to check the result,
/// but do not label the Jam 'active' without a decoded session contract.
pub(super) async fn start_session() -> AppResult<Value> {
    let session = engine::session()?;
    let device_id = session.device_id();
    let body = start_payload(device_id)?;
    let result = http::spc_send_once(
        &session,
        ::http::Method::POST,
        START_SESSION_PATH,
        http::JSON,
        http::JSON,
        body,
    ).await;
    match result {
        Ok(response) => {
            if response.len() > MAX_REPLY_BYTES {
                log::warn!("SpotifyGoodJam: operation=start-session result=oversized");
                return Ok(json!({"result": "unverified", "httpStatus": 200,
                    "replyBytes": 0, "replyFormat": "oversized"}));
            }
            let format = reply_format(&response);
            // The server's POST acknowledgment is insufficient on its own. A
            // separate v2 current-session GET confirms an active session.
            let current_path = format!("{CURRENT_SESSION_PATH}?local_device_id={device_id}");
            let current = check(&session, &current_path, "current-after-start").await;
            let active = current["session"]["active"].as_bool() == Some(true);
            let state = if active { "active" } else { "server-acknowledged" };
            log::info!("SpotifyGoodJam: operation=start-session result={} status=200 bytes={} format={}",
                state, response.len(), format);
            Ok(json!({"result": state, "httpStatus": 200,
                "replyBytes": response.len(), "replyFormat": format, "current": current}))
        }
        Err(error) => {
            let state = if error.status.is_some() { "request-rejected" } else { "unverified" };
            log::warn!("SpotifyGoodJam: operation=start-session result={} status={:?}",
                state, error.status);
            Ok(json!({"result": state, "httpStatus": error.status,
                "replyBytes": 0, "replyFormat": "unknown"}))
        }
    }
}

/// Read the authoritative session immediately before leaving. A stale ID from
/// the Compose UI is never accepted, and the join/session token is not exposed.
/// The official hk9 leaveSession is POST v3/sessions/{sessionId}/leave with no body.
/// Host-leave effects on other members are not assumed or labeled as "end Jam".
pub(super) async fn leave_session() -> AppResult<Value> {
    let session = engine::session()?;
    let device_id = session.device_id();
    if !valid_device_id(device_id) {
        return Err(AppError::unavailable("Jam leave cannot use this device identifier"));
    }
    let current_path = format!("{CURRENT_SESSION_PATH}?local_device_id={device_id}");
    let current = match http::spc_get_plain(&session, &current_path, Some(http::JSON)).await {
        Ok(body) if body.len() <= MAX_REPLY_BYTES => body,
        Ok(_) => return Ok(json!({"result": "unverified"})),
        Err(error) => {
            let state = if error.status == Some(404) { "no-active-session" } else { "unverified" };
            return Ok(json!({"result": state, "httpStatus": error.status}));
        }
    };
    // Unlike summary output, this private ID is used only inside this RPC.
    let doc: Value = match serde_json::from_slice(&current) {
        Ok(doc) => doc,
        Err(_) => return Ok(json!({"result": "unverified"})),
    };
    if doc["active"].as_bool() != Some(true) {
        return Ok(json!({"result": "no-active-session"}));
    }
    let Some(id) = doc["session_id"].as_str().filter(|id| valid_device_id(id)) else {
        return Ok(json!({"result": "unverified"}));
    };
    let path = format!("{LEAVE_SESSION_PREFIX}{id}/leave");
    match http::spc_send_once(
        &session, ::http::Method::POST, &path, http::JSON, http::JSON, Vec::new()
    ).await {
        Ok(_) => {
            let next = check(&session, &current_path, "current-after-leave").await;
            let active = next["session"]["active"].as_bool() == Some(true);
            let state = if active { "still-active" } else if next["result"] == "not-found" ||
                next["session"]["active"].as_bool() == Some(false) { "left" }
                else { "server-acknowledged" };
            log::info!("SpotifyGoodJam: operation=leave-session result={}", state);
            Ok(json!({"result": state, "httpStatus": 200, "current": next}))
        }
        Err(error) => {
            let state = if error.status.is_some() { "request-rejected" } else { "unverified" };
            log::warn!("SpotifyGoodJam: operation=leave-session result={} status={:?}",
                state, error.status);
            Ok(json!({"result": state, "httpStatus": error.status}))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn peer_lookup_identifies_only_the_explicit_peer_with_a_valid_token() {
        let response = json!({"tokens":[
            {"username":"other", "token":"0123456789ABCDEFGHIJKL"},
            {"username":"the_peer", "token": {"token":"2y2NpAKIdfoyg8JN5ogvUT"}}
        ]});
        assert_eq!(peer_lookup_token(&response, "the_peer").as_deref(),
            Some("https://open.spotify.com/socialsession/2y2NpAKIdfoyg8JN5ogvUT"));
        assert!(peer_lookup_token(&response, "unrelated").is_none());
        assert!(peer_lookup_token(&json!({"tokens":[{"username":"the_peer","token":"bad"}]}),
            "the_peer").is_none());
        assert!(peer_lookup_token(&json!({"tokens":[]}), "the_peer").is_none());
        assert!(!valid_peer_username("foo/../bar"));
    }


    #[test]
    fn ask_to_join_only_accepts_canonical_session_tokens_and_uuid_ids() {
        assert_eq!(parse_canonical_join_token(
            "https://open.spotify.com/socialsession/2y2NpAKIdfoyg8JN5ogvUT"),
            Some("2y2NpAKIdfoyg8JN5ogvUT"));
        assert!(parse_canonical_join_token("https://spotify.link/abcdefghi").is_none());
        assert!(parse_canonical_join_token(
            "https://open.spotify.com/socialsession/../../bad").is_none());
        assert!(valid_join_attempt_id("3cedc971-d63b-4b08-89cd-d5535da9a507"));
        assert!(!valid_join_attempt_id("../../../etc/passwd"));
    }

    #[test]
    fn session_state_is_verified_but_never_leaks_identifiers_or_member_details() {
        let bytes = br#"{"active":true,"session_id":"abc123","session_owner_id":"owner-secret","session_members":[{"user_id":"alice"},{"join_token":"private"}],"is_session_owner":true,"is_controlling":true,"is_listening":false,"is_paused":true,"queue_only_mode":false,"maxMemberCount":32,"link_token":{"token":"SECRET"}}"#;
        let summary = session_summary(bytes).unwrap();
        assert_eq!(summary["active"], true);
        assert_eq!(summary["isSessionOwner"], true);
        assert_eq!(summary["memberCount"], 2);
        assert_eq!(summary["queueOnlyMode"], false);
        assert_eq!(summary["maxMemberCount"], 32);
        let serialized = summary.to_string();
        for secret in ["abc123", "owner-secret", "alice", "private", "SECRET"] {
            assert!(!serialized.contains(secret));
        }
    }

    #[test]
    fn inactive_or_unidentified_replies_are_not_verified_as_active() {
        assert!(session_summary(br#"{"active":true}"#).is_none());
        assert!(session_summary(br#"{"session_id":"../bad","active":true}"#).is_none());
        assert!(session_summary(br#"{"session_id":"ok"}"#).is_none());
        assert_eq!(session_summary(br#"{"session_id":"ok","active":false}"#).unwrap()["active"], false);
    }

    #[test]
    fn creation_request_matches_official_required_fields_only() {
        let body = start_payload("local_123").unwrap();
        let value: Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(value, json!({"activate": true, "local_device_id": "local_123"}));
        assert!(start_payload("a/../b").is_err());
        assert!(start_payload("a?token=x").is_err());
    }

    #[test]
    fn guest_permissions_path_matches_official_hk9_string_enum() {
        assert_eq!(queue_only_mode_path(true),
            "/social-connect/v2/sessions/current/queue_only_mode/enabled");
        assert_eq!(queue_only_mode_path(false),
            "/social-connect/v2/sessions/current/queue_only_mode/disabled");
        assert!(!queue_only_mode_path(true).ends_with("/true"));
        assert!(!queue_only_mode_path(false).ends_with("/false"));
    }

    #[test]
    fn invitation_url_requires_current_valid_session_token() {
        let source = json!({"active":true,"join_session_uri":"spotify:socialsession:0123456789ABCDEFGHIJKL"});
        assert_eq!(invitation_url(&source).as_deref(),
            Some("https://open.spotify.com/socialsession/0123456789ABCDEFGHIJKL"));
        assert!(invitation_url(&json!({"active":false,"join_session_uri":"spotify:socialsession:0123456789ABCDEFGHIJKL"})).is_none());
        assert!(invitation_url(&json!({"active":true,"join_session_uri":"spotify:socialsession:../../bad"})).is_none());
        assert!(invitation_url(&json!({"active":true,"join_session_uri":"spotify:socialsession:0123456789ABCDEFGHIJKL","user_capabilities":{"invite":false}})).is_none());
    }

    #[test]
    fn device_identifiers_cannot_modify_the_request_path() {
        assert!(valid_device_id("cd8debdcaa46dc8d1c0d7847c27cfaab5afc07a9"));
        for id in ["", "/", "../a", "a?x=1", "with space", "hi\nthere"] {
            assert!(!valid_device_id(id));
        }
    }

    #[test]
    fn status_shape_never_exposes_identifiers_or_free_form_values() {
        let bytes = br#"{"deviceId":"private-device","joinToken":"secret","session":{"sessionId":"private-session","isHost":true,"displayName":"private-name"},"active":false,"error":"private-error"}"#;
        let (fields, flags) = status_shape(bytes);
        assert!(fields.contains(&"session.isHost".to_owned()));
        assert!(!fields.iter().any(|f| f.to_ascii_lowercase().contains("token") ||
            f.to_ascii_lowercase().contains("id") || f.to_ascii_lowercase().contains("name")));
        assert_eq!(flags.get("active"), Some(&false));
        assert_eq!(flags.get("session.isHost"), Some(&true));
        assert!(!format!("{fields:?}{flags:?}").contains("private"));
    }

    #[test]
    fn status_shape_is_bounded_and_ignores_arbitrary_boolean_fields() {
        let body = format!("{{{}}}", (0..80).map(|i| format!("\"field{i}\":true"))
            .collect::<Vec<_>>().join(","));
        let (fields, flags) = status_shape(body.as_bytes());
        assert!(fields.len() <= 24);
        assert!(flags.is_empty());
        assert_eq!(status_shape(b"not JSON").0.len(), 0);
    }

    #[test]
    fn an_opaque_response_is_not_mistaken_for_an_active_jam() {
        assert_eq!(reply_format(b""), "empty");
        assert_eq!(reply_format(b"{}"), "json");
        assert_eq!(reply_format(b"\x0a\x04test"), "other");
    }

    #[test]
    fn jam_invitation_short_link_requires_spotify_supplied_code() {
        let good = json!({"short_link": "https://spotify.link/4yscAxDmt7b"});
        assert_eq!(invitation_short_url(&good), Some("https://spotify.link/4yscAxDmt7b"));
        assert_eq!(invitation_short_url(&json!({"short_link": "https://spotify.link/../x"})), None);
        assert_eq!(invitation_short_url(&json!({"short_link": "https://evil.example/abcde12345"})), None);
        assert_eq!(invitation_short_url(&json!({"join_session_url": "hm://social-connect/v2/sessions/join/example"})), None);
        assert_eq!(invitation_short_url(&json!({"join_session_short_url": "https://spotify.link/4yscAxDmt7b"})),
            Some("https://spotify.link/4yscAxDmt7b"));
    }

}
