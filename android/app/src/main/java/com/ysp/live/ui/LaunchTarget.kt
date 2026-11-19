package com.ysp.live.ui

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

object LaunchTarget {
    fun activityClass(context: Context): Class<out Activity> {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            uiMode == Configuration.UI_MODE_TYPE_TELEVISION
        return if (isTv) TvMainActivity::class.java else PhoneMainActivity::class.java
    }
}

