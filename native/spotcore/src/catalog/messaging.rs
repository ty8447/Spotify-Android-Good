//! Conversation hierarchy read APIs and single-attempt text submission.
//!
//! Internal field numbers are based on the client request/response schemas.
//! Latest-message text, share types, sender identity and event times are parsed
//! only from verified protobuf fields. The response is returned to the signed-in
//! user; message identifiers and bodies are never written to logs.

use super::http::{self, PROTOBUF};
use ::http::Method;
use crate::engine;
use crate::error::{AppError, AppResult};
use crate::rpc::to_value;
use serde::Serialize;
use serde_json::Value;
use std::collections::HashSet;
use super::user;

const LATEST_PATH: &str = "/speechless/v1/retrieve/chats-share-set/latest";
const HISTORY_PATH: &str = "/speechless/v1/retrieve/hierarchy";
const SHARES_PATH: &str = "/speechless/v1/retrieve/hierarchy/shares";
const SUBMIT_PATH: &str = "/speechless/v1/submit/request";
const MUTE_PATH: &str = "/speechless/v1/hierarchy/settings/mute";
const UNMUTE_PATH: &str = "/speechless/v1/hierarchy/settings/unmute";
const CHAT_SETTINGS_PATH: &str = "/speechless/v1/hierarchy/settings";
const DELETE_CHAT_PATH: &str = "/speechless/v1/hierarchy/delete";
const CREATE_GROUP_PATH: &str = "/speechless/v1/group/create";
const LEAVE_GROUP_PATH: &str = "/speechless/v1/group/leave";
const HISTORY_PAGE_SIZE: u64 = 30;
const MAX_HISTORY_NODES: usize = 150;

const MAX_REPLY_BYTES: usize = 4 * 1024 * 1024;
const MAX_NODES: usize = 100;
const MAX_MEMBERS: usize = 100;
const MAX_TEXT_BYTES: usize = 32 * 1024;
const MAX_PREVIEW_CHARS: usize = 180;
const MAX_SHARE_URI_BYTES: usize = 2 * 1024;
// RetrieveLatestNodeRequest: sort_order=DESCENDING_OCCURRED_AT (1: 2),
// limit=50 (2: 50), no precondition. This is a read-only service operation.
const LATEST_REQUEST: &[u8] = &[0x08, 0x02, 0x10, 0x32];

#[derive(Default, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
struct MessageOverview {
    latest_nodes: usize,
    with_hierarchy: usize,
    with_client_node: usize,
    distinct_hierarchies: usize,
    conversations: Vec<ConversationSummary>,
}

#[derive(Default, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
struct ConversationSummary {
    /// Used only internally by the app to identify a conversation; not logged.
    hierarchy_id: String,
    /// Group title from the server; direct chat titles use resolved participants.
    group_name: Option<String>,
    is_group: bool,
    group_member_count: usize,
    pending_member_count: usize,
    // Status UNSPECIFIED(0) members beyond self; excludes opted-out/left/pending.
    // Status zero is not independent evidence of acceptance.
    active_other_member_count: usize,
    unseen_events: u32,
    /// HierarchyStatistic.totalUserUnseenContributionEvents (protobuf field 4).
    /// Unlike unseen_events, this excludes reaction/receipt activity.
    unseen_contributions: u32,
    /// Classifies the latest node, not the content of a human message.
    latest_node_kind: String,
    /// Content type, e.g. "Text message" or "Shared a song".
    latest_content_label: Option<String>,
    /// A bounded, readable excerpt of a text message, when one exists.
    latest_preview_text: Option<String>,
    /// The event sender from ClientEventCapture; never written to logs.
    latest_sender_username: Option<String>,
    /// Public-facing sender name resolved alongside other conversation members.
    latest_sender_name: Option<String>,
    /// Event time from google.protobuf.Timestamp, in Unix milliseconds.
    latest_occurred_at_ms: Option<i64>,
    /// Display title derived from the named group or other participants.
    display_title: String,
    participants: Vec<ChatParticipant>,
    /// Never serialized; contains the raw protobuf member usernames for name resolution.
    #[serde(skip_serializing)]
    member_usernames: Vec<String>,
    // GroupMember.memberUsername=1, groupMemberStatus=2. Statuses are private
    // to the serializer and only aggregated after the signed-in user is known.
    #[serde(skip_serializing)]
    member_statuses: Vec<(String, u64)>,
}

#[derive(Default, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
struct ChatParticipant {
    username: String,
    display_name: String,
    avatar_url: Option<String>,
}

/// One read-only history page. A response may contain client nodes with child
/// nodes; these are flattened without changing server-side read state.
#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
struct MessageHistory {
    hierarchy_id: String,
    group_name: Option<String>,
    group_member_count: usize,
    pending_member_count: usize,
    active_other_member_count: usize,
    participants: Vec<ChatParticipant>,
    messages: Vec<HistoryEntry>,
    next_page_token: Option<String>,
    previous_page_token: Option<String>,
}

#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
struct HistoryEntry {
    id: String,
    kind: String,
    sender_username: Option<String>,
    sender_name: Option<String>,
    sender_avatar_url: Option<String>,
    occurred_at_ms: Option<i64>,
    content_label: Option<String>,
    text: Option<String>,
    share_uri: Option<String>,
    gif_id: Option<String>,
    sticker_id: Option<String>,
    // Present only when the activity is a child of a contribution in the hierarchy.
    parent_node_id: Option<String>,
    activity_type: Option<String>,
    activity_initiator: Option<String>,
    receipt_type: Option<String>,
    reaction_emoji: Option<String>,
    system_event_type: Option<String>,
    share_origin: Option<String>,
    is_mine: bool,
}

/// Resolve public avatars for a small set of New Message picker suggestions.
/// The buddy-list presence feed contains usernames but not profile images.
/// This RPC reuses the public-profile resolver already used by the inbox.
pub(super) async fn recipient_profiles(args: Value) -> AppResult<Value> {
    let usernames = args.get("usernames").and_then(Value::as_array)
        .ok_or_else(|| AppError::unavailable("Missing usernames"))?;
    if usernames.len() > 30 { return Err(AppError::unavailable("Too many profile lookups")); }
    let session = engine::session()?;
    let mut names = Vec::<String>::new();
    let mut seen = HashSet::<String>::new();
    for value in usernames {
        let name = value.as_str().ok_or_else(|| AppError::unavailable("Invalid username"))?;
        if name.is_empty() || name.len() > 128 || name.chars().any(char::is_control) {
            return Err(AppError::unavailable("Invalid username"));
        }
        if seen.insert(name.to_owned()) { names.push(name.to_owned()); }
    }
    user::resolve_names(&session, names.iter().map(String::as_str)).await;
    let profiles: Vec<ChatParticipant> = names.into_iter().map(|username| {
        let profile = user::cached_user(&username);
        ChatParticipant {
            display_name: profile.display_name.filter(|n| !n.trim().is_empty())
                .unwrap_or_else(|| username.clone()),
            avatar_url: profile.images.iter().map(|i| i.url.clone())
                .find(|url| url.starts_with("https://")),
            username,
        }
    }).collect();
    to_value(&profiles)
}

/// Retrieve the history page for one hierarchy. This path is read-only: never
/// invoke submit/request, receipt, or hierarchy/settings from here.
pub(super) async fn history(args: Value) -> AppResult<Value> {
    let id = args.get("hierarchyId").and_then(Value::as_str)
        .filter(|v| !v.is_empty() && v.len() <= 128 && !v.chars().any(char::is_control))
        .ok_or_else(|| AppError::unavailable("Missing or invalid conversation identifier"))?;
    let token = args.get("pageToken").and_then(Value::as_str).unwrap_or("");
    if token.len() > 2048 || token.chars().any(char::is_control) {
        return Err(AppError::unavailable("Invalid history pagination token"));
    }
    // RetrieveNodeRequest: hierarchyId (1), descending sortOrder (5),
    // optional pageToken (8), pageSize (9). All fields verified in APK model.
    let mut body = Vec::with_capacity(id.len() + token.len() + 16);
    append_pb_string(&mut body, 1, id);
    body.extend_from_slice(&[0x28, 0x02]);
    if !token.is_empty() { append_pb_string(&mut body, 8, token); }
    body.extend_from_slice(&[0x48, HISTORY_PAGE_SIZE as u8]);
    let session = engine::session()?;
    let response = http::spc_post_idempotent(&session, HISTORY_PATH, PROTOBUF, PROTOBUF, &body)
        .await.map_err(|err| {
            let status = err.status.map(|v| format!("HTTP {v}"))
                .unwrap_or_else(|| "network or authorization failure".to_string());
            AppError::unavailable(format!("Conversation history unavailable: {status}"))
        })?;
    if response.len() > MAX_REPLY_BYTES {
        return Err(AppError::unavailable("Conversation history response exceeded safe limit"));
    }
    let mut result = decode_history(&response, id)?;
    // Name resolution is limited to users present in the requested conversation.
    let self_username = engine::username().unwrap_or_else(|| session.username());
    let mut names: HashSet<String> = result.participants.iter()
        .map(|p| p.username.clone()).filter(|u| u != &self_username).collect();
    names.extend(result.messages.iter().filter_map(|m| m.sender_username.clone())
        .filter(|u| u != &self_username));
    names.extend(result.messages.iter().filter_map(|m| m.activity_initiator.clone())
        .filter(|u| u != &self_username));
    user::resolve_names(&session, names.iter().map(String::as_str)).await;
    for participant in &mut result.participants {
        let profile = user::cached_user(&participant.username);
        let resolved = profile.display_name.filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| participant.username.clone());
        participant.display_name = if participant.username == self_username {
            format!("{resolved} (you)")
        } else { resolved };
        participant.avatar_url = profile.images.first().map(|i| i.url.clone())
            .filter(|url| url.starts_with("https://"));
    }
    sort_participants_for_display(&mut result.participants);
    for message in &mut result.messages {
        message.is_mine = message.sender_username.as_deref() == Some(self_username.as_str())
            || (message.kind == "activity" && message.activity_initiator.as_deref() == Some(self_username.as_str()));
        message.sender_avatar_url = message.sender_username.as_deref().and_then(|username| {
            result.participants.iter().find(|p| p.username == username)
                .and_then(|p| p.avatar_url.clone())
        });
        message.sender_name = message.sender_username.as_deref().map(|username| {
            if username == self_username { "You".to_owned() }
            else {
                result.participants.iter().find(|p| p.username == username)
                    .map(|p| p.display_name.clone())
                    .unwrap_or_else(|| user::cached_user(username).display_name
                        .filter(|s| !s.is_empty()).unwrap_or_else(|| username.to_owned()))
            }
        });
    }
    // Aggregate diagnostic only: never log message text, node IDs, sticker IDs,
    // participant names, or raw protobuf contents. This distinguishes a missing
    // Campfire sticker ID from a subsequent failed artwork-details lookup.
    let sticker_labels = result.messages.iter().filter(|m| {
        m.content_label.as_deref().is_some_and(|label|
            label.to_ascii_lowercase().contains("sticker"))
    }).count();
    let sticker_ids = result.messages.iter().filter(|m| m.sticker_id.is_some()).count();
    let sticker_labels_without_id = result.messages.iter().filter(|m| {
        m.sticker_id.is_none() && m.content_label.as_deref().is_some_and(|label|
            label.to_ascii_lowercase().contains("sticker"))
    }).count();
    // A provider may supply the word "Sticker" as plain message text, not a
    // Campfire rich-media contribution. Count separately; never infer an ID.
    let sticker_text_fallbacks = result.messages.iter().filter(|m| {
        m.kind == "contribution" && m.sticker_id.is_none() &&
            m.text.as_deref().is_some_and(|text| text.eq_ignore_ascii_case("sticker"))
    }).count();
    log::info!(
        "SpotifyGoodLyricStickers: history pageNodes={} stickerLabels={} stickerIds={} missingStickerIds={} textPlaceholders={}",
        result.messages.len(), sticker_labels, sticker_ids,
        sticker_labels_without_id, sticker_text_fallbacks,
    );
    to_value(&result)
}

/// Dedicated shares archive, separately paginated from the conversation timeline.
/// This endpoint is read-only and never generates seen/played/saved receipts.
pub(super) async fn shares(args: Value) -> AppResult<Value> {
    let id = chat_hierarchy_id(&args)?;
    let token = args.get("pageToken").and_then(Value::as_str).unwrap_or("");
    if token.len() > 2048 || token.chars().any(char::is_control) {
        return Err(AppError::unavailable("Invalid shared-content pagination token"));
    }
    let mut body = Vec::with_capacity(id.len() + token.len() + 16);
    append_pb_string(&mut body, 1, id);
    // RetrieveNodeRequest.sortOrder=5, pageToken=8, pageSize=9.
    body.extend_from_slice(&[0x28, 0x02]);
    if !token.is_empty() { append_pb_string(&mut body, 8, token); }
    body.extend_from_slice(&[0x48, 50]);
    let session = engine::session()?;
    let response = http::spc_post_idempotent(&session, SHARES_PATH, PROTOBUF, PROTOBUF, &body)
        .await.map_err(|e| {
            let status = e.status.map(|s| format!("HTTP {s}"))
                .unwrap_or_else(|| "network or authorization failure".to_owned());
            AppError::unavailable(format!("Shares archive unavailable: {status}"))
        })?;
    if response.len() > MAX_REPLY_BYTES {
        return Err(AppError::unavailable("Shares archive response exceeded safe size limit"));
    }
    let mut page = decode_history(&response, id)?;
    page.messages.retain(|m| m.share_uri.is_some());
    let self_username = engine::username().unwrap_or_else(|| session.username());
    let names: HashSet<String> = page.messages.iter().filter_map(|m| m.sender_username.clone())
        .filter(|n| n != &self_username).collect();
    user::resolve_names(&session, names.iter().map(String::as_str)).await;
    for message in &mut page.messages {
        message.is_mine = message.sender_username.as_deref() == Some(self_username.as_str());
        if let Some(name) = message.sender_username.as_deref() {
            let public = user::cached_user(name);
            message.sender_name = Some(if name == self_username { "You".to_owned() }
                else { public.display_name.unwrap_or_else(|| name.to_owned()) });
            message.sender_avatar_url = public.images.first().map(|i| i.url.clone())
                .filter(|u| u.starts_with("https://"));
        }
    }
    to_value(&page)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct MessageSubmission {
    acknowledged: bool,
    client_node_id: Option<String>,
}

/// Send exactly once, only on an explicit composer action. A successful HTTP
/// response without a matching returned node is *not* treated as confirmed.
/// In particular we must not retry automatically after a timeout/unknown outcome.
pub(super) async fn send_text(args: Value) -> AppResult<Value> {
    let id = chat_hierarchy_id(&args)?;
    let message = args.get("text").and_then(Value::as_str)
        .ok_or_else(|| AppError::unavailable("Message text is required"))?;
    let message = message.trim();
    if message.is_empty() || message.len() > 4096 || message.chars().any(|ch| ch == '\0') {
        return Err(AppError::unavailable("Message must contain 1–4096 UTF-8 bytes"));
    }
    // Protobuf schemas verified from the matching APK:
    // ClientNodeRequest.nodeRequest=1 (repeated), NodeRequest.messageRequest=2,
    // MessageRequest.addContribution=1, AddContribution.parentNodePathId=1,
    // AddContribution.payload=2, ContributionPayload.freeText=1,
    // FreeText.messageText=1.
    let mut free_text = Vec::new();
    append_pb_string(&mut free_text, 1, message);
    let mut contribution_payload = Vec::new();
    append_pb_message(&mut contribution_payload, 1, &free_text);
    let mut add_contribution = Vec::new();
    append_pb_string(&mut add_contribution, 1, id);
    append_pb_message(&mut add_contribution, 2, &contribution_payload);
    let mut message_request = Vec::new();
    append_pb_message(&mut message_request, 1, &add_contribution);
    let mut node_request = Vec::new();
    append_pb_message(&mut node_request, 2, &message_request);
    let mut client_request = Vec::new();
    append_pb_message(&mut client_request, 1, &node_request);
    let session = engine::session()?;
    let submitted_at_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64).unwrap_or(0);
    let reply = http::spc_send_once(&session, Method::POST, SUBMIT_PATH, PROTOBUF,
        PROTOBUF, client_request).await.map_err(|e| {
        let status = e.status.map(|s| format!("HTTP {s}"))
            .unwrap_or_else(|| "network or authorization failure".to_owned());
        AppError::unavailable(format!("Message submission not confirmed: {status}. Check the chat before retrying."))
    })?;
    if reply.len() > MAX_REPLY_BYTES {
        return Err(AppError::unavailable("Message acknowledgement exceeded safe size limit"));
    }
    let mut confirmed_id = None;
    let mut returned_hierarchy_id: Option<String> = None;
    // ClientNodeResponse.clientNode (repeated field 2). A match to the
    // transmitted body prevents a bare HTTP 200 from being shown as success.
    visit_fields(&reply, |tag, value| {
        if tag == 1 {
            let hierarchy = decode_hierarchy(expect_bytes(value)?)?;
            if !hierarchy.hierarchy_id.is_empty() {
                returned_hierarchy_id = Some(hierarchy.hierarchy_id);
            }
            return Ok(());
        }
        if tag != 2 { return Ok(()); }
        let node = expect_bytes(value)?;
        if decode_node_full_text(node)?.as_deref() != Some(message) { return Ok(()); }
        visit_fields(node, |field, value| {
            if field == 1 {
                visit_fields(expect_bytes(value)?, |sub, value| {
                    if sub == 1 {
                        let id = parse_string(expect_bytes(value)?, 256)?;
                        if !id.is_empty() { confirmed_id = Some(id); }
                    }
                    Ok(())
                })?;
            }
            Ok(())
        })?;
        Ok(())
    })?;
    if returned_hierarchy_id.as_deref().is_some_and(|returned| returned != id) {
        return Err(AppError::unavailable("Submission acknowledgement was for a different conversation"));
    }
    // Some successful responses contain only a hierarchy acknowledgement and
    // no echoed text node. Verify those with bounded read-only history polls.
    // Never retry the POST: that could send the user's message twice.
    if confirmed_id.is_none() {
        let self_username = engine::username().unwrap_or_else(|| session.username());
        let mut request = Vec::new();
        append_pb_string(&mut request, 1, id);
        request.extend_from_slice(&[0x28, 0x02, 0x48, 20]);
        for _ in 0..3 {
            tokio::time::sleep(std::time::Duration::from_millis(550)).await;
            let Ok(bytes) = http::spc_post_idempotent(&session, HISTORY_PATH,
                PROTOBUF, PROTOBUF, &request).await else { continue };
            if bytes.len() > MAX_REPLY_BYTES { break; }
            let Ok(page) = decode_history(&bytes, id) else { continue };
            if let Some(found) = page.messages.iter().find(|node| {
                node.kind == "contribution" && node.text.as_deref() == Some(message)
                    && node.sender_username.as_deref() == Some(self_username.as_str())
                    && node.occurred_at_ms.is_some_and(|at| at >= submitted_at_ms - 3_000)
            }) {
                confirmed_id = Some(found.id.clone());
                break;
            }
        }
    }
    to_value(&MessageSubmission { acknowledged: confirmed_id.is_some(), client_node_id: confirmed_id })
}

/// Native in-app Spotify share, using the same verified AddContribution envelope
/// as text but with ContributionPayload.trackedShare=2 / TrackedShare.uri=1.
/// The write is sent ONCE; on an ambiguous result, never retry the POST.
pub(super) async fn send_share(args: Value) -> AppResult<Value> {
    let hierarchy_id = chat_hierarchy_id(&args)?;
    let uri = args.get("uri").and_then(Value::as_str)
        .ok_or_else(|| AppError::unavailable("Spotify music URI is required"))?;
    validate_share_uri(uri)?;

    let request = encode_share_request(hierarchy_id, uri);
    let session = engine::session()?;
    let submitted_at_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64).unwrap_or(0);
    let reply = http::spc_send_once(&session, Method::POST, SUBMIT_PATH, PROTOBUF,
        PROTOBUF, request).await.map_err(|e| {
        let status = e.status.map(|s| format!("HTTP {s}"))
            .unwrap_or_else(|| "network or authorization failure".to_owned());
        AppError::unavailable(format!(
            "Music share not confirmed: {status}. Check the chat before retrying."))
    })?;
    if reply.len() > MAX_REPLY_BYTES {
        return Err(AppError::unavailable("Music share acknowledgement exceeded safe size limit"));
    }

    let mut confirmed_id: Option<String> = None;
    let mut returned_hierarchy_id: Option<String> = None;
    visit_fields(&reply, |tag, value| {
        if tag == 1 {
            let hierarchy = decode_hierarchy(expect_bytes(value)?)?;
            if !hierarchy.hierarchy_id.is_empty() {
                returned_hierarchy_id = Some(hierarchy.hierarchy_id);
            }
            return Ok(());
        }
        if tag != 2 { return Ok(()); }
        let node = expect_bytes(value)?;
        // A share sent off-platform is not the native share we submitted.
        if decode_node_share_origin(node)?.as_deref() != Some("in_app") ||
            decode_node_share_uri(node)?.as_deref() != Some(uri) { return Ok(()); }
        visit_fields(node, |field, value| {
            if field == 1 {
                visit_fields(expect_bytes(value)?, |sub, value| {
                    if sub == 1 {
                        let id = parse_string(expect_bytes(value)?, 256)?;
                        if !id.is_empty() { confirmed_id = Some(id); }
                    }
                    Ok(())
                })?;
            }
            Ok(())
        })?;
        Ok(())
    })?;
    if returned_hierarchy_id.as_deref()
        .is_some_and(|returned| returned != hierarchy_id) {
        return Err(AppError::unavailable(
            "Music share acknowledgement was for another conversation"));
    }

    // Some successful responses acknowledge only the hierarchy. The read-back
    // must match URI, sender, and time; do not infer success from HTTP 200 alone.
    if confirmed_id.is_none() {
        let self_username = engine::username().unwrap_or_else(|| session.username());
        let mut request = Vec::new();
        append_pb_string(&mut request, 1, hierarchy_id);
        request.extend_from_slice(&[0x28, 0x02, 0x48, 20]);
        for _ in 0..3 {
            tokio::time::sleep(std::time::Duration::from_millis(550)).await;
            let Ok(bytes) = http::spc_post_idempotent(&session, HISTORY_PATH,
                PROTOBUF, PROTOBUF, &request).await else { continue };
            if bytes.len() > MAX_REPLY_BYTES { break; }
            let Ok(page) = decode_history(&bytes, hierarchy_id) else { continue };
            if let Some(found) = page.messages.iter().find(|node| {
                node.kind == "contribution" && node.share_uri.as_deref() == Some(uri)
                    && node.share_origin.as_deref() == Some("in_app")
                    && node.sender_username.as_deref() == Some(self_username.as_str())
                    && node.occurred_at_ms.is_some_and(|at| at >= submitted_at_ms - 3_000)
            }) {
                confirmed_id = Some(found.id.clone());
                break;
            }
        }
    }
    to_value(&MessageSubmission {
        acknowledged: confirmed_id.is_some(), client_node_id: confirmed_id,
    })
}

/// Only native, canonical, user-selected Spotify collection/track identifiers.
/// Other URI kinds and web links remain unsupported until their wire schemas
/// and action flows are verified. This also excludes embedded control data.
fn validate_share_uri(uri: &str) -> AppResult<()> {
    let parts: Vec<&str> = uri.split(':').collect();
    if parts.len() != 3 || parts[0] != "spotify" ||
        !matches!(parts[1], "track" | "album" | "playlist") ||
        parts[2].len() != 22 || !parts[2].bytes().all(|b| b.is_ascii_alphanumeric()) {
        return Err(AppError::unavailable("Unsupported Spotify share URI"));
    }
    Ok(())
}

fn encode_share_request(hierarchy_id: &str, uri: &str) -> Vec<u8> {
    let mut tracked_share = Vec::new();
    append_pb_string(&mut tracked_share, 1, uri);
    let mut contribution_payload = Vec::new();
    append_pb_message(&mut contribution_payload, 2, &tracked_share);
    let mut add_contribution = Vec::new();
    append_pb_string(&mut add_contribution, 1, hierarchy_id);
    append_pb_message(&mut add_contribution, 2, &contribution_payload);
    let mut message_request = Vec::new();
    append_pb_message(&mut message_request, 1, &add_contribution);
    let mut node_request = Vec::new();
    append_pb_message(&mut node_request, 2, &message_request);
    let mut client_request = Vec::new();
    append_pb_message(&mut client_request, 1, &node_request);
    client_request
}


/// Campfire ContributionPayload.richMedia=6. RichMedia.giphyGif=1 / sticker=2.
/// IDs are opaque provider identifiers, not arbitrary media URLs.
fn decode_rich_media(bytes: &[u8]) -> AppResult<Option<(String, String)>> {
    let mut result = None;
    visit_fields(bytes, |tag, field| {
        let kind = match tag { 1 => "gif", 2 => "sticker", _ => return Ok(()) };
        visit_fields(expect_bytes(field)?, |nested_tag, nested_value| {
            if nested_tag == 1 {
                let id = parse_string(expect_bytes(nested_value)?, 256)?;
                if !id.is_empty() && !id.chars().any(char::is_control) {
                    result = Some((kind.to_owned(), id));
                }
            }
            Ok(())
        })
    })?;
    Ok(result)
}

fn decode_node_rich_media(bytes: &[u8]) -> AppResult<Option<(String, String)>> {
    let mut result = None;
    visit_fields(bytes, |tag, field| {
        if tag != 8 { return Ok(()); }
        visit_fields(expect_bytes(field)?, |payload_tag, payload| {
            if payload_tag != 1 { return Ok(()); }
            visit_fields(expect_bytes(payload)?, |variant, contribution| {
                if variant == 6 { result = decode_rich_media(expect_bytes(contribution)?)?; }
                Ok(())
            })
        })
    })?;
    Ok(result)
}

/// GIF/sticker submit envelope from the APK's RichMedia model; the caller must
/// supply a provider-selected ID (and verified GIF rating) in a later UI layer.
fn encode_rich_media_request(hierarchy_id: &str, kind: &str, media_id: &str, rating: u64) -> Vec<u8> {
    let mut provider_media = Vec::new();
    append_pb_string(&mut provider_media, 1, media_id);
    if kind == "gif" {
        provider_media.push(0x10); // GiphyGif.ageRating = field 2
        append_pb_varint(&mut provider_media, rating);
    }
    let mut rich_media = Vec::new();
    append_pb_message(&mut rich_media, if kind == "gif" { 1 } else { 2 }, &provider_media);
    let mut contribution_payload = Vec::new();
    append_pb_message(&mut contribution_payload, 6, &rich_media);
    let mut add_contribution = Vec::new();
    append_pb_string(&mut add_contribution, 1, hierarchy_id);
    append_pb_message(&mut add_contribution, 2, &contribution_payload);
    let mut message_request = Vec::new();
    append_pb_message(&mut message_request, 1, &add_contribution);
    let mut node_request = Vec::new();
    append_pb_message(&mut node_request, 2, &message_request);
    let mut client_request = Vec::new();
    append_pb_message(&mut client_request, 1, &node_request);
    client_request
}

/// Native rich-media submit. No automatic retry after a potentially delivered POST.
/// A successful HTTP response alone is not an acknowledgement.
pub(super) async fn send_rich_media(args: Value) -> AppResult<Value> {
    let hierarchy_id = chat_hierarchy_id(&args)?;
    let kind = args.get("kind").and_then(Value::as_str)
        .filter(|k| matches!(*k, "gif" | "sticker"))
        .ok_or_else(|| AppError::unavailable("Unsupported rich-media attachment type"))?;
    let media_id = args.get("mediaId").and_then(Value::as_str)
        .filter(|s| !s.is_empty() && s.len() <= 128 &&
            s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_'))
        .ok_or_else(|| AppError::unavailable("Invalid provider media identifier"))?;
    // GIPHY age rating from APK enum: 0=unspecified, 1=Y, 2=G, 3=PG,
    // 4=PG13, 5=R. Do not fabricate a rating for an ID without provider metadata.
    let rating = if kind == "gif" {
        args.get("ageRating").and_then(Value::as_u64).filter(|v| *v <= 5)
            .ok_or_else(|| AppError::unavailable("A provider-verified GIF age rating is required"))?
    } else { 0 };
    let request = encode_rich_media_request(hierarchy_id, kind, media_id, rating);
    let session = engine::session()?;
    let submitted_at_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64).unwrap_or(0);
    let reply = http::spc_send_once(&session, Method::POST, SUBMIT_PATH,
        PROTOBUF, PROTOBUF, request).await.map_err(|e| {
        let status = e.status.map(|s| format!("HTTP {s}"))
            .unwrap_or_else(|| "network or authorization failure".to_owned());
        AppError::unavailable(format!("Rich-media delivery unconfirmed: {status}. Check the chat before retrying."))
    })?;
    if reply.len() > MAX_REPLY_BYTES {
        return Err(AppError::unavailable("Rich-media acknowledgement exceeded safe limit"));
    }
    let mut confirmed_id: Option<String> = None;
    let mut returned_hierarchy_id: Option<String> = None;
    visit_fields(&reply, |tag, value| {
        if tag == 1 {
            let hierarchy = decode_hierarchy(expect_bytes(value)?)?;
            if !hierarchy.hierarchy_id.is_empty() {
                returned_hierarchy_id = Some(hierarchy.hierarchy_id);
            }
        } else if tag == 2 {
            let node = expect_bytes(value)?;
            if decode_node_rich_media(node)?.as_ref().is_some_and(|(k, id)|
                k == kind && id == media_id) {
                visit_fields(node, |node_tag, nested| {
                    if node_tag == 1 {
                        visit_fields(expect_bytes(nested)?, |id_tag, path| {
                            if id_tag == 1 {
                                let id = parse_string(expect_bytes(path)?, 256)?;
                                if !id.is_empty() { confirmed_id = Some(id); }
                            }
                            Ok(())
                        })?;
                    }
                    Ok(())
                })?;
            }
        }
        Ok(())
    })?;
    if returned_hierarchy_id.as_deref()
        .is_some_and(|returned| returned != hierarchy_id) {
        return Err(AppError::unavailable("Rich-media acknowledgement belonged to another conversation"));
    }
    if confirmed_id.is_none() {
        let self_username = engine::username().unwrap_or_else(|| session.username());
        let mut request = Vec::new();
        append_pb_string(&mut request, 1, hierarchy_id);
        request.extend_from_slice(&[0x28, 0x02, 0x48, 20]);
        for _ in 0..3 {
            tokio::time::sleep(std::time::Duration::from_millis(550)).await;
            let Ok(bytes) = http::spc_post_idempotent(&session, HISTORY_PATH,
                PROTOBUF, PROTOBUF, &request).await else { continue };
            if bytes.len() > MAX_REPLY_BYTES { break; }
            let Ok(page) = decode_history(&bytes, hierarchy_id) else { continue };
            if let Some(found) = page.messages.iter().find(|node| {
                node.kind == "contribution" &&
                    (if kind == "gif" { node.gif_id.as_deref() } else { node.sticker_id.as_deref() }) == Some(media_id)
                    && node.sender_username.as_deref() == Some(self_username.as_str())
                    && node.occurred_at_ms.is_some_and(|at| at >= submitted_at_ms - 3_000)
            }) {
                confirmed_id = Some(found.id.clone());
                break;
            }
        }
    }
    to_value(&MessageSubmission { acknowledged: confirmed_id.is_some(), client_node_id: confirmed_id })
}

/// Server mutation for a selected tapback. React and unreact are deliberately
/// distinct explicit actions. A POST is never retried on uncertain delivery.
/// The response is not assumed to prove mutation: verify via read-only history.
pub(super) async fn set_reaction(args: Value) -> AppResult<Value> {
    let hierarchy_id = chat_hierarchy_id(&args)?;
    let parent_id = args.get("nodePathId").and_then(Value::as_str)
        .filter(|v| !v.is_empty() && v.len() <= 256 && !v.chars().any(char::is_control))
        .ok_or_else(|| AppError::unavailable("Invalid message identifier"))?;
    let emoji = args.get("emoji").and_then(Value::as_str)
        .ok_or_else(|| AppError::unavailable("Reaction is required"))?;
    // Preset tapbacks use ReactionEmoti (ActivityRequest field 1). The
    // official extended picker uses ActivityRequest.emojiReaction (field 4),
    // whose EmojiReaction.emojiUtf8 is field 1. Neither is free-form text.
    let enum_value = reaction_emoti(emoji);
    if enum_value.is_none() && !valid_emoji_reaction(emoji) {
        return Err(AppError::unavailable("Invalid emoji reaction"));
    }
    let existing_activity = args.get("removeActivityId").and_then(Value::as_str);
    if existing_activity.is_some_and(|v| v.is_empty() || v.len() > 256 || v.chars().any(char::is_control)) {
        return Err(AppError::unavailable("Invalid reaction activity identifier"));
    }
    let request = encode_reaction_request(parent_id, enum_value, emoji, existing_activity);
    let session = engine::session()?;
    let self_username = engine::username().unwrap_or_else(|| session.username());
    // Never auto-retry non-idempotent reactions; an error could mean the server
    // applied it but the network lost the response.
    let response = http::spc_send_once(&session, Method::POST, SUBMIT_PATH, PROTOBUF,
        PROTOBUF, request).await.map_err(|error| {
        let status = error.status.map(|s| format!("HTTP {s}"))
            .unwrap_or_else(|| "network or authorization error".to_owned());
        AppError::unavailable(format!("Tapback outcome unknown: {status}. Refresh before retrying."))
    })?;
    if response.len() > MAX_REPLY_BYTES {
        return Err(AppError::unavailable("Tapback reply exceeds safe size limit"));
    }
    // A mismatched hierarchy must never turn into a success report.
    visit_fields(&response, |tag, field| {
        if tag == 1 {
            let returned = decode_hierarchy(expect_bytes(field)?)?.hierarchy_id;
            if !returned.is_empty() && returned != hierarchy_id { return Err(wire_error()); }
        }
        Ok(())
    })?;
    let mut confirmed = false;
    let mut history_request = Vec::new();
    append_pb_string(&mut history_request, 1, hierarchy_id);
    history_request.extend_from_slice(&[0x28, 0x02, 0x48, 30]);
    for _ in 0..3 {
        tokio::time::sleep(std::time::Duration::from_millis(650)).await;
        let Ok(bytes) = http::spc_post_idempotent(&session, HISTORY_PATH,
            PROTOBUF, PROTOBUF, &history_request).await else { continue };
        if bytes.len() > MAX_REPLY_BYTES { break; }
        let Ok(page) = decode_history(&bytes, hierarchy_id) else { continue };
        // If the target is not in the current history page, absence does not
        // prove deletion. Tell the caller confirmation is unavailable instead.
        if !page.messages.iter().any(|entry| entry.id == parent_id) { continue; }
        if let Some(activity_id) = existing_activity {
            confirmed = !page.messages.iter().any(|entry| entry.id == activity_id);
        } else {
            confirmed = page.messages.iter().any(|entry| {
                entry.kind == "activity" && entry.activity_type.as_deref() == Some("reaction")
                    && entry.parent_node_id.as_deref() == Some(parent_id)
                    && entry.activity_initiator.as_deref() == Some(self_username.as_str())
                    && entry.reaction_emoji.as_deref() == Some(emoji)
            });
        }
        if confirmed { break; }
    }
    to_value(&serde_json::json!({ "acknowledged": confirmed }))
}

fn reaction_emoti(emoji: &str) -> Option<u64> {
    match emoji {
        "👍" => Some(1), "❤️" => Some(2), "😂" => Some(3),
        "🥺" => Some(7), "👎" => Some(8), "🔥" => Some(9),
        "🎧" => Some(10), _ => None,
    }
}

/// ClientNodeRequest.nodeRequest=1 -> NodeRequest.messageRequest=2 ->
/// MessageRequest.addActivity=4 / deleteActivity=5.
/// AddActivity.parentNodePathId=1, request=2; DeleteActivity.nodePathId=1,
/// request=2. ActivityRequest.reactionEmoti=1 (ReactionEmoti enum).
fn valid_emoji_reaction(emoji: &str) -> bool {
    !emoji.is_empty() && emoji.len() <= 64 && emoji.chars().count() <= 16
        && emoji.chars().any(|c| c as u32 >= 0x00a9)
        && !emoji.chars().any(|c| c.is_whitespace() || c.is_control())
}

fn encode_reaction_request(parent_id: &str, reaction: Option<u64>, emoji: &str,
    remove: Option<&str>) -> Vec<u8> {
    let mut activity = Vec::new();
    if let Some(reaction) = reaction {
        activity.push(0x08); // ActivityRequest.reactionEmoti = field 1 / varint
        append_pb_varint(&mut activity, reaction);
    } else {
        let mut utf8_emoji = Vec::new();
        append_pb_string(&mut utf8_emoji, 1, emoji); // EmojiReaction.emojiUtf8
        append_pb_message(&mut activity, 4, &utf8_emoji);
    }
    let mut target = Vec::new();
    append_pb_string(&mut target, 1, remove.unwrap_or(parent_id));
    append_pb_message(&mut target, 2, &activity);
    let mut message = Vec::new();
    append_pb_message(&mut message, if remove.is_some() { 5 } else { 4 }, &target);
    let mut node = Vec::new();
    append_pb_message(&mut node, 2, &message);
    let mut client = Vec::new();
    append_pb_message(&mut client, 1, &node);
    client
}

/// Deletes an individual contribution, not the entire conversation.
/// Verified APK: MessageRequest.deleteContribution=3,
/// DeleteContribution.nodePathId=1, deleteForAll=2 (boolean).
/// A successful transport response means submitted, not necessarily removed;
/// the app must refetch to observe the server's authoritative state.
pub(super) async fn delete_contribution(args: Value) -> AppResult<Value> {
    let hierarchy_id = chat_hierarchy_id(&args)?;
    let node_id = args.get("nodePathId").and_then(Value::as_str)
        .filter(|s| !s.is_empty() && s.len() <= 256 && !s.chars().any(char::is_control))
        .ok_or_else(|| AppError::unavailable("Invalid message identifier"))?;
    let everyone = args.get("deleteForAll").and_then(Value::as_bool)
        .ok_or_else(|| AppError::unavailable("Deletion scope is required"))?;
    let session = engine::session()?;
    // A Delete for everyone must never be sent solely on a client-side
    // isMine boolean. Confirm sender ownership in bounded authoritative history.
    if everyone {
        let username = engine::username().unwrap_or_else(|| session.username());
        let mut token = String::new();
        let mut verified_owner = false;
        for _ in 0..12 {
            let mut request = Vec::new();
            append_pb_string(&mut request, 1, hierarchy_id);
            request.extend_from_slice(&[0x28, 0x02]); // descending by occurredAt
            if !token.is_empty() { append_pb_string(&mut request, 8, &token); }
            request.extend_from_slice(&[0x48, 30]);
            let bytes = http::spc_post_idempotent(&session, HISTORY_PATH,
                PROTOBUF, PROTOBUF, &request).await.map_err(|_| {
                AppError::unavailable("Cannot verify message ownership; nothing was deleted")
            })?;
            if bytes.len() > MAX_REPLY_BYTES {
                return Err(AppError::unavailable("Ownership verification exceeded safe limit"));
            }
            let page = decode_history(&bytes, hierarchy_id)?;
            if let Some(entry) = page.messages.iter().find(|m| m.id == node_id) {
                if entry.kind != "contribution" || entry.sender_username.as_deref() != Some(username.as_str()) {
                    return Err(AppError::unavailable("Only your own messages can be deleted for everyone"));
                }
                verified_owner = true;
                break;
            }
            let next = page.next_page_token.unwrap_or_default();
            if next.is_empty() || next == token { break; }
            token = next;
        }
        if !verified_owner {
            return Err(AppError::unavailable(
                "Cannot verify ownership in the available message history; nothing was deleted"));
        }
    }
    let request = encode_delete_contribution(node_id, everyone);
    let response = http::spc_send_once(&session, Method::POST, SUBMIT_PATH,
        PROTOBUF, PROTOBUF, request).await.map_err(|e| {
        let status = e.status.map(|s| format!("HTTP {s}"))
            .unwrap_or_else(|| "network or authorization error".to_owned());
        AppError::unavailable(format!("Deletion outcome unknown: {status}. Refresh before retrying."))
    })?;
    if response.len() > MAX_REPLY_BYTES {
        return Err(AppError::unavailable("Deletion reply exceeds safe size limit"));
    }
    visit_fields(&response, |field, value| {
        if field == 1 {
            let id = decode_hierarchy(expect_bytes(value)?)?.hierarchy_id;
            if !id.is_empty() && id != hierarchy_id {
                return Err(AppError::unavailable("Deletion reply is for another chat"));
            }
        }
        Ok(())
    })?;
    to_value(&serde_json::json!({ "submitted": true, "confirmed": false }))
}

fn encode_delete_contribution(node_id: &str, for_all: bool) -> Vec<u8> {
    let mut remove = Vec::new();
    append_pb_string(&mut remove, 1, node_id);
    remove.extend_from_slice(&[0x10, if for_all { 1 } else { 0 }]);
    let mut message = Vec::new();
    append_pb_message(&mut message, 3, &remove);
    let mut node = Vec::new();
    append_pb_message(&mut node, 2, &message);
    let mut client = Vec::new();
    append_pb_message(&mut client, 1, &node);
    client
}

/// Match Spotify's pgc.H implementation exactly: ClientNodeRequest.nodeRequest
/// (1) -> NodeRequest.bulkMessageRequest (3) ->
/// BulkMessageRequest.addHierarchyActivity (2) ->
/// AddHierarchyActivity.hierarchyId (1), request (2) ->
/// ActivityRequest.ackType (3) = ACKTYPE_USER_SEEN (1).
fn encode_mark_seen(hierarchy_id: &str) -> Vec<u8> {
    let activity = vec![0x18, 0x01]; // ackType field 3, enum 1
    let mut hierarchy = Vec::new();
    append_pb_string(&mut hierarchy, 1, hierarchy_id);
    append_pb_message(&mut hierarchy, 2, &activity);
    let mut bulk = Vec::new();
    append_pb_message(&mut bulk, 2, &hierarchy);
    let mut node = Vec::new();
    append_pb_message(&mut node, 3, &bulk);
    let mut client = Vec::new();
    append_pb_message(&mut client, 1, &node);
    client
}

/// Mark an opened/read chat seen on Spotify itself, not only in local badge state.
/// A result is confirmed only after the server's own unread-contribution count
/// becomes zero; never retry the write if the transport response is uncertain.
pub(super) async fn mark_chat_seen(args: Value) -> AppResult<Value> {
    let hierarchy_id = chat_hierarchy_id(&args)?;
    let session = engine::session()?;
    let reply = http::spc_send_once(&session, Method::POST, SUBMIT_PATH, PROTOBUF,
        PROTOBUF, encode_mark_seen(hierarchy_id)).await.map_err(|e| {
        let status = e.status.map(|s| format!("HTTP {s}"))
            .unwrap_or_else(|| "network or authorization error".to_owned());
        AppError::unavailable(format!("Read status uncertain: {status}. Check chat before retrying."))
    })?;
    if reply.len() > MAX_REPLY_BYTES {
        return Err(AppError::unavailable("Read acknowledgement reply exceeds safe limit"));
    }
    visit_fields(&reply, |tag, field| {
        if tag == 1 {
            let id = decode_hierarchy(expect_bytes(field)?)?.hierarchy_id;
            if !id.is_empty() && id != hierarchy_id {
                return Err(AppError::unavailable("Read acknowledgement was for another chat"));
            }
        }
        Ok(())
    })?;
    for _ in 0..3 {
        tokio::time::sleep(std::time::Duration::from_millis(500)).await;
        let Ok(overview) = fetch_overview().await else { continue };
        if let Some(entry) = overview.conversations.iter().find(|c| c.hierarchy_id == hierarchy_id) {
            if entry.unseen_contributions == 0 {
                return to_value(&serde_json::json!({"acknowledged": true}));
            }
        }
    }
    to_value(&serde_json::json!({"acknowledged": false}))
}

/// Current user mute status is field 1 of GetHierarchySettingsResponse.
/// Response field 2 is a list of disabled receipt kinds, not mute state.
pub(super) async fn chat_mute_status(args: Value) -> AppResult<Value> {
    let id = chat_hierarchy_id(&args)?;
    let mut body = Vec::with_capacity(id.len() + 4);
    append_pb_string(&mut body, 1, id);
    let session = engine::session()?;
    let bytes = http::spc_post_idempotent(&session, CHAT_SETTINGS_PATH, PROTOBUF, PROTOBUF, &body)
        .await.map_err(|e| {
            let code = e.status.map(|s| format!("HTTP {s}"))
                .unwrap_or_else(|| "network or authorization error".to_owned());
            AppError::unavailable(format!("Unable to read chat settings: {code}"))
        })?;
    if bytes.len() > 16 * 1024 {
        return Err(AppError::unavailable("Chat settings response too large"));
    }
    let mut muted = false;
    visit_fields(&bytes, |field, value| {
        if field == 1 { muted = expect_varint(value)? != 0; }
        Ok(())
    })?;
    to_value(&serde_json::json!({ "muted": muted }))
}

/// DeleteChatForMeRequest.hierarchyId=1 (verified APK model).
/// This is a per-account chat removal; it does NOT remove all members' messages.
/// Never retry a write automatically after an uncertain response.
pub(super) async fn delete_chat_for_me(args: Value) -> AppResult<Value> {
    let id = chat_hierarchy_id(&args)?;
    let mut body = Vec::with_capacity(id.len() + 6);
    append_pb_string(&mut body, 1, id);
    let session = engine::session()?;
    http::spc_send_once(&session, Method::POST, DELETE_CHAT_PATH, PROTOBUF,
        PROTOBUF, body).await.map_err(|e| {
        let code = e.status.map(|s| format!("HTTP {s}"))
            .unwrap_or_else(|| "network or authorization failure".to_owned());
        AppError::unavailable(format!("Chat deletion submission uncertain: {code}. Check Messages before retrying."))
    })?;
    // HTTP accepted the request. No server-side read-back is implied.
    to_value(&serde_json::json!({ "submitted": true }))
}

/// CreateGroupRequest (official APK): memberUsername=2 (repeated), name=3,
/// createGroupOptions=4 (optional). CreateGroupResponse.hierarchy=1.
/// Never retry a POST without first checking the conversation directory.
pub(super) async fn create_group(args: Value) -> AppResult<Value> {
    let usernames = args.get("memberUsernames").and_then(Value::as_array)
        .ok_or_else(|| AppError::unavailable("At least one recipient is required"))?;
    // A two-person chat uses the same CreateGroupRequest with one other member.
    // No cardinality constraint is specified in the APK schema; server validation
    // remains authoritative. For existing member sets, the UI opens the old chat.
    if !(1..=39).contains(&usernames.len()) {
        return Err(AppError::unavailable("A chat requires 1–39 other members"));
    }
    let mut unique = HashSet::new();
    let mut members = Vec::new();
    for item in usernames {
        let username = item.as_str().filter(|s| !s.trim().is_empty() && s.len() <= 256
            && !s.chars().any(char::is_control) && !s.chars().any(char::is_whitespace))
            .ok_or_else(|| AppError::unavailable("Invalid group member username"))?;
        if !unique.insert(username.to_owned()) {
            return Err(AppError::unavailable("Duplicate group member"));
        }
        members.push(username);
    }
    let session = engine::session()?;
    let me = engine::username().unwrap_or_else(|| session.username());
    if members.contains(&me.as_str()) {
        return Err(AppError::unavailable("The signed-in user is added automatically"));
    }
    let group_name = args.get("name").and_then(Value::as_str).unwrap_or("").trim();
    if group_name.len() > 200 || group_name.chars().any(char::is_control) {
        return Err(AppError::unavailable("Invalid group name"));
    }
    let mut body = Vec::new();
    for member in members { append_pb_string(&mut body, 2, member); }
    if !group_name.is_empty() { append_pb_string(&mut body, 3, group_name); }
    let reply = http::spc_send_once(&session, Method::POST, CREATE_GROUP_PATH,
        PROTOBUF, PROTOBUF, body).await.map_err(|e| {
        let status = e.status.map(|v| format!("HTTP {v}"))
            .unwrap_or_else(|| "network/authorization failure".to_owned());
        AppError::unavailable(format!("Group creation outcome uncertain: {status}. Check Messages before retrying."))
    })?;
    if reply.len() > MAX_REPLY_BYTES {
        return Err(AppError::unavailable("Group response exceeded safe size limit"));
    }
    let mut created_id = None;
    visit_fields(&reply, |field, value| {
        if field == 1 {
            let group = decode_hierarchy(expect_bytes(value)?)?;
            if !group.hierarchy_id.is_empty() { created_id = Some(group.hierarchy_id); }
        }
        Ok(())
    })?;
    let id = created_id.ok_or_else(|| AppError::unavailable(
        "Group was submitted but a new chat ID was not returned. Check Messages before retrying."))?;
    to_value(&serde_json::json!({ "acknowledged": true, "hierarchyId": id }))
}

/// LeaveGroupRequest: hierarchyId=1, leaveMode=2. LeaveMode READ_ONLY=1
/// keeps the conversation's previous content; DELETE=2 leaves and deletes.
/// The official LeaveGroupResponse contains no acknowledgement fields.
pub(super) async fn leave_group(args: Value) -> AppResult<Value> {
    let id = chat_hierarchy_id(&args)?;
    let delete_history = args.get("deleteHistory").and_then(Value::as_bool)
        .ok_or_else(|| AppError::unavailable("Leave mode must be specified"))?;
    let session = engine::session()?;
    // Check group classification from the server before attempting an account mutation.
    let mut request = Vec::new();
    append_pb_string(&mut request, 1, id);
    request.extend_from_slice(&[0x28, 0x02, 0x48, 1]);
    let history_reply = http::spc_post_idempotent(&session, HISTORY_PATH,
        PROTOBUF, PROTOBUF, &request).await.map_err(|_| AppError::unavailable(
            "Cannot verify group membership. Leave request not submitted"))?;
    if history_reply.len() > MAX_REPLY_BYTES {
        return Err(AppError::unavailable("Group membership verification response too large"));
    }
    let known = decode_history(&history_reply, id)?;
    if known.group_member_count < 3 {
        return Err(AppError::unavailable("Only group conversations can be left"));
    }
    let mut body = Vec::new();
    append_pb_string(&mut body, 1, id);
    body.push(0x10); // LeaveGroupRequest.leaveMode = field 2, varint
    body.push(if delete_history { 2 } else { 1 });
    let response = http::spc_send_once(&session, Method::POST, LEAVE_GROUP_PATH,
        PROTOBUF, PROTOBUF, body).await.map_err(|e| {
        let status = e.status.map(|v| format!("HTTP {v}"))
            .unwrap_or_else(|| "network/authorization failure".to_owned());
        AppError::unavailable(format!("Leave request outcome uncertain: {status}. Check Messages before retrying."))
    })?;
    if response.len() > 16 * 1024 {
        return Err(AppError::unavailable("Unexpected leave-group response size"));
    }
    // HTTP acceptance only. No read-back confirmation implied.
    to_value(&serde_json::json!({ "submitted": true }))
}

fn chat_hierarchy_id(args: &Value) -> AppResult<&str> {
    args.get("hierarchyId").and_then(Value::as_str)
        .filter(|v| !v.is_empty() && v.len() <= 128 && !v.chars().any(char::is_control))
        .ok_or_else(|| AppError::unavailable("Invalid conversation identifier"))
}

/// UnmuteHierarchyRequest uses the hierarchy identifier as its first field.
/// Only the server acknowledgement changes the app's displayed mute state.
pub(super) async fn unmute_chat(args: Value) -> AppResult<Value> {
    let id = chat_hierarchy_id(&args)?;
    let mut body = Vec::with_capacity(id.len() + 4);
    append_pb_string(&mut body, 1, id);
    let session = engine::session()?;
    http::spc_send_once(&session, Method::POST, UNMUTE_PATH, PROTOBUF, PROTOBUF, body)
        .await.map_err(|e| {
            let code = e.status.map(|s| format!("HTTP {s}"))
                .unwrap_or_else(|| "network or authorization error".to_owned());
            AppError::unavailable(format!("Unable to unmute chat: {code}"))
        })?;
    to_value(&true)
}

/// MuteHierarchyRequest fields: 1=hierarchyId, 2=google.protobuf.Duration.
/// Only user-selected finite durations are accepted. "Always" is not guessed.
pub(super) async fn mute_chat(args: Value) -> AppResult<Value> {
    let id = args.get("hierarchyId").and_then(Value::as_str)
        .filter(|v| !v.is_empty() && v.len() <= 128 && !v.chars().any(char::is_control))
        .ok_or_else(|| AppError::unavailable("Invalid conversation identifier"))?;
    let seconds = args.get("durationSeconds").and_then(Value::as_u64)
        .filter(|v| matches!(*v, 3600 | 28800 | 86400 | 604800))
        .ok_or_else(|| AppError::unavailable("Unsupported mute duration"))?;
    let mut duration = vec![0x08]; // google.protobuf.Duration.seconds = tag 1, varint
    append_pb_varint(&mut duration, seconds);
    let mut body = Vec::with_capacity(id.len() + 12);
    append_pb_string(&mut body, 1, id);
    body.push(0x12); // MuteHierarchyRequest.duration = tag 2, length-delimited
    body.push(duration.len() as u8);
    body.extend_from_slice(&duration);
    let session = engine::session()?;
    http::spc_send_once(&session, Method::POST, MUTE_PATH, PROTOBUF, PROTOBUF, body)
        .await.map_err(|e| {
            let code = e.status.map(|s| format!("HTTP {s}"))
                .unwrap_or_else(|| "network or authorization error".to_owned());
            AppError::unavailable(format!("Unable to mute chat: {code}"))
        })?;
    to_value(&true)
}

fn append_pb_varint(buf: &mut Vec<u8>, mut value: u64) {
    while value >= 128 {
        buf.push((value as u8 & 127) | 128);
        value >>= 7;
    }
    buf.push(value as u8);
}

fn append_pb_string(buf: &mut Vec<u8>, tag: u8, content: &str) {
    buf.push(tag << 3 | 2);
    let mut remaining = content.len();
    while remaining >= 128 {
        buf.push((remaining as u8 & 127) | 128);
        remaining >>= 7;
    }
    buf.push(remaining as u8);
    buf.extend_from_slice(content.as_bytes());
}

fn append_pb_message(buf: &mut Vec<u8>, field: u8, bytes: &[u8]) {
    buf.push(field << 3 | 2);
    append_pb_varint(buf, bytes.len() as u64);
    buf.extend_from_slice(bytes);
}

fn decode_history(bytes: &[u8], requested_id: &str) -> AppResult<MessageHistory> {
    let mut result = MessageHistory { hierarchy_id: requested_id.to_owned(), ..Default::default() };
    let mut seen = HashSet::new();
    visit_fields(bytes, |tag, field| {
        match tag {
            1 => {
                let h = decode_hierarchy(expect_bytes(field)?)?;
                // A different hierarchy in the server response must never be displayed
                // as part of the requested chat.
                if !h.hierarchy_id.is_empty() && h.hierarchy_id != requested_id {
                    return Err(wire_error());
                }
                result.group_name = h.group_name;
                result.group_member_count = h.group_member_count;
                // Status values verified: 0=UNSPECIFIED, 1=OPTED_OUT,
                // 2=PENDING, 3=LEFT. A pending recipient has not yet accepted.
                // Treat default/unspecified as not explicitly pending; this is
                // NOT positive proof of invitation acceptance.
                let current_user = engine::username().unwrap_or_default();
                result.pending_member_count = h.member_statuses.iter()
                    .filter(|(name, status)| name != &current_user && *status == 2).count();
                result.active_other_member_count = h.member_statuses.iter()
                    .filter(|(name, status)| name != &current_user && *status == 0).count();
                result.participants = h.member_usernames.into_iter().filter(|v| !v.is_empty())
                    .map(|username| ChatParticipant {
                        display_name: username.clone(), username, avatar_url: None,
                    }).collect();
            }
            2 => collect_history_nodes(expect_bytes(field)?, &mut result.messages, &mut seen, 0, None)?,
            3 => result.next_page_token = nonempty_token(field)?,
            4 => result.previous_page_token = nonempty_token(field)?,
            _ => {}
        }
        Ok(())
    })?;
    result.messages.sort_by_key(|m| m.occurred_at_ms.unwrap_or(i64::MIN));
    Ok(result)
}

fn nonempty_token(value: WireValue<'_>) -> AppResult<Option<String>> {
    let token = parse_string(expect_bytes(value)?, 2048)?;
    Ok(if token.is_empty() { None } else { Some(token) })
}

fn collect_history_nodes(
    bytes: &[u8], out: &mut Vec<HistoryEntry>, seen: &mut HashSet<String>, depth: usize,
    parent_id: Option<&str>,
) -> AppResult<()> {
    if depth > 5 || out.len() > MAX_HISTORY_NODES { return Err(wire_error()); }
    let d = decode_client_node(bytes)?;
    let mut node_id = None;
    // ClientNode.nodeIdentifier=1; NodeIdentifier.nodePathId=1.
    visit_fields(bytes, |tag, field| {
        if tag == 1 {
            visit_fields(expect_bytes(field)?, |part, value| {
                if part == 1 { node_id = Some(parse_string(expect_bytes(value)?, 256)?); }
                Ok(())
            })?;
        }
        Ok(())
    })?;
    // Retain event nodes with a known payload, not structural containers.
    if d.content_label.is_some() || d.preview_text.is_some() {
        if out.len() >= MAX_HISTORY_NODES { return Err(wire_error()); }
        let key = node_id.clone().filter(|id| !id.is_empty())
            .unwrap_or_else(|| format!("event-{}", out.len()));
        if seen.insert(key.clone()) {
            let (activity_type, activity_initiator, receipt_type, reaction_emoji) =
                if d.kind == "activity" {
                    decode_activity_info(bytes)?
                } else { (None, None, None, None) };
            let media_attachment = decode_node_rich_media(bytes)?;
            out.push(HistoryEntry {
                id: key, kind: d.kind.clone(), sender_username: d.sender_username,
                occurred_at_ms: d.occurred_at_ms, content_label: d.content_label,
                text: decode_node_full_text(bytes)?.or(d.preview_text), share_uri: decode_node_share_uri(bytes)?,
                gif_id: media_attachment.as_ref().and_then(|(kind, id)|
                    (kind == "gif").then(|| id.clone())),
                sticker_id: media_attachment.as_ref().and_then(|(kind, id)|
                    (kind == "sticker").then(|| id.clone())),
                parent_node_id: if d.kind == "activity" { parent_id.map(str::to_owned) } else { None },
                activity_type, activity_initiator, receipt_type, reaction_emoji,
                system_event_type: decode_node_system_event(bytes)?,
                share_origin: decode_node_share_origin(bytes)?,
                ..Default::default()
            });
        }
    }
    // Preserve the real structural parent when child activity is embedded under
    // a contribution. Never guess a parent from nearby timestamps.
    let child_parent = if d.kind == "contribution" {
        node_id.as_deref().or(parent_id)
    } else { parent_id };
    visit_fields(bytes, |tag, field| {
        if tag == 3 {
            collect_history_nodes(expect_bytes(field)?, out, seen, depth + 1, child_parent)?;
        }
        Ok(())
    })?;
    Ok(())
}

/// ActivityPayload receipt types are verified enum values:
/// 1=PLAYED, 2=SAVED, 3=SEEN. The event's initiator is receipt field 2.
fn decode_activity_info(node: &[u8]) -> AppResult<(
    Option<String>, Option<String>, Option<String>, Option<String>,
)> {
    let mut category: Option<String> = None;
    let mut initiator: Option<String> = None;
    let mut receipt_type: Option<String> = None;
    let mut reaction_emoji: Option<String> = None;
    let mut enum_reaction: Option<String> = None;
    visit_fields(node, |tag, field| {
        if tag != 8 { return Ok(()); }
        visit_fields(expect_bytes(field)?, |payload_tag, payload| {
            if payload_tag != 2 { return Ok(()); }
            visit_fields(expect_bytes(payload)?, |activity_tag, activity| {
                category = Some(match activity_tag {
                    1 => "reaction", 2 => "receipt", 3 => "ack", _ => return Ok(()),
                }.to_owned());
                visit_fields(expect_bytes(activity)?, |item_tag, item| {
                    match item_tag {
                        1 if activity_tag == 1 => {
                            // Reaction.reactionEmoti=1, ReactionEmoti values verified in APK.
                            enum_reaction = match expect_varint(item)? {
                                1 => Some("👍"), 2 => Some("❤️"), 3 => Some("😂"),
                                7 => Some("🥺"), 8 => Some("👎"), 9 => Some("🔥"),
                                10 => Some("🎧"), _ => None,
                            }.map(str::to_owned);
                        }
                        1 if activity_tag == 2 => {
                            receipt_type = match expect_varint(item)? {
                                1 => Some("played".to_owned()),
                                2 => Some("saved".to_owned()),
                                3 => Some("seen".to_owned()),
                                _ => None,
                            };
                        }
                        2 => {
                            let who = parse_string(expect_bytes(item)?, 256)?;
                            if !who.is_empty() { initiator = Some(who); }
                        }
                        // Reaction.emojiUtf8=3; use only explicit emoji values.
                        // ReactionEmoti enum values are not mapped yet.
                        3 if activity_tag == 1 => {
                            let emoji = parse_string(expect_bytes(item)?, 32)?;
                            if !emoji.is_empty() { reaction_emoji = Some(emoji); }
                        }
                        _ => {}
                    }
                    Ok(())
                })?;
                Ok(())
            })
        })
    })?;
    // Explicit UTF-8 emoji takes precedence over the enum variant.
    Ok((category, initiator, receipt_type, reaction_emoji.or(enum_reaction)))
}

/// A ChatEvent is contribution variant 5: ChatEvent.eventType=1 contains a
/// ChatEventType oneof with verified field tags. Do not infer the invitee from
/// the sender: that requires InviteEvent's inner member fields.
fn decode_node_system_event(node: &[u8]) -> AppResult<Option<String>> {
    let mut event: Option<String> = None;
    visit_fields(node, |node_tag, node_value| {
        if node_tag != 8 { return Ok(()); }
        visit_fields(expect_bytes(node_value)?, |payload_tag, payload_value| {
            if payload_tag != 1 { return Ok(()); }
            visit_fields(expect_bytes(payload_value)?, |variant_tag, variant_value| {
                if variant_tag != 5 { return Ok(()); }
                visit_fields(expect_bytes(variant_value)?, |chat_tag, chat_value| {
                    if chat_tag != 1 { return Ok(()); }
                    visit_fields(expect_bytes(chat_value)?, |case_tag, case_value| {
                        expect_bytes(case_value)?;
                        let name = match case_tag {
                            1 => "created", 2 => "invited", 3 => "joined", 4 => "left",
                            5 => "renamed", 6 => "admin", 7 => "photo",
                            8 => "removed", 9 => "invite_link", 10 => "reported",
                            _ => return Ok(()),
                        };
                        event = Some(name.to_owned());
                        Ok(())
                    })
                })
            })
        })
    })?;
    Ok(event)
}

/// Distinguish a share sent off-platform only when contribution variant 3
/// (OffplatformTrackedShare) is present in the wire payload.
fn decode_node_share_origin(node: &[u8]) -> AppResult<Option<String>> {
    let mut origin = None;
    visit_fields(node, |tag, field| {
        if tag != 8 { return Ok(()); }
        visit_fields(expect_bytes(field)?, |payload_tag, payload| {
            if payload_tag != 1 { return Ok(()); }
            visit_fields(expect_bytes(payload)?, |variant_tag, _variant| {
                if variant_tag == 2 { origin = Some("in_app".to_owned()); }
                if variant_tag == 3 { origin = Some("offplatform".to_owned()); }
                Ok(())
            })
        })
    })?;
    Ok(origin)
}

/// History displays complete bounded text; the conversation list keeps short previews.
fn decode_node_full_text(bytes: &[u8]) -> AppResult<Option<String>> {
    let mut text = None;
    visit_fields(bytes, |tag, field| {
        if tag != 8 { return Ok(()); }
        visit_fields(expect_bytes(field)?, |payload_tag, payload| {
            if payload_tag != 1 { return Ok(()); }
            visit_fields(expect_bytes(payload)?, |contribution_tag, contribution| {
                if contribution_tag != 1 { return Ok(()); }
                visit_fields(expect_bytes(contribution)?, |text_tag, text_value| {
                    if text_tag == 1 {
                        let value = parse_string(expect_bytes(text_value)?, MAX_TEXT_BYTES)?;
                        if !value.trim().is_empty() { text = Some(value); }
                    }
                    Ok(())
                })
            })
        })
    })?;
    Ok(text)
}

fn decode_node_share_uri(bytes: &[u8]) -> AppResult<Option<String>> {
    let mut uri = None;
    visit_fields(bytes, |tag, field| {
        if tag != 8 { return Ok(()); }
        visit_fields(expect_bytes(field)?, |payload_tag, payload| {
            if payload_tag != 1 { return Ok(()); }
            visit_fields(expect_bytes(payload)?, |contribution_tag, contribution| {
                if contribution_tag == 2 || contribution_tag == 3 {
                    uri = tracked_share_uri(expect_bytes(contribution)?)?;
                }
                Ok(())
            })
        })
    })?;
    Ok(uri.filter(|u| u.starts_with("spotify:") || u.starts_with("https://open.spotify.com/")))
}

/// Use alphabetical order inside unnamed group titles, independent of the
/// server's member order. Chat rows themselves retain latest-activity order.
fn sort_participants_for_display(participants: &mut [ChatParticipant]) {
    participants.sort_by(|a, b| {
        a.display_name.to_lowercase().cmp(&b.display_name.to_lowercase())
            .then_with(|| a.username.cmp(&b.username))
    });
}

/// The previous v27 RPC remains functional for diagnostics.
pub(super) async fn probe() -> AppResult<Value> {
    let overview = fetch_overview().await?;
    to_value(&serde_json::json!({
        "latestNodes": overview.latest_nodes,
        "withHierarchy": overview.with_hierarchy,
        "withClientNode": overview.with_client_node,
    }))
}

/// Decode latest conversation metadata without causing read receipts or writes.
pub(super) async fn overview() -> AppResult<Value> {
    let mut result = fetch_overview().await?;
    let session = engine::session()?;
    let self_username = engine::username().unwrap_or_else(|| session.username());
    let usernames: HashSet<String> = result.conversations.iter()
        .flat_map(|c| c.member_usernames.iter())
        .filter(|u| !u.is_empty() && u.as_str() != self_username.as_str())
        .cloned().collect();
    // Reuses the existing bounded public-profile resolver. Failure to look up a
    // display name never blocks the conversation list: use the server username.
    user::resolve_names(&session, usernames.iter().map(String::as_str)).await;
    for row in &mut result.conversations {
        let mut seen = HashSet::<String>::new();
        row.participants = row.member_usernames.iter()
            .filter(|u| !u.is_empty() && u.as_str() != self_username.as_str() && seen.insert(u.to_string()))
            .map(|u| {
                let profile = user::cached_user(u);
                ChatParticipant {
                    username: u.clone(),
                    display_name: profile.display_name.filter(|s| !s.trim().is_empty())
                        .unwrap_or_else(|| u.clone()),
                    avatar_url: profile.images.first().map(|i| i.url.clone())
                        .filter(|s| s.starts_with("https://")),
                }
            }).collect();
        sort_participants_for_display(&mut row.participants);
        let other_names: Vec<&str> = row.participants.iter()
            .map(|p| p.display_name.as_str()).collect();
        row.display_title = match row.group_name.as_deref().filter(|n| !n.trim().is_empty()) {
            Some(group_title) => group_title.to_owned(),
            None if !other_names.is_empty() => {
                let names = other_names.iter().take(3).copied().collect::<Vec<_>>().join(", ");
                if other_names.len() > 3 {
                    format!("{names} +{}", other_names.len() - 3)
                } else { names }
            }
            None => "Conversation (participants unavailable)".to_owned(),
        };
        row.latest_sender_name = row.latest_sender_username.as_deref()
            .filter(|u| !u.trim().is_empty())
            .map(|sender| {
                if sender == self_username.as_str() {
                    "You".to_owned()
                } else {
                    row.participants.iter().find(|p| p.username == sender)
                        .map(|p| p.display_name.clone())
                        .unwrap_or_else(|| sender.to_owned())
                }
            });
        // Do not return the full membership identifiers in an extra field.
        row.member_usernames.clear();
    }
    to_value(&result)
}

async fn fetch_overview() -> AppResult<MessageOverview> {
    let session = engine::session()?;
    let reply = http::spc_post_idempotent(
        &session, LATEST_PATH, PROTOBUF, PROTOBUF, LATEST_REQUEST,
    ).await.map_err(|e| {
        let detail = e.status.map(|s| format!("HTTP {s}"))
            .unwrap_or_else(|| "network or authorization failure".to_owned());
        AppError::unavailable(format!("Messaging read-only request unavailable: {detail}"))
    })?;
    if reply.len() > MAX_REPLY_BYTES {
        return Err(AppError::unavailable("Messaging response exceeded safe size limit"));
    }
    decode_overview(&reply)
}

fn decode_overview(buf: &[u8]) -> AppResult<MessageOverview> {
    let mut result = MessageOverview::default();
    let mut seen = HashSet::<String>::new();
    visit_fields(buf, |tag, value| {
        if tag != 1 { return Ok(()); }
        let payload = expect_bytes(value)?;
        result.latest_nodes += 1;
        if result.latest_nodes > MAX_NODES {
            return Err(AppError::unavailable("Unexpectedly large messaging response"));
        }
        let mut hierarchy = None;
        let mut node = None;
        visit_fields(payload, |field, nested| {
            match field {
                1 => hierarchy = Some(decode_hierarchy(expect_bytes(nested)?)?),
                2 => node = Some(decode_client_node(expect_bytes(nested)?)?),
                _ => {}
            }
            Ok(())
        })?;
        if hierarchy.is_some() { result.with_hierarchy += 1; }
        if node.is_some() { result.with_client_node += 1; }
        if let Some(mut row) = hierarchy {
            let details = node.unwrap_or_else(|| LatestNodeDetails {
                kind: "missing".to_owned(), ..Default::default()
            });
            row.latest_node_kind = details.kind;
            row.latest_content_label = details.content_label;
            row.latest_preview_text = details.preview_text;
            row.latest_sender_username = details.sender_username;
            row.latest_occurred_at_ms = details.occurred_at_ms;
            // If the server omits an ID, preserve that record rather than
            // merging otherwise unrelated conversations into one.
            if row.hierarchy_id.is_empty() || seen.insert(row.hierarchy_id.clone()) {
                result.conversations.push(row);
            }
        }
        Ok(())
    })?;
    result.distinct_hierarchies = result.conversations.len();
    Ok(result)
}

fn decode_hierarchy(buf: &[u8]) -> AppResult<ConversationSummary> {
    let mut row = ConversationSummary::default();
    visit_fields(buf, |tag, field| {
        match tag {
            1 => visit_fields(expect_bytes(field)?, |id_tag, id_value| {
                if id_tag == 1 {
                    row.hierarchy_id = parse_string(expect_bytes(id_value)?, 128)?;
                }
                Ok(())
            })?,
            3 => {
                let stats = decode_unseen(expect_bytes(field)?)?;
                row.unseen_events = stats.events;
                row.unseen_contributions = stats.contributions;
            },
            5 => {
                row.is_group = true;
                visit_fields(expect_bytes(field)?, |group_tag, group_value| {
                    match group_tag {
                        1 => {
                            let name = parse_string(expect_bytes(group_value)?, 200)?;
                            if !name.is_empty() { row.group_name = Some(name); }
                        }
                        2 => {
                            if row.group_member_count >= MAX_MEMBERS {
                                return Err(wire_error());
                            }
                            row.group_member_count += 1;
                            // GroupMember: memberUsername=1, groupMemberStatus=2.
                            let mut username = String::new();
                            let mut status = 0u64;
                            visit_fields(expect_bytes(group_value)?, |member_tag, member_value| {
                                match member_tag {
                                    1 => username = parse_string(expect_bytes(member_value)?, 256)?,
                                    2 => status = expect_varint(member_value)?,
                                    _ => {}
                                }
                                Ok(())
                            })?;
                            if !username.is_empty() {
                                row.member_usernames.push(username.clone());
                                row.member_statuses.push((username, status));
                            }
                        }
                        _ => {}
                    }
                    Ok(())
                })?;
            }
            _ => {}
        }
        Ok(())
    })?;
    Ok(row)
}

#[derive(Default, Debug, PartialEq, Eq)]
struct UnseenCounts { events: u32, contributions: u32 }

/// HierarchyStatistic: 1=all unseen events; 3=unseen activity;
/// 4=unseen contributions. We must not use field 1 for unread-message badges.
fn decode_unseen(buf: &[u8]) -> AppResult<UnseenCounts> {
    let mut counts = UnseenCounts::default();
    visit_fields(buf, |tag, field| {
        let count = || u32::try_from(expect_varint(field)?)
            .map_err(|_| wire_error());
        match tag {
            1 => counts.events = count()?,
            4 => counts.contributions = count()?,
            _ => {}
        }
        Ok(())
    })?;
    Ok(counts)
}

#[derive(Default)]
struct LatestNodeDetails {
    kind: String,
    content_label: Option<String>,
    preview_text: Option<String>,
    sender_username: Option<String>,
    occurred_at_ms: Option<i64>,
}

fn decode_client_node(buf: &[u8]) -> AppResult<LatestNodeDetails> {
    let mut details = LatestNodeDetails {
        kind: "no payload".to_owned(), ..Default::default()
    };
    visit_fields(buf, |tag, field| {
        match tag {
            // ClientEventCapture: initUsername=1, eventCapture=2.
            6 => visit_fields(expect_bytes(field)?, |event_tag, event_value| {
                match event_tag {
                    1 => {
                        let username = parse_string(expect_bytes(event_value)?, 256)?;
                        if !username.is_empty() { details.sender_username = Some(username); }
                    }
                    2 => visit_fields(expect_bytes(event_value)?, |capture_tag, capture_value| {
                        if capture_tag == 1 {
                            // EventCapture.occurredAt is google.protobuf.Timestamp.
                            details.occurred_at_ms = timestamp_ms(expect_bytes(capture_value)?)?;
                        }
                        Ok(())
                    })?,
                    _ => {}
                }
                Ok(())
            })?,
            8 => {
                details.kind = "other payload".to_owned();
                // MessagePayload: contribution=1, activity=2.
                visit_fields(expect_bytes(field)?, |payload_tag, payload_value| {
                    match payload_tag {
                        1 => {
                            details.kind = "contribution".to_owned();
                            let (label, excerpt) = decode_contribution(expect_bytes(payload_value)?)?;
                            details.content_label = Some(label);
                            details.preview_text = excerpt;
                        }
                        2 => {
                            expect_bytes(payload_value)?;
                            details.kind = "activity".to_owned();
                            details.content_label = Some("Chat activity".to_owned());
                        }
                        _ => {}
                    }
                    Ok(())
                })?;
            }
            _ => {}
        }
        Ok(())
    })?;
    Ok(details)
}

/// The ContributionPayload discriminants and nested field numbers were
/// independently verified against the decompiled protobuf descriptors.
/// Only FreeText and TrackedShare have mapped content bodies. Unsupported
/// variants retain an honest type label without trying to decode unknown data.
fn decode_contribution(buf: &[u8]) -> AppResult<(String, Option<String>)> {
    let mut label = "Message".to_owned();
    let mut excerpt = None;
    visit_fields(buf, |tag, value| {
        match tag {
            1 => {
                label = "Text message".to_owned();
                excerpt = decode_free_text(expect_bytes(value)?)?;
            }
            2 => {
                let uri = tracked_share_uri(expect_bytes(value)?)?;
                label = share_type_label(uri.as_deref()).to_owned();
            }
            3 => {
                // OffplatformTrackedShare.uri is field 1. Distinguish supported
                // Spotify URIs; other destinations stay generically "Shared link".
                let uri = tracked_share_uri(expect_bytes(value)?)?;
                label = offplatform_share_label(uri.as_deref()).to_owned();
            }
            4 => { expect_bytes(value)?; label = "Chat invitation".to_owned(); }
            5 => { expect_bytes(value)?; label = "Chat event".to_owned(); }
            6 => {
                let rich = expect_bytes(value)?;
                label = match decode_rich_media(rich)?.as_ref().map(|(kind, _)| kind.as_str()) {
                    Some("gif") => "Sent a GIF",
                    Some("sticker") => "Sent a sticker",
                    _ => "Shared media",
                }.to_owned();
            }
            8 => { expect_bytes(value)?; label = "Taste Match card".to_owned(); }
            // 7 is contribution metadata, not a content variant.
            _ => {}
        }
        Ok(())
    })?;
    Ok((label, excerpt))
}

fn decode_free_text(buf: &[u8]) -> AppResult<Option<String>> {
    let mut excerpt = None;
    visit_fields(buf, |tag, value| {
        if tag == 1 {
            let text = parse_string(expect_bytes(value)?, MAX_TEXT_BYTES)?;
            let normalized = text.split_whitespace().collect::<Vec<_>>().join(" ");
            if !normalized.is_empty() {
                let mut chars = normalized.chars();
                let first: String = chars.by_ref().take(MAX_PREVIEW_CHARS).collect();
                excerpt = Some(if chars.next().is_some() {
                    format!("{first}…")
                } else { first });
            }
        }
        Ok(())
    })?;
    Ok(excerpt)
}

fn tracked_share_uri(buf: &[u8]) -> AppResult<Option<String>> {
    let mut uri = None;
    visit_fields(buf, |tag, value| {
        if tag == 1 {
            let found = parse_string(expect_bytes(value)?, MAX_SHARE_URI_BYTES)?;
            if !found.is_empty() { uri = Some(found); }
        }
        Ok(())
    })?;
    Ok(uri)
}

fn offplatform_share_label(uri: Option<&str>) -> &'static str {
    match uri {
        Some(uri) if uri.starts_with("spotify:")
            || uri.starts_with("https://open.spotify.com/") => share_type_label(Some(uri)),
        _ => "Shared link",
    }
}

fn share_type_label(uri: Option<&str>) -> &'static str {
    let Some(uri) = uri else { return "Shared music" };
    let kind = uri.strip_prefix("spotify:").and_then(|v| v.split(':').next())
        .or_else(|| uri.strip_prefix("https://open.spotify.com/")
            .and_then(|v| v.split(['/', '?']).next()));
    match kind {
        Some("track") => "Shared a song",
        Some("album") => "Shared an album",
        Some("playlist") => "Shared a playlist",
        Some("artist") => "Shared an artist",
        Some("episode") => "Shared an episode",
        Some("show") => "Shared a podcast",
        Some("audiobook") => "Shared an audiobook",
        _ => "Shared music",
    }
}

/// google.protobuf.Timestamp: field 1 is seconds, field 2 is nanos.
/// Bad/missing timestamps are omitted rather than shown as 1970 or overflowing.
fn timestamp_ms(buf: &[u8]) -> AppResult<Option<i64>> {
    let mut seconds = None;
    let mut nanos = 0u64;
    visit_fields(buf, |tag, value| {
        match tag {
            1 => seconds = Some(expect_varint(value)? as i64),
            2 => nanos = expect_varint(value)?,
            _ => {}
        }
        Ok(())
    })?;
    let Some(seconds) = seconds else { return Ok(None) };
    // Bounded to a sensible date range (2000-2100), not a guessed epoch unit.
    if !(946_684_800..=4_102_444_800).contains(&seconds) || nanos >= 1_000_000_000 {
        return Ok(None);
    }
    Ok(seconds.checked_mul(1_000).and_then(|ms| ms.checked_add((nanos / 1_000_000) as i64)))
}

fn parse_string(buf: &[u8], max: usize) -> AppResult<String> {
    if buf.len() > max { return Err(wire_error()); }
    std::str::from_utf8(buf).map(|s| s.to_owned()).map_err(|_| wire_error())
}

#[derive(Clone, Copy)]
enum WireValue<'a> {
    Bytes(&'a [u8]),
    Varint(u64),
    Fixed,
}

fn expect_bytes(value: WireValue<'_>) -> AppResult<&[u8]> {
    match value { WireValue::Bytes(buf) => Ok(buf), _ => Err(wire_error()) }
}

fn expect_varint(value: WireValue<'_>) -> AppResult<u64> {
    match value { WireValue::Varint(n) => Ok(n), _ => Err(wire_error()) }
}

/// Strict bounded protobuf scanner; unknown fields can be skipped safely.
fn visit_fields(buf: &[u8], mut visit: impl FnMut(u32, WireValue<'_>) -> AppResult<()>) -> AppResult<()> {
    let mut offset = 0usize;
    while offset < buf.len() {
        let key = read_varint(buf, &mut offset)?;
        let number = key >> 3;
        if number == 0 || number > u32::MAX as u64 { return Err(wire_error()); }
        let value = match key & 7 {
            0 => WireValue::Varint(read_varint(buf, &mut offset)?),
            1 => { offset = offset.checked_add(8).ok_or_else(wire_error)?; WireValue::Fixed }
            2 => {
                let len = read_varint(buf, &mut offset)?;
                let len = usize::try_from(len).map_err(|_| wire_error())?;
                let end = offset.checked_add(len).ok_or_else(wire_error)?;
                let payload = buf.get(offset..end).ok_or_else(wire_error)?;
                offset = end;
                WireValue::Bytes(payload)
            }
            5 => { offset = offset.checked_add(4).ok_or_else(wire_error)?; WireValue::Fixed }
            _ => return Err(wire_error()),
        };
        if offset > buf.len() { return Err(wire_error()); }
        visit(number as u32, value)?;
    }
    Ok(())
}

fn read_varint(buf: &[u8], offset: &mut usize) -> AppResult<u64> {
    let mut result = 0u64;
    for i in 0..10 {
        let byte = *buf.get(*offset).ok_or_else(wire_error)?;
        *offset += 1;
        if i == 9 && byte > 1 { return Err(wire_error()); }
        result |= u64::from(byte & 0x7f) << (i * 7);
        if byte & 0x80 == 0 { return Ok(result); }
    }
    Err(wire_error())
}

fn wire_error() -> AppError {
    AppError::unavailable("Messaging service returned an invalid protobuf response")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn len_field(tag: u8, body: &[u8]) -> Vec<u8> {
        let mut v = vec![(tag << 3) | 2];
        let mut len = body.len();
        while len >= 0x80 {
            v.push((len as u8 & 0x7f) | 0x80);
            len >>= 7;
        }
        v.push(len as u8);
        v.extend_from_slice(body);
        v
    }

    #[test]
    fn unseen_messages_excludes_reactions_and_receipts() {
        let stats = [vec![0x08, 0x06], vec![0x18, 0x04], vec![0x20, 0x02]].concat();
        assert_eq!(decode_unseen(&stats).unwrap(), UnseenCounts { events: 6, contributions: 2 });
        assert_eq!(decode_unseen(&[0x08, 0x05]).unwrap().contributions, 0);
        assert!(decode_unseen(&[0x20, 0xff, 0xff, 0xff, 0xff, 0x1f]).is_err());
    }

    #[test]
    fn reaction_request_uses_verified_apk_field_tags() {
        assert_eq!(reaction_emoti("❤️"), Some(2));
        assert_eq!(reaction_emoti("more"), None);
        let add = encode_reaction_request("message123", Some(2), "❤️", None);
        let remove = encode_reaction_request("message123", Some(2), "❤️", Some("activity123"));
        assert_eq!(add[0], 0x0a); // ClientNodeRequest.nodeRequest = 1
        assert_eq!(remove[0], 0x0a);
        let mut found_add = false;
        let mut found_remove = false;
        for (bytes, operation, target, flag) in [
            (add, 4, "message123", &mut found_add),
            (remove, 5, "activity123", &mut found_remove),
        ] {
            visit_fields(&bytes, |field, value| {
                assert_eq!(field, 1);
                visit_fields(expect_bytes(value)?, |field, value| {
                    assert_eq!(field, 2);
                    visit_fields(expect_bytes(value)?, |field, value| {
                        assert_eq!(field, operation);
                        visit_fields(expect_bytes(value)?, |field, value| {
                            match field {
                                1 => assert_eq!(parse_string(expect_bytes(value)?, 256)?, target),
                                2 => visit_fields(expect_bytes(value)?, |field, value| {
                                    assert_eq!(field, 1);
                                    assert_eq!(expect_varint(value)?, 2);
                                    *flag = true;
                                    Ok(())
                                })?,
                                _ => panic!("unexpected field"),
                            }
                            Ok(())
                        })
                    })
                })
            }).unwrap();
        }
        assert!(found_add && found_remove);
    }

    #[test]
    fn deletion_envelope_contains_explicit_scope_and_target() {
        for (all, expected) in [(false, 0), (true, 1)] {
            let bytes = encode_delete_contribution("message-42", all);
            let mut found = false;
            visit_fields(&bytes, |tag, field| {
                assert_eq!(tag, 1); // ClientNodeRequest.nodeRequest
                visit_fields(expect_bytes(field)?, |tag, field| {
                    assert_eq!(tag, 2); // NodeRequest.messageRequest
                    visit_fields(expect_bytes(field)?, |tag, field| {
                        assert_eq!(tag, 3); // MessageRequest.deleteContribution
                        visit_fields(expect_bytes(field)?, |tag, field| {
                            match tag {
                                1 => assert_eq!(parse_string(expect_bytes(field)?, 256)?, "message-42"),
                                2 => { assert_eq!(expect_varint(field)?, expected); found = true; },
                                _ => panic!("unsupported delete field"),
                            }
                            Ok(())
                        })
                    })
                })
            }).unwrap();
            assert!(found);
        }
    }

    #[test]
    fn mark_seen_envelope_uses_verified_hierarchy_ack_oneof() {
        let request = encode_mark_seen("test-chat");
        assert!(request.windows(9).any(|w| w == b"test-chat"));
        assert!(request.ends_with(&[0x12, 0x02, 0x18, 0x01]));
        assert_eq!(request[0], 0x0a); // ClientNodeRequest field 1
        assert!(request.contains(&0x1a)); // NodeRequest bulk field 3
    }

    #[test]
    fn custom_emoji_reaction_uses_utf8_oneof_not_enum() {
        let encoded = encode_reaction_request("node", None, "😀", None);
        assert!(encoded.windows("😀".len()).any(|w| w == "😀".as_bytes()));
        assert!(valid_emoji_reaction("😀"));
        assert!(!valid_emoji_reaction("not an emoji"));
    }

    #[test]
    fn group_protocol_fields_and_leave_modes() {
        let mut body = Vec::new();
        append_pb_string(&mut body, 2, "alice");
        append_pb_string(&mut body, 2, "bob");
        append_pb_string(&mut body, 3, "Friends");
        let mut fields = Vec::new();
        visit_fields(&body, |field, value| {
            fields.push((field, expect_bytes(value)?.len()));
            Ok(())
        }).unwrap();
        assert_eq!(fields, vec![(2, 5), (2, 3), (3, 7)]);
        // Official LeaveGroupRequest tag 2 is a varint, and the enum values
        // are READ_ONLY=1 and DELETE=2.
        let mut read_only = Vec::new();
        append_pb_string(&mut read_only, 1, "chat123");
        read_only.extend_from_slice(&[0x10, 1]);
        let mut parsed = None;
        visit_fields(&read_only, |tag, value| {
            if tag == 2 { parsed = Some(expect_varint(value)?); }
            Ok(())
        }).unwrap();
        assert_eq!(parsed, Some(1));
        read_only.pop();
        read_only.push(2);
        parsed = None;
        visit_fields(&read_only, |tag, value| {
            if tag == 2 { parsed = Some(expect_varint(value)?); }
            Ok(())
        }).unwrap();
        assert_eq!(parsed, Some(2));
    }

    #[test]
    fn request_is_read_only_sorted_and_bounded() {
        assert_eq!(LATEST_REQUEST, &[8, 2, 16, 50]);
    }

    #[test]
    fn decodes_group_name_unseen_count_and_payload_type() {
        let id = len_field(1, b"chat123");
        let group = [len_field(1, b"Friends"), len_field(2, b"")].concat();
        let unseen = vec![8, 3];
        let hierarchy = [len_field(1, &id), len_field(3, &unseen), len_field(5, &group)].concat();
        let node = len_field(8, &len_field(1, b""));
        let record = [len_field(1, &hierarchy), len_field(2, &node)].concat();
        let result = decode_overview(&[len_field(1, &record), len_field(1, &record)].concat()).unwrap();
        assert_eq!(result.latest_nodes, 2);
        assert_eq!(result.distinct_hierarchies, 1);
        assert_eq!(result.conversations[0], ConversationSummary {
            hierarchy_id: "chat123".into(), group_name: Some("Friends".into()),
            is_group: true, group_member_count: 1, pending_member_count: 0,
            active_other_member_count: 0, unseen_events: 3,
            unseen_contributions: 0,
            latest_node_kind: "contribution".into(),
            latest_content_label: Some("Message".into()),
            latest_preview_text: None,
            latest_sender_username: None,
            latest_sender_name: None,
            latest_occurred_at_ms: None,
            display_title: String::new(),
            participants: vec![],
            member_usernames: vec![],
            member_statuses: vec![],
        });
    }

    #[test]
    fn decodes_verified_group_member_status_enum_values() {
        // GroupMember.status field 2: PENDING=2, UNSPECIFIED=0.
        let pending = [len_field(1, b"alice"), vec![0x10, 0x02]].concat();
        let accepted = len_field(1, b"bob");
        let group = [len_field(2, &pending), len_field(2, &accepted)].concat();
        let hierarchy = len_field(5, &group);
        let parsed = decode_hierarchy(&hierarchy).unwrap();
        assert_eq!(parsed.group_member_count, 2);
        assert_eq!(parsed.member_statuses,
            vec![("alice".to_owned(), 2), ("bob".to_owned(), 0)]);
    }

    #[test]
    fn reads_group_member_usernames_and_contribution_types() {
        let member_a = len_field(1, b"myself");
        let member_b = len_field(1, b"alice");
        let group = [len_field(2, &member_a), len_field(2, &member_b)].concat();
        let hierarchy = [len_field(1, &len_field(1, b"dm123")), len_field(5, &group)].concat();
        let free_text = len_field(1, &[]);
        let payload = len_field(1, &free_text);
        let node = len_field(8, &payload);
        let record = [len_field(1, &hierarchy), len_field(2, &node)].concat();
        let result = decode_overview(&len_field(1, &record)).unwrap();
        assert_eq!(result.conversations[0].member_usernames, vec!["myself", "alice"]);
        assert_eq!(result.conversations[0].latest_content_label.as_deref(), Some("Text message"));
    }

    #[test]
    fn classifies_share_and_activity_without_reading_content() {
        assert_eq!(decode_contribution(&len_field(2, &[])).unwrap().0, "Shared music");
        assert_eq!(decode_contribution(&len_field(8, &[])).unwrap().0, "Taste Match card");
        let activity = decode_client_node(&len_field(8, &len_field(2, &[]))).unwrap();
        assert_eq!(activity.kind, "activity");
        assert_eq!(activity.content_label.as_deref(), Some("Chat activity"));
    }

    #[test]
    fn handles_direct_chat_and_bad_wire_format() {
        let result = decode_overview(&len_field(1, &len_field(1, &len_field(1, &len_field(1, b"direct"))))).unwrap();
        assert_eq!(result.distinct_hierarchies, 1);
        assert!(!result.conversations[0].is_group);
        assert_eq!(result.conversations[0].group_name, None);
        assert!(decode_overview(&[0x0a, 5, 0]).is_err());
        assert!(decode_overview(&[0]).is_err());
        assert!(decode_overview(&[0x0a, 1, 0x0a]).is_err());
    }

    #[test]
    fn extracts_text_message_sender_and_timestamp_without_logging_bodies() {
        let free_text = len_field(1, b"Hello from chat!  Hope you are well.");
        let contribution = len_field(1, &free_text);
        let capture = len_field(1, &[0x08, 0x80, 0xc5, 0x8c, 0xe4, 0x06]);
        let event = [len_field(1, b"alice"), len_field(2, &capture)].concat();
        let node = [len_field(6, &event), len_field(8, &len_field(1, &contribution))].concat();
        let decoded = decode_client_node(&node).unwrap();
        assert_eq!(decoded.kind, "contribution");
        assert_eq!(decoded.content_label.as_deref(), Some("Text message"));
        assert_eq!(decoded.preview_text.as_deref(), Some("Hello from chat! Hope you are well."));
        assert_eq!(decoded.sender_username.as_deref(), Some("alice"));
        assert!(decoded.occurred_at_ms.is_some());
    }

    #[test]
    fn classify_shared_spotify_uri_and_bound_text_preview() {
        let track = len_field(1, b"spotify:track:abc");
        assert_eq!(decode_contribution(&len_field(2, &track)).unwrap().0, "Shared a song");
        assert_eq!(share_type_label(Some("spotify:playlist:abc")), "Shared a playlist");
        assert_eq!(share_type_label(Some("spotify:episode:abc")), "Shared an episode");
        assert_eq!(share_type_label(Some("https://untrusted.example/track/abc")), "Shared music");
        let long = "🚀".repeat(MAX_PREVIEW_CHARS + 5);
        let preview = decode_free_text(&len_field(1, long.as_bytes())).unwrap().unwrap();
        assert_eq!(preview.chars().count(), MAX_PREVIEW_CHARS + 1);
        assert!(preview.ends_with('…'));
    }

    #[test]
    fn sorts_unnamed_group_members_case_insensitively() {
        let mut members = ["taylor_jackman", "Enzo", "Allan"]
            .into_iter()
            .map(|name| ChatParticipant {
                username: name.to_owned(), display_name: name.to_owned(), avatar_url: None,
            })
            .collect::<Vec<_>>();
        sort_participants_for_display(&mut members);
        assert_eq!(members.iter().map(|m| m.display_name.as_str()).collect::<Vec<_>>(),
            vec!["Allan", "Enzo", "taylor_jackman"]);
    }

    #[test]
    fn recognizes_offplatform_spotify_shares_but_not_arbitrary_links() {
        let track = len_field(1, b"spotify:track:example");
        assert_eq!(decode_contribution(&len_field(3, &track)).unwrap().0, "Shared a song");
        let playlist = len_field(1, b"https://open.spotify.com/playlist/example");
        assert_eq!(decode_contribution(&len_field(3, &playlist)).unwrap().0, "Shared a playlist");
        let other = len_field(1, b"https://example.com/track/example");
        assert_eq!(decode_contribution(&len_field(3, &other)).unwrap().0, "Shared link");
        assert_eq!(decode_contribution(&len_field(3, &[])).unwrap().0, "Shared link");
    }

    #[test]
    fn malformed_timestamp_and_text_fail_closed() {
        assert_eq!(timestamp_ms(&[]).unwrap(), None);
        assert_eq!(timestamp_ms(&[8, 1]).unwrap(), None);
        assert!(decode_free_text(&len_field(1, &[0xff])).is_err());
    }

    #[test]
    fn history_reads_text_share_and_pagination_without_writes() {
        let free_text = len_field(1, b"Hello again, this is the full history body");
        let contribution = len_field(1, &free_text);
        let node = [len_field(1, &len_field(1, b"node1")),
            len_field(8, &len_field(1, &contribution))].concat();
        let h = len_field(1, &len_field(1, b"thread1"));
        let response = [len_field(1, &h), len_field(2, &node),
            len_field(3, b"olderToken")].concat();
        let page = decode_history(&response, "thread1").unwrap();
        assert_eq!(page.messages.len(), 1);
        assert_eq!(page.messages[0].text.as_deref(), Some("Hello again, this is the full history body"));
        assert_eq!(page.next_page_token.as_deref(), Some("olderToken"));
        assert!(decode_history(&response, "different").is_err());
    }

    #[test]
    fn history_flattens_child_nodes() {
        let share = len_field(1, b"spotify:track:song123");
        let child = len_field(8, &len_field(1, &len_field(2, &share)));
        let root = len_field(3, &child);
        let page = decode_history(&len_field(2, &root), "chat").unwrap();
        assert_eq!(page.messages.len(), 1);
        assert_eq!(page.messages[0].share_uri.as_deref(), Some("spotify:track:song123"));
    }

    #[test]
    fn nested_receipt_stays_attached_to_its_original_message() {
        let receipt = [vec![8, 7], len_field(2, b"alice")].concat();
        let activity = len_field(8, &len_field(2, &len_field(2, &receipt)));
        let contribution = len_field(8, &len_field(1, &len_field(1, &len_field(1, b"Hi"))));
        let parent = [len_field(1, &len_field(1, b"parent42")), contribution,
            len_field(3, &activity)].concat();
        let page = decode_history(&len_field(2, &parent), "chat").unwrap();
        assert_eq!(page.messages.len(), 2);
        let evt = page.messages.iter().find(|m| m.kind == "activity").unwrap();
        assert_eq!(evt.parent_node_id.as_deref(), Some("parent42"));
        assert_eq!(evt.activity_type.as_deref(), Some("receipt"));
        assert_eq!(evt.activity_initiator.as_deref(), Some("alice"));
    }

    #[test]
    fn receipt_enum_and_initiator_are_decoded_without_guessing() {
        let receipt = [vec![8, 1], len_field(2, b"alice")].concat();
        let activity = len_field(8, &len_field(2, &len_field(2, &receipt)));
        let decoded = decode_activity_info(&activity).unwrap();
        assert_eq!(decoded, (Some("receipt".to_owned()), Some("alice".to_owned()),
            Some("played".to_owned()), None));
        let saved = len_field(8, &len_field(2, &len_field(2, &vec![8, 2])));
        assert_eq!(decode_activity_info(&saved).unwrap().2.as_deref(), Some("saved"));
        let unknown = len_field(8, &len_field(2, &len_field(2, &vec![8, 42])));
        assert!(decode_activity_info(&unknown).unwrap().2.is_none());
    }

    #[test]
    fn reaction_emoji_and_origin_are_decoded_only_from_verified_fields() {
        let reaction = [len_field(2, b"alice"), len_field(3, "❤️".as_bytes())].concat();
        let node = len_field(8, &len_field(2, &len_field(1, &reaction)));
        let parsed = decode_activity_info(&node).unwrap();
        assert_eq!(parsed.0.as_deref(), Some("reaction"));
        assert_eq!(parsed.1.as_deref(), Some("alice"));
        assert_eq!(parsed.3.as_deref(), Some("❤️"));
        let normal = len_field(8, &len_field(1,
            &len_field(2, &len_field(1, b"spotify:track:foo"))));
        let external = len_field(8, &len_field(1,
            &len_field(3, &len_field(1, b"spotify:track:foo"))));
        assert_eq!(decode_node_share_origin(&normal).unwrap().as_deref(), Some("in_app"));
        assert_eq!(decode_node_share_origin(&external).unwrap().as_deref(), Some("offplatform"));
        assert_eq!(decode_node_share_origin(&node).unwrap(), None);
    }

    #[test]
    fn reaction_enum_and_event_variants_use_verified_wire_values() {
        // reactionEmoti=1 (fire=9), initiator=2. The server may omit emojiUtf8.
        let reaction = [vec![8, 9], len_field(2, b"alice")].concat();
        let reaction_node = len_field(8, &len_field(2, &len_field(1, &reaction)));
        let decoded = decode_activity_info(&reaction_node).unwrap();
        assert_eq!(decoded.3.as_deref(), Some("🔥"));
        // ChatEventType.joinEvent=3 nested inside ChatEvent.eventType=1.
        let event_type = len_field(3, &[]);
        let event = len_field(1, &event_type);
        let contribution = len_field(5, &event);
        let event_node = len_field(8, &len_field(1, &contribution));
        assert_eq!(decode_node_system_event(&event_node).unwrap().as_deref(), Some("joined"));
        assert_eq!(decode_node_system_event(&reaction_node).unwrap(), None);
        // Unknown variants must never be reported as a known invitation.
        let unknown = len_field(8, &len_field(1,
            &len_field(5, &len_field(1, &len_field(12, &[])))));
        assert_eq!(decode_node_system_event(&unknown).unwrap(), None);
    }

    #[test]
    fn native_share_envelope_matches_verified_fields() {
        let uri = "spotify:track:0123456789ABCDEFGHIJKL";
        assert!(validate_share_uri(uri).is_ok());
        assert!(validate_share_uri("spotify:album:0123456789ABCDEFGHIJKL").is_ok());
        assert!(validate_share_uri("spotify:playlist:0123456789ABCDEFGHIJKL").is_ok());
        assert!(validate_share_uri("spotify:artist:0123456789ABCDEFGHIJKL").is_err());
        assert!(validate_share_uri("https://open.spotify.com/track/0123456789ABCDEFGHIJKL").is_err());
        assert!(validate_share_uri("spotify:track:too-short").is_err());
        assert!(validate_share_uri("spotify:track:0123456789ABCDEFGHIJKL:extra").is_err());

        let request = encode_share_request("chat-id", uri);
        let mut found = false;
        visit_fields(&request, |tag, bytes| {
            assert_eq!(tag, 1); // ClientNodeRequest.nodeRequest
            visit_fields(expect_bytes(bytes)?, |tag, bytes| {
                assert_eq!(tag, 2); // NodeRequest.messageRequest
                visit_fields(expect_bytes(bytes)?, |tag, bytes| {
                    assert_eq!(tag, 1); // MessageRequest.addContribution
                    visit_fields(expect_bytes(bytes)?, |tag, bytes| {
                        match tag {
                            1 => assert_eq!(expect_bytes(bytes)?, b"chat-id"),
                            2 => visit_fields(expect_bytes(bytes)?, |tag, bytes| {
                                assert_eq!(tag, 2); // ContributionPayload.trackedShare
                                visit_fields(expect_bytes(bytes)?, |tag, bytes| {
                                    assert_eq!(tag, 1); // TrackedShare.uri
                                    assert_eq!(expect_bytes(bytes)?, uri.as_bytes());
                                    found = true;
                                    Ok(())
                                })
                            })?,
                            _ => panic!("Unexpected AddContribution field"),
                        }
                        Ok(())
                    })
                })
            })
        }).unwrap();
        assert!(found);
    }

    #[test]
    fn delete_chat_for_me_request_uses_hierarchy_id_field_one() {
        let mut data = Vec::new();
        append_pb_string(&mut data, 1, "abc123");
        assert_eq!(data, [0x0a, 6, b'a', b'b', b'c', b'1', b'2', b'3']);
    }

    #[test]
    fn decodes_giphy_and_sticker_in_verified_rich_media_envelope() {
        let mut gif = Vec::new();
        append_pb_string(&mut gif, 1, "abc123Z");
        gif.extend_from_slice(&[0x10, 0x02]); // G-rated
        let mut rich = Vec::new(); append_pb_message(&mut rich, 1, &gif);
        let mut contribution = Vec::new(); append_pb_message(&mut contribution, 6, &rich);
        let mut payload = Vec::new(); append_pb_message(&mut payload, 1, &contribution);
        let mut node = Vec::new(); append_pb_message(&mut node, 8, &payload);
        assert_eq!(decode_node_rich_media(&node).unwrap(),
            Some(("gif".into(), "abc123Z".into())));
        assert_eq!(decode_client_node(&node).unwrap().content_label.as_deref(), Some("Sent a GIF"));
        let mut sticker = Vec::new(); append_pb_string(&mut sticker, 1, "sticker-id");
        let mut rm = Vec::new(); append_pb_message(&mut rm, 2, &sticker);
        assert_eq!(decode_rich_media(&rm).unwrap(),
            Some(("sticker".into(), "sticker-id".into())));
        let request = encode_rich_media_request("chat", "gif", "abc123Z", 2);
        assert!(request.windows(gif.len()).any(|part| part == gif.as_slice()));
    }

    #[test]
    fn plain_text_request_uses_verified_nested_fields() {
        let mut text = Vec::new(); append_pb_string(&mut text, 1, "hello");
        let mut payload = Vec::new(); append_pb_message(&mut payload, 1, &text);
        let mut add = Vec::new(); append_pb_string(&mut add, 1, "chat-id");
        append_pb_message(&mut add, 2, &payload);
        let mut request = Vec::new(); append_pb_message(&mut request, 1, &add);
        let mut node = Vec::new(); append_pb_message(&mut node, 2, &request);
        let mut client = Vec::new(); append_pb_message(&mut client, 1, &node);
        assert!(!client.is_empty());
        assert_eq!(client[0], 0x0a);
        assert!(client.windows(5).any(|w| w == b"hello"));
    }

}
