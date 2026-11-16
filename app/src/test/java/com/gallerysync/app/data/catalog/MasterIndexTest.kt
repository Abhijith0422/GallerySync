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

    @Test
    fun testManifestShard_validationAndSerializationRoundtrip() {
        val manifests = (1..100).map { i ->
            ManifestReference(
                backupId = "backup_$i",
                fileId = "file_id_$i",
                messageId = 1000L + i,
                fileName = "manifest_$i.json",
                sizeBytes = 2048L,
                sha256 = "sha256_$i" + "a".repeat(40),
                mediaSha256 = "media_sha256_$i" + "b".repeat(40),
                displayName = "video_$i.mp4"
            )
        }

        val shard = com.gallerysync.app.data.catalog.models.ManifestShard(
            format = com.gallerysync.app.data.catalog.models.ManifestShard.FORMAT_IDENTIFIER,
            version = com.gallerysync.app.data.catalog.models.ManifestShard.CURRENT_VERSION,
            shardId = "mshard_1",
            createdAt = System.currentTimeMillis(),
            botId = 123456789L,
            channelId = -1001234567890L,
            itemCount = manifests.size,
            manifests = manifests
        )
        shard.validate()

        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(shard)
        val deserialized = mapper.readValue(json, com.gallerysync.app.data.catalog.models.ManifestShard::class.java)
        deserialized.validate()
        assertEquals(100, deserialized.itemCount)
        assertEquals("backup_1", deserialized.manifests[0].backupId)
    }

    @Test
    fun testMasterIndexScalabilityBounds_documentSizesSafelyBelow20MB() {
        // 1. Root index with 1,000 active media records and 1,000 active manifests
        val activeMedia = (1..1000).map { createSampleMediaRecord(it) }
        val activeManifests = (1..1000).map { i ->
            ManifestReference(
                backupId = "backup_$i",
                fileId = "file_id_$i",
                messageId = 1000L + i,
                fileName = "manifest_$i.json",
                sizeBytes = 2048L,
                sha256 = "sha256_$i" + "a".repeat(40),
                mediaSha256 = "media_sha256_$i" + "b".repeat(40),
                displayName = "video_$i.mp4"
            )
        }

        // Shard references representing 50,000 historical media items (50 shards)
        val mediaShards = (1..50).map { s ->
            ShardReference(
                shardId = "media_shard_$s",
                fileId = "tg_file_mshard_$s",
                messageId = 5000L + s,
                fileName = "shard_$s.json",
                sizeBytes = 250000L,
                sha256 = "sha256_shard_$s",
                itemCount = 1000
            )
        }

        // Manifest shard references representing 10,000 historical manifests (10 shards)
        val manifestShards = (1..10).map { s ->
            ShardReference(
                shardId = "manifest_shard_$s",
                fileId = "tg_file_manshard_$s",
                messageId = 6000L + s,
                fileName = "manifest_shard_$s.json",
                sizeBytes = 150000L,
                sha256 = "sha256_manshard_$s",
                itemCount = 1000
            )
        }

        val largeMasterIndex = MasterIndex(
            format = MasterIndex.FORMAT_IDENTIFIER,
            version = MasterIndex.CURRENT_VERSION,
            generation = 50L,
            updatedAt = System.currentTimeMillis(),
            botId = 123456789L,
            channelId = -1001234567890L,
            manifests = activeManifests,
            manifestShards = manifestShards,
            shards = mediaShards,
            activeMediaRecords = activeMedia
        )
        largeMasterIndex.validate()

        // Total media: 1,000 active + 50,000 in shards = 51,000
        assertEquals(51000, largeMasterIndex.totalMediaCount)
        // Total manifests: 1,000 active + 10,000 in shards = 11,000
        assertEquals(11000, largeMasterIndex.totalManifestCount)

        // Serialize root index
        val rootBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(largeMasterIndex)
        val rootSize = rootBytes.size

        // Telegram Bot API getFile download limit is 20,000,000 bytes (20 MB)
        val telegramBotApiLimit = 20_000_000L
        assertTrue(
            "Root master index size ($rootSize bytes) must be well below 20 MB Telegram limit",
            rootSize < 2_000_000 // In fact strictly under 2 MB (we expect ~600-800 KB)
        )
        assertTrue("Root master index must be safely below 20 MB limit", rootSize < telegramBotApiLimit)

        // Serialize a 1,000-item CatalogShard
        val sampleShard = CatalogShard(
            format = CatalogShard.FORMAT_IDENTIFIER,
            version = CatalogShard.CURRENT_VERSION,
            shardId = "shard_max_test",
            createdAt = System.currentTimeMillis(),
            botId = 123456789L,
            channelId = -1001234567890L,
            itemCount = activeMedia.size,
            records = activeMedia
        )
        sampleShard.validate()
        val shardBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(sampleShard)
        val shardSize = shardBytes.size
        assertTrue(
            "CatalogShard size ($shardSize bytes) must be well below 20 MB Telegram limit",
            shardSize < 500_000 // In fact strictly under 500 KB (~250 KB)
        )
        assertTrue("CatalogShard must be safely below 20 MB limit", shardSize < telegramBotApiLimit)

        // Serialize a 1,000-item ManifestShard
        val sampleManifestShard = com.gallerysync.app.data.catalog.models.ManifestShard(
            format = com.gallerysync.app.data.catalog.models.ManifestShard.FORMAT_IDENTIFIER,
            version = com.gallerysync.app.data.catalog.models.ManifestShard.CURRENT_VERSION,
            shardId = "mshard_max_test",
            createdAt = System.currentTimeMillis(),
            botId = 123456789L,
            channelId = -1001234567890L,
            itemCount = activeManifests.size,
            manifests = activeManifests
        )
        sampleManifestShard.validate()
        val mShardBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(sampleManifestShard)
        val mShardSize = mShardBytes.size
        assertTrue(
            "ManifestShard size ($mShardSize bytes) must be well below 20 MB Telegram limit",
            mShardSize < 500_000 // In fact strictly under 500 KB (~200 KB)
        )
        assertTrue("ManifestShard must be safely below 20 MB limit", mShardSize < telegramBotApiLimit)
    }
}
