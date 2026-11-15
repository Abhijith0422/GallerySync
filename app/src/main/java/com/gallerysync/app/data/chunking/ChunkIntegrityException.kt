package com.gallerysync.app.data.chunking

import java.io.IOException

/**
 * Exception thrown when a chunk's in-flight byte count or SHA-256 hash fails verification during upload.
 */
class ChunkIntegrityException(
    val chunkIndex: Int,
    val expectedSha256: String?,
    val actualSha256: String?,
    val expectedBytes: Long,
    val actualBytes: Long,
    message: String = "Chunk $chunkIndex integrity verification failed: expected $expectedBytes bytes (hash $expectedSha256), got $actualBytes bytes (hash $actualSha256)"
) : IOException(message)
