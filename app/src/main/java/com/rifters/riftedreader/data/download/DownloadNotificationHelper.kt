package com.rifters.riftedreader.data.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

internal class DownloadNotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "book_downloads"
        private const val CHANNEL_NAME = "Book Downloads"
        private const val CHANNEL_DESC =
            "Progress and completion notices for book downloads"
    }

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = CHANNEL_DESC }
            )
        }
    }

    fun notifyProgress(notifId: Int, filename: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(filename)
            .setContentText("Downloading…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
        notifyIfAllowed(notifId, n)
    }

    fun notifySuccess(notifId: Int, bookTitle: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(bookTitle)
            .setContentText("Download complete")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notifyIfAllowed(notifId, n)
    }

    fun notifyFailure(notifId: Int, reason: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download failed")
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notifyIfAllowed(notifId, n)
    }

    private fun notifyIfAllowed(notifId: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                NotificationManagerCompat.from(context).notify(notifId, notification)
            }
        }
    }

    fun cancel(notifId: Int) =
        NotificationManagerCompat.from(context).cancel(notifId)
}
