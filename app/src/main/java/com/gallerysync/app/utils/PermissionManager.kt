package com.gallerysync.app.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Represents the granular media permission access levels on Android 14+ / Android 15.
 */
sealed class MediaAccessState {
    /**
     * Both images and videos are fully accessible (READ_MEDIA_IMAGES and READ_MEDIA_VIDEO granted).
     * This is the preferred state for complete automatic gallery backup.
     */
    data object FullAccess : MediaAccessState()

    /**
     * User granted access to selected photos and videos via Android 14+ Selected Photos access
     * (READ_MEDIA_VISUAL_USER_SELECTED is granted while full permissions are not).
     */
    data object PartialAccess : MediaAccessState()

    /**
     * Only images are accessible (READ_MEDIA_IMAGES is granted, READ_MEDIA_VIDEO is not).
     */
    data object ImagesOnlyAccess : MediaAccessState()

    /**
     * Only videos are accessible (READ_MEDIA_VIDEO is granted, READ_MEDIA_IMAGES is not).
     */
    data object VideosOnlyAccess : MediaAccessState()

    /**
     * No media permissions are granted.
     */
    data object NoAccess : MediaAccessState()

    val hasAnyMediaAccess: Boolean
        get() = this !is NoAccess

    val isFullAccess: Boolean
        get() = this is FullAccess

    val isPartialAccess: Boolean
        get() = this is PartialAccess
}

/**
 * Centralized manager for checking and requesting Android 15 media and notification permissions.
 */
object PermissionManager {

    /**
     * Evaluates current media access state across images, videos, and partial selection.
     */
    fun getMediaAccessState(context: Context): MediaAccessState {
        val hasImages = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED

        val hasVideos = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VIDEO
        ) == PackageManager.PERMISSION_GRANTED

        val hasVisualUserSelected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        return when {
            hasImages && hasVideos -> MediaAccessState.FullAccess
            hasImages && !hasVideos -> MediaAccessState.ImagesOnlyAccess
            !hasImages && hasVideos -> MediaAccessState.VideosOnlyAccess
            hasVisualUserSelected -> MediaAccessState.PartialAccess
            else -> MediaAccessState.NoAccess
        }
    }

    /**
     * Returns true if both images and videos are accessible without restrictions.
     */
    fun hasFullMediaAccess(context: Context): Boolean {
        return getMediaAccessState(context) == MediaAccessState.FullAccess
    }

    /**
     * Returns true if user granted access only to selected media items.
     */
    fun hasPartialMediaAccess(context: Context): Boolean {
        return getMediaAccessState(context) is MediaAccessState.PartialAccess
    }

    /**
     * Returns true if the app has permission to read at least some media (full or partial).
     */
    fun hasAnyMediaAccess(context: Context): Boolean {
        return getMediaAccessState(context).hasAnyMediaAccess
    }

    /**
     * Checks if notification permission is granted.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    /**
     * Returns the array of permissions needed to request full media access on Android 14 / 15.
     */
    fun getMediaPermissionsToRequest(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            emptyArray()
        }
    }

    /**
     * Returns notification permission array to request.
     */
    fun getNotificationPermissionToRequest(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }
    }

    /**
     * Opens application settings details page where user can grant permissions.
     */
    fun openAppSettings(context: Context) {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(this)
        }
    }
}
