package com.mastercrm.ui.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mastercrm.MainActivity

object BookingNotificationHelper {

    const val CHANNEL_ID = "mastercrm_bookings"
    const val CHANNEL_NAME = "Записи клієнтів"
    const val CHANNEL_DESC = "Сповіщення про нові онлайн-записи через Telegram-бота"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESC
                enableVibration(true)
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showNewBookingNotification(
        context: Context,
        appointmentId: String,
        clientName: String,
        clientPhone: String,
        serviceTitle: String,
        date: String,
        startTime: String,
        price: Double
    ) {
        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "💅 Новий запис: $clientName"
        val message = "$serviceTitle • $date о $startTime (${price.toInt()} ₴)"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.mastercrm.R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Клієнт: $clientName ($clientPhone)\nПослуга: $serviceTitle\nДата: $date о $startTime\nСума: ${price.toInt()} ₴"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            val notifId = appointmentId.hashCode()
            notificationManager.notify(notifId, builder.build())
        } catch (e: SecurityException) {
            // Permission POST_NOTIFICATIONS not granted yet
        } catch (e: Exception) {
            // Safe fallback
        }
    }
}
