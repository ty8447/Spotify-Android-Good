//! Playlist page extras of the Android client:
//!
//! * "Recommended Songs" (owned playlists): `POST playlistextender/v2/extendp` (JSON)
//!   `{title, numResults, playlistURI, trackIDs, trackSkipIDs}` → `{recommendedTracks: [{id, name,
//!   album: {id, name, imageUrl, largeImageUrl}, duration, artists: [{id, name}], isExplicit,
//!   isCurrentlyPlayable}]}`
//! * BPM and key (mixed playlists): extended-metadata `AUDIO_ATTRIBUTES_V2` (222):
//!   `AudioAttributes{1: double bpm, 2: Key{1: key, 2: mode}, 3: CamelotKey{1: key "10A", 2: color}}`
//! * Library filter (Genres, Moods, Activities): `POST tags-service/spotify.tags.v1.SuggestionService/
//!   GetSuggestions` `{1: true}` → `{2: ttl_in_seconds, 3: repeated Tag{1: tag_uri, 2: playlist_uri,
//!   3: repeated Item{1: uri, 2: relevance}, 4: rank}}`; the tags are concepts, named by
//!   extended-metadata `CONCEPT` (84)
//! * Prompted playlists: `POST spotbot-api2/spotify.spotbot.v2.promptable_playlist.
//!   PromptablePlaylistApi/CreatePromptablePlaylist` with `CreatePromptablePlaylistRequest{2:
//!   username, 3: query_text, 5: is_private, 13: generation_mode (2 FULL_GENERATION)}` →
//!   `{1: violating_trust_and_safety, 2: spotbot_uri, 3: PlaylistMetadata{3: playlist_title}}`

use super::http::{self, JSON, PROTOBUF};
use super::messaging::{append_pb_string, append_pb_varint};
use super::metadata;
use super::showextras::fields;
use crate::engine;
use crate::error::AppResult;
use crate::rpc::parse_args;
use serde::Deserialize;
use serde_json::{json, Value};

const AUDIO_ATTRIBUTES_V2: i32 = 222;
const CONCEPT: i32 = 84;

/// Rows (`{uri, name, artists, image, explicit, durationMs, playable}`) of an extender reply.
pub(crate) fn parse_extender(body: &Value) -> Vec<Value> {
    let tracks = body["recommendedTracks"].as_array().cloned().unwrap_or_default();
    tracks.iter().filter_map(|t| {
        let id = t["id"].as_str().filter(|id| !id.is_empty())?;
        let artists: Vec<&str> = t["artists"].as_array().into_iter().flatten().filter_map(|a| a["name"].as_str()).collect();
        Some(json!({
            "uri": format!("spotify:track:{id}"), "name": t["name"], "artists": artists.join(", "),
            "image": t["album"]["imageUrl"].as_str().or(t["album"]["largeImageUrl"].as_str()),
            "explicit": t["isExplicit"].as_bool().unwrap_or(false), "durationMs": t["duration"].as_u64().unwrap_or(0),
            "playable": t["isCurrentlyPlayable"].as_bool().unwrap_or(true),
        }))
    }).collect()
}

/// `catalog.playlistRecommendations {uri, title, trackUris, skipUris, count}` → `{items}`.
pub(crate) async fn recommendations(args: Value) -> AppResult<Value> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Args {
        uri: String,
        #[serde(default)]
        title: String,
        #[serde(default)]
        track_uris: Vec<String>,
        #[serde(default)]
        skip_uris: Vec<String>,
        #[serde(default)]
        count: Option<u32>,
    }
    let a: Args = parse_args(args)?;
    let ids = |uris: &[String]| -> Vec<String> { uris.iter().filter_map(|u| u.strip_prefix("spotify:track:")).map(str::to_string).collect() };
    let body = json!({
        "title": a.title, "numResults": a.count.unwrap_or(20).clamp(1, 50), "playlistURI": a.uri,
        "trackIDs": ids(&a.track_uris), "trackSkipIDs": ids(&a.skip_uris),
    });
    let session = engine::session()?;
    let raw = http::spc_post_idempotent(&session, "/playlistextender/v2/extendp", JSON, JSON, body.to_string().as_bytes())
        .await
        .map_err(|e| e.error)?;
    Ok(json!({ "items": parse_extender(&http::json(&raw)?) }))
}

/// `{bpm, key, camelot, color}` of one track.
pub(crate) fn parse_audio_attributes(b: &[u8]) -> Value {
    let text = |s: &[u8]| String::from_utf8_lossy(s).into_owned();
    let (mut bpm, mut key, mut camelot, mut color) = (0.0, String::new(), String::new(), String::new());
    for (f, v, s) in fields(b) {
        match f {
            1 => bpm = f64::from_bits(v),
            2 => key = fields(s).iter().find(|x| x.0 == 1).map(|x| text(x.2)).unwrap_or_default(),
            3 => for (g, _, t) in fields(s) {
                match g {
                    1 => camelot = text(t),
                    2 => color = text(t),
                    _ => {}
                }
            },
            _ => {}
        }
    }
    json!({ "bpm": bpm, "key": key, "camelot": camelot, "color": color })
}

/// `catalog.audioAttributes {uris}` → `{items: {uri: {bpm, key, camelot, color}}}` (tracks without data left out).
pub(crate) async fn audio_attributes(args: Value) -> AppResult<Value> {
    #[derive(Deserialize)]
    struct Uris {
        uris: Vec<String>,
    }
    let a: Uris = parse_args(args)?;
    let uris: Vec<String> = a.uris.into_iter().filter(|u| u.starts_with("spotify:track:")).take(200).collect();
    let session = engine::session()?;
    let found = metadata::fetch_extension_raw(&session, AUDIO_ATTRIBUTES_V2, &uris).await?.found;
    let items: serde_json::Map<String, Value> = found.iter().map(|(u, b)| (u.clone(), parse_audio_attributes(b))).collect();
    Ok(json!({ "items": items }))
}

/// `playlist.createPrompted {prompt, private}` → `{uri, title, violating}`.
pub(crate) async fn create_prompted(args: Value) -> AppResult<Value> {
    #[derive(Deserialize)]
    struct Args {
        prompt: String,
        #[serde(default)]
        private: bool,
    }
    let a: Args = parse_args(args)?;
    let prompt = a.prompt.trim();
    if prompt.is_empty() {
        return Err(crate::error::AppError::invalid("prompt must not be empty"));
    }
    let session = engine::session()?;
    let mut body = Vec::new();
    append_pb_string(&mut body, 2, &session.username());
    append_pb_string(&mut body, 3, prompt);
    if a.private {
        body.extend([5 << 3, 1]);
    }
    body.push(13 << 3);
    append_pb_varint(&mut body, 2);
    let reply = http::spc_send_once(
        &session, ::http::Method::POST,
        &format!("{SPOTBOT}/CreatePromptablePlaylist"),
        PROTOBUF, PROTOBUF, body,
    )
    .await
    .map_err(|e| e.error)?;
    let text = |s: &[u8]| String::from_utf8_lossy(s).into_owned();
    let (mut uri, mut title, mut violating) = (String::new(), String::new(), false);
    for (f, v, s) in fields(&reply) {
        match f {
            1 => violating = v != 0,
            2 => uri = text(s),
            3 => title = fields(s).iter().find(|x| x.0 == 3).map(|x| text(x.2)).unwrap_or_default(),
            _ => {}
        }
    }
    log::info!("createPrompted: uri={} violating={violating}", uri.split(':').nth(1).unwrap_or_default());
    // Spotify generates the songs after answering: the playlist 404s for a few seconds. Wait for
    // it (up to ~30 s) so its page opens with the songs.
    if let Some(id) = uri.strip_prefix("spotify:playlist:") {
        for _ in 0..15 {
            if http::spc_get(&session, &format!("/playlist/v2/playlist/{id}?decorate=revision,length&length=1"), None).await.is_ok() {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_secs(2)).await;
        }
    }
    Ok(json!({ "uri": uri, "title": title, "violating": violating }))
}

/// `[(tag_uri, list_uri, item uris, their relevance)]` of a `GetSuggestionsResponse`.
pub(crate) fn parse_tag_suggestions(b: &[u8]) -> Vec<(String, String, Vec<String>, Vec<f64>)> {
    let text = |s: &[u8]| String::from_utf8_lossy(s).into_owned();
    // Item.relevance: a double, float or integer score, whichever the wire says.
    let score = |v: u64, s: &[u8]| match s.len() {
        8 => f64::from_bits(v),
        4 => f64::from(f32::from_bits(v as u32)),
        _ => v as f64,
    };
    fields(b).into_iter().filter(|f| f.0 == 3).map(|(_, _, t)| {
        let (mut tag, mut list, mut items, mut scores) = (String::new(), String::new(), Vec::new(), Vec::new());
        for (f, _, v) in fields(t) {
            match f {
                1 => tag = text(v),
                2 => list = text(v),
                3 => {
                    let item = fields(v);
                    if let Some(u) = item.iter().find(|x| x.0 == 1) {
                        items.push(text(u.2));
                        scores.push(item.iter().find(|x| x.0 == 2).map_or(0.0, |x| score(x.1, x.2)));
                    }
                }
                _ => {}
            }
        }
        (tag, list, items, scores)
    }).filter(|t| !t.0.is_empty()).collect()
}

/// The class ("Genres", "Moods", "Activities") of a tag from its URI (`spotify:tag:<class>:<id>`).
fn tag_class(uri: &str) -> &'static str {
    let parts: Vec<&str> = uri.split(':').collect();
    let class = if parts.len() >= 4 { parts[parts.len() - 2].to_ascii_lowercase() } else { String::new() };
    match class.as_str() {
        c if c.starts_with("genre") => "Genres",
        c if c.starts_with("mood") => "Moods",
        c if c.starts_with("activit") => "Activities",
        _ => "Tags",
    }
}

/// `library.tags` → `{classes: [{name, tags: [{uri, name, items}]}]}`: Spotify's "Filter by" sheet.
pub(crate) async fn library_tags(_args: Value) -> AppResult<Value> {
    let session = engine::session()?;
    let raw = http::spc_post_idempotent(&session, "/tags-service/spotify.tags.v1.SuggestionService/GetSuggestions", PROTOBUF, PROTOBUF, &[0x08, 0x01])
        .await
        .map_err(|e| e.error)?;
    let tags = parse_tag_suggestions(&raw);
    // The tags are concepts (`spotify:concept:…`): their names and classes from extended-metadata
    // `CONCEPT` (84): `Concept{1: name, 3: image_uri, 4: repeated ConceptClass{1: uri, 2: name},
    // 5: library_class ConceptClass}` ("Genres", "Moods", "Activities").
    let names: Vec<String> = Vec::new();
    let mut manifest: std::collections::HashMap<String, (String, Option<String>)> = Default::default();
    let uris: Vec<String> = tags.iter().map(|t| t.0.clone()).collect();
    if let Ok(found) = metadata::fetch_extension_raw(&session, CONCEPT, &uris).await.map(|p| p.found) {
        for (uri, b) in found {
            let text = |s: &[u8]| String::from_utf8_lossy(s).into_owned();
            let name = fields(&b).into_iter().find(|f| f.0 == 1).map(|f| text(f.2)).unwrap_or_default();
            let class = fields(&b).into_iter().find(|f| f.0 == 5).or_else(|| fields(&b).into_iter().find(|f| f.0 == 4))
                .and_then(|c| fields(c.2).into_iter().find(|f| f.0 == 2)).map(|f| text(f.2));
            manifest.insert(uri.rsplit(':').next().unwrap_or_default().to_string(), (name, class));
        }
    }
    log::info!("libraryTags: tags={} named={} manifest={} sample={}", tags.len(), names.iter().filter(|n| !n.is_empty()).count(), manifest.len(),
        tags.first().map(|t| t.0.rsplitn(2, ':').nth(1).unwrap_or_default().to_string()).unwrap_or_default());
    let mut classes: Vec<(&str, Vec<Value>)> = Vec::new();
    for (i, (uri, _, items, scores)) in tags.iter().enumerate() {
        let id = uri.rsplit(':').next().unwrap_or_default();
        let known = manifest.get(id).or_else(|| manifest.get(uri.as_str()));
        let name = known.map(|k| k.0.clone()).filter(|n| !n.is_empty())
            .or_else(|| names.get(i).filter(|n| !n.is_empty()).cloned())
            .unwrap_or_else(|| id.replace(['-', '_'], " "));
        let class = match known.and_then(|k| k.1.as_deref()) {
            Some(c) if c.to_ascii_lowercase().starts_with("genre") => "Genres",
            Some(c) if c.to_ascii_lowercase().starts_with("mood") => "Moods",
            Some(c) if c.to_ascii_lowercase().starts_with("activit") => "Activities",
            _ => tag_class(uri),
        };
        let tag = json!({ "uri": uri, "name": name, "items": items, "scores": scores });
        match classes.iter_mut().find(|c| c.0 == class) {
            Some(c) => c.1.push(tag),
            None => classes.push((class, vec![tag])),
        }
    }
    // Spotify's order: Genres, Moods, Activities.
    classes.sort_by_key(|c| ["Genres", "Moods", "Activities"].iter().position(|n| *n == c.0).unwrap_or(3));
    Ok(json!({ "classes": classes.into_iter().map(|(n, t)| json!({ "name": n, "tags": t })).collect::<Vec<_>>() }))
}

const SPOTBOT: &str = "/spotbot-api2/spotify.spotbot.v2.promptable_playlist.PromptablePlaylistApi";

/// `playlist.refreshPrompted {uri}`: the prompted playlist's "Update" (new songs for its prompt):
/// `RefreshPromptablePlaylist{1: spotbot_uri, 3: refresh_type MANUAL (1)}`.
pub(crate) async fn refresh_prompted(args: Value) -> AppResult<Value> {
    #[derive(Deserialize)]
    struct Args {
        uri: String,
    }
    let a: Args = parse_args(args)?;
    let mut body = Vec::new();
    append_pb_string(&mut body, 1, &a.uri);
    body.extend([3 << 3, 1]);
    let session = engine::session()?;
    http::spc_send_once(&session, ::http::Method::POST, &format!("{SPOTBOT}/RefreshPromptablePlaylist"), PROTOBUF, PROTOBUF, body)
        .await
        .map_err(|e| e.error)?;
    Ok(json!({}))
}

const AI_CREATION: &str = "/playlist-ai-creation/spotify.playlist.ai.creation.v2.PlaylistAiCreationServiceV2";

/// `playlist.refinePrompted {uri, text}`: Spotify's "Refine" (what to change, in words), as the
/// official client does it: the request goes into the playlist's AI session (format attribute
/// `spotbot_session_id`) with `SubmitMessage{1: session_id, 2: message_prompt}` → `{1: Status{1:
/// action}, 3: message_id, 5: retry_after_ms}`; `GetMessage{1: session_id, 2: message_id}` is
/// polled until its `Status.action` is SUCCESS (4); then the result is attached to the playlist
/// (`AttachRefinementSession{1: spotbot_uri, 2: message_id}` → `{1: refinement_session_id}`) and
/// kept (`SaveRefinementSession{1: spotbot_uri, 2: refinement_message_id}`).
pub(crate) async fn refine(args: Value) -> AppResult<Value> {
    #[derive(Deserialize)]
    struct Args {
        uri: String,
        text: String,
    }
    let a: Args = parse_args(args)?;
    let text = a.text.trim();
    if text.is_empty() {
        return Err(crate::error::AppError::invalid("say what to change"));
    }
    let session = engine::session()?;
    let attrs = super::playlist::format_attributes(&session, &a.uri).await?;
    let session_id = attrs.get("spotbot_session_id").filter(|s| !s.is_empty()).cloned()
        .ok_or_else(|| crate::error::AppError::unavailable("This playlist has no Spotify AI session to refine"))?;
    let post = |path: String, body: Vec<u8>| {
        let session = session.clone();
        async move { http::spc_send_once(&session, ::http::Method::POST, &path, PROTOBUF, PROTOBUF, body).await.map_err(|e| e.error) }
    };
    let text_of = |b: &[u8], n: u32| fields(b).into_iter().find(|f| f.0 == n).map(|f| String::from_utf8_lossy(f.2).into_owned()).unwrap_or_default();
    let action = |b: &[u8]| fields(b).into_iter().find(|f| f.0 == 1).and_then(|s| fields(s.2).into_iter().find(|x| x.0 == 1)).map_or(0, |x| x.1);
    let mut body = Vec::new();
    append_pb_string(&mut body, 1, &session_id);
    append_pb_string(&mut body, 2, text);
    let submitted = post(format!("{AI_CREATION}/SubmitMessage"), body).await?;
    let message_id = text_of(&submitted, 3);
    log::info!("refine: submitted (action {}, message {})", action(&submitted), !message_id.is_empty());
    if message_id.is_empty() {
        return Err(crate::error::AppError::unavailable("Spotify didn't take the refinement"));
    }
    // Wait for the new songs (up to ~90 s).
    let mut done = false;
    for _ in 0..45 {
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
        let mut body = Vec::new();
        append_pb_string(&mut body, 1, &session_id);
        append_pb_string(&mut body, 2, &message_id);
        let Ok(reply) = post(format!("{AI_CREATION}/GetMessage"), body).await else { continue };
        match action(&reply) {
            4 => {
                done = true;
                break;
            }
            3 | 5 => return Err(crate::error::AppError::unavailable("Spotify couldn't refine this playlist")),
            _ => {}
        }
    }
    if !done {
        return Err(crate::error::AppError::unavailable("Spotify is still refining: try Update in a moment"));
    }
    let mut body = Vec::new();
    append_pb_string(&mut body, 1, &a.uri);
    append_pb_string(&mut body, 2, &message_id);
    let attached = post(format!("{SPOTBOT}/AttachRefinementSession"), body.clone()).await;
    log::info!("refine: attached={}", attached.as_ref().map(|b| !text_of(b, 1).is_empty()).unwrap_or(false));
    post(format!("{SPOTBOT}/SaveRefinementSession"), body).await?;
    Ok(json!({}))
}

/// `playlist.editPrompt {uri, prompt?, title?}`: "Edit prompt" / the prompted playlist's name:
/// `EditPromptablePlaylist{1: spotbot_uri, 2: repeated AttributeUpdate{1: prompt | 2: title}}`.
pub(crate) async fn edit_prompt(args: Value) -> AppResult<Value> {
    use super::messaging::append_pb_message;
    #[derive(Deserialize)]
    struct Args {
        uri: String,
        #[serde(default)]
        prompt: Option<String>,
        #[serde(default)]
        title: Option<String>,
    }
    let a: Args = parse_args(args)?;
    let mut body = Vec::new();
    append_pb_string(&mut body, 1, &a.uri);
    for (field, value) in [(1, &a.prompt), (2, &a.title)] {
        if let Some(v) = value.as_deref().map(str::trim).filter(|v| !v.is_empty()) {
            let mut update = Vec::new();
            append_pb_string(&mut update, field, v);
            append_pb_message(&mut body, 2, &update);
        }
    }
    let session = engine::session()?;
    http::spc_send_once(&session, ::http::Method::POST, &format!("{SPOTBOT}/EditPromptablePlaylist"), PROTOBUF, PROTOBUF, body)
        .await
        .map_err(|e| e.error)?;
    Ok(json!({}))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_extender_and_audio_attributes() {
        let reply = json!({ "recommendedTracks": [{ "id": "abc", "name": "Kirra", "album": { "imageUrl": "https://i" },
            "artists": [{ "name": "CharlestheFirst" }], "duration": 198000, "isExplicit": false }] });
        let r = parse_extender(&reply);
        assert_eq!(r[0]["uri"], "spotify:track:abc");
        assert_eq!(r[0]["artists"], "CharlestheFirst");
        assert_eq!(r[0]["image"], "https://i");
        // AudioAttributes{1: 70.0, 2: {1: "D"}, 3: {1: "10A", 2: "#B4A7FF"}}
        let mut b = vec![0x09];
        b.extend(70.0f64.to_bits().to_le_bytes());
        b.extend([0x12, 0x03, 0x0a, 0x01, b'D', 0x1a, 0x0e, 0x0a, 0x03]);
        b.extend(b"10A");
        b.extend([0x12, 0x07]);
        b.extend(b"#B4A7FF");
        let v = parse_audio_attributes(&b);
        assert_eq!(v["bpm"], 70.0);
        assert_eq!(v["key"], "D");
        assert_eq!(v["camelot"], "10A");
        assert_eq!(v["color"], "#B4A7FF");
        // GetSuggestionsResponse{3: Tag{1: "spotify:tag:genre:rock", 2: "spotify:list:x", 3: {1: "spotify:album:a"}}}
        let pb = |f: u8, b: &[u8]| [vec![f << 3 | 2, b.len() as u8], b.to_vec()].concat();
        let tag = [pb(1, b"spotify:tag:genre:rock"), pb(2, b"spotify:list:x"), pb(3, &pb(1, b"spotify:album:a"))].concat();
        let t = parse_tag_suggestions(&pb(3, &tag));
        assert_eq!(t[0].1, "spotify:list:x");
        assert_eq!(t[0].2, vec!["spotify:album:a".to_string()]);
        assert_eq!(tag_class(&t[0].0), "Genres");
    }
}
