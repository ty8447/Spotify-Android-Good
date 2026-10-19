//! `catalog.whatsNew` (pathfinder `queryWhatsNewFeed`, the web player's "What's new": new
//! releases of followed artists and new episodes of followed shows) and `catalog.friendActivity`
//! (spclient `presence-view/v1/buddylist`, the desktop app's "Friend Activity").

use super::http::{self, JSON};
use super::{pathfinder, pfparse};
use crate::engine;
use crate::error::{AppError, AppResult};
use crate::models::{Image, MediaRef, MediaType};
use serde::Serialize;
use serde_json::{json, Value};

/// What one friend listens to (or last listened to).
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct FriendActivity {
    pub username: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub image: Option<String>,
    pub track: MediaRef,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub context_uri: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub context_name: Option<String>,
    pub timestamp_ms: i64,
    /// From the listening-activity feed: playing right now. None when that feed is unavailable.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub is_active: Option<bool>,
}

/// Every entity of a pathfinder answer that reads as a tile, in order, each once.
fn collect_media(v: &Value, out: &mut Vec<MediaRef>) {
    if let Some(m) = pfparse::media(v) {
        if !out.iter().any(|o| o.uri == m.uri) {
            out.push(m);
        }
        return;
    }
    match v {
        Value::Array(a) => a.iter().for_each(|x| collect_media(x, out)),
        Value::Object(o) => o.values().for_each(|x| collect_media(x, out)),
        _ => {}
    }
}

/// A buddylist response's friends, newest first (entries without a user or track skipped).
pub(crate) fn parse_buddylist(body: &[u8]) -> AppResult<Vec<FriendActivity>> {
    let v: Value = http::json(body)?;
    let s = |v: &Value, p: &str| v.pointer(p).and_then(Value::as_str).filter(|s| !s.is_empty()).map(str::to_string);
    let mut out: Vec<FriendActivity> = v
        .get("friends")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|f| {
            let username = s(f, "/user/uri")?.strip_prefix("spotify:user:")?.to_string();
            let images = s(f, "/track/imageUrl").map(|url| vec![Image { url, width: None, height: None }]).unwrap_or_default();
            let track = MediaRef { kind: MediaType::Track, uri: s(f, "/track/uri")?, name: s(f, "/track/name")?, subtitle: s(f, "/track/artist/name"), images };
            Some(FriendActivity {
                name: s(f, "/user/name").unwrap_or_else(|| username.clone()),
                username,
                image: s(f, "/user/imageUrl"),
                track,
                context_uri: s(f, "/track/context/uri"),
                context_name: s(f, "/track/context/name"),
                timestamp_ms: f.get("timestamp").and_then(Value::as_i64).unwrap_or(0),
                is_active: None,
            })
        })
        .collect();
    out.sort_by(|a, b| b.timestamp_ms.cmp(&a.timestamp_ms));
    Ok(out)
}

pub(crate) async fn whats_new(_args: Value) -> AppResult<Value> {
    let session = engine::session()?;
	log::info!("product state: {:?}", session.user_data().attributes);
    let variables = json!({ "offset": 0, "limit": 50, "onlyUnPlayedItems": false, "includedContentTypes": [] });
    let answer = pathfinder::query(&session, "queryWhatsNewFeed", variables).await.map_err(AppError::from)?;
    let mut items = Vec::new();
    collect_media(&answer.data, &mut items);
    Ok(json!({ "items": items }))
}

/// Spotify Your Updates notifications (concert announcements, save-the-dates and reminders).
/// Kept separate from `catalog.whatsNew`, which contains releases and podcast episodes.
pub(crate) async fn notifications(_args: Value) -> AppResult<Value> {
    let session = engine::session()?;
    let body = http::spc_get_grpc(&session, "/gander/v2/GetNotifications?locale=en_US&limit=20").await
        .map_err(|e| {
            log::warn!("Your Updates request failed: status={:?} error={}", e.status, e.error);
            e.error
        })?;
    let data: Value = http::json(&body)?;
    let items = data.get("notifications").and_then(Value::as_array)
        .ok_or_else(|| AppError::new(crate::error::ErrorCode::InvalidArgument, "Invalid notifications response"))?;
    // Forward exactly the notification fields from the response. Android uses a lenient model
    // so additional future action and artwork fields won't break decoding.
    log::info!("Your Updates received {} notifications", items.len());
    Ok(json!({ "items": items, "latestCursor": data.get("latestCursor") }))
}

// Wire-format protobuf for Gander. Using the official APK field numbers avoids a generated
// protobuf dependency; both mutations are sent once and only acknowledged on HTTP success.
fn pb_string(field: u8, value: &str) -> Vec<u8> {
    let mut bytes = vec![(field << 3) | 2];
    let mut n = value.len();
    while n >= 128 {
        bytes.push(((n & 0x7f) as u8) | 0x80);
        n >>= 7;
    }
    bytes.push(n as u8);
    bytes.extend_from_slice(value.as_bytes());
    bytes
}

pub(crate) async fn mark_notification_read(args: Value) -> AppResult<Value> {
    let cursor = args.get("cursor").and_then(Value::as_str)
        .filter(|s| !s.is_empty() && s.bytes().all(|c| c.is_ascii_digit()))
        .ok_or_else(|| AppError::new(crate::error::ErrorCode::InvalidArgument, "Missing notification cursor"))?;
    let session = engine::session()?;
    http::spc_send_once(&session, ::http::Method::POST,
        "/gander/v2/ResetLatestCursor", http::PROTOBUF, http::PROTOBUF,
        pb_string(1, cursor)).await.map_err(|e| e.error)?;
    Ok(json!({}))
}

pub(crate) async fn delete_notification(args: Value) -> AppResult<Value> {
    let id = args.get("storageId").and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| AppError::new(crate::error::ErrorCode::InvalidArgument, "Missing notification storage ID"))?;
    let session = engine::session()?;
    // UpdateNotificationStateRequest: storage_ids = field 1 (string), state = field 2
    // (enum: AVAILABLE=0, DELETED=1). The enum field is varint encoded.
    let mut body = pb_string(1, id);
    body.extend_from_slice(&[0x10, 0x01]);
    http::spc_send_once(&session, ::http::Method::POST,
        "/gander/v2/UpdateNotificationState", http::PROTOBUF, http::PROTOBUF,
        body).await.map_err(|e| e.error)?;
    Ok(json!({}))
}

pub(crate) async fn delete_notifications(args: Value) -> AppResult<Value> {
    let ids = args.get("storageIds").and_then(Value::as_array)
        .ok_or_else(|| AppError::new(crate::error::ErrorCode::InvalidArgument, "Missing storage IDs"))?;
    if ids.is_empty() || ids.len() > 100 {
        return Err(AppError::new(crate::error::ErrorCode::InvalidArgument, "Invalid notification batch size"));
    }
    let mut body = Vec::new();
    for id in ids {
        let id = id.as_str().filter(|s| !s.is_empty())
            .ok_or_else(|| AppError::new(crate::error::ErrorCode::InvalidArgument, "Invalid storage ID"))?;
        body.extend_from_slice(&pb_string(1, id));
    }
    body.extend_from_slice(&[0x10, 0x01]); // DELETED
    let session = engine::session()?;
    http::spc_send_once(&session, ::http::Method::POST,
        "/gander/v2/UpdateNotificationState", http::PROTOBUF, http::PROTOBUF,
        body).await.map_err(|e| e.error)?;
    Ok(json!({}))
}

pub(crate) async fn friend_activity(_args: Value) -> AppResult<Value> {
    let session = engine::session()?;
    let (buddies, feed) = tokio::join!(
        http::spc_get(&session, "/presence-view/v1/buddylist", Some(JSON)),
        http::spc_post_idempotent(&session, "/listening-activity/v1/feed", JSON, JSON, br#"{"unused":true,"resultLimit":100}"#),
    );
    let mut items = parse_buddylist(&buddies?)?;
    if let Ok(body) = feed {
        let playing = playing_users(&body);
        items.iter_mut().for_each(|f| f.is_active = Some(playing.contains(&f.username)));
    }
    Ok(json!({ "items": items }))
}

/// Usernames the listening-activity feed reports as playing right now
/// (entries are `{"followEntity"|"userEntity": {"uri", "activity": {"isPlaying", ..}}}`).
fn playing_users(body: &[u8]) -> std::collections::HashSet<String> {
    let v: Value = serde_json::from_slice(body).unwrap_or_default();
    v["entities"].as_array().into_iter().flatten()
        .filter_map(|e| e.as_object()?.values().next())
        .filter(|e| e.pointer("/activity/isPlaying").and_then(Value::as_bool) == Some(true))
        .filter_map(|e| e["uri"].as_str()?.strip_prefix("spotify:user:").map(str::to_string))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_buddylist() {
        let body = br#"{"friends":[
            {"timestamp":100,"user":{"uri":"spotify:user:bob","name":"Bob"},
             "track":{"uri":"spotify:track:a","name":"Old","artist":{"uri":"spotify:artist:x","name":"X"}}},
            {"timestamp":200,"user":{"uri":"spotify:user:31abc","name":"Ann","imageUrl":"https://i.scdn.co/image/u"},
             "track":{"uri":"spotify:track:b","name":"New","imageUrl":"https://i.scdn.co/image/t","artist":{"name":"Y"},
                      "context":{"uri":"spotify:playlist:p","name":"Mix","index":0}}},
            {"timestamp":300,"user":{"uri":"spotify:user:eve"}}
        ]}"#;
        let f = parse_buddylist(body).unwrap();
        assert_eq!(f.len(), 2, "an entry without a track is skipped");
        assert_eq!((f[0].username.as_str(), f[0].name.as_str()), ("31abc", "Ann"), "newest first");
        assert_eq!(f[0].track.subtitle.as_deref(), Some("Y"));
        assert_eq!(f[0].context_name.as_deref(), Some("Mix"));
        assert_eq!(f[1].track.images.len(), 0);
        assert!(parse_buddylist(b"{}").unwrap().is_empty());
    }

    #[test]
    fn reads_who_is_playing() {
        let body = br#"{"entities":[
            {"followEntity":{"uri":"spotify:user:ann","activity":{"isPlaying":true}}},
            {"userEntity":{"uri":"spotify:user:bob","activity":{"isPlaying":false}}}]}"#;
        assert_eq!(playing_users(body), ["ann".to_string()].into());
        assert!(playing_users(b"oops").is_empty());
    }
}
