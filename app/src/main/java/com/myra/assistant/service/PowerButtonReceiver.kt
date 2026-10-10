package com.myra.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Detects a "double power press": SCREEN_OFF followed by SCREEN_ON (or vice versa) within 600 ms.
 * Must be registered in code (SCREEN_ON/OFF cannot be declared in the manifest).
 */
class PowerButtonReceiver(private val onDoublePress: () -> Unit) : BroadcastReceiver() {
    private var lastAction: String? = null
    private var lastTime = 0L

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SCREEN_OFF && action != Intent.ACTION_SCREEN_ON) return
        val now = SystemClock.elapsedRealtime()
        if (lastAction != null && lastAction != action && now - lastTime < 600) {
            lastAction = null
            onDoublePress()
        } else {
            lastAction = action
            lastTime = now
        }
    }
}
