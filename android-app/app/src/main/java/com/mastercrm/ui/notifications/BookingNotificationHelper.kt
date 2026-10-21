package com.mastercrm.ui.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mastercrm.MainActivity
import kotlin.math.abs

object BookingNotificationHelper {

    const val CHANNEL_ID = "mastercrm_bookings_v3"
    const val CHANNEL_NAME = "Онлайн-записи JuNailCRM"
    const val CHANNEL_DESC = "Миттєві сповіщення про нові онлайн-записи через Telegram-бота"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
                setSound(soundUri, audioAttributes)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableLights(true)
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

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val title = "💅 Новий онлайн-запис: $clientName"
        val message = "$serviceTitle • $date о $startTime (${price.toInt()} ₴)"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "👤 Клієнт: $clientName\n📞 Телефон: $clientPhone\n💅 Послуга: $serviceTitle\n📅 Дата: $date о $startTime\n💰 Сума: ${price.toInt()} ₴"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            val notifId = if (appointmentId.isNotEmpty()) abs(appointmentId.hashCode()) else 2001
            notificationManager.notify(notifId, builder.build())
        } catch (e: Exception) {
            android.util.Log.e("BookingNotification", "Error showing notification", e)
        }
    }

    fun sendTestNotification(context: Context) {
        showNewBookingNotification(
            context = context,
            appointmentId = "test_${System.currentTimeMillis()}",
            clientName = "Анна (Тестовий запис)",
            clientPhone = "+380991234567",
            serviceTitle = "Манікюр з покриттям гель-лак",
            date = "Сьогодні",
            startTime = "14:00",
            price = 650.0
        )
    }
}
