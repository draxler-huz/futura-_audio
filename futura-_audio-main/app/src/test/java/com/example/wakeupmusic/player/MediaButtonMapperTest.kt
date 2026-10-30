package com.example.wakeupmusic.player

import com.example.wakeupmusic.player.service.ButtonAction
import com.example.wakeupmusic.player.service.ClickMapping
import com.example.wakeupmusic.player.service.MediaButtonMapper
import com.example.wakeupmusic.player.service.MediaButtonMapper.Companion.KEYCODE_MEDIA_NEXT
import com.example.wakeupmusic.player.service.MediaButtonMapper.Companion.KEYCODE_MEDIA_PLAY_PAUSE
import com.example.wakeupmusic.player.service.MediaButtonMapper.Companion.KEYCODE_MEDIA_PREVIOUS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * NOT RUN. Written for the Stage 1 checklist. Needs a JVM test run in CI, which this session cannot do.
 * Uses a fake clock, so timing is deterministic.
 */
class MediaButtonMapperTest {

    private var now = 0L
    private val mapper = MediaButtonMapper(clock = { now })

    @Test
    fun singleClickResolvesAfterWindowToPlayPause() {
        assertEquals(emptyList<ButtonAction>(), mapper.onKey(KEYCODE_MEDIA_PLAY_PAUSE))
        now = 349
        assertNull(mapper.resolveExpired())
        now = 350
        assertEquals(ButtonAction.PLAY_PAUSE, mapper.resolveExpired())
        assertNull(mapper.resolveExpired())
    }

    @Test
    fun doubleClickMapsToNextByDefault() {
        mapper.onKey(KEYCODE_MEDIA_PLAY_PAUSE)
        now = 200
        mapper.onKey(KEYCODE_MEDIA_PLAY_PAUSE)
        now = 200 + 350
        assertEquals(ButtonAction.NEXT, mapper.resolveExpired())
    }

    @Test
    fun tripleClickMapsToPreviousByDefault() {
        mapper.onKey(KEYCODE_MEDIA_PLAY_PAUSE)
        now = 100
        mapper.onKey(KEYCODE_MEDIA_PLAY_PAUSE)
        now = 200
        mapper.onKey(KEYCODE_MEDIA_PLAY_PAUSE)
        now = 550
        assertEquals(ButtonAction.PREVIOUS, mapper.resolveExpired())
    }

    @Test
    fun windowIsMeasuredFromTheLastClick() {
        mapper.onKey(KEYCODE_MEDIA_PLAY_PAUSE)
        now = 300
        mapper.onKey(KEYCODE_MEDIA_PLAY_PAUSE)
        // 350 ms after the first click, but only 50 ms after the second: the burst is still open.
        now = 400
        assertNull(mapper.resolveExpired())
        assertEquals(650L, mapper.pendingDeadlineMs())
    }

    @Test
    fun dedicatedNextAndPreviousKeysActAtOnce() {
        assertEquals(listOf(ButtonAction.NEXT), mapper.onKey(KEYCODE_MEDIA_NEXT))
        assertEquals(listOf(ButtonAction.PREVIOUS), mapper.onKey(KEYCODE_MEDIA_PREVIOUS))
        assertNull(mapper.pendingDeadlineMs())
    }

    @Test
    fun laterKeyPressResolvesAnExpiredBurstFirst() {
        mapper.onKey(KEYCODE_MEDIA_PLAY_PAUSE)
        now = 500
        assertEquals(
            listOf(ButtonAction.PLAY_PAUSE, ButtonAction.NEXT),
            mapper.onKey(KEYCODE_MEDIA_NEXT),
        )
    }

    @Test
    fun customMappingIsUsed() {
        val custom = MediaButtonMapper(
            mapping = ClickMapping(single = ButtonAction.NONE, double = ButtonAction.SHUFFLE, triple = ButtonAction.NEXT),
            clock = { now },
        )
        custom.onKey(KEYCODE_MEDIA_PLAY_PAUSE)
        custom.onKey(KEYCODE_MEDIA_PLAY_PAUSE)
        now = 400
        assertEquals(ButtonAction.SHUFFLE, custom.resolveExpired())
    }

    @Test
    fun unrelatedKeysAreIgnored() {
        assertEquals(emptyList<ButtonAction>(), mapper.onKey(0))
        assertNull(mapper.pendingDeadlineMs())
    }
}
