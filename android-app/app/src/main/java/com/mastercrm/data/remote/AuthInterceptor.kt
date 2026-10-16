package com.mastercrm.data.remote

import com.mastercrm.data.repository.AuthRepository
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val authRepository: AuthRepository,
    private val onAuthRevoked: () -> Unit
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val token = authRepository.getDeviceToken()

        val requestBuilder = originalRequest.newBuilder()
        if (!token.isNullOrEmpty()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        val response = chain.proceed(requestBuilder.build())

        // Handle 401 / 403 session revocation
        if (response.code == 401 || response.code == 403) {
            authRepository.clearAuth()
            onAuthRevoked.invoke()
        }

        return response
    }
}
