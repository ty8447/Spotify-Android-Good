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

// The mobile listening-activity feed and the desktop buddylist are not identical.
// In particular, the feed may include message contacts absent from the buddylist.
// Until we verify its track-reference schema, report aggregate differences rather than
// creating synthetic rows, inventing track details, or logging any account identifiers.
#[derive(Debug, PartialEq, Eq)]
struct ActivitySourceSummary {
    entities: usize,
    users: usize,
    absent_from_buddylist: usize,
    playing: usize,
    entity_fields: Vec<String>,
    activity_fields: Vec<String>,
}

fn summarize_activity_source(body: &[u8], buddies: &[FriendActivity]) -> Option<ActivitySourceSummary> {
    use std::collections::{BTreeSet, HashSet};
    let value: Value = serde_json::from_slice(body).ok()?;
    let entities = value.get("entities")?.as_array()?;
    let buddy_users: HashSet<&str> = buddies.iter().map(|b| b.username.as_str()).collect();
    let mut users: HashSet<&str> = HashSet::new();
    let mut missing: HashSet<&str> = HashSet::new();
    let mut active: HashSet<&str> = HashSet::new();
    let mut entity_fields = BTreeSet::new();
    let mut activity_fields = BTreeSet::new();

    for item in entities {
        let entity = item.get("followEntity").or_else(|| item.get("userEntity"));
        let Some(entity) = entity else { continue };
        if let Some(object) = entity.as_object() {
            entity_fields.extend(object.keys().cloned());
        }
        if let Some(object) = entity.get("activity").and_then(Value::as_object) {
            activity_fields.extend(object.keys().cloned());
        }
        let Some(username) = entity.get("uri").and_then(Value::as_str)
            .and_then(|uri| uri.strip_prefix("spotify:user:"))
            .filter(|s| !s.is_empty()) else { continue };
        users.insert(username);
        if !buddy_users.contains(username) { missing.insert(username); }
        if entity.pointer("/activity/isPlaying").and_then(Value::as_bool) == Some(true) {
            active.insert(username);
        }
    }

    Some(ActivitySourceSummary {
        entities: entities.len(),
        users: users.len(),
        absent_from_buddylist: missing.len(),
        playing: active.len(),
        // Report field names only. Never log user IDs, listening history or response bodies.
        entity_fields: entity_fields.into_iter().take(24).collect(),
        activity_fields: activity_fields.into_iter().take(24).collect(),
    })
}

/// A newer mobile-feed activity absent from the desktop buddylist. The feed
/// provides IDs, not titles or profile pictures; Android resolves verified media
/// and public profile metadata before displaying the activity.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct MobileFeedOnlyActivity {
    username: String,
    media_uri: String,
    context_uri: Option<String>,
    timestamp_ms: i64,
    is_active: bool,
}

/// Return only users Spotify includes in the mobile feed and whose media URI is
/// a valid track/episode reference. Never fabricate an activity or song title.
fn mobile_feed_only(body: &[u8], buddies: &[FriendActivity]) -> Vec<MobileFeedOnlyActivity> {
    use std::collections::HashSet;
    let Ok(value) = serde_json::from_slice::<Value>(body) else { return Vec::new() };
    let Some(entities) = value.get("entities").and_then(Value::as_array) else { return Vec::new() };
    let buddy_users: HashSet<&str> = buddies.iter().map(|b| b.username.as_str()).collect();
    let mut seen = HashSet::new();
    let mut missing = Vec::new();
    for item in entities {
        let Some(entity) = item.get("followEntity").or_else(|| item.get("userEntity")) else { continue };
        let Some(username) = entity.get("uri").and_then(Value::as_str)
            .and_then(|s| s.strip_prefix("spotify:user:"))
            .filter(|s| !s.is_empty() && s.len() <= 256) else { continue };
        if buddy_users.contains(username) || seen.contains(username) { continue; }
        let Some(media_uri) = entity.pointer("/activity/entityUri").and_then(Value::as_str) else { continue };
        let media_id = media_uri.strip_prefix("spotify:track:")
            .or_else(|| media_uri.strip_prefix("spotify:episode:"));
        if !media_id.is_some_and(|id| id.len() == 22 && id.bytes().all(|b| b.is_ascii_alphanumeric())) {
            continue;
        }
        seen.insert(username.to_string());
        let activity = &entity["activity"];
        let context_uri = activity.get("contextUri").and_then(Value::as_str)
            .filter(|s| s.starts_with("spotify:")).map(str::to_string);
        let timestamp = activity.get("timestamp").and_then(Value::as_i64)
            .or_else(|| activity.get("timestamp").and_then(Value::as_str)?.parse::<i64>().ok())
            .unwrap_or(0).max(0);
        // Spotify APIs may express timestamps in either epoch seconds or millis.
        let timestamp_ms = if timestamp > 0 && timestamp < 1_000_000_000_000 {
            timestamp.saturating_mul(1000)
        } else { timestamp };
        missing.push(MobileFeedOnlyActivity {
            username: username.to_string(), media_uri: media_uri.to_string(), context_uri,
            timestamp_ms, is_active: activity.get("isPlaying").and_then(Value::as_bool) == Some(true),
        });
    }
    missing.sort_by(|a, b| b.timestamp_ms.cmp(&a.timestamp_ms));
    missing
}


// Read-only diagnostic parser for the official protobuf GetFeedResponse.
// Field numbers verified against the Android APK: GetFeedResponse.entities=1,
// FeedEntityWrapper.groupEntity=3, GroupFeedEntity.conversationUri=2,
// GroupFeedEntity.members=3, GroupMember.activity=2.
// Logs counts only; never decode or log names, URIs, or listening history.
#[derive(Default)]
struct GroupProbeSummary {
    entities: usize,
    groups: usize,
    valid_group_uris: usize,
    members: usize,
    with_activity: usize,
}

// Small, bounded protobuf wire reader. This probe is intentionally independent
// of the application's production feed decoding and does not change results.
fn probe_varint(bytes: &[u8], pos: &mut usize) -> Option<u64> {
    let mut out = 0_u64;
    for index in 0..10 {
        let byte = *bytes.get(*pos)?;
        *pos += 1;
        if index == 9 && byte > 1 { return None; }
        out |= u64::from(byte & 0x7f) << (index * 7);
        if byte & 0x80 == 0 { return Some(out); }
    }
    None
}

fn probe_wire_fields(bytes: &[u8]) -> Option<Vec<(u32, u8, &[u8])>> {
    if bytes.len() > 4 * 1024 * 1024 { return None; }
    let mut pos = 0;
    let mut fields = Vec::new();
    while pos < bytes.len() {
        if fields.len() >= 100_000 { return None; }
        let tag = probe_varint(bytes, &mut pos)?;
        let field = u32::try_from(tag >> 3).ok()?;
        let wire = (tag & 7) as u8;
        if field == 0 { return None; }
        let start;
        let end;
        match wire {
            0 => {
                start = pos;
                probe_varint(bytes, &mut pos)?;
                end = pos;
            }
            1 => {
                start = pos;
                pos = pos.checked_add(8)?;
                end = pos;
            }
            2 => {
                let size = usize::try_from(probe_varint(bytes, &mut pos)?).ok()?;
                start = pos;
                pos = pos.checked_add(size)?;
                end = pos;
            }
            5 => {
                start = pos;
                pos = pos.checked_add(4)?;
                end = pos;
            }
            _ => return None,
        }
        fields.push((field, wire, bytes.get(start..end)?));
    }
    Some(fields)
}

fn summarize_group_protobuf(body: &[u8]) -> Option<GroupProbeSummary> {
    let mut counts = GroupProbeSummary::default();
    for (number, wire, wrapper) in probe_wire_fields(body)? {
        if number != 1 || wire != 2 { continue; }
        counts.entities += 1;
        for (kind, kind_wire, group) in probe_wire_fields(wrapper)? {
            if kind != 3 || kind_wire != 2 { continue; }
            counts.groups += 1;
            for (field, value_wire, value) in probe_wire_fields(group)? {
                match (field, value_wire) {
                    (2, 2) if value.starts_with(b"spotify:") => counts.valid_group_uris += 1,
                    (3, 2) => {
                        counts.members += 1;
                        let member_fields = probe_wire_fields(value)?;
                        if member_fields.iter().any(|(f, w, _)| *f == 2 && *w == 2) {
                            counts.with_activity += 1;
                        }
                    }
                    _ => {}
                }
            }
        }
    }
    Some(counts)
}

// Diagnostic summary of a feed response. Protobuf permits an empty message,
// so report byte length and top-level wire fields before interpreting counts.
// No response payload, identity, track or conversation data enters the logs.
fn log_group_comparison(source: &str, body: &[u8]) {
    let parsed = probe_wire_fields(body);
    let format = if body.is_empty() {
        "empty"
    } else if parsed.is_some() {
        "protobuf"
    } else if serde_json::from_slice::<Value>(body).is_ok() {
        "json"
    } else {
        "unrecognized"
    };
    let root_fields: Vec<u32> = parsed.as_ref()
        .map(|fields| fields.iter().take(8).map(|(number, _, _)| *number).collect())
        .unwrap_or_default();
    match summarize_group_protobuf(body) {
        Some(info) => log::info!(
            "SpotifyGoodGroupCompare: source={} format={} bytes={} rootFields={:?} entities={} groups={} members={} withActivity={}",
            source, format, body.len(), root_fields, info.entities, info.groups, info.members,
            info.with_activity,
        ),
        None => log::info!(
            "SpotifyGoodGroupCompare: source={} format={} bytes={} rootFields={:?} decode=failed",
            source, format, body.len(), root_fields,
        ),
    }
}

// The official mobile client also fetches listening activity for an explicit
// set of usernames via /listening-activity/v1/users-activity. This diagnostic
// is restricted to participants supplied by an opened group chat. It returns
// aggregate counts ONLY; it cannot make any activity visible in the UI.
#[derive(Debug, Default, PartialEq, Eq)]
struct GroupUsersProbeSummary {
    returned: usize,
    with_activity: usize,
    playable: usize,
    playing: usize,
}

fn summarize_group_users_protobuf(body: &[u8]) -> Option<GroupUsersProbeSummary> {
    let mut result = GroupUsersProbeSummary::default();
    // UsersActivityResponse.activities=1, UserActivity.activity=2,
    // ListeningActivity.entityUri=1, isPlaying=2 (official APK schema).
    for (field, wire, user) in probe_wire_fields(body)? {
        if field != 1 || wire != 2 { continue; }
        result.returned += 1;
        for (f, w, activity) in probe_wire_fields(user)? {
            if f != 2 || w != 2 { continue; }
            result.with_activity += 1;
            for (af, aw, value) in probe_wire_fields(activity)? {
                if af == 1 && aw == 2 {
                    let media = std::str::from_utf8(value).ok()?;
                    let id = media.strip_prefix("spotify:track:")
                        .or_else(|| media.strip_prefix("spotify:episode:"));
                    if id.is_some_and(|id| id.len() == 22 &&
                        id.bytes().all(|b| b.is_ascii_alphanumeric())) {
                        result.playable += 1;
                    }
                } else if af == 2 && aw == 0 {
                    let mut offset = 0;
                    if probe_varint(value, &mut offset) == Some(1) {
                        result.playing += 1;
                    }
                }
            }
        }
    }
    Some(result)
}

// The official mobile client uses UsersActivityRequest (repeated usernames, field 1)
// to populate member listening activity. The service returns only the activity
// visible to the authenticated caller. Do not synthesize missing rows, or ever
// expose response data outside the actual conversation participant set.
fn parse_member_timestamp(body: &[u8]) -> Option<i64> {
    let mut seconds: Option<i64> = None;
    let mut nanos: u64 = 0;
    for (field, wire, bytes) in probe_wire_fields(body)? {
        if wire != 0 { continue; }
        let mut pos = 0;
        let value = probe_varint(bytes, &mut pos)?;
        match field {
            1 => seconds = i64::try_from(value).ok(),
            2 => nanos = value,
            _ => {},
        }
    }
    let seconds = seconds?;
    if !(0..=4_102_444_800).contains(&seconds) || nanos >= 1_000_000_000 { return None; }
    seconds.checked_mul(1000)?.checked_add(i64::try_from(nanos / 1_000_000).ok()?)
}

fn parse_group_user_activities(
    body: &[u8], requested: &std::collections::HashSet<String>,
) -> Option<(usize, Vec<MobileFeedOnlyActivity>)> {
    let mut returned = 0;
    let mut selected = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for (outer_field, outer_wire, user) in probe_wire_fields(body)? {
        if outer_field != 1 || outer_wire != 2 { continue; }
        returned += 1;
        let mut username = None;
        let mut listening = None;
        for (field, wire, bytes) in probe_wire_fields(user)? {
            match (field, wire) {
                (1, 2) => {
                    let candidate = std::str::from_utf8(bytes).ok()?;
                    let canonical = candidate.strip_prefix("spotify:user:").unwrap_or(candidate);
                    if requested.contains(canonical) { username = Some(canonical.to_string()); }
                },
                (2, 2) => listening = Some(bytes),
                _ => {},
            }
        }
        let Some(username) = username else { continue; };
        if seen.contains(&username) { continue; }
        let Some(listening) = listening else { continue; };
        let mut media_uri = None;
        let mut context_uri = None;
        let mut is_active = false;
        let mut timestamp_ms = 0;
        for (field, wire, bytes) in probe_wire_fields(listening)? {
            match (field, wire) {
                (1, 2) => {
                    let value = std::str::from_utf8(bytes).ok()?;
                    let id = value.strip_prefix("spotify:track:")
                        .or_else(|| value.strip_prefix("spotify:episode:"));
                    if id.is_some_and(|id| id.len() == 22 &&
                        id.bytes().all(|byte| byte.is_ascii_alphanumeric())) {
                        media_uri = Some(value.to_string());
                    }
                },
                (2, 0) => {
                    let mut pos = 0;
                    is_active = probe_varint(bytes, &mut pos)? == 1;
                },
                (3, 2) => timestamp_ms = parse_member_timestamp(bytes).unwrap_or(0),
                (4, 2) => {
                    let value = std::str::from_utf8(bytes).ok()?;
                    if value.starts_with("spotify:") { context_uri = Some(value.to_string()); }
                },
                _ => {},
            }
        }
        if let Some(media_uri) = media_uri {
            seen.insert(username.clone());
            selected.push(MobileFeedOnlyActivity {
                username, media_uri, context_uri, timestamp_ms, is_active,
            });
        }
    }
    selected.sort_by(|a, b| b.is_active.cmp(&a.is_active)
        .then_with(|| b.timestamp_ms.cmp(&a.timestamp_ms)));
    Some((returned, selected))
}

/// Read-only activity for explicitly named participants of an opened group.
/// The global FOLLOWING feed is intentionally NOT used as an alternate source.
pub(crate) async fn group_member_activity(args: Value) -> AppResult<Value> {
    let entries = args.get("usernames").and_then(Value::as_array)
        .ok_or_else(|| AppError::new(crate::error::ErrorCode::InvalidArgument,
            "Missing group participant usernames"))?;
    if entries.len() < 3 || entries.len() > 40 {
        return Err(AppError::new(crate::error::ErrorCode::InvalidArgument,
            "Invalid group participant count"));
    }
    let mut unique = std::collections::HashSet::new();
    let mut usernames = Vec::new();
    for item in entries {
        let name = item.as_str().filter(|s| !s.is_empty() && s.len() <= 256 &&
            s.chars().all(|c| !c.is_control() && c != ':' && c != '/' && c != '\\'))
            .ok_or_else(|| AppError::new(crate::error::ErrorCode::InvalidArgument,
                "Invalid group participant username"))?;
        if unique.insert(name.to_string()) { usernames.push(name.to_string()); }
    }
    if usernames.len() < 3 {
        return Err(AppError::new(crate::error::ErrorCode::InvalidArgument,
            "Invalid distinct group participant count"));
    }
    let mut request = Vec::new();
    for name in &usernames { request.extend_from_slice(&pb_string(1, name)); }
    let session = engine::session()?;
    let body = http::spc_post_idempotent(&session,
        "/listening-activity/v1/users-activity", http::PROTOBUF, http::PROTOBUF,
        &request).await.map_err(|error| error.error)?;
    let (returned, items) = parse_group_user_activities(&body, &unique)
        .ok_or_else(|| AppError::new(crate::error::ErrorCode::InvalidArgument,
            "Invalid member activity response"))?;
    log::info!("SpotifyGoodGroupMembers: requested={} returned={} playable={} playing={}",
        usernames.len(), returned, items.len(), items.iter().filter(|item| item.is_active).count());
    Ok(json!({"items": items}))
}

pub(crate) async fn probe_group_member_activity(args: Value) -> AppResult<Value> {
    let entries = args.get("usernames").and_then(Value::as_array)
        .ok_or_else(|| AppError::new(crate::error::ErrorCode::InvalidArgument,
            "Missing group participant usernames"))?;
    if entries.len() < 3 || entries.len() > 40 {
        return Err(AppError::new(crate::error::ErrorCode::InvalidArgument,
            "Invalid group participant count"));
    }
    let mut usernames = Vec::new();
    let mut unique = std::collections::HashSet::new();
    for item in entries {
        let username = item.as_str().filter(|s| !s.is_empty() && s.len() <= 256 &&
            s.chars().all(|c| !c.is_control() && c != ':' && c != '/' && c != '\\'))
            .ok_or_else(|| AppError::new(crate::error::ErrorCode::InvalidArgument,
                "Invalid group participant username"))?;
        if unique.insert(username) { usernames.push(username); }
    }
    if usernames.len() < 3 {
        return Err(AppError::new(crate::error::ErrorCode::InvalidArgument,
            "Invalid distinct group participant count"));
    }
    // UsersActivityRequest.usernames=1 (repeated string). Never log these
    // identifiers or the response body, even if the user list is empty.
    let mut request = Vec::new();
    for username in &usernames { request.extend_from_slice(&pb_string(1, username)); }
    let session = engine::session()?;
    let response = http::spc_post_idempotent(&session,
        "/listening-activity/v1/users-activity", http::PROTOBUF, http::PROTOBUF,
        &request).await;
    match response {
        Ok(body) => {
            let format = if body.is_empty() { "empty" }
                else if probe_wire_fields(&body).is_some() { "protobuf" }
                else { "unrecognized" };
            match summarize_group_users_protobuf(&body) {
                Some(summary) => {
                    log::info!(
                        "SpotifyGoodGroupUsersProbe: requested={} format={} bytes={} returned={} withActivity={} playable={} playing={}",
                        usernames.len(), format, body.len(), summary.returned,
                        summary.with_activity, summary.playable, summary.playing);
                    Ok(json!({"requested":usernames.len(), "returned":summary.returned,
                        "withActivity":summary.with_activity, "playable":summary.playable}))
                }
                None => {
                    log::info!("SpotifyGoodGroupUsersProbe: requested={} format={} bytes={} decode=failed",
                        usernames.len(), format, body.len());
                    Ok(json!({"requested":usernames.len(), "decodeFailed":true}))
                }
            }
        }
        Err(error) => {
            log::info!("SpotifyGoodGroupUsersProbe: requested={} requestFailed status={:?}",
                usernames.len(), error.status);
            Ok(json!({"requested":usernames.len(), "requestFailed":true}))
        }
    }
}

pub(crate) async fn friend_activity(_args: Value) -> AppResult<Value> {
    let session = engine::session()?;
    // Official GetFeedRequest: unused=true (field 1, bool); includeSources (field
    // 3, packed enum). v75 APK enum: GROUP=2, DM=1, FOLLOWING=3.
    // Wire bytes: 08 01 1A 01 02. Leave the existing JSON friend feed unchanged.
    let group_request = [0x08, 0x01, 0x1a, 0x01, 0x02];
    let (buddies, feed, group_probe) = tokio::join!(
        http::spc_get(&session, "/presence-view/v1/buddylist", Some(JSON)),
        http::spc_post_idempotent(&session, "/listening-activity/v1/feed", JSON, JSON, br#"{"unused":true,"resultLimit":100}"#),
        http::spc_post_idempotent(&session, "/listening-activity/v1/feed",
            http::PROTOBUF, http::PROTOBUF, &group_request),
    );
    match group_probe {
        Ok(body) => log_group_comparison("GROUP", &body),
        Err(error) => log::info!("SpotifyGoodGroupCompare: source=GROUP requestFailed status={:?}", error.status),
    }
    // Run control requests once per app process, not on every activity refresh.
    // FOLLOWING validates whether protobuf feeds work for a source known to
    // produce results through the existing JSON friend feed. ALL checks whether
    // Spotify requires a combined source selection for group entries.
    static CONTROL_PROBES_SENT: std::sync::atomic::AtomicBool =
        std::sync::atomic::AtomicBool::new(false);
    if !CONTROL_PROBES_SENT.swap(true, std::sync::atomic::Ordering::Relaxed) {
        let following_request = [0x08, 0x01, 0x1a, 0x01, 0x03];
        let all_sources_request = [0x08, 0x01, 0x1a, 0x03, 0x01, 0x02, 0x03];
        let (following, all) = tokio::join!(
            http::spc_post_idempotent(&session, "/listening-activity/v1/feed",
                http::PROTOBUF, http::PROTOBUF, &following_request),
            http::spc_post_idempotent(&session, "/listening-activity/v1/feed",
                http::PROTOBUF, http::PROTOBUF, &all_sources_request),
        );
        match following {
            Ok(body) => log_group_comparison("FOLLOWING", &body),
            Err(error) => log::info!(
                "SpotifyGoodGroupCompare: source=FOLLOWING requestFailed status={:?}", error.status),
        }
        match all {
            Ok(body) => log_group_comparison("ALL", &body),
            Err(error) => log::info!(
                "SpotifyGoodGroupCompare: source=ALL requestFailed status={:?}", error.status),
        }
    }
    let mut items = parse_buddylist(&buddies?)?;
    let mut feed_only = Vec::new();
    match feed {
        Ok(body) => {
            if let Some(summary) = summarize_activity_source(&body, &items) {
                log::info!(
                    "SpotifyGoodActivity: buddyRows={} feedEntities={} feedUsers={} absentFromBuddy={} playing={} entityFields={:?} activityFields={:?}",
                    items.len(), summary.entities, summary.users, summary.absent_from_buddylist,
                    summary.playing, summary.entity_fields, summary.activity_fields,
                );
            } else {
                log::info!("SpotifyGoodActivity: buddyRows={} feed=unrecognized", items.len());
            }
            // Identify group cards in the same mobile feed without exposing group
            // names, conversation IDs, usernames, or activity history in logs.
            if let Ok(value) = serde_json::from_slice::<Value>(&body) {
                if let Some(entities) = value.get("entities").and_then(Value::as_array) {
                    let groups: Vec<&Value> = entities.iter()
                        .filter_map(|item| item.get("groupEntity")).collect();
                    let group_members: usize = groups.iter().map(|g| {
                        g.get("members").and_then(Value::as_array).map_or(0, Vec::len)
                    }).sum();
                    let fields = groups.first().and_then(|g| g.as_object())
                        .map(|o| o.keys().cloned().collect::<Vec<_>>()).unwrap_or_default();
                    let member_fields = groups.iter().find_map(|g| {
                        g.get("members").and_then(Value::as_array)
                            .and_then(|members| members.first())
                            .and_then(Value::as_object)
                            .map(|obj| obj.keys().cloned().collect::<Vec<_>>())
                    }).unwrap_or_default();
                    log::info!("SpotifyGoodGroupActivity: groups={} members={} groupFields={:?} memberFields={:?}",
                        groups.len(), group_members, fields, member_fields);
                }
            }
            feed_only = mobile_feed_only(&body, &items);
            log::info!("SpotifyGoodActivity: feedOnlyPlayable={}", feed_only.len());
            let playing = playing_users(&body);
            items.iter_mut().for_each(|f| f.is_active = Some(playing.contains(&f.username)));
        }
        Err(_) => {
            log::info!("SpotifyGoodActivity: buddyRows={} feed=unavailable", items.len());
        }
    }
    Ok(json!({ "items": items, "feedOnly": feed_only }))
}

/// Usernames the listening-activity feed reports as playing right now
/// (entries are `{"followEntity"|"userEntity": {"uri", "activity": {"isPlaying", ..}}}`).
fn playing_users(body: &[u8]) -> std::collections::HashSet<String> {
    let v: Value = serde_json::from_slice(body).unwrap_or_default();
    v["entities"].as_array().into_iter().flatten()
        .filter_map(|e| e.get("followEntity").or_else(|| e.get("userEntity")))
        .filter(|e| e.pointer("/activity/isPlaying").and_then(Value::as_bool) == Some(true))
        .filter_map(|e| e["uri"].as_str()?.strip_prefix("spotify:user:").map(str::to_string))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn group_member_parser_only_returns_requested_playable_users() {
        let user = |name: &str, media: Option<&str>| {
            let mut bytes = pb_string(1, name);
            if let Some(media) = media {
                let mut activity = pb_string(1, media);
                activity.extend_from_slice(&[0x10, 0x01]);
                let mut timestamp = vec![0x08];
                timestamp.extend_from_slice(&[0x80, 0xa0, 0x86, 0x8f, 0x06]);
                activity.push(0x1a);
                activity.push(timestamp.len() as u8);
                activity.extend(timestamp);
                bytes.push(0x12);
                bytes.push(activity.len() as u8);
                bytes.extend(activity);
            }
            bytes
        };
        let uri = "spotify:track:0123456789ABCDEFGHIJKL";
        let mut response = Vec::new();
        for member in [user("friend", Some(uri)), user("other", Some(uri)),
                       user("friend2", None)] {
            response.push(0x0a);
            response.push(member.len() as u8);
            response.extend(member);
        }
        let requested = ["friend".to_string(), "friend2".to_string()]
            .into_iter().collect();
        let (returned, parsed) = parse_group_user_activities(&response, &requested).unwrap();
        assert_eq!(returned, 3);
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].username, "friend");
        assert!(parsed[0].is_active);
    }

    #[test]
    fn group_users_probe_counts_playable_and_playing() {
        // UserActivity: username (1), ListeningActivity (2).
        let track = format!("spotify:track:{}", "A".repeat(22));
        let mut activity = pb_string(1, &track);
        activity.extend_from_slice(&[0x10, 0x01]); // isPlaying=true
        let mut person = pb_string(1, "testuser");
        person.push(0x12); // field 2, nested activity
        person.push(activity.len() as u8);
        person.extend(activity);
        let mut response = vec![0x0a, person.len() as u8];
        response.extend(person);
        let counts = summarize_group_users_protobuf(&response).unwrap();
        assert_eq!(counts, GroupUsersProbeSummary {
            returned: 1, with_activity: 1, playable: 1, playing: 1,
        });
        assert_eq!(summarize_group_users_protobuf(&[]).unwrap(),
            GroupUsersProbeSummary::default());
        assert!(summarize_group_users_protobuf(&[0x0a, 0xff]).is_none());
    }

    #[test]
    fn group_probe_distinguishes_empty_and_malformed_responses() {
        assert!(probe_wire_fields(&[]).unwrap().is_empty());
        assert_eq!(summarize_group_protobuf(&[]).unwrap().entities, 0);
        assert!(probe_wire_fields(&[0x0a, 0xff]).is_none());
    }

    #[test]
    fn group_probe_counts_protobuf_without_exposing_user_data() {
        // Two group members, one with activity, one without.
        // group uri=spotify:chat:test (field 2), repeated members (field 3).
        let group = [
            0x12, 0x11, b's', b'p', b'o', b't', b'i', b'f', b'y', b':',
            b'c', b'h', b'a', b't', b':', b't', b'e', b's', b't',
            0x1a, 0x02, 0x12, 0x00, 0x1a, 0x00,
        ];
        let mut wrapper = vec![0x1a, u8::try_from(group.len()).unwrap()];
        wrapper.extend_from_slice(&group);
        let mut answer = vec![0x0a, u8::try_from(wrapper.len()).unwrap()];
        answer.extend_from_slice(&wrapper);
        let counts = summarize_group_protobuf(&answer).unwrap();
        assert_eq!((counts.entities, counts.groups, counts.valid_group_uris,
                    counts.members, counts.with_activity), (1, 1, 1, 2, 1));
        assert!(summarize_group_protobuf(&[0x0a, 0xff]).is_none());
    }

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
    fn diagnostics_count_feed_only_users_without_exposing_identities() {
        let buddy = parse_buddylist(br#"{"friends":[
            {"timestamp":100,"user":{"uri":"spotify:user:alice","name":"Alice"},
             "track":{"uri":"spotify:track:abc","name":"Song"}}
        ]}"#).unwrap();
        let feed = br#"{"entities":[
            {"followEntity":{"uri":"spotify:user:alice","activity":{"isPlaying":true,"trackUri":"spotify:track:abc"}}},
            {"userEntity":{"uri":"spotify:user:bob","activity":{"isPlaying":true,"trackUri":"spotify:track:def"}}},
            {"userEntity":{"uri":"spotify:user:bob","activity":{"isPlaying":true}}}
        ]}"#;
        let summary = summarize_activity_source(feed, &buddy).unwrap();
        assert_eq!(summary.entities, 3);
        assert_eq!(summary.users, 2);
        assert_eq!(summary.absent_from_buddylist, 1);
        assert_eq!(summary.playing, 2);
        assert_eq!(summary.entity_fields, vec!["activity", "uri"]);
        assert_eq!(summary.activity_fields, vec!["isPlaying", "trackUri"]);
        assert!(!format!("{:?}", summary).contains("alice"));
        assert!(!format!("{:?}", summary).contains("bob"));
    }

    #[test]
    fn diagnostics_reject_malformed_feed_without_guessing() {
        assert!(summarize_activity_source(b"{}", &[]).is_none());
        assert!(summarize_activity_source(b"not-json", &[]).is_none());
    }

    #[test]
    fn mobile_feed_merges_only_verified_media_for_users_missing_from_buddylist() {
        let buddy = parse_buddylist(br#"{"friends":[{"timestamp":100,
            "user":{"uri":"spotify:user:alice","name":"Alice"},
            "track":{"uri":"spotify:track:a","name":"Song"}}]}"#).unwrap();
        let valid_id = "0123456789ABCDEFGHIJKL";
        let response = format!(r#"{{"entities":[
            {{"followEntity":{{"uri":"spotify:user:alice","activity":{{"entityUri":"spotify:track:{valid_id}","isPlaying":true}}}}}},
            {{"userEntity":{{"uri":"spotify:user:bob","activity":{{"entityUri":"spotify:track:{valid_id}","contextUri":"spotify:playlist:abc","isPlaying":false,"timestamp":1600000000}}}}}},
            {{"followEntity":{{"uri":"spotify:user:carol","activity":{{"entityUri":"spotify:local:unsupported"}}}}}}
        ]}}"#);
        let entries = mobile_feed_only(response.as_bytes(), &buddy);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].username, "bob");
        assert_eq!(entries[0].media_uri, format!("spotify:track:{valid_id}"));
        assert_eq!(entries[0].timestamp_ms, 1600000000000);
        assert_eq!(entries[0].context_uri.as_deref(), Some("spotify:playlist:abc"));
        assert!(!entries[0].is_active);
        assert!(mobile_feed_only(b"not-json", &buddy).is_empty());
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
