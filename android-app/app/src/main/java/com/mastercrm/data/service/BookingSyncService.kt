package com.mastercrm.data.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.mastercrm.MasterCrmApp
import kotlinx.coroutines.*

class BookingSyncService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (syncJob == null || syncJob?.isActive != true) {
            syncJob = serviceScope.launch {
                while (isActive) {
                    try {
                        val app = MasterCrmApp.instance
                        if (app.authRepository.isLoggedIn()) {
                            app.syncRepository.performSync()
                        }
                    } catch (e: Exception) {
                        // Safe error recovery in background loop
                    }
                    delay(45_000L)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
