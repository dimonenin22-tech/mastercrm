package com.mastercrm.data.service

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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.mastercrm.MainActivity
import com.mastercrm.MasterCrmApp
import kotlinx.coroutines.*

class BookingSyncService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    companion object {
        const val CHANNEL_ID = "mastercrm_bg_sync"
        const val NOTIFICATION_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JuNailCRM Синхронізація",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновий моніторинг нових записів 24/7"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JuNailCRM Синхронізація")
            .setContentText("Фоновий моніторинг нових записів 24/7")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (syncJob == null || syncJob?.isActive != true) {
            syncJob = serviceScope.launch {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                while (isActive) {
                    val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JuNailCRM:SyncWakeLock")
                    try {
                        wakeLock?.acquire(10_000L)
                        val app = MasterCrmApp.instance
                        if (app.authRepository.isLoggedIn()) {
                            app.syncRepository.performSync()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("BookingSyncService", "Background sync exception", e)
                    } finally {
                        if (wakeLock?.isHeld == true) {
                            wakeLock.release()
                        }
                    }
                    delay(25_000L)
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val restartServiceIntent = Intent(applicationContext, SyncBroadcastReceiver::class.java).apply {
                action = SyncBroadcastReceiver.ACTION_RESTART_SYNC
            }
            val restartPendingIntent = PendingIntent.getBroadcast(
                this, 1, restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmService = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            alarmService?.set(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 2000,
                restartPendingIntent
            )
        } catch (e: Exception) {
            android.util.Log.e("BookingSyncService", "Failed to schedule restart", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
