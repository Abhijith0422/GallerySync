package com.gallerysync.app.data.localdb.backup

import androidx.annotation.Keep
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.RemotePhoto
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@Keep
@JsonIgnoreProperties(ignoreUnknown = true)
data class BackupFile(
    @JsonProperty("version") val version: Int = 3,
    @JsonProperty("deviceId") val deviceId: String = "",
    @JsonProperty("deviceName") val deviceName: String = "",
    @JsonProperty("createdAt") val createdAt: Long = System.currentTimeMillis(),
    @JsonProperty("channelId") val channelId: Long? = null,
    @JsonProperty("botUsername") val botUsername: String? = null,
    @JsonProperty("photos") val photos: List<Photo> = emptyList(),
    @JsonProperty("remotePhotos") val remotePhotos: List<RemotePhoto> = emptyList(),
    @JsonProperty("mediaRecords") val mediaRecords: List<ExportedMediaRecord> = emptyList()
)

/**
 * Durable media metadata record for cross-device and reinstall recovery (Section 6 A).
 * Never contains secret credentials or bot tokens.
 */
@Keep
@JsonIgnoreProperties(ignoreUnknown = true)
data class ExportedMediaRecord(
    @JsonProperty("originalMediaId") val originalMediaId: String = "",
    @JsonProperty("originalUri") val originalUri: String? = null,
    @JsonProperty("displayName") val displayName: String? = null,
    @JsonProperty("fileSize") val fileSize: Long? = null,
    @JsonProperty("mediaType") val mediaType: String = "IMAGE",
    @JsonProperty("modificationTimestamp") val modificationTimestamp: Long? = null,
    @JsonProperty("contentSha256") val contentSha256: String? = null,
    @JsonProperty("telegramFileId") val telegramFileId: String? = null,
    @JsonProperty("telegramMessageId") val telegramMessageId: Long? = null,
    @JsonProperty("destinationChannelId") val destinationChannelId: Long? = null,
    @JsonProperty("botIdentityId") val botIdentityId: String? = null,
    @JsonProperty("backupCompletionState") val backupCompletionState: String = "DONE",
    @JsonProperty("backupTimestamp") val backupTimestamp: Long = System.currentTimeMillis()
)
