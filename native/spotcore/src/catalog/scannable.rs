//! Read-only Spotify Code reference resolver. No credentials are returned or logged.
//! The expected `target` response shape is provisional until tested on a live account.

use super::http;
use crate::engine;
use crate::error::{AppError, AppResult, ErrorCode};
use crate::rpc::to_value;
use bytes::Bytes;
use ::http::{Method, Request};
use serde::Deserialize;
use serde_json::Value;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Args {
    media_reference: String,
}

fn valid_reference(input: &str) -> bool {
    !input.is_empty() && input.len() <= 20 && input.bytes().all(|b| b.is_ascii_digit())
}

fn parse_target(body: &[u8]) -> AppResult<String> {
    let document: Value = http::json(body)?;
    let target = document.get("target").and_then(Value::as_str)
        .ok_or_else(|| AppError::new(ErrorCode::Unavailable, "Scannable response has no target"))?;
    let mut parts = target.split(':');
    let service = parts.next();
    let kind = parts.next();
    let identifier = parts.next();
    if service != Some("spotify")
        || !matches!(kind, Some("track" | "album" | "playlist" | "artist" | "show" | "episode" | "user"))
        || identifier.is_none_or(str::is_empty)
        || parts.next().is_some()
        || !identifier.unwrap_or_default().bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
    {
        return Err(AppError::new(ErrorCode::Unavailable, "Scannable target is not a supported Spotify URI"));
    }
    Ok(target.to_owned())
}

/// Input: `{ "mediaReference": "103490524832" }`.
/// Output on success: `{ "mediaReference": "...", "uri": "spotify:track:..." }`.
/// Does not log the bearer token, client-token, or response body.
pub(super) async fn resolve(args: Value) -> AppResult<Value> {
    let args: Args = serde_json::from_value(args)
        .map_err(|e| AppError::invalid(format!("resolveScannable arguments: {e}")))?;
    if !valid_reference(&args.media_reference) {
        return Err(AppError::invalid("mediaReference must be 1-20 ASCII digits"));
    }
    let session = engine::session()?;
    let token = session.login5().auth_token().await?;
    let url = format!(
        "https://spclient.wg.spotify.com/scannable-id/id/{}?format=json",
        args.media_reference
    );
    let mut builder = Request::builder()
        .method(Method::GET)
        .uri(url)
        .header("Accept", "application/json")
        .header("Authorization", format!("{} {}", token.token_type, token.access_token));
    if let Ok(client_token) = session.spclient().client_token().await {
        builder = builder.header("client-token", client_token);
    }
    let request = builder.body(Bytes::new())
        .map_err(|e| AppError::invalid(format!("Invalid scannable request: {e}")))?;
    let reply = match http::send(Some(&session), request, http::TIMEOUT).await {
        Ok(body) => body,
        Err(err) => {
            log::warn!("[scannable] resolve status={:?}", err.status);
            return Err(err.into());
        }
    };
    let uri = parse_target(&reply)?;
    //log::info!("[scannable] resolved reference {} to {}", args.media_reference, uri);
    to_value(&serde_json::json!({ "mediaReference": args.media_reference, "uri": uri }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_target_uri() {
        let body = br#"{"target":"spotify:track:0yNttAVwMr39qyODHNIkrY"}"#;
        assert_eq!(parse_target(body).unwrap(), "spotify:track:0yNttAVwMr39qyODHNIkrY");
    }

    #[test]
    fn rejects_untrusted_target() {
        assert!(parse_target(br#"{"target":"https://example.com"}"#).is_err());
        assert!(parse_target(br#"{"target":"spotify:track:abc:extra"}"#).is_err());
        assert!(parse_target(br#"{}"#).is_err());
    }

    #[test]
    fn validates_numeric_reference() {
        assert!(valid_reference("103490524832"));
        assert!(!valid_reference("-1"));
        assert!(!valid_reference("123?format=x"));
    }
}
