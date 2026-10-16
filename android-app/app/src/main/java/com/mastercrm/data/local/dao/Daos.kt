package com.mastercrm.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mastercrm.data.local.entity.AppointmentEntity
import com.mastercrm.data.local.entity.ClientEntity
import com.mastercrm.data.local.entity.ExpenseEntity
import com.mastercrm.data.local.entity.IncomeEntity
import com.mastercrm.data.local.entity.MasterEntity
import com.mastercrm.data.local.entity.OutboxMutationEntity
import com.mastercrm.data.local.entity.ProductEntity
import com.mastercrm.data.local.entity.ReviewEntity
import com.mastercrm.data.local.entity.ServiceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppointmentDao {
    @Query("SELECT * FROM appointments WHERE date = :date AND deleted = 0 ORDER BY startTime ASC")
    fun getAppointmentsByDateFlow(date: String): Flow<List<AppointmentEntity>>

    @Query("SELECT * FROM appointments WHERE deleted = 0 ORDER BY date DESC, startTime ASC")
    fun getAllAppointmentsFlow(): Flow<List<AppointmentEntity>>

    @Query("SELECT * FROM appointments WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AppointmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(appointment: AppointmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(appointments: List<AppointmentEntity>)

    @Query("DELETE FROM appointments WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ClientDao {
    @Query("SELECT * FROM clients WHERE deleted = 0 ORDER BY firstName ASC")
    fun getAllClientsFlow(): Flow<List<ClientEntity>>

    @Query("SELECT * FROM clients WHERE deleted = 0 AND (firstName LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%') ORDER BY firstName ASC")
    fun searchClientsFlow(query: String): Flow<List<ClientEntity>>

    @Query("SELECT * FROM clients WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ClientEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(client: ClientEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(clients: List<ClientEntity>)

    @Query("DELETE FROM clients WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ServiceDao {
    @Query("SELECT * FROM services WHERE deleted = 0 AND isActive = 1 ORDER BY title ASC")
    fun getActiveServicesFlow(): Flow<List<ServiceEntity>>

    @Query("SELECT * FROM services WHERE deleted = 0 ORDER BY title ASC")
    fun getAllServicesFlow(): Flow<List<ServiceEntity>>

    @Query("SELECT * FROM services WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ServiceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(service: ServiceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(services: List<ServiceEntity>)

    @Query("DELETE FROM services WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface MasterDao {
    @Query("SELECT * FROM masters WHERE deleted = 0 ORDER BY name ASC")
    fun getAllMastersFlow(): Flow<List<MasterEntity>>

    @Query("SELECT * FROM masters WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MasterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(masters: List<MasterEntity>)

    @Query("DELETE FROM masters WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface IncomeDao {
    @Query("SELECT * FROM incomes WHERE deleted = 0 ORDER BY date DESC")
    fun getAllIncomesFlow(): Flow<List<IncomeEntity>>

    @Query("SELECT * FROM incomes WHERE date >= :fromDate AND date <= :toDate AND deleted = 0")
    suspend fun getIncomesForPeriod(fromDate: String, toDate: String): List<IncomeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(income: IncomeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(incomes: List<IncomeEntity>)

    @Query("DELETE FROM incomes WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses WHERE deleted = 0 ORDER BY date DESC")
    fun getAllExpensesFlow(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE date >= :fromDate AND date <= :toDate AND deleted = 0")
    suspend fun getExpensesForPeriod(fromDate: String, toDate: String): List<ExpenseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(expense: ExpenseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(expenses: List<ExpenseEntity>)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ProductDao {
    @Query("SELECT * FROM products WHERE deleted = 0 ORDER BY name ASC")
    fun getAllProductsFlow(): Flow<List<ProductEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(product: ProductEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(products: List<ProductEntity>)

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ReviewDao {
    @Query("SELECT * FROM reviews WHERE deleted = 0 ORDER BY createdAt DESC")
    fun getAllReviewsFlow(): Flow<List<ReviewEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(reviews: List<ReviewEntity>)

    @Query("DELETE FROM reviews WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox_queue ORDER BY id ASC")
    suspend fun getAllPending(): List<OutboxMutationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mutation: OutboxMutationEntity): Long

    @Delete
    suspend fun delete(mutation: OutboxMutationEntity)

    @Query("DELETE FROM outbox_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM outbox_queue")
    fun getPendingCountFlow(): Flow<Int>
}
