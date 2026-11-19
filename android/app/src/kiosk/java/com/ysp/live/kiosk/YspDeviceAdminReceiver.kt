package com.ysp.live.kiosk

import android.app.admin.DeviceAdminReceiver

/**
 * Minimal admin receiver used only by the kiosk variant.
 * Device Owner provisioning is an explicit enterprise/dedicated-device step.
 */
class YspDeviceAdminReceiver : DeviceAdminReceiver()

