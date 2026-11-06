package com.taehagen.spotifygood.data

import android.content.Context
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.model.LibraryPush
import com.taehagen.spotifygood.model.CollectionChangedPush
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

@Serializable
private data class AccountPinEntry(val uri: String, val addedAt: Long = 0L)
@Serializable
private data class AccountPinsResult(val items: List<AccountPinEntry> = emptyList())

/** Whether another device changed the relative order after the last confirmed account read.
 * Membership changes alone do not count as a reorder: pending Pin/Unpin writes can do those.
 * Without a confirmed baseline, do not send a speculative write that may overwrite Desktop.
 */
internal fun remotePinOrderChanged(base: List<String>?, serverOrder: List<String>): Boolean {
    if (base == null) return true
    val baseSet = base.toSet()
    val serverSet = serverOrder.toSet()
    return serverOrder.filter { it in baseSet } != base.filter { it in serverSet }
}

/** One account-scoped synchronization owner for Your Library pins, including action sheets. */
@OptIn(FlowPreview::class)
class AccountPinsRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val rpc: NativeRpc,
    private val user: StateFlow<User?>,
    private val online: StateFlow<Boolean>,
    private val foreground: StateFlow<Boolean>,
    private val pushes: Flow<LibraryPush>,
) {
    private val store = LibraryPins(context)
    private val lock = Mutex()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 32)
    private val revision = AtomicLong(0)
    private val _pins = MutableStateFlow(store.read(user.value?.username))
    val pins: StateFlow<List<LibraryPin>> = _pins

    init {
        scope.launch {
            user.map { it?.username }.distinctUntilChanged().collectLatest { username ->
                revision.incrementAndGet()
                _pins.value = store.read(username)
                if (username != null && online.value) refresh()
            }
        }
        scope.launch { online.filter { it }.collect { refresh() } }
        scope.launch { changes.debounce(450).collect { refresh() } }
        // Native collection pushes are quickest; some Desktop clients do not emit them.
        scope.launch { pushes.filterIsInstance<CollectionChangedPush>()
            .filter { it.set == "ylpin" }.collect { refresh() } }
        // Foreground-only fallback ensures Desktop changes arrive without pulling to refresh.
        scope.launch {
            combine(online, foreground) { connected, visible -> connected && visible }
                .distinctUntilChanged().collectLatest { active ->
                    if (active) {
                        while (currentCoroutineContext().isActive) {
                            delay(30_000)
                            refresh()
                        }
                    }
                }
        }
    }

    fun toggle(pin: LibraryPin) {
        val username = user.value?.username ?: return
        val next = store.toggle(username, pin)
        _pins.value = next
        revision.incrementAndGet()
        // Local-only synthetic folders cannot be sent to the Spotify collection service.
        if (pin.id.startsWith("spotify:")) {
            store.queue(username, pin.id, next.any { it.id == pin.id })
            changes.tryEmit(Unit)
        }
    }

    fun unpin(pin: LibraryPin) {
        val username = user.value?.username ?: return
        if (_pins.value.none { it.id == pin.id }) return
        toggle(pin)
    }

    fun move(from: Int, to: Int) {
        val username = user.value?.username ?: return
        val before = _pins.value
        val after = store.move(username, from, to)
        if (after == before) return
        _pins.value = after
        revision.incrementAndGet()
        store.markReorder(username)
        changes.tryEmit(Unit)
    }

    fun requestRefresh() { scope.launch { refresh() } }

    private suspend fun readRemote(): List<AccountPinEntry> =
        rpc.callOffMain<AccountPinsResult>("library.pins").items.distinctBy { it.uri }

    /** Reconciles pending local edits before replacing the local cache with a full server read. */
    suspend fun refresh(): Unit = lock.withLock {
        val username = user.value?.username ?: return@withLock
        if (!online.value) return@withLock
        val started = revision.get()
        try {
            var remote = readRemote()
            val local = store.read(username)
            val pending = store.pending(username)
            val initial = !store.hasSynced(username)
            // One-time additive migration of legacy local pins. Never remove existing account pins.
            if (initial) {
                val known = remote.mapTo(HashSet()) { it.uri }
                for (pin in local) {
                    if (pin.id.startsWith("spotify:") && known.add(pin.id) && pending.none { it.uri == pin.id && !it.pinned }) {
                        rpc.callUnitOffMain("library.pinSet", rpcArgs {
                            put("uri", pin.id)
                            put("pinned", true)
                        })
                    }
                }
            }
            // A move made while the first network read was in flight takes precedence.
            if (revision.get() != started || user.value?.username != username) {
                changes.tryEmit(Unit)
                return@withLock
            }
            for (edit in pending) {
                rpc.callUnitOffMain("library.pinSet", rpcArgs {
                    put("uri", edit.uri)
                    put("pinned", edit.pinned)
                })
            }
            if (initial || pending.isNotEmpty()) remote = readRemote()
            for (edit in pending) {
                check(remote.any { it.uri == edit.uri } == edit.pinned) {
                    "pin write has not appeared in the account collection yet"
                }
            }
            if (store.reorderQueued(username)) {
                val base = store.reorderBase(username)
                val serverOrder = remote.map { it.uri }
                // Compare only pins common to both lists; a queued Pin/Unpin can change
                // membership without invalidating an otherwise intentional local reorder.
                val serverSet = serverOrder.toSet()
                if (remotePinOrderChanged(base, serverOrder)) {
                    // A second device moved pins since our last confirmed read. The server
                    // wins; never replay the phone's stale order over a newer desktop edit.
                    android.util.Log.i("AccountPins", "Remote pin order changed; keeping server order")
                } else {
                    val desired = local.filter { it.id.startsWith("spotify:") }.map { it.id }
                    val requested = (desired.filter { id -> id in serverSet } +
                        serverOrder.filterNot { it in desired }).distinct()
                    if (requested.size == remote.size && requested != serverOrder) {
                        if (revision.get() != started || user.value?.username != username) {
                            changes.tryEmit(Unit)
                            return@withLock
                        }
                        rpc.callUnitOffMain("library.pinReorder", rpcArgs { putStrings("uris", requested) })
                        remote = readRemote()
                        check(remote.map { it.uri } == requested) {
                            "pin order has not appeared in the account collection yet"
                        }
                    }
                }
            }
            // A toggle while an RPC was awaiting must not be overwritten by an older read.
            if (revision.get() != started || user.value?.username != username) {
                changes.tryEmit(Unit)
                return@withLock
            }
            val metadata = local.toList()
            val updated = remote.map { entry -> libraryPinFromUri(entry.uri, metadata) }
            val localOnly = local.filter { !it.id.startsWith("spotify:") }
            val combined = (updated + localOnly).distinctBy { it.id }
            store.acceptServer(username, combined)
            _pins.value = combined
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Cache remains untouched: retry on reconnect, a user edit, or pull-to-refresh.
            android.util.Log.w("AccountPins", "Could not synchronize library pins: ${e.javaClass.simpleName}")
        }
    }
}
