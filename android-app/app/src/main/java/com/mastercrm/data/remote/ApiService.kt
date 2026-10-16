package com.mastercrm.data.remote

import com.mastercrm.data.remote.dto.*
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

interface ApiService {

    @POST("exec?action=auth_exchange_code")
    suspend fun exchangeOtpCode(
        @Body body: Map<String, String>
    ): ApiResponse<AuthExchangeResponseDto>

    @POST("exec?action=sync")
    suspend fun sync(
        @Body body: Map<String, String>
    ): ApiResponse<SyncResponseDto>

    @POST("exec")
    suspend fun executeAction(
        @Query("action") action: String,
        @Body body: Any?
    ): ApiResponse<Any>

    @POST("exec?action=finance_report")
    suspend fun getFinanceReport(
        @Body body: Map<String, String>
    ): ApiResponse<FinanceReportDto>

    @POST("exec?action=appointment_update_status")
    suspend fun updateAppointmentStatus(
        @Body body: Map<String, Any?>
    ): ApiResponse<AppointmentDto>

    @POST("exec?action=appointment_create")
    suspend fun createAppointment(
        @Body body: Map<String, Any?>
    ): ApiResponse<AppointmentDto>

    @POST("exec?action=visit_brief_save")
    suspend fun saveVisitBrief(
        @Body body: Map<String, Any?>
    ): ApiResponse<Map<String, Any>>

    @POST("exec?action=client_reset_removal_counter")
    suspend fun resetRemovalCounter(
        @Body body: Map<String, String>
    ): ApiResponse<Map<String, Any>>
}
