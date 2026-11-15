package com.gallerysync.app.utils.coil

import android.util.Log
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.gallerysync.app.api.BotApi
import com.gallerysync.app.data.localdb.entities.RemotePhoto
import com.gallerysync.app.utils.getMimeTypeFromExt

/**
 * Wrapper to pass a Telegram fileId as Coil image data without conflicting
 * with built-in [Fetcher.Factory] implementations for [String].
 */
data class FileIdData(val fileId: String)

class NetworkFetcher(
    private val remotePhoto: RemotePhoto,
    private val options: Options,
    private val fileFetcher: suspend (String) -> ByteArray? = { BotApi.getFile(it) }
) : Fetcher {

    constructor(
        botApi: BotApi,
        remotePhoto: RemotePhoto,
        options: Options
    ) : this(remotePhoto, options, { botApi.getFile(it) })

    override suspend fun fetch(): FetchResult? {
        val isVideo = remotePhoto.mediaType == "VIDEO"
        // For video files, only fetch the thumbnail (previewRemoteId). Never attempt to download the full video file.
        val targetFileId = if (isVideo) {
            remotePhoto.previewRemoteId ?: run {
                Log.d(TAG, "🌐 Video ${remotePhoto.remoteId} has no previewRemoteId; skipping thumbnail network fetch")
                return null
            }
        } else {
            remotePhoto.previewRemoteId ?: remotePhoto.remoteId
        }

        Log.d(TAG, "🌐 === NETWORK FETCHER START ===")
        Log.d(TAG, "🌐 Fetching image for targetFileId: $targetFileId (remoteId: ${remotePhoto.remoteId}, mediaType: ${remotePhoto.mediaType})")
        Log.d(TAG, "🌐 Photo type: ${remotePhoto.photoType}")
        Log.d(TAG, "🌐 Photo fileName: ${remotePhoto.fileName}")
        Log.d(TAG, "🌐 Photo fileSize: ${remotePhoto.fileSize}")
        Log.d(TAG, "🌐 Request size: ${options.size}")

        return try {
            Log.i(TAG, "🌐 Calling fileFetcher for targetFileId: $targetFileId")
            val startTime = System.currentTimeMillis()
            val byteArray = fileFetcher(targetFileId)
            val endTime = System.currentTimeMillis()

            if (byteArray != null) {
                Log.i(TAG, "🌐 ✅ SUCCESS: Downloaded ${byteArray.size} bytes in ${endTime - startTime}ms for targetFileId: $targetFileId")
                val mimeType = if (isVideo) "image/jpeg" else getMimeTypeFromExt(remotePhoto.photoType)
                Log.d(TAG, "🌐 Detected MIME type: $mimeType")

                val buffer = okio.Buffer().write(byteArray)
                val result = SourceResult(
                    source = ImageSource(buffer, options.context),
                    mimeType = mimeType,
                    dataSource = DataSource.NETWORK
                )
                Log.i(TAG, "🌐 Created SourceResult for targetFileId: $targetFileId")
                Log.d(TAG, "🌐 === NETWORK FETCHER SUCCESS ===")
                result
            } else {
                Log.e(TAG, "🌐 ❌ FAILED: BotApi.getFile returned null for targetFileId: $targetFileId")
                Log.d(TAG, "🌐 === NETWORK FETCHER FAILED ===")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "🌐 ❌ EXCEPTION in NetworkFetcher for targetFileId: $targetFileId", e)
            Log.d(TAG, "🌐 === NETWORK FETCHER EXCEPTION ===")
            null
        }
    }

    class Factory(
        private val botApi: BotApi = BotApi,
    ) : Fetcher.Factory<RemotePhoto> {
        override fun create(
            data: RemotePhoto,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            Log.d(TAG, "🏭 NetworkFetcher.Factory creating fetcher for remoteId: ${data.remoteId}, size: ${options.size}")
            return NetworkFetcher(botApi, data, options)
        }
    }

    class FileIdFactory(
        private val botApi: BotApi = BotApi,
    ) : Fetcher.Factory<FileIdData> {
        override fun create(
            data: FileIdData,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            Log.d(TAG, "🏭 FileIdFactory creating fetcher for fileId: ${data.fileId}, size: ${options.size}")
            return FileIdFetcher(botApi, data.fileId, options)
        }
    }

    companion object {
        private const val TAG = "NetworkFetcher"
    }
}

class FileIdFetcher(
    private val botApi: BotApi = BotApi,
    private val fileId: String,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        if (fileId.isBlank()) {
            Log.d(TAG, "🌐 FileIdFetcher: blank fileId passed; returning null")
            return null
        }
        Log.d(TAG, "🌐 === FILE ID FETCHER START ===")
        Log.d(TAG, "🌐 Fetching image for fileId: $fileId")
        Log.d(TAG, "🌐 Request size: ${options.size}")

        return try {
            Log.i(TAG, "🌐 Calling BotApi.getFile for fileId: $fileId")
            val startTime = System.currentTimeMillis()
            val byteArray = botApi.getFile(fileId)
            val endTime = System.currentTimeMillis()

            if (byteArray != null) {
                Log.i(TAG, "🌐 ✅ SUCCESS: Downloaded ${byteArray.size} bytes in ${endTime - startTime}ms for fileId: $fileId")
                val buffer = okio.Buffer().write(byteArray)
                val result = SourceResult(
                    source = ImageSource(buffer, options.context),
                    mimeType = null,
                    dataSource = DataSource.NETWORK
                )
                Log.i(TAG, "🌐 Created SourceResult for fileId: $fileId")
                Log.d(TAG, "🌐 === FILE ID FETCHER SUCCESS ===")
                result
            } else {
                Log.e(TAG, "🌐 ❌ FAILED: BotApi.getFile returned null for fileId: $fileId")
                Log.d(TAG, "🌐 === FILE ID FETCHER FAILED ===")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "🌐 ❌ EXCEPTION in FileIdFetcher for fileId: $fileId", e)
            Log.d(TAG, "🌐 === FILE ID FETCHER EXCEPTION ===")
            null
        }
    }

    companion object {
        private const val TAG = "FileIdFetcher"
    }
}
