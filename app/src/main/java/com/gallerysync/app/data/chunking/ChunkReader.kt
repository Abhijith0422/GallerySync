package com.gallerysync.app.data.chunking

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.coroutines.coroutineContext

/**
 * Thrown when the source ends prematurely before the expected chunk size is read.
 */
class PrematureEofException(
    val expectedBytes: Long,
    val actualBytes: Long,
    val chunkIndex: Int
) : EOFException("Premature EOF in chunk $chunkIndex: expected $expectedBytes bytes, but read $actualBytes bytes")

/**
 * Dual-path chunk reader supporting seekable channels (Path A) and non-seekable fallback (Path B).
 *
 * Guarantees:
 * - Reads exactly [ChunkDescriptor.chunkSize] bytes.
 * - Detects premature EOF.
 * - Bounded buffer allocation (64 KB).
 * - Safe temporary file generation with guaranteed cleanup on failure or cancellation.
 */
object ChunkReader {
    private const val TAG = "ChunkReader"
    const val BUFFER_SIZE = 64 * 1024 // 64 KB bounded buffer

    /**
     * Reads a chunk from [source] according to [descriptor] and writes it to [outputStream].
     *
     * @return Number of bytes written to [outputStream].
     */
    suspend fun readChunk(
        source: ChunkSource,
        descriptor: ChunkDescriptor,
        outputStream: OutputStream
    ): Long = withContext(Dispatchers.IO) {
        val channel = source.openSeekableChannel()
        if (channel != null) {
            readSeekable(channel, descriptor, outputStream)
        } else {
            readNonSeekable(source, descriptor, outputStream)
        }
    }

    /**
     * Path A: Seekable Fast Path.
     * Positions channel directly at descriptor.startOffset and reads descriptor.chunkSize bytes.
     */
    private suspend fun readSeekable(
        channel: FileChannel,
        descriptor: ChunkDescriptor,
        outputStream: OutputStream
    ): Long {
        channel.position(descriptor.startOffset)
        val byteBuffer = ByteBuffer.allocate(BUFFER_SIZE)
        var totalRead = 0L

        while (totalRead < descriptor.chunkSize) {
            coroutineContext.ensureActive()

            val toRead = minOf(BUFFER_SIZE.toLong(), descriptor.chunkSize - totalRead).toInt()
            byteBuffer.clear()
            byteBuffer.limit(toRead)

            val read = channel.read(byteBuffer)
            if (read == -1) {
                throw PrematureEofException(descriptor.chunkSize, totalRead, descriptor.chunkIndex)
            }

            byteBuffer.flip()
            outputStream.write(byteBuffer.array(), byteBuffer.arrayOffset() + byteBuffer.position(), read)
            totalRead += read
        }

        outputStream.flush()
        return totalRead
    }

    /**
     * Path B: Non-Seekable Fallback.
     * Sequentially reads from openStream(), safely skips startOffset, and reads descriptor.chunkSize bytes.
     */
    private suspend fun readNonSeekable(
        source: ChunkSource,
        descriptor: ChunkDescriptor,
        outputStream: OutputStream
    ): Long {
        source.openStream().use { stream ->
            // Safely skip to startOffset
            safeSkip(stream, descriptor.startOffset, descriptor.chunkIndex)

            val buffer = ByteArray(BUFFER_SIZE)
            var totalRead = 0L

            while (totalRead < descriptor.chunkSize) {
                coroutineContext.ensureActive()

                val toRead = minOf(BUFFER_SIZE.toLong(), descriptor.chunkSize - totalRead).toInt()
                val read = stream.read(buffer, 0, toRead)
                if (read == -1) {
                    throw PrematureEofException(descriptor.chunkSize, totalRead, descriptor.chunkIndex)
                }

                outputStream.write(buffer, 0, read)
                totalRead += read
            }

            outputStream.flush()
            return totalRead
        }
    }

    /**
     * Skips [bytesToSkip] bytes safely by reading and discarding, avoiding unreliable InputStream.skip.
     */
    private fun safeSkip(stream: InputStream, bytesToSkip: Long, chunkIndex: Int) {
        var remaining = bytesToSkip
        val discard = ByteArray(minOf(BUFFER_SIZE.toLong(), remaining).toInt())
        while (remaining > 0) {
            val toRead = minOf(discard.size.toLong(), remaining).toInt()
            val read = stream.read(discard, 0, toRead)
            if (read == -1) {
                throw PrematureEofException(bytesToSkip, bytesToSkip - remaining, chunkIndex)
            }
            remaining -= read
        }
    }

    /**
     * Creates a bounded temporary file for a chunk in [cacheDir].
     * Max transient size under default config is 16 MiB.
     * Guaranteed cleanup if exception or cancellation occurs before completion.
     */
    suspend fun createChunkTempFile(
        source: ChunkSource,
        descriptor: ChunkDescriptor,
        cacheDir: File,
        backupId: String
    ): File = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, "chunks").apply { mkdirs() }
        val tempFile = File(dir, "temp_${backupId}_chunk_${descriptor.chunkIndex}.bin")
        var completedSuccessfully = false

        try {
            FileOutputStream(tempFile).use { fos ->
                readChunk(source, descriptor, fos)
            }
            completedSuccessfully = true
            tempFile
        } finally {
            if (!completedSuccessfully && tempFile.exists()) {
                Log.d(TAG, "Cleaning incomplete temp file: ${tempFile.name}")
                tempFile.delete()
            }
        }
    }

    /**
     * Safely deletes a temporary chunk file.
     */
    fun deleteTempFile(file: File?): Boolean {
        return try {
            file?.delete() ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete temp file ${file?.name}: ${e.message}")
            false
        }
    }

    /**
     * Cleans all temporary chunk files belonging to a specific backup ID.
     */
    fun cleanTempFilesForBackup(cacheDir: File, backupId: String): Int {
        val dir = File(cacheDir, "chunks")
        if (!dir.exists()) return 0
        val files = dir.listFiles { _, name -> name.startsWith("temp_${backupId}_") } ?: return 0
        var count = 0
        for (f in files) {
            if (f.delete()) count++
        }
        return count
    }
}
