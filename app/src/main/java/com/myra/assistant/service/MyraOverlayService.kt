package com.myra.assistant.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.myra.assistant.R
import com.myra.assistant.data.SecurePrefs
import com.myra.assistant.ui.main.MainActivity
import com.myra.assistant.ui.main.OrbAnimationView
import kotlin.math.abs

/** Foreground service that owns the floating orb and the double-power-press detector. */
class MyraOverlayService : Service() {

    companion object {
        @Volatile var isRunning = false
        const val CHANNEL_ID = MyraNotifications.CH_FG
        const val ACTION_SHOW_OVERLAY = "com.myra.SHOW_OVERLAY"
        private const val NOTIF_ID = 1001
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val powerReceiver = PowerButtonReceiver { showOverlay() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        MyraNotifications.ensureChannels(this)
        MyraNotifications.startFg(
            this, NOTIF_ID,
            MyraNotifications.foreground(this, "MYRA is active", "Double-press power to show MYRA")
        )
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(powerReceiver, f)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_OVERLAY) showOverlay()
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlayView != null) return
        if (!SecurePrefs.enabled) return
        if (!Settings.canDrawOverlays(this)) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_orb, null)
        val dm = resources.displayMetrics
        val size = (160 * dm.density).toInt()

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - size) / 2
            y = (dm.heightPixels - size) / 2
        }

        view.findViewById<OrbAnimationView>(R.id.overlayOrb).setState(OrbAnimationView.State.LISTENING)
        view.findViewById<View>(R.id.overlayClose).setOnClickListener { hideOverlay() }

        view.setOnTouchListener(object : View.OnTouchListener {
            var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        ix = lp.x; iy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - tx).toInt()
                        val dy = (e.rawY - ty).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) moved = true
                        lp.x = ix + dx
                        lp.y = iy + dy
                        wm.updateViewLayout(view, lp)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            startActivity(
                                Intent(this@MyraOverlayService, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            )
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            wm.addView(view, lp)
            overlayView = view
        } catch (_: Exception) {
        }
    }

    private fun hideOverlay() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }

    override fun onDestroy() {
        isRunning = false
        hideOverlay()
        try { unregisterReceiver(powerReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
