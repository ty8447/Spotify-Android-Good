package com.taehagen.spotifygood.playback

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import android.support.v4.media.session.MediaSessionCompat
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.diagnostics.DiagnosticLogs

/**
 * Android Auto's stock Media3 player does not allow an app-defined header title. New hosts that
 * support Car API 8 use this Car App Library service and its MediaPlaybackTemplate instead.
 * Older hosts continue to use the unchanged PlaybackService/MediaLibraryService browser.
 */
class SpotifyGoodCarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(this)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session {
        DiagnosticLogs.record(DiagnosticLogs.Level.INFO, "car", "template_session_created")
        return SpotifyGoodCarSession()
    }
}

/** Private commands only available to our app's own Media3 browser controller. */
internal object CarPlaybackBridge {
    const val ACTION_TOKEN = "com.taehagen.spotifygood.car.GET_PLATFORM_TOKEN"
    const val KEY_PLATFORM_TOKEN = "platform_token"
    val TOKEN_COMMAND = SessionCommand(ACTION_TOKEN, Bundle.EMPTY)
}

private class SpotifyGoodCarSession : Session() {
    private var browser: MediaBrowser? = null
    private var browserFuture: ListenableFuture<MediaBrowser>? = null
    private val waiting = mutableListOf<() -> Unit>()
    private var tokenRegistered = false
    private var tokenError = false
    private var lastHeaderSource: String? = null

    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = invalidateVisible()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = invalidateVisible()
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = connectBrowser()
            override fun onDestroy(owner: LifecycleOwner) {
                browser?.removeListener(listener)
                browserFuture?.let(androidx.media3.session.MediaController::releaseFuture)
                browser = null
                waiting.clear()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen =
        if (intent.action == ACTION_SHOW_PLAYBACK) {
            SpotifyGoodCarPlaybackScreen(carContext, this)
        } else {
            SpotifyGoodCarBrowseScreen(carContext, this, LibraryTree.ROOT,
                carContext.getString(R.string.app_name), true)
        }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_SHOW_PLAYBACK &&
            carContext.getCarService(androidx.car.app.ScreenManager::class.java).top !is SpotifyGoodCarPlaybackScreen) {
            showPlayback()
        }
    }

    private fun connectBrowser() {
        val future = MediaBrowser.Builder(carContext,
            SessionToken(carContext, ComponentName(carContext, PlaybackService::class.java)))
            .buildAsync()
        browserFuture = future
        Futures.addCallback(future, object : FutureCallback<MediaBrowser> {
            override fun onSuccess(result: MediaBrowser?) {
                if (result == null) { tokenError = true; invalidateVisible(); return }
                browser = result
                result.addListener(listener)
                // Must register the platform session token before showing MediaPlaybackTemplate.
                val command = result.sendCustomCommand(CarPlaybackBridge.TOKEN_COMMAND, Bundle.EMPTY)
                Futures.addCallback(command, object : FutureCallback<SessionResult> {
                    override fun onSuccess(value: SessionResult?) {
                        @Suppress("DEPRECATION")
                        val platform = value?.extras?.getParcelable(CarPlaybackBridge.KEY_PLATFORM_TOKEN)
                            as? android.media.session.MediaSession.Token
                        if (value?.resultCode == SessionResult.RESULT_SUCCESS && platform != null) {
                            try {
                                val compat = MediaSessionCompat.Token.fromToken(platform)
                                val manager = carContext.getCarService(CarContext.MEDIA_PLAYBACK_SERVICE)
                                    as MediaPlaybackManager
                                manager.registerMediaPlaybackToken(compat)
                                tokenRegistered = true
                                DiagnosticLogs.record(DiagnosticLogs.Level.INFO, "car", "template_token_registered")
                            } catch (e: Exception) {
                                Log.w(TAG, "MediaPlaybackTemplate token registration failed", e)
                                tokenError = true
                                DiagnosticLogs.record(DiagnosticLogs.Level.WARN, "car", "template_token_failed")
                            }
                        } else {
                            tokenError = true
                            DiagnosticLogs.record(DiagnosticLogs.Level.WARN, "car", "template_token_unavailable")
                            Log.w(TAG, "MediaPlaybackTemplate: no valid media token")
                        }
                        flushWaiting()
                    }
                    override fun onFailure(t: Throwable) {
                        Log.w(TAG, "MediaPlaybackTemplate token lookup failed", t)
                        tokenError = true
                        DiagnosticLogs.record(DiagnosticLogs.Level.WARN, "car", "template_token_lookup_failed")
                        flushWaiting()
                    }
                }, ContextCompat.getMainExecutor(carContext))
                flushWaiting()
            }
            override fun onFailure(t: Throwable) {
                Log.w(TAG, "Car MediaBrowser connection failed", t)
                tokenError = true
                flushWaiting()
            }
        }, ContextCompat.getMainExecutor(carContext))
    }

    private fun flushWaiting() {
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { it.invoke() }
        invalidateVisible()
    }

    private fun invalidateVisible() {
        carContext.getCarService(androidx.car.app.ScreenManager::class.java).top?.invalidate()
    }

    fun isReady() = tokenRegistered
    fun error() = tokenError
    fun playbackHeader(): String {
        val meta = browser?.mediaMetadata
        val context = meta?.subtitle?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() && !it.startsWith("Playing on ", ignoreCase = true) }
        val album = meta?.albumTitle?.toString()?.trim()?.takeIf(String::isNotEmpty)
        val source = when {
            context != null -> "context"
            album != null -> "album"
            else -> "fallback"
        }
        if (lastHeaderSource != source) {
            lastHeaderSource = source
            // Do not log the playlist/album names: the exported diagnostics stay private-safe.
            DiagnosticLogs.record(DiagnosticLogs.Level.INFO, "car", "template_header_source", "source=$source")
        }
        return context ?: album ?: "Now Playing"
    }

    fun loadChildren(parentId: String, page: Int, onResult: (List<MediaItem>?) -> Unit) {
        val b = browser
        if (b == null) {
            if (!tokenError) waiting += { loadChildren(parentId, page, onResult) }
            else onResult(null)
            return
        }
        val request = b.getChildren(parentId, page, PAGE_SIZE, null)
        Futures.addCallback(request, object : FutureCallback<androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
            override fun onSuccess(result: androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>?) {
                onResult(result?.value?.toList())
            }
            override fun onFailure(t: Throwable) {
                Log.w(TAG, "Car browse load failed", t)
                onResult(null)
            }
        }, ContextCompat.getMainExecutor(carContext))
    }

    fun activate(item: MediaItem) {
        browser?.apply {
            setMediaItem(item)
            prepare()
            play()
        }
        showPlayback()
    }

    fun showPlayback() {
        val manager = carContext.getCarService(androidx.car.app.ScreenManager::class.java)
        if (manager.top !is SpotifyGoodCarPlaybackScreen) {
            manager.push(SpotifyGoodCarPlaybackScreen(carContext, this))
        }
    }

    companion object {
        private const val TAG = "SpotifyGoodCar"
        const val PAGE_SIZE = 3
        private const val ACTION_SHOW_PLAYBACK = "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK"
    }
}

/** Uses Android Auto's standard MediaSession controls; only the header is customized. */
private class SpotifyGoodCarPlaybackScreen(context: CarContext, private val car: SpotifyGoodCarSession) : Screen(context) {
    override fun onGetTemplate(): Template {
        if (!car.isReady()) {
            return MessageTemplate.Builder(if (car.error()) "Unable to connect to playback" else "Connecting to playback…")
                .setTitle("Now Playing")
                .setHeaderAction(Action.APP_ICON)
                .build()
        }
        return MediaPlaybackTemplate.Builder()
            .setHeader(Header.Builder()
                .setStartHeaderAction(Action.APP_ICON)
                .setTitle(car.playbackHeader())
                .build())
            .build()
    }
}

/** Lightweight library navigator for hosts that choose the templated media experience. */
private class SpotifyGoodCarBrowseScreen(
    context: CarContext,
    private val car: SpotifyGoodCarSession,
    private val parentId: String,
    private val heading: String,
    private val root: Boolean = false,
) : Screen(context) {
    private var items: List<MediaItem>? = null
    private var loading = false
    private var failed = false
    private var page = 0

    override fun onGetTemplate(): Template {
        val header = Header.Builder().setTitle(heading)
            .setStartHeaderAction(if (root) Action.APP_ICON else Action.BACK).build()
        val b = ListTemplate.Builder().setHeader(header)
        if (items == null && !failed) {
            if (!loading) {
                loading = true
                car.loadChildren(parentId, page) { result ->
                    loading = false
                    if (result == null) failed = true else items = result
                    invalidate()
                }
            }
            return b.setLoading(true).build()
        }
        val rows = ItemList.Builder()
        rows.addItem(Row.Builder().setTitle("Now Playing").setOnClickListener { car.showPlayback() }.build())
        if (failed) {
            rows.addItem(Row.Builder().setTitle("Retry loading library").setOnClickListener {
                failed = false; items = null; invalidate()
            }.build())
        } else {
            if (page > 0) {
                rows.addItem(Row.Builder().setTitle("Previous page").setOnClickListener {
                    page--; items = null; failed = false; invalidate()
                }.build())
            }
            items.orEmpty().forEach { item ->
                val meta = item.mediaMetadata
                val title = meta.title?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                val row = Row.Builder().setTitle(title)
                meta.artist?.toString()?.takeIf { it.isNotBlank() }?.let(row::addText)
                row.setOnClickListener {
                    if (meta.isBrowsable == true) {
                        carContext.getCarService(androidx.car.app.ScreenManager::class.java)
                            .push(SpotifyGoodCarBrowseScreen(carContext, car, item.mediaId, title))
                    } else if (meta.isPlayable == true) {
                        car.activate(item)
                    }
                }
                rows.addItem(row.build())
            }
            if (items.orEmpty().size >= SpotifyGoodCarSession.PAGE_SIZE) {
                rows.addItem(Row.Builder().setTitle("Next page").setOnClickListener {
                    page++; items = null; failed = false; invalidate()
                }.build())
            }
        }
        return b.setSingleList(rows.build()).build()
    }
}
