package com.gallerysync.app.api

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelegramCatalogApiTest {

    private fun createMockClient(
        statusCode: Int = 200,
        responseJson: String = "",
        throwException: IOException? = null,
        contentType: String = "application/json",
        rawBytes: ByteArray? = null
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (throwException != null) {
                    throw throwException
                }
                val request = chain.request()
                val body = if (rawBytes != null) {
                    rawBytes.toResponseBody(contentType.toMediaType())
                } else {
                    responseJson.toResponseBody(contentType.toMediaType())
                }

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message("Response $statusCode")
                    .body(body)
                    .build()
            }
            .build()
    }

    @Test
    fun testSendDocument_success_parsesResponse() = runBlocking {
        val successJson = """
            {
              "ok": true,
              "result": {
                "message_id": 554433,
                "chat": {
                  "id": -1001234567890,
                  "type": "channel"
                },
                "document": {
                  "file_id": "MANIFEST_FILE_ID_123",
                  "file_name": "gallerysync_manifest_abc.json",
                  "file_size": 2048
                }
              }
            }
        """.trimIndent()

        val api = TelegramCatalogApi(createMockClient(200, successJson))
        val result = api.sendDocument(
            botToken = "123456:ABC-DEF",
            channelId = -1001234567890L,
            fileBytes = ByteArray(2048),
            fileName = "gallerysync_manifest_abc.json"
        )

        assertTrue(result is TelegramCatalogUploadResult.Success)
        val success = result as TelegramCatalogUploadResult.Success
        assertEquals("MANIFEST_FILE_ID_123", success.fileId)
        assertEquals(554433L, success.messageId)
        assertEquals(2048L, success.fileSize)
        assertEquals(-1001234567890L, success.chatId)
    }

    @Test
    fun testSendDocument_rateLimited429_parsesRetryAfter() = runBlocking {
        val rateLimitJson = """
            {
              "ok": false,
              "error_code": 429,
              "description": "Too Many Requests: retry after 45",
              "parameters": {
                "retry_after": 45
              }
            }
        """.trimIndent()

        val api = TelegramCatalogApi(createMockClient(429, rateLimitJson))
        val result = api.sendDocument(
            botToken = "123456:ABC-DEF",
            channelId = -1001234567890L,
            fileBytes = ByteArray(100),
            fileName = "index.json"
        )

        assertTrue(result is TelegramCatalogUploadResult.RateLimited)
        val rateLimited = result as TelegramCatalogUploadResult.RateLimited
        assertEquals(45, rateLimited.retryAfterSeconds)
    }

    @Test
    fun testSendDocument_networkTimeout_surfacesNetworkError() = runBlocking {
        val api = TelegramCatalogApi(createMockClient(throwException = SocketTimeoutException("Connection timed out")))
        val result = api.sendDocument(
            botToken = "123456:ABC-DEF",
            channelId = -1001234567890L,
            fileBytes = ByteArray(100),
            fileName = "index.json"
        )

        assertTrue(result is TelegramCatalogUploadResult.NetworkError)
    }

    @Test
    fun testPinChatMessage_success() = runBlocking {
        val pinSuccessJson = """{"ok": true, "result": true}"""
        val api = TelegramCatalogApi(createMockClient(200, pinSuccessJson))
        val result = api.pinChatMessage("123:token", -100L, 554433L)

        assertTrue(result is TelegramCatalogResult.Success)
        assertEquals(true, (result as TelegramCatalogResult.Success).value)
    }

    @Test
    fun testUnpinChatMessage_success() = runBlocking {
        val unpinSuccessJson = """{"ok": true, "result": true}"""
        val api = TelegramCatalogApi(createMockClient(200, unpinSuccessJson))
        val result = api.unpinChatMessage("123:token", -100L, 554433L)

        assertTrue(result is TelegramCatalogResult.Success)
        assertEquals(true, (result as TelegramCatalogResult.Success).value)
    }

    @Test
    fun testGetChatInfo_withPinnedDocument_parsesCorrectly() = runBlocking {
        val chatInfoJson = """
            {
              "ok": true,
              "result": {
                "id": -1001234567890,
                "type": "channel",
                "title": "Backup Channel",
                "pinned_message": {
                  "message_id": 990011,
                  "date": 1700000000,
                  "document": {
                    "file_id": "INDEX_FILE_ABC",
                    "file_unique_id": "unique_1",
                    "file_name": "gallerysync_master_index.json",
                    "mime_type": "application/json",
                    "file_size": 4096
                  }
                }
              }
            }
        """.trimIndent()

        val api = TelegramCatalogApi(createMockClient(200, chatInfoJson))
        val result = api.getChatInfo("123:token", -1001234567890L)

        assertTrue(result is TelegramCatalogResult.Success)
        val chatInfo = (result as TelegramCatalogResult.Success).value
        assertEquals(-1001234567890L, chatInfo.id)
        assertNotNull(chatInfo.pinnedMessage)
        assertEquals(990011L, chatInfo.pinnedMessage!!.messageId)
        val doc = chatInfo.pinnedMessage!!.document
        assertNotNull(doc)
        assertEquals("INDEX_FILE_ABC", doc!!.fileId)
        assertEquals("gallerysync_master_index.json", doc.fileName)
        assertEquals(4096L, doc.fileSize)
    }

    @Test
    fun testGetChatInfo_withoutPinnedMessage_returnsNullPinned() = runBlocking {
        val chatInfoJson = """
            {
              "ok": true,
              "result": {
                "id": -1001234567890,
                "type": "channel",
                "title": "Backup Channel"
              }
            }
        """.trimIndent()

        val api = TelegramCatalogApi(createMockClient(200, chatInfoJson))
        val result = api.getChatInfo("123:token", -1001234567890L)

        assertTrue(result is TelegramCatalogResult.Success)
        val chatInfo = (result as TelegramCatalogResult.Success).value
        assertNull(chatInfo.pinnedMessage)
    }

    @Test
    fun testGetFile_success_parsesFilePath() = runBlocking {
        val fileInfoJson = """
            {
              "ok": true,
              "result": {
                "file_id": "FILE_XYZ",
                "file_unique_id": "UNIQUE_XYZ",
                "file_size": 1024,
                "file_path": "documents/master_index.json"
              }
            }
        """.trimIndent()

        val api = TelegramCatalogApi(createMockClient(200, fileInfoJson))
        val result = api.getFile("123:token", "FILE_XYZ")

        assertTrue(result is TelegramCatalogResult.Success)
        val fileInfo = (result as TelegramCatalogResult.Success).value
        assertEquals("FILE_XYZ", fileInfo.fileId)
        assertEquals("documents/master_index.json", fileInfo.filePath)
    }

    @Test
    fun testDownloadFile_boundedMemory_success() = runBlocking {
        val samplePayload = "{\"test\": \"data\"}".toByteArray(Charsets.UTF_8)
        val api = TelegramCatalogApi(createMockClient(200, rawBytes = samplePayload, contentType = "application/json"))
        val result = api.downloadFile("123:token", "documents/sample.json")

        assertTrue(result is TelegramCatalogResult.Success)
        val bytes = (result as TelegramCatalogResult.Success).value
        assertEquals(samplePayload.size, bytes.size)
    }

    @Test
    fun testDownloadFile_exceedingMaxSizeBytes_rejected() = runBlocking {
        // Enforce a small max size of 100 bytes, attempt to download 200 bytes
        val largePayload = ByteArray(200)
        val api = TelegramCatalogApi(createMockClient(200, rawBytes = largePayload, contentType = "application/octet-stream"))
        val result = api.downloadFile("123:token", "documents/large.bin", maxSizeBytes = 100L)

        assertTrue(result is TelegramCatalogResult.InvalidResponse)
        val inv = result as TelegramCatalogResult.InvalidResponse
        assertTrue(inv.reason.contains("exceeds", ignoreCase = true))
    }

    @Test
    fun testExtractBotId() {
        assertEquals(123456789L, TelegramCatalogApi.extractBotId("123456789:ABCdef-GHIjkl"))
        assertEquals(0L, TelegramCatalogApi.extractBotId(""))
        assertEquals(0L, TelegramCatalogApi.extractBotId(null))
        assertEquals(0L, TelegramCatalogApi.extractBotId("invalid_token"))
    }
}
