package com.mastercrm.data.remote

import com.mastercrm.data.repository.AuthRepository
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class ApiClient(
    private val authRepository: AuthRepository,
    private val onAuthRevoked: () -> Unit
) {
    private var currentBaseUrl: String = authRepository.getServerBaseUrl()
    private var currentRetrofit: Retrofit? = null
    private var currentApiService: ApiService? = null

    fun getApiService(): ApiService {
        val configuredUrl = authRepository.getServerBaseUrl()
        if (currentApiService != null && configuredUrl == currentBaseUrl) {
            return currentApiService!!
        }

        currentBaseUrl = if (configuredUrl.endsWith("/")) configuredUrl else "$configuredUrl/"

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(authRepository, onAuthRevoked))
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(currentBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        currentRetrofit = retrofit
        currentApiService = retrofit.create(ApiService::class.java)
        return currentApiService!!
    }
}
