//! Music videos and video podcasts, as the Android client plays them: HLS with Widevine.
//!
//! * A song's music video: extended-metadata `VIDEO_ASSOCIATIONS` (99) →
//!   `VideoAssociations{1: repeated VideoAssociation{1: track_uri (the video's track), 2: images}}`;
//!   that track's `TRACK_V4` (10) `Track.original_video` (38) `Video{1: gid}`.
//! * A video episode: `EPISODE_V4` (12) `Episode.video` (72) `VideoFile{1: file_id}`.
//! * The source id (hex) → `https://spclient.wg.spotify.com/manifests/v9/hls/sources/{id}/master.m3u8
//!   ?supports_drm&gzip&filetype=mp4`, keys from `POST /widevine-license/v1/video/license`.

use super::metadata;
use super::showextras::fields;
use crate::engine;
use crate::error::{AppError, AppResult};
use crate::rpc::parse_args;
use serde::Deserialize;
use serde_json::{json, Value};

const TRACK_V4: i32 = 10;
const EPISODE_V4: i32 = 12;
const VIDEO_ASSOCIATIONS: i32 = 99;
const SPCLIENT: &str = "https://spclient.wg.spotify.com";

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// The first `{1: bytes}` of each `field` message in `b`, as hex.
fn first_id(b: &[u8], field: u32) -> Option<String> {
    fields(b).into_iter().filter(|f| f.0 == field)
        .find_map(|(_, _, m)| fields(m).into_iter().find(|x| x.0 == 1).map(|x| hex(x.2)))
        .filter(|h| !h.is_empty())
}

async fn raw(kind: i32, uri: &str) -> AppResult<Option<Vec<u8>>> {
    let session = engine::session()?;
    Ok(metadata::fetch_extension_raw(&session, kind, &[uri.to_string()]).await?.found.remove(uri))
}

/// The HLS source of a track's music video or a video episode, if it has one.
async fn source(uri: &str) -> AppResult<Option<String>> {
    if uri.starts_with("spotify:episode:") {
        return Ok(raw(EPISODE_V4, uri).await?.and_then(|b| first_id(&b, 72)));
    }
    if !uri.starts_with("spotify:track:") {
        return Err(AppError::invalid("not a track or episode"));
    }
    // A video track itself carries the video; a song points at its video's track.
    if let Some(id) = raw(TRACK_V4, uri).await?.and_then(|b| first_id(&b, 38)) {
        return Ok(Some(id));
    }
    let video_track = raw(VIDEO_ASSOCIATIONS, uri).await?.and_then(|b| {
        fields(&b).into_iter().filter(|f| f.0 == 1).find_map(|(_, _, a)| {
            fields(a).into_iter().find(|x| x.0 == 1).map(|x| String::from_utf8_lossy(x.2).into_owned())
        })
    });
    match video_track.filter(|t| t.starts_with("spotify:track:") && t != uri) {
        Some(t) => Ok(raw(TRACK_V4, &t).await?.and_then(|b| first_id(&b, 38))),
        None => Ok(None),
    }
}

/// `catalog.video {uri}` → `{manifestUrl, licenseUrl, headers}` or `{}` when it has no video.
pub(crate) async fn video(args: Value) -> AppResult<Value> {
    #[derive(Deserialize)]
    struct Uri {
        uri: String,
    }
    let a: Uri = parse_args(args)?;
    let Some(id) = source(&a.uri).await? else { return Ok(json!({})) };
    let session = engine::session()?;
    let token = session.login5().auth_token().await.map_err(|e| AppError::unavailable(format!("token: {e}")))?;
    let mut headers = json!({ "Authorization": format!("{} {}", token.token_type, token.access_token) });
    if let Ok(ct) = session.spclient().client_token().await {
        headers["client-token"] = json!(ct);
    }
    log::info!("video: {} has a video", a.uri.split(':').nth(1).unwrap_or_default());
    Ok(json!({
        "manifestUrl": format!("{SPCLIENT}/manifests/v9/hls/sources/{id}/master.m3u8?supports_drm&gzip&filetype=mp4"),
        "licenseUrl": format!("{SPCLIENT}/widevine-license/v1/video/license"),
        "headers": headers,
    }))
}

/// Extended-metadata `CANVAZ` (1): `Canvaz{1: id, 2: url, 3: file_id, 4: type, 5: entity_uri, ...}`;
/// type 0 is an image, 1–3 videos (2, 3 looping), 4 a GIF.
const CANVAZ: i32 = 1;

/// The url and type of a Canvaz message (`None` without a url).
fn parse_canvas(b: &[u8]) -> Option<(String, u64)> {
    let f = fields(b);
    let url = f.iter().find(|x| x.0 == 2).map(|x| String::from_utf8_lossy(x.2).into_owned()).filter(|u| u.starts_with("https://"))?;
    Some((url, f.iter().find(|x| x.0 == 4).map_or(0, |x| x.1)))
}

/// `catalog.canvas {uri}` → `{url, video}` (a song's Canvas: its short looping visual) or `{}`.
pub(crate) async fn canvas(args: Value) -> AppResult<Value> {
    #[derive(Deserialize)]
    struct Uri {
        uri: String,
    }
    let a: Uri = parse_args(args)?;
    if !a.uri.starts_with("spotify:track:") {
        return Ok(json!({}));
    }
    Ok(match raw(CANVAZ, &a.uri).await?.as_deref().and_then(parse_canvas) {
        Some((url, kind)) => json!({ "url": url, "video": (1..=3).contains(&kind) }),
        None => json!({}),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_a_canvas() {
        let url = b"https://canvaz.scdn.co/upload/x.cnvs.mp4";
        let mut b = vec![0x0a, 0x01, b'i', 0x12, url.len() as u8];
        b.extend_from_slice(url);
        b.extend_from_slice(&[0x20, 0x02]);
        assert_eq!(parse_canvas(&b), Some((String::from_utf8_lossy(url).into_owned(), 2)));
        assert_eq!(parse_canvas(&[0x0a, 0x01, b'i']), None);
    }

    #[test]
    fn reads_video_ids() {
        // Track{38: Video{1: 0xab 0xcd}}: field 38 is a two-byte tag.
        let b = [0xb2, 0x02, 0x04, 0x0a, 0x02, 0xab, 0xcd];
        assert_eq!(first_id(&b, 38).as_deref(), Some("abcd"));
        assert_eq!(first_id(&b, 72), None);
    }
}
