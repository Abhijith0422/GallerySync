package com.gallerysync.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.debug.DatabaseDebugHelper
import com.gallerysync.app.ui.main.MainPage
import com.gallerysync.app.ui.main.MainViewModel
import com.gallerysync.app.ui.main.nav.screenScopedViewModel
import com.gallerysync.app.ui.onboarding.OnboardingPage
import com.gallerysync.app.ui.permission.PermissionDialogScreen
import com.gallerysync.app.ui.permission.PermissionViewModel
import com.gallerysync.app.data.mediastore.MediaStoreContentObserver
import com.gallerysync.app.ui.theme.AppTheme
import com.gallerysync.app.utils.NotificationHelper
import com.gallerysync.app.workers.WorkModule
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var startDestination by mutableStateOf(ScreenFlow.Onboarding.route)

    private fun hasMediaAccess(): Boolean =
        com.gallerysync.app.utils.PermissionManager.hasAnyMediaAccess(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Initialize notification channels
        NotificationHelper.createNotificationChannels(this)

        // Debug database state on app startup
        lifecycleScope.launch {
            DatabaseDebugHelper.debugDatabaseState(this@MainActivity)
        }

        // Initialize hash backfill for content dedup
        initializeHashBackfill()

        // Initialize cloud photo sync for background channel scanning
        initializeCloudPhotoSync()

        // Start daily database backup
        WorkModule.DailyDatabaseBackup.enqueuePeriodic()

        setContent {
            AppTheme {
                val topNavController = rememberNavController()
                NavHost(navController = topNavController, startDestination = startDestination) {
                    composable(ScreenFlow.Onboarding.route) {
                        OnboardingPage(onProceed = {
                            val navigateToRoute = if (hasMediaAccess()) {
                                ScreenFlow.Main.route
                            } else {
                                ScreenFlow.Permission.route
                            }
                            topNavController.navigate(navigateToRoute) {
                                popUpTo(ScreenFlow.Onboarding.route) { inclusive = true }
                            }
                        })
                    }
                    composable(ScreenFlow.Main.route) {
                        val viewModel: MainViewModel = screenScopedViewModel()
                        MainPage(viewModel)
                    }
                    dialog(ScreenFlow.Permission.route) {
                        val viewModel: PermissionViewModel = screenScopedViewModel()
                        PermissionDialogScreen(
                            onContinue = {
                                topNavController.navigate(ScreenFlow.Main.route) {
                                    popUpTo(ScreenFlow.Permission.route) { inclusive = true }
                                }
                            },
                            onOpenSettings = {
                                com.gallerysync.app.utils.PermissionManager.openAppSettings(this@MainActivity)
                            },
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }

    private var mediaStoreObserver: MediaStoreContentObserver? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onStart() {
        super.onStart()
        if (hasMediaAccess()) {
            registerMediaStoreObserver()
        }
        registerNetworkCallback()
    }

    override fun onStop() {
        super.onStop()
        unregisterMediaStoreObserver()
        unregisterNetworkCallback()
    }

    private fun registerNetworkCallback() {
        if (networkCallback == null) {
            networkCallback = com.gallerysync.app.data.upload.SyncNetworkPolicy.registerNetworkCallback(this) {
                if (Preferences.isTelegramConfigured() && Preferences.getBoolean(Preferences.isAutoBackupEnabledKey, true)) {
                    Log.i("MainActivity", "Eligible Wi-Fi detected -> requesting queue processing")
                    WorkModule.PeriodicBackup.enqueue()
                }
            }
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            com.gallerysync.app.data.upload.SyncNetworkPolicy.unregisterNetworkCallback(this, it)
            networkCallback = null
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasMediaAccess() && mediaStoreObserver == null) {
            registerMediaStoreObserver()
        }
        startDestination = when {
            Preferences.getEncryptedLong(Preferences.channelId, 0) == 0L -> ScreenFlow.Onboarding.route
            !hasMediaAccess() -> ScreenFlow.Permission.route
            else -> ScreenFlow.Main.route
        }
    }

    private fun registerMediaStoreObserver() {
        if (mediaStoreObserver == null) {
            mediaStoreObserver = MediaStoreContentObserver(lifecycleScope) {
                Log.d("MainActivity", "Debounced MediaStore change detected -> enqueue instant sync")
                if (hasMediaAccess()) {
                    WorkModule.SyncMediaStore.enqueueInstant()
                }
            }
            mediaStoreObserver?.let { observer ->
                MediaStoreContentObserver.register(this, observer)
            }
        }
    }

    private fun unregisterMediaStoreObserver() {
        mediaStoreObserver?.let { observer ->
            MediaStoreContentObserver.unregister(this, observer)
            mediaStoreObserver = null
        }
    }

    /**
     * Initialize hash backfill worker for content-based dedup
     */
    private fun initializeHashBackfill() {
        lifecycleScope.launch {
            try {
                // Start hash backfill for existing photos without hashes
                WorkModule.HashBackfill.enqueue()

                // Clean up old upload queue entries
                WorkModule.UploadQueueCleanup.cleanup()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error initializing hash backfill", e)
            }
        }
    }

    /**
     * Initialize cloud photo sync workers for periodic channel scanning
     */
    private fun initializeCloudPhotoSync() {
        lifecycleScope.launch {
            try {
                // Start periodic cloud photo sync (daily)
                WorkModule.CloudPhotoSync.enqueue()

                // Start quick sync (every 6 hours)
                WorkModule.QuickCloudSync.enqueue()

                // Trigger an immediate one-time sync if this is a fresh install
                // or if it's been a while since last sync
                val lastSyncTime = try {
                    Preferences.getLong("last_cloud_photo_sync_timestamp", 0L)
                } catch (e: ClassCastException) {
                    Log.w("MainActivity", "Invalid sync timestamp format, resetting to 0", e)
                    Preferences.edit { remove("last_cloud_photo_sync_timestamp") }
                    0L
                }
                val daysSinceLastSync = (System.currentTimeMillis() - lastSyncTime) / (1000 * 60 * 60 * 24)

                if (lastSyncTime == 0L || daysSinceLastSync > 1) {
                    WorkModule.CloudPhotoSync.enqueueOneTime()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error initializing cloud photo sync", e)
            }
        }
    }
}

sealed class ScreenFlow(val route: String) {
    data object Onboarding : ScreenFlow("onboard")
    data object Main : ScreenFlow("main")
    data object Permission : ScreenFlow("permission")
}

fun Activity.openAppSettings() {
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)
    ).also(::startActivity)
}
