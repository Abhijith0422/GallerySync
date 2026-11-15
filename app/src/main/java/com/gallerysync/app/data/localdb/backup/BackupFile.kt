package com.gallerysync.app.data.localdb.backup

import androidx.annotation.Keep
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.RemotePhoto

@Keep
data class BackupFile(
    val version: Int = 2,
    val deviceId: String = "",
    val deviceName: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val photos: List<Photo> = emptyList(),
    val remotePhotos: List<RemotePhoto> = emptyList(),
)
