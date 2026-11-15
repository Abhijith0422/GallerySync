package com.gallerysync.app.ui.permission

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PermMedia
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.gallerysync.app.utils.MediaAccessState
import com.gallerysync.app.utils.PermissionManager

@Composable
fun PermissionDialogScreen(
    onContinue: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: PermissionViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val mediaAccessState by viewModel.mediaAccessState.collectAsState()
    val hasNotificationPermission by viewModel.hasNotificationPermission.collectAsState()
    val hasRequestedMedia by viewModel.hasRequestedMedia.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.updatePermissionStates(context)
    }

    // Media permissions launcher
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { perms ->
            viewModel.onMediaPermissionsResult(perms, context)
        }
    )

    // Notification permission launcher
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            viewModel.onNotificationPermissionResult(granted, context)
        }
    )

    // Check if permanently declined
    val isMediaPermanentlyDeclined = hasRequestedMedia &&
        mediaAccessState is MediaAccessState.NoAccess &&
        activity?.let { act ->
            val perms = PermissionManager.getMediaPermissionsToRequest()
            perms.isNotEmpty() && perms.all { perm ->
                !ActivityCompat.shouldShowRequestPermissionRationale(act, perm)
            }
        } == true

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header icon
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PermMedia,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Media & Permissions",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "GallerySync requires access to your photos and videos to back them up to your private Telegram channel.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Card 1: Media Access Status Card
            MediaStatusCard(
                mediaAccessState = mediaAccessState,
                isPermanentlyDeclined = isMediaPermanentlyDeclined,
                onRequestMedia = {
                    mediaPermissionLauncher.launch(PermissionManager.getMediaPermissionsToRequest())
                },
                onOpenSettings = onOpenSettings
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Card 2: Notifications Status Card
            NotificationStatusCard(
                hasNotificationPermission = hasNotificationPermission,
                onRequestNotifications = {
                    val notifPerms = PermissionManager.getNotificationPermissionToRequest()
                    if (notifPerms.isNotEmpty()) {
                        notificationPermissionLauncher.launch(notifPerms[0])
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Bottom Actions
            if (mediaAccessState.hasAnyMediaAccess) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = if (mediaAccessState is MediaAccessState.FullAccess) "Continue to Gallery" else "Continue with Accessible Media",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else if (isMediaPermanentlyDeclined) {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(imageVector = Icons.Rounded.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Open App Settings",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            } else {
                Button(
                    onClick = {
                        mediaPermissionLauncher.launch(PermissionManager.getMediaPermissionsToRequest())
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "Grant Media Access",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaStatusCard(
    mediaAccessState: MediaAccessState,
    isPermanentlyDeclined: Boolean,
    onRequestMedia: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val (containerColor, icon, title, description) = when (mediaAccessState) {
        is MediaAccessState.FullAccess -> Quadruple(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            Icons.Rounded.CheckCircle,
            "Full Media Access",
            "Full access granted. All gallery photos and videos can be automatically backed up."
        )
        is MediaAccessState.PartialAccess -> Quadruple(
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
            Icons.Rounded.Info,
            "Selected Media Access",
            "Access is limited to selected media items. Only accessible items will be backed up. Grant full access for automatic gallery-wide sync."
        )
        is MediaAccessState.ImagesOnlyAccess -> Quadruple(
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            Icons.Rounded.Warning,
            "Photos Only (Videos Missing)",
            "Photo access granted, but video access is not. Grant video permission to include videos in backup."
        )
        is MediaAccessState.VideosOnlyAccess -> Quadruple(
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            Icons.Rounded.Warning,
            "Videos Only (Photos Missing)",
            "Video access granted, but photo access is not. Grant photo permission to include photos in backup."
        )
        is MediaAccessState.NoAccess -> Quadruple(
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
            Icons.Rounded.Lock,
            "No Media Access",
            if (isPermanentlyDeclined) "Media permissions are permanently denied. Please enable them in app settings."
            else "Access to photos and videos is required to backup your gallery."
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Re-grant or upgrade button when not full access
            if (mediaAccessState !is MediaAccessState.FullAccess) {
                Spacer(modifier = Modifier.height(10.dp))
                if (isPermanentlyDeclined && mediaAccessState is MediaAccessState.NoAccess) {
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Settings")
                    }
                } else {
                    OutlinedButton(
                        onClick = onRequestMedia,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = when (mediaAccessState) {
                                is MediaAccessState.PartialAccess -> "Grant Full Gallery Access"
                                is MediaAccessState.ImagesOnlyAccess -> "Grant Video Access"
                                is MediaAccessState.VideosOnlyAccess -> "Grant Photo Access"
                                else -> "Grant Access"
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationStatusCard(
    hasNotificationPermission: Boolean,
    onRequestNotifications: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasNotificationPermission)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (hasNotificationPermission) Icons.Rounded.NotificationsActive else Icons.Rounded.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (hasNotificationPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Background Notifications",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (hasNotificationPermission)
                    "Notifications enabled. You will receive real-time updates for backup progress, completion, and network pause alerts."
                else
                    "Optional: Enable notifications to monitor backup progress, file upload completions, and retry notices in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!hasNotificationPermission) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onRequestNotifications,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Enable Notifications")
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
