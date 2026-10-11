package com.myra.assistant.ui.main

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.myra.assistant.R
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.CommandParser
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.ai.PromptBuilder
import com.myra.assistant.data.SecurePrefs
import com.myra.assistant.databinding.ActivityMainBinding
import com.myra.assistant.service.CallMonitorService
import com.myra.assistant.service.MyraOverlayService
import com.myra.assistant.ui.settings.SettingsActivity
import com.myra.assistant.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val chatAdapter = ChatAdapter()

    private var geminiLive: GeminiLiveClient? = null
    private var audioEngine: AudioEngine? = null

    private var isMuted = false
    private var greetingSent = false
    private var askedOptional = false
    private var isInCallMode = false
    private val inputBuffer = StringBuilder()
    private val outputBuffer = StringBuilder()
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
            setActive(false)
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

    override fun onPause() {
        super.onPause()
        audioEngine?.isMuted = true
    }

    override fun onResume() {
        super.onResume()
        val n = SecurePrefs.userName.trim()
        binding.subtitleText.text = if (n.isEmpty()) "AI COMPANION" else "${n.uppercase()}'S AI"
        audioEngine?.isMuted = isMuted
    }

    override fun onDestroy() {
        try { unregisterReceiver(callEndedReceiver) } catch (_: Exception) {}
        recognizer?.destroy()
        geminiLive?.release()
        audioEngine?.release()
        super.onDestroy()
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
            val live = geminiLive
            if (live == null || !live.isConnected) {
                startOfflineListen()
                return@setOnClickListener
            }
            isMuted = !isMuted
            audioEngine?.isMuted = isMuted
            if (isMuted) live.sendAudioStreamEnd()
            updateMicUi()
        }
        binding.micButton.setOnLongClickListener {
            audioEngine?.clearPlayback()
            setStatus("Ruk gayi 🤐")
            true
        }
    }

    private fun setStatus(text: String) {
        binding.statusText.text = text
    }

    private fun updateMicUi() {
        binding.micButton.setImageResource(if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on)
        binding.orbView.setState(if (isMuted) OrbAnimationView.State.IDLE else OrbAnimationView.State.LISTENING)
        setStatus(if (isMuted) "Muted 🔇 — mic dabao" else "Sun rahi hoon...")
    }

    private fun setActive(active: Boolean) {
        binding.redOverlay.animate().cancel()
        binding.redOverlay.animate().alpha(if (active) 0.08f else 0f)
            .setDuration(if (active) 300 else 500).start()
    }

    private fun addChat(text: String, isUser: Boolean) {
        if (text.isBlank()) return
        val n = chatAdapter.add(ChatMessage(text.trim(), isUser))
        if (n > 0) binding.chatRecycler.scrollToPosition(n - 1)
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
        if (SecurePrefs.apiKey.isBlank()) {
            setStatus("Settings mein Gemini API key daalo")
            Toast.makeText(this, "Pehle Settings mein API key daalo", Toast.LENGTH_LONG).show()
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
            return
        }
        if (geminiLive == null) initGeminiLive()
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
        val live = geminiLive
        if (live != null) {
            setStatus("Settings apply ho rahi hain...")
            live.reconnect()
        } else {
            ensurePermissions()
        }
    }

    // ------------------------------------------------------------------ Gemini Live

    private fun initGeminiLive() {
        val live = GeminiLiveClient(applicationContext)
        val audio = AudioEngine(this)
        geminiLive = live
        audioEngine = audio
        audio.isMuted = isMuted
        audio.muteWhileSpeaking = SecurePrefs.muteWhileSpeaking

        audio.onAudioChunk = { live.sendAudio(it) }
        audio.onAmplitudeChanged = { a ->
            binding.waveformView.setAmplitude(a)
            binding.orbView.setAmplitude(a)
        }
        audio.onSpeakingStarted = {
            binding.orbView.setState(OrbAnimationView.State.SPEAKING)
            setActive(true)
            setStatus("Bol rahi hoon...")
        }
        audio.onSpeakingStopped = {
            binding.orbView.setState(if (isMuted) OrbAnimationView.State.IDLE else OrbAnimationView.State.LISTENING)
            setActive(false)
            setStatus(if (isMuted) "Muted 🔇 — mic dabao" else "Sun rahi hoon...")
        }

        live.onConnected = {
            audio.muteWhileSpeaking = SecurePrefs.muteWhileSpeaking
            audio.startRecording()
            audio.startPlayback()
            updateMicUi()
            if (!greetingSent) {
                greetingSent = true
                handler.postDelayed({ sendGreeting() }, 600)
            }
        }
        live.onDisconnected = {
            binding.orbView.setState(OrbAnimationView.State.IDLE)
            setStatus("Reconnect ho rahi hoon...")
        }
        live.onError = { msg ->
            setStatus("⚠️ $msg")
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        live.onAudioReceived = { audio.queueAudio(it) }
        live.onInterrupted = {
            audio.clearPlayback()
            runOnUiThread { flushOutputOnly() }
        }
        live.onInputTranscript = { inputBuffer.append(it) }
        live.onOutputTranscript = { outputBuffer.append(it) }
        live.onTurnComplete = { flushTranscripts() }
        live.onToolCall = { id, name, args ->
            val params = mutableMapOf<String, String>()
            args.keys().forEach { k -> params[k] = args.opt(k)?.toString() ?: "" }
            binding.orbView.setState(OrbAnimationView.State.THINKING)
            setStatus("Kar rahi hoon...")
            viewModel.execute(name, params) { result ->
                binding.orbView.setState(OrbAnimationView.State.LISTENING)
                live.sendToolResponse(id, name, result)
            }
        }

        setStatus("Connect ho rahi hoon...")
        live.connect()
    }

    private fun flushTranscripts() {
        val u = inputBuffer.toString().trim()
        val m = outputBuffer.toString().trim()
        inputBuffer.setLength(0)
        outputBuffer.setLength(0)
        if (u.isNotEmpty()) addChat(u, true)
        if (m.isNotEmpty()) addChat(m, false)
    }

    private fun flushOutputOnly() {
        val m = outputBuffer.toString().trim()
        outputBuffer.setLength(0)
        if (m.isNotEmpty()) addChat(m, false)
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
                else -> "হ্যালো ${name.ifBlank { "বন্ধু" }}! আমি মায়রা, এসে গেছি। বলো, কী সাহায্য লাগবে?"
            }
            "ENGLISH" -> when {
                pro -> "Good day${if (name.isBlank()) "" else " $name"}. MYRA is online and ready to assist you."
                else -> "Hey ${name.ifBlank { "there" }}! MYRA here. How can I help you?"
            }
            else -> when {
                pro -> "Good day${if (name.isBlank()) "" else " $name"}. MYRA is online and ready to assist you."
                assistant -> "Hello $n! Main MYRA hoon. Kaise help karun aapki?"
                else -> "Hey $n! Main aa gayi hoon. Kya help chahiye tumhe?"
            }
        }
        geminiLive?.sendText("Say this greeting exactly, in your own voice, and nothing else: \"$text\"")
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
        announceCall(name)
    }

    private fun announceCall(callerName: String) {
        isInCallMode = true
        setActive(true)
        val live = geminiLive
        if (live != null && live.isConnected) {
            isMuted = false
            audioEngine?.isMuted = false
            live.sendText(
                "Incoming phone call from $callerName. Tell the user in your personality and ask whether to " +
                    "answer or reject. When they decide, call answer_call or reject_call."
            )
        } else {
            // Offline fallback: show text and listen with the system speech recognizer.
            addChat("📞 $callerName ka call aa raha hai — bolo 'uthao' ya 'reject'", false)
            setStatus("📞 $callerName")
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
                accept -> viewModel.execute("answer_call", emptyMap()) { addChat(it, false) }
                reject -> viewModel.execute("reject_call", emptyMap()) { addChat(it, false) }
                else -> addChat("Bolo 'uthao' ya 'reject'", false)
            }
            return
        }

        val cmd = CommandParser.parse(text)
        if (cmd == null) {
            addChat("Offline hoon — abhi sirf commands samajh sakti hoon (e.g. 'YouTube kholo')", false)
        } else {
            viewModel.execute(cmd.type, cmd.params) { addChat(it, false) }
        }
    }
}
