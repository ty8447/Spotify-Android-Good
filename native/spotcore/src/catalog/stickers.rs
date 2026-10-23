//! Read-only Spotify stickers catalog, verified against the APK's
//! com.spotify.stickers.v1 GetSurfaceResponse/SearchSurfaceStickersResponse/GetStickerResponse.
//! Campfire mutations are handled by `messaging::send_rich_media`, not here.
use crate::engine;
use crate::error::{AppError, AppResult};
use serde::Serialize;
use serde_json::{json, Value};
use super::http;

const PROTOBUF: &str = "application/protobuf";
const MAX_RESPONSE: usize = 2 * 1024 * 1024;
const MAX_STICKERS: usize = 300;
// APK callsite p7q0 invokes o881.g(20, "MESSAGING_LYRICS", query, ...).
// LYRICS_STICKERS is the tab ID, not the sticker API surface name.
const DEFAULT_SURFACE: &str = "MESSAGING_LYRICS";
const WEBGATE_ROOT: &str = "https://spclient.wg.spotify.com";
// Official Spotify Android client id (from the APK). The lyric-stickers backend returns an
// intentional empty 200 to clients whose token carries a non-entitled client id, so sticker
// reads use a token minted under this id rather than the session's librespot client id.
const STICKER_CLIENT_ID: &str = "9a8d2f0ce77a4e248bb71fefcb557637";

/// An empty HTTP response is not proof of an empty sticker catalog. In
/// particular, a wrong Webgate route may legitimately return a blank 2xx body.
fn require_body(bytes: &[u8], operation: &str) -> AppResult<()> {
    if bytes.is_empty() {
        log::warn!("SpotifyGoodLyricStickers: Webgate {} returned zero bytes", operation);
        return Err(AppError::unavailable(
            "Spotify returned an empty sticker response. Lyric stickers are currently unavailable in this app."
        ));
    }
    Ok(())
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct LyricSticker {
    sticker_id: String,
    image_url: Option<String>,
    alt_text: String,
    lyrics_text: String,
    track_uri: String,
    track_preview_url: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct StickerPack {
    title: String,
    stickers: Vec<LyricSticker>,
}

fn invalid_wire() -> AppError { AppError::unavailable("Malformed Spotify sticker response") }

fn varint(buf: &[u8], pos: &mut usize) -> AppResult<u64> {
    let mut number = 0u64;
    for shift in (0..=63).step_by(7) {
        let byte = *buf.get(*pos).ok_or_else(invalid_wire)?;
        *pos += 1;
        if shift == 63 && byte > 1 { return Err(invalid_wire()); }
        number |= u64::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 { return Ok(number); }
    }
    Err(invalid_wire())
}

/// Only the wire types used by the catalog's protobuf messages are accepted.
fn fields<'a>(buf: &'a [u8], mut visit: impl FnMut(u32, u8, &'a [u8]) -> AppResult<()>) -> AppResult<()> {
    let mut pos = 0;
    while pos < buf.len() {
        let key = varint(buf, &mut pos)?;
        let field = u32::try_from(key >> 3).map_err(|_| invalid_wire())?;
        let wire = (key & 7) as u8;
        if field == 0 { return Err(invalid_wire()); }
        let (start, end) = match wire {
            0 => { let start = pos; varint(buf, &mut pos)?; (start, pos) }
            1 | 5 => {
                let start = pos;
                pos = pos.checked_add(if wire == 1 { 8 } else { 4 }).ok_or_else(invalid_wire)?;
                (start, pos)
            }
            2 => {
                let len = usize::try_from(varint(buf, &mut pos)?).map_err(|_| invalid_wire())?;
                let start = pos;
                pos = pos.checked_add(len).ok_or_else(invalid_wire)?;
                (start, pos)
            }
            _ => return Err(invalid_wire()),
        };
        let slice = buf.get(start..end).ok_or_else(invalid_wire)?;
        visit(field, wire, slice)?;
    }
    Ok(())
}

fn utf8(buf: &[u8], max: usize) -> AppResult<String> {
    if buf.len() > max { return Err(invalid_wire()); }
    Ok(std::str::from_utf8(buf).map_err(|_| invalid_wire())?.to_string())
}

fn image_url(image: &str) -> Option<String> {
    if image.starts_with("https://") && image.len() < 2048 { return Some(image.to_owned()); }
    let id = image.strip_prefix("spotify:image:")?;
    if id.len() == 40 && id.bytes().all(|b| b.is_ascii_hexdigit()) {
        Some(format!("https://i.scdn.co/image/{id}"))
    } else { None }
}

fn sticker(bytes: &[u8]) -> AppResult<Option<LyricSticker>> {
    let (mut id, mut uri, mut alt, mut lyrics, mut track, mut preview) =
        (String::new(), String::new(), String::new(), String::new(), String::new(), String::new());
    let mut is_lyrics = false;
    fields(bytes, |field, wire, data| {
        if wire != 2 { return Ok(()); }
        match field {
            1 => id = utf8(data, 256)?,
            2 => uri = utf8(data, 2048)?,
            3 => alt = utf8(data, 512)?,
            5 => {
                is_lyrics = true;
                fields(data, |nested, nested_wire, value| {
                    if nested_wire == 2 {
                        match nested {
                            1 => lyrics = utf8(value, 4096)?,
                            2 => track = utf8(value, 160)?,
                            3 => preview = utf8(value, 2048)?,
                            _ => {}
                        }
                    }
                    Ok(())
                })?;
            }
            _ => {}
        }
        Ok(())
    })?;
    // Sticker IDs are opaque protobuf strings, not guaranteed to be alphanumeric.
    // Campfire encodes this value as protobuf bytes, so ':' or '.' must not
    // cause a valid server-issued lyric sticker to be silently discarded.
    if !is_lyrics || !valid_sticker_id(&id) { return Ok(None); }
    Ok(Some(LyricSticker {
        sticker_id: id, image_url: image_url(&uri), alt_text: alt,
        lyrics_text: lyrics, track_uri: track,
        track_preview_url: preview.starts_with("https://").then_some(preview),
    }))
}

fn pack(bytes: &[u8]) -> AppResult<StickerPack> {
    let mut title = String::new();
    let mut stickers = Vec::new();
    fields(bytes, |field, wire, data| {
        if wire != 2 { return Ok(()); }
        match field {
            2 => title = utf8(data, 200)?,
            3 if stickers.len() < MAX_STICKERS => if let Some(item) = sticker(data)? { stickers.push(item); },
            _ => {}
        }
        Ok(())
    })?;
    Ok(StickerPack { title, stickers })
}

fn decode_packs(bytes: &[u8], search: bool) -> AppResult<Value> {
    if bytes.len() > MAX_RESPONSE { return Err(AppError::unavailable("Sticker catalog response too large")); }
    let mut packs = Vec::new();
    let mut token = String::new();
    let mut total = 0;
    fields(bytes, |field, wire, data| {
        if wire != 2 { return Ok(()); }
        match field {
            1 if total < MAX_STICKERS => {
                let mut p = pack(data)?;
                if !p.stickers.is_empty() {
                    let remaining = MAX_STICKERS - total;
                    p.stickers.truncate(remaining);
                    total += p.stickers.len();
                    packs.push(p);
                }
            }
            2 if search => token = utf8(data, 2048)?,
            _ => {}
        }
        Ok(())
    })?;
    Ok(json!({"packs": packs, "nextPageToken": token}))
}

/// A provider sticker ID is opaque, but must be safe to use as one encoded
/// HTTP path segment. Do not interpret provider IDs as arbitrary URLs.
fn valid_sticker_id(value: &str) -> bool {
    !value.is_empty() && value.len() <= 256 && value != "." && value != ".." &&
        !value.chars().any(|c| c.is_control() || c.is_whitespace() || c == '/' || c == '\\')
}

fn segment(value: &str) -> AppResult<&str> {
    if value.is_empty() || value.len() > 100 || !value.bytes().all(|b|
        b.is_ascii_alphanumeric() || b == b'_' || b == b'-') {
        return Err(AppError::unavailable("Invalid sticker catalog identifier"));
    }
    Ok(value)
}

fn percent_encode(value: &str) -> String {
    let mut out = String::new();
    for byte in value.bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.' | b'~') {
            out.push(byte as char);
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}

pub(super) async fn surface(args: Value) -> AppResult<Value> {
    let surface = segment(args.get("surface").and_then(Value::as_str).unwrap_or(DEFAULT_SURFACE))?;
    let session = engine::session()?;
    let path = format!("/stickers/v1/surfaces/{surface}");
    let bytes = webgate_get_stickers(&session, &path).await.map_err(|e| {
        AppError::unavailable(format!("Lyric sticker catalog unavailable: {}", e.status
            .map(|s| format!("HTTP {s}")).unwrap_or_else(|| "network or authorization failure".into())))
    })?;
    require_body(&bytes, "surface")?;
    let result = decode_packs(&bytes, false)?;
    log::info!("SpotifyGoodLyricStickers: transport=webgate surface={} bytes={} packs={}", surface,
        bytes.len(), result["packs"].as_array().map_or(0, Vec::len));
    Ok(result)
}

pub(super) async fn search(args: Value) -> AppResult<Value> {
    let surface = segment(args.get("surface").and_then(Value::as_str).unwrap_or(DEFAULT_SURFACE))?;
    let query = args.get("query").and_then(Value::as_str).unwrap_or("").trim();
    if query.is_empty() || query.len() > 120 || query.chars().any(char::is_control) {
        return Err(AppError::unavailable("Enter a search term up to 120 bytes"));
    }
    let page_size = args.get("pageSize").and_then(Value::as_u64).unwrap_or(20).clamp(1, 40);
    let token = args.get("pageToken").and_then(Value::as_str).unwrap_or("");
    if token.len() > 2048 || token.chars().any(char::is_control) {
        return Err(AppError::unavailable("Invalid sticker pagination token"));
    }
    let session = engine::session()?;
    let path = format!("/stickers/v1/surfaces/{surface}/stickers:search?query={}&page_size={page_size}&page_token={}",
        percent_encode(query), percent_encode(token));
    let bytes = webgate_get_stickers(&session, &path).await.map_err(|e| {
        AppError::unavailable(format!("Lyric sticker search unavailable: {}", e.status
            .map(|s| format!("HTTP {s}")).unwrap_or_else(|| "network or authorization failure".into())))
    })?;
    require_body(&bytes, "search")?;
    let result = decode_packs(&bytes, true)?;
    log::info!("SpotifyGoodLyricStickers: transport=webgate search bytes={} packs={}", bytes.len(),
        result["packs"].as_array().map_or(0, Vec::len));
    Ok(result)
}

pub(super) async fn details(args: Value) -> AppResult<Value> {
    let id = args.get("stickerId").and_then(Value::as_str).unwrap_or("");
    if !valid_sticker_id(id) {
        return Err(AppError::unavailable("Invalid sticker identifier"));
    }
    let session = engine::session()?;
    // Even if a sticker ID contains ':' or '.', do not use it as URL syntax.
    let path = format!("/stickers/v1/stickers/{}", percent_encode(id));
    let bytes = webgate_get_stickers(&session, &path).await?;
    require_body(&bytes, "details")?;
    if bytes.len() > 128 * 1024 { return Err(AppError::unavailable("Sticker response too large")); }
    let mut result = None;
    fields(&bytes, |field, wire, data| {
        if field == 1 && wire == 2 { result = sticker(data)?; }
        Ok(())
    })?;
    serde_json::to_value(result).map_err(|_| invalid_wire())
}

/// Sticker reads use a token minted under the official Spotify Android client id. The lyric
/// stickers backend answers an intentional empty 200 to clients whose token carries a client id
/// not entitled to the feature, so we set that Bearer ourselves over the raw client.
async fn webgate_get_stickers(session: &librespot_core::Session, path: &str) -> Result<bytes::Bytes, http::HttpError> {
    use ::http::{Method, Request};
    use ::http::header::{ACCEPT, AUTHORIZATION, HeaderName, HeaderValue};
    use http_body_util::BodyExt;
    let token = crate::device_token::mint(
        crate::device_token::TokenSource::DeviceAuth,
        STICKER_CLIENT_ID.to_string(),
        session.device_id().to_string(),
    )
    .await
    .map_err(|error| http::HttpError { status: None, error })?;
    let mut req = Request::builder()
        .method(Method::GET)
        .uri(format!("{WEBGATE_ROOT}{path}"))
        .header(ACCEPT, PROTOBUF)
        .header(AUTHORIZATION, format!("Bearer {token}"))
        .body(bytes::Bytes::new())
        .map_err(|_| http::HttpError { status: None, error: AppError::unavailable("Invalid sticker Webgate request") })?;
    if let Ok(ct) = session.spclient().client_token().await {
        if let Ok(v) = HeaderValue::from_str(&ct) { req.headers_mut().insert(HeaderName::from_static("client-token"), v); }
    }
    let response = session.http_client().request(req).await.map_err(http::HttpError::from)?;
    let status = response.status().as_u16();
    let bytes = response.into_body().collect().await
        .map_err(|_| http::HttpError { status: Some(status), error: AppError::unavailable("Sticker Webgate response could not be read") })?
        .to_bytes();
    log::info!("SpotifyGoodLyricStickers: transport=webgate tokenSource=android-client status={} bytes={}", status, bytes.len());
    if !(200..300).contains(&status) {
        return Err(http::HttpError { status: Some(status), error: AppError::unavailable("Sticker Webgate HTTP request was not successful") });
    }
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn wire(field: u8, data: &[u8]) -> Vec<u8> {
        assert!(data.len() < 128);
        let mut out = vec![(field << 3) | 2, data.len() as u8];
        out.extend_from_slice(data);
        out
    }
    #[test]
    fn decodes_lyrics_only_and_preserves_media() {
        let mut lyrics = wire(1, b"hello");
        lyrics.extend(wire(2, b"spotify:track:123"));
        let mut row = wire(1, b"lyric_1");
        row.extend(wire(5, &lyrics));
        let mut p = wire(2, b"Featured");
        p.extend(wire(3, &row));
        let result = decode_packs(&wire(1, &p), false).unwrap();
        assert_eq!(result["packs"][0]["stickers"][0]["lyricsText"], "hello");
        assert_eq!(result["packs"][0]["stickers"][0]["stickerId"], "lyric_1");
    }
    #[test]
    fn rejects_malformed_wire_and_unsafe_ids() {
        assert!(decode_packs(&[0x0a, 0xff], false).is_err());
        assert!(sticker(&wire(1, b"not/an/id")).unwrap().is_none());
        assert!(!valid_sticker_id("../traversal"));
        assert!(!valid_sticker_id(".."));
        assert!(!valid_sticker_id("has newline\n"));
        assert!(segment("../traversal").is_err());
    }
    #[test]
    fn opaque_provider_sticker_ids_are_preserved_and_encoded_for_details() {
        let mut lyrics = wire(1, b"example");
        lyrics.extend(wire(2, b"spotify:track:123"));
        let mut item = wire(1, b"spotify:sticker:abc.def");
        item.extend(wire(5, &lyrics));
        let parsed = sticker(&item).unwrap().unwrap();
        assert_eq!(parsed.sticker_id, "spotify:sticker:abc.def");
        assert!(valid_sticker_id(&parsed.sticker_id));
        assert_eq!(percent_encode(&parsed.sticker_id), "spotify%3Asticker%3Aabc.def");
    }
    #[test]
    fn non_success_http_status_is_never_accepted_as_sticker_protobuf() {
        assert!(ensure_success(200).is_ok());
        assert!(ensure_success(204).is_ok());
        assert_eq!(ensure_success(401).unwrap_err().status, Some(401));
        assert_eq!(ensure_success(429).unwrap_err().status, Some(429));
        assert_eq!(ensure_success(500).unwrap_err().status, Some(500));
    }
    #[test]
    fn percent_encodes_query() {
        assert_eq!(percent_encode("hello world & ok"), "hello%20world%20%26%20ok");
    }
}
