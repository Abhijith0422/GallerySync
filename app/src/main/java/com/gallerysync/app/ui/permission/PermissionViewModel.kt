package com.gallerysync.app.ui.permission

import android.content.Context
import androidx.lifecycle.ViewModel
import com.gallerysync.app.utils.MediaAccessState
import com.gallerysync.app.utils.PermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PermissionViewModel : ViewModel() {

    private val _mediaAccessState = MutableStateFlow<MediaAccessState>(MediaAccessState.NoAccess)
    val mediaAccessState: StateFlow<MediaAccessState> = _mediaAccessState.asStateFlow()

    private val _hasNotificationPermission = MutableStateFlow(false)
    val hasNotificationPermission: StateFlow<Boolean> = _hasNotificationPermission.asStateFlow()

    private val _hasRequestedMedia = MutableStateFlow(false)
    val hasRequestedMedia: StateFlow<Boolean> = _hasRequestedMedia.asStateFlow()

    fun updatePermissionStates(context: Context) {
        _mediaAccessState.value = PermissionManager.getMediaAccessState(context)
        _hasNotificationPermission.value = PermissionManager.hasNotificationPermission(context)
    }

    fun onMediaPermissionsResult(result: Map<String, Boolean>, context: Context) {
        _hasRequestedMedia.value = true
        _mediaAccessState.value = PermissionManager.getMediaAccessState(context)
    }

    fun onNotificationPermissionResult(isGranted: Boolean, context: Context) {
        _hasNotificationPermission.value = isGranted || PermissionManager.hasNotificationPermission(context)
    }
}
