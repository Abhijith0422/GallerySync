package com.gallerysync.app.data.restoration

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.gallerysync.app.api.TelegramCatalogApi
import com.gallerysync.app.api.TelegramCatalogResult
import com.gallerysync.app.data.catalog.models.ApplicationInfo
import com.gallerysync.app.data.catalog.models.BackupManifest
import com.gallerysync.app.data.catalog.models.BotIdentity
import com.gallerysync.app.data.catalog.models.DestinationInfo
import com.gallerysync.app.data.catalog.models.ManifestChunkConfig
import com.gallerysync.app.data.catalog.models.ManifestChunkDescriptor
import com.gallerysync.app.data.catalog.models.MediaDescriptor
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.ChunkedBackup
import com.gallerysync.app.data.localdb.entities.RemotePhoto
import com.gallerysync.app.data.localdb.entities.VideoRestorationJob
import com.gallerysync.app.utils.ContentHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.UUID

sealed class RestorationExecutionResult {
    data class Success(
        val restorationId: String,
        val restoredFile: File?,
        val destinationUri: String?,
        val totalBytes: Long,
        val fullSha256: String
    ) : RestorationExecutionResult()

    data class AwaitingDestination(
        val restorationId: String,
        val stagedOutputFile: File,
        val totalBytes: Long,
        val fullSha256: String
    ) : RestorationExecutionResult()

    data class RateLimited(val retryAfterSeconds: Int, val description: String) : RestorationExecutionResult()
    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) : RestorationExecutionResult()
    data class VerificationFailed(val reason: String) : RestorationExecutionResult()
    data class Cancelled(val reason: String = "Restoration cancelled") : RestorationExecutionResult()
    data class Error(val message: String, val isRetryable: Boolean = true) : RestorationExecutionResult()
}

/**
 * High-level orchestration manager for video restoration.
 *
 * Guarantees:
 * - Independent of the upload queue (never represents restoration as an upload).
 * - Enforces bot and channel configuration consistency.
 * - Validates available staging and destination storage.
 * - Resumable: retains and revalidates verified chunks across restarts.
 * - Streams output directly to Android Storage Access Framework (SAF) URI.
 * - Cleans up staging chunks only after final destination verification.
 */
class VideoRestorationManager(
    private val database: WhDatabase = DbHolder.database,
    private val downloader: TelegramFileDownloader = TelegramFileDownloader.defaultInstance,
    private val reconstructionEngine: VideoReconstructionEngine = VideoReconstructionEngine.defaultInstance,
    private val catalogApi: TelegramCatalogApi = TelegramCatalogApi.defaultInstance
) {

    private val mapper by lazy {
        ObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    /**
     * Resolves an existing active or matching restoration job, or initializes a new one.
     */
    suspend fun createOrGetJob(
        context: Context,
        backupId: String? = null,
        remoteId: String? = null,
        localId: String? = null,
        fileName: String,
        mimeType: String = "video/mp4",
        fileSize: Long,
        fileSha256: String = "",
        totalChunks: Int = 1,
        destinationUri: String? = null
    ): VideoRestorationJob = withContext(Dispatchers.IO) {
        val dao = database.videoRestorationDao()

        // 1. Check for existing active or incomplete job
        if (!backupId.isNullOrBlank()) {
            val existing = dao.getByBackupId(backupId)
            if (existing != null && existing.status != RestorationStatus.COMPLETED && existing.status != RestorationStatus.CANCELLED) {
                if (destinationUri != null && existing.destinationUri != destinationUri) {
                    dao.updateDestinationUri(existing.restorationId, destinationUri)
                    return@withContext existing.copy(destinationUri = destinationUri)
                }
                return@withContext existing
            }
        } else if (!remoteId.isNullOrBlank()) {
            val existing = dao.getByRemoteId(remoteId)
            if (existing != null && existing.status != RestorationStatus.COMPLETED && existing.status != RestorationStatus.CANCELLED) {
                if (destinationUri != null && existing.destinationUri != destinationUri) {
                    dao.updateDestinationUri(existing.restorationId, destinationUri)
                    return@withContext existing.copy(destinationUri = destinationUri)
                }
                return@withContext existing
            }
        }

        // 2. Create fresh job
        val restorationId = UUID.randomUUID().toString()
        val stagingDir = getStagingDir(context, restorationId).absolutePath

        val job = VideoRestorationJob(
            restorationId = restorationId,
            backupId = backupId,
            remoteId = remoteId,
            localId = localId,
            originalFileName = fileName,
            mimeType = mimeType,
            expectedFileSize = fileSize,
            expectedSha256 = fileSha256,
            totalChunks = totalChunks,
            verifiedChunks = 0,
            downloadedBytes = 0L,
            status = RestorationStatus.PENDING,
            destinationUri = destinationUri,
            stagingDirectory = stagingDir,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        dao.insertOrReplace(job)
        Log.i(TAG, "Created VideoRestorationJob: id=$restorationId, file=$fileName, size=$fileSize, chunks=$totalChunks")
        job
    }

    /**
     * Executes end-to-end restoration for a job.
     */
    suspend fun executeRestoration(
        context: Context,
        restorationId: String,
        botToken: String,
        isStopped: () -> Boolean = { false },
        onProgress: ((job: VideoRestorationJob) -> Unit)? = null
    ): RestorationExecutionResult = withContext(Dispatchers.IO) {
        val dao = database.videoRestorationDao()
        var job = dao.getById(restorationId)
            ?: return@withContext RestorationExecutionResult.Error("Restoration job $restorationId not found", isRetryable = false)

        if (isStopped()) {
            return@withContext RestorationExecutionResult.Cancelled()
        }

        // 1. Storage check (ensure available storage >= file size * 2 for chunked, or file size + margin)
        val stagingDir = File(job.stagingDirectory)
        stagingDir.mkdirs()
        val requiredBytes = job.expectedFileSize + (30L * 1024 * 1024) // file size + 30 MB buffer
        val availableBytes = getAvailableStorageBytes(stagingDir)
        if (availableBytes < requiredBytes) {
            val err = "Insufficient storage: required $requiredBytes bytes, available $availableBytes bytes"
            Log.e(TAG, err)
            dao.updateStatus(restorationId, RestorationStatus.FAILED, err)
            return@withContext RestorationExecutionResult.InsufficientStorage(requiredBytes, availableBytes)
        }

        // Route to chunked or normal video restoration
        if (!job.backupId.isNullOrBlank() || job.totalChunks > 1) {
            restoreChunkedVideoInternal(context, job, botToken, isStopped, onProgress)
        } else {
            restoreNormalVideoInternal(context, job, botToken, isStopped, onProgress)
        }
    }

    /**
     * Pipeline for chunked video restoration.
     */
    private suspend fun restoreChunkedVideoInternal(
        context: Context,
        initialJob: VideoRestorationJob,
        botToken: String,
        isStopped: () -> Boolean,
        onProgress: ((job: VideoRestorationJob) -> Unit)?
    ): RestorationExecutionResult {
        val dao = database.videoRestorationDao()
        var job = initialJob
        val restorationId = job.restorationId
        val stagingDir = File(job.stagingDirectory)
        val chunksDir = File(stagingDir, RestorationConfiguration.CHUNK_STAGE_SUBDIR).apply { mkdirs() }
        val outputDir = File(stagingDir, RestorationConfiguration.RECONSTRUCT_STAGE_SUBDIR).apply { mkdirs() }
        val outputFile = File(outputDir, "${job.originalFileName}.reconstructed")

        // 1. Resolve manifest
        dao.updateStatus(restorationId, RestorationStatus.VALIDATING)
        job = job.copy(status = RestorationStatus.VALIDATING)
        onProgress?.invoke(job)

        val manifest = resolveManifest(context, job.backupId ?: "", botToken)
        if (manifest == null) {
            val err = "Failed to resolve or validate backup manifest for backupId: ${job.backupId}"
            Log.e(TAG, err)
            dao.updateStatus(restorationId, RestorationStatus.FAILED, err)
            return RestorationExecutionResult.Error(err, isRetryable = false)
        }

        try {
            manifest.validate()
        } catch (e: Exception) {
            val err = "Manifest validation failed: ${e.message}"
            Log.e(TAG, err, e)
            dao.updateStatus(restorationId, RestorationStatus.FAILED, err)
            return RestorationExecutionResult.VerificationFailed(err)
        }

        // Fast-path: Check if reconstructed output is already verified on disk (e.g. SAF export retry)
        if (outputFile.exists() && outputFile.length() == manifest.media.sizeBytes) {
            val existingSha = outputFile.inputStream().use { ContentHasher.computeHashFromStream(it) }
            if (existingSha.equals(manifest.media.sha256, ignoreCase = true)) {
                Log.i(TAG, "Reconstructed output file already verified on disk for $restorationId. Skipping chunk downloads and reconstruction.")
                dao.updateProgress(
                    restorationId = restorationId,
                    status = RestorationStatus.RECONSTRUCTING,
                    verifiedChunks = manifest.chunkConfiguration.totalChunks,
                    downloadedBytes = manifest.media.sizeBytes
                )
                val destUri = job.destinationUri
                if (destUri.isNullOrBlank()) {
                    dao.updateStatus(restorationId, RestorationStatus.AWAITING_DESTINATION)
                    job = job.copy(status = RestorationStatus.AWAITING_DESTINATION)
                    onProgress?.invoke(job)
                    return RestorationExecutionResult.AwaitingDestination(
                        restorationId = restorationId,
                        stagedOutputFile = outputFile,
                        totalBytes = manifest.media.sizeBytes,
                        fullSha256 = manifest.media.sha256
                    )
                }
                return exportReconstructedFileToSaf(context, job, outputFile, Uri.parse(destUri), manifest.media.sha256, onProgress)
            } else {
                Log.w(TAG, "Existing reconstructed file hash mismatch. Deleting invalid output.")
                outputFile.delete()
            }
        }

        // 2. Download and verify missing chunks (resumable)
        dao.updateStatus(restorationId, RestorationStatus.DOWNLOADING)
        job = job.copy(status = RestorationStatus.DOWNLOADING)
        onProgress?.invoke(job)

        val chunkFilesMap = mutableMapOf<Int, File>()
        var verifiedCount = 0
        var totalBytesDownloaded = 0L

        for (desc in manifest.chunks) {
            if (isStopped()) {
                Log.i(TAG, "Restoration stopped at chunk boundary ${desc.index}")
                return RestorationExecutionResult.Cancelled()
            }

            val chunkFile = File(chunksDir, "chunk_${desc.index}.bin")

            // Re-validate existing staging chunk file
            var chunkValid = false
            if (chunkFile.exists() && chunkFile.length() == desc.sizeBytes) {
                val existingHash = chunkFile.inputStream().use { ContentHasher.computeHashFromStream(it) }
                if (existingHash.equals(desc.sha256, ignoreCase = true)) {
                    chunkValid = true
                    verifiedCount++
                    totalBytesDownloaded += desc.sizeBytes
                    chunkFilesMap[desc.index] = chunkFile
                    Log.i(TAG, "Retained previously verified chunk ${desc.index} (${desc.sizeBytes} bytes)")
                } else {
                    Log.w(TAG, "Existing chunk ${desc.index} hash mismatch. Discarding corrupt file.")
                    chunkFile.delete()
                }
            }

            if (!chunkValid) {
                // Download chunk
                val dlResult = downloader.downloadToFile(
                    botToken = botToken,
                    fileId = desc.telegramFileId,
                    destinationFile = chunkFile,
                    expectedSizeBytes = desc.sizeBytes,
                    expectedSha256 = desc.sha256,
                    isCancelled = isStopped,
                    onProgress = { bytesRead, _ ->
                        val currentTotal = totalBytesDownloaded + bytesRead
                        job = job.copy(
                            status = RestorationStatus.DOWNLOADING,
                            verifiedChunks = verifiedCount,
                            downloadedBytes = currentTotal
                        )
                        onProgress?.invoke(job)
                    }
                )

                when (dlResult) {
                    is TelegramDownloadResult.Success -> {
                        verifiedCount++
                        totalBytesDownloaded += dlResult.bytesRead
                        chunkFilesMap[desc.index] = chunkFile
                        dao.updateProgress(
                            restorationId = restorationId,
                            status = RestorationStatus.DOWNLOADING,
                            verifiedChunks = verifiedCount,
                            downloadedBytes = totalBytesDownloaded
                        )
                        job = job.copy(verifiedChunks = verifiedCount, downloadedBytes = totalBytesDownloaded)
                        onProgress?.invoke(job)
                    }
                    is TelegramDownloadResult.RateLimited -> {
                        return RestorationExecutionResult.RateLimited(dlResult.retryAfterSeconds, dlResult.description)
                    }
                    is TelegramDownloadResult.Cancelled -> {
                        return RestorationExecutionResult.Cancelled()
                    }
                    is TelegramDownloadResult.IntegrityMismatch -> {
                        val err = "Integrity mismatch on chunk ${desc.index}: expected SHA=${desc.sha256}, actual SHA=${dlResult.actualSha256}"
                        dao.updateStatus(restorationId, RestorationStatus.FAILED, err)
                        return RestorationExecutionResult.VerificationFailed(err)
                    }
                    is TelegramDownloadResult.InsufficientStorage -> {
                        return RestorationExecutionResult.InsufficientStorage(dlResult.requiredBytes, dlResult.availableBytes)
                    }
                    else -> {
                        val err = "Failed downloading chunk ${desc.index}"
                        dao.updateStatus(restorationId, RestorationStatus.FAILED, err)
                        return RestorationExecutionResult.Error(err)
                    }
                }
            }
        }

        // 3. Reconstruct original file
        dao.updateStatus(restorationId, RestorationStatus.RECONSTRUCTING)
        job = job.copy(status = RestorationStatus.RECONSTRUCTING)
        onProgress?.invoke(job)

        val reconResult = reconstructionEngine.reconstruct(
            chunkFiles = chunkFilesMap,
            chunkDescriptors = manifest.chunks,
            expectedTotalSize = manifest.media.sizeBytes,
            expectedFullSha256 = manifest.media.sha256,
            outputFile = outputFile,
            isCancelled = isStopped,
            onProgress = { written, total ->
                job = job.copy(downloadedBytes = written)
                onProgress?.invoke(job)
            }
        )

        val reconstructedFile = when (reconResult) {
            is ReconstructionResult.Success -> reconResult.outputFile
            is ReconstructionResult.Cancelled -> return RestorationExecutionResult.Cancelled()
            is ReconstructionResult.VerificationFailed -> {
                val err = "Reconstructed file verification failed: expected SHA=${reconResult.expectedSha256}, computed SHA=${reconResult.actualSha256}"
                dao.updateStatus(restorationId, RestorationStatus.FAILED, err)
                return RestorationExecutionResult.VerificationFailed(err)
            }
            is ReconstructionResult.ChunkMissing -> {
                val err = "Missing chunk during reconstruction: ${reconResult.chunkIndex}"
                dao.updateStatus(restorationId, RestorationStatus.FAILED, err)
                return RestorationExecutionResult.Error(err)
            }
            is ReconstructionResult.ChunkCorrupted -> {
                val err = "Corrupted chunk during reconstruction: ${reconResult.chunkIndex}: ${reconResult.reason}"
                dao.updateStatus(restorationId, RestorationStatus.FAILED, err)
                return RestorationExecutionResult.VerificationFailed(err)
            }
            is ReconstructionResult.ValidationFailed -> {
                dao.updateStatus(restorationId, RestorationStatus.FAILED, reconResult.reason)
                return RestorationExecutionResult.Error(reconResult.reason)
            }
        }

        // 4. Output writing to SAF destination
        val destUri = job.destinationUri
        if (destUri.isNullOrBlank()) {
            dao.updateStatus(restorationId, RestorationStatus.AWAITING_DESTINATION)
            job = job.copy(status = RestorationStatus.AWAITING_DESTINATION)
            onProgress?.invoke(job)
            return RestorationExecutionResult.AwaitingDestination(
                restorationId = restorationId,
                stagedOutputFile = reconstructedFile,
                totalBytes = manifest.media.sizeBytes,
                fullSha256 = manifest.media.sha256
            )
        }

        return exportReconstructedFileToSaf(context, job, reconstructedFile, Uri.parse(destUri), manifest.media.sha256, onProgress)
    }

    /**
     * Pipeline for normal (single-file) video restoration.
     */
    private suspend fun restoreNormalVideoInternal(
        context: Context,
        initialJob: VideoRestorationJob,
        botToken: String,
        isStopped: () -> Boolean,
        onProgress: ((job: VideoRestorationJob) -> Unit)?
    ): RestorationExecutionResult {
        val dao = database.videoRestorationDao()
        var job = initialJob
        val restorationId = job.restorationId
        val stagingDir = File(job.stagingDirectory)
        val outputDir = File(stagingDir, RestorationConfiguration.RECONSTRUCT_STAGE_SUBDIR).apply { mkdirs() }
        val outputFile = File(outputDir, "${job.originalFileName}.downloaded")

        val remoteId = job.remoteId
        if (remoteId.isNullOrBlank()) {
            val err = "remoteId missing for normal video restoration"
            dao.updateStatus(restorationId, RestorationStatus.FAILED, err)
            return RestorationExecutionResult.Error(err, isRetryable = false)
        }

        // 0. Check direct video download compatibility against Telegram hosted 20 MB ceiling
        if (job.expectedFileSize > RestorationConfiguration.MAX_HOSTED_TELEGRAM_DOWNLOAD_BYTES) {
            val err = "Direct video (${job.expectedFileSize} bytes) exceeds Telegram hosted download limit of " +
                "${RestorationConfiguration.MAX_HOSTED_TELEGRAM_DOWNLOAD_BYTES} bytes. " +
                "Direct videos over 20 MB cannot be retrieved via api.telegram.org."
            Log.e(TAG, err)
            dao.updateStatus(restorationId, RestorationStatus.FAILED, err)
            return RestorationExecutionResult.Error(err, isRetryable = false)
        }

        val expectedSize = job.expectedFileSize.takeIf { it > 0L }
        val expectedHash = job.expectedSha256.takeIf { it.isNotBlank() }

        // Fast-path: Check if downloaded normal video is already verified on disk (e.g. SAF export retry)
        if (outputFile.exists() && expectedSize != null && outputFile.length() == expectedSize) {
            val existingHash = outputFile.inputStream().use { ContentHasher.computeHashFromStream(it) }
            if (expectedHash == null || existingHash.equals(expectedHash, ignoreCase = true)) {
                Log.i(TAG, "Normal video already downloaded and verified on disk for $restorationId. Skipping re-download.")
                dao.updateProgress(
                    restorationId = restorationId,
                    status = RestorationStatus.VERIFYING,
                    verifiedChunks = 1,
                    downloadedBytes = expectedSize
                )
                val destUri = job.destinationUri
                if (destUri.isNullOrBlank()) {
                    dao.updateStatus(restorationId, RestorationStatus.AWAITING_DESTINATION)
                    job = job.copy(status = RestorationStatus.AWAITING_DESTINATION)
                    onProgress?.invoke(job)
                    return RestorationExecutionResult.AwaitingDestination(
                        restorationId = restorationId,
                        stagedOutputFile = outputFile,
                        totalBytes = expectedSize,
                        fullSha256 = existingHash
                    )
                }
                return exportReconstructedFileToSaf(context, job, outputFile, Uri.parse(destUri), existingHash, onProgress)
            } else {
                Log.w(TAG, "Existing downloaded normal video hash mismatch. Deleting invalid file.")
                outputFile.delete()
            }
        }

        // 1. Download
        dao.updateStatus(restorationId, RestorationStatus.DOWNLOADING)
        job = job.copy(status = RestorationStatus.DOWNLOADING)
        onProgress?.invoke(job)

        val dlResult = downloader.downloadToFile(
            botToken = botToken,
            fileId = remoteId,
            destinationFile = outputFile,
            expectedSizeBytes = expectedSize,
            expectedSha256 = expectedHash,
            isCancelled = isStopped,
            onProgress = { bytesRead, _ ->
                job = job.copy(downloadedBytes = bytesRead)
                onProgress?.invoke(job)
            }
        )

        when (dlResult) {
            is TelegramDownloadResult.Success -> {
                val computedHash = dlResult.sha256
                dao.updateProgress(
                    restorationId = restorationId,
                    status = RestorationStatus.VERIFYING,
                    verifiedChunks = 1,
                    downloadedBytes = dlResult.bytesRead
                )

                val destUri = job.destinationUri
                if (destUri.isNullOrBlank()) {
                    dao.updateStatus(restorationId, RestorationStatus.AWAITING_DESTINATION)
                    job = job.copy(status = RestorationStatus.AWAITING_DESTINATION)
                    onProgress?.invoke(job)
                    return RestorationExecutionResult.AwaitingDestination(
                        restorationId = restorationId,
                        stagedOutputFile = outputFile,
                        totalBytes = dlResult.bytesRead,
                        fullSha256 = computedHash
                    )
                }

                return exportReconstructedFileToSaf(context, job, outputFile, Uri.parse(destUri), computedHash, onProgress)
            }
            is TelegramDownloadResult.RateLimited -> {
                return RestorationExecutionResult.RateLimited(dlResult.retryAfterSeconds, dlResult.description)
            }
            is TelegramDownloadResult.Cancelled -> {
                return RestorationExecutionResult.Cancelled()
            }
            is TelegramDownloadResult.IntegrityMismatch -> {
                val err = "Checksum mismatch: expected SHA=$expectedHash, actual SHA=${dlResult.actualSha256}"
                dao.updateStatus(restorationId, RestorationStatus.FAILED, err)
                return RestorationExecutionResult.VerificationFailed(err)
            }
            is TelegramDownloadResult.InsufficientStorage -> {
                return RestorationExecutionResult.InsufficientStorage(dlResult.requiredBytes, dlResult.availableBytes)
            }
            is TelegramDownloadResult.InvalidResponse -> {
                val err = "Telegram API error: ${dlResult.reason}"
                dao.updateStatus(restorationId, RestorationStatus.FAILED, err)
                return RestorationExecutionResult.Error(err, isRetryable = false)
            }
            is TelegramDownloadResult.Unauthorized -> {
                val err = "Telegram unauthorized (401): ${dlResult.description}"
                dao.updateStatus(restorationId, RestorationStatus.FAILED, err)
                return RestorationExecutionResult.Error(err, isRetryable = false)
            }
            else -> {
                val err = "Failed downloading video from Telegram"
                dao.updateStatus(restorationId, RestorationStatus.FAILED, err)
                return RestorationExecutionResult.Error(err)
            }
        }
    }

    /**
     * Streams the verified staged file directly to the SAF destination URI and finalizes the job.
     */
    suspend fun exportReconstructedFileToSaf(
        context: Context,
        job: VideoRestorationJob,
        stagedFile: File,
        destinationUri: Uri,
        verifiedSha256: String,
        onProgress: ((job: VideoRestorationJob) -> Unit)? = null
    ): RestorationExecutionResult = withContext(Dispatchers.IO) {
        val dao = database.videoRestorationDao()
        val restorationId = job.restorationId

        dao.updateStatus(restorationId, RestorationStatus.WRITING_OUTPUT)
        var updatedJob = job.copy(status = RestorationStatus.WRITING_OUTPUT)
        onProgress?.invoke(updatedJob)

        val totalBytes = stagedFile.length()
        var bytesWritten = 0L
        val buffer = ByteArray(RestorationConfiguration.BUFFER_SIZE_BYTES)

        try {
            val resolver = context.contentResolver
            val outStream = if (destinationUri.scheme == "file") {
                val path = destinationUri.path ?: destinationUri.schemeSpecificPart
                java.io.FileOutputStream(File(path))
            } else {
                resolver.openOutputStream(destinationUri)
            } ?: return@withContext RestorationExecutionResult.Error("Failed to open output stream for destination URI", isRetryable = false)

            FileInputStream(stagedFile).use { inStream ->
                outStream.use { out ->
                    while (true) {
                        val count = inStream.read(buffer)
                        if (count == -1) break
                        out.write(buffer, 0, count)
                        bytesWritten += count
                    }
                    out.flush()
                }
            }

            if (bytesWritten != totalBytes) {
                val err = "SAF export size mismatch: wrote $bytesWritten bytes, expected $totalBytes bytes"
                Log.e(TAG, err)
                dao.updateStatus(restorationId, RestorationStatus.FAILED, err)
                return@withContext RestorationExecutionResult.Error(err)
            }

            // Mark job COMPLETED
            val completedTime = System.currentTimeMillis()
            dao.updateStatus(
                restorationId = restorationId,
                status = RestorationStatus.COMPLETED,
                completedAt = completedTime
            )
            updatedJob = updatedJob.copy(
                status = RestorationStatus.COMPLETED,
                completedAt = completedTime
            )
            onProgress?.invoke(updatedJob)

            // Safe cleanup of intermediate staging chunks
            cleanupStagingChunks(context, restorationId)

            Log.i(TAG, "Restoration $restorationId completed successfully! Wrote $bytesWritten bytes to SAF destination.")
            RestorationExecutionResult.Success(
                restorationId = restorationId,
                restoredFile = stagedFile,
                destinationUri = destinationUri.toString(),
                totalBytes = bytesWritten,
                fullSha256 = verifiedSha256
            )
        } catch (e: Exception) {
            val err = "Failed writing to SAF destination: ${e.message}"
            Log.e(TAG, err, e)
            dao.updateStatus(restorationId, RestorationStatus.FAILED, err)
            RestorationExecutionResult.Error(err)
        }
    }

    /**
     * Resolves the [BackupManifest] either from Room or from Telegram document download.
     */
    suspend fun resolveManifest(
        context: Context,
        backupId: String,
        botToken: String
    ): BackupManifest? = withContext(Dispatchers.IO) {
        val chunkedBackupDao = database.chunkedBackupDao()
        val videoChunkDao = database.videoChunkDao()

        // 1. Try resolving from local database
        val backup = chunkedBackupDao.getById(backupId)
        val chunks = videoChunkDao.getByBackupId(backupId)

        if (backup != null && chunks.isNotEmpty() && chunks.size == backup.totalChunks) {
            val manifest = BackupManifest(
                format = BackupManifest.FORMAT_IDENTIFIER,
                version = BackupManifest.CURRENT_VERSION,
                backupId = backup.backupId,
                createdAt = backup.createdAt,
                application = ApplicationInfo(),
                bot = BotIdentity(id = backup.botId, username = backup.botUsername),
                destination = DestinationInfo(chatId = backup.channelId, topicId = backup.topicId),
                media = MediaDescriptor(
                    originalName = backup.fileName,
                    mimeType = backup.mimeType,
                    sizeBytes = backup.fileSize,
                    width = backup.width,
                    height = backup.height,
                    durationMs = backup.duration,
                    sha256 = backup.fileSha256 ?: ""
                ),
                chunkConfiguration = ManifestChunkConfig(
                    chunkSizeBytes = backup.chunkSize,
                    totalChunks = backup.totalChunks
                ),
                chunks = chunks.sortedBy { it.chunkIndex }.map { c ->
                    ManifestChunkDescriptor(
                        index = c.chunkIndex,
                        startOffset = c.startOffset,
                        endOffsetExclusive = c.endOffset,
                        sizeBytes = c.chunkSize,
                        sha256 = c.chunkSha256 ?: "",
                        telegramFileId = c.telegramFileId ?: "",
                        telegramMessageId = c.telegramMessageId
                    )
                }
            )
            return@withContext manifest
        }

        // 2. Fallback: Download manifest JSON from Telegram using manifestFileId
        val manifestFileId = backup?.manifestFileId
        if (!manifestFileId.isNullOrBlank()) {
            val downloadResult = catalogApi.downloadFileByFileId(botToken, manifestFileId)
            val bytes = downloadResult.getOrNull()
            if (bytes != null) {
                return@withContext try {
                    mapper.readValue(bytes, BackupManifest::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed parsing downloaded manifest JSON", e)
                    null
                }
            }
        }

        null
    }

    /**
     * Cleans up staging chunk files while preserving the reconstructed output if still needed.
     */
    fun cleanupStagingChunks(context: Context, restorationId: String) {
        try {
            val stagingDir = getStagingDir(context, restorationId)
            val chunksDir = File(stagingDir, RestorationConfiguration.CHUNK_STAGE_SUBDIR)
            if (chunksDir.exists()) {
                chunksDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning staging chunks for $restorationId", e)
        }
    }

    /**
     * Completely deletes all staging files for a job (e.g. on cancellation or deletion).
     */
    fun cleanupAllStaging(context: Context, restorationId: String) {
        try {
            val stagingDir = getStagingDir(context, restorationId)
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting staging directory for $restorationId", e)
        }
    }

    fun getStagingDir(context: Context, restorationId: String): File {
        val base = context.getExternalFilesDir("restoration") ?: File(context.filesDir, "restoration")
        return File(base, "jobs/$restorationId").apply { mkdirs() }
    }

    private fun getAvailableStorageBytes(dir: File): Long {
        return try {
            val stat = StatFs(dir.absolutePath)
            val blocks = stat.availableBlocksLong
            val size = stat.blockSizeLong
            val space = if (blocks > 0L && size > 0L) blocks * size else dir.usableSpace
            if (space > 0L) space else Long.MAX_VALUE
        } catch (_: Exception) {
            dir.usableSpace.takeIf { it > 0L } ?: Long.MAX_VALUE
        }
    }

    companion object {
        private const val TAG = "VideoRestorationManager"
        val defaultInstance: VideoRestorationManager by lazy { VideoRestorationManager() }
    }
}
