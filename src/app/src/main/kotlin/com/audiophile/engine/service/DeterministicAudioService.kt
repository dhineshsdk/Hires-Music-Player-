package com.audiophile.engine.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.audiophile.engine.MainActivity
import com.audiophile.engine.playback.NativeEnginePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Playback service. Media3 runs the foreground notification, lock screen and
 * headset/Bluetooth buttons; audio is produced by the native engine through
 * [NativeEnginePlayer].
 */
@UnstableApi
class DeterministicAudioService : MediaSessionService() {

    private var session: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateWakeLock(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val player = session?.player
            val isPlaying = player?.isPlaying == true
            updateWakeLock(isPlaying && playbackState == Player.STATE_READY)
        }
    }

    private fun updateWakeLock(acquire: Boolean) {
        val wl = wakeLock ?: return
        if (acquire) {
            if (!wl.isHeld) {
                runCatching { wl.acquire(3600_000L) } // 1 hour max safety timeout
            }
        } else {
            if (wl.isHeld) {
                runCatching { wl.release() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeterministicAudioService:WakeLock")?.apply {
            setReferenceCounted(false)
        }

        val player = NativeEnginePlayer(this)
        player.addListener(playerListener)
        PlaybackHolder.player = player
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(UriRestoringCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        updateWakeLock(false)
        wakeLock = null
        session?.run {
            player.removeListener(playerListener)
            player.release()
            release()
        }
        session = null
        PlaybackHolder.player = null
        super.onDestroy()
    }

    /**
     * Controllers cannot pass a playable URI directly (Media3 strips it), so the app
     * sends it as requestMetadata.mediaUri and it is restored here.
     */
    private object UriRestoringCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri
                if (uri != null) item.buildUpon().setUri(uri).build() else item
            }.toMutableList()
        )
    }
}

/** Lets in-process UI read engine-only details (source and device sample rates). */
@UnstableApi
object PlaybackHolder {
    @Volatile var player: NativeEnginePlayer? = null
}
