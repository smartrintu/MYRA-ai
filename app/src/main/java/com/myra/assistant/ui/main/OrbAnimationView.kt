package com.myra.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Animated orb: 7 layers, 5 states. One time-based ValueAnimator drives every layer. */
class OrbAnimationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State { IDLE, LISTENING, HEARING, SPEAKING, THINKING, ACTIVE }

    private val density = resources.displayMetrics.density
    private var state = State.IDLE
    private var amp = 0f
    private var targetAmp = 0f
    private val startTime = SystemClock.uptimeMillis()
    private var driver: ValueAnimator? = null

    private var c1 = Color.parseColor("#C2185B")
    private var c2 = Color.parseColor("#7B1FA2")

    private var cx = 0f
    private var cy = 0f
    private var coreR = 0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * density; strokeCap = Paint.Cap.ROUND
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val thinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f * density; strokeCap = Paint.Cap.ROUND
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val wavePath = Path()
    private val rect = RectF()

    fun setState(s: State) {
        if (s == state) return
        state = s
        when (s) {
            State.IDLE -> { c1 = Color.parseColor("#C2185B"); c2 = Color.parseColor("#7B1FA2") }
            State.LISTENING, State.ACTIVE -> { c1 = Color.parseColor("#FF4D8D"); c2 = Color.parseColor("#B388FF") }
            State.HEARING -> { c1 = Color.parseColor("#FFD54F"); c2 = Color.parseColor("#FF8A65") }
            State.SPEAKING -> { c1 = Color.parseColor("#FF80AB"); c2 = Color.parseColor("#E040FB") }
            State.THINKING -> { c1 = Color.parseColor("#80D8FF"); c2 = Color.parseColor("#B388FF") }
        }
        rebuildShaders()
        invalidate()
    }

    /** rms 0..1 */
    fun setAmplitude(a: Float) {
        targetAmp = a.coerceIn(0f, 1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun rebuildShaders() {
        if (width == 0 || height == 0) return
        cx = width / 2f
        cy = height / 2f
        coreR = min(width, height) * 0.22f
        glowPaint.shader = RadialGradient(
            cx, cy, coreR * 1.6f,
            intArrayOf(withAlpha(c1, 255), withAlpha(c1, 0)), floatArrayOf(0.35f, 1f), Shader.TileMode.CLAMP
        )
        corePaint.shader = RadialGradient(
            cx - coreR * 0.3f, cy - coreR * 0.3f, coreR * 1.5f, c1, c2, Shader.TileMode.CLAMP
        )
        hlPaint.shader = RadialGradient(
            cx - coreR * 0.35f, cy - coreR * 0.35f, coreR * 0.55f,
            intArrayOf(Color.argb(110, 255, 255, 255), Color.argb(0, 255, 255, 255)), null, Shader.TileMode.CLAMP
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        driver = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        driver?.cancel()
        driver = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || coreR == 0f) return
        val t = (SystemClock.uptimeMillis() - startTime) / 1000f
        amp += (targetAmp - amp) * 0.25f
        targetAmp *= 0.92f

        val twoPi = (2.0 * PI).toFloat()
        val pulse = (sin(t * twoPi / 1.5f) + 1f) / 2f
        val idle = state == State.IDLE
        val scale = if (idle) 1f + 0.15f * pulse else 1f + 0.04f * pulse + 0.12f * amp
        val rot = t * (if (state == State.SPEAKING) 90f else 45f)
        val waveSpeed = if (state == State.SPEAKING) 6f else 3f
        val showRings = state == State.LISTENING || state == State.HEARING || state == State.SPEAKING || state == State.ACTIVE

        // 1 + 2: glow and core
        glowPaint.alpha = (120 + 100 * pulse).toInt()
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.drawCircle(cx, cy, coreR * 1.6f, glowPaint)
        canvas.drawCircle(cx, cy, coreR, corePaint)
        canvas.restore()

        // 3: rotating rings
        if (showRings) {
            ringPaint.color = c1
            for (i in 0 until 3) {
                val r = coreR * (1.25f + 0.2f * i)
                rect.set(cx - r, cy - r, cx + r, cy + r)
                ringPaint.alpha = 220 - i * 55
                canvas.save()
                canvas.rotate(rot * (if (i % 2 == 0) 1f else -1f) * (1f + i * 0.3f), cx, cy)
                canvas.drawArc(rect, 0f, 110f, false, ringPaint)
                canvas.drawArc(rect, 180f, 70f, false, ringPaint)
                canvas.restore()
            }

            // 4: wave ring
            wavePaint.color = c2
            wavePaint.alpha = 190
            wavePath.reset()
            val baseR = coreR * 1.12f
            val a = coreR * (0.04f + 0.18f * amp)
            val n = 90
            for (i in 0..n) {
                val th = i * twoPi / n
                val rr = baseR + a * sin(6f * th + t * waveSpeed)
                val x = cx + rr * cos(th)
                val y = cy + rr * sin(th)
                if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
            }
            wavePath.close()
            canvas.drawPath(wavePath, wavePaint)
        }

        // 5: thinking arcs
        if (state == State.THINKING) {
            thinkPaint.color = c1
            val ra = coreR * 1.35f
            rect.set(cx - ra, cy - ra, cx + ra, cy + ra)
            canvas.drawArc(rect, (t * 300f) % 360f, 100f, false, thinkPaint)
            thinkPaint.color = c2
            canvas.drawArc(rect, 360f - (t * 200f) % 360f, 60f, false, thinkPaint)
        }

        // 6: orbiting particles
        if (state == State.SPEAKING || state == State.ACTIVE) {
            particlePaint.color = c2
            particlePaint.alpha = 200
            for (i in 0 until 12) {
                val ang = Math.toRadians((t * 40f + i * 30f).toDouble()).toFloat()
                val rad = coreR * (1.85f + 0.1f * sin(t * 2f + i))
                val size = (3f + 3f * amp) * density
                canvas.drawCircle(cx + rad * cos(ang), cy + rad * sin(ang), size, particlePaint)
            }
        }

        // 7: inner highlight
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.drawCircle(cx - coreR * 0.35f, cy - coreR * 0.35f, coreR * 0.55f, hlPaint)
        canvas.restore()
    }
}
