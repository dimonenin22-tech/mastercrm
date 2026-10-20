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
    @SerializedName("telegram_chat_id") val telegramChatId: Long? = null,
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
    @SerializedName("master_id", alternate = ["masterId"]) val masterId: String? = "m-01",
    @SerializedName("client_id", alternate = ["clientId"]) val clientId: String? = "",
    @SerializedName("client_name", alternate = ["clientName"]) val clientName: String? = "",
    @SerializedName("client_phone", alternate = ["clientPhone"]) val clientPhone: String? = "",
    @SerializedName("service_id", alternate = ["serviceId"]) val serviceId: String? = "",
    @SerializedName("service_title", alternate = ["serviceTitle"]) val serviceTitle: String? = "",
    @SerializedName("date") val date: String,
    @SerializedName("start_time", alternate = ["startTime"]) val startTime: String,
    @SerializedName("end_time", alternate = ["endTime"]) val endTime: String,
    @SerializedName("status") val status: String? = "PENDING",
    @SerializedName("total_price", alternate = ["totalPrice"]) val totalPrice: Double = 0.0,
    @SerializedName("deposit_amount", alternate = ["depositAmount"]) val depositAmount: Double = 0.0,
    @SerializedName("is_deposit_paid", alternate = ["isDepositPaid"]) val isDepositPaid: Boolean = false,
    @SerializedName("receipt_photo_url", alternate = ["receiptPhotoUrl"]) val receiptPhotoUrl: String? = "",
    @SerializedName("cancellation_reason", alternate = ["cancellationReason"]) val cancellationReason: String? = "",
    @SerializedName("deleted") val deleted: Boolean = false,
    @SerializedName("version") val version: Int = 1,
    @SerializedName("updated_at", alternate = ["updatedAt"]) val updatedAt: String? = ""
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
    @SerializedName("appointment_id") val appointmentId: String?,
    @SerializedName("client_id") val clientId: String?,
    @SerializedName("client_name") val clientName: String? = null,
    @SerializedName("service_title") val serviceTitle: String? = null,
    @SerializedName("rating") val rating: Int = 5,
    @SerializedName("comment") val comment: String?,
    @SerializedName("photo_url") val photoUrl: String? = null,
    @SerializedName("tg_file_id") val tgFileId: String? = null,
    @SerializedName("review_type") val reviewType: String? = "POST_VISIT",
    @SerializedName("moderation_status") val moderationStatus: String? = "APPROVED",
    @SerializedName("is_published") val isPublished: Boolean = true,
    @SerializedName("deleted") val deleted: Boolean = false,
    @SerializedName("version") val version: Int = 1,
    @SerializedName("updated_at") val updatedAt: String? = null
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
    @SerializedName("visit_briefs") val visitBriefs: List<VisitBriefDto>?,
    @SerializedName("server_sync_time") val serverSyncTime: String
)

data class VisitBriefDto(
    @SerializedName("id") val id: String,
    @SerializedName("appointment_id") val appointmentId: String,
    @SerializedName("client_id") val clientId: String,
    @SerializedName("master_id") val masterId: String,
    @SerializedName("material_notes") val materialNotes: String?,
    @SerializedName("nail_condition") val nailCondition: String?,
    @SerializedName("coffee_prefs") val coffeePrefs: String?,
    @SerializedName("photo_url") val photoUrl: String?,
    @SerializedName("coating_condition") val coatingCondition: String?,
    @SerializedName("nail_plate_type") val nailPlateType: String?,
    @SerializedName("cuticle_type") val cuticleType: String?,
    @SerializedName("base_gel_used") val baseGelUsed: String?,
    @SerializedName("design_and_length") val designAndLength: String?,
    @SerializedName("health_notes") val healthNotes: String?,
    @SerializedName("photo_before_url") val photoBeforeUrl: String?,
    @SerializedName("photo_after_url") val photoAfterUrl: String?,
    @SerializedName("deleted") val deleted: Boolean = false,
    @SerializedName("version") val version: Int = 1,
    @SerializedName("updated_at") val updatedAt: String = ""
)

data class ServiceConsumableItemDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("product_id") val productId: String,
    @SerializedName("product_name") val productName: String = "",
    @SerializedName("quantity_per_service") val quantityPerService: Double = 1.0,
    @SerializedName("unit") val unit: String = "шт"
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
