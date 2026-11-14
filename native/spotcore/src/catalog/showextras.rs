//! Podcast extras of the Android client: ratings, topics (the show's type tags) and new-episode
//! notifications (the bell). Reversed from the Spotify app:
//!
//! * extended-metadata `PODCAST_RATING` (37): `PodcastRating{1: AverageRating{1: double average,
//!   2: int64 total_ratings, 3: bool show_average}, 2: Rating{3: int32 rating}, 3: bool can_rate}`
//! * extended-metadata `PODCAST_TOPICS` (3): `PodcastTopics{1: repeated PodcastTopic{1: uri,
//!   2: name}}`
//! * `POST|DELETE ratings/v1/rating/show/{showUri}` with `RateRequest{1: int32 rating}`
//! * `GET|PUT|DELETE /newepisodenotifications/v1/optin?s={showId}` (GET answers a JSON boolean)
//! * extended-metadata `ON_PLATFORM_REPUTATION_TRAIT` (185): `{3: total_plays, 4: monthly_listeners,
//!   7: ChartPosition{1: Chart{1: name, 2: uri}, 2: position}, 8: Rating{1: double average,
//!   2: count}, 9: Verification{1: is_registered, 2: is_verified}}` (the header's "#41 in Top
//!   Podcasts ★ 4.8 (12K)" and the verified badge)
//! * extended-metadata `CONSUMPTION_EXPERIENCE_TRAIT` (182): `{3: repeated Genre{1: name, 2: uri}}`
//! * `SHOW_V4` (11) `Show.trailer_uri` (83); "More like this": the list-platform list
//!   `spotify:list:more-like-this:show%3A{id}` (the app builds it with `Uri.encode("show:{id}")`),
//!   the web player's `internalLinkRecommenderShow` as a fallback

use super::http::{self, JSON, PROTOBUF};
use super::metadata;
use crate::engine;
use crate::error::{AppError, AppResult};
use crate::rpc::parse_args;
use ::http::Method;
use serde::Deserialize;
use serde_json::{json, Value};

const PODCAST_TOPICS: i32 = 3;
const PODCAST_RATING: i32 = 37;
const SHOW_V4: i32 = 11;
const CONSUMPTION_EXPERIENCE_TRAIT: i32 = 182;
const ON_PLATFORM_REPUTATION_TRAIT: i32 = 185;
const DISPLAY_SEGMENTS: i32 = 38;

/// Protobuf wire fields of a message: (field, varint value or bytes).
pub(crate) fn fields(mut b: &[u8]) -> Vec<(u32, u64, &[u8])> {
    fn varint(b: &mut &[u8]) -> Option<u64> {
        let mut v = 0u64;
        for shift in (0..64).step_by(7) {
            let (&byte, rest) = b.split_first()?;
            *b = rest;
            v |= u64::from(byte & 0x7f) << shift;
            if byte & 0x80 == 0 {
                return Some(v);
            }
        }
        None
    }
    let mut out = Vec::new();
    while !b.is_empty() {
        let Some(key) = varint(&mut b) else { break };
        let (field, wire) = ((key >> 3) as u32, key & 7);
        match wire {
            0 => match varint(&mut b) {
                Some(v) => out.push((field, v, &[][..])),
                None => break,
            },
            1 if b.len() >= 8 => {
                let v = u64::from_le_bytes(b[..8].try_into().unwrap_or([0; 8]));
                out.push((field, v, &b[..8]));
                b = &b[8..];
            }
            2 => {
                let Some(len) = varint(&mut b) else { break };
                let len = len as usize;
                if len > b.len() {
                    break;
                }
                out.push((field, len as u64, &b[..len]));
                b = &b[len..];
            }
            5 if b.len() >= 4 => {
                out.push((field, u64::from(u32::from_le_bytes(b[..4].try_into().unwrap_or([0; 4]))), &b[..4]));
                b = &b[4..];
            }
            _ => break,
        }
    }
    out
}

pub(crate) fn parse_rating(b: &[u8]) -> Value {
    let (mut average, mut total, mut show, mut mine, mut can_rate) = (0.0f64, 0u64, false, 0u64, false);
    for (f, v, bytes) in fields(b) {
        match f {
            1 => {
                for (g, w, _) in fields(bytes) {
                    match g {
                        1 => average = f64::from_bits(w),
                        2 => total = w,
                        3 => show = w != 0,
                        _ => {}
                    }
                }
            }
            2 => mine = fields(bytes).iter().find(|x| x.0 == 3).map_or(0, |x| x.1),
            3 => can_rate = v != 0,
            _ => {}
        }
    }
    json!({ "average": average, "total": total, "showAverage": show, "mine": mine, "canRate": can_rate })
}

pub(crate) fn parse_topics(b: &[u8]) -> Vec<Value> {
    fields(b)
        .into_iter()
        .filter(|f| f.0 == 1)
        .filter_map(|(_, _, t)| {
            let mut uri = String::new();
            let mut name = String::new();
            for (g, _, s) in fields(t) {
                match g {
                    1 => uri = String::from_utf8_lossy(s).into_owned(),
                    2 => name = String::from_utf8_lossy(s).into_owned(),
                    _ => {}
                }
            }
            (!name.is_empty()).then(|| json!({ "uri": uri, "name": name }))
        })
        .collect()
}

/// `{chart: {name, uri, position}?, rating: {average, total}?, verified}` of a show.
pub(crate) fn parse_reputation(b: &[u8]) -> Value {
    let text = |s: &[u8]| String::from_utf8_lossy(s).into_owned();
    let (mut chart, mut rating, mut verified) = (Value::Null, Value::Null, false);
    for (f, _, v) in fields(b) {
        match f {
            7 => {
                let (mut name, mut uri, mut position) = (String::new(), String::new(), 0);
                for (g, w, s) in fields(v) {
                    match g {
                        1 => for (h, _, t) in fields(s) {
                            match h {
                                1 => name = text(t),
                                2 => uri = text(t),
                                _ => {}
                            }
                        },
                        2 => position = w,
                        _ => {}
                    }
                }
                if position > 0 {
                    chart = json!({ "name": name, "uri": uri, "position": position });
                }
            }
            8 => {
                let r = fields(v);
                let get = |n| r.iter().find(|x| x.0 == n).map_or(0, |x| x.1);
                if get(2) > 0 {
                    rating = json!({ "average": f64::from_bits(get(1)), "total": get(2) });
                }
            }
            9 => verified = fields(v).iter().any(|x| x.0 == 2 && x.1 != 0),
            _ => {}
        }
    }
    json!({ "chart": chart, "rating": rating, "verified": verified })
}

/// A show's genres ("Comedy") from its consumption-experience trait, as topics.
pub(crate) fn parse_genres(b: &[u8]) -> Vec<Value> {
    parse_topics(&fields(b).into_iter().filter(|f| f.0 == 3).flat_map(|(_, _, g)| {
        // Genre{1: name, 2: uri} → the PodcastTopic layout {1: uri, 2: name}.
        let g = fields(g);
        let get = |n| g.iter().find(|x| x.0 == n).map(|x| x.2.to_vec()).unwrap_or_default();
        let (name, uri) = (get(1), get(2));
        let mut t = vec![0x0a, uri.len() as u8];
        t.extend(uri);
        t.extend([0x12, name.len() as u8]);
        t.extend(name);
        let mut out = vec![0x0a, t.len() as u8];
        out.extend(t);
        out
    }).collect::<Vec<u8>>())
}

#[derive(Deserialize)]
struct UriArgs {
    uri: String,
}

fn show_id(uri: &str) -> AppResult<&str> {
    uri.strip_prefix("spotify:show:").filter(|id| id.len() == 22 && id.chars().all(|c| c.is_ascii_alphanumeric()))
        .ok_or_else(|| AppError::invalid("not a show uri"))
}

/// `catalog.showExtras {uri}` → `{rating, topics, notify, chart, verified, trailerUri}` (each null
/// when Spotify didn't say).
pub(crate) async fn extras(args: Value) -> AppResult<Value> {
    let a: UriArgs = parse_args(args)?;
    let id = show_id(&a.uri)?.to_string();
    let session = engine::session()?;
    let uris = [a.uri.clone()];
    let optin = format!("/newepisodenotifications/v1/optin?s={id}");
    let kinds = [PODCAST_RATING, PODCAST_TOPICS, ON_PLATFORM_REPUTATION_TRAIT, CONSUMPTION_EXPERIENCE_TRAIT, SHOW_V4];
    let (raw, notify) = tokio::join!(
        futures_util::future::join_all(kinds.map(|k| metadata::fetch_extension_raw(&session, k, &uris))),
        http::spc_get(&session, &optin, Some(JSON)),
    );
    let raw: Vec<Option<Vec<u8>>> = raw.into_iter().map(|r| r.ok().and_then(|mut p| p.found.remove(&a.uri))).collect();
    let reputation = raw[2].as_deref().map(parse_reputation).unwrap_or_else(|| json!({}));
    // The trait's average and count when the podcast extension has none (Spotify's header uses it).
    let mut rating = raw[0].as_deref().map(parse_rating);
    if let (Some(r), Some(t)) = (rating.as_mut().filter(|r| r["total"] == 0), reputation.get("rating").filter(|t| !t.is_null())) {
        r["average"] = t["average"].clone();
        r["total"] = t["total"].clone();
        r["showAverage"] = json!(true);
    }
    let rating = rating.or_else(|| reputation.get("rating").filter(|t| !t.is_null()).map(|t| json!({ "average": t["average"], "total": t["total"], "showAverage": true })));
    let topics = raw[1].as_deref().map(parse_topics).filter(|t| !t.is_empty()).or_else(|| raw[3].as_deref().map(parse_genres));
    let trailer = raw[4].as_deref().and_then(|b| fields(b).into_iter().find(|f| f.0 == 83)).map(|f| String::from_utf8_lossy(f.2).into_owned());
    let notify = notify.ok().and_then(|b| serde_json::from_slice::<Value>(&b).ok()).and_then(|v| v.as_bool());
    log::info!("showExtras: rating={} topics={} reputation={} trailer={}", rating.is_some(), topics.as_ref().map_or(0, Vec::len), raw[2].is_some(), trailer.is_some());
    Ok(json!({
        "rating": rating, "topics": topics, "notify": notify, "chart": reputation.get("chart"),
        "verified": reputation.get("verified").and_then(Value::as_bool).unwrap_or(false), "trailerUri": trailer,
    }))
}

/// `catalog.verifiedShows {uris}` → `{uris: [...]}`: which shows carry Spotify's verified badge
/// (search results show it after the name).
pub(crate) async fn verified_shows(args: Value) -> AppResult<Value> {
    #[derive(serde::Deserialize)]
    struct A {
        uris: Vec<String>,
    }
    let a: A = parse_args(args)?;
    let uris: Vec<String> = a.uris.into_iter().filter(|u| u.starts_with("spotify:show:")).take(100).collect();
    if uris.is_empty() {
        return Ok(json!({ "uris": [] }));
    }
    let session = engine::session()?;
    let found = metadata::fetch_extension_raw(&session, ON_PLATFORM_REPUTATION_TRAIT, &uris).await.map(|p| p.found).unwrap_or_default();
    let verified: Vec<&String> = found.iter().filter(|(_, b)| parse_reputation(b)["verified"] == true).map(|(u, _)| u).collect();
    Ok(json!({ "uris": verified }))
}

/// `catalog.showRecommendations {uri}` → `{items: [MediaRef]}`: the show page's "More like this".
pub(crate) async fn recommendations(args: Value) -> AppResult<Value> {
    let a: UriArgs = parse_args(args)?;
    let id = show_id(&a.uri)?;
    let session = engine::session()?;
    let list = format!("spotify:list:more-like-this:show%3A{id}");
    let raw = http::spc_post_idempotent(&session, super::recent::LISTS_PATH, PROTOBUF, PROTOBUF, &super::recent::list_request(&list, 0, 30)).await;
    let uris: Vec<String> = raw.as_ref().map(|b| super::recent::parse_recents(b).1).unwrap_or_default().iter()
        .filter_map(|r| r["uri"].as_str().filter(|u| u.starts_with("spotify:show:")).map(str::to_string)).collect();
    log::info!("showRecommendations: list={} shows={}", raw.is_ok(), uris.len());
    if !uris.is_empty() {
        let refs = super::refs::resolve(&session, &uris).await;
        return Ok(json!({ "items": refs }));
    }
    let answer = super::pathfinder::query(&session, "internalLinkRecommenderShow", json!({ "uri": a.uri })).await?;
    let mut out = Vec::new();
    collect_shows(&answer.data, &a.uri, &mut out);
    Ok(json!({ "items": out }))
}

fn collect_shows(v: &Value, own: &str, out: &mut Vec<Value>) {
    match v {
        Value::Object(m) => {
            let uri = m.get("uri").and_then(Value::as_str).unwrap_or_default();
            if uri.starts_with("spotify:show:") && uri != own && m.get("name").is_some() && !out.iter().any(|o| o["uri"] == uri) {
                let image = m.get("coverArt").and_then(|c| c.pointer("/sources/0/url")).cloned().unwrap_or(Value::Null);
                let publisher = m.get("publisher").and_then(|p| p.get("name").or(Some(p))).and_then(Value::as_str).unwrap_or_default();
                let images = if image.is_null() { json!([]) } else { json!([{ "url": image }]) };
                out.push(json!({ "type": "show", "uri": uri, "name": m["name"], "subtitle": publisher, "images": images }));
                return;
            }
            m.values().for_each(|x| collect_shows(x, own, out));
        }
        Value::Array(a) => a.iter().for_each(|x| collect_shows(x, own, out)),
        _ => {}
    }
}

/// `catalog.rateShow {uri, rating}`: 1–5 stars, 0 removes the rating.
pub(crate) async fn rate(args: Value) -> AppResult<Value> {
    #[derive(Deserialize)]
    struct Rate {
        uri: String,
        rating: u8,
    }
    let a: Rate = parse_args(args)?;
    show_id(&a.uri)?;
    let session = engine::session()?;
    let endpoint = format!("/ratings/v1/rating/show/{}", a.uri);
    if a.rating == 0 {
        http::spc_send_once(&session, Method::DELETE, &endpoint, PROTOBUF, PROTOBUF, Vec::new()).await.map_err(|e| e.error)?;
    } else {
        let body = vec![0x08, a.rating.clamp(1, 5)];
        http::spc_send_once(&session, Method::POST, &endpoint, PROTOBUF, PROTOBUF, body).await.map_err(|e| e.error)?;
    }
    Ok(json!({}))
}

/// `catalog.setShowNotify {uri, on}`: the show page's bell (new-episode notifications).
pub(crate) async fn set_notify(args: Value) -> AppResult<Value> {
    #[derive(Deserialize)]
    struct Notify {
        uri: String,
        on: bool,
    }
    let a: Notify = parse_args(args)?;
    let id = show_id(&a.uri)?;
    let session = engine::session()?;
    let method = if a.on { Method::PUT } else { Method::DELETE };
    http::spc_send_once(&session, method, &format!("/newepisodenotifications/v1/optin?s={id}"), JSON, JSON, Vec::new())
        .await
        .map_err(|e| e.error)?;
    Ok(json!({ "notify": a.on }))
}

/// Spotify's people suggestions (the "Add friends" list): `POST speechless/v1/retrieve/sharelist`
/// with an empty `ShareListRequest` → `SharelistResponse{1: repeated SharelistItem{1:
/// SharelistUser{1: username, 3: repeated Reason}}}`.
pub(crate) fn parse_sharelist(b: &[u8]) -> Vec<Value> {
    const REASONS: [&str; 9] = [
        "", "You've shared content with each other", "You collaborate on playlists", "You have a Blend together",
        "You've been in a Jam together", "You have a family plan together", "You follow each other",
        "You've messaged each other", "You partied together",
    ];
    let mut out = Vec::new();
    for (f, _, item) in fields(b) {
        if f != 1 {
            continue;
        }
        for (g, _, user) in fields(item) {
            if g != 1 {
                continue;
            }
            let mut username = String::new();
            let mut reasons = Vec::new();
            for (h, v, bytes) in fields(user) {
                match h {
                    1 => username = String::from_utf8_lossy(bytes).into_owned(),
                    // Unpacked varint, or packed (length-delimited) varints.
                    3 if bytes.is_empty() => reasons.push(v),
                    3 => reasons.extend(bytes.iter().map(|x| u64::from(*x & 0x7f))),
                    _ => {}
                }
            }
            if !username.is_empty() {
                let text: Vec<&str> = reasons.iter().filter_map(|r| REASONS.get(*r as usize).copied()).filter(|t| !t.is_empty()).collect();
                out.push(json!({ "username": username, "reasons": text }));
            }
        }
    }
    out
}

/// `catalog.friendSuggestions` → `{items: [{username, reasons}]}`.
pub(crate) async fn friend_suggestions(_args: Value) -> AppResult<Value> {
    let session = engine::session()?;
    let body = http::spc_send_once(&session, Method::POST, "/speechless/v1/retrieve/sharelist", PROTOBUF, PROTOBUF, Vec::new())
        .await
        .map_err(|e| e.error)?;
    Ok(json!({ "items": parse_sharelist(&body) }))
}

/// `catalog.chapters {uri}` → `{items: [{title, subtitle, startMs, stopMs}]}`: an episode's chapters,
/// extended-metadata `DISPLAY_SEGMENTS` (38): `{1: episode_uri, 2: repeated DisplaySegment{1: uri,
/// 2: type, 3: duration_ms, 4: seek_start_ms, 5: seek_stop_ms, 6: title, 7: subtitle, 8: image_url}}`.
pub(crate) async fn chapters(args: Value) -> AppResult<Value> {
    let a: UriArgs = parse_args(args)?;
    let session = engine::session()?;
    let found = metadata::fetch_extension_raw(&session, DISPLAY_SEGMENTS, std::slice::from_ref(&a.uri)).await?.found;
    let text = |s: &[u8]| String::from_utf8_lossy(s).into_owned();
    let items: Vec<Value> = found.get(&a.uri).map(|b| {
        fields(b).into_iter().filter(|f| f.0 == 2).map(|(_, _, seg)| {
            let s = fields(seg);
            let num = |n: u32| s.iter().find(|x| x.0 == n).map_or(0, |x| x.1);
            let txt = |n: u32| s.iter().find(|x| x.0 == n).map(|x| text(x.2)).unwrap_or_default();
            json!({ "title": txt(6), "subtitle": txt(7), "startMs": num(4), "stopMs": num(5), "durationMs": num(3), "image": image_url(&txt(8)) })
        }).filter(|c| c["title"] != "").collect()
    }).unwrap_or_default();
    log::info!("chapters: {} (image sample: {:?})", items.len(),
        found.get(&a.uri).and_then(|b| fields(b).into_iter().find(|f| f.0 == 2).and_then(|f| fields(f.2).into_iter().find(|x| x.0 == 8)).map(|x| text(x.2).chars().take(48).collect::<String>())));
    Ok(json!({ "items": items }))
}

/// An image reference as a URL: `https://…` as it is, `spotify:image:<id>` or a bare image id
/// (40 hex digits) on Spotify's image CDN.
pub(crate) fn image_url(s: &str) -> String {
    let s = s.trim();
    let id = s.strip_prefix("spotify:image:").unwrap_or(s);
    if s.starts_with("http") {
        s.to_string()
    } else if !id.is_empty() && id.len() >= 32 && id.chars().all(|c| c.is_ascii_hexdigit()) {
        format!("https://i.scdn.co/image/{}", id.to_ascii_lowercase())
    } else {
        String::new()
    }
}

/// `catalog.comments {uri, pageToken?}` → `{total, comments: [{uri, text, author, image, createdAt,
/// reactions, replies, pinned}], next}`: an episode's comments, `CommentsReadHandler/ListComments`
/// (`ListCommentsRequest{1: entity_uri, 2: page_token}` → `{1: number_of_comments, 2: repeated
/// Comment{1: uri, 2: create_date, 3: text, 4: UserAuthorMetadata{1: username, 2: full_name,
/// 3: image}, 5: ShowAuthorMetadata{2: name, 3: image}, 8: reactions, 9: replies, 16: pinned}, 3:
/// next_page_token}`).
pub(crate) async fn comments(args: Value) -> AppResult<Value> {
    use super::messaging::append_pb_string;
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Args {
        uri: String,
        #[serde(default)]
        page_token: Option<String>,
    }
    let a: Args = parse_args(args)?;
    let mut body = Vec::new();
    append_pb_string(&mut body, 1, &a.uri);
    if let Some(t) = a.page_token.as_deref().filter(|t| !t.is_empty()) {
        append_pb_string(&mut body, 2, t);
    }
    let raw = super::social::webgate_request(::http::Method::POST,
        "/spotify.userinteractivityservice.usercomments.v1.CommentsReadHandler/ListComments", body).await?;
    Ok(parse_comments(&raw))
}

/// `catalog.postComment {uri, text}`: comments on an episode as the user,
/// `CommentsWriteHandler/CreateComment` (`CreateCommentRequest{1: entity_uri, 2: comment_str}`).
pub(crate) async fn post_comment(args: Value) -> AppResult<Value> {
    use super::messaging::append_pb_string;
    #[derive(Deserialize)]
    struct Args {
        uri: String,
        text: String,
    }
    let a: Args = parse_args(args)?;
    let text = a.text.trim();
    if text.is_empty() {
        return Err(AppError::invalid("comment must not be empty"));
    }
    let mut body = Vec::new();
    append_pb_string(&mut body, 1, &a.uri);
    append_pb_string(&mut body, 2, text);
    super::social::webgate_request(::http::Method::POST,
        "/spotify.userinteractivityservice.usercomments.v1.CommentsWriteHandler/CreateComment", body).await?;
    Ok(json!({ "ok": true }))
}

/// `catalog.transcript {uri}` → lyrics-shaped `{syncType, lines: [{startTimeMs, words}]}`: an episode's
/// transcript (its subtitles), `GET transcript-read-along/v2/episode/{id}` → `ReadAlongTranscript{6:
/// repeated Section{1: start_ms, 3: TextSentence{1: Sentence{1: start_ms, 2: text}}, 4: Title{1: title}}}`.
pub(crate) async fn transcript(args: Value) -> AppResult<Value> {
    let a: UriArgs = parse_args(args)?;
    let id = a.uri.strip_prefix("spotify:episode:").ok_or_else(|| AppError::invalid("not an episode uri"))?;
    let session = engine::session()?;
    let raw = http::spc_get(&session, &format!("/transcript-read-along/v2/episode/{id}?excludeCC=true&excludeChapters=true"), Some(PROTOBUF))
        .await
        .map_err(|e| e.error)?;
    Ok(json!({ "syncType": "LINE_SYNCED", "lines": parse_transcript(&raw) }))
}

pub(crate) fn parse_transcript(b: &[u8]) -> Vec<Value> {
    let text = |s: &[u8]| String::from_utf8_lossy(s).trim().to_string();
    fields(b).into_iter().filter(|f| f.0 == 6).filter_map(|(_, _, sec)| {
        let s = fields(sec);
        let start = s.iter().find(|x| x.0 == 1).map_or(0, |x| x.1);
        let words = s.iter().find_map(|x| match x.0 {
            3 => fields(x.2).into_iter().find(|y| y.0 == 1).and_then(|y| fields(y.2).into_iter().find(|z| z.0 == 2)).map(|z| text(z.2)),
            4 => fields(x.2).into_iter().find(|y| y.0 == 1).map(|y| text(y.2)),
            _ => None,
        })?;
        (!words.is_empty()).then(|| json!({ "startTimeMs": start, "words": words }))
    }).collect()
}

/// `ListCommentsResponse{1: number_of_comments, 2: repeated CommentListDisplay{1: root_comment
/// Comment, 2: replies}, 3: next_page_token, 4: eligibility, 5: repeated root_comments Comment}`.
pub(crate) fn parse_comments(b: &[u8]) -> Value {
    let text = |s: &[u8]| String::from_utf8_lossy(s).into_owned();
    let (mut total, mut next, mut comments) = (0, String::new(), Vec::new());
    let all = fields(b);
    // The displays' root comments, else the plain root comments (same comments, either list).
    let displayed: Vec<&[u8]> = all.iter().filter(|f| f.0 == 2).filter_map(|f| fields(f.2).into_iter().find(|x| x.0 == 1).map(|x| x.2)).collect();
    let roots: Vec<&[u8]> = if displayed.is_empty() { all.iter().filter(|f| f.0 == 5).map(|f| f.2).collect() } else { displayed };
    for (f, v, s) in all.iter().filter(|f| f.0 != 2 && f.0 != 5).copied().chain(roots.into_iter().map(|r| (2, 0, r))) {
        match f {
            1 => total = v,
            2 => {
                let c = fields(s);
                let num = |n: u32| c.iter().find(|x| x.0 == n).map_or(0, |x| x.1);
                let txt = |n: u32| c.iter().find(|x| x.0 == n).map(|x| text(x.2)).unwrap_or_default();
                let sub = |n: u32, k: u32| c.iter().find(|x| x.0 == n).and_then(|x| fields(x.2).into_iter().find(|y| y.0 == k)).map(|y| text(y.2));
                let author = sub(4, 2).filter(|n| !n.is_empty()).or_else(|| sub(4, 1)).or_else(|| sub(5, 2)).unwrap_or_default();
                comments.push(json!({
                    "uri": txt(1), "text": txt(3), "author": author, "image": sub(4, 3).or_else(|| sub(5, 3)),
                    "createdAt": num(2), "reactions": num(8), "replies": num(9), "pinned": num(16) != 0,
                }));
            }
            3 => next = text(s),
            _ => {}
        }
    }
    json!({ "total": total, "comments": comments, "next": if next.is_empty() { Value::Null } else { json!(next) } })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_transcript() {
        let pb = |f: u8, b: &[u8]| [vec![f << 3 | 2, b.len() as u8], b.to_vec()].concat();
        // Section{1: 1500, 3: TextSentence{1: Sentence{1: 1500, 2: "Hello there"}}}, Section{1: 0, 4: Title{1: "Intro"}}
        let sentence = [vec![0x08, 0xdc, 0x0b], pb(2, b"Hello there")].concat();
        let a = [vec![0x08, 0xdc, 0x0b], pb(3, &pb(1, &sentence))].concat();
        let b = [vec![0x08, 0x00], pb(4, &pb(1, b"Intro"))].concat();
        let lines = parse_transcript(&[pb(1, b"v"), pb(6, &b), pb(6, &a)].concat());
        assert_eq!(lines.len(), 2);
        assert_eq!(lines[0]["words"], "Intro");
        assert_eq!(lines[1]["startTimeMs"], 1500);
        assert_eq!(lines[1]["words"], "Hello there");
    }

    #[test]
    fn parses_rating_and_topics() {
        // AverageRating{1: 4.5, 2: 1234, 3: true}
        let mut avg = vec![0x09];
        avg.extend(4.5f64.to_bits().to_le_bytes());
        avg.extend([0x10, 0xd2, 0x09, 0x18, 0x01]);
        let mut b = vec![0x0a, avg.len() as u8];
        b.extend(&avg);
        b.extend([0x12, 0x02, 0x18, 0x04, 0x18, 0x01]);
        let v = parse_rating(&b);
        assert_eq!(v["average"], 4.5);
        assert_eq!(v["total"], 1234);
        assert_eq!(v["mine"], 4);
        assert_eq!(v["canRate"], true);
        // PodcastTopics{1: {1: "spotify:genre:x", 2: "Comedy"}}
        let mut t = vec![0x0a, 0x0f];
        t.extend(b"spotify:genre:x");
        t.extend([0x12, 0x06]);
        t.extend(b"Comedy");
        let mut topics = vec![0x0a, t.len() as u8];
        topics.extend(&t);
        assert_eq!(parse_topics(&topics)[0]["name"], "Comedy");
        // SharelistResponse{1: {1: {1: "ann", 3: [5, 6]}}}
        let user = [0x0a, 0x03, b'a', b'n', b'n', 0x1a, 0x02, 0x05, 0x06];
        let mut item = vec![0x0a, user.len() as u8];
        item.extend(user);
        let mut resp = vec![0x0a, item.len() as u8];
        resp.extend(item);
        let s = parse_sharelist(&resp);
        assert_eq!(s[0]["username"], "ann");
        assert_eq!(s[0]["reasons"][0], "You have a family plan together");
        assert_eq!(s[0]["reasons"][1], "You follow each other");
        // ON_PLATFORM_REPUTATION_TRAIT{7: {1: {1: "Top Podcasts"}, 2: 41}, 8: {1: 4.8, 2: 9000}, 9: {2: true}}
        let mut chart = vec![0x0a, 0x0e, 0x0a, 0x0c];
        chart.extend(b"Top Podcasts");
        chart.extend([0x10, 41]);
        let mut rep = vec![0x3a, chart.len() as u8];
        rep.extend(&chart);
        let mut r = vec![0x09];
        r.extend(4.8f64.to_bits().to_le_bytes());
        r.extend([0x10, 0xa8, 0x46]);
        rep.extend([0x42, r.len() as u8]);
        rep.extend(&r);
        rep.extend([0x4a, 0x02, 0x10, 0x01]);
        let v = parse_reputation(&rep);
        assert_eq!(v["chart"]["position"], 41);
        assert_eq!(v["chart"]["name"], "Top Podcasts");
        assert_eq!(v["rating"]["total"], 9000);
        assert_eq!(v["verified"], true);
        // CONSUMPTION_EXPERIENCE_TRAIT{3: Genre{1: "Comedy", 2: "spotify:genre:c"}}
        let mut g = vec![0x0a, 0x06];
        g.extend(b"Comedy");
        g.extend([0x12, 0x0f]);
        g.extend(b"spotify:genre:c");
        let mut c = vec![0x1a, g.len() as u8];
        c.extend(g);
        assert_eq!(parse_genres(&c)[0]["name"], "Comedy");
        assert_eq!(parse_genres(&c)[0]["uri"], "spotify:genre:c");
        // ListCommentsResponse{1: 266, 2: CommentListDisplay{1: Comment{3: "hi", 4: {2: "Ann"}, 8: 5}}, 3: "next"}
        let comment = [vec![0x1a, 0x02], b"hi".to_vec(), vec![0x22, 0x05, 0x12, 0x03], b"Ann".to_vec(), vec![0x40, 0x05]].concat();
        let display = [vec![0x0a, comment.len() as u8], comment.clone()].concat();
        let resp = [vec![0x08, 0x8a, 0x02, 0x12, display.len() as u8], display, vec![0x1a, 0x04], b"next".to_vec()].concat();
        assert_eq!(parse_comments(&[vec![0x2a, comment.len() as u8], comment].concat())["comments"][0]["text"], "hi");
        let c = parse_comments(&resp);
        assert_eq!(c["total"], 266);
        assert_eq!(c["comments"][0]["author"], "Ann");
        assert_eq!(c["comments"][0]["reactions"], 5);
        assert_eq!(c["next"], "next");
    }
}
