package com.taehagen.spotifygood.vibedj

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.taehagen.spotifygood.App

/**
 * Analyses downloaded songs for Vibe DJ while the phone charges and is idle, so mixes are ready
 * offline. Each song is analysed once (the result is cached natively).
 */
class VibeDjAnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as App).graph
        return runCatching { graph.vibeDj.analyzeDownloads() }.fold({ Result.success() }, { Result.retry() })
    }
}
