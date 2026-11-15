package com.gallerysync.app.api

import com.gallerysync.app.data.chunking.ChunkIntegrityException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Unit tests for [TelegramChunkApi] validating response parsing, error mapping,
 * status code handling, rate limiting, and response validation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelegramChunkApiTest {

    private fun createMockClient(
        statusCode: Int = 200,
        responseJson: String = "",
        throwException: IOException? = null
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (throwException != null) {
                    throw throwException
                }
                val request = chain.request()
                // Ensure request body writeTo is invoked for mock testing
                val sink = okio.Buffer()
                request.body?.writeTo(sink)

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message("Response $statusCode")
                    .body(responseJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    private val dummyRequestBody = object : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = 100L
        override fun writeTo(sink: BufferedSink) {
            sink.write(ByteArray(100))
        }
    }

    @Test
    fun testSuccessfulSendDocumentResponseParsed() = runBlocking {
        val successJson = """
            {
              "ok": true,
              "result": {
                "message_id": 998877,
                "chat": {
                  "id": -1001234567890,
                  "type": "channel"
                },
                "document": {
                  "file_id": "FILE_XYZ_123",
                  "file_name": "GallerySync_chunk_00000.bin",
                  "file_size": 16777216
                }
              }
            }
        """.trimIndent()

        val client = createMockClient(200, successJson)
        val api = TelegramChunkApi(client)

        val result = api.sendChunkDocument(
            botToken = "123456:ABC-DEF",
            channelId = -1001234567890L,
            chunkRequestBody = dummyRequestBody,
            fileName = "chunk_0.bin",
            expectedSize = 16777216L
        )

        assertTrue(result is TelegramChunkUploadResult.Success)
        val success = result as TelegramChunkUploadResult.Success
        assertEquals("FILE_XYZ_123", success.fileId)
        assertEquals(998877L, success.messageId)
        assertEquals(16777216L, success.fileSize)
        assertEquals(-1001234567890L, success.chatId)
    }

    @Test
    fun testMissingDocumentObjectRejected() = runBlocking {
        val jsonNoDoc = """
            {
              "ok": true,
              "result": {
                "message_id": 1234,
                "chat": { "id": -100 }
              }
            }
        """.trimIndent()

        val client = createMockClient(200, jsonNoDoc)
        val api = TelegramChunkApi(client)

        val result = api.sendChunkDocument(
            botToken = "token",
            channelId = -100L,
            chunkRequestBody = dummyRequestBody,
            fileName = "test.bin",
            expectedSize = 100L
        )

        assertTrue(result is TelegramChunkUploadResult.InvalidResponse)
        val err = result as TelegramChunkUploadResult.InvalidResponse
        assertTrue(err.reason.contains("Missing document object"))
    }

    @Test
    fun testEmptyFileIdRejected() = runBlocking {
        val jsonEmptyId = """
            {
              "ok": true,
              "result": {
                "message_id": 1234,
                "chat": { "id": -100 },
                "document": {
                  "file_id": "   ",
                  "file_size": 100
                }
              }
            }
        """.trimIndent()

        val client = createMockClient(200, jsonEmptyId)
        val api = TelegramChunkApi(client)

        val result = api.sendChunkDocument(
            botToken = "token",
            channelId = -100L,
            chunkRequestBody = dummyRequestBody,
            fileName = "test.bin",
            expectedSize = 100L
        )

        assertTrue(result is TelegramChunkUploadResult.InvalidResponse)
        val err = result as TelegramChunkUploadResult.InvalidResponse
        assertTrue(err.reason.contains("Empty file_id"))
    }

    @Test
    fun testWrongChatIdRejected() = runBlocking {
        val jsonWrongChat = """
            {
              "ok": true,
              "result": {
                "message_id": 1234,
                "chat": { "id": -999999 },
                "document": {
                  "file_id": "valid_id",
                  "file_size": 100
                }
              }
            }
        """.trimIndent()

        val client = createMockClient(200, jsonWrongChat)
        val api = TelegramChunkApi(client)

        val result = api.sendChunkDocument(
            botToken = "token",
            channelId = -100123L,
            chunkRequestBody = dummyRequestBody,
            fileName = "test.bin",
            expectedSize = 100L
        )

        assertTrue(result is TelegramChunkUploadResult.InvalidResponse)
        val err = result as TelegramChunkUploadResult.InvalidResponse
        assertTrue(err.reason.contains("Chat ID mismatch"))
    }

    @Test
    fun testWrongReturnedFileSizeRejected() = runBlocking {
        val jsonWrongSize = """
            {
              "ok": true,
              "result": {
                "message_id": 1234,
                "chat": { "id": -100 },
                "document": {
                  "file_id": "valid_id",
                  "file_size": 500
                }
              }
            }
        """.trimIndent()

        val client = createMockClient(200, jsonWrongSize)
        val api = TelegramChunkApi(client)

        val result = api.sendChunkDocument(
            botToken = "token",
            channelId = -100L,
            chunkRequestBody = dummyRequestBody,
            fileName = "test.bin",
            expectedSize = 1000L
        )

        assertTrue(result is TelegramChunkUploadResult.InvalidResponse)
        val err = result as TelegramChunkUploadResult.InvalidResponse
        assertTrue(err.reason.contains("does not match expected chunk size"))
    }

    @Test
    fun testMissingFileSizeInTelegramResponseAccepted() = runBlocking {
        val jsonMissingSize = """
            {
              "ok": true,
              "result": {
                "message_id": 1234,
                "chat": { "id": -100 },
                "document": {
                  "file_id": "valid_id_no_size"
                }
              }
            }
        """.trimIndent()

        val client = createMockClient(200, jsonMissingSize)
        val api = TelegramChunkApi(client)

        val result = api.sendChunkDocument(
            botToken = "token",
            channelId = -100L,
            chunkRequestBody = dummyRequestBody,
            fileName = "test.bin",
            expectedSize = 1000L
        )

        assertTrue(result is TelegramChunkUploadResult.Success)
        val success = result as TelegramChunkUploadResult.Success
        assertEquals("valid_id_no_size", success.fileId)
        assertEquals(1000L, success.fileSize) // Uses expectedSize as safe fallback
        assertEquals(1234L, success.messageId)
    }

    @Test
    fun testNullFileSizeInTelegramResponseAccepted() = runBlocking {
        val jsonNullSize = """
            {
              "ok": true,
              "result": {
                "message_id": 5678,
                "chat": { "id": -100 },
                "document": {
                  "file_id": "valid_id_null_size",
                  "file_size": null
                }
              }
            }
        """.trimIndent()

        val client = createMockClient(200, jsonNullSize)
        val api = TelegramChunkApi(client)

        val result = api.sendChunkDocument(
            botToken = "token",
            channelId = -100L,
            chunkRequestBody = dummyRequestBody,
            fileName = "test.bin",
            expectedSize = 2000L
        )

        assertTrue(result is TelegramChunkUploadResult.Success)
        val success = result as TelegramChunkUploadResult.Success
        assertEquals("valid_id_null_size", success.fileId)
        assertEquals(2000L, success.fileSize)
    }

    @Test
    fun testOkFalseWithDescription() = runBlocking {
        val jsonError = """
            {
              "ok": false,
              "error_code": 400,
              "description": "Bad Request: chat not found"
            }
        """.trimIndent()

        val client = createMockClient(400, jsonError)
        val api = TelegramChunkApi(client)

        val result = api.sendChunkDocument(
            botToken = "token",
            channelId = -100L,
            chunkRequestBody = dummyRequestBody,
            fileName = "test.bin",
            expectedSize = 100L
        )

        assertTrue(result is TelegramChunkUploadResult.InvalidResponse)
        val err = result as TelegramChunkUploadResult.InvalidResponse
        assertTrue(err.reason.contains("chat not found"))
    }

    @Test
    fun testHttp429WithRetryAfter() = runBlocking {
        val rateLimitJson = """
            {
              "ok": false,
              "error_code": 429,
              "description": "Too Many Requests: retry after 42",
              "parameters": {
                "retry_after": 42
              }
            }
        """.trimIndent()

        val client = createMockClient(429, rateLimitJson)
        val api = TelegramChunkApi(client)

        val result = api.sendChunkDocument(
            botToken = "token",
            channelId = -100L,
            chunkRequestBody = dummyRequestBody,
            fileName = "test.bin",
            expectedSize = 100L
        )

        assertTrue(result is TelegramChunkUploadResult.RateLimited)
        val rl = result as TelegramChunkUploadResult.RateLimited
        assertEquals(42, rl.retryAfterSeconds)
    }

    @Test
    fun testHttp401Unauthorized() = runBlocking {
        val client = createMockClient(401, """{"ok":false,"description":"Unauthorized"}""")
        val api = TelegramChunkApi(client)

        val result = api.sendChunkDocument(
            botToken = "token",
            channelId = -100L,
            chunkRequestBody = dummyRequestBody,
            fileName = "test.bin",
            expectedSize = 100L
        )

        assertTrue(result is TelegramChunkUploadResult.Unauthorized)
        assertEquals(401, (result as TelegramChunkUploadResult.Unauthorized).code)
    }

    @Test
    fun testHttp403PermissionDenied() = runBlocking {
        val client = createMockClient(403, """{"ok":false,"description":"Forbidden: bot is not a member"}""")
        val api = TelegramChunkApi(client)

        val result = api.sendChunkDocument(
            botToken = "token",
            channelId = -100L,
            chunkRequestBody = dummyRequestBody,
            fileName = "test.bin",
            expectedSize = 100L
        )

        assertTrue(result is TelegramChunkUploadResult.PermissionDenied)
        assertEquals(403, (result as TelegramChunkUploadResult.PermissionDenied).code)
    }

    @Test
    fun testHttp500ServerError() = runBlocking {
        val client = createMockClient(500, """Internal Server Error""")
        val api = TelegramChunkApi(client)

        val result = api.sendChunkDocument(
            botToken = "token",
            channelId = -100L,
            chunkRequestBody = dummyRequestBody,
            fileName = "test.bin",
            expectedSize = 100L
        )

        assertTrue(result is TelegramChunkUploadResult.ServerError)
        assertEquals(500, (result as TelegramChunkUploadResult.ServerError).code)
    }

    @Test
    fun testNetworkTimeoutHandled() = runBlocking {
        val client = createMockClient(throwException = SocketTimeoutException("connect timed out"))
        val api = TelegramChunkApi(client)

        val result = api.sendChunkDocument(
            botToken = "token",
            channelId = -100L,
            chunkRequestBody = dummyRequestBody,
            fileName = "test.bin",
            expectedSize = 100L
        )

        assertTrue(result is TelegramChunkUploadResult.NetworkError)
        val netErr = result as TelegramChunkUploadResult.NetworkError
        assertTrue(netErr.message.contains("Timeout"))
    }

    @Test
    fun testIntegrityErrorSurfacedFromRequestBody() = runBlocking {
        val failingRequestBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = 100L
            override fun writeTo(sink: BufferedSink) {
                throw ChunkIntegrityException(0, "expected", "actual", 100L, 50L)
            }
        }

        val client = createMockClient(200, "{}")
        val api = TelegramChunkApi(client)

        val result = api.sendChunkDocument(
            botToken = "token",
            channelId = -100L,
            chunkRequestBody = failingRequestBody,
            fileName = "test.bin",
            expectedSize = 100L
        )

        assertTrue(result is TelegramChunkUploadResult.IntegrityError)
        val intErr = result as TelegramChunkUploadResult.IntegrityError
        assertTrue(intErr.message.contains("integrity verification failed"))
    }

    @Test
    fun testExtractBotIdFromToken() {
        assertEquals(123456789L, TelegramChunkApi.extractBotId("123456789:ABCdefGHIjk"))
        assertEquals(987654321L, TelegramChunkApi.extractBotId("987654321:token"))
        assertEquals(0L, TelegramChunkApi.extractBotId(""))
        assertEquals(0L, TelegramChunkApi.extractBotId("invalidTokenNoColon"))
    }
}
