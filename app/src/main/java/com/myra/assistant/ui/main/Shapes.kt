package com.myra.assistant.ui.main

import android.graphics.Path

object HeartShape {
    /** Adds a heart centred on (cx, cy); s is roughly half of its height. */
    fun add(p: Path, cx: Float, cy: Float, s: Float) {
        p.moveTo(cx, cy + s * 0.9f)
        p.cubicTo(cx - s * 1.5f, cy - s * 0.1f, cx - s * 0.8f, cy - s * 1.2f, cx, cy - s * 0.45f)
        p.cubicTo(cx + s * 0.8f, cy - s * 1.2f, cx + s * 1.5f, cy - s * 0.1f, cx, cy + s * 0.9f)
        p.close()
    }
}
