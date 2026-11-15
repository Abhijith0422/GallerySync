package com.gallerysync.app.data.chunking

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Result produced by the dual-level streaming hasher.
 */
sealed class HashingResult {
    data class Success(
        val fileSha256: String,
        val chunkDescriptors: List<ChunkDescriptor>,
        val totalBytesRead: Long
    ) : HashingResult()

    data class EmptySource(val message: String) : HashingResult()
    data class SourceModified(val details: String) : HashingResult()
    data class Failure(val error: Throwable) : HashingResult()
}

/**
 * High-performance streaming SHA-256 calculator.
 *
 * Guarantees:
 * 1. Single sequential pass: computes full-file SHA-256 and all per-chunk SHA-256 hashes concurrently.
 * 2. Strictly bounded memory: allocates only 64 KB buffers, never loading the media file into memory.
 * 3. Never combines chunk hashes to form the file hash: the full-file digest receives every original byte.
 * 4. Coroutine cancellation safe: checks isActive on each buffer iteration and guarantees stream closure.
 * 5. Pre/post source modification verification: detects if file changed during hashing pass.
 */
object StreamingChunkHasher {
    private const val TAG = "StreamingChunkHasher"
    const val BUFFER_SIZE = 64 * 1024 // 64 KB bounded buffer

    suspend fun hash(
        source: ChunkSource,
        config: ChunkConfiguration = ChunkConfiguration.DEFAULT,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): HashingResult = withContext(Dispatchers.IO) {
        val initialMetadata = try {
            source.getMetadata()
        } catch (e: Exception) {
            return@withContext HashingResult.Failure(e)
        }

        if (initialMetadata.fileSize <= 0L) {
            return@withContext HashingResult.EmptySource("Cannot hash empty or zero-byte source: ${source.uri}")
        }

        val fileDigest = MessageDigest.getInstance("SHA-256")
        var currentChunkDigest = MessageDigest.getInstance("SHA-256")

        val chunkDescriptors = mutableListOf<ChunkDescriptor>()
        val buffer = ByteArray(BUFFER_SIZE)

        var currentChunkIndex = 0
        var bytesInCurrentChunk = 0L
        var totalBytesRead = 0L

        try {
            source.openStream().use { stream ->
                while (true) {
                    coroutineContext.ensureActive()

                    val bytesNeededInChunk = config.chunkSize - bytesInCurrentChunk
                    val toRead = minOf(buffer.size.toLong(), bytesNeededInChunk).toInt()
                    val read = stream.read(buffer, 0, toRead)

                    if (read == -1) break

                    fileDigest.update(buffer, 0, read)
                    currentChunkDigest.update(buffer, 0, read)
                    bytesInCurrentChunk += read
                    totalBytesRead += read

                    onProgress?.invoke(totalBytesRead, initialMetadata.fileSize)

                    if (bytesInCurrentChunk == config.chunkSize) {
                        val chunkHash = currentChunkDigest.digest().joinToString("") { "%02x".format(it) }
                        val startOffset = currentChunkIndex.toLong() * config.chunkSize
                        val endOffset = startOffset + config.chunkSize
                        chunkDescriptors.add(
                            ChunkDescriptor(
                                chunkIndex = currentChunkIndex,
                                startOffset = startOffset,
                                endOffset = endOffset,
                                chunkSize = config.chunkSize,
                                chunkSha256 = chunkHash
                            )
                        )
                        currentChunkIndex++
                        bytesInCurrentChunk = 0L
                        currentChunkDigest = MessageDigest.getInstance("SHA-256")
                    }
                }
            }

            if (totalBytesRead == 0L) {
                return@withContext HashingResult.EmptySource("No bytes were read from source: ${source.uri}")
            }

            // Final partial chunk if remainder exists
            if (bytesInCurrentChunk > 0L) {
                val chunkHash = currentChunkDigest.digest().joinToString("") { "%02x".format(it) }
                val startOffset = currentChunkIndex.toLong() * config.chunkSize
                val endOffset = startOffset + bytesInCurrentChunk
                chunkDescriptors.add(
                    ChunkDescriptor(
                        chunkIndex = currentChunkIndex,
                        startOffset = startOffset,
                        endOffset = endOffset,
                        chunkSize = bytesInCurrentChunk,
                        chunkSha256 = chunkHash
                    )
                )
            }

            val fileSha256 = fileDigest.digest().joinToString("") { "%02x".format(it) }

            // Post-hashing validation: check if file was modified while reading
            val postMetadata = source.getMetadata()
            val change = SourceModificationDetector.detectChanges(initialMetadata, postMetadata)
            if (change is SourceModificationDetector.ChangeResult.Modified) {
                Log.w(TAG, "Source modified during hashing pass: ${change.message}")
                return@withContext HashingResult.SourceModified(change.message)
            }

            Log.i(
                TAG,
                "Successfully hashed source: ${chunkDescriptors.size} chunks, total $totalBytesRead bytes, sha256=$fileSha256"
            )

            HashingResult.Success(
                fileSha256 = fileSha256,
                chunkDescriptors = chunkDescriptors,
                totalBytesRead = totalBytesRead
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
            Log.e(TAG, "Hashing failed for ${source.uri}: ${e.message}", e)
            HashingResult.Failure(e)
        }
    }
}
