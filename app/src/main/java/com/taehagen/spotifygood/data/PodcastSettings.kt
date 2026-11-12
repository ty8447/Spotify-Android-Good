package com.taehagen.spotifygood.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.taehagen.spotifygood.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Per-show podcast settings (Spotify's show "Settings" sheet), kept on this phone:
 * auto-download new episodes, and whether playing the show skips finished episodes.
 */
class PodcastSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("podcast_settings", Context.MODE_PRIVATE)

    /** Show URI → when auto-download was turned on (episodes released since then download). */
    private val _autoDownload = MutableStateFlow(readMap(KEY_AUTO))
    val autoDownload: StateFlow<Map<String, Long>> = _autoDownload.asStateFlow()

    private val _skipFinished = MutableStateFlow(prefs.getStringSet(KEY_SKIP, emptySet()).orEmpty().toSet())
    val skipFinished: StateFlow<Set<String>> = _skipFinished.asStateFlow()

    fun setAutoDownload(showUri: String, on: Boolean) {
        val next = if (on) _autoDownload.value + (showUri to System.currentTimeMillis()) else _autoDownload.value - showUri
        _autoDownload.value = next
        prefs.edit().putStringSet(KEY_AUTO, next.map { "${it.value}|${it.key}" }.toSet()).apply()
        schedule()
    }

    fun setSkipFinished(showUri: String, on: Boolean) {
        val next = if (on) _skipFinished.value + showUri else _skipFinished.value - showUri
        _skipFinished.value = next
        prefs.edit().putStringSet(KEY_SKIP, next).apply()
    }

    private fun readMap(key: String): Map<String, Long> = prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull {
        val (at, uri) = it.split('|', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
        uri to (at.toLongOrNull() ?: 0L)
    }.toMap()

    fun schedule() {
        val wm = WorkManager.getInstance(context)
        if (_autoDownload.value.isEmpty()) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<PodcastAutoDownloadWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        private const val KEY_AUTO = "autoDownload"
        private const val KEY_SKIP = "skipFinished"
        private const val WORK = "podcast-auto-download"
    }
}

/** Downloads the new, unplayed episodes of shows with auto-download on (the downloader's own rules apply: Wi-Fi only, etc.). */
class PodcastAutoDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as App).graph
        val zone = ZoneId.systemDefault()
        for ((show, since) in graph.podcastSettings.autoDownload.value) {
            val sinceDay = java.time.Instant.ofEpochMilli(since).atZone(zone).toLocalDate()
            val page = runCatching { graph.catalog.showPage(show, 0, 10) }.getOrNull() ?: continue
            val fresh = page.episodes.filter { e ->
                e.playable && e.fullyPlayed != true &&
                    e.releaseDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }?.let { !it.isBefore(sinceDay) } == true
            }.map { it.uri }
            if (fresh.isNotEmpty()) runCatching { graph.downloads.downloadItems(fresh) }
        }
        return Result.success()
    }
}
