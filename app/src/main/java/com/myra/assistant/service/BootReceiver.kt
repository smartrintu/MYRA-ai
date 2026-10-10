package com.myra.assistant.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Restarts the call monitor after reboot (the microphone is never started from the background). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!ok) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, CallMonitorService::class.java))
        } catch (_: Exception) {
        }
    }
}
