//! `catalog.user` via spclient `user-profile-view/v3` (display name, image and, for other users,
//! their public playlists); product, country and explicit filter come from the session
//! attributes for the logged-in user (whose playlists come from the rootlist instead).

use super::http::{self, JSON};
use super::util::{encode_component, normalize_images, parse_kind, UriKind};
use crate::engine;
use crate::error::{AppError, AppResult};
use crate::models::{Image, PlaylistOwner, PlaylistRef, User};
use crate::rpc::{parse_args, to_value};
use futures_util::stream::{self, StreamExt};
use bytes::Bytes;
use base64::Engine as _;
use ::http::{Method, Request};
use librespot_core::Session;
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

/// Public playlists requested for another user's profile.
const PUBLIC_PLAYLISTS: u32 = 50;
/// Most profiles [`resolve_names`] fetches in one call (the rest follow on later calls).
const NAME_LOOKUPS: usize = 30;

/// Display names by username, for the process (`None`: the profile has none). playlist4 names
/// owners by bare username, which for newer accounts is a random id.
static NAMES: LazyLock<Mutex<HashMap<String, User>>> = LazyLock::new(Default::default);

#[derive(Deserialize, Default)]
struct Args {
    #[serde(default)]
    username: Option<String>,
}

/// `catalog.user` result: `User` plus `publicPlaylists` (other users only, omitted when empty).
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Profile {
    #[serde(flatten)]
    pub user: User,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub public_playlists: Vec<PlaylistRef>,
}

/// An image reference of the profile API: an https URL, `spotify:image:<hex>` or
/// `spotify:mosaic:<hex>:<hex>…` (the generated cover of a playlist).
pub(super) fn image_url(s: &str) -> Option<String> {
    if s.starts_with("https://") {
        return Some(s.to_string());
    }
    let hex_ok = |h: &str| !h.is_empty() && h.chars().all(|c| c.is_ascii_hexdigit());
    if let Some(id) = s.strip_prefix("spotify:image:").filter(|h| hex_ok(h)) {
        return Some(format!("https://i.scdn.co/image/{id}"));
    }
    let tiles: Vec<&str> = s.strip_prefix("spotify:mosaic:")?.split(':').collect();
    tiles.iter().all(|t| hex_ok(t)).then(|| format!("https://mosaic.scdn.co/640/{}", tiles.concat()))
}

/// `public_playlists[]` of a profile response (entries without a playlist URI or name skipped).
fn parse_public_playlists(v: &Value) -> Vec<PlaylistRef> {
    let Some(list) = v.get("public_playlists").or_else(|| v.get("publicPlaylists")).and_then(Value::as_array) else {
        return Vec::new();
    };
    let str_of = |p: &Value, keys: &[&str]| keys.iter().find_map(|k| p.get(*k).and_then(Value::as_str)).map(str::to_string);
    list.iter()
        .filter_map(|p| {
            let uri = parse_kind(&str_of(p, &["uri"])?, UriKind::Playlist)?.uri();
            let name = str_of(p, &["name"]).filter(|n| !n.is_empty())?;
            let images = str_of(p, &["image_url", "imageUrl"])
                .and_then(|u| image_url(&u))
                .map(|url| vec![Image { url, width: None, height: None }])
                .unwrap_or_default();
            let owner = str_of(p, &["owner_uri", "ownerUri"])
                .and_then(|u| u.strip_prefix("spotify:user:").map(str::to_string))
                .filter(|u| !u.is_empty())
                .map(|username| PlaylistOwner { username, display_name: str_of(p, &["owner_name", "ownerName"]).filter(|n| !n.is_empty()) });
            Some(PlaylistRef { uri, name, images: normalize_images(images), owner, ..Default::default() })
        })
        .collect()
}

/// Display name, images and public playlists from a profile response (lenient about field names).
pub(crate) fn parse_profile(body: &[u8], username: &str) -> AppResult<Profile> {
    let v: Value = http::json(body)?;
    let str_of = |keys: &[&str]| keys.iter().find_map(|k| v.get(*k).and_then(Value::as_str)).map(str::to_string);
    let display_name = str_of(&["name", "display_name", "displayName"]).filter(|n| !n.is_empty());
    let mut images = Vec::new();
    for key in ["image_url", "imageUrl", "large_image_url", "largeImageUrl"] {
        if let Some(url) = v.get(key).and_then(Value::as_str).filter(|u| u.starts_with("https://")) {
            images.push(Image { url: url.to_string(), width: None, height: None });
        }
    }
    if let Some(arr) = v.get("images").and_then(Value::as_array) {
        for i in arr {
            if let Some(url) = i.get("url").and_then(Value::as_str) {
                let dim = |k: &str| i.get(k).and_then(Value::as_u64).map(|n| n as u32);
                images.push(Image { url: url.to_string(), width: dim("width"), height: dim("height") });
            }
        }
    }
    let username = v
        .get("uri")
        .and_then(Value::as_str)
        .and_then(|u| u.strip_prefix("spotify:user:"))
        .map(str::to_string)
        .unwrap_or_else(|| username.to_string());
    let user = User {
        username, display_name, images: normalize_images(images),
        followers_count: v.get("followers_count").or_else(|| v.get("followersCount")).and_then(Value::as_u64).and_then(|n| u32::try_from(n).ok()),
        following_count: v.get("following_count").or_else(|| v.get("followingCount")).and_then(Value::as_u64).and_then(|n| u32::try_from(n).ok()),
        ..Default::default()
    };
    Ok(Profile { user, public_playlists: parse_public_playlists(&v) })
}

/// The cached display name of `username` (see [`resolve_names`]).
pub(crate) fn cached_name(username: &str) -> Option<String> {
    NAMES.lock().get(username).and_then(|u| u.display_name.clone())
}

/// The cached profile of `username` (name and avatar), or a bare user (see [`resolve_names`]).
pub(crate) fn cached_user(username: &str) -> User {
    NAMES.lock().get(username).cloned().unwrap_or_else(|| User { username: username.to_string(), ..Default::default() })
}

/// Fetches the profiles of the `usernames` not cached yet, so [`cached_name`] knows them.
pub(crate) async fn resolve_names<'a>(session: &Session, usernames: impl IntoIterator<Item = &'a str>) {
    let missing: HashSet<String> = {
        let names = NAMES.lock();
        usernames.into_iter().filter(|u| !u.is_empty() && *u != "spotify" && !names.contains_key(*u)).map(str::to_string).collect()
    };
    let _: Vec<_> = stream::iter(missing.into_iter().take(NAME_LOOKUPS))
        .map(|u| async move { profile(session, &u, 0).await })
        .buffer_unordered(6)
        .collect()
        .await;
}

/// A profile from `user-profile-view/v3`; its display name is cached for [`cached_name`].
async fn profile(session: &Session, username: &str, playlist_limit: u32) -> AppResult<Profile> {
    let endpoint = format!(
        "/user-profile-view/v3/profile/{}?playlist_limit={playlist_limit}&artist_limit=0",
        encode_component(username)
    );
    let p = parse_profile(&http::spc_get(session, &endpoint, Some(JSON)).await?, username)?;
    NAMES.lock().insert(username.to_string(), p.user.clone());
    Ok(p)
}

// Minimal, independent protobuf field decoder for the Premium account-management response.
// Never infer the plan from generic account product or from the available-plan list.
fn proto_varint(data: &[u8], at: &mut usize) -> Option<u64> {
    let mut out = 0_u64;
    for shift in (0..=63).step_by(7) {
        let byte = *data.get(*at)?;
        *at += 1;
        if shift == 63 && byte > 1 { return None; }
        out |= u64::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 { return Some(out); }
    }
    None
}

fn proto_field<'a>(data: &'a [u8], wanted: u64) -> Option<&'a [u8]> {
    let mut at = 0;
    while at < data.len() {
        let key = proto_varint(data, &mut at)?;
        let number = key >> 3;
        if number == 0 { return None; }
        match key & 7 {
            0 => { proto_varint(data, &mut at)?; }
            1 => { at = at.checked_add(8)?; if at > data.len() { return None; } }
            2 => {
                let len = usize::try_from(proto_varint(data, &mut at)?).ok()?;
                let end = at.checked_add(len)?;
                let bytes = data.get(at..end)?;
                if number == wanted { return Some(bytes); }
                at = end;
            }
            5 => { at = at.checked_add(4)?; if at > data.len() { return None; } }
            _ => return None,
        }
    }
    None
}

/// The tier of Spotify's own menu badge (`GetYourPremiumBadgeResponse`): field 1 is the short plan
/// name, field 3 the badge style (`Premium`, `PremiumDuo`, `PremiumStudent`, `PremiumFamily`, each
/// also `...Alternate` / `...Experimental`). The style decides; plain `Premium` is Individual.
/// The premium plan from the account's own attributes: `multiuserplan-member-type` (FAMILY_MEMBER,
/// ...; absent on single-user plans) and the rate plan in `financial-product`
/// (`pr:premium,...,rt:v2_US_default_new-family-sub-1m_...`). Other premium plans are Individual.
pub(crate) fn plan_of(session: &Session) -> Option<String> {
    let hint = ["multiuserplan-member-type", "financial-product"]
        .iter()
        .filter_map(|key| session.get_user_attribute(key))
        .collect::<Vec<_>>()
        .join(" ")
        .to_ascii_lowercase();
    ["family", "duo", "student"].into_iter().find(|t| hint.contains(t)).map(str::to_owned)
        .or_else(|| hint.contains("pr:premium").then(|| "individual".to_owned()))
}

fn premium_plan_from_response(data: &[u8]) -> Option<String> {
    // Field numbers are not confirmed, so every text in the response (nested too) is considered.
    let mut texts = Vec::new();
    proto_strings(data, &mut texts, 0);
    //log::info!("[premium-plan] badge texts={texts:?}");
    let lower: Vec<String> = texts.iter().map(|t| t.to_ascii_lowercase()).collect();
    let tier = ["family", "duo", "student", "individual"].into_iter().find(|t| lower.iter().any(|s| s.contains(t)));
    tier.map(str::to_owned).or_else(|| lower.iter().any(|s| s.starts_with("premium")).then(|| "individual".to_owned()))
}

/// Every length-delimited field that is readable text, descending into nested messages.
fn proto_strings(data: &[u8], out: &mut Vec<String>, depth: u8) -> bool {
    let mut at = 0;
    while at < data.len() {
        let Some(key) = proto_varint(data, &mut at) else { return false };
        match key & 7 {
            0 => if proto_varint(data, &mut at).is_none() { return false },
            1 => at += 8,
            5 => at += 4,
            2 => {
                let Some(len) = proto_varint(data, &mut at).and_then(|l| usize::try_from(l).ok()) else { return false };
                let Some(bytes) = at.checked_add(len).and_then(|end| data.get(at..end)) else { return false };
                at += len;
                let mut nested = Vec::new();
                if depth < 4 && !bytes.is_empty() && proto_strings(bytes, &mut nested, depth + 1) && !nested.is_empty() {
                    out.extend(nested);
                } else if let Ok(s) = std::str::from_utf8(bytes) {
                    if !s.is_empty() && s.chars().all(|c| !c.is_control()) { out.push(s.to_owned()); }
                }
            }
            _ => return false,
        }
    }
    at == data.len()
}

/// POST to a Webgate (spclient.wg) endpoint with the session's tokens; None when it fails (logged).
async fn webgate_post(session: &Session, url: &str, body: Bytes) -> Option<Bytes> {
    let token = session.login5().auth_token().await.map_err(|e| log::info!("[webgate] token unavailable: {e:?}")).ok()?;
    let mut builder = Request::builder()
        .method(Method::POST)
        .uri(url)
        .header("Content-Type", "application/protobuf")
        .header("Accept", "application/json, application/protobuf")
        .header("Authorization", format!("{} {}", token.token_type, token.access_token));
    if let Ok(client_token) = session.spclient().client_token().await {
        builder = builder.header("client-token", client_token);
    }
    let request = builder.body(body).map_err(|e| log::warn!("[webgate] request: {e}")).ok()?;
    http::send(Some(session), request, http::TIMEOUT)
        .await
        .map_err(|e| log::info!("[webgate] {url}: status={:?} error={:?}", e.status, e.error))
        .ok()
}

async fn premium_plan_lookup(session: &Session) -> Option<String> {
    // The endpoint Spotify's app reads its "Your Premium" badge from (an empty request).
    let body = webgate_post(session, "https://spclient.wg.spotify.com/pam-view-service/v2/yourpremiumbadge", Bytes::new()).await?;
    premium_plan_from_response(&body)
}

/// Encode a protobuf length-delimited field without an additional protobuf dependency.
fn profile_proto_field(out: &mut Vec<u8>, field: u64, value: &[u8]) {
    fn varint(out: &mut Vec<u8>, mut n: u64) {
        while n >= 0x80 { out.push((n as u8) | 0x80); n >>= 7; }
        out.push(n as u8);
    }
    varint(out, field << 3 | 2);
    varint(out, value.len() as u64);
    out.extend_from_slice(value);
}

/// Account mutation backed by Spotify's identity/v3 update API. No local-only changes.
/// Identity$UserProfileUpdateRequest: mask=1, user_profile=2.
/// Identity$UserProfile: name=2 (google.protobuf.StringValue).
pub(crate) async fn update_name(args: Value) -> AppResult<Value> {
    #[derive(Deserialize)]
    struct Rename { name: String }
    let Rename { name } = parse_args(args)?;
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 100 || name.chars().any(char::is_control) {
        return Err(AppError::invalid("Display name must contain 1–100 printable characters"));
    }
    let session = engine::session()?;
    let username = engine::username().unwrap_or_else(|| session.username());
    if username.is_empty() { return Err(AppError::invalid("Not signed in")); }

    // google.protobuf.FieldMask { paths: ["name"] }
    let mut mask = Vec::new();
    profile_proto_field(&mut mask, 1, b"name");
    // google.protobuf.StringValue { value: name }
    let mut wrapped_name = Vec::new();
    profile_proto_field(&mut wrapped_name, 1, name.as_bytes());
    let mut updated_profile = Vec::new();
    profile_proto_field(&mut updated_profile, 2, &wrapped_name);
    let mut body = Vec::new();
    profile_proto_field(&mut body, 1, &mask);
    profile_proto_field(&mut body, 2, &updated_profile);

    // Identity interface p.cv50.i() uses the Webgate service android-profile-identity.
    // Do NOT retry this POST: an account write is not idempotent by definition.
    let token = session.login5().auth_token().await?;
    let url = format!("https://spclient.wg.spotify.com/identity/v3/user/username/{}", encode_component(&username));
    let mut builder = Request::builder().method(Method::POST).uri(url)
        .header("Content-Type", http::PROTOBUF)
        .header("Accept", http::PROTOBUF)
        .header("Authorization", format!("{} {}", token.token_type, token.access_token));
    if let Ok(ct) = session.spclient().client_token().await { builder = builder.header("client-token", ct); }
    let request = builder.body(Bytes::from(body)).map_err(|e| AppError::invalid(format!("Invalid profile update request: {e}")))?;
    http::send(Some(&session), request, http::TIMEOUT).await.map_err(AppError::from)?;

    // A successful HTTP status alone is not proof of persisted account state.
    let confirmed = profile(&session, &username, 0).await?;
    if confirmed.user.display_name.as_deref() != Some(name) {
        return Err(AppError::invalid("Spotify accepted the request but did not return the updated display name; please refresh and verify"));
    }
    NAMES.lock().insert(username, confirmed.user.clone());
    to_value(&confirmed.user)
}

/// Upload a cropped JPEG to Spotify's image-upload host, then associate its upload token
/// through the identity service. Never claim success until a fresh profile confirms the avatar.
pub(crate) async fn update_photo(args: Value) -> AppResult<Value> {
    #[derive(Deserialize)] struct Photo { #[serde(rename = "jpegBase64")] jpeg_base64: String }
    let Photo { jpeg_base64 } = parse_args(args)?;
    if jpeg_base64.len() > 8_000_000 { return Err(AppError::invalid("Profile photo too large")); }
    let jpeg = base64::engine::general_purpose::STANDARD.decode(&jpeg_base64)
        .map_err(|_| AppError::invalid("Invalid profile photo encoding"))?;
    if jpeg.len() < 4 || !jpeg.starts_with(&[0xff, 0xd8, 0xff]) || jpeg.len() > 6_000_000 {
        return Err(AppError::invalid("Expected a JPEG under 6 MB"));
    }
    let session = engine::session()?;
    let username = engine::username().unwrap_or_else(|| session.username());
    if username.is_empty() { return Err(AppError::invalid("Not signed in")); }
    let previous = profile(&session, &username, 0).await?.user.images;
    let token = session.login5().auth_token().await?;
    let mut request = Request::builder().method(Method::POST)
        .uri("https://image-upload.spotify.com/v4/user-profile")
        .header("Content-Type", "image/jpeg")
        .header("Accept", "application/json")
        .header("Authorization", format!("{} {}", token.token_type, token.access_token));
    if let Ok(ct) = session.spclient().client_token().await { request = request.header("client-token", ct); }
    let request = request.body(Bytes::from(jpeg))
        .map_err(|e| AppError::invalid(format!("Invalid image upload: {e}")))?;
    let response = http::send(Some(&session), request, http::TIMEOUT).await.map_err(AppError::from)?;
    let json: Value = http::json(&response)?;
    let upload_token = json.get("uploadToken").and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| AppError::invalid("Spotify image upload returned no uploadToken"))?;
    let url = format!("https://spclient.wg.spotify.com/identity/v2/profile-image/{}/{}",
        encode_component(&username), encode_component(upload_token));
    let mut request = Request::builder().method(Method::POST).uri(url)
        .header("Authorization", format!("{} {}", token.token_type, token.access_token));
    if let Ok(ct) = session.spclient().client_token().await { request = request.header("client-token", ct); }
    let request = request.body(Bytes::new())
        .map_err(|e| AppError::invalid(format!("Invalid image registration: {e}")))?;
    http::send(Some(&session), request, http::TIMEOUT).await.map_err(AppError::from)?;
    let fresh = profile(&session, &username, 0).await?;
    if fresh.user.images.is_empty() || fresh.user.images == previous {
        return Err(AppError::invalid("Spotify accepted the image but the account avatar has not yet changed; refresh your profile"));
    }
    to_value(&fresh.user)
}

pub(crate) async fn remove_photo(_args: Value) -> AppResult<Value> {
    let session = engine::session()?;
    let username = engine::username().unwrap_or_else(|| session.username());
    if username.is_empty() { return Err(AppError::invalid("Not signed in")); }
    let token = session.login5().auth_token().await?;
    let url = format!("https://spclient.wg.spotify.com/identity/v2/profile-image/{}", encode_component(&username));
    let mut request = Request::builder().method(Method::DELETE).uri(url)
        .header("Authorization", format!("{} {}", token.token_type, token.access_token));
    if let Ok(ct) = session.spclient().client_token().await { request = request.header("client-token", ct); }
    let request = request.body(Bytes::new())
        .map_err(|e| AppError::invalid(format!("Invalid image deletion: {e}")))?;
    http::send(Some(&session), request, http::TIMEOUT).await.map_err(AppError::from)?;
    let fresh = profile(&session, &username, 0).await?;
    if !fresh.user.images.is_empty() {
        return Err(AppError::invalid("Spotify has not yet confirmed the profile photo removal; refresh your profile"));
    }
    to_value(&fresh.user)
}

/// Every text in a JSON or protobuf response.
fn response_texts(body: &[u8]) -> Vec<String> {
    fn walk(v: &Value, out: &mut Vec<String>) {
        match v {
            Value::String(s) => out.push(s.clone()),
            Value::Array(a) => a.iter().for_each(|v| walk(v, out)),
            Value::Object(o) => o.values().for_each(|v| walk(v, out)),
            _ => {}
        }
    }
    let mut out = Vec::new();
    match serde_json::from_slice::<Value>(body) {
        Ok(v) => walk(&v, &mut out),
        Err(_) => { proto_strings(body, &mut out, 0); }
    }
    out
}

/// `blend.invite`: a new Blend invitation (`blend-invitation/v1/generate`, as Spotify's app); `{link}`.
pub(crate) async fn blend_invite(_args: Value) -> AppResult<Value> {
    let session = engine::session()?;
    let body = webgate_post(&session, "https://spclient.wg.spotify.com/blend-invitation/v1/generate", Bytes::new())
        .await
        .ok_or_else(|| AppError::invalid("could not create a Blend invite"))?;
    let texts = response_texts(&body);
    //log::info!("[blend] invite texts={texts:?}");
    let link = texts.iter().find(|t| t.starts_with("https://") && t.contains("blend")).cloned()
        .or_else(|| texts.iter().find(|t| t.len() >= 8 && t.chars().all(|c| c.is_ascii_alphanumeric()))
            .map(|token| format!("https://open.spotify.com/blend/taste-match/{token}")))
        .ok_or_else(|| AppError::invalid("unexpected Blend invite response"))?;
    Ok(serde_json::json!({ "link": link }))
}

pub(crate) async fn rpc(args: Value) -> AppResult<Value> {
    let a: Args = if args.is_null() { Args::default() } else { parse_args(args)? };
    let session = engine::session()?;
    let me = engine::username().unwrap_or_else(|| session.username());
    let username = a.username.filter(|u| !u.is_empty()).unwrap_or_else(|| me.clone());
    if username.is_empty() {
        return Err(AppError::invalid("no username"));
    }
    let is_me = username.eq_ignore_ascii_case(&me);
    // My own playlists come from the rootlist; other users' public ones only from here.
    let playlist_limit = if is_me { 0 } else { PUBLIC_PLAYLISTS };
    let mut profile = match profile(&session, &username, playlist_limit).await {
        Ok(p) => p,
        Err(e) if is_me => {
            log::info!("own profile lookup failed: {e}");
            Profile { user: User { username: username.clone(), ..Default::default() }, public_playlists: Vec::new() }
        }
        Err(e) => return Err(e),
    };
    if is_me {
        let user = &mut profile.user;
        user.product = session.get_user_attribute("type");
        // Diagnostic: every product state value (listening activity lives in one of them).
        //let mut attrs: Vec<_> = session.user_data().attributes.into_iter().collect();
        //attrs.sort();
        //log::info!("[attributes] all: {attrs:?}");
        user.plan_type = plan_of(&session);
        //log::info!(
        //    "[premium-plan] product={:?} plan_type={:?}",
        //    user.product,
        //    user.plan_type
        //);
        user.country = Some(session.country()).filter(|c| !c.is_empty());
        if user.plan_type.is_none() && user.product.as_deref() == Some("premium") {
            user.plan_type = premium_plan_lookup(&session).await;
        }
        user.explicit_filter = session.filter_explicit_content();
        profile.public_playlists.clear();
    }
    to_value(&profile)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_profile() {
        let body = br#"{"uri":"spotify:user:alice","name":"Alice A.","image_url":"https://i.scdn.co/image/ab6775700000ee85aaaa",
            "followers_count":3,"following_count":4,"is_following":false,"recently_played_artists":[],"public_playlists":[],
            "has_spotify_name":true,"has_spotify_image":true,"color":-123,"allow_follows":true,"show_follows":true}"#;
        let p = parse_profile(body, "fallback").unwrap();
        let u = &p.user;
        assert_eq!(u.username, "alice");
        assert_eq!(u.display_name.as_deref(), Some("Alice A."));
        assert_eq!(u.images.len(), 1);
        assert!(p.public_playlists.is_empty());
        let v = serde_json::to_value(&p).unwrap();
        assert_eq!(v["username"], "alice", "User fields are flattened");
        assert!(v.get("publicPlaylists").is_none(), "omitted when empty");
        let p = parse_profile(br#"{"name":""}"#, "bob").unwrap();
        assert_eq!(p.user.username, "bob");
        assert!(p.user.display_name.is_none() && p.user.images.is_empty());
        assert!(parse_profile(b"<", "x").is_err());
    }

    #[test]
    fn parses_public_playlists() {
        let body = br#"{"uri":"spotify:user:alice","name":"Alice","public_playlists":[
            {"uri":"spotify:playlist:37i9dQZF1DXcBWIGoYBM5M","name":"Road trip","image_url":"https://i.scdn.co/image/ab67706c0000da84aaaa",
             "followers_count":12,"owner_name":"Alice A.","owner_uri":"spotify:user:alice","is_following":false},
            {"uri":"spotify:playlist:5ihSl7a56tjMkVSzwQpSnl","name":"Mix","image_url":"spotify:mosaic:ab67616d0000b273aa:ab67616d0000b273bb",
             "owner_name":"","owner_uri":"spotify:user:alice"},
            {"uri":"spotify:playlist:1yQ6yj6Gyd1kkMqk4YaRRd","name":"Cover","image_url":"spotify:image:ab67706c0000bebbcc"},
            {"uri":"spotify:track:4uLU6hMCjMI75M1A2tKUQC","name":"not a playlist"},
            {"uri":"spotify:playlist:2UZk7JjJnbTut1w8fqs3JL","name":""}
        ]}"#;
        let p = parse_profile(body, "alice").unwrap();
        assert_eq!(p.public_playlists.len(), 3);
        let road = &p.public_playlists[0];
        assert_eq!(road.uri, "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M");
        assert_eq!(road.images[0].url, "https://i.scdn.co/image/ab67706c0000da84aaaa");
        let owner = road.owner.as_ref().unwrap();
        assert_eq!((owner.username.as_str(), owner.display_name.as_deref()), ("alice", Some("Alice A.")));
        assert_eq!(p.public_playlists[1].images[0].url, "https://mosaic.scdn.co/640/ab67616d0000b273aaab67616d0000b273bb");
        assert!(p.public_playlists[1].owner.as_ref().unwrap().display_name.is_none());
        assert_eq!(p.public_playlists[2].images[0].url, "https://i.scdn.co/image/ab67706c0000bebbcc");
        assert!(p.public_playlists[2].owner.is_none());
        let v = serde_json::to_value(&p).unwrap();
        assert_eq!(v["publicPlaylists"][0]["name"], "Road trip");
        assert_eq!(image_url("spotify:image:not-hex"), None);
        assert_eq!(image_url("http://insecure"), None);
    }
}
