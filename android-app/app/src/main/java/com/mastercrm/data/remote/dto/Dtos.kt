package com.mastercrm.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ApiErrorDto(
    @SerializedName("code") val code: String,
    @SerializedName("message") val message: String
)

data class ApiResponse<T>(
    @SerializedName("success") val success: Boolean,
    @SerializedName("status") val status: String,
    @SerializedName("data") val data: T?,
    @SerializedName("error") val error: ApiErrorDto?
)

data class MasterBasicDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("phone") val phone: String,
    @SerializedName("role") val role: String
)

data class AuthExchangeResponseDto(
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("master_id") val masterId: String,
    @SerializedName("master_name") val masterName: String,
    @SerializedName("role") val role: String,
    @SerializedName("master") val master: MasterBasicDto
)

data class MasterDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("phone") val phone: String,
    @SerializedName("role") val role: String,
    @SerializedName("specialization") val specialization: String?,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("deleted") val deleted: Boolean,
    @SerializedName("version") val version: Int,
    @SerializedName("updated_at") val updatedAt: String
)

data class ClientDto(
    @SerializedName("id") val id: String,
    @SerializedName("telegram_id") val telegramId: Long?,
    @SerializedName("first_name") val firstName: String,
    @SerializedName("last_name") val lastName: String?,
    @SerializedName("phone") val phone: String,
    @SerializedName("notes") val notes: String?,
    @SerializedName("total_visits") val totalVisits: Int,
    @SerializedName("total_spent") val totalSpent: Double,
    @SerializedName("visits_since_last_removal") val visitsSinceLastRemoval: Int,
    @SerializedName("last_removal_date") val lastRemovalDate: String?,
    @SerializedName("no_show_count") val noShowCount: Int = 0,
    @SerializedName("is_blacklisted") val isBlacklisted: Boolean = false,
    @SerializedName("discount_percent") val discountPercent: Int = 0,
    @SerializedName("deleted") val deleted: Boolean,
    @SerializedName("version") val version: Int,
    @SerializedName("updated_at") val updatedAt: String
)

data class ServiceDto(
    @SerializedName("id") val id: String,
    @SerializedName("category_id") val categoryId: String,
    @SerializedName("title") val title: String,
    @SerializedName("price") val price: Double,
    @SerializedName("duration_minutes") val durationMinutes: Int,
    @SerializedName("buffer_minutes") val bufferMinutes: Int,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("deleted") val deleted: Boolean,
    @SerializedName("version") val version: Int,
    @SerializedName("updated_at") val updatedAt: String
)

data class AppointmentDto(
    @SerializedName("id") val id: String,
    @SerializedName("master_id") val masterId: String,
    @SerializedName("client_id") val clientId: String,
    @SerializedName("service_id") val serviceId: String,
    @SerializedName("date") val date: String,
    @SerializedName("start_time") val startTime: String,
    @SerializedName("end_time") val endTime: String,
    @SerializedName("status") val status: String,
    @SerializedName("total_price") val totalPrice: Double,
    @SerializedName("deposit_amount") val depositAmount: Double,
    @SerializedName("is_deposit_paid") val isDepositPaid: Boolean,
    @SerializedName("cancellation_reason") val cancellationReason: String?,
    @SerializedName("deleted") val deleted: Boolean,
    @SerializedName("version") val version: Int,
    @SerializedName("updated_at") val updatedAt: String
)

data class IncomeDto(
    @SerializedName("id") val id: String,
    @SerializedName("appointment_id") val appointmentId: String?,
    @SerializedName("master_id") val masterId: String,
    @SerializedName("client_id") val clientId: String?,
    @SerializedName("amount") val amount: Double,
    @SerializedName("payment_method") val paymentMethod: String,
    @SerializedName("date") val date: String,
    @SerializedName("category") val category: String,
    @SerializedName("deleted") val deleted: Boolean,
    @SerializedName("version") val version: Int,
    @SerializedName("updated_at") val updatedAt: String
)

data class ExpenseDto(
    @SerializedName("id") val id: String,
    @SerializedName("master_id") val masterId: String,
    @SerializedName("category") val category: String,
    @SerializedName("amount") val amount: Double,
    @SerializedName("payment_method") val paymentMethod: String,
    @SerializedName("date") val date: String,
    @SerializedName("description") val description: String?,
    @SerializedName("deleted") val deleted: Boolean,
    @SerializedName("version") val version: Int,
    @SerializedName("updated_at") val updatedAt: String
)

data class ProductDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("unit") val unit: String,
    @SerializedName("current_stock") val currentStock: Double,
    @SerializedName("min_stock_alert") val minStockAlert: Double,
    @SerializedName("purchase_price") val purchasePrice: Double,
    @SerializedName("deleted") val deleted: Boolean,
    @SerializedName("version") val version: Int,
    @SerializedName("updated_at") val updatedAt: String
)

data class ReviewDto(
    @SerializedName("id") val id: String,
    @SerializedName("appointment_id") val appointmentId: String,
    @SerializedName("client_id") val clientId: String,
    @SerializedName("rating") val rating: Int,
    @SerializedName("comment") val comment: String?,
    @SerializedName("moderation_status") val moderationStatus: String,
    @SerializedName("is_published") val isPublished: Boolean,
    @SerializedName("deleted") val deleted: Boolean,
    @SerializedName("version") val version: Int,
    @SerializedName("updated_at") val updatedAt: String
)

data class SyncResponseDto(
    @SerializedName("appointments") val appointments: List<AppointmentDto>?,
    @SerializedName("clients") val clients: List<ClientDto>?,
    @SerializedName("services") val services: List<ServiceDto>?,
    @SerializedName("masters") val masters: List<MasterDto>?,
    @SerializedName("incomes") val incomes: List<IncomeDto>?,
    @SerializedName("expenses") val expenses: List<ExpenseDto>?,
    @SerializedName("products") val products: List<ProductDto>?,
    @SerializedName("reviews") val reviews: List<ReviewDto>?,
    @SerializedName("settings") val settings: Map<String, String>?,
    @SerializedName("schedules") val schedules: List<ScheduleDto>?,
    @SerializedName("server_sync_time") val serverSyncTime: String
)

data class FinanceReportDto(
    @SerializedName("from_date") val fromDate: String,
    @SerializedName("to_date") val toDate: String,
    @SerializedName("gross_revenue") val grossRevenue: Double,
    @SerializedName("tips") val tips: Double,
    @SerializedName("direct_expenses") val directExpenses: Double,
    @SerializedName("materials_cogs") val materialsCogs: Double,
    @SerializedName("total_expenses") val totalExpenses: Double,
    @SerializedName("net_profit") val netProfit: Double,
    @SerializedName("appointments_completed") val appointmentsCompleted: Int,
    @SerializedName("average_check") val averageCheck: Double,
    @SerializedName("margin_percent") val marginPercent: Double
)

data class ScheduleDto(
    @SerializedName("id") val id: String,
    @SerializedName("master_id") val masterId: String,
    @SerializedName("day_of_week") val dayOfWeek: Int,
    @SerializedName("start_time") val startTime: String,
    @SerializedName("end_time") val endTime: String,
    @SerializedName("slot_step_minutes") val slotStepMinutes: Int = 30,
    @SerializedName("is_working_day") val isWorkingDay: Boolean = true
)

data class ScheduleExceptionDto(
    @SerializedName("id") val id: String,
    @SerializedName("master_id") val masterId: String,
    @SerializedName("date") val date: String,
    @SerializedName("start_time") val startTime: String? = null,
    @SerializedName("end_time") val endTime: String? = null,
    @SerializedName("is_day_off") val isDayOff: Boolean = true,
    @SerializedName("reason") val reason: String? = ""
)

data class ScheduleResponseDto(
    @SerializedName("master_id") val masterId: String,
    @SerializedName("schedules") val schedules: List<ScheduleDto>,
    @SerializedName("exceptions") val exceptions: List<ScheduleExceptionDto>
)

data class PhotoDto(
    @SerializedName("id") val id: String,
    @SerializedName("appointment_id") val appointmentId: String?,
    @SerializedName("client_id") val clientId: String?,
    @SerializedName("photo_type") val photoType: String,
    @SerializedName("file_url") val fileUrl: String,
    @SerializedName("thumbnail_url") val thumbnailUrl: String?
)

data class ReminderTemplateDto(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String,
    @SerializedName("template_text") val templateText: String,
    @SerializedName("is_active") val isActive: Boolean
)

data class FinanceExportCsvResponseDto(
    @SerializedName("csv") val csv: String,
    @SerializedName("from_date") val fromDate: String,
    @SerializedName("to_date") val toDate: String
)
