package com.myra.assistant.ui.theme

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.myra.assistant.data.SecurePrefs

/** Colour themes for the main screen. MYRA can switch them by voice (change_theme), including an animated multicolor mode. */
object ThemeManager {

    data class Palette(val p: Int, val s: Int, val bgTop: Int, val bgMid: Int, val bgBottom: Int, val soft: Int, val text: Int)

    const val RAINBOW = "RAINBOW"

    private val named = mapOf(
        "pink" to "#FF4D8D", "red" to "#FF3B4E", "blue" to "#3D8BFF", "green" to "#2ED47A",
        "purple" to "#A24DFF", "violet" to "#A24DFF", "orange" to "#FF8A3D", "yellow" to "#FFD43D",
        "teal" to "#1DE9B6", "aqua" to "#1DE9B6", "cyan" to "#26C6DA", "gold" to "#FFC107",
        "maroon" to "#B0204F", "magenta" to "#E040FB"
    )
    private val rainbowWords = listOf(
        "multicolor", "multi color", "multi-color", "multicolour", "rainbow", "colorful", "colourful",
        "all colors", "all colours", "multi"
    )

    @Volatile var onChanged: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun isRainbow() = SecurePrefs.theme == RAINBOW

    /** Accepts a colour name, a #hex code or "multicolor"/"rainbow". Returns false if not understood. */
    fun set(input: String): Boolean {
        val k = input.trim().lowercase()
        val value = when {
            k in rainbowWords -> RAINBOW
            k == "default" || k == "reset" || k == "normal" -> "pink"
            named.containsKey(k) -> k
            Regex("^#?[0-9a-f]{6}$").matches(k) -> "#" + k.removePrefix("#")
            else -> return false
        }
        SecurePrefs.theme = value
        main.post { onChanged?.invoke() }
        return true
    }

    fun palette(timeSec: Float): Palette {
        val th = SecurePrefs.theme
        if (th == RAINBOW) return fromPrimary(hsv((timeSec * 40f) % 360f, 0.62f, 1f))
        val base = try {
            if (th.startsWith("#")) Color.parseColor(th) else Color.parseColor(named[th] ?: "#FF4D8D")
        } catch (e: Exception) {
            Color.parseColor("#FF4D8D")
        }
        return fromPrimary(base)
    }

    private fun hsv(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(h, s, v))

    private fun fromPrimary(p: Int): Palette {
        val a = FloatArray(3)
        Color.colorToHSV(p, a)
        fun mk(dh: Float, s: Float, v: Float) = hsv((a[0] + dh + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
        return Palette(
            p = p,
            s = mk(45f, (a[1] * 0.75f).coerceAtLeast(0.25f), 1f),
            bgTop = mk(-10f, 0.75f, 0.11f),
            bgMid = mk(0f, 0.72f, 0.22f),
            bgBottom = mk(15f, 0.70f, 0.33f),
            soft = mk(0f, 0.35f, 1f),
            text = mk(0f, 0.10f, 1f)
        )
    }
}
