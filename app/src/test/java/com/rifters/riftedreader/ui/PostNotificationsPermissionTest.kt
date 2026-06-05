package com.rifters.riftedreader.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostNotificationsPermissionTest {

    @Test
    fun preTiramisu_neverRequestsPermission() {
        assertFalse(shouldRequestPostNotificationsPermission(sdkInt = 32, isGranted = false))
    }

    @Test
    fun grantedPermission_skipsRequestOnTiramisuAndAbove() {
        assertFalse(shouldRequestPostNotificationsPermission(sdkInt = 33, isGranted = true))
        assertFalse(shouldRequestPostNotificationsPermission(sdkInt = 35, isGranted = true))
    }

    @Test
    fun missingPermission_requestsOnTiramisuAndAbove() {
        assertTrue(shouldRequestPostNotificationsPermission(sdkInt = 33, isGranted = false))
        assertTrue(shouldRequestPostNotificationsPermission(sdkInt = 35, isGranted = false))
    }
}
