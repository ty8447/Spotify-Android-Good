//! Cache of Vibe DJ analyses: one small JSON file per track under `files_dir/vibedj/` (~0.5–3 KB
//! each, so 5 000 songs take under 15 MB), an in-memory LRU in front, and failure markers so
//! unplayable tracks aren't retried for a week.

use super::analysis::{Analysis, VERSION};
use crate::runtime;
use lru::LruCache;
use parking_lot::Mutex;
use std::num::NonZeroUsize;
use std::path::PathBuf;
use std::sync::{Arc, LazyLock};

const RETRY_FAILED_MS: i64 = 7 * 24 * 3600 * 1000;

static MEM: LazyLock<Mutex<LruCache<String, Arc<Analysis>>>> =
    LazyLock::new(|| Mutex::new(LruCache::new(NonZeroUsize::new(1024).expect("non-zero"))));

fn dir() -> Option<PathBuf> {
    runtime::is_initialized().then(|| runtime::config().files_dir.join("vibedj"))
}

/// File stem for a track URI (`spotify:track:<id>` → `<id>`; anything else is sanitized).
fn stem(uri: &str) -> String {
    uri.rsplit(':').next().unwrap_or(uri).chars().filter(char::is_ascii_alphanumeric).collect()
}

pub(crate) fn get(uri: &str) -> Option<Arc<Analysis>> {
    if let Some(a) = MEM.lock().get(uri) {
        return Some(a.clone());
    }
    let path = dir()?.join(format!("{}.json", stem(uri)));
    let bytes = std::fs::read(path).ok()?;
    let a: Analysis = serde_json::from_slice(&bytes).ok()?;
    if a.v != VERSION {
        return None;
    }
    let a = Arc::new(a);
    MEM.lock().put(uri.to_string(), a.clone());
    Some(a)
}

pub(crate) fn put(uri: &str, analysis: Analysis) -> Arc<Analysis> {
    let a = Arc::new(analysis);
    MEM.lock().put(uri.to_string(), a.clone());
    if let Some(dir) = dir() {
        let write = || -> std::io::Result<()> {
            std::fs::create_dir_all(&dir)?;
            let path = dir.join(format!("{}.json", stem(uri)));
            let tmp = path.with_extension("tmp");
            std::fs::write(&tmp, serde_json::to_vec(&*a).unwrap_or_default())?;
            std::fs::rename(tmp, path)
        };
        if let Err(e) = write() {
            log::warn!("vibe dj cache: {e}");
        }
    }
    a
}

pub(crate) fn mark_failed(uri: &str) {
    if let Some(dir) = dir() {
        let _ = std::fs::create_dir_all(&dir);
        let _ = std::fs::write(dir.join(format!("{}.failed", stem(uri))), crate::connect::now_ms().to_string());
    }
}

pub(crate) fn failed_recently(uri: &str) -> bool {
    let Some(dir) = dir() else { return false };
    std::fs::read_to_string(dir.join(format!("{}.failed", stem(uri))))
        .ok()
        .and_then(|s| s.trim().parse::<i64>().ok())
        .is_some_and(|at| crate::connect::now_ms() - at < RETRY_FAILED_MS)
}

/// (analyzed tracks, bytes on disk).
pub(crate) fn info() -> (usize, u64) {
    let Some(dir) = dir() else { return (0, 0) };
    let Ok(entries) = std::fs::read_dir(dir) else { return (0, 0) };
    entries
        .flatten()
        .filter(|e| e.path().extension().is_some_and(|x| x == "json"))
        .fold((0, 0), |(n, b), e| (n + 1, b + e.metadata().map(|m| m.len()).unwrap_or(0)))
}

pub(crate) fn clear() {
    MEM.lock().clear();
    if let Some(dir) = dir() {
        let _ = std::fs::remove_dir_all(dir);
    }
}
