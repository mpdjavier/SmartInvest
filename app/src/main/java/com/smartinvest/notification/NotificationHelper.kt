package com.smartinvest.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.smartinvest.MainActivity
import com.smartinvest.R
import com.smartinvest.domain.model.AppAlert
import com.smartinvest.domain.model.AlertSeverity

object NotificationHelper {

    const val CHANNEL_ID = "smartinvest_alerts"
    private const val NOTIFICATION_ID = 4201

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_alerts_desc)
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyAlerts(context: Context, alerts: List<AppAlert>) {
        if (alerts.isEmpty()) return
        if (!notificationsAllowed(context)) return

        ensureChannel(context)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val critical = alerts.any { it.severity == AlertSeverity.CRITICAL }
        val title = if (alerts.size == 1) alerts.first().title else "${alerts.size} alertas financieras"
        val summary = if (alerts.size == 1) {
            alerts.first().message
        } else {
            alerts.joinToString(" · ") { it.title }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(if (critical) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp)

        if (alerts.size > 1) {
            val inbox = NotificationCompat.InboxStyle()
            alerts.take(5).forEach { inbox.addLine(it.title) }
            builder.setStyle(inbox)
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
