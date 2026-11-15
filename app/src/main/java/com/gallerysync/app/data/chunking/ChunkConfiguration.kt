package com.gallerysync.app.data.chunking

/**
 * Configuration for the streaming chunk engine.
 *
 * Enforces:
 * - Default 16 MiB (16,777,216 bytes) chunk size.
 * - Minimum chunk size of 1 MiB (1,048,576 bytes) to prevent overhead.
 * - Absolute Telegram-compatible maximum chunk size of 20,000,000 bytes (Telegram getFile download limit).
 * - Strict use of [Long] for byte sizes and offsets to prevent integer overflow.
 */
data class ChunkConfiguration(
    val chunkSize: Long = DEFAULT_CHUNK_SIZE
) {
    init {
        require(chunkSize in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) {
            "Chunk size must be between $MIN_CHUNK_SIZE and $MAX_CHUNK_SIZE bytes, but was $chunkSize"
        }
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 16 * 1024 * 1024L // 16 MiB = 16,777,216 bytes
        const val MIN_CHUNK_SIZE = 1 * 1024 * 1024L      // 1 MiB = 1,048,576 bytes
        const val MAX_CHUNK_SIZE = 20_000_000L           // 20 MB = 20,000,000 bytes (Telegram getFile limit)

        val DEFAULT = ChunkConfiguration(DEFAULT_CHUNK_SIZE)
    }
}
