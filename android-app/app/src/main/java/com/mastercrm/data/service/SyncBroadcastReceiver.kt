package com.mastercrm.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mastercrm.MasterCrmApp

class SyncBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RESTART_SYNC = "com.mastercrm.RESTART_SYNC_SERVICE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val app = MasterCrmApp.instance
            if (app.authRepository.isLoggedIn()) {
                val serviceIntent = Intent(context, BookingSyncService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("SyncBroadcastReceiver", "Failed to start service", e)
        }
    }
}
