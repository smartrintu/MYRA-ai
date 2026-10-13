package com.myra.assistant.ui.main

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.myra.assistant.data.SecurePrefs

/** Small vibration on taps. Obeys the "Haptic Feedback" switch in Aura Control. */
object Haptics {
    @Suppress("DEPRECATION")
    fun tap(ctx: Context, strong: Boolean = false) {
        if (!SecurePrefs.haptics) return
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createOneShot(if (strong) 30L else 14L, if (strong) 200 else 90))
        } catch (_: Exception) {
        }
    }
}
