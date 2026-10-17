package com.mastercrm.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.mastercrm.data.local.dao.*
import com.mastercrm.data.local.entity.*

@Database(
    entities = [
        MasterEntity::class,
        ClientEntity::class,
        ServiceEntity::class,
        AppointmentEntity::class,
        IncomeEntity::class,
        ExpenseEntity::class,
        ProductEntity::class,
        ReviewEntity::class,
        OutboxMutationEntity::class,
        VisitBriefEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appointmentDao(): AppointmentDao
    abstract fun clientDao(): ClientDao
    abstract fun serviceDao(): ServiceDao
    abstract fun masterDao(): MasterDao
    abstract fun incomeDao(): IncomeDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun productDao(): ProductDao
    abstract fun reviewDao(): ReviewDao
    abstract fun outboxDao(): OutboxDao
    abstract fun visitBriefDao(): VisitBriefDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mastercrm_offline.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
