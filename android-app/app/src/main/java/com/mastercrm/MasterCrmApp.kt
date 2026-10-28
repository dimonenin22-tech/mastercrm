package com.mastercrm

import android.app.Application
import com.mastercrm.data.local.AppDatabase
import com.mastercrm.data.remote.ApiClient
import com.mastercrm.data.repository.AuthRepository
import com.mastercrm.data.repository.SyncRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MasterCrmApp : Application() {

    lateinit var database: AppDatabase
        private set
    lateinit var authRepository: AuthRepository
        private set
    lateinit var apiClient: ApiClient
        private set
    lateinit var syncRepository: SyncRepository
        private set

    private val _authRevokedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val authRevokedEvents = _authRevokedEvents.asSharedFlow()

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AppDatabase.getInstance(this)
        authRepository = AuthRepository(this)

        apiClient = ApiClient(authRepository) {
            _authRevokedEvents.tryEmit(Unit)
        }

        syncRepository = SyncRepository(database, apiClient, authRepository)
    }

    companion object {
        lateinit var instance: MasterCrmApp
            private set
    }
}
