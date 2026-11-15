package com.gallerysync.app.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TelegramConnectionManagerTest {

    private lateinit var originalClient: OkHttpClient

    @Before
    fun setUp() {
        originalClient = TelegramConnectionManager.httpClient
    }

    @After
    fun tearDown() {
        TelegramConnectionManager.httpClient = originalClient
    }

    // --- 1. Token Format Validation ---

    @Test
    fun `validateTokenFormat rejects empty token`() {
        val (valid, error) = TelegramConnectionManager.validateTokenFormat("   ")
        assertFalse(valid)
        assertEquals("Bot token cannot be empty.", error)
    }

    @Test
    fun `validateTokenFormat rejects token without colon`() {
        val (valid, error) = TelegramConnectionManager.validateTokenFormat("123456789abcdefghijk")
        assertFalse(valid)
        assertNotNull(error)
        assertTrue(error!!.contains("Malformed"))
    }

    @Test
    fun `validateTokenFormat rejects non-numeric bot id prefix`() {
        val (valid, error) = TelegramConnectionManager.validateTokenFormat("abc:defghijk123456789")
        assertFalse(valid)
        assertNotNull(error)
        assertTrue(error!!.contains("Malformed"))
    }

    @Test
    fun `validateTokenFormat rejects secret part shorter than 10 chars`() {
        val (valid, error) = TelegramConnectionManager.validateTokenFormat("123456789:short")
        assertFalse(valid)
        assertNotNull(error)
        assertTrue(error!!.contains("Malformed"))
    }

    @Test
    fun `validateTokenFormat accepts standard Telegram bot token`() {
        val (valid, error) = TelegramConnectionManager.validateTokenFormat("123456789:ABC-DEF1234ghIkl-zyx57W2v1u123ew11")
        assertTrue(valid)
        assertNull(error)
    }

    // --- 2. Chat ID & Topic ID Validation ---

    @Test
    fun `validateChatIdFormat validates negative 64-bit channel ids`() {
        val (valid, id) = TelegramConnectionManager.validateChatIdFormat("-1001234567890")
        assertTrue(valid)
        assertEquals(-1001234567890L, id)
    }

    @Test
    fun `validateChatIdFormat rejects zero or invalid string`() {
        assertFalse(TelegramConnectionManager.validateChatIdFormat("0").first)
        assertFalse(TelegramConnectionManager.validateChatIdFormat("not_a_number").first)
        assertFalse(TelegramConnectionManager.validateChatIdFormat("").first)
    }

    @Test
    fun `validateTopicIdFormat handles optional and positive topic ids`() {
        // Empty is valid (topic is optional)
        val (validEmpty, emptyId) = TelegramConnectionManager.validateTopicIdFormat("")
        assertTrue(validEmpty)
        assertNull(emptyId)

        // Positive number is valid
        val (validTopic, topicId) = TelegramConnectionManager.validateTopicIdFormat("42")
        assertTrue(validTopic)
        assertEquals(42L, topicId)

        // Zero or negative is invalid
        val (validZero, _) = TelegramConnectionManager.validateTopicIdFormat("0")
        assertFalse(validZero)
        val (validNeg, _) = TelegramConnectionManager.validateTopicIdFormat("-5")
        assertFalse(validNeg)
    }

    // --- 3. Token Masking and Sanitization ---

    @Test
    fun `maskToken hides token secret while preserving id prefix and suffix`() {
        val token = "123456789:ABCDefghIJKlmnopqrstuvwxyz12345"
        val masked = TelegramConnectionManager.maskToken(token)

        assertTrue("Should start with bot id", masked.startsWith("123456789:"))
        assertTrue("Should end with last 4 chars", masked.endsWith("2345"))
        assertFalse("Must not contain plain secret", masked.contains("ABCDefgh"))
        assertTrue("Must contain bullet masking", masked.contains("••••"))
    }

    @Test
    fun `sanitizeError strips token from error strings and URLs`() {
        val token = "123456789:ABCDefghIJKlmnopqrstuvwxyz12345"
        val rawError = "Failed call to https://api.telegram.org/bot$token/sendMessage with code 400"
        val sanitized = TelegramConnectionManager.sanitizeError(rawError, token)

        assertFalse("Token must not be present in sanitized output", sanitized.contains(token))
        assertTrue("Must contain redacted placeholder", sanitized.contains("[REDACTED_TOKEN]"))
    }

    // --- 4. Mocked API Connection Verification ---

    private fun createMockClient(
        getMeResponse: Pair<Int, String>,
        getChatResponse: Pair<Int, String>,
        getChatMemberResponse: Pair<Int, String>
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val (code, body) = when {
                    url.contains("/getMe") -> getMeResponse
                    url.contains("/getChat?") -> getChatResponse
                    url.contains("/getChatMember?") -> getChatMemberResponse
                    else -> Pair(404, """{"ok":false,"description":"Not found"}""")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    @Test
    fun `verifyConnection returns Connected when all Telegram checks succeed`() = runBlocking {
        TelegramConnectionManager.httpClient = createMockClient(
            getMeResponse = Pair(200, """{"ok":true,"result":{"id":987654321,"is_bot":true,"first_name":"GallerySyncBot","username":"gallerysync_bot"}}"""),
            getChatResponse = Pair(200, """{"ok":true,"result":{"id":-1001234567890,"title":"My Private Channel","type":"channel"}}"""),
            getChatMemberResponse = Pair(200, """{"ok":true,"result":{"status":"administrator","can_post_messages":true}}""")
        )

        val result = TelegramConnectionManager.verifyConnection(
            token = "987654321:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
            chatId = -1001234567890L,
            topicId = 2L,
            isNetworkAvailable = true
        )

        assertTrue(result is ConnectionState.Connected)
        val connected = result as ConnectionState.Connected
        assertEquals(987654321L, connected.botInfo.id)
        assertEquals("GallerySyncBot", connected.botInfo.firstName)
        assertEquals("gallerysync_bot", connected.botInfo.username)
        assertEquals(-1001234567890L, connected.chatInfo.id)
        assertEquals("My Private Channel", connected.chatInfo.title)
        assertEquals(2L, connected.topicId)
    }

    @Test
    fun `verifyConnection succeeds when bot status is creator`() = runBlocking {
        TelegramConnectionManager.httpClient = createMockClient(
            getMeResponse = Pair(200, """{"ok":true,"result":{"id":987654321,"is_bot":true,"first_name":"GallerySyncBot","username":"gallerysync_bot"}}"""),
            getChatResponse = Pair(200, """{"ok":true,"result":{"id":-1001234567890,"title":"My Channel","type":"channel"}}"""),
            getChatMemberResponse = Pair(200, """{"ok":true,"result":{"status":"creator"}}""")
        )

        val result = TelegramConnectionManager.verifyConnection(
            token = "987654321:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
            chatId = -1001234567890L,
            isNetworkAvailable = true
        )

        assertTrue(result is ConnectionState.Connected)
    }

    @Test
    fun `verifyConnection returns InvalidToken on HTTP 401`() = runBlocking {
        TelegramConnectionManager.httpClient = createMockClient(
            getMeResponse = Pair(401, """{"ok":false,"error_code":401,"description":"Unauthorized"}"""),
            getChatResponse = Pair(200, "{}"),
            getChatMemberResponse = Pair(200, "{}")
        )

        val result = TelegramConnectionManager.verifyConnection(
            token = "987654321:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
            chatId = -1001234567890L,
            isNetworkAvailable = true
        )

        assertTrue(result is ConnectionState.InvalidToken)
        val invalidToken = result as ConnectionState.InvalidToken
        assertTrue(invalidToken.message.contains("Unauthorized"))
    }

    @Test
    fun `verifyConnection returns ChannelNotFound when chat does not exist`() = runBlocking {
        TelegramConnectionManager.httpClient = createMockClient(
            getMeResponse = Pair(200, """{"ok":true,"result":{"id":987654321,"is_bot":true,"first_name":"GallerySyncBot"}}"""),
            getChatResponse = Pair(400, """{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}"""),
            getChatMemberResponse = Pair(200, "{}")
        )

        val result = TelegramConnectionManager.verifyConnection(
            token = "987654321:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
            chatId = -1001234567890L,
            isNetworkAvailable = true
        )

        assertTrue(result is ConnectionState.ChannelNotFound)
        val channelNotFound = result as ConnectionState.ChannelNotFound
        assertTrue(channelNotFound.message.contains("chat not found"))
    }

    @Test
    fun `verifyConnection returns BotNotAdmin when bot is regular member`() = runBlocking {
        TelegramConnectionManager.httpClient = createMockClient(
            getMeResponse = Pair(200, """{"ok":true,"result":{"id":987654321,"is_bot":true,"first_name":"GallerySyncBot"}}"""),
            getChatResponse = Pair(200, """{"ok":true,"result":{"id":-1001234567890,"title":"My Channel","type":"channel"}}"""),
            getChatMemberResponse = Pair(200, """{"ok":true,"result":{"status":"member"}}""")
        )

        val result = TelegramConnectionManager.verifyConnection(
            token = "987654321:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
            chatId = -1001234567890L,
            isNetworkAvailable = true
        )

        assertTrue(result is ConnectionState.BotNotAdmin)
        val notAdmin = result as ConnectionState.BotNotAdmin
        assertTrue(notAdmin.message.contains("Administrator"))
    }

    @Test
    fun `verifyConnection returns InsufficientPermissions when admin lacks can_post_messages`() = runBlocking {
        TelegramConnectionManager.httpClient = createMockClient(
            getMeResponse = Pair(200, """{"ok":true,"result":{"id":987654321,"is_bot":true,"first_name":"GallerySyncBot"}}"""),
            getChatResponse = Pair(200, """{"ok":true,"result":{"id":-1001234567890,"title":"My Channel","type":"channel"}}"""),
            getChatMemberResponse = Pair(200, """{"ok":true,"result":{"status":"administrator","can_post_messages":false}}""")
        )

        val result = TelegramConnectionManager.verifyConnection(
            token = "987654321:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
            chatId = -1001234567890L,
            isNetworkAvailable = true
        )

        assertTrue(result is ConnectionState.InsufficientPermissions)
        val insufficient = result as ConnectionState.InsufficientPermissions
        assertTrue(insufficient.message.contains("Post Messages"))
    }

    @Test
    fun `verifyConnection returns NetworkUnavailable when offline or socket times out`() = runBlocking {
        // Offline check
        val offlineResult = TelegramConnectionManager.verifyConnection(
            token = "987654321:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
            chatId = -1001234567890L,
            isNetworkAvailable = false
        )
        assertTrue(offlineResult is ConnectionState.NetworkUnavailable)

        // Socket timeout check
        TelegramConnectionManager.httpClient = OkHttpClient.Builder()
            .addInterceptor { throw SocketTimeoutException("Read timed out") }
            .build()

        val timeoutResult = TelegramConnectionManager.verifyConnection(
            token = "987654321:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
            chatId = -1001234567890L,
            isNetworkAvailable = true
        )
        assertTrue(timeoutResult is ConnectionState.NetworkUnavailable)
    }

    // --- 5. Test Message Operation ---

    @Test
    fun `sendTestMessage returns Success when Telegram API confirms message delivery`() = runBlocking {
        TelegramConnectionManager.httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body = """{"ok":true,"result":{"message_id":5544,"text":"test"}}"""
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val result = TelegramConnectionManager.sendTestMessage(
            token = "987654321:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
            chatId = -1001234567890L,
            topicId = 2L,
            isNetworkAvailable = true
        )

        assertTrue(result is TestMessageResult.Success)
        assertEquals(5544L, (result as TestMessageResult.Success).messageId)
    }

    @Test
    fun `sendTestMessage returns Failure when Telegram API returns error`() = runBlocking {
        TelegramConnectionManager.httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body = """{"ok":false,"error_code":400,"description":"Bad Request: have no rights to send a message"}"""
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(400)
                    .message("Bad Request")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val result = TelegramConnectionManager.sendTestMessage(
            token = "987654321:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
            chatId = -1001234567890L,
            isNetworkAvailable = true
        )

        assertTrue(result is TestMessageResult.Failure)
        val failure = result as TestMessageResult.Failure
        assertTrue(failure.error.contains("have no rights to send a message"))
    }

    // --- 6. Telegram Auto-Detection & Update Retrieval Tests ---

    private fun createUpdatesMockClient(
        webhookResponse: Pair<Int, String> = Pair(200, """{"ok":true,"result":{"url":""}}"""),
        updatesResponse: Pair<Int, String> = Pair(200, """{"ok":true,"result":[]}""")
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val (code, body) = when {
                    url.contains("/getWebhookInfo") -> webhookResponse
                    url.contains("/getUpdates") -> updatesResponse
                    else -> Pair(404, """{"ok":false,"description":"Not found"}""")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Error")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    @Test
    fun `parseUpdates parses group message update correctly`() {
        val json = """
        {
            "ok": true,
            "result": [
                {
                    "update_id": 1001,
                    "message": {
                        "message_id": 55,
                        "from": { "id": 99999, "is_bot": false, "first_name": "User" },
                        "chat": {
                            "id": -123456789,
                            "title": "Family Backup Group",
                            "type": "group"
                        },
                        "text": "/start"
                    }
                }
            ]
        }
        """.trimIndent()

        val destinations = TelegramConnectionManager.parseUpdates(json)
        assertEquals(1, destinations.size)
        assertEquals(-123456789L, destinations[0].chatId)
        assertEquals("Family Backup Group", destinations[0].title)
        assertEquals("group", destinations[0].type)
    }

    @Test
    fun `parseUpdates parses supergroup update correctly`() {
        val json = """
        {
            "ok": true,
            "result": [
                {
                    "update_id": 1002,
                    "message": {
                        "message_id": 56,
                        "from": { "id": 88888, "is_bot": false, "first_name": "User" },
                        "chat": {
                            "id": -1001122334455,
                            "title": "GallerySync Supergroup",
                            "type": "supergroup"
                        },
                        "text": "/start@GallerySyncBot"
                    }
                }
            ]
        }
        """.trimIndent()

        val destinations = TelegramConnectionManager.parseUpdates(json)
        assertEquals(1, destinations.size)
        assertEquals(-1001122334455L, destinations[0].chatId)
        assertEquals("GallerySync Supergroup", destinations[0].title)
        assertEquals("supergroup", destinations[0].type)
    }

    @Test
    fun `parseUpdates parses channel post update correctly`() {
        val json = """
        {
            "ok": true,
            "result": [
                {
                    "update_id": 1003,
                    "channel_post": {
                        "message_id": 99,
                        "chat": {
                            "id": -1001987654321,
                            "title": "Private Cloud Channel",
                            "type": "channel"
                        },
                        "text": "test post"
                    }
                }
            ]
        }
        """.trimIndent()

        val destinations = TelegramConnectionManager.parseUpdates(json)
        assertEquals(1, destinations.size)
        assertEquals(-1001987654321L, destinations[0].chatId)
        assertEquals("Private Cloud Channel", destinations[0].title)
        assertEquals("channel", destinations[0].type)
    }

    @Test
    fun `parseUpdates preserves 64-bit negative channel and group IDs and never mistakes message_id from_id or update_id`() {
        val json = """
        {
            "ok": true,
            "result": [
                {
                    "update_id": 99999999,
                    "message": {
                        "message_id": 12345,
                        "from": { "id": 555555, "is_bot": false },
                        "chat": {
                            "id": -1009988776655,
                            "title": "Negative ID Channel",
                            "type": "channel"
                        }
                    }
                }
            ]
        }
        """.trimIndent()

        val destinations = TelegramConnectionManager.parseUpdates(json)
        assertEquals(1, destinations.size)
        val dest = destinations[0]
        assertEquals(-1009988776655L, dest.chatId)
        // Ensure never mistaken
        org.junit.Assert.assertNotEquals(99999999L, dest.chatId)
        org.junit.Assert.assertNotEquals(12345L, dest.chatId)
        org.junit.Assert.assertNotEquals(555555L, dest.chatId)
    }

    @Test
    fun `parseUpdates returns multiple unique detected destinations without duplicates`() {
        val json = """
        {
            "ok": true,
            "result": [
                {
                    "update_id": 1,
                    "channel_post": {
                        "message_id": 10,
                        "chat": { "id": -1001111111111, "title": "Channel A", "type": "channel" }
                    }
                },
                {
                    "update_id": 2,
                    "message": {
                        "message_id": 20,
                        "chat": { "id": -222222222, "title": "Group B", "type": "group" }
                    }
                },
                {
                    "update_id": 3,
                    "channel_post": {
                        "message_id": 30,
                        "chat": { "id": -1001111111111, "title": "Channel A Renamed", "type": "channel" }
                    }
                }
            ]
        }
        """.trimIndent()

        val destinations = TelegramConnectionManager.parseUpdates(json)
        assertEquals(2, destinations.size)
        val ids = destinations.map { it.chatId }.toSet()
        assertTrue(ids.contains(-1001111111111L))
        assertTrue(ids.contains(-222222222L))
    }

    @Test
    fun `parseUpdates returns empty list when result is empty`() {
        val emptyResultJson = """{"ok":true,"result":[]}"""
        assertTrue(TelegramConnectionManager.parseUpdates(emptyResultJson).isEmpty())

        val malformedJson = """{"error":"bad"}"""
        assertTrue(TelegramConnectionManager.parseUpdates(malformedJson).isEmpty())

        val blankString = ""
        assertTrue(TelegramConnectionManager.parseUpdates(blankString).isEmpty())
    }

    @Test
    fun `detectChatDestinations returns WebhookActive when webhook is configured`() = runBlocking {
        TelegramConnectionManager.httpClient = createUpdatesMockClient(
            webhookResponse = Pair(200, """{"ok":true,"result":{"url":"https://mywebhook.example.com/bot"}}""")
        )

        val result = TelegramConnectionManager.detectChatDestinations(
            token = "987654321:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
            isNetworkAvailable = true
        )

        assertTrue(result is ChatDetectionResult.WebhookActive)
        val webhook = result as ChatDetectionResult.WebhookActive
        assertTrue(webhook.webhookUrl.contains("https://mywebhook.example.com/bot"))
    }

    @Test
    fun `detectChatDestinations returns PollingConflict when HTTP 409 occurs`() = runBlocking {
        TelegramConnectionManager.httpClient = createUpdatesMockClient(
            webhookResponse = Pair(200, """{"ok":true,"result":{"url":""}}"""),
            updatesResponse = Pair(409, """{"ok":false,"error_code":409,"description":"Conflict: terminated by other getUpdates request"}""")
        )

        val result = TelegramConnectionManager.detectChatDestinations(
            token = "987654321:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
            isNetworkAvailable = true
        )

        assertTrue(result is ChatDetectionResult.PollingConflict)
        val conflict = result as ChatDetectionResult.PollingConflict
        assertTrue(conflict.message.contains("Polling conflict"))
    }

    @Test
    fun `detectChatDestinations returns Success with parsed destinations on clean polling`() = runBlocking {
        val updatesJson = """
        {
            "ok": true,
            "result": [
                {
                    "update_id": 500,
                    "channel_post": {
                        "message_id": 1,
                        "chat": {
                            "id": -1001234567890,
                            "title": "Detected Channel",
                            "type": "channel"
                        }
                    }
                }
            ]
        }
        """.trimIndent()

        TelegramConnectionManager.httpClient = createUpdatesMockClient(
            webhookResponse = Pair(200, """{"ok":true,"result":{"url":""}}"""),
            updatesResponse = Pair(200, updatesJson)
        )

        val result = TelegramConnectionManager.detectChatDestinations(
            token = "987654321:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
            isNetworkAvailable = true
        )

        assertTrue(result is ChatDetectionResult.Success)
        val success = result as ChatDetectionResult.Success
        assertEquals(1, success.destinations.size)
        assertEquals(-1001234567890L, success.destinations[0].chatId)
        assertEquals("Detected Channel", success.destinations[0].title)
    }

    @Test
    fun `detectChatDestinations returns NoUpdatesFound when update list is empty`() = runBlocking {
        TelegramConnectionManager.httpClient = createUpdatesMockClient(
            webhookResponse = Pair(200, """{"ok":true,"result":{"url":""}}"""),
            updatesResponse = Pair(200, """{"ok":true,"result":[]}""")
        )

        val result = TelegramConnectionManager.detectChatDestinations(
            token = "987654321:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
            isNetworkAvailable = true
        )

        assertTrue(result is ChatDetectionResult.NoUpdatesFound)
    }

    @Test
    fun `manual Chat ID fallback format and validation functions safely accept manual user input`() {
        val (validChannel, channelId) = TelegramConnectionManager.validateChatIdFormat("-1001234567890")
        assertTrue(validChannel)
        assertEquals(-1001234567890L, channelId)

        val (validGroup, groupId) = TelegramConnectionManager.validateChatIdFormat("-123456789")
        assertTrue(validGroup)
        assertEquals(-123456789L, groupId)

        val (invalid, _) = TelegramConnectionManager.validateChatIdFormat("not-a-number")
        assertFalse(invalid)
    }

    @Test
    fun `bot token is completely redacted in detectChatDestinations error and polling conflict messages`() = runBlocking {
        val secretToken = "987654321:SUPER_SECRET_TOKEN_XYZ_12345"

        TelegramConnectionManager.httpClient = createUpdatesMockClient(
            webhookResponse = Pair(200, """{"ok":true,"result":{"url":""}}"""),
            updatesResponse = Pair(409, """{"ok":false,"description":"Conflict on token $secretToken"}""")
        )

        val result = TelegramConnectionManager.detectChatDestinations(
            token = secretToken,
            isNetworkAvailable = true
        )

        assertTrue(result is ChatDetectionResult.PollingConflict)
        val msg = (result as ChatDetectionResult.PollingConflict).message
        assertFalse("Secret token must never be present in error message", msg.contains("SUPER_SECRET_TOKEN_XYZ_12345"))
        assertTrue("Sanitized placeholder must be present", msg.contains("[REDACTED_TOKEN]"))
    }
}
