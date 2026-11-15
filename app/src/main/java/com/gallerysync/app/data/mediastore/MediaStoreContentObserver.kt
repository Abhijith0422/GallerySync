package com.gallerysync.app.data.mediastore

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Observes MediaStore changes while the application process is active.
 * Debounces rapid-fire events (e.g., camera bursts or multi-file transfers)
 * before invoking [onMediaChanged].
 */
class MediaStoreContentObserver(
    private val coroutineScope: CoroutineScope,
    private val debounceMs: Long = 1500L,
    private val onMediaChanged: () -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private var debounceJob: Job? = null

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        Log.d(TAG, "MediaStore change detected: uri=$uri, selfChange=$selfChange")
        debounceJob?.cancel()
        debounceJob = coroutineScope.launch {
            delay(debounceMs)
            Log.d(TAG, "Debounce timer expired, triggering onMediaChanged")
            onMediaChanged()
        }
    }

    companion object {
        private const val TAG = "MediaStoreObserver"

        /**
         * Registers the observer on external MediaStore files collection with descendant notifications.
         */
        fun register(context: Context, observer: MediaStoreContentObserver) {
            try {
                val resolver = context.contentResolver
                resolver.registerContentObserver(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    true,
                    observer
                )
                Log.d(TAG, "Registered MediaStoreContentObserver on external files")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register MediaStoreContentObserver", e)
            }
        }

        /**
         * Unregisters the observer from ContentResolver.
         */
        fun unregister(context: Context, observer: MediaStoreContentObserver) {
            try {
                context.contentResolver.unregisterContentObserver(observer)
                Log.d(TAG, "Unregistered MediaStoreContentObserver")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister MediaStoreContentObserver", e)
            }
        }
    }
}
