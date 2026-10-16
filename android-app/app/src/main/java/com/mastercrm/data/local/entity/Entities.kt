package com.mastercrm.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "masters")
data class MasterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String,
    val role: String,
    val specialization: String?,
    val isActive: Boolean,
    val deleted: Boolean,
    val version: Int,
    val updatedAt: String
)

@Entity(tableName = "clients")
data class ClientEntity(
    @PrimaryKey val id: String,
    val telegramId: Long?,
    val firstName: String,
    val lastName: String?,
    val phone: String,
    val notes: String?,
    val totalVisits: Int,
    val totalSpent: Double,
    val visitsSinceLastRemoval: Int,
    val lastRemovalDate: String?,
    val noShowCount: Int = 0,
    val isBlacklisted: Boolean = false,
    val discountPercent: Int = 0,
    val deleted: Boolean,
    val version: Int,
    val updatedAt: String
)

@Entity(tableName = "services")
data class ServiceEntity(
    @PrimaryKey val id: String,
    val categoryId: String,
    val title: String,
    val price: Double,
    val durationMinutes: Int,
    val bufferMinutes: Int,
    val isActive: Boolean,
    val deleted: Boolean,
    val version: Int,
    val updatedAt: String
)

@Entity(tableName = "appointments")
data class AppointmentEntity(
    @PrimaryKey val id: String,
    val masterId: String,
    val clientId: String,
    val serviceId: String,
    val date: String,          // YYYY-MM-DD
    val startTime: String,     // HH:MM
    val endTime: String,       // HH:MM
    val status: String,        // PENDING, CONFIRMED, COMPLETED, CANCELLED_BY_CLIENT, CANCELLED_BY_MASTER, NO_SHOW
    val totalPrice: Double,
    val depositAmount: Double,
    val isDepositPaid: Boolean,
    val cancellationReason: String?,
    val deleted: Boolean,
    val version: Int,
    val updatedAt: String
)

@Entity(tableName = "incomes")
data class IncomeEntity(
    @PrimaryKey val id: String,
    val appointmentId: String?,
    val masterId: String,
    val clientId: String?,
    val amount: Double,
    val paymentMethod: String,
    val date: String,
    val category: String,
    val deleted: Boolean,
    val version: Int,
    val updatedAt: String
)

@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val masterId: String,
    val category: String,
    val amount: Double,
    val paymentMethod: String,
    val date: String,
    val description: String?,
    val deleted: Boolean,
    val version: Int,
    val updatedAt: String
)

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: String,
    val name: String,
    val unit: String,
    val currentStock: Double,
    val minStockAlert: Double,
    val purchasePrice: Double,
    val deleted: Boolean,
    val version: Int,
    val updatedAt: String
)

@Entity(tableName = "reviews")
data class ReviewEntity(
    @PrimaryKey val id: String,
    val appointmentId: String,
    val clientId: String,
    val rating: Int,
    val comment: String?,
    val moderationStatus: String,
    val isPublished: Boolean,
    val deleted: Boolean,
    val version: Int,
    val updatedAt: String
)

@Entity(tableName = "outbox_queue")
data class OutboxMutationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val action: String,
    val payloadJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
)
