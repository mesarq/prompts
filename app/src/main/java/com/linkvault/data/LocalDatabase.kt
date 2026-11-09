package com.linkvault.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vault_records", primaryKeys = ["userId", "recordType", "recordId"], indices = [Index("userId"), Index(value = ["userId", "recordType"])])
data class VaultRecordEntity(
    val userId: String,
    val recordType: String,
    val recordId: String,
    val payload: String,
    val deleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sync_outbox", indices = [Index(value = ["userId", "createdAt"])])
data class SyncOutboxEntity(
    @PrimaryKey val operationId: String,
    val userId: String,
    val entity: String,
    val action: String,
    val entityId: String?,
    val baseVersion: Int?,
    val payload: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastError: String? = null
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val userId: String,
    val cursor: String = "0",
    val lastSuccessfulSync: Long = 0L,
    val fullSyncComplete: Boolean = false
)

@Dao
interface LocalVaultDao {
    @Query("SELECT * FROM vault_records WHERE userId = :userId AND recordType = :type AND deleted = 0 ORDER BY updatedAt DESC")
    fun observeRecords(userId: String, type: String): Flow<List<VaultRecordEntity>>

    @Query("SELECT * FROM vault_records WHERE userId = :userId AND recordType = :type AND deleted = 0 ORDER BY updatedAt DESC")
    suspend fun records(userId: String, type: String): List<VaultRecordEntity>

    @Query("SELECT * FROM vault_records WHERE userId = :userId AND recordId = :id LIMIT 1")
    suspend fun record(userId: String, id: String): VaultRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRecord(record: VaultRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRecords(records: List<VaultRecordEntity>)

    @Query("DELETE FROM vault_records WHERE userId = :userId AND recordType = :type AND recordId = :id")
    suspend fun deleteRecord(userId: String, type: String, id: String)

    @Query("DELETE FROM vault_records WHERE userId = :userId")
    suspend fun clearRecords(userId: String)

    @Query("DELETE FROM vault_records WHERE userId = :userId AND recordType = :type")
    suspend fun clearRecordType(userId: String, type: String)

    @Query("UPDATE vault_records SET deleted = 1, updatedAt = :updatedAt WHERE userId = :userId AND recordType = :type AND recordId = :id")
    suspend fun markDeleted(userId: String, type: String, id: String, updatedAt: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(operation: SyncOutboxEntity)

    @Query("SELECT * FROM sync_outbox WHERE userId = :userId ORDER BY createdAt LIMIT :limit")
    suspend fun pending(userId: String, limit: Int = 100): List<SyncOutboxEntity>

    @Query("DELETE FROM sync_outbox WHERE operationId = :operationId")
    suspend fun acknowledge(operationId: String)

    @Query("UPDATE sync_outbox SET attempts = attempts + 1, lastError = :error WHERE operationId = :operationId")
    suspend fun recordFailure(operationId: String, error: String)

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE userId = :userId")
    fun observePendingCount(userId: String): Flow<Int>

    @Query("SELECT * FROM sync_state WHERE userId = :userId")
    suspend fun syncState(userId: String): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSyncState(state: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE userId = :userId")
    suspend fun clearSyncState(userId: String)

    @Transaction
    suspend fun clearAccount(userId: String) {
        clearRecords(userId)
        clearSyncState(userId)
    }
}

@Database(
    entities = [VaultRecordEntity::class, SyncOutboxEntity::class, SyncStateEntity::class],
    version = 1,
    exportSchema = true
)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun vaultDao(): LocalVaultDao

    companion object {
        @Volatile private var INSTANCE: LocalDatabase? = null
        fun get(context: Context): LocalDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                LocalDatabase::class.java,
                "linkvault-local.db"
            ).build().also { INSTANCE = it }
        }
    }
}
