package com.gallerysync.app.data.localdb.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gallerysync.app.data.localdb.entities.PendingCatalogEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingCatalogEntryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(entry: PendingCatalogEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(entry: PendingCatalogEntry): Long

    @Query("SELECT * FROM pending_catalog_entries WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPendingEntries(): List<PendingCatalogEntry>

    @Query("SELECT * FROM pending_catalog_entries WHERE status = 'PENDING' ORDER BY createdAt ASC")
    fun getPendingEntriesFlow(): Flow<List<PendingCatalogEntry>>

    @Query("SELECT COUNT(*) FROM pending_catalog_entries WHERE status = 'PENDING'")
    suspend fun getPendingCount(): Int

    @Query("SELECT * FROM pending_catalog_entries WHERE referenceId = :referenceId LIMIT 1")
    suspend fun getByReferenceId(referenceId: String): PendingCatalogEntry?

    @Query("UPDATE pending_catalog_entries SET status = 'COMMITTED' WHERE id IN (:ids)")
    suspend fun markCommitted(ids: List<Long>): Int

    @Query("DELETE FROM pending_catalog_entries WHERE status = 'COMMITTED'")
    suspend fun deleteCommitted(): Int

    @Query("DELETE FROM pending_catalog_entries WHERE referenceId = :referenceId")
    suspend fun deleteByReferenceId(referenceId: String): Int
}
