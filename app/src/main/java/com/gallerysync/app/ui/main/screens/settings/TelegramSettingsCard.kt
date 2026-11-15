package com.gallerysync.app.ui.main.screens.settings

import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import com.gallerysync.app.api.BotApi
import com.gallerysync.app.api.ConnectionState
import com.gallerysync.app.api.TelegramBotInfo
import com.gallerysync.app.api.TelegramChatInfo
import com.gallerysync.app.api.TelegramConnectionManager
import com.gallerysync.app.api.TestMessageResult
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.utils.connectivity.ConnectivityObserver
import com.gallerysync.app.utils.connectivity.ConnectivityStatus
import com.gallerysync.app.utils.toastFromMainThread
import com.gallerysync.app.workers.WorkModule
import kotlinx.coroutines.launch

/**
 * Settings section for Telegram Bot connection and channel configuration.
 */
@Composable
fun TelegramSettingsCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var connectionState by remember {
        mutableStateOf<ConnectionState>(
            if (Preferences.isTelegramConfigured()) {
                ConnectionState.Connected(
                    botInfo = TelegramBotInfo(
                        id = 0L,
                        firstName = Preferences.getBotName().ifBlank { "Connected Bot" },
                        username = Preferences.getBotUsername().takeIf { it.isNotBlank() }
                    ),
                    chatInfo = TelegramChatInfo(
                        id = Preferences.getEncryptedChannelId(),
                        title = Preferences.getChannelTitle().ifBlank { "Private Channel" },
                        type = "channel"
                    ),
                    topicId = Preferences.getEncryptedTopicId()
                )
            } else {
                ConnectionState.NotConfigured
            }
        )
    }

    var showConfigDialog by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showTestMessageDialog by remember { mutableStateOf(false) }
    var testMessageResultText by remember { mutableStateOf<String?>(null) }
    var isSendingTestMessage by remember { mutableStateOf(false) }
    var isVerifyingConnection by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        SettingsSection(title = "Telegram Connection")

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                when (val state = connectionState) {
                    is ConnectionState.Connected -> {
                        ConnectedCard(
                            state = state,
                            isSendingTestMessage = isSendingTestMessage,
                            onSendTestMessage = {
                                scope.launch {
                                    isSendingTestMessage = true
                                    val token = Preferences.getBotToken()
                                    val channelId = Preferences.getEncryptedChannelId()
                                    val topicId = Preferences.getEncryptedTopicId()
                                    val isOnline = ConnectivityObserver.status() == ConnectivityStatus.Available
                                    val result = TelegramConnectionManager.sendTestMessage(
                                        token = token,
                                        chatId = channelId,
                                        topicId = topicId,
                                        isNetworkAvailable = isOnline
                                    )
                                    isSendingTestMessage = false
                                    when (result) {
                                        is TestMessageResult.Success -> {
                                            testMessageResultText = "Test message delivered successfully to your channel! (Message ID: ${result.messageId})"
                                            showTestMessageDialog = true
                                        }
                                        is TestMessageResult.Failure -> {
                                            testMessageResultText = "Failed to send test message:\n\n${result.error}"
                                            showTestMessageDialog = true
                                        }
                                    }
                                }
                            },
                            onConfigure = { showConfigDialog = true },
                            onDisconnect = { showDisconnectDialog = true }
                        )
                    }

                    is ConnectionState.NotConfigured -> {
                        NotConfiguredCard(
                            onConfigure = { showConfigDialog = true }
                        )
                    }

                    is ConnectionState.Connecting -> {
                        ConnectingCard()
                    }

                    else -> {
                        // Error states
                        ErrorCard(
                            state = state,
                            isRetrying = isVerifyingConnection,
                            onRetry = {
                                scope.launch {
                                    isVerifyingConnection = true
                                    val token = Preferences.getBotToken()
                                    val channelId = Preferences.getEncryptedChannelId()
                                    val topicId = Preferences.getEncryptedTopicId()
                                    val isOnline = ConnectivityObserver.status() == ConnectivityStatus.Available
                                    val verified = TelegramConnectionManager.verifyConnection(
                                        token = token,
                                        chatId = channelId,
                                        topicId = topicId,
                                        isNetworkAvailable = isOnline
                                    )
                                    isVerifyingConnection = false
                                    connectionState = verified
                                    if (verified is ConnectionState.Connected) {
                                        Preferences.saveTelegramCredentials(
                                            token = token,
                                            channel = verified.chatInfo.id,
                                            topic = verified.topicId,
                                            botName = verified.botInfo.firstName,
                                            botUsername = verified.botInfo.username,
                                            channelTitle = verified.chatInfo.title
                                        )
                                        BotApi.reinitialize()
                                        context.toastFromMainThread("✅ Telegram bot verified!")
                                    }
                                }
                            },
                            onConfigure = { showConfigDialog = true },
                            onDisconnect = { showDisconnectDialog = true }
                        )
                    }
                }
            }
        }
    }

    // Configuration Dialog
    if (showConfigDialog) {
        TelegramConfigDialog(
            initialToken = Preferences.getBotToken(),
            initialChatId = if (Preferences.getEncryptedChannelId() != 0L) Preferences.getEncryptedChannelId().toString() else "",
            initialTopicId = Preferences.getEncryptedTopicId()?.toString() ?: "",
            onDismiss = { showConfigDialog = false },
            onConnected = { connectedState ->
                connectionState = connectedState
                showConfigDialog = false
            }
        )
    }

    // Disconnect Confirmation Dialog
    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.LinkOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Disconnect Telegram Bot?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Automatic cloud backups and photo synchronization will be paused until you connect again. Your existing local gallery files are not affected.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        Preferences.clearTelegramCredentials()
                        try {
                            val workManager = WorkManager.getInstance(context)
                            workManager.cancelAllWorkByTag("manual_backup")
                            workManager.cancelAllWorkByTag("instant_upload")
                            WorkModule.PeriodicBackup.cancel()
                        } catch (e: Exception) {
                            Log.e("TelegramSettings", "Error cancelling work", e)
                        }
                        BotApi.reinitialize()
                        connectionState = ConnectionState.NotConfigured
                        showDisconnectDialog = false
                        scope.launch {
                            context.toastFromMainThread("Telegram bot disconnected")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Disconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Test Message Result Dialog
    if (showTestMessageDialog) {
        AlertDialog(
            onDismissRequest = { showTestMessageDialog = false },
            icon = {
                Icon(
                    imageVector = if (testMessageResultText?.startsWith("Test") == true) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                    contentDescription = null,
                    tint = if (testMessageResultText?.startsWith("Test") == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = if (testMessageResultText?.startsWith("Test") == true) "Test Message Sent" else "Test Message Failed",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = testMessageResultText ?: "",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(onClick = { showTestMessageDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
private fun ConnectedCard(
    state: ConnectionState.Connected,
    isSendingTestMessage: Boolean,
    onSendTestMessage: () -> Unit,
    onConfigure: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudDone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "ONLINE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = "🤖 ${state.botInfo.firstName}${state.botInfo.username?.let { " (@$it)" } ?: ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoRow(
                icon = Icons.Rounded.Campaign,
                label = "Channel",
                value = "${state.chatInfo.title} (${state.chatInfo.id})"
            )
            if (state.topicId != null && state.topicId > 0) {
                InfoRow(
                    icon = Icons.Rounded.Tag,
                    label = "Forum Topic ID",
                    value = state.topicId.toString()
                )
            }
            InfoRow(
                icon = Icons.Rounded.Key,
                label = "Bot Token",
                value = TelegramConnectionManager.maskToken(Preferences.getBotToken())
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSendTestMessage,
                enabled = !isSendingTestMessage,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isSendingTestMessage) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isSendingTestMessage) "Sending..." else "Send Test Message")
            }

            OutlinedButton(
                onClick = onConfigure,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Edit")
            }

            OutlinedButton(
                onClick = onDisconnect,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
private fun NotConfiguredCard(onConfigure: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Bot Not Configured",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Connect a Telegram bot and private channel to start backing up your media.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Button(
            onClick = onConfigure,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.AddLink,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Connect Telegram Bot", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ConnectingCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = "Connecting to Telegram...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Verifying bot token, channel, and administrator permissions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorCard(
    state: ConnectionState,
    isRetrying: Boolean,
    onRetry: () -> Unit,
    onConfigure: () -> Unit,
    onDisconnect: () -> Unit
) {
    val (title, explanation) = when (state) {
        is ConnectionState.InvalidToken -> "Invalid Bot Token" to state.message
        is ConnectionState.ChannelNotFound -> "Channel Not Found" to state.message
        is ConnectionState.BotNotAdmin -> "Bot is Not Administrator" to state.message
        is ConnectionState.InsufficientPermissions -> "Missing Channel Permissions" to state.message
        is ConnectionState.NetworkUnavailable -> "Network Unavailable" to state.message
        is ConnectionState.GenericError -> "Connection Error" to state.message
        else -> "Connection Error" to "Unable to verify Telegram connection."
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onRetry,
                enabled = !isRetrying,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isRetrying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text("Retry")
            }

            OutlinedButton(
                onClick = onConfigure,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Edit")
            }

            OutlinedButton(
                onClick = onDisconnect,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Clear")
            }
        }
    }
}

@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Dialog for entering and validating Telegram Bot Token, Channel ID, and Topic ID.
 */
@Composable
fun TelegramConfigDialog(
    initialToken: String,
    initialChatId: String,
    initialTopicId: String,
    onDismiss: () -> Unit,
    onConnected: (ConnectionState.Connected) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tokenInput by remember { mutableStateOf(initialToken) }
    var chatIdInput by remember { mutableStateOf(initialChatId) }
    var topicIdInput by remember { mutableStateOf(initialTopicId) }

    var showToken by remember { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showAutoDetectDialog by remember { mutableStateOf(false) }
    var detectedBadge by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isVerifying) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Configure Telegram Bot",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Connect your bot to a private Telegram channel. The bot must be added as an Administrator with message posting permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Bot Token Input
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = {
                        tokenInput = it
                        errorMessage = null
                    },
                    label = { Text("Bot Token") },
                    placeholder = { Text("123456789:ABC-DEF1234...") },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                imageVector = if (showToken) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (showToken) "Hide token" else "Show token"
                            )
                        }
                    },
                    supportingText = {
                        Text("Obtain token from @BotFather")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // Auto Detect Chat ID Button
                OutlinedButton(
                    onClick = {
                        val (tokenValid, tokenErr) = TelegramConnectionManager.validateTokenFormat(tokenInput)
                        if (!tokenValid) {
                            errorMessage = tokenErr ?: "Please enter a valid Bot Token first."
                        } else {
                            showAutoDetectDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Radar,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Auto Detect Chat ID")
                }

                // Detected Destination Badge
                AnimatedVisibility(visible = detectedBadge != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Auto-detected: $detectedBadge",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                // Channel Chat ID Input (Manual fallback)
                OutlinedTextField(
                    value = chatIdInput,
                    onValueChange = {
                        chatIdInput = it
                        detectedBadge = null
                        errorMessage = null
                    },
                    label = { Text("Channel Chat ID") },
                    placeholder = { Text("-1001234567890") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text("Include the negative prefix (e.g. -100...)")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // Forum Topic ID (Optional)
                OutlinedTextField(
                    value = topicIdInput,
                    onValueChange = {
                        topicIdInput = it
                        errorMessage = null
                    },
                    label = { Text("Forum Topic ID (Optional)") },
                    placeholder = { Text("e.g. 2") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text("Leave empty for root channel / general topic")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // Error message banner
                AnimatedVisibility(visible = errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errorMessage ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val (tokenValid, tokenErr) = TelegramConnectionManager.validateTokenFormat(tokenInput)
                    if (!tokenValid) {
                        errorMessage = tokenErr
                        return@Button
                    }

                    val (chatIdValid, parsedChatId) = TelegramConnectionManager.validateChatIdFormat(chatIdInput)
                    if (!chatIdValid || parsedChatId == null) {
                        errorMessage = "Invalid Chat ID. Please enter a valid non-zero integer (e.g. -1001234567890)."
                        return@Button
                    }

                    val (topicValid, parsedTopicId) = TelegramConnectionManager.validateTopicIdFormat(topicIdInput)
                    if (!topicValid) {
                        errorMessage = "Invalid Forum Topic ID. Must be a positive number or left empty."
                        return@Button
                    }

                    scope.launch {
                        isVerifying = true
                        errorMessage = null
                        val isOnline = ConnectivityObserver.status() == ConnectivityStatus.Available
                        val result = TelegramConnectionManager.verifyConnection(
                            token = tokenInput,
                            chatId = parsedChatId,
                            topicId = parsedTopicId,
                            isNetworkAvailable = isOnline
                        )
                        isVerifying = false

                        when (result) {
                            is ConnectionState.Connected -> {
                                Preferences.saveTelegramCredentials(
                                    token = tokenInput,
                                    channel = result.chatInfo.id,
                                    topic = result.topicId,
                                    botName = result.botInfo.firstName,
                                    botUsername = result.botInfo.username,
                                    channelTitle = result.chatInfo.title
                                )
                                BotApi.reinitialize()
                                context.toastFromMainThread("✅ Telegram bot connected successfully!")
                                onConnected(result)
                            }
                            is ConnectionState.InvalidToken -> errorMessage = result.message
                            is ConnectionState.ChannelNotFound -> errorMessage = result.message
                            is ConnectionState.BotNotAdmin -> errorMessage = result.message
                            is ConnectionState.InsufficientPermissions -> errorMessage = result.message
                            is ConnectionState.NetworkUnavailable -> errorMessage = result.message
                            is ConnectionState.GenericError -> errorMessage = result.message
                            else -> errorMessage = "Failed to verify Telegram connection."
                        }
                    }
                },
                enabled = !isVerifying && tokenInput.isNotBlank() && chatIdInput.isNotBlank()
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Verifying...")
                } else {
                    Text("Connect & Verify")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isVerifying
            ) {
                Text("Cancel")
            }
        }
    )

    if (showAutoDetectDialog) {
        TelegramAutoDetectDialog(
            botToken = tokenInput,
            onDismiss = { showAutoDetectDialog = false },
            onDestinationConfirmed = { dest ->
                chatIdInput = dest.chatId.toString()
                detectedBadge = "${dest.title} (${dest.chatId})"
                errorMessage = null
                showAutoDetectDialog = false
            }
        )
    }
}
