//! User-to-user Followers and Following. Never mix artists into people counts.
//! Read endpoint is the same user-profile-view service as catalog.user.

use super::http::{self, JSON};
use super::user::image_url;
use super::util::encode_component;
use crate::engine;
use crate::error::{AppError, AppResult};
use crate::rpc::{parse_args, to_value};
use bytes::Bytes;
use ::http::header::{HeaderName, HeaderValue, ACCEPT, AUTHORIZATION, CONTENT_TYPE};
use ::http::{Method, Request};
use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ListArgs {
    kind: String,
    username: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct FollowArgs {
    username: String,
    following: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Person {
    username: String,
    display_name: String,
    image_url: Option<String>,
    followers_count: Option<u32>,
    is_following: bool,
}

#[derive(Serialize)]
struct People {
    items: Vec<Person>,
}

fn parse_people(v: &Value, kind: &str) -> AppResult<People> {
    let rows = v.get("profiles").and_then(Value::as_array)
        .ok_or_else(|| AppError::unavailable("Spotify returned no social profiles array"))?;
    let people = rows.iter().filter_map(|p| {
        let username = p.get("uri").and_then(Value::as_str)
            .and_then(|v| v.strip_prefix("spotify:user:"))?.to_string();
        if username.is_empty() { return None; }
        let display_name = p.get("name").or_else(|| p.get("display_name"))
            .and_then(Value::as_str).filter(|v| !v.is_empty()).unwrap_or(&username).to_string();
        let image_url = p.get("image_url").or_else(|| p.get("imageUrl"))
            .and_then(Value::as_str).and_then(image_url);
        let followers_count = p.get("followers_count").or_else(|| p.get("followersCount"))
            .and_then(Value::as_u64).and_then(|n| u32::try_from(n).ok());
        let is_following = if kind == "following" { true } else {
            p.get("is_following").or_else(|| p.get("isFollowing"))
                .and_then(Value::as_bool).unwrap_or(false)
        };
        Some(Person { username, display_name, image_url, followers_count, is_following })
    }).collect();
    Ok(People { items: people })
}

pub(super) async fn list(args: Value) -> AppResult<Value> {
    let a: ListArgs = parse_args(args)?;
    if a.kind != "followers" && a.kind != "following" {
        return Err(AppError::invalid("kind must be followers or following"));
    }
    let session = engine::session()?;
    let username = a.username.filter(|v| !v.is_empty())
        .unwrap_or_else(|| engine::username().unwrap_or_else(|| session.username()));
    if username.is_empty() { return Err(AppError::invalid("missing username")); }
    let endpoint = format!("/user-profile-view/v3/profile/{}/{}?market=from_token", encode_component(&username), a.kind);
    let body = http::spc_get(&session, &endpoint, Some(JSON)).await?;
    let json = http::json(&body)?;
    to_value(&parse_people(&json, &a.kind)?)
}

/// Follow/unfollow user through Spotify's 2026 library API. Only confirm changes on HTTP success.
/// OAuth permission and account-specific server support are required; errors propagate to UI.
pub(super) async fn follow(args: Value) -> AppResult<Value> {
    let a: FollowArgs = parse_args(args)?;
    let username = a.username.strip_prefix("spotify:user:").unwrap_or(&a.username);
    if username.is_empty() || username.contains(':') || username.contains('/') {
        return Err(AppError::invalid("invalid user ID"));
    }
    let session = engine::session()?;
    let token = match engine::oauth_token() {
        Some((token, _)) => token,
        None => http::timed(session.login5().auth_token()).await?.access_token,
    };
    let uri = format!("spotify:user:{username}");
    let target = format!("https://api.spotify.com/v1/me/library?uris={}", encode_component(&uri));
    let method = if a.following { Method::PUT } else { Method::DELETE };
    let req = Request::builder().method(method).uri(target)
        .header(AUTHORIZATION, format!("Bearer {token}"))
        .header(ACCEPT, JSON)
        .header(CONTENT_TYPE, JSON)
        .body(Bytes::new()).map_err(|e| AppError::invalid(format!("invalid follow request: {e}")))?;
    http::send(Some(&session), req, http::TIMEOUT).await?;
    to_value(&serde_json::json!({ "success": true }))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn parses_followers_and_following() {
        let v = serde_json::json!({"profiles": [
            {"uri":"spotify:user:alice", "name":"Alice", "image_url":"spotify:image:0123abcd", "followers_count":21, "is_following":false},
            {"uri":"spotify:artist:bad", "name":"No"}
        ]});
        let followers = parse_people(&v, "followers").unwrap();
        assert_eq!(followers.items.len(), 1);
        assert_eq!(followers.items[0].username, "alice");
        assert!(!followers.items[0].is_following);
        assert!(parse_people(&v, "following").unwrap().items[0].is_following);
    }
}


/// Spotify Android ncb0 Retrofit interface, listening-activity/v1/audience (POST protobuf).
/// AudienceResponse field 1 = repeated AudienceUser; AudienceUser fields: 1 username,
/// 2 restricted, 3 source, 4 hidden. No client-side account privacy approximation.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AudienceMember { username: String, restricted: bool, hidden: bool }

fn read_varint(buf: &[u8], at: &mut usize) -> AppResult<u64> {
    let mut v = 0u64;
    for shift in (0..=63).step_by(7) {
        let b = *buf.get(*at).ok_or_else(|| AppError::unavailable("Truncated audience protobuf"))?;
        *at += 1;
        v |= ((b & 0x7f) as u64) << shift;
        if b < 128 { return Ok(v); }
    }
    Err(AppError::unavailable("Malformed audience protobuf varint"))
}

fn proto_fields<'a>(buf: &'a [u8]) -> AppResult<Vec<(u32, u64, &'a [u8])>> {
    let mut pos = 0;
    let mut out = Vec::new();
    while pos < buf.len() {
        let key = read_varint(buf, &mut pos)?;
        let field = (key >> 3) as u32;
        if field == 0 { return Err(AppError::unavailable("Malformed protobuf field")); }
        let wire = key & 7;
        let mut number = 0;
        let data = match wire {
            0 => { number = read_varint(buf, &mut pos)?; &buf[pos..pos] }
            1 => { let end = pos.checked_add(8).filter(|n| *n <= buf.len())
                .ok_or_else(|| AppError::unavailable("Truncated protobuf 64-bit field"))?;
                let slice = &buf[pos..end]; pos = end; slice }
            2 => { let len = usize::try_from(read_varint(buf, &mut pos)?)
                .map_err(|_| AppError::unavailable("Invalid protobuf length"))?;
                let end = pos.checked_add(len).filter(|n| *n <= buf.len())
                    .ok_or_else(|| AppError::unavailable("Truncated protobuf data"))?;
                let slice = &buf[pos..end]; pos = end; slice }
            5 => { let end = pos.checked_add(4).filter(|n| *n <= buf.len())
                .ok_or_else(|| AppError::unavailable("Truncated protobuf 32-bit field"))?;
                let slice = &buf[pos..end]; pos = end; slice }
            _ => return Err(AppError::unavailable("Unsupported audience protobuf wire type")),
        };
        out.push((field, number, data));
    }
    Ok(out)
}

// The official Android service is created with RetrofitMaker.createWebgateService,
// not with a rotating spclient access point (the latter returns HTTP 503).
// Use the existing Spotify session credentials and preserve the protobuf wire format.
async fn audience_webgate_request(method: Method, path: &str, body: Vec<u8>) -> AppResult<Bytes> {
    let session = engine::session()?;
    let token = session.login5().auth_token().await?;
    let request_url = format!("https://spclient.wg.spotify.com{path}");
    let mut request = Request::builder()
        .method(method)
        .uri(request_url)
        .header(CONTENT_TYPE, "application/x-protobuf")
        .header(ACCEPT, "application/protobuf")
        .header("Content-Encoding", "identity")
        .header(AUTHORIZATION, format!("{} {}", token.token_type, token.access_token))
        .body(Bytes::from(body))
        .map_err(|e| AppError::unavailable(format!("Invalid audience request: {e}")))?;
    if let Ok(client_token) = session.spclient().client_token().await {
        if let Ok(value) = HeaderValue::from_str(&client_token) {
            request.headers_mut().insert(HeaderName::from_static("client-token"), value);
        }
    }
    Ok(http::send(Some(&session), request, http::TIMEOUT).await?)
}

/// Session's account-backed ProductState attribute. This is read-only:
/// setting it locally would not persist to Spotify's ProductState.PutValues.
pub(super) async fn listening_global(_args: Value) -> AppResult<Value> {
    let session = engine::session()?;
    let enabled = match session.get_user_attribute("publish-activity") {
        Some(value) if value == "1" => Some(true),
        Some(value) if value == "0" => Some(false),
        _ => None,
    };
    to_value(&serde_json::json!({ "enabled": enabled }))
}

/// Writes Spotify's global "all followers" listening-activity flag (`publish-activity`). This is the
/// call the desktop client makes: PUT /clientsettings/api/v1/ with a ClientSetting protobuf
/// (field 1 = key string, field 2 = { field 4 = value string }). Returns `{ enabled }` with the
/// value now stored. Does not touch the individual listening-activity audience.
pub(super) async fn set_listening_global(args: Value) -> AppResult<Value> {
    #[derive(serde::Deserialize)]
    struct Args { enabled: bool }
    let enabled: bool = serde_json::from_value::<Args>(args)
        .map_err(|e| AppError::invalid(format!("set_listening_global: {e}")))?
        .enabled;
    let value = if enabled { "1" } else { "0" };
    audience_webgate_request(Method::PUT, "/clientsettings/api/v1/", client_setting("publish-activity", value)).await?;
    // Keep the cached session attribute in step so an immediate listening_global read agrees.
    session_set_attribute("publish-activity", value);
    to_value(&serde_json::json!({ "enabled": enabled }))
}

/// A ClientSetting protobuf for the clientsettings service: field 1 is the key, field 2 is a
/// message whose field 4 is the value (`publish-activity` -> "0"/"1").
fn client_setting(key: &str, value: &str) -> Vec<u8> {
    let mut inner = Vec::new();
    proto_len_delimited(&mut inner, 4, value.as_bytes());
    let mut out = Vec::new();
    proto_len_delimited(&mut out, 1, key.as_bytes());
    proto_len_delimited(&mut out, 2, &inner);
    out
}

/// Appends a length-delimited protobuf field (wire type 2).
fn proto_len_delimited(out: &mut Vec<u8>, field: u64, payload: &[u8]) {
    append_varint(out, (field << 3) | 2);
    append_varint(out, payload.len() as u64);
    out.extend_from_slice(payload);
}

/// Updates the session's cached product-state attribute so a later read reflects the write at once.
fn session_set_attribute(key: &str, value: &str) {
    if let Ok(session) = engine::session() {
        session.set_user_attribute(key, value);
    }
}

// Read-only Mercury routing probe: no write and no logging of payload bytes.
async fn probe_product_state_mercury() {
    let uri = "sp://esperanto/spotify.product_state.esperanto.proto.ProductState/SubValues";
    let session = match engine::session() {
        Ok(s) => s,
        Err(e) => { log::warn!("[productstate-probe] no session: {e}"); return; }
    };
    // SubValues is a streaming RPC in the Android client. Probe Mercury SUB,
    // not GET, and do not send any setting changes or log account values.
    match tokio::time::timeout(
        std::time::Duration::from_secs(5),
        session.mercury().subscribe(uri),
    ).await {
        Ok(Ok(mut updates)) => {
            log::info!("[productstate-probe] SUB accepted (subscription created)");
            match tokio::time::timeout(
                std::time::Duration::from_secs(2), updates.recv(),
            ).await {
                Ok(Some(response)) => {
                    let size: usize = response.payload.iter().map(|p| p.len()).sum();
                    log::info!("[productstate-probe] SUB update status={} parts={} bytes={}",
                        response.status_code, response.payload.len(), size);
                }
                Ok(None) => log::info!("[productstate-probe] SUB stream closed"),
                Err(_) => log::info!("[productstate-probe] SUB accepted; no update within 2s"),
            }
        }
        Ok(Err(e)) => log::warn!("[productstate-probe] SUB request error: {e}"),
        Err(_) => log::warn!("[productstate-probe] SUB timed out after 5 seconds"),
    }
}

pub(super) async fn listening_audience(_args: Value) -> AppResult<Value> {
    //tokio::spawn(probe_product_state_mercury());
    // Read-only diagnostic; never infer global setting from individual selections.
    let session = engine::session()?;
    match session.get_user_attribute("publish-activity") {
        Some(value) if value == "1" || value == "0" =>
            log::info!("[listening-global] publish-activity present; enabled={}", value == "1"),
        Some(_) => log::warn!("[listening-global] publish-activity has unexpected value"),
        None => log::info!("[listening-global] publish-activity absent from session attributes"),
    }
    //probe_profile_privacy().await;
    // Spotify calls AudienceRequest builder n(), setting field 1 (unused) to true.
    // The wire bytes are 08 01 rather than the empty default-instance message.
    let raw = audience_webgate_request(Method::POST, "/listening-activity/v1/audience", vec![0x08, 0x01]).await?;
    let mut users = Vec::new();
    for (field, _, data) in proto_fields(&raw)? {
        if field != 1 { continue; }
        let mut username = String::new();
        let mut restricted = false;
        let mut hidden = false;
        for (f, number, bytes) in proto_fields(data)? {
            match f {
                1 => username = String::from_utf8(bytes.to_vec())
                    .map_err(|_| AppError::unavailable("Invalid audience username"))?,
                2 => restricted = number != 0,
                4 => hidden = number != 0,
                _ => {}
            }
        }
        if !username.is_empty() {
            users.push(AudienceMember { username, restricted, hidden });
        }
    }
    to_value(&serde_json::json!({ "users": users }))
}

/// ProductState write probe. Encodes a PutValuesRequest with one pair {publish-activity: <current>}
/// — a no-op write — and tries each candidate Esperanto spclient path, logging the HTTP status.
/// Non-destructive: it rewrites the value the account already has, so nothing changes even on success.
/// A 200 identifies a path we can then use to actually flip the setting.
async fn probe_profile_privacy() {
    let session = match engine::session() { Ok(s) => s, Err(_) => return };
    let current = session.get_user_attribute("publish-activity").unwrap_or_else(|| "0".into());
    let body = encode_put_values("publish-activity", &current);
    log::info!("[ps-write] trying no-op PutValues publish-activity={current} body={}", hex(&body));
    let paths = [
        "/esperanto/spotify.product_state.esperanto.proto.ProductState/PutValues",
        "/connect-state/v1/product-state/PutValues",
        "/product-state/v1/PutValues",
    ];
    for path in paths {
        match audience_webgate_request(Method::POST, path, body.clone()).await {
            Ok(bytes) => { log::info!("[ps-write] OK {path} -> {} bytes: {}", bytes.len(), hex(&bytes)); return; }
            Err(e) => log::warn!("[ps-write] {path} failed: {e}"),
        }
    }
    log::warn!("[ps-write] no candidate path accepted PutValues");
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect::<String>()
}

/// A `PutValuesRequest { pairs: { key: value } }`: field 1 is a protobuf map, each entry a length-
/// delimited message of field 1 (key string) and field 2 (value string).
fn encode_put_values(key: &str, value: &str) -> Vec<u8> {
    let mut entry = Vec::new();
    entry.push(0x0a); append_varint(&mut entry, key.len() as u64); entry.extend_from_slice(key.as_bytes());
    entry.push(0x12); append_varint(&mut entry, value.len() as u64); entry.extend_from_slice(value.as_bytes());
    let mut out = Vec::new();
    out.push(0x0a); append_varint(&mut out, entry.len() as u64); out.extend_from_slice(&entry);
    out
}

#[derive(Deserialize)]
struct RestrictArgs { username: String, restrict: bool }

fn append_varint(out: &mut Vec<u8>, mut value: u64) {
    while value > 127 { out.push((value as u8 & 0x7f) | 0x80); value >>= 7; }
    out.push(value as u8);
}

pub(super) async fn restrict_audience(args: Value) -> AppResult<Value> {
    let a: RestrictArgs = parse_args(args)?;
    let username = a.username.strip_prefix("spotify:user:").unwrap_or(&a.username);
    if username.is_empty() || username.contains('/') || username.contains(':') || username.len() > 256 {
        return Err(AppError::invalid("Invalid audience username"));
    }
    // RestrictAudienceUserRequest field 1 = username (string), field 2 = restrict (bool).
    let mut body = vec![0x0a];
    append_varint(&mut body, username.len() as u64);
    body.extend_from_slice(username.as_bytes());
    body.extend_from_slice(&[0x10, if a.restrict { 1 } else { 0 }]);
    // Official Retrofit @pfo0 maps to HTTP PUT (ra50.java), not POST.
    audience_webgate_request(Method::PUT, "/listening-activity/v1/audience/restrict", body).await?;
    to_value(&serde_json::json!({ "success": true }))
}

#[cfg(test)]
mod audience_tests {
    use super::*;
    #[test]
    fn audience_fields_decode() {
        let user = [0x0a, 0x03, b'b', b'o', b'b', 0x10, 0x01, 0x20, 0x00];
        let f = proto_fields(&user).unwrap();
        assert_eq!(f[0].0, 1);
        assert_eq!(f[0].2, b"bob");
        assert_eq!(f[1].0, 2);
        assert_eq!(f[1].1, 1);
    }
}
