package com.gallerysync.app.data.chunking

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Streaming OkHttp [RequestBody] that reads an exact chunk slice from a [ChunkSource]
 * and verifies byte count and SHA-256 integrity in-flight during [writeTo].
 *
 * Guarantees:
 * - Constant bounded memory consumption: 64 KB streaming buffer, zero full-chunk ByteArray allocations.
 * - Reproducible: Can be invoked multiple times by OkHttp (retries, logging, redirects).
 * - Exact byte count enforcement: Throws [ChunkIntegrityException] if actual bytes != descriptor.chunkSize.
 * - Checksum verification: Throws [ChunkIntegrityException] if computed SHA-256 != expectedSha256.
 * - Safe stream/channel lifecycle: Closes opened streams/channels in finally blocks.
 */
class ChunkedRequestBody(
    val source: ChunkSource,
    val descriptor: ChunkDescriptor,
    val expectedSha256: String? = descriptor.chunkSha256,
    private val contentType: MediaType = "application/octet-stream".toMediaType()
) : RequestBody() {

    @Volatile
    var lastCalculatedSha256: String? = null
        private set

    @Volatile
    var isVerified: Boolean = false
        private set

    override fun contentType(): MediaType = contentType

    override fun contentLength(): Long = descriptor.chunkSize

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesWritten = 0L
        isVerified = false
        lastCalculatedSha256 = null

        val outputStream = sink.outputStream()

        // Path A: Seekable FileChannel fast path
        val seekableChannel = runCatching {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                source.openSeekableChannel()
            }
        }.getOrNull()

        if (seekableChannel != null) {
            try {
                seekableChannel.position(descriptor.startOffset)
                val byteBuffer = ByteBuffer.allocate(BUFFER_SIZE)

                while (bytesWritten < descriptor.chunkSize) {
                    val toRead = minOf(BUFFER_SIZE.toLong(), descriptor.chunkSize - bytesWritten).toInt()
                    byteBuffer.clear()
                    byteBuffer.limit(toRead)

                    val read = seekableChannel.read(byteBuffer)
                    if (read == -1) {
                        throw EOFException("Premature EOF in chunk ${descriptor.chunkIndex}: expected ${descriptor.chunkSize} bytes, got $bytesWritten")
                    }

                    byteBuffer.flip()
                    val offset = byteBuffer.arrayOffset() + byteBuffer.position()
                    digest.update(byteBuffer.array(), offset, read)
                    outputStream.write(byteBuffer.array(), offset, read)
                    bytesWritten += read
                }
                outputStream.flush()
            } finally {
                source.close()
            }
        } else {
            // Path B: Non-seekable stream fallback
            val stream: InputStream = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                source.openStream()
            }
            try {
                // Safely skip to startOffset
                var remainingSkip = descriptor.startOffset
                val discard = ByteArray(minOf(BUFFER_SIZE.toLong(), remainingSkip).toInt())
                while (remainingSkip > 0) {
                    val toDiscard = minOf(discard.size.toLong(), remainingSkip).toInt()
                    val skipped = stream.read(discard, 0, toDiscard)
                    if (skipped == -1) {
                        throw EOFException("Premature EOF while skipping to offset ${descriptor.startOffset} in chunk ${descriptor.chunkIndex}")
                    }
                    remainingSkip -= skipped
                }

                val buffer = ByteArray(BUFFER_SIZE)
                while (bytesWritten < descriptor.chunkSize) {
                    val toRead = minOf(BUFFER_SIZE.toLong(), descriptor.chunkSize - bytesWritten).toInt()
                    val read = stream.read(buffer, 0, toRead)
                    if (read == -1) {
                        throw EOFException("Premature EOF in chunk ${descriptor.chunkIndex}: expected ${descriptor.chunkSize} bytes, got $bytesWritten")
                    }

                    digest.update(buffer, 0, read)
                    outputStream.write(buffer, 0, read)
                    bytesWritten += read
                }
                outputStream.flush()
            } finally {
                try { stream.close() } catch (_: Exception) {}
                source.close()
            }
        }

        // 1. Verify exact byte count
        if (bytesWritten != descriptor.chunkSize) {
            throw ChunkIntegrityException(
                chunkIndex = descriptor.chunkIndex,
                expectedSha256 = expectedSha256,
                actualSha256 = null,
                expectedBytes = descriptor.chunkSize,
                actualBytes = bytesWritten,
                message = "Chunk ${descriptor.chunkIndex} byte count mismatch: expected ${descriptor.chunkSize}, wrote $bytesWritten"
            )
        }

        // 2. Verify SHA-256 hash
        val calculatedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        lastCalculatedSha256 = calculatedSha256

        if (expectedSha256 != null && !calculatedSha256.equals(expectedSha256, ignoreCase = true)) {
            throw ChunkIntegrityException(
                chunkIndex = descriptor.chunkIndex,
                expectedSha256 = expectedSha256,
                actualSha256 = calculatedSha256,
                expectedBytes = descriptor.chunkSize,
                actualBytes = bytesWritten,
                message = "Chunk ${descriptor.chunkIndex} SHA-256 mismatch: expected $expectedSha256, got $calculatedSha256"
            )
        }

        isVerified = true
    }

    companion object {
        const val BUFFER_SIZE = 64 * 1024 // 64 KB bounded streaming buffer
    }
}
