package com.mastercrm.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mastercrm.data.local.AppDatabase
import com.mastercrm.data.local.entity.*
import com.mastercrm.data.remote.ApiClient
import com.mastercrm.data.remote.dto.*
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
                // Network unreachable or server timeout: leave in queue for next sync attempt
                _syncEvents.emit("Мережа недоступна, зміни збережено офлайн")
                break
            }
        }
        successCount
    }

    suspend fun performSync(): Result<SyncResponseDto> = withContext(Dispatchers.IO) {
        try {
            // 1. First flush outbox mutations
            flushOutboxQueue()

            // 2. Fetch delta changes from server
            val since = authRepository.getLastSyncTime() ?: ""
            val api = apiClient.getApiService()
            val response = api.sync(if (since.isNotEmpty()) mapOf("since" to since) else emptyMap())

            if (!response.success || response.data == null) {
                val err = response.error?.message ?: "Помилка отримання даних від сервера"
                return@withContext Result.failure(Exception(err))
            }

            val data = response.data

            // 3. Process Appointments Delta & Tombstones
            data.appointments?.forEach { dto ->
                if (dto.deleted) {
                    db.appointmentDao().deleteById(dto.id)
                } else {
                    db.appointmentDao().insertOrUpdate(
                        AppointmentEntity(
                            id = dto.id,
                            masterId = dto.masterId,
                            clientId = dto.clientId,
                            serviceId = dto.serviceId,
                            date = dto.date,
                            startTime = dto.startTime,
                            endTime = dto.endTime,
                            status = dto.status,
                            totalPrice = dto.totalPrice,
                            depositAmount = dto.depositAmount,
                            isDepositPaid = dto.isDepositPaid,
                            cancellationReason = dto.cancellationReason,
                            deleted = dto.deleted,
                            version = dto.version,
                            updatedAt = dto.updatedAt
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
                    db.reviewDao().insertOrUpdateAll(
                        listOf(
                            ReviewEntity(
                                id = dto.id,
                                appointmentId = dto.appointmentId,
                                clientId = dto.clientId,
                                rating = dto.rating,
                                comment = dto.comment,
                                moderationStatus = dto.moderationStatus,
                                isPublished = dto.isPublished,
                                deleted = dto.deleted,
                                version = dto.version,
                                updatedAt = dto.updatedAt
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
                            deleted = dto.deleted,
                            version = dto.version,
                            updatedAt = dto.updatedAt
                        )
                    )
                }
            }

            // Save server sync time checkpoint
            authRepository.saveLastSyncTime(data.serverSyncTime)
            Result.success(data)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
