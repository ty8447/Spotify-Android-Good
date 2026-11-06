package com.taehagen.spotifygood.data

import org.junit.Assert.assertEquals
import kotlinx.serialization.json.Json
import com.taehagen.spotifygood.model.Image
import org.junit.Test

class LibraryPinsOrderTest {
    private val a = LibraryPin("PLAYLIST", "spotify:playlist:one", "One")
    private val b = LibraryPin("ALBUM", "spotify:album:two", "Two")
    private val c = LibraryPin("FOLDER", "folder:three", "Three")

    @Test fun movingPinsPreservesIdentityAndOrder() {
        assertEquals(listOf(b, c, a), reorderLibraryPins(listOf(a, b, c), 0, 2))
        assertEquals(listOf(c, a, b), reorderLibraryPins(listOf(a, b, c), 2, 0))
    }


    @Test fun legacyPinJsonPreservesPinWithoutNewFields() {
        val old = """{"kind":"PLAYLIST","id":"spotify:playlist:one","name":"One","creator":"Owner"}"""
        val pin = Json.decodeFromString(LibraryPin.serializer(), old)
        assertEquals(emptyList<Image>(), pin.images)
        assertEquals(null, pin.ownerUsername)
        assertEquals("Owner", pin.creator)
    }

    @Test fun newPinPreservesArtworkAndOwner() {
        val pin = a.copy(images = listOf(Image("https://example.com/cover.jpg")), ownerUsername = "owner")
        assertEquals(pin, Json.decodeFromString(LibraryPin.serializer(), Json.encodeToString(LibraryPin.serializer(), pin)))
        assertEquals(pin.images, pin.ref()?.images)
    }

    @Test fun accountCollectionUrisKeepTheirMediaKind() {
        val pins = listOf(
            "spotify:playlist:xyz" to "PLAYLIST",
            "spotify:album:abc" to "ALBUM",
            "spotify:artist:artist" to "ARTIST",
            "spotify:show:show" to "SHOW",
            "spotify:folder:folder" to "FOLDER",
            "spotify:user:someone:collection" to "LIKED_SONGS",
        )
        pins.forEach { (uri, expected) -> assertEquals(expected, libraryPinFromUri(uri).kind) }
        assertEquals(a, libraryPinFromUri(a.id, listOf(a)))
    }

    @Test fun likedSongsAliasesShareOneLibraryCard() {
        assertEquals(true, libraryPinFromUri("spotify:user:someone:collection").isLikedSongsPin())
        assertEquals(true, libraryPinFromUri("spotify:collection:tracks").isLikedSongsPin())
        assertEquals(false, a.isLikedSongsPin())
    }

    @Test fun remoteReorderMustNotBeOverwrittenByPendingPhoneOrder() {
        val base = listOf("a", "b", "c")
        assertEquals(false, remotePinOrderChanged(base, base))
        assertEquals(false, remotePinOrderChanged(base, listOf("a", "x", "b", "c")))
        assertEquals(true, remotePinOrderChanged(base, listOf("b", "a", "c")))
        assertEquals(true, remotePinOrderChanged(null, base))
    }

    @Test fun invalidOrNoopMovesDoNotChangePins() {
        val original = listOf(a, b, c)
        assertEquals(original, reorderLibraryPins(original, -1, 0))
        assertEquals(original, reorderLibraryPins(original, 0, 3))
        assertEquals(original, reorderLibraryPins(original, 1, 1))
    }
}
