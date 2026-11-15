package com.gallerysync.app.data.chunking

/**
 * Metadata descriptor representing an individual slice of a file.
 *
 * Invariants:
 * - [startOffset] is INCLUSIVE.
 * - [endOffset] is EXCLUSIVE.
 * - [chunkSize] = [endOffset] - [startOffset].
 * - For a file of size N and chunk size C:
 *     totalChunks = ceil(N / C)
 *     For index i:
 *     startOffset = i * C
 *     endOffset = min(startOffset + C, N)
 */
data class ChunkDescriptor(
    val chunkIndex: Int,
    val startOffset: Long,
    val endOffset: Long,
    val chunkSize: Long,
    val chunkSha256: String? = null
) {
    init {
        require(chunkIndex >= 0) { "chunkIndex must be non-negative: $chunkIndex" }
        require(startOffset >= 0) { "startOffset must be non-negative: $startOffset" }
        require(endOffset >= startOffset) { "endOffset ($endOffset) must be >= startOffset ($startOffset)" }
        require(chunkSize == endOffset - startOffset) {
            "chunkSize ($chunkSize) must equal endOffset - startOffset (${endOffset - startOffset})"
        }
    }

    companion object {
        /**
         * Generates deterministic chunk descriptors for a given file size.
         *
         * @param fileSize Total file size in bytes (must be > 0).
         * @param config Chunk configuration defining chunk size.
         * @return List of descriptors spanning the entire file from 0 to fileSize with zero gaps or overlaps.
         */
        fun calculateDescriptors(
            fileSize: Long,
            config: ChunkConfiguration = ChunkConfiguration.DEFAULT
        ): List<ChunkDescriptor> {
            require(fileSize > 0) { "fileSize must be positive: $fileSize" }
            val c = config.chunkSize

            // Overflow-safe totalChunks calculation: 1L + (fileSize - 1L) / c
            val totalChunksLong = 1L + (fileSize - 1L) / c
            require(totalChunksLong <= Int.MAX_VALUE) { "Chunk count exceeds supported Int range: $totalChunksLong" }
            val totalChunks = totalChunksLong.toInt()
            return (0 until totalChunks).map { i ->
                val start = i.toLong() * c
                val end = minOf(start + c, fileSize)
                ChunkDescriptor(
                    chunkIndex = i,
                    startOffset = start,
                    endOffset = end,
                    chunkSize = end - start
                )
            }
        }
    }
}
