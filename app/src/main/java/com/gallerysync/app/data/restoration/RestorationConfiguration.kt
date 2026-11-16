package com.gallerysync.app.data.restoration

object RestorationConfiguration {
    /** Bounded streaming buffer size (64 KiB) to avoid high memory pressure */
    const val BUFFER_SIZE_BYTES = 64 * 1024

    /** Hosted Telegram Bot API hard ceiling for getFile downloads */
    const val MAX_HOSTED_TELEGRAM_DOWNLOAD_BYTES = 20_000_000L

    /** App-specific external files subdirectory for verified chunk staging */
    const val CHUNK_STAGE_SUBDIR = "restoration_chunks"

    /** App-specific external files subdirectory for reconstructed video staging */
    const val RECONSTRUCT_STAGE_SUBDIR = "restoration_output"

    /** Network timeouts */
    const val CONNECT_TIMEOUT_SECONDS = 30L
    const val READ_TIMEOUT_SECONDS = 60L
    const val WRITE_TIMEOUT_SECONDS = 60L
}
