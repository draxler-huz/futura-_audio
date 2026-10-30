@file:OptIn(UnstableApi::class)

package com.example.wakeupmusic.player.service

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.example.wakeupmusic.engine.PlayerCode
import com.example.wakeupmusic.engine.PlayerEngine
import com.example.wakeupmusic.player.data.PlayerStateStore
import com.example.wakeupmusic.player.data.SavedPlayback
import com.example.wakeupmusic.player.data.SourceOpener
import com.example.wakeupmusic.player.data.TrackMeta
import com.example.wakeupmusic.player.data.TrackMetadataReader
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * The player the media session exposes (features 1.3, 1.4, 1.5, 1.7). It forwards every command to [PlayerEngine].
 *
 * THREADING. There are two sides, and the rules are strict:
 *  - Main thread (SimpleBasePlayer callbacks, focus callbacks, media keys, posted results). It owns the mirror state
 *    fields (uris, index, positionMs, playWhen, ...). Only this thread reads or writes them.
 *  - Control thread ("futura-control", one HandlerThread). It owns [PlayerEngine] and the ctl* fields. Every engine
 *    call happens here, because PlayerEngine requires one calling thread. Work from the main thread is posted here,
 *    and results come back by posting to the main thread.
 *
 * GAPLESS (feature 1.3). After each load, the control thread queues track i+1 with loadNext. The control poll,
 * every [POLL_MS], watches switchCount. When it changes, the engine has moved on to the queued track, so the control
 * side advances its index and queues the one after. The main side learns the new index from the snapshot.
 *
 * Shuffle takes effect from the next track that is queued. A track that is already queued keeps its place.
 */
class FuturaPlayer(
    context: Context,
    private val store: PlayerStateStore,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val controlThread = HandlerThread("futura-control").apply { start() }
    private val control = Handler(controlThread.looper)
    private val metaExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val reader = TrackMetadataReader(appContext)
    private val mapper = MediaButtonMapper(store.readMapping())

    private val focusListener: AudioFocusController.Listener = object : AudioFocusController.Listener {
        override fun onFocusPause(transient: Boolean) {
            if (!playWhen) return
            playWhen = false
            pausedByFocus = transient
            if (!transient) focus.abandon()
            control.post { ctlPause() }
            invalidateState()
            save()
        }

        override fun onFocusDuck(enabled: Boolean) {
            duck = if (enabled) DUCK_VOLUME else 1f
            pushVolume()
        }

        override fun onFocusRegained() {
            if (pausedByFocus) {
                pausedByFocus = false
                setPlayIntent(true)
            }
        }

        override fun onBecomingNoisy() {
            if (playWhen) setPlayIntent(false)
        }
    }
    private val focus: AudioFocusController = AudioFocusController(appContext, focusListener)

    // ---- Main-thread mirror state. Only the main thread reads or writes these. ----
    private var uris: List<Uri> = emptyList()
    private var metas: List<TrackMeta> = emptyList()
    private var index = 0
    private var positionMs = 0L
    private var liveDurationMs = -1L
    private var playWhen = false
    private var playbackState = Player.STATE_IDLE
    private var userVolume = 1f
    private var duck = 1f
    private var shuffle = false
    private var pausedByFocus = false
    private var lastError: PlaybackException? = null
    private var lastSaveMs = 0L

    // ---- Control-thread state. Only code running on the control thread reads or writes these. ----
    private var engine: PlayerEngine? = null
    private var ctlUris: List<Uri> = emptyList()
    private var ctlIndex = 0
    private var ctlQueuedNext: Int? = null
    private var ctlShuffle = false
    private var ctlStarted = false
    private var ctlLastSwitch = 0
    private var ctlVolume = 1f

    private val poll = Runnable { ctlPoll() }

    private val burstCheck = Runnable {
        mapper.resolveExpired()?.let { performAction(it) }
    }

    init {
        // Read the crash flag before it is overwritten below.
        val crashed = store.crashRecoveryNeeded()
        val saved: SavedPlayback? = store.readPlayback()
        store.cleanExit = false
        if (saved != null) {
            userVolume = saved.volume
            shuffle = saved.shuffle
            val restoredUris = saved.uris.map { Uri.parse(it) }
            // Restore the queue and position. Auto-resume only after a crash, so a normal restart stays paused.
            mainHandler.post { applyItems(restoredUris, saved.index, saved.positionMs, autoStart = crashed) }
        }
    }

    // ======================================================================================
    // SimpleBasePlayer state and commands (main thread)
    // ======================================================================================

    override fun getState(): SimpleBasePlayer.State {
        val playlist = buildPlaylist()
        val builder = SimpleBasePlayer.State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaylist(playlist)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(playWhen, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setVolume(userVolume)
            .setShuffleModeEnabled(shuffle)
        if (playlist.isNotEmpty()) {
            builder.setCurrentMediaItemIndex(index.coerceIn(0, playlist.size - 1))
                .setContentPositionMs(positionMs)
        }
        lastError?.let { builder.setPlayerError(it) }
        return builder.build()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val list = mediaItems.mapNotNull { it.localConfiguration?.uri }
        val start = if (startIndex in list.indices) startIndex else 0
        val startMs = if (startPositionMs == C.TIME_UNSET || startPositionMs < 0) 0L else startPositionMs
        applyItems(list, start, startMs, autoStart = playWhen)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (uris.isNotEmpty() && playbackState == Player.STATE_IDLE) {
            // After a load error, prepare retries the load. READY must never be paired with an error.
            if (lastError != null) {
                jumpTo(index, positionMs)
            } else {
                playbackState = Player.STATE_READY
                invalidateState()
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        setPlayIntent(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val ms = if (positionMs == C.TIME_UNSET || positionMs < 0) 0L else positionMs
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> skipToNext()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> skipToPrevious()
            else -> if (mediaItemIndex in uris.indices && mediaItemIndex != index) {
                jumpTo(mediaItemIndex, ms)
            } else {
                seekInCurrent(ms)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        userVolume = volume.coerceIn(0f, 1f)
        pushVolume()
        invalidateState()
        save()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        setShuffle(shuffleModeEnabled)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playWhen = false
        pausedByFocus = false
        positionMs = 0L
        focus.setNoisyReceiverActive(false)
        focus.abandon()
        control.post { ctlStop() }
        playbackState = if (uris.isEmpty()) Player.STATE_IDLE else Player.STATE_READY
        store.cleanExit = true // deliberate stop
        invalidateState()
        save()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        focus.setNoisyReceiverActive(false)
        focus.abandon()
        control.post {
            control.removeCallbacks(poll)
            engine?.close()
            engine = null
        }
        controlThread.quitSafely()
        metaExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        return Futures.immediateVoidFuture()
    }

    // ======================================================================================
    // Hardware and headset media keys (main thread, called by PlaybackService)
    // ======================================================================================

    /** Runs the click mapping (feature 1.6) for one key-down event. */
    fun onMediaKey(keyCode: Int) {
        mapper.mapping = store.readMapping()
        for (action in mapper.onKey(keyCode)) performAction(action)
        scheduleBurstCheck()
    }

    private fun scheduleBurstCheck() {
        val deadline = mapper.pendingDeadlineMs() ?: return
        mainHandler.removeCallbacks(burstCheck)
        mainHandler.postDelayed(burstCheck, (deadline - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    private fun performAction(action: ButtonAction) {
        when (action) {
            ButtonAction.PLAY_PAUSE -> setPlayIntent(!playWhen)
            ButtonAction.NEXT -> skipToNext()
            ButtonAction.PREVIOUS -> skipToPrevious()
            ButtonAction.SHUFFLE -> setShuffle(!shuffle)
            ButtonAction.NONE -> Unit
        }
    }

    // ======================================================================================
    // Main-thread helpers
    // ======================================================================================

    /** Replaces the queue and starts loading at [start]. Used by commands, and by the restore path. */
    private fun applyItems(list: List<Uri>, start: Int, startMs: Long, autoStart: Boolean) {
        uris = list
        metas = list.map { TrackMeta.fallback(it) }
        index = if (start in list.indices) start else 0
        positionMs = startMs
        liveDurationMs = -1L
        lastError = null
        if (list.isEmpty()) {
            playWhen = false
            playbackState = Player.STATE_IDLE
            control.post { ctlStop() }
            invalidateState()
            return
        }
        val wantPlay = autoStart && focus.request()
        playWhen = wantPlay
        focus.setNoisyReceiverActive(wantPlay)
        playbackState = Player.STATE_BUFFERING
        val vol = effectiveVolume()
        val shuffleNow = shuffle
        val startNow = index
        control.post {
            ctlShuffle = shuffleNow
            val rc = ctlLoad(list, startNow, startMs, wantPlay, vol)
            mainHandler.post { onLoadResult(list, rc) }
        }
        loadMetadata(list)
        invalidateState()
        save()
    }

    private fun setPlayIntent(play: Boolean) {
        if (play) {
            if (playbackState == Player.STATE_ENDED) playbackState = Player.STATE_READY
            if (!focus.request()) {
                playWhen = false
                pausedByFocus = false
                invalidateState()
                return
            }
            playWhen = true
            pausedByFocus = false
            focus.setNoisyReceiverActive(true)
            control.post { ctlPlay() }
        } else {
            playWhen = false
            pausedByFocus = false
            focus.setNoisyReceiverActive(false)
            focus.abandon()
            control.post { ctlPause() }
        }
        invalidateState()
        save()
    }

    private fun setShuffle(enabled: Boolean) {
        shuffle = enabled
        control.post { ctlShuffle = enabled }
        invalidateState()
        save()
    }

    private fun skipToNext() {
    val n = pickNext(index, uris.size, shuffle) ?: return
    jumpTo(n, 0L)
}

private fun skipToPrevious() {
    if (positionMs > PREVIOUS_RESTART_MS || index <= 0) {
        seekInCurrent(0L)
    } else {
        jumpTo(index - 1, 0L)
    }
}

    private fun jumpTo(n: Int, ms: Long) {
        index = n
        positionMs = ms
        liveDurationMs = -1L
        lastError = null
        playbackState = Player.STATE_BUFFERING
        val list = uris
        val auto = playWhen
        val vol = effectiveVolume()
        val shuffleNow = shuffle
        control.post {
            ctlShuffle = shuffleNow
            val rc = ctlLoad(list, n, ms, auto, vol)
            mainHandler.post { onLoadResult(list, rc) }
        }
        invalidateState()
        save()
    }

    private fun seekInCurrent(ms: Long) {
        positionMs = ms
        control.post { engine?.seekTo(ms) }
        invalidateState()
    }

    private fun pushVolume() {
        val v = effectiveVolume()
        control.post {
            ctlVolume = v
            engine?.setVolume(v, VOLUME_RAMP_MS)
        }
    }

    private fun effectiveVolume(): Float = userVolume * duck

    private fun onLoadResult(list: List<Uri>, rc: Int) {
        if (list !== uris) return // a newer load has replaced this one
        if (rc == PlayerCode.OK) {
            lastError = null
            if (playbackState == Player.STATE_BUFFERING) playbackState = Player.STATE_READY
        } else {
            lastError = errorFor(rc)
            playbackState = Player.STATE_IDLE
            playWhen = false
            focus.setNoisyReceiverActive(false)
        }
        invalidateState()
    }

    private fun onSnapshot(s: Snapshot) {
        val indexChanged = s.index != index
        index = s.index
        positionMs = s.positionMs.coerceAtLeast(0L)
        liveDurationMs = s.durationMs
        if (s.ended && lastError == null) {
            playWhen = false
            playbackState = Player.STATE_ENDED
            focus.setNoisyReceiverActive(false)
            focus.abandon()
        }
        invalidateState()
        if (indexChanged || System.currentTimeMillis() - lastSaveMs > SAVE_INTERVAL_MS) save()
    }

    private fun onMetadataLoaded(list: List<Uri>, result: List<TrackMeta>) {
        if (list !== uris) return
        metas = result
        invalidateState()
    }

    private fun loadMetadata(list: List<Uri>) {
        metaExecutor.execute {
            val result = list.map { reader.read(it).meta }
            mainHandler.post { onMetadataLoaded(list, result) }
        }
    }

    private fun buildPlaylist(): List<SimpleBasePlayer.MediaItemData> = uris.mapIndexed { i, uri ->
        val meta = metas.getOrNull(i) ?: TrackMeta.fallback(uri)
        val md = MediaMetadata.Builder()
            .setTitle(meta.title)
            .setArtist(meta.artist)
            .setAlbumTitle(meta.album)
        meta.artwork?.let { md.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        val item = MediaItem.Builder()
            .setMediaId("$i:$uri")
            .setUri(uri)
            .setMediaMetadata(md.build())
            .build()
        val durationMs = if (i == index && liveDurationMs > 0) liveDurationMs else meta.durationMs
        SimpleBasePlayer.MediaItemData.Builder(i)
            .setMediaItem(item)
            .setDurationUs(if (durationMs > 0) durationMs * 1000L else C.TIME_UNSET)
            .setIsSeekable(true)
            .build()
    }

    private fun save() {
        lastSaveMs = System.currentTimeMillis()
        store.savePlayback(
            SavedPlayback(
                uris = uris.map { it.toString() },
                index = index.coerceAtLeast(0),
                positionMs = positionMs,
                volume = userVolume,
                wasPlaying = playWhen,
                shuffle = shuffle,
            )
        )
    }

    // ======================================================================================
    // Control-thread side. Every function here runs on the "futura-control" thread only.
    // ======================================================================================

    private fun ctlEnsureEngine(): PlayerEngine? {
        engine?.let { return it }
        val created = PlayerEngine()
        if (created.open() != PlayerCode.OK) {
            created.close()
            return null
        }
        engine = created
        ctlLastSwitch = created.switchCount()
        control.post(poll)
        return created
    }

    /** Opens track [idx] and queues the one after it. Returns a PlayerCode. */
    private fun ctlLoad(list: List<Uri>, idx: Int, startMs: Long, autoStart: Boolean, volume: Float): Int {
        ctlUris = list
        if (idx !in list.indices) return PlayerCode.ERR_INVALID_STATE
        val e = ctlEnsureEngine() ?: return PlayerCode.ERR_OUTPUT
        ctlIndex = idx
        ctlQueuedNext = null
        ctlStarted = false
        val fd = SourceOpener.openNativeOwnedFd(appContext, list[idx]) ?: return PlayerCode.ERR_IO
        val rc = e.loadCurrent(fd, 0L, -1L, startMs)
        if (rc != PlayerCode.OK) return rc
        ctlVolume = volume
        e.setVolume(volume, 0)
        ctlLastSwitch = e.switchCount()
        ctlQueueNext()
        if (autoStart) ctlPlay()
        return PlayerCode.OK
    }

    private fun ctlQueueNext() {
        val e = engine ?: return
        val n = pickNext(ctlIndex, ctlUris.size, ctlShuffle)
        ctlQueuedNext = null
        // Drop any track queued before the queue changed (for example, before a jump), so the engine cannot play it.
        e.clearNext()
        if (n == null) return
        val fd = SourceOpener.openNativeOwnedFd(appContext, ctlUris[n]) ?: return
        // loadNext owns the descriptor even when it fails.
        if (e.loadNext(fd) == PlayerCode.OK) ctlQueuedNext = n
    }

    private fun ctlPlay() {
        val e = ctlEnsureEngine() ?: return
        if (ctlUris.isEmpty()) return
        if (e.isEnded()) {
            // The queue has finished. Playing again reloads the last track from the start, then falls through to start.
            // autoStart is false here, so ctlLoad does not call back into ctlPlay.
            if (ctlLoad(ctlUris, ctlIndex, 0L, autoStart = false, volume = ctlVolume) != PlayerCode.OK) return
        }
        if (ctlStarted) {
            e.resume()
        } else if (e.start() == PlayerCode.OK) {
            ctlStarted = true
        }
    }

    private fun ctlPause() {
        engine?.pause()
    }

    private fun ctlStop() {
        engine?.stop()
        ctlStarted = false
    }

    private fun ctlPoll() {
        val e = engine ?: return
        val sc = e.switchCount()
        if (sc != ctlLastSwitch) {
            ctlLastSwitch = sc
            val queued = ctlQueuedNext
            if (queued != null) {
                ctlIndex = queued
                ctlQueueNext()
            }
        }
        val ended = e.isEnded()
        if (ended) ctlStarted = false
        val snap = Snapshot(
            index = ctlIndex,
            positionMs = e.positionMs(),
            durationMs = e.durationMs(),
            playing = e.isPlaying(),
            ended = ended,
        )
        mainHandler.post { onSnapshot(snap) }
        control.postDelayed(poll, POLL_MS)
    }

    /** Control thread to main thread. Plain data, so it is safe to pass between threads. */
    private class Snapshot(
        val index: Int,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val ended: Boolean,
    )

    companion object {
        private const val POLL_MS = 250L
        private const val SAVE_INTERVAL_MS = 5_000L
        private const val PREVIOUS_RESTART_MS = 3_000L
        private const val VOLUME_RAMP_MS = 30
        private const val DUCK_VOLUME = 0.3f

        private val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_SET_SHUFFLE_MODE,
            )
            .build()

        private fun errorFor(rc: Int): PlaybackException {
            val code = when (rc) {
                PlayerCode.ERR_UNSUPPORTED -> PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                PlayerCode.ERR_IO -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                else -> PlaybackException.ERROR_CODE_DECODING_FAILED
            }
            return PlaybackException("Could not play this track (engine code $rc)", null, code)
        }
    }
}

/**
 * The next track after [from], or null at the end of the queue. With [shuffle] on, it picks any other track at random.
 * Top-level and pure, so the main and control sides agree on the rule.
 */
internal fun pickNext(from: Int, size: Int, shuffle: Boolean): Int? {
    if (size <= 0 || from < 0 || from >= size) return null
    if (shuffle && size > 1) {
        val r = Random.nextInt(size - 1)
        return if (r >= from) r + 1 else r
    }
    return if (from + 1 < size) from + 1 else null
}
