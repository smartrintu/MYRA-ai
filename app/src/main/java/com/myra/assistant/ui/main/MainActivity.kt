package com.myra.assistant.ui.main

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.myra.assistant.ai.ResearchClient
import com.myra.assistant.ui.connectors.ConnectorCatalog
import com.myra.assistant.ui.connectors.ConnectorsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.myra.assistant.R
import com.myra.assistant.ai.CommandParser
import com.myra.assistant.data.AvatarStore
import com.myra.assistant.data.SecurePrefs
import com.myra.assistant.data.TerminalLog
import com.myra.assistant.databinding.ActivityMainBinding
import com.myra.assistant.license.LicenseManager
import com.myra.assistant.ui.license.ActivationActivity
import com.myra.assistant.service.CallMonitorService
import com.myra.assistant.service.MyraOverlayService
import com.myra.assistant.service.MyraVoiceService
import com.myra.assistant.ui.settings.SettingsActivity
import com.myra.assistant.ui.theme.ThemeManager
import com.myra.assistant.viewmodel.CommandExecutor
import kotlinx.coroutines.launch
import java.util.Locale

/** UI only. The microphone + Gemini session live in MyraVoiceService so they survive background/lock. */
class MainActivity : AppCompatActivity(), MyraVoiceService.UiListener, TerminalLog.Listener {

    private lateinit var binding: ActivityMainBinding
    private val chatAdapter = ChatAdapter()
    private val executor by lazy { CommandExecutor(applicationContext) }

    private enum class Page { HOME, CHAT, DISCOVER, AURA }
    private var page = Page.HOME
    private var lensFile: File? = null
    private var keyAtLaunch = ""

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) sendLensPhoto()
    }

    private var askedOptional = false
    private var isInCallMode = false
    private var lastSyncedTs = 0L
    private var suppressSwitch = false
    private var avatarStamp = -1L
    private val themeTicker = object : Runnable {
        override fun run() {
            applyTheme()
            if (ThemeManager.isRainbow()) handler.postDelayed(this, 150)
        }
    }
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
        if (!LicenseManager.status(this).valid) {
            startActivity(Intent(this, ActivationActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initViews()

        ContextCompat.registerReceiver(
            this, callEndedReceiver, IntentFilter(CallMonitorService.ACTION_CALL_ENDED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != Page.HOME) showPage(Page.HOME) else finish()
            }
        })
        keyAtLaunch = SecurePrefs.apiKey

        ensurePermissions()
        handleIncomingCallIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!::binding.isInitialized) return
        setIntent(intent)
        handleIncomingCallIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (!::binding.isInitialized) return
        MyraVoiceService.uiListener = this
        TerminalLog.listener = this
        if (TerminalLog.busy) onTerminal(TerminalLog.text(), true)
        syncFromService()
        ThemeManager.onChanged = { applyThemeNow() }
        applyThemeNow()
    }

    override fun onStop() {
        if (MyraVoiceService.uiListener === this) MyraVoiceService.uiListener = null
        if (TerminalLog.listener === this) TerminalLog.listener = null
        handler.removeCallbacks(themeTicker)
        handler.removeCallbacks(hideTerminal)
        ThemeManager.onChanged = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        if (!LicenseManager.status(this).valid) {
            startActivity(Intent(this, ActivationActivity::class.java))
            finish()
            return
        }
        val n = SecurePrefs.userName.trim()
        binding.greetingText.text = if (n.isEmpty()) "Hello" else "Hello, $n"
        val stamp = AvatarStore.stamp(this)
        if (stamp != avatarStamp) {
            avatarStamp = stamp
            setNavAvatar(AvatarStore.load(this))
        }
        binding.connectorsCount.text = "${ConnectorCatalog.connectedCount()} of ${ConnectorCatalog.all.size} connected"
        binding.auraCurrent.text = "Current: ${currentAuraName()}"
        binding.hapticSwitch.isChecked = SecurePrefs.haptics
        if (SecurePrefs.apiKey != keyAtLaunch) {
            keyAtLaunch = SecurePrefs.apiKey
            onSettingsSaved()
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(callEndedReceiver) } catch (_: Exception) {}
        recognizer?.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ theme

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun applyThemeNow() {
        handler.removeCallbacks(themeTicker)
        themeTicker.run()
        chatAdapter.refresh()
        binding.auraCurrent.text = "Current: ${currentAuraName()}"
    }

    private fun applyTheme() {
        val t = (SystemClock.uptimeMillis() % 1_000_000L) / 1000f
        val pal = ThemeManager.palette(t)
        binding.sceneView.setColors(pal.p, pal.s)
        binding.orbView.setThemeColors(pal.p, pal.s)
        binding.micButton.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(pal.p, pal.s)
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(3), 0x66FFFFFF)
        }
        binding.sendBtn.setColorFilter(pal.p)
        binding.chatSend.setColorFilter(pal.p)
        chatAdapter.setTheme(pal.p, pal.s)
        tintNav(pal.p)
    }

    private val auraThemes = listOf(
        "Crimson Aura" to "red", "Rose Aura" to "pink", "Ocean Aura" to "blue", "Violet Aura" to "purple",
        "Emerald Aura" to "green", "Amber Aura" to "orange", "Golden Aura" to "gold", "Multicolor Aura" to "multicolor"
    )

    private fun currentAuraName(): String {
        val th = SecurePrefs.theme
        if (ThemeManager.isRainbow()) return "Multicolor Aura"
        return auraThemes.firstOrNull { it.second == th }?.first ?: "Custom Aura"
    }

    // ------------------------------------------------------------------ pages / navigation

    private fun tintNav(active: Int) {
        val items = listOf(
            binding.navHome to Page.HOME, binding.navChat to Page.CHAT,
            binding.navDiscover to Page.DISCOVER, binding.navSettings to Page.AURA
        )
        for ((v, p) in items) {
            val c = if (p == page) active else Color.parseColor("#8A7F80")
            (v.getChildAt(0) as ImageView).setColorFilter(c)
            (v.getChildAt(1) as TextView).setTextColor(c)
        }
    }

    private fun pageView(p: Page): View = when (p) {
        Page.HOME -> binding.pageHome
        Page.CHAT -> binding.pageChat
        Page.DISCOVER -> binding.pageDiscover
        Page.AURA -> binding.pageAura
    }

    private fun showPage(p: Page) {
        if (p == page && pageView(p).visibility == View.VISIBLE) return
        Haptics.tap(this)
        val out = pageView(page)
        val inn = pageView(p)
        page = p
        out.animate().cancel()
        out.visibility = View.GONE
        inn.animate().cancel()
        inn.alpha = 0f
        inn.translationY = dp(24).toFloat()
        inn.visibility = View.VISIBLE
        inn.animate().alpha(1f).translationY(0f).setDuration(230).start()
        tintNav(ThemeManager.palette(0f).p)
        applyThemeNow()
        if (p == Page.CHAT && chatAdapter.itemCount > 0) binding.chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
    }

    private fun setNavAvatar(bmp: Bitmap?) {
        if (bmp == null) return
        val side = minOf(bmp.width, bmp.height)
        val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        binding.navOrb.scaleType = ImageView.ScaleType.FIT_CENTER
        binding.navOrb.setImageDrawable(
            RoundedBitmapDrawableFactory.create(resources, sq).apply { isCircular = true }
        )
    }

    // ------------------------------------------------------------------ UiListener (called on main thread)

    override fun onStatus(text: String) {
        binding.statusText.text = if (SecurePrefs.enabled) text else "MYRA OFF — switch ON karo"
    }

    override fun onOrb(state: OrbAnimationView.State) {
        binding.orbView.setState(state)
    }

    private val hideTerminal = Runnable { binding.terminalPanel.visibility = android.view.View.GONE }

    /** Live terminal: shows background work (commands, website code being written). */
    override fun onTerminal(text: String, busy: Boolean) {
        if (!::binding.isInitialized) return
        handler.removeCallbacks(hideTerminal)
        binding.terminalPanel.visibility = android.view.View.VISIBLE
        binding.terminalTitle.text = if (busy) "● MYRA TERMINAL — kaam chal raha hai..." else "✔ MYRA TERMINAL — ho gaya"
        binding.terminalText.text = text
        binding.terminalScroll.post { binding.terminalScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        if (!busy) handler.postDelayed(hideTerminal, 12_000)
    }

    override fun onAmplitude(a: Float) {
        binding.sceneView.setAmplitude(a)
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
        updateEnableSwitch()
    }

    private fun updateEnableSwitch() {
        suppressSwitch = true
        binding.enableSwitch.isChecked = SecurePrefs.enabled
        binding.enableSwitch.text = if (SecurePrefs.enabled) "MYRA ON" else "MYRA OFF"
        suppressSwitch = false
    }

    /** Master switch: OFF stops the mic, the floating orb and call announcements completely. */
    private fun setMyraEnabled(on: Boolean) {
        SecurePrefs.enabled = on
        updateEnableSwitch()
        if (on) {
            startSystemServices()
            startVoiceService()
        } else {
            stopService(Intent(this, MyraVoiceService::class.java))
            stopService(Intent(this, MyraOverlayService::class.java))
            stopService(Intent(this, CallMonitorService::class.java))
            onOrb(OrbAnimationView.State.IDLE)
            onMic(true, false)
            setStatus("MYRA OFF — switch ON karo")
        }
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

        binding.enableSwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitch) setMyraEnabled(checked)
        }
        updateEnableSwitch()

        binding.terminalClose.setOnClickListener { binding.terminalPanel.visibility = View.GONE }

        // bottom navigation
        binding.navHome.setOnClickListener { showPage(Page.HOME) }
        binding.navChat.setOnClickListener { showPage(Page.CHAT) }
        binding.navDiscover.setOnClickListener { showPage(Page.DISCOVER) }
        binding.navSettings.setOnClickListener { showPage(Page.AURA) }
        binding.navOrb.setOnClickListener {
            showPage(Page.HOME)
            toggleMic()
        }

        // home
        binding.micButton.setOnClickListener { Haptics.tap(this, true); toggleMic() }
        binding.micButton.setOnLongClickListener {
            MyraVoiceService.instance?.interrupt()
            setStatus("Ruk gayi 🤐")
            true
        }
        binding.cardVoice.setOnClickListener { Haptics.tap(this, true); toggleMic() }
        binding.cardLens.setOnClickListener { Haptics.tap(this); openLens() }
        binding.quickResearch.setOnClickListener {
            Haptics.tap(this)
            askDialog("Deep Research", "Kis topic par research karun?") { deepResearch(it) }
        }
        binding.quickImage.setOnClickListener {
            Haptics.tap(this)
            askDialog("Image Search", "Kya dhoondhna hai?") { imageSearch(it) }
        }
        binding.sendBtn.setOnClickListener { submitInput(binding.askInput, true) }
        binding.askInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { submitInput(binding.askInput, true); true } else false
        }
        binding.chatSend.setOnClickListener { submitInput(binding.chatInput, false) }
        binding.chatInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { submitInput(binding.chatInput, false); true } else false
        }

        buildDiscover()
        buildAura()
    }

    private fun toggleMic() {
        val svc = MyraVoiceService.instance
        when {
            svc == null -> if (!SecurePrefs.enabled) setMyraEnabled(true) else startVoiceService()
            !MyraVoiceService.connected -> startOfflineListen()
            else -> svc.setMuted(!MyraVoiceService.muted)
        }
    }

    private fun submitInput(field: EditText, goToChat: Boolean) {
        val t = field.text.toString().trim()
        if (t.isEmpty()) return
        field.setText("")
        Haptics.tap(this)
        if (goToChat) showPage(Page.CHAT)
        sendTyped(t)
    }

    /** Typed message: goes to the live session if connected, else the offline command parser. */
    private fun sendTyped(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val svc = MyraVoiceService.instance
        if (svc != null && MyraVoiceService.connected) {
            addChat(t, true)
            svc.tell(t)
            setStatus("Soch rahi hoon...")
        } else {
            handleOfflineText(t)
        }
    }

    private fun askDialog(title: String, hint: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val box = FrameLayout(this).apply { setPadding(dp(16), dp(8), dp(16), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Go") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) onOk(q)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ home quick actions

    private fun deepResearch(q: String) {
        showPage(Page.CHAT)
        addChat("🔎 Deep Research: $q", true)
        setStatus("Research chal rahi hai...")
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { ResearchClient.run(q) }
            val svc = MyraVoiceService.instance
            if (res != null) {
                addChat(res.text, false)
                setStatus("Research ho gayi ✅ (${res.source})")
                svc?.tell(
                    "You just finished deep research for the user on \"$q\" using ${res.source}. " +
                        "Tell them the key findings in 2-3 short spoken sentences. Findings: ${res.text.take(3500)}"
                )
            } else if (svc != null && MyraVoiceService.connected) {
                svc.tell("Do a quick but careful research answer about: $q. Give the key points in 3 short spoken sentences.")
                addChat("Connectors mein Perplexity / Tavily / Claude key daalo to poori research aayegi. Abhi Gemini se short jawab le rahi hoon.", false)
            } else {
                addChat("Research ke liye Aura Control > Connectors mein Perplexity, Tavily ya Claude ki key daalo.", false)
                setStatus("Key chahiye")
            }
        }
    }

    private fun imageSearch(q: String) {
        try {
            val url = "https://www.google.com/search?tbm=isch&q=" + URLEncoder.encode(q, "UTF-8")
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Browser nahi khula: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLens() {
        val svc = MyraVoiceService.instance
        if (svc == null || !MyraVoiceService.connected) {
            Toast.makeText(this, "Pehle MYRA ko connect karo (mic ON, Gemini key)", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val f = File(cacheDir, "lens/lens.jpg")
            f.parentFile?.mkdirs()
            lensFile = f
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            takePicture.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera nahi khula: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendLensPhoto() {
        val f = lensFile ?: return
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { try { compressPhoto(f) } catch (e: Exception) { null } }
            val svc = MyraVoiceService.instance
            if (bytes == null) {
                Toast.makeText(this@MainActivity, "Photo load nahi hui", Toast.LENGTH_SHORT).show()
            } else if (svc != null && svc.sendImage(
                    bytes,
                    "The user just showed you a photo with the camera (Visual Lens). Look at it and tell them what you see in 2 short sentences, then ask what they want to know."
                )
            ) {
                addChat("📷 Visual Lens: photo bhej di", true)
                setStatus("Dekh rahi hoon...")
            } else {
                Toast.makeText(this@MainActivity, "MYRA connected nahi hai", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun compressPhoto(f: File): ByteArray? {
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        var bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rot = when (ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
        val m = maxOf(bmp.width, bmp.height)
        if (m > 1024) {
            val k = 1024f / m
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ discover page

    private fun buildDiscover() {
        data class Tip(val title: String, val sub: String, val prompt: String)
        val tips = listOf(
            Tip("Aaj ka mausam", "Weather aur forecast", "Aaj ka mausam kaisa hai?"),
            Tip("Gaana chalao", "YouTube par music", "YouTube par ek romantic gaana chalao"),
            Tip("Website banao", "Poori website ek minute mein", "Mere liye ek photography studio ki website bana do"),
            Tip("Alarm lagao", "Subah ka alarm", "Kal subah 6 baje ka alarm laga do"),
            Tip("WhatsApp bhejo", "Kisi ko message", "Mummy ko WhatsApp par good morning bhejo"),
            Tip("Torch ON", "Flashlight control", "Torch on kar do"),
            Tip("Screen padho", "Screen par kya hai", "Meri screen par kya likha hai padh ke batao"),
            Tip("Interface ka color", "Aura theme badlo", "Interface ka color nila kar do"),
            Tip("Multicolor mode", "Rainbow animation", "Interface multicolor kar do"),
            Tip("Battery check", "Phone ki battery", "Battery kitni bachi hai?")
        )
        val list = binding.discoverList
        for (t in tips) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(com.myra.assistant.R.drawable.bg_card_dark)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    Haptics.tap(this@MainActivity)
                    showPage(Page.CHAT)
                    sendTyped(t.prompt)
                }
            }
            card.addView(TextView(this).apply {
                text = t.title; setTextColor(Color.WHITE); textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = t.sub; setTextColor(Color.parseColor("#B5AAAB")); textSize = 12f
            })
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    // ------------------------------------------------------------------ Aura Control page

    private fun buildAura() {
        binding.auraBack.setOnClickListener { showPage(Page.HOME) }

        binding.hapticSwitch.isChecked = SecurePrefs.haptics
        binding.hapticSwitch.setOnCheckedChangeListener { _, on ->
            SecurePrefs.haptics = on
            if (on) Haptics.tap(this, true)
        }

        binding.rowAura.setOnClickListener {
            Haptics.tap(this)
            AlertDialog.Builder(this)
                .setTitle("Aura Signature")
                .setItems(auraThemes.map { it.first }.toTypedArray()) { _, i ->
                    ThemeManager.set(auraThemes[i].second)
                    Haptics.tap(this, true)
                }
                .show()
        }
        binding.rowSystem.setOnClickListener {
            Haptics.tap(this)
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
        binding.rowConnectors.setOnClickListener {
            Haptics.tap(this)
            startActivity(Intent(this, ConnectorsActivity::class.java))
        }
        buildIconRow()
    }

    private fun buildIconRow() {
        val row = binding.iconRow
        row.removeAllViews()
        for (ic in LauncherIcons.all) {
            val selected = ic.id == SecurePrefs.launcherIcon
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener {
                    Haptics.tap(this@MainActivity, true)
                    LauncherIcons.apply(this@MainActivity, ic.id)
                    Toast.makeText(this@MainActivity, "${ic.label} icon set ✅ (launcher ko 2-3 sec lag sakte hain)", Toast.LENGTH_LONG).show()
                    buildIconRow()
                }
            }
            val frame = FrameLayout(this).apply {
                setBackgroundResource(if (selected) com.myra.assistant.R.drawable.bg_icon_selected else com.myra.assistant.R.drawable.bg_icon_plain)
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            val clip = FrameLayout(this).apply {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
                    }
                }
            }
            val img = ImageView(this).apply {
                setImageDrawable(ContextCompat.getDrawable(this@MainActivity, ic.res))
            }
            // adaptive icons are 108dp with a 72dp visible window -> draw big, clip to the window
            clip.addView(img, FrameLayout.LayoutParams(dp(90), dp(90), Gravity.CENTER))
            frame.addView(clip, FrameLayout.LayoutParams(dp(60), dp(60)))
            cell.addView(frame)
            cell.addView(TextView(this).apply {
                text = ic.label
                textSize = 12f
                setTextColor(if (selected) Color.WHITE else Color.parseColor("#B5AAAB"))
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
            row.addView(cell)
        }
    }

    private fun setStatus(text: String) {
        binding.statusText.text = text
    }

    private fun addChat(text: String, isUser: Boolean) {
        if (text.isBlank()) return
        onChat(ChatMessage(text.trim(), isUser))
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
        if (!SecurePrefs.enabled) {
            setStatus("MYRA OFF — switch ON karo")
            return
        }
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
        if (!SecurePrefs.enabled) return
        val svc = MyraVoiceService.instance
        if (svc != null) svc.reconnect() else ensurePermissions()
    }

    // ------------------------------------------------------------------ incoming calls

    private fun handleIncomingCallIntent(i: Intent?) {
        if (i == null || !i.getBooleanExtra("INCOMING_CALL", false)) return
        if (!SecurePrefs.enabled) return
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
