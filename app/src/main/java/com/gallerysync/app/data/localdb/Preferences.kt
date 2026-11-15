package com.gallerysync.app.data.localdb

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.util.UUID

@Suppress("ktlint:standard:property-naming")
object Preferences {

    // encrypted preferences
    const val botToken: String = "botToken"
    const val channelId: String = "channelId"
    const val topicId: String = "topicId"
    const val botUsernameKey: String = "botUsername"
    const val botNameKey: String = "botName"
    const val channelTitleKey: String = "channelTitle"
    const val connectionStateKey: String = "telegramConnectionState"

    fun getBotToken(): String = getEncryptedString(botToken, "")

    fun getEncryptedChannelId(): Long {
        return try {
            encryptedPreferences.getLong(channelId, 0L)
        } catch (_: Exception) {
            try {
                val str = encryptedPreferences.getString(channelId, null)
                val parsed = str?.toLongOrNull() ?: 0L
                if (parsed != 0L) {
                    editEncrypted { putLong(channelId, parsed) }
                }
                parsed
            } catch (_: Exception) {
                0L
            }
        }
    }

    fun getEncryptedTopicId(): Long? {
        return try {
            val topic = encryptedPreferences.getLong(topicId, -1L)
            if (topic > 0L) topic else null
        } catch (_: Exception) {
            try {
                val str = encryptedPreferences.getString(topicId, null)
                val parsed = str?.toLongOrNull()
                if (parsed != null && parsed > 0L) {
                    editEncrypted { putLong(topicId, parsed) }
                    parsed
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }

    fun getBotUsername(): String = getEncryptedString(botUsernameKey, "")
    fun getBotName(): String = getEncryptedString(botNameKey, "")
    fun getChannelTitle(): String = getEncryptedString(channelTitleKey, "")
    fun getTelegramConnectionState(): String = getEncryptedString(connectionStateKey, "NOT_CONFIGURED")

    fun saveTelegramCredentials(
        token: String,
        channel: Long,
        topic: Long?,
        botName: String,
        botUsername: String?,
        channelTitle: String
    ) {
        editEncrypted {
            putString(botToken, token.trim())
            putLong(channelId, channel)
            if (topic != null && topic > 0L) {
                putLong(topicId, topic)
            } else {
                remove(topicId)
            }
            putString(botNameKey, botName)
            putString(botUsernameKey, botUsername ?: "")
            putString(channelTitleKey, channelTitle)
            putString(connectionStateKey, "CONNECTED")
        }
    }

    fun clearTelegramCredentials() {
        editEncrypted {
            remove(botToken)
            remove(channelId)
            remove(topicId)
            remove(botNameKey)
            remove(botUsernameKey)
            remove(channelTitleKey)
            remove(connectionStateKey)
        }
    }

    fun isTelegramConfigured(): Boolean {
        return getBotToken().isNotBlank() && getEncryptedChannelId() != 0L
    }

    // device identity
    const val deviceIdKey: String = "device_id"
    const val deviceNameKey: String = "device_name"

    fun getOrCreateDeviceId(): String {
        return try {
            var id = getEncryptedString(deviceIdKey, "")
            if (id.isBlank()) {
                id = UUID.randomUUID().toString().take(8)
                editEncrypted { putString(deviceIdKey, id) }
            }
            id
        } catch (_: Throwable) {
            "default_device"
        }
    }

    fun getDeviceName(): String {
        val default = "${Build.MANUFACTURER} ${Build.MODEL}"
        return getString(deviceNameKey, default)
    }

    fun setDeviceName(name: String) {
        edit { putString(deviceNameKey, name) }
    }

    // non-encrypted preferences
    const val startTabKey: String = "startTab"
    const val gridColumnCountKey: String = "gridColumnCount"
    const val isAutoBackupEnabledKey: String = "isPeriodicPhotoBackupEnabled"
    const val isAutoCloudBackupEnabledKey: String = "isAutoCloudBackupEnabled"
    const val autoBackupIntervalKey: String = "periodicPhotoBackupInterval"
    const val autoBackupNetworkTypeKey: String = "periodicPhotoBackupNetworkType"
    const val isAutoExportDatabaseEnabledKey: String = "isAutoExportDatabaseEnabled"
    const val autoExportDatabaseIntervalKey: String = "autoExportDatabaseInterval"
    const val autoExportDatabseLocation: String = "autoExportDatabaseLocation"
    const val defaultAutoExportDatabaseIntervalKey: Long = 7
    const val defaultAutoBackupInterval: Long = 7
    const val defaultGridColumnCount: Int = 4
    const val glideSelectionBehaviorKey: String = "glideSelectionBehavior"
    const val thumbnailResolutionKey: String = "thumbnailResolution"
    const val defaultThumbnailResolution: Int = 150
    const val syncImagePreviewKey: String = "syncImagePreview"
    const val syncImagePreviewSizeKey: String = "syncImagePreviewSize"
    const val excludedBucketNamesKey: String = "excludedBucketNames"
    const val syncModeKey: String = "syncMode"
    const val syncNewOnlyTimestampKey: String = "syncNewOnlyTimestamp"
    const val SYNC_MODE_ALL: String = "all"
    const val SYNC_MODE_NEW_ONLY: String = "newOnly"

    // Network policy preferences (Phase 7)
    const val isWifiOnlyBackupEnabledKey: String = "isWifiOnlyBackupEnabled"
    const val allowMeteredWifiBackupKey: String = "allowMeteredWifiBackup"
    const val allowManualMobileDataUploadKey: String = "allowManualMobileDataUpload"

    fun isWifiOnlyBackupEnabled(): Boolean = getBoolean(isWifiOnlyBackupEnabledKey, true)
    fun setWifiOnlyBackupEnabled(enabled: Boolean) = edit { putBoolean(isWifiOnlyBackupEnabledKey, enabled) }

    fun allowMeteredWifiBackup(): Boolean = getBoolean(allowMeteredWifiBackupKey, false)
    fun setAllowMeteredWifiBackup(allowed: Boolean) = edit { putBoolean(allowMeteredWifiBackupKey, allowed) }

    fun allowManualMobileDataUpload(): Boolean = getBoolean(allowManualMobileDataUploadKey, false)
    fun setAllowManualMobileDataUpload(allowed: Boolean) = edit { putBoolean(allowManualMobileDataUploadKey, allowed) }

    private const val prefFile: String = "preferences"
    private const val encryptedPrefFile: String = "encryptedPreferences"
    @androidx.annotation.VisibleForTesting
    var allowUnencryptedFallbackForTesting: Boolean = false

    private fun isTestEnvironment(): Boolean {
        if (allowUnencryptedFallbackForTesting) return true
        return try {
            Class.forName("org.robolectric.Robolectric") != null ||
            Build.FINGERPRINT == "robolectric" ||
            Build.HARDWARE == "robolectric"
        } catch (_: Throwable) {
            false
        }
    }

    private lateinit var preferences: SharedPreferences
    private lateinit var encryptedPreferences: SharedPreferences

    private val observableStringPreferences = mutableMapOf<String, MutableStateFlow<String>>()
    private lateinit var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener

    fun init(context: Context) {
        try {
            preferences = context.getSharedPreferences(prefFile, Context.MODE_PRIVATE)
            val keyGenParameterSpec = MasterKeys.AES256_GCM_SPEC
            val masterKeyAlias = MasterKeys.getOrCreate(keyGenParameterSpec)
            encryptedPreferences = EncryptedSharedPreferences.create(
                encryptedPrefFile,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            migrateToEncrypted(preferences.all.filterKeys { it == botToken || it == channelId })

            // Initialize StateFlows with current values for observable keys
            observableStringPreferences[glideSelectionBehaviorKey] = MutableStateFlow(getString(glideSelectionBehaviorKey, "Fixed"))

            // Register a listener for SharedPreferences changes to update StateFlows
            // Store the listener reference to prevent garbage collection
            preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                when (key) {
                    glideSelectionBehaviorKey -> {
                        // Use the correct default value when updating the flow
                        val newValue = sharedPreferences.getString(key, "Fixed") ?: "Fixed"
                        observableStringPreferences[key]?.update { newValue }
                    }
                    // Handle other observable keys here if needed
                }
            }
            preferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        } catch (e: Throwable) {
            if (isTestEnvironment()) {
                Log.w("Preferences", "Test environment detected: falling back to unencrypted storage for testing: ${e.message}")
                try {
                    encryptedPreferences = context.getSharedPreferences(encryptedPrefFile, Context.MODE_PRIVATE)
                } catch (_: Throwable) { }
            } else {
                Log.e("Preferences", "KeyStore/EncryptedSharedPreferences init failed in production environment!", e)
                throw SecurityException("EncryptedSharedPreferences failed to initialize in production. Production credentials cannot fall back to unencrypted storage.", e)
            }
        }
    }

    fun getBoolean(key: String, defValue: Boolean) = preferences.getBoolean(key, defValue)
    fun getString(key: String, defValue: String) = preferences.getString(key, defValue) ?: defValue
    fun getFloat(key: String, defValue: Float) = preferences.getFloat(key, defValue)
    fun getLong(key: String, defValue: Long): Long {
        return try {
            preferences.getLong(key, defValue)
        } catch (e: ClassCastException) {
            try {
                val stringValue = preferences.getString(key, null)
                val longValue = stringValue?.toLongOrNull()
                if (longValue != null) {
                    // Fix the preference type
                    edit { putLong(key, longValue) }
                    longValue
                } else {
                    Log.e("Preferences", "Error getting long for key $key", e)
                    edit { remove(key) }
                    defValue
                }
            } catch (e2: Exception) {
                Log.e("Preferences", "Error recovering long for key $key", e2)
                edit { remove(key) }
                defValue
            }
        }
    }
    fun getInt(key: String, defValue: Int) = preferences.getInt(key, defValue)
    fun getStringSet(key: String, defValue: Set<String>) =
        preferences.getStringSet(key, defValue) ?: defValue

    fun getExcludedBucketNames(): Set<String> = getStringSet(excludedBucketNamesKey, emptySet())

    fun setExcludedBucketNames(names: Set<String>) {
        edit { putStringSet(excludedBucketNamesKey, names) }
    }

    fun getSyncMode(): String = getString(syncModeKey, SYNC_MODE_ALL)

    fun setSyncMode(mode: String) {
        edit { putString(syncModeKey, mode) }
    }

    fun getSyncNewOnlyTimestamp(): Long = getLong(syncNewOnlyTimestampKey, 0L)

    fun setSyncNewOnlyTimestamp(timestamp: Long) {
        edit { putLong(syncNewOnlyTimestampKey, timestamp) }
    }

    fun getStringFlow(key: String, defValue: String): StateFlow<String> {
        // Ensure the flow is initialized. If not, create it and populate with current value.
        // This handles cases where a flow might be requested before init() or for a new key.
        return observableStringPreferences.getOrPut(key) {
            MutableStateFlow(getString(key, defValue))
        }.asStateFlow()
    }

    fun edit(action: SharedPreferences.Editor.() -> Unit) {
        preferences.edit().apply(action).apply()
    }

    fun getEncryptedBoolean(key: String, defValue: Boolean) = encryptedPreferences.getBoolean(key, defValue)
    fun getEncryptedString(key: String, defValue: String) = encryptedPreferences.getString(key, defValue) ?: defValue
    fun getEncryptedFloat(key: String, defValue: Float) = encryptedPreferences.getFloat(key, defValue)
    fun getEncryptedLong(key: String, defValue: Long) = encryptedPreferences.getLong(key, defValue)
    fun getEncryptedStringSet(key: String, defValue: Set<String>) =
        encryptedPreferences.getStringSet(key, defValue) ?: defValue

    fun editEncrypted(action: SharedPreferences.Editor.() -> Unit) {
        encryptedPreferences.edit().apply(action).apply()
    }

    fun migrateAllToEncrypted(context: Context) {
        if (preferences.all.isNotEmpty()) {
            migrateToEncrypted(preferences.all)
        }
    }

    private fun migrateToEncrypted(keys: Map<String, *>) {
        for ((key, value1) in keys) {
            val value = value1!!
            if (key == botToken) {
                Log.d("map values", "$key: [REDACTED]")
            } else {
                Log.d("map values", "$key: $value")
            }
            when (value) {
                is Int -> {
                    editEncrypted { putInt(key, value) }
                    edit { remove(key) }
                }

                is Boolean -> {
                    editEncrypted { putBoolean(key, value) }
                    edit { remove(key) }
                }

                is Long -> {
                    editEncrypted { putLong(key, value) }
                    edit { remove(key) }
                }

                is Float -> {
                    editEncrypted { putFloat(key, value) }
                    edit { remove(key) }
                }

                else -> {
                    editEncrypted { putString(key, value.toString()) }
                    edit { remove(key) }
                }
            }
        }
    }
}
