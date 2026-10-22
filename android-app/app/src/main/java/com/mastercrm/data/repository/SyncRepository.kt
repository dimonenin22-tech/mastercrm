package com.mastercrm.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mastercrm.data.local.AppDatabase
import com.mastercrm.data.local.entity.*
import com.mastercrm.data.remote.ApiClient
import com.mastercrm.data.remote.dto.*
import com.mastercrm.MasterCrmApp
import com.mastercrm.ui.notifications.BookingNotificationHelper
import retrofit2.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

class SyncRepository(
    private val db: AppDatabase,
    private val apiClient: ApiClient,
    private val authRepository: AuthRepository
) {
    private val gson = Gson()
    private val _syncEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val syncEvents: SharedFlow<String> = _syncEvents.asSharedFlow()

    suspend fun queueMutation(action: String, payload: Map<String, Any?>) {
        withContext(Dispatchers.IO) {
            val json = gson.toJson(payload)
            db.outboxDao().insert(
                OutboxMutationEntity(
                    action = action,
                    payloadJson = json
                )
            )
            // Proactively try to flush queue
            flushOutboxQueue()
        }
    }

    suspend fun flushOutboxQueue(): Int = withContext(Dispatchers.IO) {
        val pending = db.outboxDao().getAllPending()
        var successCount = 0

        for (mutation in pending) {
            try {
                val type = object : TypeToken<Map<String, Any?>>() {}.type
                val payload: Map<String, Any?> = gson.fromJson(mutation.payloadJson, type)

                val api = apiClient.getApiService()
                val response = api.executeAction(mutation.action, payload)

                if (response.success) {
                    // Success: remove from queue
                    db.outboxDao().delete(mutation)
                    successCount++
                } else {
                    // Business error (e.g. 409 RESOURCE_BUSY, 400, 422):
                    // Remove from queue to PREVENT INFINITE LOOPS!
                    db.outboxDao().delete(mutation)
                    val errMessage = response.error?.message ?: "Помилка конфлікту синхронізації"
                    _syncEvents.emit("Конфлікт: $errMessage")
                }
            } catch (e: Exception) {
                if (e is HttpException) {
                    val code = e.code()
                    val errBody = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                    android.util.Log.e("SyncRepo", "HTTP error $code executing ${mutation.action}: $errBody", e)
                    if (code in 400..499) {
                        db.outboxDao().delete(mutation)
                    }
                    _syncEvents.emit("Помилка сервера ($code): спробуйте знову")
                } else {
                    _syncEvents.emit("Мережа недоступна, зміни збережено офлайн")
                }
                break
            }
        }
        successCount
    }

    suspend fun performSync(forceFullSync: Boolean = false): Result<SyncResponseDto> = withContext(Dispatchers.IO) {
        try {
            // 1. First flush outbox mutations
            flushOutboxQueue()

            // 2. Fetch delta changes from server (or full sync if requested)
            val since = if (forceFullSync) "1970-01-01T00:00:00.000Z" else (authRepository.getLastSyncTime() ?: "")
            val api = apiClient.getApiService()
            val syncParams = mutableMapOf<String, String>()
            if (since.isNotEmpty()) {
                syncParams["since"] = since
            }
            if (forceFullSync) {
                syncParams["force_full"] = "true"
            }
            val response = api.sync(syncParams)

            if (!response.success || response.data == null) {
                val err = response.error?.message ?: "Помилка отримання даних від сервера"
                _syncEvents.emit("Помилка синхронізації: $err")
                return@withContext Result.failure(Exception(err))
            }

            val data = response.data
            android.util.Log.d("SyncRepo", "Synced ${data.appointments?.size ?: 0} appointments (forceFullSync=$forceFullSync)")

            // 3. Process Appointments Delta & Tombstones
            data.appointments?.forEach { dto ->
                if (dto.deleted) {
                    db.appointmentDao().deleteById(dto.id)
                } else {
                    val existing = db.appointmentDao().getById(dto.id)
                    if (existing == null && dto.status == "PENDING" && !dto.deleted) {
                        val clientName = if (!dto.clientName.isNullOrBlank()) dto.clientName
                            else data.clients?.find { it.id == dto.clientId }?.firstName
                            ?: db.clientDao().getById(dto.clientId ?: "")?.firstName
                            ?: "Новий клієнт"
                        val clientPhone = if (!dto.clientPhone.isNullOrBlank()) dto.clientPhone
                            else data.clients?.find { it.id == dto.clientId }?.phone
                            ?: db.clientDao().getById(dto.clientId ?: "")?.phone
                            ?: ""
                        val serviceTitle = if (!dto.serviceTitle.isNullOrBlank()) dto.serviceTitle
                            else data.services?.find { it.id == dto.serviceId }?.title
                            ?: db.serviceDao().getById(dto.serviceId ?: "")?.title
                            ?: "Послуга"

                        BookingNotificationHelper.showNewBookingNotification(
                            context = MasterCrmApp.instance,
                            appointmentId = dto.id,
                            clientName = clientName,
                            clientPhone = clientPhone,
                            serviceTitle = serviceTitle,
                            date = dto.date,
                            startTime = dto.startTime,
                            price = dto.totalPrice
                        )
                    }

                    db.appointmentDao().insertOrUpdate(
                        AppointmentEntity(
                            id = dto.id,
                            masterId = dto.masterId ?: "m-01",
                            clientId = dto.clientId ?: "",
                            serviceId = dto.serviceId ?: "",
                            date = dto.date,
                            startTime = dto.startTime,
                            endTime = dto.endTime,
                            status = dto.status ?: "PENDING",
                            totalPrice = dto.totalPrice,
                            depositAmount = dto.depositAmount,
                            isDepositPaid = dto.isDepositPaid,
                            receiptPhotoUrl = dto.receiptPhotoUrl ?: "",
                            cancellationReason = dto.cancellationReason ?: "",
                            deleted = dto.deleted,
                            version = dto.version,
                            updatedAt = dto.updatedAt ?: ""
                        )
                    )
                }
            }

            // 4. Process Clients Delta & Tombstones
            data.clients?.forEach { dto ->
                if (dto.deleted) {
                    db.clientDao().deleteById(dto.id)
                } else {
                    db.clientDao().insertOrUpdate(
                        ClientEntity(
                            id = dto.id,
                            telegramId = dto.telegramId,
                            firstName = dto.firstName,
                            lastName = dto.lastName,
                            phone = dto.phone,
                            notes = dto.notes,
                            totalVisits = dto.totalVisits,
                            totalSpent = dto.totalSpent,
                            visitsSinceLastRemoval = dto.visitsSinceLastRemoval,
                            lastRemovalDate = dto.lastRemovalDate,
                            noShowCount = dto.noShowCount,
                            isBlacklisted = dto.isBlacklisted,
                            discountPercent = dto.discountPercent,
                            deleted = dto.deleted,
                            version = dto.version,
                            updatedAt = dto.updatedAt
                        )
                    )
                }
            }

            // 5. Process Services Delta & Tombstones
            data.services?.forEach { dto ->
                if (dto.deleted) {
                    db.serviceDao().deleteById(dto.id)
                } else {
                    db.serviceDao().insertOrUpdate(
                        ServiceEntity(
                            id = dto.id,
                            categoryId = dto.categoryId,
                            title = dto.title,
                            price = dto.price,
                            durationMinutes = dto.durationMinutes,
                            bufferMinutes = dto.bufferMinutes,
                            isActive = dto.isActive,
                            deleted = dto.deleted,
                            version = dto.version,
                            updatedAt = dto.updatedAt
                        )
                    )
                }
            }

            // 6. Process Masters Delta & Tombstones
            data.masters?.forEach { dto ->
                if (dto.deleted) {
                    db.masterDao().deleteById(dto.id)
                } else {
                    db.masterDao().insertOrUpdateAll(
                        listOf(
                            MasterEntity(
                                id = dto.id,
                                name = dto.name,
                                phone = dto.phone,
                                telegramChatId = dto.telegramChatId,
                                role = dto.role,
                                specialization = dto.specialization,
                                isActive = dto.isActive,
                                deleted = dto.deleted,
                                version = dto.version,
                                updatedAt = dto.updatedAt
                            )
                        )
                    )
                }
            }

            // 7. Process Incomes
            data.incomes?.forEach { dto ->
                if (dto.deleted) {
                    db.incomeDao().deleteById(dto.id)
                } else {
                    db.incomeDao().insertOrUpdate(
                        IncomeEntity(
                            id = dto.id,
                            appointmentId = dto.appointmentId,
                            masterId = dto.masterId,
                            clientId = dto.clientId,
                            amount = dto.amount,
                            paymentMethod = dto.paymentMethod,
                            date = dto.date,
                            category = dto.category,
                            deleted = dto.deleted,
                            version = dto.version,
                            updatedAt = dto.updatedAt
                        )
                    )
                }
            }

            // 8. Process Expenses
            data.expenses?.forEach { dto ->
                if (dto.deleted) {
                    db.expenseDao().deleteById(dto.id)
                } else {
                    db.expenseDao().insertOrUpdate(
                        ExpenseEntity(
                            id = dto.id,
                            masterId = dto.masterId,
                            category = dto.category,
                            amount = dto.amount,
                            paymentMethod = dto.paymentMethod,
                            date = dto.date,
                            description = dto.description,
                            deleted = dto.deleted,
                            version = dto.version,
                            updatedAt = dto.updatedAt
                        )
                    )
                }
            }

            // 9. Process Products
            data.products?.forEach { dto ->
                if (dto.deleted) {
                    db.productDao().deleteById(dto.id)
                } else {
                    db.productDao().insertOrUpdate(
                        ProductEntity(
                            id = dto.id,
                            name = dto.name,
                            unit = dto.unit,
                            currentStock = dto.currentStock,
                            minStockAlert = dto.minStockAlert,
                            purchasePrice = dto.purchasePrice,
                            deleted = dto.deleted,
                            version = dto.version,
                            updatedAt = dto.updatedAt
                        )
                    )
                }
            }

            // 10. Process Reviews
            data.reviews?.forEach { dto ->
                if (dto.deleted) {
                    db.reviewDao().deleteById(dto.id)
                } else {
                    val resolvedClientName = if (!dto.clientName.isNullOrBlank()) {
                        dto.clientName
                    } else {
                        dto.clientId?.let { cId -> db.clientDao().getById(cId)?.firstName } ?: "Клієнт"
                    }
                    val resolvedServiceTitle = if (!dto.serviceTitle.isNullOrBlank()) {
                        dto.serviceTitle
                    } else {
                        dto.appointmentId?.let { aId ->
                            val apt = db.appointmentDao().getById(aId)
                            apt?.serviceId?.let { sId -> db.serviceDao().getById(sId)?.title }
                        } ?: "Манікюр"
                    }
                    db.reviewDao().insertOrUpdateAll(
                        listOf(
                            ReviewEntity(
                                id = dto.id,
                                appointmentId = dto.appointmentId ?: "",
                                clientId = dto.clientId ?: "",
                                clientName = resolvedClientName,
                                serviceTitle = resolvedServiceTitle,
                                rating = dto.rating,
                                comment = dto.comment,
                                photoUrl = dto.photoUrl,
                                tgFileId = dto.tgFileId,
                                reviewType = dto.reviewType ?: "POST_VISIT",
                                moderationStatus = dto.moderationStatus ?: "APPROVED",
                                isPublished = dto.isPublished,
                                deleted = dto.deleted,
                                version = dto.version,
                                updatedAt = dto.updatedAt ?: ""
                            )
                        )
                    )
                }
            }

            // 11. Process VisitBriefs
            data.visitBriefs?.forEach { dto ->
                if (dto.deleted) {
                    db.visitBriefDao().deleteById(dto.id)
                } else {
                    db.visitBriefDao().insertOrUpdate(
                        VisitBriefEntity(
                            id = dto.id,
                            appointmentId = dto.appointmentId,
                            clientId = dto.clientId,
                            masterId = dto.masterId,
                            materialNotes = dto.materialNotes,
                            nailCondition = dto.nailCondition,
                            coffeePrefs = dto.coffeePrefs,
                            photoUrl = dto.photoUrl,
                            coatingCondition = dto.coatingCondition,
                            nailPlateType = dto.nailPlateType,
                            cuticleType = dto.cuticleType,
                            baseGelUsed = dto.baseGelUsed,
                            designAndLength = dto.designAndLength,
                            healthNotes = dto.healthNotes,
                            photoBeforeUrl = dto.photoBeforeUrl,
                            photoAfterUrl = dto.photoAfterUrl,
                            consumedMaterialsJson = dto.consumedMaterialsJson ?: "[]",
                            deleted = dto.deleted,
                            version = dto.version,
                            updatedAt = dto.updatedAt
                        )
                    )
                }
            }

            // Save server sync time checkpoint
            authRepository.saveLastSyncTime(data.serverSyncTime)
            if (forceFullSync) {
                _syncEvents.emit("Синхронізовано записів: ${data.appointments?.size ?: 0}")
            }
            Result.success(data)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
