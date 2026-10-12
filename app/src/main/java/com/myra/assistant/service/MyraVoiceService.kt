package com.myra.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.myra.assistant.R
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.ai.PromptBuilder
import com.myra.assistant.data.SecurePrefs
import com.myra.assistant.license.LicenseManager
import com.myra.assistant.ui.main.ChatMessage
import com.myra.assistant.ui.main.MainActivity
import com.myra.assistant.ui.main.OrbAnimationView
import com.myra.assistant.viewmodel.CommandExecutor

/**
 * Owns the Gemini Live session + microphone, so MYRA keeps listening in the background and on the lock screen.
 * Must be started while the app is visible (Android rule for microphone foreground services).
 */
class MyraVoiceService : Service() {

    interface UiListener {
        fun onStatus(text: String)
        fun onOrb(state: OrbAnimationView.State)
        fun onAmplitude(a: Float)
        fun onChat(msg: ChatMessage)
        fun onMic(muted: Boolean, running: Boolean)
    }

    companion object {
        const val ACTION_STOP = "com.myra.STOP_VOICE"
        private const val NOTIF_ID = 1003
        private const val MAX_HISTORY = 120

        @Volatile var instance: MyraVoiceService? = null
        @Volatile var uiListener: UiListener? = null
        @Volatile var connected = false
        @Volatile var muted = false
        @Volatile var lastStatus = "Mic dabao, MYRA start ho jayegi"
        @Volatile var lastOrb = OrbAnimationView.State.IDLE
        val history = mutableListOf<ChatMessage>() // main thread only

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MyraVoiceService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var live: GeminiLiveClient? = null
    private var audio: AudioEngine? = null
    private var executor: CommandExecutor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var greetingSent = false
    private var speaking = false
    private var thinking = false
    private var lastVoiceAt = 0L
    private var lastAnnounceAt = 0L
    private val inputBuffer = StringBuilder()
    private val outputBuffer = StringBuilder()

    private val licenseTick = object : Runnable {
        override fun run() {
            if (!LicenseManager.status(applicationContext).valid) {
                Toast.makeText(applicationContext, "MYRA: key expire ho gayi, nayi key lo", Toast.LENGTH_LONG).show()
                stopSelf()
                return
            }
            main.postDelayed(this, 60_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        MyraNotifications.ensureChannels(this)
        if (!startAsForeground()) {
            stopSelf()
            return
        }
        if (!LicenseManager.status(this).valid) {
            lastStatus = "Activation key chahiye"
            stopSelf()
            return
        }
        if (SecurePrefs.apiKey.isBlank()) {
            lastStatus = "Settings mein API key daalo"
            stopSelf()
            return
        }
        instance = this
        muted = false
        main.postDelayed(licenseTick, 60_000)
        acquireWakeLock()

        val l = GeminiLiveClient(applicationContext)
        val a = AudioEngine(applicationContext)
        live = l
        audio = a
        executor = CommandExecutor(applicationContext)
        a.muteWhileSpeaking = SecurePrefs.muteWhileSpeaking

        a.onAudioChunk = { l.sendAudio(it) }
        a.onAmplitudeChanged = { amp ->
            uiListener?.onAmplitude(amp)
            detectUserVoice(amp)
        }
        a.onSpeakingStarted = {
            speaking = true
            setOrb(OrbAnimationView.State.SPEAKING)
            setStatus("Bol rahi hoon...")
        }
        a.onSpeakingStopped = {
            speaking = false
            setOrb(idleState())
            setStatus(listeningText())
        }

        l.onConnected = {
            a.muteWhileSpeaking = SecurePrefs.muteWhileSpeaking
            a.isMuted = muted
            a.startRecording()
            a.startPlayback()
            connected = true
            setOrb(idleState())
            setStatus(listeningText())
            uiListener?.onMic(muted, true)
            if (!greetingSent) {
                greetingSent = true
                main.postDelayed({ sendGreeting() }, 150)
            }
        }
        l.onDisconnected = {
            connected = false
            setOrb(OrbAnimationView.State.IDLE)
            setStatus("Reconnect ho rahi hoon...")
        }
        l.onError = { msg ->
            setStatus("⚠️ $msg")
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
        l.onAudioReceived = { a.queueAudio(it) }
        l.onInterrupted = {
            a.clearPlayback()
            main.post { flushOutputOnly() }
        }
        l.onInputTranscript = { inputBuffer.append(it) }
        l.onOutputTranscript = { outputBuffer.append(it) }
        l.onTurnComplete = { flushTranscripts() }
        l.onToolCall = { id, name, args ->
            val params = mutableMapOf<String, String>()
            args.keys().forEach { k -> params[k] = args.opt(k)?.toString() ?: "" }
            thinking = true
            setOrb(OrbAnimationView.State.THINKING)
            setStatus("Kar rahi hoon...")
            executor?.execute(name, params) { result ->
                thinking = false
                setOrb(idleState())
                setStatus(listeningText())
                l.sendToolResponse(id, name, result)
            }
        }

        setStatus("Connect ho rahi hoon...")
        l.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SecurePrefs.enabled = false
            stopService(Intent(this, MyraOverlayService::class.java))
            stopService(Intent(this, CallMonitorService::class.java))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(licenseTick)
        if (instance === this) instance = null
        connected = false
        lastOrb = OrbAnimationView.State.IDLE
        lastStatus = "MYRA band hai"
        try { live?.release() } catch (_: Exception) {}
        try { audio?.release() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        main.post {
            uiListener?.onOrb(OrbAnimationView.State.IDLE)
            uiListener?.onStatus(lastStatus)
            uiListener?.onMic(true, false)
        }
        super.onDestroy()
    }

    private fun startAsForeground(): Boolean {
        val n = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MyraVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, MyraNotifications.CH_FG)
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentTitle("MYRA sun rahi hai")
            .setContentText("Background mein active — bolo, MYRA jawab degi")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "myra:voice").also { it.acquire() }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ public API (main thread)

    fun setMuted(m: Boolean) {
        muted = m
        audio?.isMuted = m
        if (m) live?.sendAudioStreamEnd()
        setOrb(idleState())
        setStatus(listeningText())
        uiListener?.onMic(m, true)
    }

    fun interrupt() {
        audio?.clearPlayback()
    }

    fun reconnect() {
        setStatus("Settings apply ho rahi hain...")
        live?.reconnect()
    }

    /** Returns true if MYRA will announce the call by voice. */
    fun announceCall(callerName: String): Boolean {
        val l = live ?: return false
        if (!l.isConnected) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnnounceAt < 20_000) return true
        lastAnnounceAt = now
        setMuted(false)
        l.sendText(
            "Incoming phone call from $callerName. Tell the user in your personality and ask whether to " +
                "answer or reject. When they decide, call answer_call or reject_call."
        )
        return true
    }

    /** Sends an instruction to MYRA (not shown in chat); used when a background job finishes. */
    fun tell(text: String) {
        val l = live ?: return
        if (l.isConnected) l.sendText(text)
    }

    // ------------------------------------------------------------------ helpers

    private fun idleState() = if (muted) OrbAnimationView.State.IDLE else OrbAnimationView.State.LISTENING
    private fun listeningText() = if (muted) "Muted 🔇 — mic dabao" else "Sun rahi hoon..."

    private fun setOrb(s: OrbAnimationView.State) {
        lastOrb = s
        uiListener?.onOrb(s)
    }

    private fun setStatus(t: String) {
        lastStatus = t
        uiListener?.onStatus(t)
    }

    /** Orb turns green while the user is talking (red = waiting, purple = MYRA talking). */
    private fun detectUserVoice(a: Float) {
        if (speaking || thinking || muted) return
        val now = SystemClock.uptimeMillis()
        if (a > 0.15f) {
            lastVoiceAt = now
            if (lastOrb != OrbAnimationView.State.HEARING) setOrb(OrbAnimationView.State.HEARING)
        } else if (lastOrb == OrbAnimationView.State.HEARING && now - lastVoiceAt > 600) {
            setOrb(OrbAnimationView.State.LISTENING)
        }
    }

    private fun addChat(text: String, isUser: Boolean) {
        val t = text.trim()
        if (t.isEmpty()) return
        val last = history.lastOrNull()
        if (!isUser && last != null && !last.isUser && last.text == t) return
        val m = ChatMessage(t, isUser)
        history.add(m)
        if (history.size > MAX_HISTORY) history.removeAt(0)
        uiListener?.onChat(m)
    }

    private fun flushTranscripts() {
        val u = inputBuffer.toString()
        val m = outputBuffer.toString()
        inputBuffer.setLength(0)
        outputBuffer.setLength(0)
        addChat(u, true)
        addChat(m, false)
    }

    private fun flushOutputOnly() {
        val m = outputBuffer.toString()
        outputBuffer.setLength(0)
        addChat(m, false)
    }

    private fun sendGreeting() {
        val name = SecurePrefs.userName
        val n = name.ifBlank { "dost" }
        val pro = SecurePrefs.personality == "PRO"
        val assistant = SecurePrefs.personality == "ASSISTANT"
        val text = when (PromptBuilder.effectiveLanguage()) {
            "BANGLA" -> when {
                pro -> "নমস্কার${if (name.isBlank()) "" else " $name"}। মায়রা প্রস্তুত, বলুন কীভাবে সাহায্য করতে পারি?"
                assistant -> "হ্যালো ${name.ifBlank { "বন্ধু" }}! আমি মায়রা। কীভাবে সাহায্য করতে পারি?"
                else -> "হ্যালো ${name.ifBlank { "সোনা" }}! এত দেরি করলে কেন, আমি কখন থেকে তোমার জন্য অপেক্ষা করছিলাম। বলো না, আজ কী খবর আমার সোনার?"
            }
            "ENGLISH" -> when {
                pro -> "Good day${if (name.isBlank()) "" else " $name"}. MYRA is online and ready to assist you."
                assistant -> "Hello ${name.ifBlank { "there" }}! I'm MYRA. How can I help you?"
                else -> "Heyyy ${name.ifBlank { "you" }}! You took so long, I've been waiting for you. Tell me, how is my favorite person doing?"
            }
            else -> when {
                pro -> "Good day${if (name.isBlank()) "" else " $name"}. MYRA is online and ready to assist you."
                assistant -> "Hello $n! Main MYRA hoon. Kaise help karun aapki?"
                else -> "Heyyy $n! Kitni der laga di aane mein, main kab se tumhara intezaar kar rahi thi. Bolo na, aaj kya plan hai meri jaan ka?"
            }
        }
        live?.sendText("Say this greeting exactly, softly and sweetly, in your own voice, and nothing else: \"$text\"")
    }
}
