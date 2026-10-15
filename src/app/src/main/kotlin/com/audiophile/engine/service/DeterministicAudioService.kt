package com.audiophile.engine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

class DeterministicAudioService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        init {
            System.loadLibrary("audiophile_engine")
        }
    }

    private external fun nativeInitCore(): Boolean

    override fun onCreate() {
        super.onCreate()
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AudiophileEngine::RealtimeAudioLock"
        ).apply {
            acquire(24 * 60 * 60 * 1000L)
        }

        startForegroundServiceNotification()
        ensureSamsungOneUiExemption()
        nativeInitCore()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "audiophile_bitperfect_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "Bit-Perfect Playback Core",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Bit-Perfect Audio Engine Active")
            .setContentText("Direct hardware output active with 64-bit studio DSP")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(1001, notification)
        }
    }

    private fun ensureSamsungOneUiExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }

        try {
            val samsungIntent = Intent().apply {
                action = "com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY"
                setPackage("com.samsung.android.lool")
                putExtra("activity_type", 2)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(samsungIntent)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
