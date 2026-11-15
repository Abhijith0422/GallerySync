package com.gallerysync.app.api

import android.content.Context
import android.util.Log
import com.gallerysync.app.utils.connectivity.ConnectivityObserver
import com.gallerysync.app.utils.connectivity.ConnectivityStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Basic identity information for a Telegram bot returned by getMe.
 */
data class TelegramBotInfo(
    val id: Long,
    val firstName: String,
    val username: String?
)

/**
 * Basic channel / chat information returned by getChat.
 */
data class TelegramChatInfo(
    val id: Long,
    val title: String,
    val type: String
)

/**
 * Detected destination chat (channel, supergroup, or group) discovered via getUpdates.
 */
data class DetectedDestination(
    val chatId: Long,
    val title: String,
    val type: String
)

/**
 * Result of user-triggered Telegram update polling for automatic destination detection.
 */
sealed class ChatDetectionResult {
    data class Success(val destinations: List<DetectedDestination>) : ChatDetectionResult()
    object NoUpdatesFound : ChatDetectionResult()
    data class WebhookActive(val webhookUrl: String) : ChatDetectionResult()
    data class PollingConflict(val message: String) : ChatDetectionResult()
    data class Error(val message: String) : ChatDetectionResult()
}

/**
 * Represents the comprehensive connection states for the Telegram bot configuration.
 */
sealed class ConnectionState {
    object NotConfigured : ConnectionState()
    object Connecting : ConnectionState()

    data class Connected(
        val botInfo: TelegramBotInfo,
        val chatInfo: TelegramChatInfo,
        val topicId: Long? = null
    ) : ConnectionState()

    data class InvalidToken(val message: String) : ConnectionState()
    data class ChannelNotFound(val message: String) : ConnectionState()
    data class BotNotAdmin(val message: String) : ConnectionState()
    data class InsufficientPermissions(val message: String) : ConnectionState()
    data class NetworkUnavailable(val message: String) : ConnectionState()
    data class GenericError(val message: String) : ConnectionState()
}

/**
 * Result of user-triggered test message operation.
 */
sealed class TestMessageResult {
    data class Success(val messageId: Long) : TestMessageResult()
    data class Failure(val error: String) : TestMessageResult()
}

/**
 * Centralized manager for verifying Telegram Bot connections, validating chat memberships
 * and posting rights, masking credentials, and sending user-triggered test messages.
 */
object TelegramConnectionManager {
    private const val TAG = "TelegramConnManager"

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Overridable client for testing with mock interceptors. */
    var httpClient: OkHttpClient = defaultClient

    /**
     * Masks the bot token for display, showing the bot ID prefix and the last 4 characters.
     * Example: "123456789:ABCDefghIJKlmnopqrstuvwxyz12345" -> "123456789:••••••••••••345"
     */
    fun maskToken(token: String): String {
        val trimmed = token.trim()
        if (trimmed.isBlank()) return ""
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx > 0 && trimmed.length > colonIdx + 6) {
            val idPart = trimmed.substring(0, colonIdx)
            val suffix = trimmed.takeLast(4)
            return "$idPart:••••••••••••$suffix"
        }
        return if (trimmed.length > 8) {
            trimmed.take(4) + "••••••••" + trimmed.takeLast(4)
        } else {
            "••••••••"
        }
    }

    /**
     * Sanitizes error messages by stripping any bot tokens, preventing credential leakage in logs or UI.
     */
    fun sanitizeError(raw: String, token: String? = null): String {
        var sanitized = raw
        if (!token.isNullOrBlank()) {
            sanitized = sanitized.replace(token.trim(), "[REDACTED_TOKEN]")
        }
        return sanitized.replace(Regex("""bot\d+:[A-Za-z0-9_-]+"""), "bot[REDACTED_TOKEN]")
    }

    /**
     * Validates Telegram bot token structure: <numeric_bot_id>:<secret_key>
     */
    fun validateTokenFormat(token: String): Pair<Boolean, String?> {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            return Pair(false, "Bot token cannot be empty.")
        }
        val parts = trimmed.split(":")
        if (parts.size != 2 || parts[0].toLongOrNull() == null || parts[1].length < 10) {
            return Pair(false, "Malformed bot token. Expected format: 123456789:ABC-DEF1234ghIkl...")
        }
        return Pair(true, null)
    }

    /**
     * Validates Chat ID structure (non-zero 64-bit integer, usually negative for channels/supergroups).
     */
    fun validateChatIdFormat(chatIdStr: String): Pair<Boolean, Long?> {
        val trimmed = chatIdStr.trim()
        if (trimmed.isEmpty()) {
            return Pair(false, null)
        }
        val id = trimmed.toLongOrNull()
        if (id == null || id == 0L) {
            return Pair(false, null)
        }
        return Pair(true, id)
    }

    /**
     * Validates optional forum topic ID (positive integer or null/empty).
     */
    fun validateTopicIdFormat(topicIdStr: String): Pair<Boolean, Long?> {
        val trimmed = topicIdStr.trim()
        if (trimmed.isEmpty()) {
            return Pair(true, null)
        }
        val id = trimmed.toLongOrNull()
        if (id == null || id <= 0L) {
            return Pair(false, null)
        }
        return Pair(true, id)
    }

    /**
     * Verifies the Telegram bot connection by executing:
     * 1. getMe: validates token and retrieves bot identity.
     * 2. getChat: validates channel existence and accessibility.
     * 3. getChatMember: validates bot administrator status and channel posting rights.
     */
    suspend fun verifyConnection(
        token: String,
        chatId: Long,
        topicId: Long? = null,
        isNetworkAvailable: Boolean = true
    ): ConnectionState {
        if (!isNetworkAvailable) {
            return ConnectionState.NetworkUnavailable("Network unavailable. Please check your internet connection.")
        }

        val (tokenValid, tokenError) = validateTokenFormat(token)
        if (!tokenValid) {
            return ConnectionState.InvalidToken(tokenError ?: "Invalid bot token format.")
        }

        if (chatId == 0L) {
            return ConnectionState.ChannelNotFound("Invalid Chat ID: 0. Private channels require a valid negative chat ID (e.g. -100...).")
        }

        return withContext(Dispatchers.IO) {
            try {
                val cleanToken = token.trim()

                // Step 1: Call getMe
                val getMeUrl = "https://api.telegram.org/bot$cleanToken/getMe"
                val getMeReq = Request.Builder().url(getMeUrl).get().build()

                val botInfo: TelegramBotInfo
                httpClient.newCall(getMeReq).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful || body.isBlank()) {
                        val desc = parseTelegramDescription(body) ?: "HTTP ${resp.code}"
                        return@withContext ConnectionState.InvalidToken("Telegram rejected bot token ($desc). Please check your token with @BotFather.")
                    }
                    val json = JSONObject(body)
                    if (!json.optBoolean("ok", false)) {
                        val desc = json.optString("description", "Unauthorized")
                        return@withContext ConnectionState.InvalidToken("Telegram rejected bot token: $desc")
                    }
                    val result = json.optJSONObject("result")
                        ?: return@withContext ConnectionState.InvalidToken("Missing result from Telegram getMe.")

                    val botId = result.optLong("id", 0L)
                    val firstName = result.optString("first_name", "Bot")
                    val username = result.optString("username").takeIf { it.isNotBlank() }
                    botInfo = TelegramBotInfo(id = botId, firstName = firstName, username = username)
                }

                // Step 2: Call getChat
                val getChatUrl = "https://api.telegram.org/bot$cleanToken/getChat?chat_id=$chatId"
                val getChatReq = Request.Builder().url(getChatUrl).get().build()

                val chatInfo: TelegramChatInfo
                httpClient.newCall(getChatReq).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful || body.isBlank()) {
                        val desc = parseTelegramDescription(body) ?: "HTTP ${resp.code}"
                        return@withContext ConnectionState.ChannelNotFound("Cannot access channel ($chatId): $desc. Ensure the channel ID is correct and the bot is a member.")
                    }
                    val json = JSONObject(body)
                    if (!json.optBoolean("ok", false)) {
                        val desc = json.optString("description", "Chat not found")
                        return@withContext ConnectionState.ChannelNotFound("Cannot find channel ($chatId): $desc. Ensure the bot is added to the channel.")
                    }
                    val result = json.optJSONObject("result")
                        ?: return@withContext ConnectionState.ChannelNotFound("Missing result from Telegram getChat.")

                    val id = result.optLong("id", chatId)
                    val title = result.optString("title", "Telegram Channel")
                    val type = result.optString("type", "channel")
                    chatInfo = TelegramChatInfo(id = id, title = title, type = type)
                }

                // Step 3: Call getChatMember to verify administrator rights
                val getChatMemberUrl = "https://api.telegram.org/bot$cleanToken/getChatMember?chat_id=$chatId&user_id=${botInfo.id}"
                val getChatMemberReq = Request.Builder().url(getChatMemberUrl).get().build()

                httpClient.newCall(getChatMemberReq).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful || body.isBlank()) {
                        val desc = parseTelegramDescription(body) ?: "HTTP ${resp.code}"
                        return@withContext ConnectionState.BotNotAdmin("Failed to verify bot admin status in channel: $desc. Ensure the bot is added as an administrator.")
                    }
                    val json = JSONObject(body)
                    if (!json.optBoolean("ok", false)) {
                        val desc = json.optString("description", "Bot is not a member of the chat")
                        return@withContext ConnectionState.BotNotAdmin("Bot is not an administrator in chat $chatId: $desc")
                    }
                    val result = json.optJSONObject("result")
                        ?: return@withContext ConnectionState.BotNotAdmin("Missing result from getChatMember.")

                    val status = result.optString("status", "")
                    if (status != "administrator" && status != "creator") {
                        return@withContext ConnectionState.BotNotAdmin(
                            "Bot status in channel is '$status'. The bot must be promoted to Administrator with posting rights."
                        )
                    }

                    // For broadcast channels, verify post permissions
                    if (chatInfo.type == "channel") {
                        val canPost = if (result.has("can_post_messages")) {
                            result.optBoolean("can_post_messages", true)
                        } else {
                            true
                        }
                        if (!canPost) {
                            return@withContext ConnectionState.InsufficientPermissions(
                                "Bot is an administrator, but lacks 'Post Messages' permission in this channel. Please enable 'Post Messages' in Administrator settings."
                            )
                        }
                    }
                }

                // All verification steps passed
                Log.i(TAG, "Telegram connection successfully verified: ${botInfo.firstName} (@${botInfo.username}) -> ${chatInfo.title}")
                ConnectionState.Connected(
                    botInfo = botInfo,
                    chatInfo = chatInfo,
                    topicId = topicId
                )

            } catch (e: Exception) {
                when (e) {
                    is UnknownHostException, is SocketTimeoutException, is ConnectException -> {
                        Log.w(TAG, "Network failure during Telegram verification", e)
                        ConnectionState.NetworkUnavailable("Network error: Unable to reach Telegram servers. Please check your internet connection.")
                    }
                    else -> {
                        Log.e(TAG, "Error during Telegram connection verification", e)
                        ConnectionState.GenericError(sanitizeError(e.message ?: "Unknown error occurred during verification", token))
                    }
                }
            }
        }
    }

    /**
     * Explicit user-triggered test message to confirm live posting capability.
     */
    suspend fun sendTestMessage(
        token: String,
        chatId: Long,
        topicId: Long? = null,
        isNetworkAvailable: Boolean = true
    ): TestMessageResult {
        if (!isNetworkAvailable) {
            return TestMessageResult.Failure("Network unavailable. Please check your connection and try again.")
        }
        if (token.isBlank() || chatId == 0L) {
            return TestMessageResult.Failure("Bot token or channel ID is not configured.")
        }

        return withContext(Dispatchers.IO) {
            try {
                val cleanToken = token.trim()
                val url = "https://api.telegram.org/bot$cleanToken/sendMessage"

                val jsonBody = JSONObject().apply {
                    put("chat_id", chatId)
                    put(
                        "text",
                        "🔔 <b>GallerySync Connection Test</b>\n\n" +
                                "Your Telegram bot is successfully connected to this channel and ready to back up your photos and videos!"
                    )
                    put("parse_mode", "HTML")
                    if (topicId != null && topicId > 0L) {
                        put("message_thread_id", topicId)
                    }
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = jsonBody.toString().toRequestBody(mediaType)
                val request = Request.Builder().url(url).post(requestBody).build()

                httpClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful || body.isBlank()) {
                        val desc = parseTelegramDescription(body) ?: "HTTP ${resp.code}"
                        return@withContext TestMessageResult.Failure("Telegram API error: $desc")
                    }
                    val json = JSONObject(body)
                    if (!json.optBoolean("ok", false)) {
                        val desc = json.optString("description", "Failed to send message")
                        return@withContext TestMessageResult.Failure("Telegram API error: $desc")
                    }
                    val result = json.optJSONObject("result")
                    val messageId = result?.optLong("message_id", 0L) ?: 0L
                    TestMessageResult.Success(messageId)
                }
            } catch (e: Exception) {
                when (e) {
                    is UnknownHostException, is SocketTimeoutException, is ConnectException -> {
                        TestMessageResult.Failure("Network error: Unable to reach Telegram servers. Please check your connection.")
                    }
                    else -> {
                        TestMessageResult.Failure(sanitizeError(e.message ?: "Failed to send test message", token))
                    }
                }
            }
        }
    }

    private fun parseTelegramDescription(body: String): String? {
        return try {
            if (body.startsWith("{")) {
                val json = JSONObject(body)
                json.optString("description").takeIf { it.isNotBlank() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses Telegram Bot API updates JSON string into a list of unique DetectedDestination objects.
     * Safely distinguishes between channel_post.chat (for channels) and message.chat (for groups/supergroups).
     * Never mistakes from.id, message_id, or update_id for chat_id.
     * Preserves negative 64-bit integer chat IDs.
     */
    fun parseUpdates(jsonString: String): List<DetectedDestination> {
        if (jsonString.isBlank()) return emptyList()
        val json = try {
            JSONObject(jsonString)
        } catch (_: Exception) {
            return emptyList()
        }
        if (!json.optBoolean("ok", false)) return emptyList()
        val resultArr = json.optJSONArray("result") ?: return emptyList()

        val destinationsMap = mutableMapOf<Long, DetectedDestination>()

        for (i in 0 until resultArr.length()) {
            val update = resultArr.optJSONObject(i) ?: continue

            // 1. Channel post (broadcast channels)
            val channelPost = update.optJSONObject("channel_post")
                ?: update.optJSONObject("edited_channel_post")
            if (channelPost != null) {
                val chat = channelPost.optJSONObject("chat")
                if (chat != null) {
                    val id = chat.optLong("id", 0L)
                    if (id != 0L) {
                        val title = chat.optString("title", "").ifBlank { "Channel ($id)" }
                        val type = chat.optString("type", "channel")
                        destinationsMap[id] = DetectedDestination(chatId = id, title = title, type = type)
                        continue
                    }
                }
            }

            // 2. Message (groups, supergroups, private chats)
            val message = update.optJSONObject("message")
                ?: update.optJSONObject("edited_message")
            if (message != null) {
                val chat = message.optJSONObject("chat")
                if (chat != null) {
                    val id = chat.optLong("id", 0L)
                    if (id != 0L) {
                        val rawTitle = chat.optString("title", "")
                        val title = if (rawTitle.isNotBlank()) {
                            rawTitle
                        } else {
                            val firstName = chat.optString("first_name", "")
                            val username = chat.optString("username", "")
                            when {
                                firstName.isNotBlank() -> firstName
                                username.isNotBlank() -> "@$username"
                                else -> "Group ($id)"
                            }
                        }
                        val type = chat.optString("type", "group")
                        destinationsMap[id] = DetectedDestination(chatId = id, title = title, type = type)
                        continue
                    }
                }
            }
        }

        // Return unique destinations, newest first (later updates in Telegram getUpdates are chronologically newer)
        return destinationsMap.values.toList().reversed()
    }

    /**
     * User-triggered, bounded Telegram destination detection.
     * 1. Checks getWebhookInfo to ensure no active webhook blocks getUpdates polling.
     * 2. Executes getUpdates without advancing the offset to preserve pending updates.
     * 3. Parses channel_post and message destinations.
     * 4. Sanitizes all exceptions to ensure the bot token is never leaked.
     */
    suspend fun detectChatDestinations(
        token: String,
        isNetworkAvailable: Boolean = true
    ): ChatDetectionResult {
        if (!isNetworkAvailable) {
            return ChatDetectionResult.Error("Network unavailable. Please check your internet connection.")
        }
        val (tokenValid, tokenErr) = validateTokenFormat(token)
        if (!tokenValid) {
            return ChatDetectionResult.Error(tokenErr ?: "Invalid bot token format.")
        }

        return withContext(Dispatchers.IO) {
            val cleanToken = token.trim()
            try {
                // Step 1: Check webhook status via getWebhookInfo
                val webhookUrl = "https://api.telegram.org/bot$cleanToken/getWebhookInfo"
                val webhookReq = Request.Builder().url(webhookUrl).get().build()

                httpClient.newCall(webhookReq).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (resp.code == 409) {
                        val desc = parseTelegramDescription(body) ?: "HTTP 409 Conflict"
                        return@withContext ChatDetectionResult.PollingConflict(
                            sanitizeError("Polling conflict encountered: $desc", cleanToken)
                        )
                    }
                    if (!resp.isSuccessful) {
                        val desc = parseTelegramDescription(body) ?: "HTTP ${resp.code}"
                        return@withContext ChatDetectionResult.Error(
                            sanitizeError("Failed to check bot status ($desc).", cleanToken)
                        )
                    }
                    val json = JSONObject(body)
                    if (json.optBoolean("ok", false)) {
                        val result = json.optJSONObject("result")
                        val activeUrl = result?.optString("url", "") ?: ""
                        if (activeUrl.isNotBlank()) {
                            return@withContext ChatDetectionResult.WebhookActive(activeUrl)
                        }
                    }
                }

                // Step 2: Bounded getUpdates call
                // Note: We do not pass an advanced offset so unrelated pending updates are not acknowledged.
                val updatesUrl = "https://api.telegram.org/bot$cleanToken/getUpdates?limit=100&allowed_updates=%5B%22message%22%2C%22channel_post%22%5D"
                val updatesReq = Request.Builder().url(updatesUrl).get().build()

                httpClient.newCall(updatesReq).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (resp.code == 409) {
                        val desc = parseTelegramDescription(body) ?: "HTTP 409 Conflict"
                        return@withContext ChatDetectionResult.PollingConflict(
                            sanitizeError(
                                "Polling conflict (HTTP 409): $desc. Another bot instance or consumer may be actively polling. You can enter your Chat ID manually.",
                                cleanToken
                            )
                        )
                    }
                    if (!resp.isSuccessful) {
                        val desc = parseTelegramDescription(body) ?: "HTTP ${resp.code}"
                        return@withContext ChatDetectionResult.Error(
                            sanitizeError("Failed to retrieve updates ($desc).", cleanToken)
                        )
                    }
                    val json = JSONObject(body)
                    if (!json.optBoolean("ok", false)) {
                        val desc = json.optString("description", "Failed to retrieve updates")
                        return@withContext ChatDetectionResult.Error(
                            sanitizeError("Telegram API error: $desc", cleanToken)
                        )
                    }

                    val destinations = parseUpdates(body)
                    if (destinations.isEmpty()) {
                        ChatDetectionResult.NoUpdatesFound
                    } else {
                        ChatDetectionResult.Success(destinations)
                    }
                }
            } catch (e: Exception) {
                when (e) {
                    is UnknownHostException, is SocketTimeoutException, is ConnectException -> {
                        ChatDetectionResult.Error("Network error: Unable to reach Telegram servers. Please check your connection.")
                    }
                    else -> {
                        ChatDetectionResult.Error(
                            sanitizeError(e.message ?: "Unknown error occurred during chat detection", cleanToken)
                        )
                    }
                }
            }
        }
    }
}
