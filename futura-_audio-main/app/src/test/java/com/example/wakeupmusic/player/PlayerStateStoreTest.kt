package com.example.wakeupmusic.player

import com.example.wakeupmusic.player.data.MapBackend
import com.example.wakeupmusic.player.data.PlayerStateStore
import com.example.wakeupmusic.player.data.SavedPlayback
import com.example.wakeupmusic.player.service.ButtonAction
import com.example.wakeupmusic.player.service.ClickMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NOT RUN. Written for the Stage 1 checklist. Uses an in-memory backend, so it needs no Android.
 */
class PlayerStateStoreTest {

    private fun store(map: MutableMap<String, Any> = mutableMapOf()) = PlayerStateStore(MapBackend(map))

    @Test
    fun emptyStoreHasNoPlaybackAndDefaultMapping() {
        val s = store()
        assertNull(s.readPlayback())
        assertEquals(ClickMapping(), s.readMapping())
        assertTrue(s.cleanExit)
    }

    @Test
    fun playbackRoundTrips() {
        val s = store()
        val saved = SavedPlayback(
            uris = listOf("content://a/1", "content://a/2"),
            index = 1,
            positionMs = 42_000L,
            volume = 0.5f,
            wasPlaying = true,
            shuffle = true,
        )
        s.savePlayback(saved)
        assertEquals(saved, s.readPlayback())
    }

    @Test
    fun indexIsClampedToTheQueue() {
        val map = mutableMapOf<String, Any>("queue_uris" to "content://a/1", "queue_index" to 9)
        assertEquals(0, store(map).readPlayback()?.index)
    }

    @Test
    fun emptyEntriesAreDropped() {
        val map = mutableMapOf<String, Any>("queue_uris" to "\ncontent://a/1\n\ncontent://a/2\n")
        assertEquals(listOf("content://a/1", "content://a/2"), store(map).readPlayback()?.uris)
    }

    @Test
    fun crashRecoveryNeededOnlyWhenNotCleanAndWasPlaying() {
        val s = store()
        s.savePlayback(SavedPlayback(listOf("content://a/1"), 0, 1000L, 1f, wasPlaying = true, shuffle = false))
        s.cleanExit = false
        assertTrue(s.crashRecoveryNeeded())

        s.cleanExit = true
        assertFalse(s.crashRecoveryNeeded())

        s.cleanExit = false
        s.savePlayback(SavedPlayback(listOf("content://a/1"), 0, 1000L, 1f, wasPlaying = false, shuffle = false))
        assertFalse(s.crashRecoveryNeeded())
    }

    @Test
    fun mappingRoundTripsAndUnknownNamesFallBackToDefaults() {
        val s = store()
        val mapping = ClickMapping(single = ButtonAction.NONE, double = ButtonAction.SHUFFLE, triple = ButtonAction.PREVIOUS)
        s.saveMapping(mapping)
        assertEquals(mapping, s.readMapping())

        val bad = mutableMapOf<String, Any>("map_double" to "NOT_A_BUTTON")
        assertEquals(ButtonAction.NEXT, store(bad).readMapping().double)
    }
}
