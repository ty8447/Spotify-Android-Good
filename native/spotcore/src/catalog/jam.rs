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
// The official hk9 Webgate client handles host-side approval separately
// from the requester's AskToJoin action. Acceptance may create a Jam for a
// listener who was not already hosting; the server owns that transition.
const PENDING_APPROVAL_ACCEPT_PATH: &str = "/social-connect/v2/sessions/approval_request/accept";
const PENDING_APPROVAL_REJECT_PATH: &str = "/social-connect/v2/sessions/approval_request/reject";
const OUTGOING_APPROVAL_CANCEL_PATH: &str = "/social-connect/v2/sessions/approval_request/cancel";


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
/// Join/session secrets are excluded. Public member identifiers, names and avatars
/// are exposed only to the signed-in Jam UI so hosts can manage their guests.
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
    let my_username = engine::username().unwrap_or_default();
    let owner = obj.get("session_owner_id").and_then(Value::as_str).unwrap_or("");
    let participants: Vec<Value> = obj.get("session_members")
        .and_then(Value::as_array).filter(|v| v.len() <= 100)
        .map(|rows| rows.iter().filter_map(|row| {
            let id = row.get("id")?.as_str()?;
            let username = row.get("username")?.as_str()?;
            if !valid_device_id(id) || !valid_peer_username(username) { return None; }
            let is_host = !owner.is_empty() && (id == owner || username == owner);
            let image_url = row.get("image_url").and_then(Value::as_str)
                .filter(|url| url.starts_with("https://") && url.len() < 2048);
            let display_name = row.get("display_name").and_then(Value::as_str)
                .filter(|name| name.len() < 200).unwrap_or(username);
            Some(json!({"id": id, "username": username, "displayName": display_name,
                "imageUrl": image_url, "isHost": is_host, "isSelf": username == my_username}))
        }).collect()).unwrap_or_default();
    Some(json!({
        "members": participants,
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

// Spotify official hk61 endpoint. Request: GetSessionRecsForUserRequest.user_id=1.
// Response: GetSessionRecsForUserResponse.tracks=1, Track.uri=1.
// This is a real Jam-aware social recommendation service, not a general search.
const GROUP_RECOMMENDATIONS_PATH: &str =
    "/social-recs/spotify.socialrecs.v1.SocialRecsService/GetSessionRecsForUser";

// Decode only the fields we need. No reflected protobuf schemas, no unchecked
// allocations, and never return account/session IDs from an API response.
fn jam_read_varint(data: &[u8], pos: &mut usize) -> Option<u64> {
    let mut value = 0u64;
    for shift in (0..70).step_by(7) {
        let b = *data.get(*pos)?;
        *pos += 1;
        if shift == 63 && b > 1 { return None; }
        value |= u64::from(b & 0x7f) << shift;
        if b & 0x80 == 0 { return Some(value); }
    }
    None
}
fn jam_pb_fields<'a>(data: &'a [u8]) -> Option<Vec<(u32, &'a [u8])>> {
    let mut pos = 0;
    let mut fields = Vec::new();
    while pos < data.len() {
        if fields.len() >= 512 { return None; }
        let key = jam_read_varint(data, &mut pos)?;
        let tag = u32::try_from(key >> 3).ok()?;
        if tag == 0 { return None; }
        let start = pos;
        match key & 7 {
            0 => { jam_read_varint(data, &mut pos)?; },
            1 => { pos = pos.checked_add(8)?; },
            2 => {
                let len = usize::try_from(jam_read_varint(data, &mut pos)?).ok()?;
                let end = pos.checked_add(len)?;
                let payload = data.get(pos..end)?;
                fields.push((tag, payload));
                pos = end;
                continue;
            },
            5 => { pos = pos.checked_add(4)?; },
            _ => return None,
        }
        data.get(start..pos)?;
    }
    Some(fields)
}

// Official socialrecs.v1.Track.attributedMembers=8 is repeated Member messages.
// Member fields: username=1, display_name=2, profile_image_url=3.
// Do not label every recommendation as belonging to the logged-in account:
// attribution comes from the service response, and may name multiple members.
fn social_member_field(raw: &[u8], max: usize) -> Option<String> {
    let text = std::str::from_utf8(raw).ok()?;
    if text.is_empty() || text.len() > max { return None; }
    Some(text.to_owned())
}

fn decode_social_member(raw: &[u8]) -> Option<Value> {
    let mut username = None;
    let mut display_name = None;
    let mut image_url = None;
    for (field, data) in jam_pb_fields(raw)? {
        match field {
            1 => username = social_member_field(data, 128),
            2 => display_name = social_member_field(data, 200),
            3 => {
                let url = social_member_field(data, 2048)?;
                if url.starts_with("https://") { image_url = Some(url); }
            }
            _ => {}
        }
    }
    let username = username.filter(|u| valid_peer_username(u))?;
    Some(json!({"username": username,
        "displayName": display_name.unwrap_or_default(), "profileImageUrl": image_url}))
}

fn decode_social_recommendations(data: &[u8]) -> Option<(Vec<String>, Vec<Value>)> {
    let mut uris = Vec::new();
    let mut attributions = Vec::new();
    for (tag, track) in jam_pb_fields(data)? {
        if tag != 1 { continue; }
        if uris.len() >= 60 { break; }
        let mut track_uri = None;
        let mut members = Vec::<Value>::new();
        for (field, raw) in jam_pb_fields(track)? {
            match field {
                1 => {
                    let uri = std::str::from_utf8(raw).ok()?;
                    if let Some(id) = uri.strip_prefix("spotify:track:") {
                        if id.len() == 22 && id.bytes().all(|b| b.is_ascii_alphanumeric()) {
                            track_uri = Some(uri.to_owned());
                        }
                    }
                }
                8 if members.len() < 8 => {
                    if let Some(member) = decode_social_member(raw) {
                        if !members.iter().any(|m| m.get("username") == member.get("username")) {
                            members.push(member);
                        }
                    }
                }
                _ => {}
            }
        }
        if let Some(uri) = track_uri {
            if !uris.iter().any(|u| u == &uri) {
                attributions.push(json!({"uri": uri, "members": members}));
                uris.push(uri);
            }
        }
    }
    Some((uris, attributions))
}

/// Read-only group recommendation request, contextualized by the authenticated
/// Spotify account and current social listening session on the service side.
pub(super) async fn group_recommendations() -> AppResult<Value> {
    let session = engine::session()?;
    let user_id = session.username();
    if !valid_peer_username(&user_id) || user_id.len() >= 128 {
        return Ok(json!({"result":"unavailable", "tracks":[]}));
    }
    let mut req = vec![0x0a, user_id.len() as u8];
    req.extend_from_slice(user_id.as_bytes());
    match http::spc_send_once(&session, ::http::Method::POST,
        GROUP_RECOMMENDATIONS_PATH, http::PROTOBUF, http::PROTOBUF, req).await {
        Ok(bytes) if bytes.len() <= MAX_REPLY_BYTES => {
            match decode_social_recommendations(&bytes) {
                Some((tracks, attributions)) => Ok(json!({"result": "ready",
                    "tracks": tracks, "attributions": attributions})),
                None => Ok(json!({"result":"unavailable", "tracks":[]})),
            }
        }
        Ok(_) => Ok(json!({"result":"unavailable", "tracks":[]})),
        Err(error) => {
            log::info!("SpotifyGoodJam: group-recommendations not available status={:?}", error.status);
            Ok(json!({"result":"unavailable", "tracks":[]}))
        }
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

// The official dws/fws serializer defines exactly three DiscoveredToken keys:
// username (string), token (nullable STRING), and discovery_method (string).
// A null token must NOT abort the scan: other entries for the same peer may
// still carry a valid token. Do not accept a token for another username.
/// Different discovery surfaces can have different join types. Only use an
/// explicitly peer-bound token, and carry the *returned* method to Ask to Join.
/// These values are verified as ug80 enum strings in the official APK.
/// The last two are legitimate social-radar/device discovery contexts absent
/// from the previous three-method request. They may expose a peer-bound token
/// even when the person is listening without proactively starting a Jam.
const PEER_DISCOVERY_METHODS: [&str; 5] = [
    "listening_activity", "social_radar_tap", "social_radar_invite",
    "nearby_wifi", "discovered_device",
];

fn allowed_peer_discovery_method(method: &str) -> bool {
    PEER_DISCOVERY_METHODS.contains(&method)
}

fn peer_lookup_verified_token(doc: &Value, username: &str) -> Option<(String, String)> {
    let tokens = doc.get("tokens")?.as_array()?;
    for item in tokens {
        if item.get("username").and_then(Value::as_str) != Some(username) { continue; }
        let Some(token) = item.get("token").and_then(Value::as_str) else { continue; };
        let Some(method) = item.get("discovery_method").and_then(Value::as_str)
            .filter(|m| allowed_peer_discovery_method(m)) else { continue; };
        let canonical = if token.len() == 22 && token.bytes().all(|b| b.is_ascii_alphanumeric()) {
            format!("https://open.spotify.com/socialsession/{token}")
        } else if let Some(raw) = token.strip_prefix("spotify:socialsession:") {
            format!("https://open.spotify.com/socialsession/{raw}")
        } else { token.to_owned() };
        if parse_canonical_join_token(&canonical).is_some() {
            return Some((canonical, method.to_owned()));
        }
    }
    None
}

// Unlike public socialsession links, the official DiscoveredToken.token is an
// opaque nullable STRING. It is forwarded *unchanged* to AskToJoinRequest.
// Never require it to be a 22-character link, or expose it to Kotlin/logcat.
fn usable_peer_join_token(value: &str) -> Option<&str> {
    let token = if value.starts_with("spotify:socialsession:") ||
        value.starts_with("https://open.spotify.com/socialsession/") {
        parse_canonical_join_token(value)?
    } else { value };
    if token.len() < 4 || token.len() > 1024 ||
       !token.bytes().all(|b| b.is_ascii_graphic()) ||
       token.starts_with("http://") || token.starts_with("https://") ||
       token.starts_with("spotify:") {
        None
    } else { Some(token) }
}

fn peer_lookup_raw_token(doc: &Value, username: &str) -> Option<(String, String)> {
    let rows = doc.get("tokens")?.as_array()?;
    rows.iter().find_map(|row| {
        if row.get("username").and_then(Value::as_str) != Some(username) { return None; }
        let method = row.get("discovery_method").and_then(Value::as_str)
            .filter(|value| allowed_peer_discovery_method(value))?;
        let token = usable_peer_join_token(row.get("token")?.as_str()?)?;
        Some((token.to_owned(), method.to_owned()))
    })
}

/// Explicit-button-only lookup. The official app calls lookupTokens with one
/// discovery method (ick1.v), then sends fp6(join_token,..) with the returned
/// raw string (k150). Do not force the token through a share-link codec.
async fn lookup_join_token_for_peer(
    session: &Session, username: &str, methods: &[&str],
) -> AppResult<Option<(String, String)>> {
    let requests: Vec<Value> = methods.iter().map(|method|
        json!({"username":username,"discovery_method":method})).collect();
    let body = serde_json::to_vec(&json!({"lookup_requests":requests}))
        .map_err(|_| AppError::unavailable("Could not prepare listener token lookup"))?;
    let bytes = http::spc_send_once(session, ::http::Method::POST,
        LOOKUP_PEER_TOKENS_PATH, http::JSON, http::JSON, body).await
        .map_err(|e| AppError::unavailable(format!(
            "Listener token lookup unavailable (HTTP {:?})",e.status)))?;
    if bytes.len() > MAX_REPLY_BYTES {
        return Err(AppError::unavailable("Listener token lookup reply exceeded limit"));
    }
    let doc: Value = serde_json::from_slice(&bytes)
        .map_err(|_| AppError::unavailable("Listener token lookup was not JSON"))?;
    Ok(peer_lookup_raw_token(&doc, username))
}

fn peer_lookup_token(doc: &Value, username: &str) -> Option<String> {
    peer_lookup_verified_token(doc, username).map(|(url, _)| url)
}

// An empty token list from our single-peer query means no discoverable token.
// A response mentioning *only other users* is ambiguous, not proof our peer
// ended their Jam. Prefer preserving the last known state on such responses.
fn peer_lookup_inactive(doc: &Value, username: &str) -> bool {
    let Some(tokens) = doc.get("tokens").and_then(Value::as_array) else {
        return false;
    };
    tokens.is_empty() || tokens.iter().any(|item|
        item.get("username").and_then(Value::as_str) == Some(username))
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
    // The official LookupTokensRequest accepts a list. One peer, five
    // official discovery contexts; avoid five sequential HTTP lookups per poll.
    let requests: Vec<Value> = PEER_DISCOVERY_METHODS.iter().map(|method|
        json!({"username": username, "discovery_method": method})).collect();
    let body = serde_json::to_vec(&json!({"lookup_requests":requests}))
        .map_err(|_| AppError::unavailable("Could not prepare Jam lookup"))?;
    match http::spc_send_once(&session, ::http::Method::POST,
            LOOKUP_PEER_TOKENS_PATH, http::JSON, http::JSON, body).await {
        Ok(bytes) if bytes.len() <= MAX_REPLY_BYTES => {
            let Ok(doc) = serde_json::from_slice::<Value>(&bytes) else {
                return Ok(json!({"result":"unverified"}));
            };
            if let Some((url, method)) = peer_lookup_verified_token(&doc, username) {
                log::info!("SpotifyGoodJam: operation=peer-lookup result=active method={method}");
                return Ok(json!({"result":"active","invitationUrl":url,
                    "discoveryMethod":method}));
            }
            if peer_lookup_inactive(&doc, username) {
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
    let join_type = args.get("discoveryMethod").and_then(Value::as_str)
        .filter(|m| allowed_peer_discovery_method(m)).unwrap_or("share-link");
    let session = engine::session()?;
    submit_ask_to_join(&session,token,join_type,on_this_phone,attempt_id).await
}

/// Ask a listener to host a Jam even if they haven't started one yet. The
/// token from /tokens/lookup is an opaque per-peer join capability; return
/// neither the token nor its format to the Android UI. One user press can
/// perform at most ONE approval_request/join POST, never an automatic retry.
pub(super) async fn ask_to_join_listener(args: Value) -> AppResult<Value> {
    let username = args.get("username").and_then(Value::as_str)
        .filter(|u| valid_peer_username(u))
        .ok_or_else(|| AppError::unavailable("A valid chat peer is required"))?;
    let on_this_phone = args.get("listenOnThisPhone").and_then(Value::as_bool)
        .ok_or_else(|| AppError::unavailable("A listening-device selection is required"))?;
    let attempt_id = args.get("joinAttemptId").and_then(Value::as_str)
        .filter(|v| valid_join_attempt_id(v))
        .ok_or_else(|| AppError::unavailable("A fresh UUID join attempt is required"))?;
    let session = engine::session()?;
    if username == session.username() { return Ok(json!({"result":"no-token"})); }
    // Prefer the exact listening-activity method used by the official app.
    // Try alternative verified methods only if that lookup returned no token.
    let discovered = match lookup_join_token_for_peer(
        &session,username,&["listening_activity"]).await {
        Ok(Some(value)) => Some(value),
        Ok(None) => match lookup_join_token_for_peer(&session,username,
            &["social_radar_tap","social_radar_invite","nearby_wifi","discovered_device"]).await {
                Ok(value) => value,
                Err(_) => return Ok(json!({"result":"lookup-unverified"})),
            },
        Err(_) => return Ok(json!({"result":"lookup-unverified"})),
    };
    let Some((token,method)) = discovered else {
        log::info!("SpotifyGoodJam: operation=ask-to-join-lookup result=no-token");
        return Ok(json!({"result":"no-token"}));
    };
    // Never log join capabilities or retry a potentially accepted write.
    submit_ask_to_join(&session,&token,&method,on_this_phone,attempt_id).await
}

async fn submit_ask_to_join(
    session: &Session,token: &str,join_type: &str,on_this_phone: bool,attempt_id: &str,
) -> AppResult<Value> {
    let body = serde_json::to_vec(&json!({
        "join_token": token,
        "join_type": join_type,
        "playback_control": if on_this_phone { "LISTEN_AND_CONTROL" } else { "CONTROL" },
        "join_attempt_id": attempt_id,
    })).map_err(|_| AppError::unavailable("Could not serialize join request"))?;
    match http::spc_send_once(session, ::http::Method::POST,
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

/// A JamStatus request queue is `request_queue.pending_requests` in the
/// official h780/e780/gnz0 schema. A request has username, playback_control,
/// optional request_type and playback_device_info. Expose no device tokens or
/// other raw values. When the field is absent, return unverified rather than
/// incorrectly announcing that the queue is empty.
fn decode_pending_join_requests(doc: &Value) -> Option<Vec<Value>> {
    let source = doc.get("request_queue")?.get("pending_requests")?.as_array()?;
    if source.len() > 100 { return None; }
    let mut out = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for item in source {
        let Some(username) = item.get("username").and_then(Value::as_str)
            .filter(|value| valid_peer_username(value)) else { continue; };
        if !seen.insert(username.to_owned()) { continue; }
        if out.len() >= 20 { break; }
        let control = item.get("playback_control").and_then(Value::as_str)
            .filter(|value| matches!(*value, "CONTROL" | "LISTEN_AND_CONTROL" | "control" | "listen_and_control"));
        let request_type = item.get("request_type").and_then(Value::as_str)
            .filter(|value| value.len() <= 64 && value.bytes().all(|b|
                b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-')));
        out.push(json!({"username": username, "playbackControl": control,
            "requestType": request_type}));
    }
    Some(out)
}

async fn device_pending_requests(session: &Session) -> AppResult<Option<Vec<Value>>> {
    let device_id = session.device_id();
    if !valid_device_id(device_id) {
        return Err(AppError::unavailable("Jam approval requires a valid device"));
    }
    let url = format!("{DEVICE_STATUS_PREFIX}{device_id}/jam_status");
    let body = match http::spc_get_plain(session, &url, Some(http::JSON)).await {
        Ok(data) if data.len() <= MAX_REPLY_BYTES => data,
        _ => return Ok(None),
    };
    Ok(serde_json::from_slice::<Value>(&body).ok()
        .and_then(|doc| decode_pending_join_requests(&doc)))
}

/// Foreground host inbox; no session creation, writes or assumptions. Both
/// listeners without Jams and current Jam hosts can have pending requests.
pub(super) async fn pending_join_requests() -> AppResult<Value> {
    let session = engine::session()?;
    match device_pending_requests(&session).await? {
        Some(requests) => Ok(json!({"result": "ready", "requests": requests})),
        None => Ok(json!({"result": "unavailable", "requests": []})),
    }
}

/// Each host action is a single user-confirmed POST. Require the same user to
/// appear in a fresh request-queue read to prevent acting on stale entries;
/// never automatically retry a write whose acknowledgement is ambiguous.
pub(super) async fn respond_to_join_request(args: Value) -> AppResult<Value> {
    let username = args.get("username").and_then(Value::as_str)
        .filter(|value| valid_peer_username(value))
        .ok_or_else(|| AppError::unavailable("A valid requester is required"))?;
    let accept = args.get("accept").and_then(Value::as_bool)
        .ok_or_else(|| AppError::unavailable("An explicit accept/reject choice is required"))?;
    let session = engine::session()?;
    let Some(pending) = device_pending_requests(&session).await? else {
        return Ok(json!({"result": "unverified"}));
    };
    if !pending.iter().any(|request| request["username"] == username) {
        return Ok(json!({"result": "no-longer-pending"}));
    }
    let body = if accept {
        json!({"username": username, "local_device_id": session.device_id()})
    } else {
        json!({"username": username})
    };
    let payload = serde_json::to_vec(&body)
        .map_err(|_| AppError::unavailable("Could not serialize Jam approval"))?;
    let path = if accept { PENDING_APPROVAL_ACCEPT_PATH } else { PENDING_APPROVAL_REJECT_PATH };
    let response = http::spc_send_once(&session, ::http::Method::POST,
        path, http::JSON, http::JSON, payload).await;
    match response {
        Ok(data) if data.len() <= MAX_REPLY_BYTES => {
            // Accept can create/activate a session. Verify with a fresh GET,
            // but never label the request 'active' based solely on HTTP 200.
            let current = if accept {
                let current_path = format!("{CURRENT_SESSION_PATH}?local_device_id={}", session.device_id());
                Some(check(&session, &current_path, "current-after-accept").await)
            } else { None };
            let result = if accept && current.as_ref().is_some_and(|c|
                    c["session"]["active"].as_bool() == Some(true)) {
                "active"
            } else if accept { "accepted-unverified-session" }
              else { "declined" };
            log::info!("SpotifyGoodJam: operation=host-approval result={result}");
            Ok(json!({"result": result, "current": current}))
        }
        Ok(_) => Ok(json!({"result": "unverified"})),
        Err(error) => {
            log::info!("SpotifyGoodJam: operation=host-approval result=failed status={:?}", error.status);
            Ok(json!({"result": if error.status.is_some() { "request-rejected" }
                else { "unverified" }, "httpStatus": error.status}))
        }
    }
}

/// Read the requester's own device status. Spotify exposes active_join_request
/// independently of the host's pending-approval queue. A missing or failed
/// response must not be reported as a rejection.
pub(super) async fn outgoing_request_status(args: Value) -> AppResult<Value> {
    let host = args.get("hostUsername").and_then(Value::as_str)
        .filter(|value| valid_peer_username(value))
        .ok_or_else(|| AppError::unavailable("A valid host username is required"))?;
    let session = engine::session()?;
    let id = session.device_id();
    if !valid_device_id(id) { return Ok(json!({"result":"unverified"})); }
    let url = format!("{DEVICE_STATUS_PREFIX}{id}/jam_status");
    let body = match http::spc_get_plain(&session, &url, Some(http::JSON)).await {
        Ok(data) if data.len() <= MAX_REPLY_BYTES => data,
        _ => return Ok(json!({"result":"unverified"})),
    };
    let Ok(doc) = serde_json::from_slice::<Value>(&body) else {
        return Ok(json!({"result":"unverified"}));
    };
    let active = doc.get("active_join_request");
    if let Some(request) = active.filter(|value| value.is_object()) {
        let who = request.get("username").and_then(Value::as_str);
        // The server sometimes omits the peer on the device-status read.
        // Don't falsely attribute another user's pending request to this host.
        return Ok(json!({"result": if who == Some(host) { "pending" } else { "unverified" }}));
    }
    // The field must explicitly exist (and be null) to confirm no pending ask.
    if active == Some(&Value::Null) {
        return Ok(json!({"result":"not-pending"}));
    }
    Ok(json!({"result":"unverified"}))
}

/// Cancel an outgoing Ask to Join. The serializer in the official APK is
/// CancelJoinRequest(username). Never retry a write on a timeout.
pub(super) async fn cancel_outgoing_request(args: Value) -> AppResult<Value> {
    let host = args.get("hostUsername").and_then(Value::as_str)
        .filter(|value| valid_peer_username(value))
        .ok_or_else(|| AppError::unavailable("A valid host username is required"))?;
    let session = engine::session()?;
    let body = serde_json::to_vec(&json!({"username":host}))
        .map_err(|_| AppError::unavailable("Could not serialize Jam cancellation"))?;
    match http::spc_send_once(&session, ::http::Method::POST,
        OUTGOING_APPROVAL_CANCEL_PATH, http::JSON, http::JSON, body).await {
        Ok(data) if data.len() <= MAX_REPLY_BYTES => Ok(json!({"result":"cancelled"})),
        Ok(_) => Ok(json!({"result":"unverified"})),
        Err(error) => Ok(json!({"result":if error.status.is_some() {"rejected"} else {"unverified"},
            "httpStatus":error.status})),
    }
}

/// A host may remove one explicitly selected guest. Refresh the authoritative
/// current session and compare both session and member IDs before sending one POST.
pub(super) async fn kick_session_member(args: Value) -> AppResult<Value> {
    let member_id = args.get("memberId").and_then(Value::as_str)
        .filter(|value| valid_device_id(value))
        .ok_or_else(|| AppError::unavailable("A valid Jam member ID is required"))?;
    let session = engine::session()?;
    let path = format!("{CURRENT_SESSION_PATH}?local_device_id={}", session.device_id());
    let body = match http::spc_get_plain(&session, &path, Some(http::JSON)).await {
        Ok(data) if data.len() <= MAX_REPLY_BYTES => data,
        _ => return Ok(json!({"result":"unverified"})),
    };
    let Ok(doc) = serde_json::from_slice::<Value>(&body) else {
        return Ok(json!({"result":"unverified"}));
    };
    let Some(summary) = session_summary(&body) else { return Ok(json!({"result":"unverified"})); };
    if summary["active"] != true || summary["isSessionOwner"] != true {
        return Ok(json!({"result":"not-host"}));
    }
    let Some(session_id) = doc.get("session_id").and_then(Value::as_str)
        .filter(|v| valid_device_id(v)) else { return Ok(json!({"result":"unverified"})); };
    let owner_id = doc.get("session_owner_id").and_then(Value::as_str).unwrap_or("");
    let username = session.username();
    let matching = doc.get("session_members").and_then(Value::as_array)
        .and_then(|members| members.iter().find(|m| m.get("id").and_then(Value::as_str) == Some(member_id)));
    let Some(member) = matching else { return Ok(json!({"result":"no-longer-present"})); };
    let guest_username = member.get("username").and_then(Value::as_str).unwrap_or("");
    if guest_username.is_empty() || guest_username == username.as_str() || owner_id.is_empty() ||
        owner_id == member_id || owner_id == guest_username {
        return Ok(json!({"result":"not-guest"}));
    }
    let kick_path = format!("/social-connect/v3/sessions/{session_id}/member/{member_id}/kick");
    match http::spc_send_once(&session, ::http::Method::POST,
        &kick_path, http::JSON, http::JSON, Vec::new()).await {
        Ok(data) if data.len() <= MAX_REPLY_BYTES => {
            let latest = check(&session, &path, "current-after-kick").await;
            Ok(json!({"result":"submitted", "current":latest}))
        }
        Ok(_) => Ok(json!({"result":"unverified"})),
        Err(error) => Ok(json!({"result":if error.status.is_some() {"rejected"} else {"unverified"},
            "httpStatus":error.status})),
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
    fn inbound_join_approval_reads_only_explicit_pending_request_members() {
        let doc = json!({"request_queue": {"pending_requests": [
            {"username":"friend", "playback_control":"LISTEN_AND_CONTROL", "request_type":"listening_activity"},
            {"username":"friend", "playback_control":"CONTROL"},
            {"username":"bad/name", "playback_control":"CONTROL"},
            {"username":"second", "playback_control":"unexpected", "playback_device_info":{"id":"private"}}
        ]}});
        let requests = decode_pending_join_requests(&doc).unwrap();
        assert_eq!(requests.len(), 2);
        assert_eq!(requests[0]["username"], "friend");
        assert_eq!(requests[0]["playbackControl"], "LISTEN_AND_CONTROL");
        assert!(requests[1]["playbackControl"].is_null());
        assert!(requests[1].get("playback_device_info").is_none());
        assert_eq!(decode_pending_join_requests(&json!({"request_queue": {"pending_requests":[]}})), Some(vec![]));
        assert!(decode_pending_join_requests(&json!({"session": {"active":false}})).is_none());
    }

    #[test]
    fn peer_lookup_identifies_only_the_explicit_peer_with_a_valid_token() {
        let response = json!({"tokens":[
            {"username":"other", "token":"0123456789ABCDEFGHIJKL", "discovery_method":"listening_activity"},
            {"username":"the_peer", "token":"2y2NpAKIdfoyg8JN5ogvUT", "discovery_method":"social_radar_tap"}
        ]});
        assert_eq!(peer_lookup_token(&response, "the_peer").as_deref(),
            Some("https://open.spotify.com/socialsession/2y2NpAKIdfoyg8JN5ogvUT"));
        assert!(peer_lookup_token(&response, "unrelated").is_none());
        assert!(peer_lookup_token(&json!({"tokens":[{"username":"the_peer","token":"bad","discovery_method":"listening_activity"}]}),
            "the_peer").is_none());
        assert!(peer_lookup_token(&json!({"tokens":[]}), "the_peer").is_none());
        assert!(!valid_peer_username("foo/../bar"));
        let selected = peer_lookup_verified_token(&response, "the_peer").unwrap();
        assert_eq!(selected.1, "social_radar_tap");
    }


    #[test]
    fn peer_lookup_skips_nullable_tokens_without_losing_a_later_valid_jam() {
        // Exact official dws/fws reply field names; token is nullable.
        let reply = json!({"tokens": [
            {"username":"friend", "token":null, "discovery_method":"nearby_wifi"},
            {"username":"stranger", "token":"0123456789ABCDEFGHIJKL", "discovery_method":"listening_activity"},
            {"username":"friend", "token":"2y2NpAKIdfoyg8JN5ogvUT", "discovery_method":"listening_activity"}
        ]});
        assert_eq!(peer_lookup_token(&reply, "friend").as_deref(),
            Some("https://open.spotify.com/socialsession/2y2NpAKIdfoyg8JN5ogvUT"));
        assert!(peer_lookup_token(&reply, "another").is_none());
        assert!(!peer_lookup_inactive(&reply, "another"));
    }

    #[test]
    fn peer_lookup_requires_verified_string_token_and_target_identity() {
        assert!(peer_lookup_inactive(&json!({"tokens":[]}), "friend"));
        assert!(peer_lookup_inactive(&json!({"tokens":[{
            "username":"friend", "token":null, "discovery_method":"listening_activity"
        }]}), "friend"));
        assert!(!peer_lookup_inactive(&json!({"tokens":[{
            "username":"other", "token":null, "discovery_method":"listening_activity"
        }]}), "friend"));
        for token in [json!("expired"), json!(123), json!({"token":"0123456789ABCDEFGHIJKL"})] {
            assert!(peer_lookup_token(&json!({"tokens":[{
                "username":"friend", "token":token, "discovery_method":"listening_activity"
            }]}), "friend").is_none());
        }
        assert!(peer_lookup_token(&json!({"tokens":[{
            "username":"friend", "token":"spotify:socialsession:0123456789ABCDEFGHIJKL",
            "discovery_method":"listening_activity"
        }]}), "friend").is_some());
    }

    #[test]
    fn social_recs_decodes_official_track_uri_fields_and_rejects_bad_wire() {
        let uri = b"spotify:track:0123456789ABCDEFGHIJKL";
        let mut track = vec![0x0a, uri.len() as u8];
        track.extend_from_slice(uri);
        let mut reply = vec![0x0a, track.len() as u8];
        reply.extend_from_slice(&track);
        let (tracks, attributions) = decode_social_recommendations(&reply).unwrap();
        assert_eq!(tracks, vec![String::from_utf8(uri.to_vec()).unwrap()]);
        assert_eq!(attributions[0]["members"], json!([]));
        assert_eq!(decode_social_recommendations(&[]), Some((vec![], vec![])));
        assert_eq!(decode_social_recommendations(&[0x0a, 0xff]), None);
    }

    #[test]
    fn social_recs_decodes_member_avatar_attribution_from_field_eight() {
        // Protobuf: Member fields 1=username, 2=displayName, 3=profileImageUrl.
        fn pb_str(tag: u8, text: &str) -> Vec<u8> {
            let mut out = vec![(tag << 3) | 2, text.len() as u8];
            out.extend_from_slice(text.as_bytes());
            out
        }
        let mut member = pb_str(1, "friend");
        member.extend(pb_str(2, "Friend Name"));
        member.extend(pb_str(3, "https://i.scdn.co/image/abc"));
        let mut track = pb_str(1, "spotify:track:0123456789ABCDEFGHIJKL");
        track.push(0x42); // Track.attributedMembers = field 8, wire type 2
        track.push(member.len() as u8);
        track.extend(member);
        let mut reply = vec![0x0a, track.len() as u8];
        reply.extend(track);
        let (uris, entries) = decode_social_recommendations(&reply).unwrap();
        assert_eq!(uris.len(), 1);
        assert_eq!(entries[0]["uri"], uris[0]);
        assert_eq!(entries[0]["members"][0]["username"], "friend");
        assert_eq!(entries[0]["members"][0]["displayName"], "Friend Name");
        assert_eq!(entries[0]["members"][0]["profileImageUrl"],
            "https://i.scdn.co/image/abc");
    }

    #[test]
    fn peer_lookup_uses_only_allowed_methods() {
        let reply = json!({"tokens": [
            {"username":"target", "token":"0123456789ABCDEFGHIJKL",
              "discovery_method":"unsupported"},
            {"username":"target", "token":"2y2NpAKIdfoyg8JN5ogvUT",
              "discovery_method":"nearby_wifi"}
        ]});
        let selected = peer_lookup_verified_token(&reply, "target").unwrap();
        assert_eq!(selected.1, "nearby_wifi");
        assert!(peer_lookup_verified_token(&reply, "somebody_else").is_none());
    }

    #[test]
    fn peer_lookup_supports_official_social_radar_invite_and_discovered_device() {
        for method in ["social_radar_invite", "discovered_device"] {
            let reply = json!({"tokens": [
                {"username": "friend", "token": "0123456789ABCDEFGHIJKL",
                 "discovery_method": method}
            ]});
            let (url, selected_method) = peer_lookup_verified_token(&reply, "friend").unwrap();
            assert_eq!(selected_method, method);
            assert_eq!(url, "https://open.spotify.com/socialsession/0123456789ABCDEFGHIJKL");
            assert!(peer_lookup_verified_token(&reply, "stranger").is_none());
        }
    }

    #[test]
    fn listening_activity_uses_opaque_peer_bound_token_not_public_share_url() {
        let doc = json!({"tokens":[
            {"username":"somebody_else", "token":"for-the-other-person-001", "discovery_method":"listening_activity"},
            {"username":"friend", "token":null, "discovery_method":"listening_activity"},
            {"username":"friend", "token":"listen-only:opaque/token+value=12345", "discovery_method":"listening_activity"}
        ]});
        let selected = peer_lookup_raw_token(&doc,"friend").unwrap();
        assert_eq!(selected.0,"listen-only:opaque/token+value=12345");
        assert_eq!(selected.1,"listening_activity");
        assert!(peer_lookup_raw_token(&doc,"unrelated").is_none());
        // Legacy share-link path must remain restricted to actual links.
        assert!(peer_lookup_verified_token(&doc,"friend").is_none());
        assert!(usable_peer_join_token("\nmalformed").is_none());
        assert!(usable_peer_join_token("https://evil.invalid/link").is_none());
        assert!(usable_peer_join_token("spotify:track:not-a-jam").is_none());
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
    fn valid_current_session_members_are_labeled_from_server_owner_id() {
        let data = br#"{"active":true,"session_id":"current42","session_owner_id":"host42","is_session_owner":true,"session_members":[{"id":"host42","username":"hostuser","display_name":"Host"},{"id":"guest42","username":"guestuser","display_name":"Guest","image_url":"https://example.com/avatar.jpg"},{"id":"../bad","username":"unsafe"}]}"#;
        let summary = session_summary(data).unwrap();
        assert_eq!(summary["members"].as_array().unwrap().len(), 2);
        assert_eq!(summary["members"][0]["isHost"], true);
        assert_eq!(summary["members"][1]["isHost"], false);
        assert_eq!(summary["members"][1]["username"], "guestuser");
        assert_eq!(summary["members"][1]["imageUrl"], "https://example.com/avatar.jpg");
        assert!(!summary.to_string().contains("../bad"));
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
