package com.rokid.glass.mediastream.sender.permission

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPermissionCoordinatorTest {
    @Test
    fun selected_media_maps_only_to_its_dangerous_permissions() {
        assertEquals(
            listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            requiredMediaPermissions(videoEnabled = true, audioEnabled = true),
        )
        assertEquals(
            listOf(Manifest.permission.CAMERA),
            requiredMediaPermissions(videoEnabled = true, audioEnabled = false),
        )
        assertEquals(
            listOf(Manifest.permission.RECORD_AUDIO),
            requiredMediaPermissions(videoEnabled = false, audioEnabled = true),
        )
        assertEquals(
            emptyList<String>(),
            requiredMediaPermissions(videoEnabled = false, audioEnabled = false),
        )
    }

    @Test
    fun all_granted_continues_immediately_without_launching_a_dialog() {
        val launcher = FakePermissionLauncher()
        val coordinator = MediaPermissionCoordinator(
            permissionChecker = PermissionGrantChecker { true },
            permissionLauncher = launcher,
        )
        var granted = 0

        val accepted = coordinator.request(
            videoEnabled = true,
            audioEnabled = true,
            onGranted = { granted += 1 },
            onDenied = { error("must not deny") },
        )

        assertTrue(accepted)
        assertEquals(1, granted)
        assertEquals(0, launcher.launches.size)
    }

    @Test
    fun denial_never_invokes_the_granted_action() {
        val launcher = FakePermissionLauncher()
        val coordinator = MediaPermissionCoordinator(
            permissionChecker = PermissionGrantChecker { false },
            permissionLauncher = launcher,
        )
        var granted = 0
        var denied = 0

        coordinator.request(
            videoEnabled = true,
            audioEnabled = false,
            onGranted = { granted += 1 },
            onDenied = { denied += 1 },
        )
        coordinator.onPermissionResult(mapOf(Manifest.permission.CAMERA to false))

        assertEquals(0, granted)
        assertEquals(1, denied)
        assertEquals(listOf(Manifest.permission.CAMERA), launcher.launches.single())
    }

    @Test
    fun overlapping_request_is_rejected_without_replacing_the_pending_action() {
        val launcher = FakePermissionLauncher()
        val coordinator = MediaPermissionCoordinator(
            permissionChecker = PermissionGrantChecker { false },
            permissionLauncher = launcher,
        )
        var firstGranted = 0
        var secondGranted = 0

        assertTrue(
            coordinator.request(
                videoEnabled = true,
                audioEnabled = false,
                onGranted = { firstGranted += 1 },
                onDenied = {},
            ),
        )
        assertFalse(
            coordinator.request(
                videoEnabled = false,
                audioEnabled = true,
                onGranted = { secondGranted += 1 },
                onDenied = {},
            ),
        )
        coordinator.onPermissionResult(mapOf(Manifest.permission.CAMERA to true))

        assertEquals(1, firstGranted)
        assertEquals(0, secondGranted)
        assertEquals(1, launcher.launches.size)
    }

    @Test
    fun pending_request_blocks_a_second_action_even_when_its_permission_is_already_granted() {
        val launcher = FakePermissionLauncher()
        val coordinator = MediaPermissionCoordinator(
            permissionChecker = PermissionGrantChecker { permission ->
                permission == Manifest.permission.RECORD_AUDIO
            },
            permissionLauncher = launcher,
        )
        var firstGranted = 0
        var secondGranted = 0

        assertTrue(
            coordinator.request(
                videoEnabled = true,
                audioEnabled = false,
                onGranted = { firstGranted += 1 },
                onDenied = {},
            ),
        )
        assertFalse(
            coordinator.request(
                videoEnabled = false,
                audioEnabled = true,
                onGranted = { secondGranted += 1 },
                onDenied = {},
            ),
        )

        coordinator.onPermissionResult(mapOf(Manifest.permission.CAMERA to true))

        assertEquals(1, firstGranted)
        assertEquals(0, secondGranted)
        assertEquals(listOf(Manifest.permission.CAMERA), launcher.launches.single())
    }

    private class FakePermissionLauncher : PermissionLauncher {
        val launches = mutableListOf<List<String>>()

        override fun launch(permissions: Array<String>) {
            launches += permissions.toList()
        }
    }
}
