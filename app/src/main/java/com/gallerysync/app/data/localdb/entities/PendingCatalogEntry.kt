package com.gallerysync.app.data.localdb.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity queueing media or manifest entries awaiting publication to the next Telegram master index generation.
 * Decouples successful media uploads from catalog index updates, preventing re-uploading on index failure.
 */
@Entity(
    tableName = "pending_catalog_entries",
    indices = [
        Index(value = ["referenceId"], unique = true),
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)
data class PendingCatalogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo val entryType: String, // TYPE_MEDIA or TYPE_MANIFEST
    @ColumnInfo val referenceId: String, // mediaSha256 or backupId
    @ColumnInfo val payloadJson: String,
    @ColumnInfo val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo val status: String = STATUS_PENDING
) {
    companion object {
        const val TYPE_MEDIA = "MEDIA"
        const val TYPE_MANIFEST = "MANIFEST"
        const val TYPE_MEDIA_RECORD = TYPE_MEDIA
        const val TYPE_MANIFEST_REFERENCE = TYPE_MANIFEST

        const val STATUS_PENDING = "PENDING"
        const val STATUS_COMMITTED = "COMMITTED"
    }
}
