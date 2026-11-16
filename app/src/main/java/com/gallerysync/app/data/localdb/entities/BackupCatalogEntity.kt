package com.gallerysync.app.data.localdb.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity tracking the local confirmed state of the remote Telegram master index.
 */
@Entity(tableName = "backup_catalog")
data class BackupCatalogEntity(
    @PrimaryKey val channelId: Long,
    @ColumnInfo val botId: Long,
    @ColumnInfo val lastConfirmedGeneration: Long = 0L,
    @ColumnInfo val pinnedMessageId: Long? = null,
    @ColumnInfo val indexFileId: String? = null,
    @ColumnInfo val indexSha256: String? = null,
    @ColumnInfo val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo val syncStatus: String = STATUS_COMMITTED
) {
    companion object {
        const val STATUS_COMMITTED = "COMMITTED"
        const val STATUS_PENDING_COMMIT = "PENDING_COMMIT"
        const val STATUS_RECOVERY_NEEDED = "RECOVERY_NEEDED"
    }
}
