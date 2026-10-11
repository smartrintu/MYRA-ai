package com.myra.assistant.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.myra.assistant.R
import com.myra.assistant.data.SecurePrefs
import com.myra.assistant.ui.main.MainActivity

/**
 * Listens to PHONE_STATE (works on all API levels and delivers the number when READ_CALL_LOG is granted),
 * resolves the caller name and wakes MainActivity so MYRA can announce the call.
 */
class CallMonitorService : Service() {

    companion object {
        const val ACTION_CALL_ENDED = "com.myra.CALL_ENDED"
        private const val NOTIF_ID = 1002
        private const val CALL_NOTIF_ID = 2002
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var announced = false
    private var lastState = TelephonyManager.EXTRA_STATE_IDLE

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    if (announced) return
                    if (!number.isNullOrBlank()) {
                        cancelPending()
                        announced = true
                        announce(number)
                    } else if (pending == null) {
                        // Some devices send RINGING twice: first without number, then with it.
                        val r = Runnable {
                            pending = null
                            if (!announced) {
                                announced = true
                                announce(null)
                            }
                        }
                        pending = r
                        handler.postDelayed(r, 1200)
                    }
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> cancelPending()
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    cancelPending()
                    announced = false
                    if (lastState != TelephonyManager.EXTRA_STATE_IDLE) {
                        sendBroadcast(Intent(ACTION_CALL_ENDED).setPackage(packageName))
                        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(CALL_NOTIF_ID)
                    }
                }
            }
            lastState = state
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MyraNotifications.ensureChannels(this)
        MyraNotifications.startFg(
            this, NOTIF_ID,
            MyraNotifications.foreground(this, "MYRA call assistant", "Watching for incoming calls")
        )
        registerReceiver(receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun cancelPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    private fun announce(number: String?) {
        if (!SecurePrefs.enabled) return
        val name = if (number.isNullOrBlank()) "Unknown number" else (resolveCallerName(number) ?: number)

        val i = Intent(this, MainActivity::class.java)
            .putExtra("INCOMING_CALL", true)
            .putExtra("CALLER_NAME", name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        // Works only if the app is already visible; otherwise the full-screen notification below handles it.
        val handled = MyraVoiceService.instance?.announceCall(name) ?: false
        if (!handled) {
            try { startActivity(i) } catch (_: Exception) {}
        }

        val pi = PendingIntent.getActivity(
            this, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, MyraNotifications.CH_CALL)
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentTitle("Incoming call")
            .setContentText(name)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(CALL_NOTIF_ID, n)
        } catch (_: Exception) {
        }
    }

    private fun resolveCallerName(number: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        cancelPending()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
