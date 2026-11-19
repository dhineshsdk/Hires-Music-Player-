package com.music.bitchord.ui.standby

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
    private const val SCREEN_OFF_LAUNCH_DELAY_MS = 650L
    private const val SCREEN_OFF_RETRY_DELAY_MS = 450L
    private const val SIDE_KEY_COOLDOWN_MS = 3_000L
    private const val WAKE_LOCK_TIMEOUT_MS = 3_500L

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

    fun consumeTrigger() {
        triggerCounter.value = 0L
    }

    fun triggerNow(context: Context, wakeScreen: Boolean) {
        val appContext = context.applicationContext
        if (wakeScreen) {
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            runCatching {
                powerManager?.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "Aurex:StandbyLockscreen",
                )?.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
        notifyIntentReceived()
        runCatching {
            appContext.startActivity(
                Intent(appContext, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION,
                    )
                    putExtra(EXTRA_LOCKSCREEN_STANDBY, true)
                },
            )
        }
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
        fun attemptLaunch(allowRetry: Boolean) {
            pendingLaunch = null
            if (!AppSettings.lockscreenLyricsEnabled.value || !isStillPlaying()) return
            if (isCurrentlyVisible) return
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            if (powerManager?.isInteractive == true) {
                if (allowRetry) {
                    val retryRunnable = Runnable { attemptLaunch(allowRetry = false) }
                    pendingLaunch = retryRunnable
                    handler.postDelayed(retryRunnable, SCREEN_OFF_RETRY_DELAY_MS)
                }
                return
            }
            triggerNow(appContext, wakeScreen = true)
        }
        val launchRunnable = Runnable { attemptLaunch(allowRetry = true) }
        pendingLaunch = launchRunnable
        handler.postDelayed(launchRunnable, SCREEN_OFF_LAUNCH_DELAY_MS)
    }

    fun onScreenOn(
        context: Context,
        isPlaying: Boolean,
    ) {
        cancelPendingLaunch()
        if (!isPlaying || !AppSettings.lockscreenLyricsEnabled.value) return
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true && !isCurrentlyVisible) {
            triggerNow(context.applicationContext, wakeScreen = false)
        }
    }

    fun cancelPendingLaunch() {
        pendingLaunch?.let(handler::removeCallbacks)
        pendingLaunch = null
    }
}
