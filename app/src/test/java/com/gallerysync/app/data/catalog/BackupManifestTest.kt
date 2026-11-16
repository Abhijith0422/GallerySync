package com.gallerysync.app.data.catalog

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.gallerysync.app.data.catalog.models.ApplicationInfo
import com.gallerysync.app.data.catalog.models.BackupManifest
import com.gallerysync.app.data.catalog.models.BotIdentity
import com.gallerysync.app.data.catalog.models.DestinationInfo
import com.gallerysync.app.data.catalog.models.ManifestChunkConfig
import com.gallerysync.app.data.catalog.models.ManifestChunkDescriptor
import com.gallerysync.app.data.catalog.models.MediaDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestTest {

    private val mapper = ObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private fun createValidManifest(): BackupManifest {
        val totalSize = 32L * 1024 * 1024 // 32 MiB
        val chunkSize = 16L * 1024 * 1024 // 16 MiB
        val chunk0 = ManifestChunkDescriptor(
            index = 0,
            startOffset = 0L,
            endOffsetExclusive = 16L * 1024 * 1024,
            sizeBytes = 16L * 1024 * 1024,
            sha256 = "chunk0_sha256_hash_abcdef1234567890abcdef1234567890abcdef1234567890",
            telegramFileId = "BAACAgIAAxkBAAIB...",
            telegramMessageId = 1001L
        )
        val chunk1 = ManifestChunkDescriptor(
            index = 1,
            startOffset = 16L * 1024 * 1024,
            endOffsetExclusive = 32L * 1024 * 1024,
            sizeBytes = 16L * 1024 * 1024,
            sha256 = "chunk1_sha256_hash_abcdef1234567890abcdef1234567890abcdef1234567890",
            telegramFileId = "BAACAgIAAxkBAAIC...",
            telegramMessageId = 1002L
        )
        return BackupManifest(
            format = BackupManifest.FORMAT_IDENTIFIER,
            version = BackupManifest.CURRENT_VERSION,
            backupId = "test_backup_001",
            createdAt = 1700000000000L,
            application = ApplicationInfo(name = "GallerySync", packageName = "com.gallerysync.app"),
            bot = BotIdentity(id = 123456789L, username = "TestBot"),
            destination = DestinationInfo(chatId = -1001234567890L, topicId = 42L),
            media = MediaDescriptor(
                originalName = "sample_video.mp4",
                mimeType = "video/mp4",
                sizeBytes = totalSize,
                width = 1920,
                height = 1080,
                durationMs = 60000L,
                sha256 = "full_video_sha256_hash_1234567890abcdef1234567890abcdef1234567890"
            ),
            chunkConfiguration = ManifestChunkConfig(
                chunkSizeBytes = chunkSize,
                totalChunks = 2
            ),
            chunks = listOf(chunk0, chunk1)
        )
    }

    @Test
    fun testValidManifest_passesValidationAndSerializesRoundtrip() {
        val manifest = createValidManifest()
        manifest.validate() // should not throw

        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest)
        assertNotNull(json)
        assertTrue(json.contains("gallerysync_manifest"))
        assertTrue(json.contains("sample_video.mp4"))

        val deserialized = mapper.readValue(json, BackupManifest::class.java)
        assertEquals(manifest.backupId, deserialized.backupId)
        assertEquals(manifest.media.sizeBytes, deserialized.media.sizeBytes)
        assertEquals(manifest.chunks.size, deserialized.chunks.size)
        assertEquals(manifest.chunks[0].telegramFileId, deserialized.chunks[0].telegramFileId)
        assertEquals(manifest.chunks[1].telegramMessageId, deserialized.chunks[1].telegramMessageId)
        deserialized.validate()
    }

    @Test(expected = IllegalStateException::class)
    fun testManifest_withGapInChunkOffsets_failsValidation() {
        val valid = createValidManifest()
        // Introduce a gap: chunk1 starts at 20MB instead of 16MB
        val badChunks = listOf(
            valid.chunks[0],
            valid.chunks[1].copy(startOffset = 20L * 1024 * 1024)
        )
        val badManifest = valid.copy(chunks = badChunks)
        badManifest.validate()
    }

    @Test(expected = IllegalStateException::class)
    fun testManifest_withChunkSumNotMatchingMediaSize_failsValidation() {
        val valid = createValidManifest()
        // Total media is 32MB, but chunk1 ends at 30MB
        val badChunks = listOf(
            valid.chunks[0],
            valid.chunks[1].copy(
                endOffsetExclusive = 30L * 1024 * 1024,
                sizeBytes = 14L * 1024 * 1024
            )
        )
        val badManifest = valid.copy(chunks = badChunks)
        badManifest.validate()
    }

    @Test(expected = IllegalStateException::class)
    fun testManifest_withEmptyTelegramFileId_failsValidation() {
        val valid = createValidManifest()
        val badChunks = listOf(
            valid.chunks[0].copy(telegramFileId = ""),
            valid.chunks[1]
        )
        val badManifest = valid.copy(chunks = badChunks)
        badManifest.validate()
    }

    @Test(expected = IllegalStateException::class)
    fun testManifest_withOutOfOrderChunkIndex_failsValidation() {
        val valid = createValidManifest()
        // Index is 5 instead of 1
        val badChunks = listOf(
            valid.chunks[0],
            valid.chunks[1].copy(index = 5)
        )
        val badManifest = valid.copy(chunks = badChunks)
        badManifest.validate()
    }

    @Test(expected = IllegalStateException::class)
    fun testManifest_withMissingBotId_failsValidation() {
        val valid = createValidManifest()
        val badManifest = valid.copy(bot = valid.bot.copy(id = 0L))
        badManifest.validate()
    }

    @Test(expected = IllegalStateException::class)
    fun testManifest_withZeroChatId_failsValidation() {
        val valid = createValidManifest()
        val badManifest = valid.copy(destination = valid.destination.copy(chatId = 0L))
        badManifest.validate()
    }

    @Test(expected = IllegalStateException::class)
    fun testManifest_withChunkCountMismatch_failsValidation() {
        val valid = createValidManifest()
        // totalChunks declared as 3, but list has 2
        val badManifest = valid.copy(
            chunkConfiguration = valid.chunkConfiguration.copy(totalChunks = 3)
        )
        badManifest.validate()
    }
}
