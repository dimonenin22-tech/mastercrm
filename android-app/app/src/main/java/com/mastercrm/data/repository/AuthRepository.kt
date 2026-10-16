package com.mastercrm.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AuthRepository(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "mastercrm_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveAuth(deviceToken: String, masterId: String, masterName: String, role: String) {
        prefs.edit()
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .putString(KEY_MASTER_ID, masterId)
            .putString(KEY_MASTER_NAME, masterName)
            .putString(KEY_ROLE, role)
            .apply()
    }

    fun getDeviceToken(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)

    fun getMasterId(): String? = prefs.getString(KEY_MASTER_ID, null)

    fun getMasterName(): String? = prefs.getString(KEY_MASTER_NAME, null)

    fun getRole(): String? = prefs.getString(KEY_ROLE, "master")

    fun isLoggedIn(): Boolean = !getDeviceToken().isNullOrEmpty()

    fun clearAuth() {
        prefs.edit()
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_MASTER_ID)
            .remove(KEY_MASTER_NAME)
            .remove(KEY_ROLE)
            .apply()
    }

    fun setServerBaseUrl(url: String) {
        val cleanUrl = if (url.endsWith("/")) url else "$url/"
        prefs.edit().putString(KEY_SERVER_URL, cleanUrl).apply()
    }

    fun getServerBaseUrl(): String {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL
    }

    fun saveLastSyncTime(isoTime: String) {
        prefs.edit().putString(KEY_LAST_SYNC_TIME, isoTime).apply()
    }

    fun getLastSyncTime(): String? = prefs.getString(KEY_LAST_SYNC_TIME, null)

    companion object {
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_MASTER_ID = "master_id"
        private const val KEY_MASTER_NAME = "master_name"
        private const val KEY_ROLE = "role"
        private const val KEY_SERVER_URL = "server_base_url"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"

        // 10.0.2.2 is Android emulator host loopback
        const val DEFAULT_URL = "http://10.0.2.2:8000/"
    }
}
