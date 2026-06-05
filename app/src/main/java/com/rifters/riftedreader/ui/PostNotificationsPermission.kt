package com.rifters.riftedreader.ui

internal const val POST_NOTIFICATIONS_MIN_SDK = 33

internal fun shouldRequestPostNotificationsPermission(
    sdkInt: Int,
    isGranted: Boolean,
): Boolean = sdkInt >= POST_NOTIFICATIONS_MIN_SDK && !isGranted
