package com.sparkforge.reliefsync.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.sparkforge.reliefsync.MainActivity
import com.sparkforge.reliefsync.R

object Notifier {
    const val CH_DISPATCH = "dispatch"
    const val CH_STATUS = "status"
    const val CH_SYNC = "sync"
    const val OFFER_ID = 4100
    const val STATUS_ID = 4200

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_DISPATCH, "Dispatch requests", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Urgent volunteer dispatch requests"
            enableVibration(true); vibrationPattern = longArrayOf(0, 400, 150, 400, 150, 800)
            enableLights(true); lightColor = Color.RED
        })
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "SOS status", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Live status of your help request"
        })
        nm.createNotificationChannel(NotificationChannel(CH_SYNC, "Offline sync", NotificationManager.IMPORTANCE_LOW))
    }

    private fun canPost(ctx: Context) =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context, tab: String) = PendingIntent.getActivity(
        ctx, tab.hashCode(), Intent(ctx, MainActivity::class.java).putExtra("tab", tab)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Heads-up dispatch offer with inline ACCEPT / DECLINE actions and a countdown chronometer. */
    fun showOffer(ctx: Context, volunteerId: String, body: String, timeoutS: Double) {
        if (!canPost(ctx)) return
        fun action(accept: Boolean) = PendingIntent.getBroadcast(
            ctx, if (accept) 1 else 2,
            Intent(ctx, OfferActionReceiver::class.java).putExtra("volunteer", volunteerId).putExtra("accept", accept),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH_DISPATCH)
            .setSmallIcon(R.drawable.ic_sos)
            .setColor(0xFFFF4D5E.toInt()).setColorized(true)
            .setContentTitle("ReliefSync dispatch request")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setUsesChronometer(true).setChronometerCountDown(true)
            .setWhen(System.currentTimeMillis() + (timeoutS * 1000).toLong())
            .setTimeoutAfter((timeoutS * 1000).toLong())
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, "volunteer"))
            .setFullScreenIntent(openApp(ctx, "volunteer"), true)
            .addAction(0, "DECLINE", action(false))
            .addAction(0, "ACCEPT", action(true))
            .build()
        @Suppress("MissingPermission") NotificationManagerCompat.from(ctx).notify(OFFER_ID, n)
    }

    fun cancelOffer(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(OFFER_ID)

    /** Ongoing progress notification for the caller's own SOS (status + ETA). */
    fun showStatus(ctx: Context, title: String, text: String, progress: Int, ongoing: Boolean) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_sos)
            .setColor(0xFF2EE6A6.toInt())
            .setContentTitle(title).setContentText(text)
            .setOnlyAlertOnce(true).setOngoing(ongoing)
            .setProgress(100, progress, progress < 0)
            .setContentIntent(openApp(ctx, "ops"))
            .build()
        @Suppress("MissingPermission") NotificationManagerCompat.from(ctx).notify(STATUS_ID, n)
    }

    fun syncForegroundInfo(ctx: Context): ForegroundInfo = ForegroundInfo(
        4300,
        NotificationCompat.Builder(ctx, CH_SYNC).setSmallIcon(R.drawable.ic_sos)
            .setContentTitle("Sending queued SOS…").setProgress(0, 0, true).build(),
    )
}
