package com.hotlaps.dynamic.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hotlaps.dynamic.HotLapsApp
import com.hotlaps.dynamic.MainActivity
import com.hotlaps.dynamic.R
import com.hotlaps.dynamic.util.RecordingHealth

/**
 * Keeps recording alive while an event is active: a foreground "location" service with a
 * persistent notification plus a partial wake lock, so sampling continues with the screen off,
 * the app in the background, or another screen open. It holds [RecordingEngine] for as long
 * as it runs.
 *
 * Started by DriveViewModel.startManualEvent(), stopped once the event has been finished.
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TITLE = "title"
        private const val HOLDER = "service"

        /** Safety net: a wake lock is never held longer than this (an endurance stint + margin). */
        private const val MAX_WAKE_MS = 12 * 60 * 60 * 1000L

        fun start(context: Context, title: String) {
            val intent = Intent(context, RecordingService::class.java).putExtra(EXTRA_TITLE, title)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // e.g. started from the background on Android 12+; recording still works while
                // the app stays in front, so log it rather than fail the event
                Log.e(TAG, "start failed", e)
                RecordingHealth.log("SERVICE start failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var holdingEngine = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Session"
        val notification = buildNotification(title)

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                else 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            RecordingHealth.log("SERVICE startForeground failed: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HotLaps:recording").apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_MS)
            }
        }

        if (!holdingEngine) {
            (application as HotLapsApp).recordingEngine.acquire(HOLDER)
            holdingEngine = true
        }

        RecordingHealth.log("SERVICE started ($title)")
        // Not sticky: if the process dies, the in-memory event is gone, so there is
        // nothing to resume automatically.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (holdingEngine) {
            (application as HotLapsApp).recordingEngine.release(HOLDER)
            holdingEngine = false
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        RecordingHealth.log("SERVICE stopped")
        super.onDestroy()
    }

    private fun buildNotification(title: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a session is being recorded"
                }
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ggmap)
            .setContentTitle("Recording: $title")
            .setContentText("Apex Dynamics is recording telemetry")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
