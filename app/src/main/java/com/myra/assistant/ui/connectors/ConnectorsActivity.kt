package com.myra.assistant.ui.connectors

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.data.SecurePrefs
import com.myra.assistant.ui.main.Haptics
import com.myra.assistant.ui.main.Scene3DView
import com.myra.assistant.ui.theme.ThemeManager

data class Connector(val id: String, val name: String, val desc: String, val category: String, val hint: String)

object ConnectorCatalog {
    val all = listOf(
        Connector("gemini", "Gemini", "Google's Gemini - MYRA's voice brain", "AI & Models", "AIza..."),
        Connector("groq", "Groq", "Ultra fast AI inference.", "AI & Models", "gsk_..."),
        Connector("openai", "OpenAI", "GPT models, Functions.", "AI & Models", "sk-..."),
        Connector("claude", "Claude", "Anthropic's Claude - agent LLM", "AI & Models", "sk-ant-..."),
        Connector("perplexity", "Perplexity", "Real-time web-aware answers.", "AI & Models", "pplx-..."),
        Connector("tavily", "Tavily", "Deep web research.", "AI & Models", "tvly-..."),
        Connector("deepseek", "DeepSeek", "Reasoning & coding models.", "AI & Models", "sk-..."),
        Connector("mistral", "Mistral", "Fast open-weight models.", "AI & Models", "API key"),
        Connector("openrouter", "OpenRouter", "One key, hundreds of models.", "AI & Models", "sk-or-..."),
        Connector("elevenlabs", "ElevenLabs", "AI voice generation & cloning.", "Creative", "API key"),
        Connector("stability", "Stability AI", "Image generation.", "Creative", "sk-..."),
        Connector("replicate", "Replicate", "Run image & video models.", "Creative", "r8_..."),
        Connector("brave", "Brave Search", "Private web search API.", "Search & Data", "BSA..."),
        Connector("serpapi", "SerpAPI", "Google results API.", "Search & Data", "API key"),
        Connector("newsapi", "NewsAPI", "Live news headlines.", "Search & Data", "API key"),
        Connector("openweather", "OpenWeather", "Weather & forecasts.", "Search & Data", "API key"),
        Connector("github", "GitHub", "Repos, issues & code.", "Productivity", "ghp_..."),
        Connector("notion", "Notion", "Notes & databases.", "Productivity", "secret_..."),
        Connector("telegram", "Telegram", "Bot messaging.", "Productivity", "Bot token"),
        Connector("spotify", "Spotify", "Music control.", "Productivity", "Access token")
    )

    fun connectedCount() = all.count { SecurePrefs.connectorKey(it.id).isNotBlank() }
}

/** Connectors screen: search, All / Connected / Not Connected filter, tap a row to save its API key. */
class ConnectorsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var search: EditText
    private val chips = mutableListOf<TextView>()
    private var filter = 0 // 0 all, 1 connected, 2 not connected

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#090506"))
        val scene = Scene3DView(this)
        val pal = ThemeManager.palette(0f)
        scene.setColors(pal.p, pal.s)
        root.addView(scene, ViewGroup.LayoutParams(-1, -1))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
        }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.bg_icon_circle)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Back"
            setOnClickListener { Haptics.tap(this@ConnectorsActivity); finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        titles.addView(tv("Connectors", 22f, Color.WHITE, true))
        subtitle = tv("", 13f, Color.parseColor("#9A8F90"))
        titles.addView(subtitle)
        header.addView(titles)
        col.addView(header)

        // search
        search = EditText(this).apply {
            hint = "Search connectors..."
            setHintTextColor(Color.parseColor("#8A7F80"))
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setBackgroundResource(R.drawable.bg_input_dark)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search_small, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#9A8F90"))
            setPadding(dp(16), 0, dp(16), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { render() }
            })
        }
        col.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) })

        // filter chips
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("All", "Connected", "Not Connected").forEachIndexed { i, label ->
            val c = tv(label, 13f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(8), dp(18), dp(8))
                setOnClickListener { Haptics.tap(this@ConnectorsActivity); filter = i; render() }
            }
            chips.add(c)
            chipRow.addView(c, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        col.addView(chipRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12); bottomMargin = dp(6) })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        sv.addView(list, ViewGroup.LayoutParams(-1, -2))
        col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val total = ConnectorCatalog.all.size
        subtitle.text = "${ConnectorCatalog.connectedCount()} of $total connected"
        chips.forEachIndexed { i, c ->
            c.setBackgroundResource(if (i == filter) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
        }
        list.removeAllViews()
        val q = search.text.toString().trim().lowercase()
        var lastCat = ""
        for (c in ConnectorCatalog.all) {
            val connected = SecurePrefs.connectorKey(c.id).isNotBlank()
            if (filter == 1 && !connected) continue
            if (filter == 2 && connected) continue
            if (q.isNotEmpty() && !(c.name.lowercase().contains(q) || c.desc.lowercase().contains(q))) continue
            if (c.category != lastCat) {
                lastCat = c.category
                list.addView(tv(c.category, 13f, Color.parseColor("#B5AAAB")).apply {
                    setPadding(dp(2), dp(14), 0, dp(8))
                })
            }
            list.addView(row(c, connected), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        if (list.childCount == 0) {
            list.addView(tv("Kuch nahi mila", 14f, Color.parseColor("#9A8F90")).apply { setPadding(dp(4), dp(20), 0, 0) })
        }
    }

    private fun row(c: Connector, connected: Boolean): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card_dark)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { Haptics.tap(this@ConnectorsActivity); editKey(c) }
        }
        val avatar = tv(c.name.take(1).uppercase(), 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_icon_circle)
        }
        r.addView(avatar, LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(tv(c.name, 16f, Color.WHITE, true))
        texts.addView(tv(c.desc, 12f, Color.parseColor("#B5AAAB")).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14); marginEnd = dp(8) })
        val pill = tv(
            if (connected) "Connected" else "Not connected", 11f,
            if (connected) Color.parseColor("#69F0AE") else Color.parseColor("#CFC4C5")
        ).apply {
            setBackgroundResource(if (connected) R.drawable.bg_pill_on else R.drawable.bg_pill)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        r.addView(pill)
        return r
    }

    private fun editKey(c: Connector) {
        val input = EditText(this).apply {
            hint = c.hint
            setText(SecurePrefs.connectorKey(c.id))
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val box = FrameLayout(this).apply { setPadding(dp(16), dp(8), dp(16), 0); addView(input) }
        val b = AlertDialog.Builder(this)
            .setTitle(c.name)
            .setMessage(c.desc)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                SecurePrefs.setConnectorKey(c.id, input.text.toString().trim())
                Toast.makeText(this, "${c.name} saved ✅", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("Cancel", null)
        if (SecurePrefs.connectorKey(c.id).isNotBlank()) {
            b.setNeutralButton("Disconnect") { _, _ ->
                SecurePrefs.setConnectorKey(c.id, "")
                render()
            }
        }
        b.show()
    }
}
