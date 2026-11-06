package com.taehagen.spotifygood.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import com.taehagen.spotifygood.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Private, size-bounded, app-owned diagnostics. Android does not permit ordinary apps to
 * export the system's historical ADB logcat. Instead these events are collected as they happen,
 * survive process restarts, and can be exported without a computer or any log permissions.
 *
 * Never pass raw server messages, URLs, account IDs, track names, URIs or request JSON to record().
 * All callers use fixed event names and bounded state/error enums only.
 */
object DiagnosticLogs {
    enum class Level { INFO, WARN, ERROR }

    private const val WINDOW_MS = 3L * 60 * 60 * 1000
    private const val MAX_ACTIVE_BYTES = 1024L * 1024
    private const val MAX_EXPORT_ROWS = 6000
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "spotifygood-diagnostics").apply { isDaemon = true }
    }
    private val safeChars = Regex("[^a-zA-Z0-9_.=, -]")
    @Volatile private var directory: File? = null

    fun initialize(context: Context) {
        if (directory != null) return
        synchronized(this) {
            if (directory == null) {
                directory = File(context.noBackupFilesDir, "diagnostics").also { it.mkdirs() }
            }
        }
        record(Level.INFO, "app", "process_start")
    }

    fun record(level: Level, area: String, action: String, state: String = "") {
        val dir = directory ?: return
        val timestamp = System.currentTimeMillis()
        // Fail closed on unexpectedly long input; even a forgotten caller must not leak raw data.
        val safeArea = sanitize(area, 32)
        val safeAction = sanitize(action, 48)
        val safeState = sanitize(state, 128)
        worker.execute {
            try {
                dir.mkdirs()
                val active = File(dir, "events.jsonl")
                if (active.length() > MAX_ACTIVE_BYTES) {
                    val old = File(dir, "previous.jsonl")
                    if (old.exists()) old.delete()
                    active.renameTo(old)
                }
                val line = JSONObject()
                    .put("timeMs", timestamp)
                    .put("level", level.name)
                    .put("area", safeArea)
                    .put("event", safeAction)
                    .put("state", safeState)
                    .toString() + "\n"
                active.appendText(line, Charsets.UTF_8)
            } catch (_: Exception) {
                // Diagnostics must never break playback or cause recursive logging.
            }
        }
    }

    private fun sanitize(value: String, limit: Int): String {
        if (value.length > limit) return "redacted_long_value"
        // Long alphanumeric substrings might be session IDs or tokens; never export them.
        return value.replace(Regex("[A-Za-z0-9]{18,}"), "redacted")
            .replace(safeChars, "_")
    }

    /** Exports only events written during the last three hours to the user-selected document URI. */
    suspend fun exportTo(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val rows = worker.submit(Callable { readRecent() }).get(15, TimeUnit.SECONDS)
        val now = System.currentTimeMillis()
        val output = context.contentResolver.openOutputStream(uri)
            ?: error("Could not open the selected ZIP destination")
        output.use { stream ->
            ZipOutputStream(stream).use { zip ->
                fun entry(name: String, contents: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray(StandardCharsets.UTF_8))
                    zip.closeEntry()
                }
                entry("README.txt", """
Spotify Good diagnostic export
Generated: $now (UTC epoch milliseconds)
Window: last 3 hours (from ${now - WINDOW_MS} to $now)
Events: ${rows.size}

This is a filtered, app-owned diagnostic log. It is NOT a complete ADB logcat dump.
Android restricts reading historical system/other-app logs without privileged access.
Events before this diagnostic feature was installed cannot be recovered.

Collected: app lifecycle, native session/playback transitions and failure categories,
playback commands, download phases/retry decisions, and relevant RPC failures.
Not collected: usernames, message content, track titles, Spotify URIs, credentials,
auth tokens, request/response payloads, or raw exception messages.

Review the ZIP before sending it to anyone. It may reveal playback timings and
high-level device/app behavior. No network upload occurs automatically.
""".trimIndent() + "\n")
                entry("environment.txt", """
version=${BuildConfig.VERSION_NAME}
versionCode=${BuildConfig.VERSION_CODE}
androidSdk=${Build.VERSION.SDK_INT}
buildType=${BuildConfig.BUILD_TYPE}
""".trimIndent() + "\n")
                entry("events.jsonl", rows.joinToString("\n", postfix = if (rows.isEmpty()) "" else "\n"))
            }
        }
        rows.size
    }

    private fun readRecent(): List<String> {
        val dir = directory ?: return emptyList()
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        val events = mutableListOf<String>()
        for (file in listOf(File(dir, "previous.jsonl"), File(dir, "events.jsonl"))) {
            if (!file.exists()) continue
            try {
                file.forEachLine(Charsets.UTF_8) { line ->
                    val time = runCatching { JSONObject(line).optLong("timeMs") }.getOrDefault(0)
                    if (time >= cutoff && time <= System.currentTimeMillis() + 60_000) {
                        events.add(line)
                        if (events.size > MAX_EXPORT_ROWS) events.removeAt(0)
                    }
                }
            } catch (_: Exception) { /* Corrupt lines do not block export. */ }
        }
        return events
    }
}
