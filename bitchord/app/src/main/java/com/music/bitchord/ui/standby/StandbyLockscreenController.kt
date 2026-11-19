package com.music.bitchord.ui.standby

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.music.bitchord.MainActivity
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates automatic StaaaaandBy Lock-Screen Lyric Showcase activation when the screen turns
 * off during active playback, guarded by a 3-second cooldown to prevent power-button relaunch loops.
 */
object StandbyLockscreenController {

    const val EXTRA_LOCKSCREEN_STANDBY = "com.aurex.music.EXTRA_LOCKSCREEN_STANDBY"
    private const val SCREEN_OFF_LAUNCH_DELAY_MS = 450L
    private const val SIDE_KEY_COOLDOWN_MS = 3_000L
    private const val WAKE_LOCK_TIMEOUT_MS = 2_500L

    @Volatile
    var lastVisibleAtElapsedMs: Long = 0L

    @Volatile
    var isCurrentlyVisible: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private var pendingLaunch: Runnable? = null

    private val triggerCounter = MutableStateFlow(0L)
    val triggers: StateFlow<Long> = triggerCounter.asStateFlow()

    fun markVisible(visible: Boolean) {
        isCurrentlyVisible = visible
        lastVisibleAtElapsedMs = SystemClock.elapsedRealtime()
    }

    fun notifyIntentReceived() {
        triggerCounter.value = SystemClock.elapsedRealtime()
    }

    fun onScreenOff(
        context: Context,
        isPlaying: Boolean,
        isStillPlaying: () -> Boolean = { isPlaying },
    ) {
        if (!isPlaying || !AppSettings.lockscreenLyricsEnabled.value) return
        val sinceVisible = SystemClock.elapsedRealtime() - lastVisibleAtElapsedMs
        if (isCurrentlyVisible || sinceVisible < SIDE_KEY_COOLDOWN_MS) return

        pendingLaunch?.let(handler::removeCallbacks)
        val appContext = context.applicationContext
        val launchRunnable = Runnable {
            pendingLaunch = null
            if (!AppSettings.lockscreenLyricsEnabled.value || !isStillPlaying()) return@Runnable
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            if (powerManager?.isInteractive == true) return@Runnable
            if (!Settings.canDrawOverlays(appContext)) return@Runnable

            @Suppress("DEPRECATION")
            runCatching {
                powerManager?.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "Aurex:StandbyLockscreen",
                )?.acquire(WAKE_LOCK_TIMEOUT_MS)
            }

            runCatching {
                appContext.startActivity(
                    Intent(appContext, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION,
                        )
                        putExtra(EXTRA_LOCKSCREEN_STANDBY, true)
                    },
                )
            }
        }
        pendingLaunch = launchRunnable
        handler.postDelayed(launchRunnable, SCREEN_OFF_LAUNCH_DELAY_MS)
    }

    fun cancelPendingLaunch() {
        pendingLaunch?.let(handler::removeCallbacks)
        pendingLaunch = null
    }
}
