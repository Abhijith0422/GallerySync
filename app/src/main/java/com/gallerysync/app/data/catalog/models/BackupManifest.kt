package com.gallerysync.app.data.catalog.models

import androidx.annotation.Keep
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@Keep
@JsonIgnoreProperties(ignoreUnknown = true)
data class BackupManifest(
    @param:JsonProperty("format") @get:JsonProperty("format") val format: String = FORMAT_IDENTIFIER,
    @param:JsonProperty("version") @get:JsonProperty("version") val version: Int = CURRENT_VERSION,
    @param:JsonProperty("backup_id") @get:JsonProperty("backup_id") val backupId: String = "",
    @param:JsonProperty("created_at") @get:JsonProperty("created_at") val createdAt: Long = System.currentTimeMillis(),
    @param:JsonProperty("application") @get:JsonProperty("application") val application: ApplicationInfo = ApplicationInfo(),
    @param:JsonProperty("bot") @get:JsonProperty("bot") val bot: BotIdentity = BotIdentity(),
    @param:JsonProperty("destination") @get:JsonProperty("destination") val destination: DestinationInfo = DestinationInfo(),
    @param:JsonProperty("media") @get:JsonProperty("media") val media: MediaDescriptor = MediaDescriptor(),
    @param:JsonProperty("chunk_configuration") @get:JsonProperty("chunk_configuration") val chunkConfiguration: ManifestChunkConfig = ManifestChunkConfig(),
    @param:JsonProperty("chunks") @get:JsonProperty("chunks") val chunks: List<ManifestChunkDescriptor> = emptyList()
) {
    /**
     * Validates integrity, ordering, contiguity, and completeness of the manifest.
     * Throws [IllegalStateException] if validation fails.
     */
    fun validate() {
        check(format == FORMAT_IDENTIFIER) { "Invalid manifest format: $format" }
        check(version == CURRENT_VERSION) { "Unsupported manifest version: $version" }
        check(backupId.isNotBlank()) { "backup_id cannot be blank" }
        check(bot.id > 0L) { "bot.id must be positive: ${bot.id}" }
        check(destination.chatId != 0L) { "destination.chat_id cannot be 0" }
        check(media.sizeBytes > 0L) { "media.size_bytes must be positive: ${media.sizeBytes}" }
        check(!media.sha256.isNullOrBlank()) { "media.sha256 cannot be null or blank" }
        check(chunkConfiguration.totalChunks > 0) { "chunk_configuration.total_chunks must be positive: ${chunkConfiguration.totalChunks}" }
        check(chunks.size == chunkConfiguration.totalChunks) {
            "Chunks count (${chunks.size}) does not match total_chunks (${chunkConfiguration.totalChunks})"
        }

        var expectedOffset = 0L
        for (i in chunks.indices) {
            val chunk = chunks[i]
            check(chunk.index == i) { "Chunk index mismatch at position $i: found ${chunk.index}" }
            check(chunk.startOffset == expectedOffset) {
                "Chunk $i start_offset (${chunk.startOffset}) does not match expected ($expectedOffset)"
            }
            check(chunk.endOffsetExclusive > chunk.startOffset) {
                "Chunk $i end_offset_exclusive (${chunk.endOffsetExclusive}) must be greater than start_offset (${chunk.startOffset})"
            }
            check(chunk.sizeBytes == (chunk.endOffsetExclusive - chunk.startOffset)) {
                "Chunk $i size_bytes (${chunk.sizeBytes}) does not equal span (${chunk.endOffsetExclusive - chunk.startOffset})"
            }
            check(!chunk.sha256.isNullOrBlank()) { "Chunk $i sha256 cannot be null or blank" }
            check(!chunk.telegramFileId.isNullOrBlank()) { "Chunk $i telegram_file_id cannot be null or blank" }
            check(chunk.telegramMessageId != null && chunk.telegramMessageId > 0L) {
                "Chunk $i telegram_message_id must be valid: ${chunk.telegramMessageId}"
            }
            expectedOffset = chunk.endOffsetExclusive
        }

        check(expectedOffset == media.sizeBytes) {
            "Total chunks byte span ($expectedOffset) does not equal media.size_bytes (${media.sizeBytes})"
        }
    }

    companion object {
        const val FORMAT_IDENTIFIER = "gallerysync_manifest"
        const val CURRENT_VERSION = 1
    }
}

@Keep
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApplicationInfo(
    @param:JsonProperty("name") @get:JsonProperty("name") val name: String = "GallerySync",
    @param:JsonProperty("package") @get:JsonProperty("package") val packageName: String = "com.gallerysync.app"
)

@Keep
@JsonIgnoreProperties(ignoreUnknown = true)
data class BotIdentity(
    @param:JsonProperty("id") @get:JsonProperty("id") val id: Long = 0L,
    @param:JsonProperty("username") @get:JsonProperty("username") val username: String? = null
)

@Keep
@JsonIgnoreProperties(ignoreUnknown = true)
data class DestinationInfo(
    @param:JsonProperty("chat_id") @get:JsonProperty("chat_id") val chatId: Long = 0L,
    @param:JsonProperty("topic_id") @get:JsonProperty("topic_id") val topicId: Long? = null
)

@Keep
@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaDescriptor(
    @param:JsonProperty("original_name") @get:JsonProperty("original_name") val originalName: String = "",
    @param:JsonProperty("mime_type") @get:JsonProperty("mime_type") val mimeType: String = "",
    @param:JsonProperty("size_bytes") @get:JsonProperty("size_bytes") val sizeBytes: Long = 0L,
    @param:JsonProperty("width") @get:JsonProperty("width") val width: Int? = null,
    @param:JsonProperty("height") @get:JsonProperty("height") val height: Int? = null,
    @param:JsonProperty("duration_ms") @get:JsonProperty("duration_ms") val durationMs: Long? = null,
    @param:JsonProperty("sha256") @get:JsonProperty("sha256") val sha256: String = ""
)

@Keep
@JsonIgnoreProperties(ignoreUnknown = true)
data class ManifestChunkConfig(
    @param:JsonProperty("chunk_size_bytes") @get:JsonProperty("chunk_size_bytes") val chunkSizeBytes: Long = 0L,
    @param:JsonProperty("total_chunks") @get:JsonProperty("total_chunks") val totalChunks: Int = 0
)

@Keep
@JsonIgnoreProperties(ignoreUnknown = true)
data class ManifestChunkDescriptor(
    @param:JsonProperty("index") @get:JsonProperty("index") val index: Int = 0,
    @param:JsonProperty("start_offset") @get:JsonProperty("start_offset") val startOffset: Long = 0L,
    @param:JsonProperty("end_offset_exclusive") @get:JsonProperty("end_offset_exclusive") val endOffsetExclusive: Long = 0L,
    @param:JsonProperty("size_bytes") @get:JsonProperty("size_bytes") val sizeBytes: Long = 0L,
    @param:JsonProperty("sha256") @get:JsonProperty("sha256") val sha256: String = "",
    @param:JsonProperty("telegram_file_id") @get:JsonProperty("telegram_file_id") val telegramFileId: String = "",
    @param:JsonProperty("telegram_message_id") @get:JsonProperty("telegram_message_id") val telegramMessageId: Long? = null
)
