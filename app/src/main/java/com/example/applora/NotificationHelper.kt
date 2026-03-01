package com.example.applora

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Gestion des notifications : connexion active et message reçu en arrière-plan.
 */
object NotificationHelper {

    const val CHANNEL_CONNECTION_ID = "bluetooth_connection"
    const val CHANNEL_MESSAGES_ID = "bluetooth_messages"
    private const val NOTIFICATION_CONNECTION_ID = 1001
    private const val NOTIFICATION_MESSAGE_ID = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CONNECTION_ID,
                context.getString(R.string.notification_channel_connection),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES_ID,
                context.getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { setShowBadge(true); enableVibration(true) }
        )
    }

    /**
     * Affiche une notification persistante quand une connexion Bluetooth est active.
     */
    fun showConnectionNotification(context: Context, deviceName: String?) {
        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_CONNECTION_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(context.getString(R.string.notification_connected_title))
            .setContentText(deviceName ?: context.getString(R.string.notification_connected_device_unknown))
            .setOngoing(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_CONNECTION_ID, notification)
    }

    /**
     * Masque la notification de connexion active.
     */
    fun cancelConnectionNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_CONNECTION_ID)
    }

    /**
     * Affiche une notification quand un message est reçu et que l'app est en arrière-plan.
     */
    fun showMessageNotification(context: Context, message: String, deviceName: String?) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = deviceName ?: context.getString(R.string.notification_message_from_unknown)
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(if (message.length > 80) message.take(80) + "…" else message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_MESSAGE_ID, notification)
    }
}
