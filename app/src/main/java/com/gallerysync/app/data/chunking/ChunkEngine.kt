package com.gallerysync.app.data.chunking

import android.content.Context
import android.net.Uri
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.upload.ChunkedBackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Result of analyzing a media source before chunking.
 */
sealed class ChunkAnalysisResult {
    data class Ready(
        val metadata: SourceMetadata,
        val descriptors: List<ChunkDescriptor>,
        val isSeekable: Boolean
    ) : ChunkAnalysisResult()

    data class Invalid(val validation: SourceValidationResult) : ChunkAnalysisResult()
    data class Error(val error: Throwable) : ChunkAnalysisResult()
}

/**
 * Unified public facade for GallerySync's streaming chunk engine.
 *
 * Provides:
 * - Source inspection and boundary calculation.
 * - Single-pass complete-file and per-chunk streaming hashing.
 * - Dual-path chunk extraction (seekable channel or non-seekable fallback).
 * - Temporary chunk file management with strict bounds and automatic cleanup.
 * - Integrity verification of isolated chunks.
 * - Optional persistence to Room database v13 via [ChunkedBackupRepository].
 *
 * Completely independent of Telegram networking.
 */
object ChunkEngine {

    /**
     * Inspects a media source, validates readability, and computes deterministic chunk descriptors.
     */
    suspend fun analyze(
        source: ChunkSource,
        config: ChunkConfiguration = ChunkConfiguration.DEFAULT
    ): ChunkAnalysisResult = withContext(Dispatchers.IO) {
        try {
            when (val validation = source.validate()) {
                is SourceValidationResult.Valid -> {
                    val metadata = source.getMetadata()
                    val descriptors = ChunkDescriptor.calculateDescriptors(metadata.fileSize, config)
                    ChunkAnalysisResult.Ready(
                        metadata = metadata,
                        descriptors = descriptors,
                        isSeekable = source.isSeekable()
                    )
                }
                else -> ChunkAnalysisResult.Invalid(validation)
            }
        } catch (e: Exception) {
            ChunkAnalysisResult.Error(e)
        }
    }

    /**
     * Computes full-file SHA-256 and all individual chunk hashes in a single sequential pass.
     */
    suspend fun hash(
        source: ChunkSource,
        config: ChunkConfiguration = ChunkConfiguration.DEFAULT,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): HashingResult = StreamingChunkHasher.hash(source, config, onProgress)

    /**
     * Reads a specific chunk slice and writes it to [outputStream].
     */
    suspend fun readChunk(
        source: ChunkSource,
        descriptor: ChunkDescriptor,
        outputStream: OutputStream
    ): Long = ChunkReader.readChunk(source, descriptor, outputStream)

    /**
     * Creates a temporary disk file in [cacheDir] containing the bytes of [descriptor].
     */
    suspend fun createTempChunkFile(
        source: ChunkSource,
        descriptor: ChunkDescriptor,
        cacheDir: File,
        backupId: String
    ): File = ChunkReader.createChunkTempFile(source, descriptor, cacheDir, backupId)

    /**
     * Deletes a temporary chunk file.
     */
    fun cleanTempChunkFile(file: File?): Boolean = ChunkReader.deleteTempFile(file)

    /**
     * Cleans all temporary chunk files belonging to [backupId].
     */
    fun cleanTempFilesForBackup(cacheDir: File, backupId: String): Int =
        ChunkReader.cleanTempFilesForBackup(cacheDir, backupId)

    /**
     * Verifies the cryptographic integrity of a local chunk file against its expected SHA-256.
     */
    suspend fun verifyChunkIntegrity(
        chunkFile: File,
        expectedSha256: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (!chunkFile.exists()) return@withContext false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            FileInputStream(chunkFile).use { fis ->
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expectedSha256, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Helper to persist calculated hashing results directly to Room database v13.
     */
    suspend fun persistHashesToRepository(
        backupId: String,
        result: HashingResult.Success,
        database: WhDatabase = DbHolder.database
    ): Boolean {
        val chunkHashMap = result.chunkDescriptors.associate { it.chunkIndex to (it.chunkSha256 ?: "") }
        return ChunkedBackupRepository.updateChunkHashes(
            backupId = backupId,
            fileSha256 = result.fileSha256,
            chunkHashes = chunkHashMap,
            database = database
        )
    }

    /**
     * Convenience factory for creating a [ChunkSource] from a [Context] and [Uri].
     */
    fun createSource(context: Context, uri: Uri): ChunkSource = UriChunkSource(context, uri)

    /**
     * Convenience factory for creating a [ChunkSource] from a local [File].
     */
    fun createSource(file: File): ChunkSource = FileChunkSource(file)
}
