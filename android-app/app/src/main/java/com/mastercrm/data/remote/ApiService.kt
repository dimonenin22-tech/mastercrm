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

    @POST("exec?action=finance_export_csv")
    suspend fun exportFinanceCsv(
        @Body body: Map<String, String>
    ): ApiResponse<FinanceExportCsvResponseDto>

    @POST("exec?action=schedule_get")
    suspend fun getSchedule(
        @Body body: Map<String, String> = emptyMap()
    ): ApiResponse<ScheduleResponseDto>

    @POST("exec?action=schedule_update")
    suspend fun updateSchedule(
        @Body body: Map<String, Any?>
    ): ApiResponse<Map<String, Any>>

    @POST("exec?action=schedule_exception_add")
    suspend fun addScheduleException(
        @Body body: Map<String, Any?>
    ): ApiResponse<ScheduleExceptionDto>

    @POST("exec?action=reminder_template_update")
    suspend fun updateReminderTemplate(
        @Body body: Map<String, Any?>
    ): ApiResponse<ReminderTemplateDto>

    @POST("exec?action=clean_demo_data")
    suspend fun cleanDemoData(): ApiResponse<Map<String, Any>>

    @POST("exec?action=photo_upload")
    suspend fun uploadPhoto(
        @Body body: Map<String, Any?>
    ): ApiResponse<PhotoDto>

    @POST("exec?action=product_writeoff")
    suspend fun writeoffProduct(
        @Body body: Map<String, Any?>
    ): ApiResponse<Map<String, Any>>

    @POST("exec?action=product_create")
    suspend fun createProduct(
        @Body body: Map<String, Any?>
    ): ApiResponse<ProductDto>

    @POST("exec?action=service_consumables_get")
    suspend fun getServiceConsumables(
        @Body body: Map<String, String>
    ): ApiResponse<List<ServiceConsumableItemDto>>

    @POST("exec?action=service_consumables_set")
    suspend fun setServiceConsumables(
        @Body body: Map<String, Any?>
    ): ApiResponse<Map<String, Any>>
}
