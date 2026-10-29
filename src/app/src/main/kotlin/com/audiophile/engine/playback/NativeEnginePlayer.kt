package com.audiophile.engine.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.net.Uri
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.util.UnstableApi
import com.audiophile.engine.model.DspRackState
import com.audiophile.engine.model.FilterTopology
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Media3 Player backed by the native engine. Media3 gets the session, notification,
 * lock screen and headset controls; the C++ engine does decoding and output.
 *
 * All methods run on the main looper. Opening a file (fd + extractor setup) happens
 * on a background executor and completes the returned future.
 */
@UnstableApi
class NativeEnginePlayer(private val context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val engine = NativeAudioEngine()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private var playlist: List<MediaItem> = emptyList()
    private var metadata: List<MediaMetadata> = emptyList()
    private var currentIndex = 0
    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var preparedIndex = -1
    private var durationMs = 0L
    private var pausedByTransientFocusLoss = false
    private var seenTransitions = 0
    // Bumped whenever the open track changes, so a stale background open is dropped.
    private var openGeneration = 0

    private var transitions = TransitionSettings()
    private var queuedAllowFade = true   // what the queued track was handed to the engine with
    private val _mixing = MutableStateFlow(false)
    /** True while two tracks are being crossfaded: the audio is a mix, not the files' own samples. */
    val mixing: StateFlow<Boolean> = _mixing.asStateFlow()

    private val router = BitPerfectRouter(audioManager)
    private var bitPerfectEnabled = true
    // The EQ is changing samples, so the shared stream is opened in float (no requantizing on our side).
    private var eqActive = false
    private val _route = MutableStateFlow(BitPerfectRouter.SHARED)
    /** Current output route: bit-perfect USB or the shared mixer. */
    val route: StateFlow<BitPerfectRouter.Route> = _route.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        // A USB DAC was plugged in or removed: pick the route again for the open track.
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = applyRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = applyRoute()
    }

    private val _engineFormat = MutableStateFlow<NativeAudioEngine.Format?>(null)
    /** Format of the open track as the engine sees it (source and device rates). */
    val engineFormat: StateFlow<NativeAudioEngine.Format?> = _engineFormat.asStateFlow()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, mainHandler)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            // Headphones unplugged or Bluetooth dropped: pause instead of blasting the speaker.
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) setPlayWhenReady(false)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (preparedIndex >= 0) {
                val changes = engine.pollTransitions()
                if (changes > seenTransitions) onGaplessTransition(changes)
            }
            val mixingNow = preparedIndex >= 0 && engine.isMixing
            if (_mixing.value != mixingNow) _mixing.value = mixingNow
            if (engine.state == NativeAudioEngine.State.ENDED && playbackState == Player.STATE_READY) {
                onTrackEnded()
            }
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    init {
        ContextCompat.registerReceiver(
            context, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        mainHandler.post(ticker)
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        // The service can start without the UI (headset button, Bluetooth): use the saved settings.
        setTransitions(TransitionSettingsStore(context).load())
    }

    /** Turns Android 14 bit-perfect USB output on or off (the shared mixer is used when off). */
    fun setBitPerfectEnabled(enabled: Boolean) {
        bitPerfectEnabled = enabled
        applyRoute()
    }

    /**
     * Applies how tracks hand over to each other. Takes effect for the queued track as well: it is
     * handed to the engine again when the new settings change whether it may be faded into.
     */
    fun setTransitions(settings: TransitionSettings) {
        transitions = settings
        engine.setCrossfade(settings.crossfadeEnabled, settings.crossfadeMs, settings.curve)
        if (preparedIndex >= 0 && allowFadeInto(currentIndex + 1) != queuedAllowFade) preloadNext()
    }

    /** Key that tells whether two items belong to one album; null when unknown. */
    fun albumKeyOf(index: Int): String? = albumKey(playlist.getOrNull(index))

    /** Album key of the track that is playing now. */
    fun currentAlbumKey(): String? = albumKeyOf(currentIndex)

    private fun albumKey(item: MediaItem?): String? {
        val meta = item?.mediaMetadata ?: return null
        val id = meta.extras?.getLong(EXTRA_ALBUM_ID, NO_ALBUM) ?: NO_ALBUM
        if (id != NO_ALBUM) return "id:$id"
        return meta.albumTitle?.toString()?.takeIf { it.isNotBlank() }?.let { "t:${it.lowercase()}" }
    }

    private fun allowFadeInto(nextIndex: Int): Boolean =
        transitions.allowsFade(albumKeyOf(nextIndex - 1), albumKeyOf(nextIndex))

    /**
     * Applies the DSP rack's parametric EQ in the engine. A preamp equal to the largest boost
     * keeps the curve from clipping. Flat or bypassed leaves the stream bit-perfect.
     */
    fun setDsp(state: DspRackState) {
        val enabled = state.peqEnabled && !state.isMasterBypassed
        val bands = DoubleArray(state.peqBands.size * 5)
        state.peqBands.forEachIndexed { i, band ->
            bands[i * 5] = when (band.topology) {
                FilterTopology.PEAKING -> 0.0
                FilterTopology.LOW_SHELF -> 1.0
                FilterTopology.HIGH_SHELF -> 2.0
                FilterTopology.LOW_PASS -> 3.0
                FilterTopology.HIGH_PASS -> 4.0
            }
            bands[i * 5 + 1] = band.frequencyHz.toDouble()
            bands[i * 5 + 2] = band.gainDb.toDouble()
            bands[i * 5 + 3] = band.qFactor.toDouble()
            bands[i * 5 + 4] = if (band.isEnabled) 1.0 else 0.0
        }
        val maxBoostDb = if (!enabled) 0.0 else (0..120).maxOf { step ->
            val hz = 20.0 * Math.pow(1000.0, step / 120.0)  // 20 Hz to 20 kHz, log spaced
            state.evaluateTotalEqMagnitudeDb(hz.toFloat())
        }.coerceAtLeast(0.0)
        engine.setEq(enabled, -maxBoostDb, bands)
        val active = state.isAnyStageActive
        if (active != eqActive) {
            eqActive = active
            applyRoute()
        }
    }

    private fun applyRoute() {
        if (preparedIndex < 0) return
        val format = engine.format
        val route = router.routeFor(format.sampleRateHz, format.bitDepth, bitPerfectEnabled)
        val encoding = if (!route.bitPerfect && eqActive) AudioFormat.ENCODING_PCM_FLOAT else route.encoding
        engine.setOutputFormat(encoding, route.bitPerfect)
        _route.value = route
        _engineFormat.value = engine.format
    }

    override fun getState(): SimpleBasePlayer.State {
        val items = playlist.mapIndexed { i, item ->
            MediaItemData.Builder(item.mediaId.ifEmpty { "item-$i" } + "#$i")
                .setMediaItem(item)
                .setMediaMetadata(metadata.getOrNull(i) ?: item.mediaMetadata)
                .setDurationUs(if (i == preparedIndex && durationMs > 0) durationMs * 1000 else C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }
        return SimpleBasePlayer.State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
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
                        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                        Player.COMMAND_SET_MEDIA_ITEM,
                        Player.COMMAND_CHANGE_MEDIA_ITEMS,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_TIMELINE,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_RELEASE
                    )
                    .build()
            )
            .setPlaylist(items)
            .setCurrentMediaItemIndex(if (items.isEmpty()) 0 else currentIndex.coerceIn(0, items.size - 1))
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else playbackState)
            .setContentPositionMs { if (preparedIndex == currentIndex) engine.positionMs else 0L }
            .build()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        playlist = mediaItems.toList()
        metadata = playlist.map { it.mediaMetadata }
        currentIndex = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        engine.stop()
        preparedIndex = -1
        durationMs = 0
        loadMetadata(playlist)
        return if (playbackState == Player.STATE_IDLE) {
            Futures.immediateVoidFuture()
        } else {
            openCurrent(startPositionMs.coerceAtLeast(0))
        }
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (playlist.isEmpty()) return Futures.immediateVoidFuture()
        playbackState = Player.STATE_BUFFERING
        return openCurrent(0)
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playWhenReady) {
            pausedByTransientFocusLoss = false
            if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                this.playWhenReady = false
                return Futures.immediateVoidFuture()
            }
            if (playbackState == Player.STATE_ENDED) {
                currentIndex = 0
                return openCurrent(0)
            }
            if (preparedIndex == currentIndex) engine.play()
        } else {
            engine.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = mediaItemIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        val position = if (positionMs == C.TIME_UNSET) 0L else positionMs
        return if (target == currentIndex && preparedIndex == currentIndex) {
            engine.seekTo(position)
            if (playbackState == Player.STATE_ENDED) playbackState = Player.STATE_READY
            Futures.immediateVoidFuture()
        } else {
            currentIndex = target
            openCurrent(position)
        }
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.stop()
        preparedIndex = -1
        playbackState = Player.STATE_IDLE
        audioManager.abandonAudioFocusRequest(focusRequest)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        mainHandler.removeCallbacks(ticker)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        router.clear()
        runCatching { context.unregisterReceiver(noisyReceiver) }
        audioManager.abandonAudioFocusRequest(focusRequest)
        engine.stop()
        engine.destroy()
        ioExecutor.shutdown()
        return Futures.immediateVoidFuture()
    }

    private fun openCurrent(startPositionMs: Long): ListenableFuture<*> {
        val item = playlist.getOrNull(currentIndex) ?: return Futures.immediateVoidFuture()
        val uri = item.localConfiguration?.uri ?: return Futures.immediateVoidFuture()
        val index = currentIndex
        val generation = ++openGeneration
        playbackState = Player.STATE_BUFFERING
        val future = com.google.common.util.concurrent.SettableFuture.create<Unit>()
        ioExecutor.execute {
            val fd = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.detachFd()
            } catch (e: Exception) {
                Log.e(TAG, "Cannot open $uri", e)
                null
            }
            mainHandler.post {
                if (index != currentIndex || generation != openGeneration) {
                    // The user moved on while this file was opening.
                    fd?.let { android.os.ParcelFileDescriptor.adoptFd(it).close() }
                    future.set(Unit)
                    return@post
                }
                val opened = fd != null && engine.open(fd)
                if (opened) {
                    preparedIndex = index
                    seenTransitions = 0
                    durationMs = engine.durationMs
                    applyRoute()
                    if (startPositionMs > 0) engine.seekTo(startPositionMs)
                    playbackState = Player.STATE_READY
                    if (playWhenReady) engine.play()
                    preloadNext()
                } else {
                    Log.e(TAG, "Engine could not open $uri")
                    preparedIndex = -1
                    playbackState = Player.STATE_IDLE
                }
                future.set(Unit)
                invalidateState()
            }
        }
        return future
    }

    /** Opens the following item in the background and hands it to the engine for a gapless change. */
    private fun preloadNext() {
        val nextIndex = currentIndex + 1
        val uri = playlist.getOrNull(nextIndex)?.localConfiguration?.uri
        if (uri == null) {
            engine.clearNext()
            return
        }
        val generation = openGeneration
        ioExecutor.execute {
            val fd = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.detachFd()
            } catch (e: Exception) {
                Log.w(TAG, "Cannot preload $uri", e)
                null
            }
            mainHandler.post {
                if (fd == null) return@post
                if (generation != openGeneration || nextIndex != currentIndex + 1) {
                    android.os.ParcelFileDescriptor.adoptFd(fd).close()
                    return@post
                }
                // A different sample rate is not queued by the engine; that track opens normally at the end.
                val allowFade = allowFadeInto(nextIndex)
                queuedAllowFade = allowFade
                if (!engine.setNext(fd, allowFade)) Log.w(TAG, "Engine could not preload $uri")
            }
        }
    }

    /** The engine moved into the queued track, without a gap or with a crossfade. */
    private fun onGaplessTransition(count: Int) {
        val previous = _engineFormat.value
        seenTransitions = count
        currentIndex += 1
        preparedIndex = currentIndex
        openGeneration++
        durationMs = engine.durationMs
        val format = engine.format
        _engineFormat.value = format
        // On the shared route the engine reopens itself for a new format; pick the route again so
        // a DAC with a bit-perfect mixer at the new rate gets it.
        if (previous != null && (previous.sampleRateHz != format.sampleRateHz || previous.bitDepth != format.bitDepth)) {
            applyRoute()
        }
        preloadNext()
        invalidateState()
    }

    private fun onTrackEnded() {
        if (currentIndex + 1 < playlist.size) {
            currentIndex += 1
            openCurrent(0)
        } else {
            playbackState = Player.STATE_ENDED
            playWhenReady = false
            engine.pause()
        }
        invalidateState()
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByTransientFocusLoss = false
                setPlayWhenReady(false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // No ducking: a bit-perfect stream should not be scaled. Pause and resume after.
                if (playWhenReady) {
                    pausedByTransientFocusLoss = true
                    playWhenReady = false
                    engine.pause()
                    invalidateState()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByTransientFocusLoss) {
                pausedByTransientFocusLoss = false
                playWhenReady = true
                if (preparedIndex == currentIndex) engine.play()
                invalidateState()
            }
        }
    }

    /** Reads tags and artwork in the background so the notification and lock screen show them. */
    private fun loadMetadata(items: List<MediaItem>) {
        val snapshot = items
        ioExecutor.execute {
            val loaded = snapshot.map { item -> readTags(item) }
            mainHandler.post {
                if (playlist === snapshot) {
                    metadata = loaded
                    invalidateState()
                }
            }
        }
    }

    private fun readTags(item: MediaItem): MediaMetadata {
        val uri = item.localConfiguration?.uri ?: return item.mediaMetadata
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            // Tag, then the title the library already knows, then the file name. A content:// URI's last
            // segment is the MediaStore id, never a name.
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: item.mediaMetadata.title?.toString()
                ?: displayName(uri)
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
                .setAlbumTitle(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
                .setArtworkData(retriever.embeddedPicture, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .build()
        } catch (e: Exception) {
            item.mediaMetadata
        } finally {
            retriever.release()
        }
    }

    private fun displayName(uri: Uri): String? {
        val raw = if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()
        } else {
            uri.lastPathSegment
        }
        return raw?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val TAG = "NativeEnginePlayer"
        const val TICK_MS = 250L
        const val NO_ALBUM = -1L
    }
}
