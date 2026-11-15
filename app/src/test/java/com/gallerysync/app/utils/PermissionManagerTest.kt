package com.gallerysync.app.utils

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gallerysync.app.ui.permission.PermissionViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PermissionManagerTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val shadow = Shadows.shadowOf(app)
        shadow.denyPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }

    @Test
    fun testNoAccess_whenAllPermissionsDenied() {
        val state = PermissionManager.getMediaAccessState(app)
        assertEquals(MediaAccessState.NoAccess, state)
        assertFalse(PermissionManager.hasFullMediaAccess(app))
        assertFalse(PermissionManager.hasPartialMediaAccess(app))
        assertFalse(PermissionManager.hasAnyMediaAccess(app))
    }

    @Test
    fun testFullAccess_whenBothImagesAndVideosGranted() {
        val shadow = Shadows.shadowOf(app)
        shadow.grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )

        val state = PermissionManager.getMediaAccessState(app)
        assertEquals(MediaAccessState.FullAccess, state)
        assertTrue(PermissionManager.hasFullMediaAccess(app))
        assertFalse(PermissionManager.hasPartialMediaAccess(app))
        assertTrue(PermissionManager.hasAnyMediaAccess(app))
    }

    @Test
    fun testImagesOnlyAccess_whenOnlyImagesGranted() {
        val shadow = Shadows.shadowOf(app)
        shadow.grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        shadow.denyPermissions(Manifest.permission.READ_MEDIA_VIDEO)

        val state = PermissionManager.getMediaAccessState(app)
        assertEquals(MediaAccessState.ImagesOnlyAccess, state)
        assertFalse(PermissionManager.hasFullMediaAccess(app))
        assertFalse(PermissionManager.hasPartialMediaAccess(app))
        assertTrue(PermissionManager.hasAnyMediaAccess(app))
    }

    @Test
    fun testVideosOnlyAccess_whenOnlyVideosGranted() {
        val shadow = Shadows.shadowOf(app)
        shadow.grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        shadow.denyPermissions(Manifest.permission.READ_MEDIA_IMAGES)

        val state = PermissionManager.getMediaAccessState(app)
        assertEquals(MediaAccessState.VideosOnlyAccess, state)
        assertFalse(PermissionManager.hasFullMediaAccess(app))
        assertFalse(PermissionManager.hasPartialMediaAccess(app))
        assertTrue(PermissionManager.hasAnyMediaAccess(app))
    }

    @Test
    fun testPartialAccess_whenVisualUserSelectedGrantedWithoutFullAccess() {
        val shadow = Shadows.shadowOf(app)
        shadow.denyPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        shadow.grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

        val state = PermissionManager.getMediaAccessState(app)
        assertEquals(MediaAccessState.PartialAccess, state)
        assertFalse(PermissionManager.hasFullMediaAccess(app))
        assertTrue(PermissionManager.hasPartialMediaAccess(app))
        assertTrue(PermissionManager.hasAnyMediaAccess(app))
    }

    @Test
    fun testNotificationPermission_grantedAndDenied() {
        val shadow = Shadows.shadowOf(app)

        shadow.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(PermissionManager.hasNotificationPermission(app))

        shadow.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(PermissionManager.hasNotificationPermission(app))
    }

    @Test
    fun testPermissionsToRequestArrays() {
        val mediaPerms = PermissionManager.getMediaPermissionsToRequest()
        assertTrue(mediaPerms.contains(Manifest.permission.READ_MEDIA_IMAGES))
        assertTrue(mediaPerms.contains(Manifest.permission.READ_MEDIA_VIDEO))
        assertTrue(mediaPerms.contains(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

        val notifPerms = PermissionManager.getNotificationPermissionToRequest()
        assertTrue(notifPerms.contains(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun testPermissionViewModel_reactsToPermissionUpdates() {
        val viewModel = PermissionViewModel()
        val shadow = Shadows.shadowOf(app)

        viewModel.updatePermissionStates(app)
        assertEquals(MediaAccessState.NoAccess, viewModel.mediaAccessState.value)
        assertFalse(viewModel.hasNotificationPermission.value)

        // Grant full access
        shadow.grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS
        )
        viewModel.onMediaPermissionsResult(
            mapOf(
                Manifest.permission.READ_MEDIA_IMAGES to true,
                Manifest.permission.READ_MEDIA_VIDEO to true
            ),
            app
        )
        viewModel.onNotificationPermissionResult(true, app)

        assertEquals(MediaAccessState.FullAccess, viewModel.mediaAccessState.value)
        assertTrue(viewModel.hasNotificationPermission.value)
        assertTrue(viewModel.hasRequestedMedia.value)
    }

    @Test
    fun testPermissionRevocation_updatesStateToNoAccess() {
        val viewModel = PermissionViewModel()
        val shadow = Shadows.shadowOf(app)

        // Start with full access
        shadow.grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        viewModel.updatePermissionStates(app)
        assertEquals(MediaAccessState.FullAccess, viewModel.mediaAccessState.value)

        // Revoke permissions (simulating Android Settings toggle off)
        shadow.denyPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        viewModel.updatePermissionStates(app)
        assertEquals(MediaAccessState.NoAccess, viewModel.mediaAccessState.value)
        assertFalse(viewModel.mediaAccessState.value.hasAnyMediaAccess)
    }
}
