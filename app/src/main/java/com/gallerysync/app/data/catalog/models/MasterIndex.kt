package com.gallerysync.app.data.catalog.models

import androidx.annotation.Keep
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@Keep
@JsonIgnoreProperties(ignoreUnknown = true)
data class MasterIndex(
    @param:JsonProperty("format") @get:JsonProperty("format") val format: String = FORMAT_IDENTIFIER,
    @param:JsonProperty("version") @get:JsonProperty("version") val version: Int = CURRENT_VERSION,
    @param:JsonProperty("generation") @get:JsonProperty("generation") val generation: Long = 0L,
    @param:JsonProperty("updated_at") @get:JsonProperty("updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @param:JsonProperty("bot_id") @get:JsonProperty("bot_id") val botId: Long = 0L,
    @param:JsonProperty("channel_id") @get:JsonProperty("channel_id") val channelId: Long = 0L,
    @param:JsonProperty("manifests") @get:JsonProperty("manifests") val manifests: List<ManifestReference> = emptyList(),
    @param:JsonProperty("shards") @get:JsonProperty("shards") val shards: List<ShardReference> = emptyList(),
    @param:JsonProperty("active_media_records") @get:JsonProperty("active_media_records") val activeMediaRecords: List<CatalogMediaRecord> = emptyList(),
    @param:JsonProperty("total_media_count") @get:JsonProperty("total_media_count") val totalMediaCount: Int = activeMediaRecords.size + shards.sumOf { it.itemCount },
    @param:JsonProperty("total_manifest_count") @get:JsonProperty("total_manifest_count") val totalManifestCount: Int = manifests.size
) {
    fun validate() {
        check(format == FORMAT_IDENTIFIER) { "Invalid master index format: $format" }
        check(version == CURRENT_VERSION) { "Unsupported master index version: $version" }
        check(generation > 0L) { "generation must be positive: $generation" }
        check(botId > 0L) { "bot_id must be positive: $botId" }
        check(channelId != 0L) { "channel_id cannot be 0" }
    }

    companion object {
        const val FORMAT_IDENTIFIER = "gallerysync_master_index"
        const val CURRENT_VERSION = 1
        const val DEFAULT_INDEX_FILE_NAME = "gallerysync_master_index.json"
    }
}

@Keep
@JsonIgnoreProperties(ignoreUnknown = true)
data class ManifestReference(
    @param:JsonProperty("backup_id") @get:JsonProperty("backup_id") val backupId: String = "",
    @param:JsonProperty("file_id") @get:JsonProperty("file_id") val fileId: String = "",
    @param:JsonProperty("message_id") @get:JsonProperty("message_id") val messageId: Long = 0L,
    @param:JsonProperty("file_name") @get:JsonProperty("file_name") val fileName: String = "",
    @param:JsonProperty("size_bytes") @get:JsonProperty("size_bytes") val sizeBytes: Long = 0L,
    @param:JsonProperty("sha256") @get:JsonProperty("sha256") val sha256: String = "",
    @param:JsonProperty("media_sha256") @get:JsonProperty("media_sha256") val mediaSha256: String = "",
    @param:JsonProperty("display_name") @get:JsonProperty("display_name") val displayName: String? = null
)

@Keep
@JsonIgnoreProperties(ignoreUnknown = true)
data class ShardReference(
    @param:JsonProperty("shard_id") @get:JsonProperty("shard_id") val shardId: String = "",
    @param:JsonProperty("file_id") @get:JsonProperty("file_id") val fileId: String = "",
    @param:JsonProperty("message_id") @get:JsonProperty("message_id") val messageId: Long = 0L,
    @param:JsonProperty("file_name") @get:JsonProperty("file_name") val fileName: String = "",
    @param:JsonProperty("size_bytes") @get:JsonProperty("size_bytes") val sizeBytes: Long = 0L,
    @param:JsonProperty("sha256") @get:JsonProperty("sha256") val sha256: String = "",
    @param:JsonProperty("item_count") @get:JsonProperty("item_count") val itemCount: Int = 0
)

@Keep
@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogMediaRecord(
    @param:JsonProperty("media_sha256") @get:JsonProperty("media_sha256") val mediaSha256: String = "",
    @param:JsonProperty("display_name") @get:JsonProperty("display_name") val displayName: String? = null,
    @param:JsonProperty("size_bytes") @get:JsonProperty("size_bytes") val sizeBytes: Long = 0L,
    @param:JsonProperty("media_type") @get:JsonProperty("media_type") val mediaType: String = "IMAGE",
    @param:JsonProperty("telegram_file_id") @get:JsonProperty("telegram_file_id") val telegramFileId: String = "",
    @param:JsonProperty("telegram_message_id") @get:JsonProperty("telegram_message_id") val telegramMessageId: Long? = null,
    @param:JsonProperty("backup_timestamp") @get:JsonProperty("backup_timestamp") val backupTimestamp: Long = System.currentTimeMillis(),
    @param:JsonProperty("backup_status") @get:JsonProperty("backup_status") val backupStatus: String = "DONE",
    @param:JsonProperty("local_id") @get:JsonProperty("local_id") val localId: String? = null
)

@Keep
@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogShard(
    @param:JsonProperty("format") @get:JsonProperty("format") val format: String = FORMAT_IDENTIFIER,
    @param:JsonProperty("version") @get:JsonProperty("version") val version: Int = CURRENT_VERSION,
    @param:JsonProperty("shard_id") @get:JsonProperty("shard_id") val shardId: String = "",
    @param:JsonProperty("created_at") @get:JsonProperty("created_at") val createdAt: Long = System.currentTimeMillis(),
    @param:JsonProperty("bot_id") @get:JsonProperty("bot_id") val botId: Long = 0L,
    @param:JsonProperty("channel_id") @get:JsonProperty("channel_id") val channelId: Long = 0L,
    @param:JsonProperty("item_count") @get:JsonProperty("item_count") val itemCount: Int = records.size,
    @param:JsonProperty("records") @get:JsonProperty("records") val records: List<CatalogMediaRecord> = emptyList()
) {
    fun validate() {
        check(format == FORMAT_IDENTIFIER) { "Invalid shard format: $format" }
        check(version == CURRENT_VERSION) { "Unsupported shard version: $version" }
        check(shardId.isNotBlank()) { "shard_id cannot be blank" }
        check(botId > 0L) { "bot_id must be positive: $botId" }
        check(channelId != 0L) { "channel_id cannot be 0" }
        check(records.isNotEmpty()) { "Shard records cannot be empty" }
    }

    companion object {
        const val FORMAT_IDENTIFIER = "gallerysync_catalog_shard"
        const val CURRENT_VERSION = 1
        const val MAX_SHARD_RECORDS = 1000 // 1,000 records per shard (~250 KB)
    }
}
