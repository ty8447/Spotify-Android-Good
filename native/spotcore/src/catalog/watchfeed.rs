//! Spotify's watch feed: the vertical video feed behind Clips (a podcast's or artist's short
//! videos, clip recommendations, the discovery feed). `POST watch-feed-view/v1/feed` (APK
//! `WatchFeedRequest` → `WatchFeedResponse`):
//!
//! * request `{1: feed_id, 2: entity_id, 99: map<string,string> parameters, 101: pagination
//!   {1: offset, 2: limit}}`; feed ids the APK names: `related-clips`, `clip-recs`,
//!   `artist-clips`, `album-clips`, `countdown-clips`, `discovery-feed`, `tab-feed`;
//! * response `{2: repeated Any items, 100: pagination}`: each item a layout (`Any` again) whose
//!   components carry a `VideoContent{1: video_uri, 2: VideoFile{1: uri, 2: type, 3: start_time,
//!   4: end_time}, 3: thumbnail, 8: title, 12: entrypoint_uri}`.
//!
//! The layouts are server-driven, so items are read by walking every nested `Any` for its
//! `VideoContent`, the first Spotify entity URI and the first image; the type URLs seen are
//! returned too (to learn new layouts from logs).

use super::http;
use super::showextras::fields;
use crate::engine;
use crate::error::{AppError, AppResult};
use crate::rpc::parse_args;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};

const FEED_PATH: &str = "/watch-feed-view/v1/feed";
const PAGE: u64 = 10;

#[derive(Serialize, Default, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub(crate) struct FeedItem {
    /// The episode, clip, track or show the item is about.
    uri: Option<String>,
    title: Option<String>,
    image: Option<String>,
    /// A playable video URL (https), when the item has one.
    video_url: Option<String>,
    /// The video file's id or URI otherwise (an episode's video, for its own player).
    video_file: Option<String>,
    start_ms: u64,
    end_ms: u64,
    /// Where tapping through goes (the full episode, the artist…).
    entrypoint: Option<String>,
}

fn put_varint(out: &mut Vec<u8>, mut v: u64) {
    while v >= 0x80 {
        out.push((v as u8) | 0x80);
        v >>= 7;
    }
    out.push(v as u8);
}

fn put_bytes(out: &mut Vec<u8>, field: u64, b: &[u8]) {
    put_varint(out, field << 3 | 2);
    put_varint(out, b.len() as u64);
    out.extend_from_slice(b);
}

fn request(feed: &str, entity: Option<&str>, params: &BTreeMap<String, String>, offset: u64) -> Vec<u8> {
    let mut b = Vec::new();
    put_bytes(&mut b, 1, feed.as_bytes());
    if let Some(e) = entity.filter(|e| !e.is_empty()) {
        put_bytes(&mut b, 2, e.as_bytes());
    }
    for (k, v) in params {
        let mut entry = Vec::new();
        put_bytes(&mut entry, 1, k.as_bytes());
        put_bytes(&mut entry, 2, v.as_bytes());
        put_bytes(&mut b, 99, &entry);
    }
    let mut page = Vec::new();
    put_varint(&mut page, 1 << 3);
    put_varint(&mut page, offset);
    put_varint(&mut page, 2 << 3);
    put_varint(&mut page, PAGE);
    put_bytes(&mut b, 101, &page);
    b
}

fn text(b: &[u8]) -> Option<String> {
    let s = std::str::from_utf8(b).ok()?;
    (!s.is_empty() && !s.chars().any(char::is_control)).then(|| s.to_string())
}

/// `Any{1: type_url, 2: value}` when `b` is one.
fn as_any(b: &[u8]) -> Option<(String, &[u8])> {
    let f = fields(b);
    let url = f.iter().find(|x| x.0 == 1).and_then(|x| text(x.2)).filter(|u| u.starts_with("type.googleapis.com/"))?;
    Some((url, f.iter().find(|x| x.0 == 2).map_or(&[][..], |x| x.2)))
}

/// Walks a message for its `VideoContent`, entity URI and image (`depth` bounds the walk).
fn walk(b: &[u8], item: &mut FeedItem, types: &mut BTreeSet<String>, depth: u8) {
    if depth > 12 {
        return;
    }
    if let Some((url, value)) = as_any(b) {
        let name = url.rsplit('.').next().unwrap_or_default().to_string();
        if name == "VideoContent" {
            video_content(value, item);
        }
        types.insert(name);
        walk(value, item, types, depth + 1);
        return;
    }
    for (_, _, data) in fields(b) {
        if data.is_empty() {
            continue;
        }
        match text(data) {
            Some(s) if s.starts_with("spotify:") && is_entity(&s) => {
                item.uri.get_or_insert(s);
            }
            Some(s) if s.starts_with("https://") && looks_like_image(&s) => {
                item.image.get_or_insert(s);
            }
            Some(_) => {}
            None => walk(data, item, types, depth + 1),
        }
    }
}

fn is_entity(s: &str) -> bool {
    ["spotify:episode:", "spotify:clip:", "spotify:track:", "spotify:show:", "spotify:artist:", "spotify:album:"].iter().any(|p| s.starts_with(p))
}

fn looks_like_image(s: &str) -> bool {
    s.contains("i.scdn.co") || s.contains("image") || [".jpg", ".jpeg", ".png", ".webp"].iter().any(|e| s.ends_with(e))
}

fn video_content(b: &[u8], item: &mut FeedItem) {
    for (field, _, data) in fields(b) {
        match field {
            1 => {
                if let Some(s) = text(data) {
                    if s.starts_with("https://") { item.video_url.get_or_insert(s) } else { item.video_file.get_or_insert(s) };
                }
            }
            2 => {
                let f = fields(data);
                if let Some(u) = f.iter().find(|x| x.0 == 1).and_then(|x| text(x.2)) {
                    if u.starts_with("https://") { item.video_url.get_or_insert(u) } else { item.video_file.get_or_insert(u) };
                }
                item.start_ms = f.iter().find(|x| x.0 == 3).map_or(0, |x| x.1);
                item.end_ms = f.iter().find(|x| x.0 == 4).map_or(0, |x| x.1);
            }
            3 => {
                let mut sink = FeedItem::default();
                walk(data, &mut sink, &mut BTreeSet::new(), 10);
                if let (None, Some(i)) = (&item.image, sink.image) {
                    item.image = Some(i);
                }
            }
            8 => item.title = item.title.take().or_else(|| text(data)),
            12 => item.entrypoint = item.entrypoint.take().or_else(|| text(data)),
            _ => {}
        }
    }
}

/// The items (and the type URLs seen) of a `WatchFeedResponse`.
fn parse(b: &[u8]) -> (Vec<FeedItem>, BTreeSet<String>) {
    let mut types = BTreeSet::new();
    let items = fields(b)
        .into_iter()
        .filter(|f| f.0 == 2)
        .filter_map(|(_, _, any)| {
            let mut item = FeedItem::default();
            walk(any, &mut item, &mut types, 0);
            (item.video_url.is_some() || item.video_file.is_some() || item.uri.is_some()).then_some(item)
        })
        .collect();
    (items, types)
}

/// `catalog.watchFeed {feedId, entityId?, params?, offset?}` → `{items, types}`.
pub(crate) async fn watch_feed(args: Value) -> AppResult<Value> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct A {
        feed_id: String,
        entity_id: Option<String>,
        #[serde(default)]
        params: BTreeMap<String, String>,
        #[serde(default)]
        offset: u64,
    }
    let a: A = parse_args(args)?;
    if a.feed_id.is_empty() || a.feed_id.len() > 64 || !a.feed_id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-') {
        return Err(AppError::invalid("Invalid feed"));
    }
    let session = engine::session()?;
    let body = request(&a.feed_id, a.entity_id.as_deref(), &a.params, a.offset);
    let raw = http::spc_post_idempotent(&session, FEED_PATH, http::PROTOBUF, http::PROTOBUF, &body)
        .await
        .map_err(|e| AppError::unavailable(format!("Clips aren't available: {}", e.status.map_or("network error".into(), |s| format!("HTTP {s}")))))?;
    let (items, types) = parse(&raw);
    log::info!("watch feed {}: {} items, layouts {:?}", a.feed_id, items.len(), types);
    Ok(json!({ "items": items, "types": types }))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn any(name: &str, value: &[u8]) -> Vec<u8> {
        let mut b = Vec::new();
        put_bytes(&mut b, 1, format!("type.googleapis.com/spotify.watchfeed.v1.{name}").as_bytes());
        put_bytes(&mut b, 2, value);
        b
    }

    #[test]
    fn reads_a_clip_from_nested_layouts() {
        let mut file = Vec::new();
        put_bytes(&mut file, 1, b"https://video.example/clip.mp4");
        put_varint(&mut file, 3 << 3);
        put_varint(&mut file, 61_000);
        put_varint(&mut file, 4 << 3);
        put_varint(&mut file, 92_000);
        let mut video = Vec::new();
        put_bytes(&mut video, 2, &file);
        put_bytes(&mut video, 8, b"The best bit");
        put_bytes(&mut video, 12, b"spotify:episode:abc");
        let mut layout = Vec::new();
        put_bytes(&mut layout, 1, &any("VideoContent", &video));
        put_bytes(&mut layout, 8, b"spotify:episode:abc");
        put_bytes(&mut layout, 9, b"https://i.scdn.co/image/xyz");
        let mut resp = Vec::new();
        put_bytes(&mut resp, 2, &any("StorytellingDefaultLayout", &layout));
        let (items, types) = parse(&resp);
        assert_eq!(items.len(), 1);
        let i = &items[0];
        assert_eq!(i.video_url.as_deref(), Some("https://video.example/clip.mp4"));
        assert_eq!((i.start_ms, i.end_ms), (61_000, 92_000));
        assert_eq!(i.title.as_deref(), Some("The best bit"));
        assert_eq!(i.uri.as_deref(), Some("spotify:episode:abc"));
        assert_eq!(i.image.as_deref(), Some("https://i.scdn.co/image/xyz"));
        assert!(types.contains("VideoContent") && types.contains("StorytellingDefaultLayout"));
        // The request carries the feed, entity, parameters and page.
        let r = request("related-clips", Some("spotify:show:x"), &BTreeMap::from([("k".into(), "v".into())]), 10);
        let f = fields(&r);
        assert_eq!(f.iter().map(|x| x.0).collect::<Vec<_>>(), vec![1, 2, 99, 101]);
    }
}
