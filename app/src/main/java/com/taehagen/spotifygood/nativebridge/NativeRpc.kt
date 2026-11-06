package com.taehagen.spotifygood.nativebridge

import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.diagnostics.DiagnosticLogs
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Suspend-style calls into the native engine. Coroutine cancellation cancels the native task.
 */
class NativeRpc(val json: Json) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CancellableContinuation<JsonElement>>()

    // A single serialized gateway for public/self profile reads. Multiple screens often ask for
    // the same user at once, and Spotify responds with 429s if we issue many reads in a burst.
    // Cached data is process-local and short-lived; profile mutations invalidate it.
    private val userReadLock = Mutex()
    private val userReadCache = LinkedHashMap<String, Pair<Long, JsonElement>>()
    private var nextUserReadAt = 0L
    private val socialReadCache = LinkedHashMap<String, Pair<Long, JsonElement>>()
    private var nextSocialReadAt = 0L
    private var socialRateLimitedUntil = 0L
    private var userRateLimitedUntil = 0L
    private val profileGeneration = AtomicLong(0L)
    private var observedProfileGeneration = 0L

    /** Call after account/session changes; stale profiles must never cross accounts. */
    fun invalidateProfileReadCache() { profileGeneration.incrementAndGet() }

    private suspend fun guardedUserRead(args: JsonObject): JsonElement = userReadLock.withLock {
        if (observedProfileGeneration != profileGeneration.get()) {
            userReadCache.clear()
            socialReadCache.clear()
            // A network reconnect invalidates cached identities, not Spotify's server-side
            // rate-limit budget. Clearing the cooldown here causes another 429 immediately.
            nextSocialReadAt = 0L
            nextUserReadAt = 0L
            observedProfileGeneration = profileGeneration.get()
        }
        val generation = profileGeneration.get()
        val key = args.toString()
        val now = SystemClock.elapsedRealtime()
        userReadCache[key]?.takeIf { it.first > now }?.let { return@withLock it.second }
        if (now < userRateLimitedUntil) {
            userReadCache[key]?.let { return@withLock it.second }
            throw NativeException(NativeErrorInfo(
                code = NativeErrorCode.RATE_LIMITED,
                message = "Profile requests cooling down",
                retryAfterMs = userRateLimitedUntil - now,
            ))
        }
        val gap = nextUserReadAt - now
        if (gap > 0) delay(gap)
        try {
            val result = nativeCallRaw("catalog.user", args)
            val deadline = SystemClock.elapsedRealtime() + USER_CACHE_MS
            // Limit process memory even for many distinct accounts.
            if (userReadCache.size >= USER_CACHE_LIMIT) userReadCache.clear()
            if (generation == profileGeneration.get()) userReadCache[key] = deadline to result
            result
        } catch (e: NativeException) {
            if (e.code == NativeErrorCode.RATE_LIMITED) {
                val pause = (e.info.retryAfterMs ?: RATE_LIMIT_BACKOFF_MS)
                    .coerceIn(RATE_LIMIT_BACKOFF_MS, MAX_RATE_LIMIT_BACKOFF_MS)
                userRateLimitedUntil = SystemClock.elapsedRealtime() + pause
                DiagnosticLogs.record(DiagnosticLogs.Level.WARN, "rpc", "user_profile_backoff",
                    "durationSeconds=${pause / 1000}")
                userReadCache[key]?.let { return@withLock it.second }
            }
            throw e
        } finally {
            nextUserReadAt = SystemClock.elapsedRealtime() + USER_READ_SPACING_MS
        }
    }

    /** Followers/following reads also share a cache. Visiting profiles through different UI
     * entry points must not submit parallel social-list lookups for the same account. */
    private suspend fun guardedSocialRead(args: JsonObject): JsonElement = userReadLock.withLock {
        if (observedProfileGeneration != profileGeneration.get()) {
            userReadCache.clear()
            socialReadCache.clear()
            // Keep server cooldowns through reconnects; these are not account data.
            nextSocialReadAt = 0L
            nextUserReadAt = 0L
            observedProfileGeneration = profileGeneration.get()
        }
        val key = args.toString()
        val now = SystemClock.elapsedRealtime()
        val cached = socialReadCache[key]
        cached?.takeIf { it.first > now }?.let { return@withLock it.second }
        if (now < socialRateLimitedUntil) {
            // Stale read-only entries are preferable to another guaranteed 429.
            cached?.let { return@withLock it.second }
            throw NativeException(NativeErrorInfo(
                code = NativeErrorCode.RATE_LIMITED,
                message = "Spotify is temporarily limiting the followers list",
                retryAfterMs = socialRateLimitedUntil - now,
            ))
        }
        val gap = nextSocialReadAt - now
        if (gap > 0) delay(gap)
        val generation = profileGeneration.get()
        try {
            val response = nativeCallRaw("catalog.socialList", args)
            if (generation == profileGeneration.get()) {
                if (socialReadCache.size >= 40) socialReadCache.clear()
                socialReadCache[key] = (SystemClock.elapsedRealtime() + SOCIAL_LIST_CACHE_MS) to response
            }
            response
        } catch (e: NativeException) {
            if (e.code == NativeErrorCode.RATE_LIMITED) {
                val pause = (e.info.retryAfterMs ?: SOCIAL_BACKOFF_MS).coerceIn(5_000L, 60_000L)
                socialRateLimitedUntil = SystemClock.elapsedRealtime() + pause
                DiagnosticLogs.record(DiagnosticLogs.Level.WARN, "rpc", "social_list_backoff",
                    "durationSeconds=${pause / 1000}")
                cached?.let { return@withLock it.second }
            }
            throw e
        } finally {
            nextSocialReadAt = SystemClock.elapsedRealtime() + SOCIAL_LIST_SPACING_MS
        }
    }

    /** Raw call. Profile reads are deduplicated and rate-limit aware across all callers. */
    suspend fun callRaw(method: String, args: JsonObject = EMPTY): JsonElement = try {
        val value = when (method) {
            "catalog.user" -> guardedUserRead(args)
            "catalog.socialList" -> guardedSocialRead(args)
            else -> nativeCallRaw(method, args)
        }
        if (method in PROFILE_MUTATIONS) userReadLock.withLock {
            userReadCache.clear()
            socialReadCache.clear()
        }
        value
    } catch (e: NativeException) {
        // Profile/social 429s are already recorded as *_backoff above. In particular,
        // repeated *local* cooldown rejections are not additional server requests.
        if (e.code != NativeErrorCode.RATE_LIMITED || method !in setOf("catalog.user", "catalog.socialList")) {
            DiagnosticLogs.record(DiagnosticLogs.Level.WARN, "rpc", "native_failure", "method=$method,code=${e.code}")
        }
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        DiagnosticLogs.record(DiagnosticLogs.Level.ERROR, "rpc", "exception", "method=$method,type=${e.javaClass.simpleName}")
        throw e
    }

    private suspend fun nativeCallRaw(method: String, args: JsonObject): JsonElement =
        suspendCancellableCoroutine { cont ->
            val id = nextId.getAndIncrement()
            pending[id] = cont
            try {
                NativeBridge.nativeCall(id, method, args.toString())
            } catch (t: Throwable) {
                pending.remove(id)
                cont.resumeWithException(t)
                return@suspendCancellableCoroutine
            }
            // Registered only now that the native task exists: a cancellation that happened
            // before or during nativeCall runs this handler right away and still reaches the
            // native task (registered first, a nativeCancel could arrive before it existed and
            // the task would run unowned). A result that already arrived removed the id, so
            // the handler then does nothing.
            cont.invokeOnCancellation {
                if (pending.remove(id) != null) runCatching { NativeBridge.nativeCancel(id) }
            }
        }

    /** Typed call; the result is decoded on [Dispatchers.Default] (pages can be large). */
    suspend inline fun <reified T> call(method: String, args: JsonObject = EMPTY): T {
        val element = callRaw(method, args)
        return withContext(Dispatchers.Default) { json.decodeFromJsonElement<T>(element) }
    }

    /** Call whose result is ignored (still waits for completion and surfaces errors). */
    suspend fun callUnit(method: String, args: JsonObject = EMPTY) {
        callRaw(method, args)
    }

    inline fun <reified A> args(value: A): JsonObject = json.encodeToJsonElement(value) as JsonObject

    /** Fire and forget: no result, errors only logged natively. */
    fun fire(method: String, args: JsonObject = EMPTY) {
        runCatching { NativeBridge.nativeCall(0, method, args.toString()) }
            .onFailure { Log.w(TAG, "fire $method failed", it) }
    }

    /** Invoked from [NativeCallbacks.onResult] on a native thread. */
    internal fun complete(requestId: Long, ok: Boolean, payload: String) {
        val cont = pending.remove(requestId) ?: return
        try {
            if (ok) {
                cont.resume(json.parseToJsonElement(payload.ifEmpty { "{}" }))
            } else {
                val info = runCatching { json.decodeFromString<NativeErrorInfo>(payload) }
                    .getOrElse { NativeErrorInfo(NativeErrorCode.INTERNAL, payload) }
                cont.resumeWithException(NativeException(info))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to deliver result $requestId", t)
            if (cont.isActive) cont.resumeWithException(NativeException(NativeErrorInfo(NativeErrorCode.INTERNAL, t.message ?: "")))
        }
    }

    companion object {
        private const val TAG = "NativeRpc"
        private const val USER_CACHE_MS = 180_000L
        private const val USER_CACHE_LIMIT = 80
        private const val USER_READ_SPACING_MS = 1_100L
        private const val SOCIAL_LIST_CACHE_MS = 120_000L
        private const val SOCIAL_LIST_SPACING_MS = 500L
        private const val SOCIAL_BACKOFF_MS = 20_000L
        private const val RATE_LIMIT_BACKOFF_MS = 60_000L
        private const val MAX_RATE_LIMIT_BACKOFF_MS = 5 * 60_000L
        private val PROFILE_MUTATIONS = setOf(
            "catalog.updateProfilePhoto", "catalog.removeProfilePhoto", "catalog.updateProfileName",
            "catalog.socialFollow"
        )
        val EMPTY = JsonObject(emptyMap())
    }
}
