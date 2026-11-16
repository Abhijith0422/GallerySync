package com.gallerysync.app.data.restoration

import android.util.Log
import com.gallerysync.app.data.catalog.models.ManifestChunkDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

sealed class ReconstructionResult {
    data class Success(
        val outputFile: File,
        val totalBytes: Long,
        val fullSha256: String
    ) : ReconstructionResult()

    data class ChunkMissing(val chunkIndex: Int, val expectedFile: File) : ReconstructionResult()
    data class ChunkCorrupted(val chunkIndex: Int, val reason: String) : ReconstructionResult()
    data class ValidationFailed(val reason: String) : ReconstructionResult()
    data class VerificationFailed(
        val expectedSha256: String,
        val actualSha256: String,
        val expectedSize: Long,
        val actualSize: Long
    ) : ReconstructionResult()
    data class Cancelled(val reason: String = "Reconstruction cancelled") : ReconstructionResult()
}

/**
 * Reconstructs original media files from sequentially verified chunks.
 *
 * Guarantees:
 * - Strict descriptor validation (ordering, offsets, span, completeness).
 * - Exact byte-level sequential streaming with 64 KiB bounded memory.
 * - Real-time full-file SHA-256 hashing during reconstruction.
 * - Atomic promotion (.part -> final) only after full byte count and SHA-256 match.
 * - Cooperative cancellation support without leaking partial staging files.
 */
open class VideoReconstructionEngine {

    suspend fun reconstruct(
        chunkFiles: Map<Int, File>,
        chunkDescriptors: List<ManifestChunkDescriptor>,
        expectedTotalSize: Long,
        expectedFullSha256: String,
        outputFile: File,
        isCancelled: () -> Boolean = { false },
        onProgress: ((bytesWritten: Long, totalExpectedBytes: Long) -> Unit)? = null
    ): ReconstructionResult = withContext(Dispatchers.IO) {
        if (isCancelled()) {
            return@withContext ReconstructionResult.Cancelled()
        }

        if (chunkDescriptors.isEmpty()) {
            return@withContext ReconstructionResult.ValidationFailed("Chunk descriptors list is empty")
        }
        if (expectedTotalSize <= 0L) {
            return@withContext ReconstructionResult.ValidationFailed("Expected total size must be positive: $expectedTotalSize")
        }
        if (expectedFullSha256.isBlank()) {
            return@withContext ReconstructionResult.ValidationFailed("Expected full SHA-256 cannot be blank")
        }

        // 1. Sort descriptors and validate contiguity and completeness
        val sortedDescriptors = chunkDescriptors.sortedBy { it.index }
        var expectedOffset = 0L

        for (i in sortedDescriptors.indices) {
            val desc = sortedDescriptors[i]
            if (desc.index != i) {
                return@withContext ReconstructionResult.ValidationFailed(
                    "Non-contiguous chunk index at position $i: found ${desc.index}"
                )
            }
            if (desc.startOffset != expectedOffset) {
                return@withContext ReconstructionResult.ValidationFailed(
                    "Offset mismatch for chunk $i: expected start $expectedOffset, found ${desc.startOffset}"
                )
            }
            if (desc.endOffsetExclusive <= desc.startOffset) {
                return@withContext ReconstructionResult.ValidationFailed(
                    "Invalid offset bounds for chunk $i: start=${desc.startOffset}, end=${desc.endOffsetExclusive}"
                )
            }
            val span = desc.endOffsetExclusive - desc.startOffset
            if (desc.sizeBytes != span) {
                return@withContext ReconstructionResult.ValidationFailed(
                    "Size mismatch for chunk $i: descriptor size=${desc.sizeBytes}, span=$span"
                )
            }
            expectedOffset = desc.endOffsetExclusive
        }

        if (expectedOffset != expectedTotalSize) {
            return@withContext ReconstructionResult.ValidationFailed(
                "Total chunk span ($expectedOffset) does not equal expected total file size ($expectedTotalSize)"
            )
        }

        // 2. Verify all chunk files exist and match descriptor sizes
        for (desc in sortedDescriptors) {
            val file = chunkFiles[desc.index]
            if (file == null || !file.exists()) {
                return@withContext ReconstructionResult.ChunkMissing(
                    desc.index,
                    file ?: File("chunk_${desc.index}")
                )
            }
            val actualLen = file.length()
            if (actualLen != desc.sizeBytes) {
                return@withContext ReconstructionResult.ChunkCorrupted(
                    desc.index,
                    "Length mismatch for chunk ${desc.index}: expected ${desc.sizeBytes}, found $actualLen"
                )
            }
        }

        // 3. Prepare output file
        outputFile.parentFile?.mkdirs()
        val partFile = File(outputFile.parentFile, "${outputFile.name}.part")
        if (partFile.exists()) {
            partFile.delete()
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(RestorationConfiguration.BUFFER_SIZE_BYTES)
        var totalBytesWritten = 0L

        try {
            FileOutputStream(partFile).use { outStream ->
                for (desc in sortedDescriptors) {
                    val chunkFile = chunkFiles[desc.index]!!
                    FileInputStream(chunkFile).use { inStream ->
                        while (true) {
                            if (isCancelled()) {
                                Log.i(TAG, "Reconstruction cancelled cooperatively")
                                partFile.delete()
                                return@withContext ReconstructionResult.Cancelled()
                            }

                            val count = inStream.read(buffer)
                            if (count == -1) break

                            outStream.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            totalBytesWritten += count
                            onProgress?.invoke(totalBytesWritten, expectedTotalSize)
                        }
                    }
                }
                outStream.flush()
            }

            // 4. Verify byte count
            if (totalBytesWritten != expectedTotalSize) {
                val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
                Log.e(TAG, "Reconstruction size mismatch: wrote $totalBytesWritten, expected $expectedTotalSize")
                partFile.delete()
                return@withContext ReconstructionResult.VerificationFailed(
                    expectedSha256 = expectedFullSha256,
                    actualSha256 = computedHash,
                    expectedSize = expectedTotalSize,
                    actualSize = totalBytesWritten
                )
            }

            // 5. Verify full-file SHA-256
            val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!computedSha256.equals(expectedFullSha256, ignoreCase = true)) {
                Log.e(TAG, "Reconstruction SHA-256 mismatch: computed $computedSha256, expected $expectedFullSha256")
                partFile.delete()
                return@withContext ReconstructionResult.VerificationFailed(
                    expectedSha256 = expectedFullSha256,
                    actualSha256 = computedSha256,
                    expectedSize = expectedTotalSize,
                    actualSize = totalBytesWritten
                )
            }

            // 6. Promote to final output file
            if (outputFile.exists()) {
                outputFile.delete()
            }
            val renamed = partFile.renameTo(outputFile)
            if (!renamed) {
                partFile.copyTo(outputFile, overwrite = true)
                partFile.delete()
            }

            Log.i(TAG, "Reconstruction complete: $totalBytesWritten bytes, SHA-256=$computedSha256")
            ReconstructionResult.Success(
                outputFile = outputFile,
                totalBytes = totalBytesWritten,
                fullSha256 = computedSha256
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during video reconstruction", e)
            partFile.delete()
            ReconstructionResult.ValidationFailed("Reconstruction failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "VideoReconstructionEngine"
        val defaultInstance: VideoReconstructionEngine by lazy { VideoReconstructionEngine() }
    }
}
