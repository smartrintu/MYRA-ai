package com.myra.assistant.ui.main

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.myra.assistant.R
import com.myra.assistant.ai.CommandParser
import com.myra.assistant.data.SecurePrefs
import com.myra.assistant.databinding.ActivityMainBinding
import com.myra.assistant.service.CallMonitorService
import com.myra.assistant.service.MyraOverlayService
import com.myra.assistant.service.MyraVoiceService
import com.myra.assistant.ui.settings.SettingsActivity
import com.myra.assistant.viewmodel.CommandExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** UI only. The microphone + Gemini session live in MyraVoiceService so they survive background/lock. */
class MainActivity : AppCompatActivity(), MyraVoiceService.UiListener {

    private lateinit var binding: ActivityMainBinding
    private val chatAdapter = ChatAdapter()
    private val executor by lazy { CommandExecutor(applicationContext) }

    private var askedOptional = false
    private var isInCallMode = false
    private var lastSyncedTs = 0L
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { afterPermissions() }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r -> if (r.resultCode == RESULT_OK) onSettingsSaved() }

    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            isInCallMode = false
            setStatus("Call khatam. Sun rahi hoon...")
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initViews()

        ContextCompat.registerReceiver(
            this, callEndedReceiver, IntentFilter(CallMonitorService.ACTION_CALL_ENDED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    updateStatusBar()
                    delay(30_000)
                }
            }
        }

        ensurePermissions()
        handleIncomingCallIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingCallIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        MyraVoiceService.uiListener = this
        syncFromService()
    }

    override fun onStop() {
        if (MyraVoiceService.uiListener === this) MyraVoiceService.uiListener = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val n = SecurePrefs.userName.trim()
        binding.subtitleText.text = if (n.isEmpty()) "AI COMPANION" else "${n.uppercase()}'S AI"
    }

    override fun onDestroy() {
        try { unregisterReceiver(callEndedReceiver) } catch (_: Exception) {}
        recognizer?.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UiListener (called on main thread)

    override fun onStatus(text: String) {
        binding.statusText.text = text
    }

    override fun onOrb(state: OrbAnimationView.State) {
        binding.orbView.setState(state)
        // red = waiting, green = you are talking, purple = MYRA talking, cyan = working
        val color = when (state) {
            OrbAnimationView.State.HEARING -> Color.parseColor("#00E676")
            OrbAnimationView.State.SPEAKING -> Color.parseColor("#D500F9")
            OrbAnimationView.State.THINKING -> Color.parseColor("#40C4FF")
            else -> Color.parseColor("#FF1744")
        }
        val active = state == OrbAnimationView.State.HEARING ||
            state == OrbAnimationView.State.SPEAKING ||
            state == OrbAnimationView.State.THINKING
        binding.redOverlay.setBackgroundColor(color)
        binding.redOverlay.animate().cancel()
        binding.redOverlay.animate().alpha(if (active) 0.10f else 0f)
            .setDuration(if (active) 250 else 450).start()
    }

    override fun onAmplitude(a: Float) {
        binding.waveformView.setAmplitude(a)
        binding.orbView.setAmplitude(a)
    }

    override fun onChat(msg: ChatMessage) {
        val n = chatAdapter.add(msg)
        lastSyncedTs = msg.timestamp
        if (n > 0) binding.chatRecycler.scrollToPosition(n - 1)
    }

    override fun onMic(muted: Boolean, running: Boolean) {
        binding.micButton.setImageResource(
            if (running && !muted) R.drawable.ic_mic_on else R.drawable.ic_mic_off
        )
    }

    private fun syncFromService() {
        val running = MyraVoiceService.instance != null
        onOrb(MyraVoiceService.lastOrb)
        onStatus(MyraVoiceService.lastStatus)
        onMic(MyraVoiceService.muted, running)
        MyraVoiceService.history.filter { it.timestamp > lastSyncedTs }.forEach { onChat(it) }
    }

    // ------------------------------------------------------------------ UI

    private fun initViews() {
        binding.chatRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.chatRecycler.adapter = chatAdapter
        binding.orbView.setState(OrbAnimationView.State.IDLE)

        binding.settingsBtn.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        binding.micButton.setOnClickListener {
            val svc = MyraVoiceService.instance
            when {
                svc == null -> startVoiceService()
                !MyraVoiceService.connected -> startOfflineListen()
                else -> svc.setMuted(!MyraVoiceService.muted)
            }
        }
        binding.micButton.setOnLongClickListener {
            MyraVoiceService.instance?.interrupt()
            setStatus("Ruk gayi 🤐")
            true
        }
    }

    private fun setStatus(text: String) {
        binding.statusText.text = text
    }

    private fun addChat(text: String, isUser: Boolean) {
        if (text.isBlank()) return
        onChat(ChatMessage(text.trim(), isUser))
    }

    private fun updateStatusBar() {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        binding.batteryText.text = "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.totalMem > 0) binding.ramText.text = "RAM ${100 - (mi.availMem * 100 / mi.totalMem)}%"
        binding.timeText.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    // ------------------------------------------------------------------ permissions

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (!askedOptional) {
            askedOptional = true
            val optional = mutableListOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.ANSWER_PHONE_CALLS
            )
            if (Build.VERSION.SDK_INT >= 33) optional.add(Manifest.permission.POST_NOTIFICATIONS)
            val missing = optional.filter { !has(it) }
            if (missing.isNotEmpty()) {
                permLauncher.launch(missing.toTypedArray())
                return
            }
        }
        onPermissionsReady()
    }

    private fun afterPermissions() {
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            setStatus("Mic permission chahiye 🎤")
            Toast.makeText(this, "Mic permission ke bina MYRA sun nahi sakti", Toast.LENGTH_LONG).show()
            return
        }
        ensurePermissions()
    }

    private fun onPermissionsReady() {
        startSystemServices()
        if (MyraVoiceService.instance == null) startVoiceService()
    }

    private fun startVoiceService() {
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            ensurePermissions()
            return
        }
        if (SecurePrefs.apiKey.isBlank()) {
            setStatus("Settings mein Gemini API key daalo")
            Toast.makeText(this, "Pehle Settings mein API key daalo", Toast.LENGTH_LONG).show()
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
            return
        }
        try {
            MyraVoiceService.start(this)
            setStatus("Connect ho rahi hoon...")
        } catch (e: Exception) {
            Toast.makeText(this, "Service start nahi hui: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startSystemServices() {
        try {
            if (has(Manifest.permission.READ_PHONE_STATE)) {
                ContextCompat.startForegroundService(this, Intent(this, CallMonitorService::class.java))
            }
            if (Settings.canDrawOverlays(this)) {
                ContextCompat.startForegroundService(this, Intent(this, MyraOverlayService::class.java))
            }
        } catch (_: Exception) {
        }
    }

    private fun onSettingsSaved() {
        val svc = MyraVoiceService.instance
        if (svc != null) svc.reconnect() else ensurePermissions()
    }

    // ------------------------------------------------------------------ incoming calls

    private fun handleIncomingCallIntent(i: Intent?) {
        if (i == null || !i.getBooleanExtra("INCOMING_CALL", false)) return
        val name = i.getStringExtra("CALLER_NAME") ?: "Unknown"
        i.removeExtra("INCOMING_CALL")
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val handled = MyraVoiceService.instance?.announceCall(name) ?: false
        if (!handled) {
            isInCallMode = true
            addChat("📞 $name ka call aa raha hai — bolo 'uthao' ya 'reject'", false)
            setStatus("📞 $name")
            startOfflineListen()
        }
    }

    // ------------------------------------------------------------------ offline fallback

    private fun startOfflineListen() {
        if (!has(Manifest.permission.RECORD_AUDIO)) { ensurePermissions(); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition available nahi hai", Toast.LENGTH_SHORT).show()
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { setStatus("Offline mode: bolo...") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { setStatus("Samajh nahi aaya, dobara try karo") }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) handleOfflineText(text)
            }
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (SecurePrefs.language == "BANGLA") "bn-IN" else "en-IN")
        )
    }

    private fun handleOfflineText(text: String) {
        addChat(text, true)
        val t = text.lowercase(Locale.getDefault())

        if (isInCallMode) {
            val accept = listOf("uthao", "utha", "haan", "accept", "answer", "receive", "ধরো", "উঠাও", "রিসিভ", "হ্যাঁ").any { t.contains(it) }
            val reject = listOf("reject", "nahi", "mat", "cut", "decline", "কাটো", "না", "রিজেক্ট").any { t.contains(it) }
            when {
                accept -> executor.execute("answer_call", emptyMap()) { addChat(it, false) }
                reject -> executor.execute("reject_call", emptyMap()) { addChat(it, false) }
                else -> addChat("Bolo 'uthao' ya 'reject'", false)
            }
            return
        }

        val cmd = CommandParser.parse(text)
        if (cmd == null) {
            addChat("Offline hoon — abhi sirf commands samajh sakti hoon (e.g. 'YouTube kholo')", false)
        } else {
            executor.execute(cmd.type, cmd.params) { addChat(it, false) }
        }
    }
}
