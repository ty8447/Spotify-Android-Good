//! Account-backed profile visibility controls observed in Spotify desktop protobuf traffic.
//! No captured credentials are stored here; requests use the active librespot login.
use super::http::{self, PROTOBUF};
use crate::engine;
use crate::error::{AppError, AppResult};
use crate::rpc::{parse_args, to_value};
use bytes::Bytes;
use ::http::header::{ACCEPT, AUTHORIZATION, CONTENT_TYPE, HeaderName, HeaderValue};
use ::http::{Method, Request};
use serde::Deserialize;
use serde_json::{json, Value};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SetArgs { key: String, enabled: bool }

fn vint(mut n: u64, dst: &mut Vec<u8>) {
    while n >= 0x80 { dst.push((n as u8) | 0x80); n >>= 7; }
    dst.push(n as u8);
}
fn var(field: u64, value: u64, dst: &mut Vec<u8>) { vint(field << 3, dst); vint(value, dst); }
fn bytes_field(field: u64, value: &[u8], dst: &mut Vec<u8>) {
    vint(field << 3 | 2, dst); vint(value.len() as u64, dst); dst.extend_from_slice(value);
}
fn read_var(src: &[u8], i: &mut usize) -> Option<u64> {
    let mut n = 0u64;
    for shift in (0..=63).step_by(7) {
        let b = *src.get(*i)?; *i += 1;
        n |= ((b & 0x7f) as u64) << shift;
        if b & 0x80 == 0 { return Some(n); }
    }
    None
}
fn read_visibility(src: &[u8]) -> AppResult<(bool, bool)> {
    let mut i = 0usize;
    let (mut follows, mut playlists, mut valid) = (false, false, false);
    while i < src.len() {
        let tag = read_var(src, &mut i).ok_or_else(|| AppError::unavailable("malformed privacy reply"))?;
        match (tag >> 3, tag & 7) {
            (3, 0) => valid = read_var(src, &mut i).ok_or_else(|| AppError::unavailable("malformed status"))? == 1,
            (4, 0) => follows = read_var(src, &mut i).ok_or_else(|| AppError::unavailable("malformed follows"))? != 0,
            (5, 0) => playlists = read_var(src, &mut i).ok_or_else(|| AppError::unavailable("malformed playlists"))? != 0,
            (_, 0) => { read_var(src, &mut i).ok_or_else(|| AppError::unavailable("malformed field"))?; },
            (_, 2) => {
                let n = read_var(src, &mut i).ok_or_else(|| AppError::unavailable("malformed length"))? as usize;
                i = i.checked_add(n).filter(|&end| end <= src.len()).ok_or_else(|| AppError::unavailable("truncated privacy reply"))?;
            },
            (_, 1) => i = i.checked_add(8).filter(|&end| end <= src.len()).ok_or_else(|| AppError::unavailable("truncated privacy field"))?,
            (_, 5) => i = i.checked_add(4).filter(|&end| end <= src.len()).ok_or_else(|| AppError::unavailable("truncated privacy field"))?,
            _ => return Err(AppError::unavailable("unsupported privacy response")),
        }
    }
    if !valid { return Err(AppError::unavailable("Spotify did not confirm privacy settings")); }
    Ok((follows, playlists))
}

async fn request(method: Method, url: &str, payload: Vec<u8>) -> AppResult<Bytes> {
    let session = engine::session()?;
    let token = http::timed(session.login5().auth_token()).await?;
    let client_token = session.spclient().client_token().await.ok();
    let mut req = Request::builder().method(method).uri(url)
        .header(CONTENT_TYPE, PROTOBUF)
        // Desktop sends Accept: application/json for profile-privacy writes.
        // Keep reads and clientsettings unchanged for this single-variable test.
        .header(ACCEPT, if url.ends_with("/profile-privacy/v2/store-settings") { "application/json" } else { PROTOBUF })
        .header(AUTHORIZATION, format!("{} {}", token.token_type, token.access_token))
        .body(Bytes::from(payload)).map_err(|e| AppError::invalid(format!("privacy request: {e}")))?;
    if let Some(v) = client_token.and_then(|s| HeaderValue::from_str(&s).ok()) {
        req.headers_mut().insert(HeaderName::from_static("client-token"), v);
    }
    // Diagnostic experiment: the official Desktop client sends these headers on
    // public-toplist writes. Scope them to this endpoint only; don't change the
    // two working profile-privacy requests. Keep credentials from our session.
    if req.method() == Method::PUT && req.uri().path() == "/clientsettings/api/v1/" {
        let origin = match req.uri().authority() {
            Some(host) => format!("https://{host}"),
            None => return Err(AppError::invalid("clientsettings request missing host")),
        };
        let origin = HeaderValue::from_str(&origin)
            .map_err(|e| AppError::invalid(format!("clientsettings origin: {e}")))?;
        let headers = req.headers_mut();
        headers.insert(HeaderName::from_static("origin"), origin);
        headers.insert(HeaderName::from_static("accept-language"), HeaderValue::from_static("en"));
        headers.insert(HeaderName::from_static("app-platform"), HeaderValue::from_static("Win32_x86_64"));
        headers.insert(HeaderName::from_static("spotify-app-version"), HeaderValue::from_static("130503058"));
        headers.insert(HeaderName::from_static("time-zone"), HeaderValue::from_static("America/New_York"));
        headers.insert(HeaderName::from_static("user-agent"), HeaderValue::from_static("Spotify/130503058 Win32_x86_64/Windows 10 (10.0.26100; x64)"));
        log::info!("[profile-privacy-artists] desktop-request-metadata-test enabled");
    }
    // Only log request metadata, never bearer/client token values or payload contents.
    let path = req.uri().path().to_owned();
    let has_client_token = req.headers().contains_key("client-token");
    log::info!("[profile-privacy-wire] method={} path={} client_token_present={} payload_bytes={}",
        req.method(), path, has_client_token, req.body().len());
    match http::send(Some(&session), req, http::TIMEOUT).await {
        Ok(reply) => {
            log::info!("[profile-privacy-wire] path={} HTTP helper accepted request response_bytes={}", path, reply.len());
            Ok(reply)
        },
        Err(e) => {
            log::warn!("[profile-privacy-wire] path={} HTTP error status={:?} error={}", path, e.status, e.error);
            Err(e.into())
        },
    }
}

async fn server_visibility(username: &str) -> AppResult<(bool, bool)> {
    let mut body = Vec::new(); bytes_field(1, username.as_bytes(), &mut body);
    let reply = request(Method::POST, "https://spclient.wg.spotify.com/profile-privacy/v2/read-settings", body).await?;
    read_visibility(&reply)
}

pub(super) async fn read() -> AppResult<Value> {
    let session = engine::session()?;
    let username = super::account(&session)?;
    let (followers, playlists) = server_visibility(&username).await?;
    // Product-state attributes are account-derived, but may not be refreshed immediately after PUT.
    let raw = session.get_user_attribute("public-toplist");
    // Spotify represents public-toplist as an integer bitmask; bit zero controls
    // whether recently played artists are visible. Other bits must be preserved.
    let read_artists = raw.as_deref().and_then(|v| v.trim().parse::<i32>().ok())
        .map(|value| value & 1 != 0);
    // clientsettings exposes no confirmed read-back; default to off so the toggle is
    // operable and the PUT can run. artistsStatus below still reports it is unconfirmed.
    let artists = Some(read_artists.unwrap_or(false));
    // Only report presence/format; never log tokens or user attributes wholesale.
    let artists_status = if read_artists.is_some() { "available" }
        else if raw.is_some() { "unrecognized_session_value" }
        else { "missing_from_session" };
    to_value(&json!({"followers": followers, "playlists": playlists,
        "artists": artists, "artistsStatus": artists_status}))
}

pub(super) async fn set(args: Value) -> AppResult<Value> {
    let a: SetArgs = parse_args(args)?;
    let session = engine::session()?;
    let username = super::account(&session)?;
    match a.key.as_str() {
        "followers" | "playlists" => {
            let field = if a.key == "followers" { 5 } else { 6 };
            let setting = if a.key == "followers" { "show_follows" } else { "show_published_playlists" };
            // SetProfilePrivacyRequest: field 3 is google.protobuf.FieldMask,
            // and show_follows/show_published_playlists are TOP-LEVEL fields 5/6.
            // Encoding the bool inside the mask produces HTTP 200 but no account change.
            let mut mask = Vec::new();
            bytes_field(1, setting.as_bytes(), &mut mask); // FieldMask.paths (field 1)
            let mut body = Vec::new();
            bytes_field(1, username.as_bytes(), &mut body);
            bytes_field(3, &mask, &mut body);
            var(field, u64::from(a.enabled), &mut body);
            // Do not log the full protobuf body: field 1 contains the account username.
            log::info!("[profile-privacy-write] key={} desired={} field={} payload_bytes={}",
                a.key, a.enabled, field, body.len());
            request(Method::POST, "https://spclient.wg.spotify.com/profile-privacy/v2/store-settings", body).await?;
            let (followers, playlists) = server_visibility(&username).await?;
            let confirmed = if a.key == "followers" { followers } else { playlists };
            log::info!("[profile-privacy-write] key={} desired={} read_back={} followers={} playlists={}",
                a.key, a.enabled, confirmed, followers, playlists);
            if confirmed != a.enabled { return Err(AppError::unavailable("Spotify did not save the visibility change")); }
            to_value(&json!({"enabled": confirmed, "verified": true}))
        },
        "artists" => {
            let mut val = Vec::new();
            bytes_field(4, if a.enabled { b"1" } else { b"0" }, &mut val);
            let mut body = Vec::new();
            bytes_field(1, b"public-toplist", &mut body);
            bytes_field(2, &val, &mut body);
            let base = session.spclient().base_url().await.map_err(AppError::from)?;
            let url = format!("{}/clientsettings/api/v1/", base.trim_end_matches('/'));
            request(Method::PUT, &url, body).await?;
            // Clientsettings returned HTTP success with no response body in the desktop capture.
            // We cannot yet read back a fresh value through an independently verified endpoint.
            to_value(&json!({"enabled": a.enabled, "verified": false}))
        },
        _ => Err(AppError::invalid("unknown profile visibility switch")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn protobuf_settings_match_capture() {
        assert_eq!(read_visibility(&[0x18,1,0x20,1,0x28,1]).unwrap(), (true,true));
        assert_eq!(read_visibility(&[0x18,1,0x28,1]).unwrap(), (false,true));
        assert_eq!(read_visibility(&[0x18,1,0x20,1]).unwrap(), (true,false));
        assert!(read_visibility(&[]).is_err());
    }
}
