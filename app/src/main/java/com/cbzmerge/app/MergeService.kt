package com.cbzmerge.app

import android.annotation.SuppressLint
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the app alive while [MergeRunner] works,
 * and shows the progress in a notification. It stops on its own when the merge ends.
 */
class MergeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            MergeRunner.cancel()
            return START_NOT_STICKY
        }
        createChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, progressNotification(0f, "Starting…"), type)

        if (watcher == null) {
            watcher = scope.launch {
                var shown = ""
                MergeRunner.status.collectLatest { status ->
                    if (status !is MergeStatus.Working) return@collectLatest finish(status)
                    // Update at most once per percent, not on every page
                    val key = "${(status.progress * 100).toInt()} ${status.label.substringBefore(" · Chapter")}"
                    if (key != shown) {
                        shown = key
                        notify(progressNotification(status.progress, status.label))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun finish(status: MergeStatus) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val text = when (status) {
            is MergeStatus.Done -> {
                val files = status.files
                if (files.size == 1) "Saved ${files[0].fileName}" else "Saved ${files.size} files"
            }
            is MergeStatus.Failed -> status.message
            else -> null
        }
        if (text != null) {
            val title = if (status is MergeStatus.Done) "Merge finished" else "Merge stopped"
            notify(
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(openAppIntent())
                    .setAutoCancel(true)
                    .build(),
                DONE_NOTIFICATION_ID
            )
        }
        stopSelf()
    }

    private fun progressNotification(progress: Float, label: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Merging chapters")
            .setContentText(label)
            .setProgress(1000, (progress * 1000).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancel", cancelIntent())
            .build()

    @SuppressLint("MissingPermission")
    private fun notify(notification: Notification, id: Int = NOTIFICATION_ID) {
        // Without the notification permission the merge still runs, it just isn't shown
        runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this, 1,
        Intent(this, MergeService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        private const val CHANNEL_ID = "merge"
        private const val NOTIFICATION_ID = 1
        private const val DONE_NOTIFICATION_ID = 2
        private const val ACTION_CANCEL = "com.cbzmerge.app.CANCEL"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MergeService::class.java))
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Merging", NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
