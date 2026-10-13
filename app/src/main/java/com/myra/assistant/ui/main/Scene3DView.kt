package com.myra.assistant.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-page 3D background: a tunnel of tilted rotating rings flying towards the viewer,
 * a depth starfield and a glow behind the orb. Colours follow the Aura theme; voice energy speeds it up.
 */
class Scene3DView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class Star(var x: Float, var y: Float, var z: Float)

    private val density = resources.displayMetrics.density
    private var cP = Color.parseColor("#FF2A3A")
    private var cS = Color.parseColor("#B3001B")
    private var energy = 0f
    private var targetEnergy = 0f
    private var lastFrame = SystemClock.uptimeMillis()
    private var time = 0f
    private var running = false

    private val bgPaint = Paint()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hex = Path()
    private val stars = Array(64) { newStar(true) }
    private val rings = 11

    private fun newStar(anywhere: Boolean): Star = Star(
        (Math.random() * 2 - 1).toFloat(),
        (Math.random() * 2 - 1).toFloat(),
        if (anywhere) (0.1 + Math.random() * 0.9).toFloat() else 1f
    )

    fun setColors(p: Int, s: Int) {
        if (p == cP && s == cS) return
        cP = p
        cS = s
        rebuildShaders()
    }

    /** 0..1 voice level; makes the tunnel rush faster while MYRA hears or speaks. */
    fun setAmplitude(a: Float) {
        targetEnergy = max(targetEnergy, a.coerceIn(0f, 1f))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    private fun rebuildShaders() {
        if (width == 0 || height == 0) return
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(Color.parseColor("#050202"), ColorUtils.blendARGB(cP, Color.BLACK, 0.9f), Color.parseColor("#050202")),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        val r = width * 0.75f
        glowPaint.shader = RadialGradient(
            width / 2f, height * 0.36f, r,
            intArrayOf(ColorUtils.setAlphaComponent(cP, 90), ColorUtils.setAlphaComponent(cS, 30), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastFrame = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f) return
        val now = SystemClock.uptimeMillis()
        val dt = min((now - lastFrame) / 1000f, 0.05f)
        lastFrame = now
        energy += (targetEnergy - energy) * 0.15f
        targetEnergy *= 0.94f
        val speed = 1f + energy * 2.2f
        time += dt * speed

        canvas.drawRect(0f, 0f, w, h, bgPaint)
        canvas.drawRect(0f, 0f, w, h, glowPaint)

        val cx = w / 2f
        val cy = h * 0.36f
        val maxR = max(w, h) * 0.95f

        // rotating hexagon tunnel, tilted for a 3D look
        for (i in 0 until rings) {
            val ph = (time * 0.11f + i.toFloat() / rings) % 1f
            val z = ph * ph
            val r = 6f * density + maxR * z
            val a = (sin(ph * PI.toFloat()) * (70f + 90f * energy)).toInt().coerceIn(0, 200)
            val rot = time * 22f * (if (i % 2 == 0) 1f else -1f) + i * 17f
            hex.reset()
            for (k in 0 until 6) {
                val ang = Math.toRadians((rot + k * 60f).toDouble()).toFloat()
                val px = r * cos(ang)
                val py = r * sin(ang)
                if (k == 0) hex.moveTo(px, py) else hex.lineTo(px, py)
            }
            hex.close()
            ringPaint.strokeWidth = (0.8f + 2.4f * z) * density
            ringPaint.color = ColorUtils.setAlphaComponent(if (i % 3 == 0) cS else cP, a)
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(1f, 0.62f)
            canvas.drawPath(hex, ringPaint)
            canvas.restore()
        }

        // depth starfield (parallax: near stars move faster)
        for (s in stars) {
            s.z -= dt * (0.05f + 0.16f * speed) * (1.2f - s.z * 0.5f)
            if (s.z < 0.06f) {
                s.x = (Math.random() * 2 - 1).toFloat()
                s.y = (Math.random() * 2 - 1).toFloat()
                s.z = 1f
            }
            val sx = cx + s.x / s.z * w * 0.22f
            val sy = cy + s.y / s.z * h * 0.22f
            if (sx < -20f || sx > w + 20f || sy < -20f || sy > h + 20f) {
                s.z = 1f
                continue
            }
            val alpha = ((1f - s.z) * 230f).toInt().coerceIn(0, 230)
            starPaint.color = ColorUtils.setAlphaComponent(if (s.x > 0) cP else Color.WHITE, alpha)
            canvas.drawCircle(sx, sy, ((0.6f / s.z).coerceAtMost(3.2f)) * density, starPaint)
        }

        if (running) postInvalidateOnAnimation()
    }
}
