package com.gallerysync.app

import android.app.Application

import com.gallerysync.app.api.BotApi
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.mediastore.LocalPhotoSource
import com.gallerysync.app.utils.coil.ImageLoaderModule
import com.gallerysync.app.utils.connectivity.ConnectivityObserver
import com.gallerysync.app.workers.WorkModule
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import android.graphics.Bitmap
import coil.Coil
import com.gallerysync.app.utils.NotificationHelper
import com.gallerysync.app.data.upload.UploadQueueRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File


class App : Application(), androidx.work.Configuration.Provider {

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        Preferences.init(applicationContext)
        Preferences.getOrCreateDeviceId() // Generate device ID on first launch
        DbHolder.create(applicationContext)

        try {
            WorkModule.create(applicationContext)
        } catch (e: Exception) {
            android.util.Log.w("App", "WorkModule init skipped in headless environment: ${e.message}")
        }
        ImageLoaderModule.create(applicationContext)
        ConnectivityObserver.init(applicationContext)
        BotApi.create()

        val imageLoader = ImageLoader.Builder(this)
            .crossfade(false)                     // disable global crossfade
            .allowHardware(false)                 // prefer software decoding for consistent scroll
            .bitmapConfig(Bitmap.Config.ARGB_8888) // default high quality
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizePercent(0.05) // up to 5% of disk
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // up to 25% of app memory
                    .build()
            }
            .components {
                add(coil.decode.VideoFrameDecoder.Factory())
            }
            .build()
        Coil.setImageLoader(imageLoader)
        NotificationHelper.createNotificationChannels(this)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.gallerysync.app.data.localdb.recovery.InstallRecoveryManager.checkAndInitializeBaseline(applicationContext)
            } catch (e: Exception) {
                android.util.Log.e("App", "Error checking baseline on startup", e)
            }
            try {
                UploadQueueRepository.recoverStaleUploads()
            } catch (e: Exception) {
                android.util.Log.e("App", "Error recovering stale uploads on startup", e)
            }
        }
    }

    }
