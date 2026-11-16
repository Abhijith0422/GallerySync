package com.gallerysync.app.data.catalog

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.gallerysync.app.data.catalog.models.CatalogMediaRecord
import com.gallerysync.app.data.catalog.models.CatalogShard
import com.gallerysync.app.data.catalog.models.ManifestReference
import com.gallerysync.app.data.catalog.models.MasterIndex
import com.gallerysync.app.data.catalog.models.ShardReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterIndexTest {

    private val mapper = ObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private fun createSampleMediaRecord(index: Int): CatalogMediaRecord {
        return CatalogMediaRecord(
            mediaSha256 = "hash_$index" + "0".repeat(50),
            displayName = "photo_$index.jpg",
            sizeBytes = 1024L * 1024L,
            mediaType = "IMAGE",
            telegramFileId = "file_id_$index",
            telegramMessageId = 2000L + index,
            backupTimestamp = 1700000000000L + index,
            backupStatus = "DONE",
            localId = "local_$index"
        )
    }

    @Test
    fun testValidMasterIndex_passesValidationAndSerializesRoundtrip() {
        val manifestRef = ManifestReference(
            backupId = "backup_v1",
            fileId = "manifest_file_1",
            messageId = 5001L,
            fileName = "gallerysync_manifest_backup_v1.json",
            sizeBytes = 2048L,
            sha256 = "manifest_sha256_abcdef",
            mediaSha256 = "video_sha256_123456",
            displayName = "vacation.mp4"
        )

        val activeRecords = (1..50).map { createSampleMediaRecord(it) }

        val masterIndex = MasterIndex(
            format = MasterIndex.FORMAT_IDENTIFIER,
            version = MasterIndex.CURRENT_VERSION,
            generation = 1L,
            updatedAt = System.currentTimeMillis(),
            botId = 123456789L,
            channelId = -1001234567890L,
            manifests = listOf(manifestRef),
            shards = emptyList(),
            activeMediaRecords = activeRecords
        )

        masterIndex.validate()
        assertEquals(50, masterIndex.totalMediaCount)
        assertEquals(1, masterIndex.totalManifestCount)

        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(masterIndex)
        assertNotNull(json)
        assertTrue(json.contains("gallerysync_master_index"))
        assertTrue(json.contains("vacation.mp4"))

        val deserialized = mapper.readValue(json, MasterIndex::class.java)
        deserialized.validate()
        assertEquals(masterIndex.generation, deserialized.generation)
        assertEquals(masterIndex.botId, deserialized.botId)
        assertEquals(masterIndex.channelId, deserialized.channelId)
        assertEquals(1, deserialized.manifests.size)
        assertEquals(50, deserialized.activeMediaRecords.size)
    }

    @Test
    fun testShardingThreshold_shardsOldest1000RecordsWhenExceeding1000() {
        // Create 1,500 records
        val allRecords = (1..1500).map { createSampleMediaRecord(it) }

        // When exceeding CatalogShard.MAX_SHARD_RECORDS (1,000):
        val recordsToShard = allRecords.take(CatalogShard.MAX_SHARD_RECORDS)
        val remainingActive = allRecords.drop(CatalogShard.MAX_SHARD_RECORDS)

        assertEquals(1000, recordsToShard.size)
        assertEquals(500, remainingActive.size)

        val shard = CatalogShard(
            format = CatalogShard.FORMAT_IDENTIFIER,
            version = CatalogShard.CURRENT_VERSION,
            shardId = "shard_1_test",
            createdAt = System.currentTimeMillis(),
            botId = 123456789L,
            channelId = -1001234567890L,
            itemCount = recordsToShard.size,
            records = recordsToShard
        )

        shard.validate() // should not throw

        val shardJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(shard)
        val deserializedShard = mapper.readValue(shardJson, CatalogShard::class.java)
        deserializedShard.validate()
        assertEquals(1000, deserializedShard.records.size)

        val shardRef = ShardReference(
            shardId = shard.shardId,
            fileId = "shard_doc_file_id",
            messageId = 8888L,
            fileName = "gallerysync_shard_${shard.shardId}.json",
            sizeBytes = shardJson.toByteArray().size.toLong(),
            sha256 = "shard_hash_abc",
            itemCount = shard.itemCount
        )

        val masterIndex = MasterIndex(
            format = MasterIndex.FORMAT_IDENTIFIER,
            version = MasterIndex.CURRENT_VERSION,
            generation = 2L,
            updatedAt = System.currentTimeMillis(),
            botId = 123456789L,
            channelId = -1001234567890L,
            manifests = emptyList(),
            shards = listOf(shardRef),
            activeMediaRecords = remainingActive
        )

        masterIndex.validate()
        // Total media count should reflect both active records and shard items: 500 + 1000 = 1500
        assertEquals(1500, masterIndex.totalMediaCount)
        assertEquals(500, masterIndex.activeMediaRecords.size)
        assertEquals(1, masterIndex.shards.size)
    }

    @Test(expected = IllegalStateException::class)
    fun testMasterIndex_withNegativeOrZeroGeneration_failsValidation() {
        val masterIndex = MasterIndex(
            generation = 0L,
            botId = 123L,
            channelId = -100L
        )
        masterIndex.validate()
    }

    @Test(expected = IllegalStateException::class)
    fun testMasterIndex_withMissingBotId_failsValidation() {
        val masterIndex = MasterIndex(
            generation = 1L,
            botId = 0L,
            channelId = -100L
        )
        masterIndex.validate()
    }

    @Test(expected = IllegalStateException::class)
    fun testShard_withEmptyRecords_failsValidation() {
        val emptyShard = CatalogShard(
            shardId = "shard_empty",
            botId = 123L,
            channelId = -100L,
            records = emptyList()
        )
        emptyShard.validate()
    }
}
