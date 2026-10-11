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
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Cute animated anime-style girl drawn with Canvas (no image files).
 * Blinks, breathes, sways her twin tails, lip-syncs to MYRA's voice, looks happy when you talk,
 * thinks when she works, and winks + throws hearts when you tap her.
 * Everything is drawn in a 100x100 box that is scaled to the view.
 */
class GirlAvatarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class Floater(var x: Float, var y: Float, val vx: Float, val vy: Float, val size: Float, val born: Float, val color: Int)

    private val HAIR = Color.parseColor("#7E3FB8")
    private val HAIR_DARK = Color.parseColor("#5E2A91")
    private val SKIN = Color.parseColor("#FFE3D3")
    private val SKIN_SHADE = Color.parseColor("#F5C9B5")
    private val DRESS = Color.parseColor("#FF6FA8")
    private val PINK = Color.parseColor("#FF4D8D")
    private val IRIS = Color.parseColor("#C04CFF")
    private val PUPIL = Color.parseColor("#2B0A3D")
    private val LASH = Color.parseColor("#3A1450")
    private val MOUTH = Color.parseColor("#B0204F")
    private val TONGUE = Color.parseColor("#FF8FB0")
    private val heartColors = intArrayOf(
        Color.parseColor("#FF4D8D"), Color.parseColor("#FF8FB8"), Color.parseColor("#E040FB"), Color.parseColor("#FFD1E3")
    )

    private var state = OrbAnimationView.State.IDLE
    private var amp = 0f
    private var targetAmp = 0f
    private var mouth = 0f
    private val startTime = SystemClock.uptimeMillis()
    private var driver: ValueAnimator? = null

    private var nextBlinkAt = 2.5f
    private var blinkStart = -1f
    private var winkUntil = 0f
    private var lastSpawn = 0f
    private var lastT = 0f
    private val floaters = ArrayList<Floater>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private var glow1 = Color.parseColor("#FF8FB8")
    private var glow2 = Color.parseColor("#B388FF")

    init {
        rebuildGlow()
        setOnClickListener { burst() }
    }

    fun setState(s: OrbAnimationView.State) {
        if (s == state) return
        state = s
        when (s) {
            OrbAnimationView.State.IDLE -> { glow1 = Color.parseColor("#FF8FB8"); glow2 = Color.parseColor("#B388FF") }
            OrbAnimationView.State.LISTENING, OrbAnimationView.State.ACTIVE -> { glow1 = Color.parseColor("#FF4D8D"); glow2 = Color.parseColor("#B388FF") }
            OrbAnimationView.State.HEARING -> { glow1 = Color.parseColor("#FFD54F"); glow2 = Color.parseColor("#FF8A65") }
            OrbAnimationView.State.SPEAKING -> { glow1 = Color.parseColor("#FF80AB"); glow2 = Color.parseColor("#E040FB") }
            OrbAnimationView.State.THINKING -> { glow1 = Color.parseColor("#80D8FF"); glow2 = Color.parseColor("#B388FF") }
        }
        rebuildGlow()
        invalidate()
    }

    fun setAmplitude(a: Float) {
        targetAmp = a.coerceIn(0f, 1f)
    }

    private fun alphaOf(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    private fun rebuildGlow() {
        glowPaint.shader = RadialGradient(
            50f, 50f, 50f,
            intArrayOf(alphaOf(glow1, 170), alphaOf(glow2, 0)), floatArrayOf(0.45f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun burst() {
        winkUntil = lastT + 0.7f
        repeat(8) { spawn(lastT, 50f + Random.nextFloat() * 30f - 15f, 40f + Random.nextFloat() * 20f) }
    }

    private fun spawn(t: Float, x: Float, y: Float) {
        floaters.add(
            Floater(
                x, y, Random.nextFloat() * 10f - 5f, -(12f + Random.nextFloat() * 10f),
                2.5f + Random.nextFloat() * 2.5f, t, heartColors[Random.nextInt(heartColors.size)]
            )
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
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val t = (SystemClock.uptimeMillis() - startTime) / 1000f
        lastT = t

        amp += (targetAmp - amp) * 0.3f
        targetAmp *= 0.9f
        val mouthTarget = if (state == OrbAnimationView.State.SPEAKING)
            (amp * 1.6f + abs(sin(t * 17f)) * 0.25f * amp).coerceIn(0f, 1f) else 0f
        mouth += (mouthTarget - mouth) * 0.45f

        val u = min(w, h) / 100f
        canvas.save()
        canvas.translate((w - 100f * u) / 2f, (h - 100f * u) / 2f)
        canvas.scale(u, u)
        drawScene(canvas, t)
        canvas.restore()
    }

    private fun drawScene(c: Canvas, t: Float) {
        glowPaint.alpha = (200 + 55 * sin(t * 2f)).toInt().coerceIn(0, 255)
        c.drawCircle(50f, 50f, 50f, glowPaint)

        val breathe = 1f + 0.012f * sin(t * 2.2f)
        val bob = sin(t * 1.6f) * 1.0f
        val tilt = sin(t * 0.9f) * (if (state == OrbAnimationView.State.THINKING) 4f else 2f)

        c.save()
        c.scale(1f, breathe, 50f, 100f)
        c.translate(0f, bob)
        c.rotate(tilt, 50f, 82f)
        drawTails(c, t)
        drawBody(c)
        drawHead(c, t)
        c.restore()

        drawFloaters(c, t)
    }

    private fun drawTails(c: Canvas, t: Float) {
        val sway = sin(t * 1.9f) * (3f + 9f * amp)
        fill.color = HAIR
        c.save(); c.rotate(-10f + sway, 27f, 40f); c.drawOval(12f, 38f, 30f, 88f, fill); c.restore()
        c.save(); c.rotate(10f - sway, 73f, 40f); c.drawOval(70f, 38f, 88f, 88f, fill); c.restore()
        fill.color = HAIR_DARK
        c.save(); c.rotate(-10f + sway, 27f, 40f); c.drawOval(14f, 70f, 28f, 88f, fill); c.restore()
        c.save(); c.rotate(10f - sway, 73f, 40f); c.drawOval(72f, 70f, 86f, 88f, fill); c.restore()
    }

    private fun drawBody(c: Canvas) {
        fill.color = SKIN_SHADE
        c.drawRect(45f, 68f, 55f, 80f, fill)
        fill.color = DRESS
        c.drawRoundRect(28f, 76f, 72f, 112f, 14f, 14f, fill)
        fill.color = Color.WHITE
        path.reset()
        path.moveTo(40f, 77f); path.lineTo(50f, 90f); path.lineTo(60f, 77f)
        path.quadTo(50f, 82f, 40f, 77f); path.close()
        c.drawPath(path, fill)
        fill.color = PINK
        path.reset()
        HeartShape.add(path, 50f, 95f, 4.5f)
        c.drawPath(path, fill)
    }

    private fun drawHead(c: Canvas, t: Float) {
        // hair behind + face
        fill.color = HAIR
        c.drawCircle(50f, 44f, 32f, fill)
        fill.color = SKIN
        c.drawOval(24f, 27f, 76f, 77f, fill)
        // side locks
        fill.color = HAIR
        c.drawOval(19f, 38f, 30f, 68f, fill)
        c.drawOval(70f, 38f, 81f, 68f, fill)
        // bangs
        path.reset()
        path.moveTo(21f, 44f)
        path.quadTo(22f, 16f, 50f, 14f)
        path.quadTo(78f, 16f, 79f, 44f)
        path.quadTo(70f, 30f, 62f, 40f)
        path.quadTo(56f, 28f, 50f, 41f)
        path.quadTo(44f, 28f, 38f, 40f)
        path.quadTo(30f, 30f, 21f, 44f)
        path.close()
        fill.color = HAIR
        c.drawPath(path, fill)
        stroke.color = alphaOf(Color.WHITE, 90); stroke.strokeWidth = 1.6f
        path.reset(); path.moveTo(34f, 25f); path.quadTo(42f, 19f, 50f, 20f)
        c.drawPath(path, stroke)
        // hair ties on the tails + bow
        fill.color = PINK
        c.drawCircle(23f, 42f, 3.4f, fill)
        c.drawCircle(77f, 42f, 3.4f, fill)
        drawBow(c, 31f, 21f)

        // eyebrows
        stroke.color = HAIR_DARK; stroke.strokeWidth = 1.3f
        rect.set(32f, 38f, 44f, 44f); c.drawArc(rect, 200f, 140f, false, stroke)
        rect.set(56f, 38f, 68f, 44f); c.drawArc(rect, 200f, 140f, false, stroke)

        // blink
        var open = 1f
        if (blinkStart < 0f && t >= nextBlinkAt) blinkStart = t
        if (blinkStart >= 0f) {
            val p = (t - blinkStart) / 0.18f
            if (p >= 1f) {
                blinkStart = -1f
                nextBlinkAt = t + 2.2f + abs(sin(t * 3.7f)) * 2.8f
            } else {
                open = abs(1f - 2f * p).coerceAtLeast(0.05f)
            }
        }
        val happy = state == OrbAnimationView.State.HEARING
        val think = state == OrbAnimationView.State.THINKING
        val ox = if (think) 1.6f else 0f
        val oy = if (think) -1.8f else 0f
        val rightOpen = if (t < winkUntil) 0f else open
        drawEye(c, 38f, 53f, open, happy, ox, oy)
        drawEye(c, 62f, 53f, rightOpen, happy, ox, oy)

        // blush + nose
        val blush = if (happy || state == OrbAnimationView.State.SPEAKING) 150 else 110
        fill.color = alphaOf(Color.parseColor("#FF6F9F"), blush)
        c.drawOval(27f, 60f, 39f, 67f, fill)
        c.drawOval(61f, 60f, 73f, 67f, fill)
        fill.color = SKIN_SHADE
        c.drawCircle(50f, 60f, 0.9f, fill)

        // mouth
        val my = 67f
        when {
            think -> {
                fill.color = MOUTH
                c.drawOval(48.4f, my - 1.6f, 51.6f, my + 2.2f, fill)
            }
            mouth > 0.06f -> {
                val ry = 1.2f + 4.6f * mouth
                fill.color = MOUTH
                c.drawOval(46.4f, my - ry * 0.4f, 53.6f, my + ry, fill)
                fill.color = TONGUE
                c.drawOval(48f, my + ry * 0.35f, 52f, my + ry * 0.95f, fill)
            }
            else -> {
                stroke.color = MOUTH
                stroke.strokeWidth = 1.5f
                if (happy) rect.set(43f, 61f, 57f, 72f) else rect.set(45f, 62.5f, 55f, 70f)
                c.drawArc(rect, 25f, 130f, false, stroke)
            }
        }

        // thought bubbles
        if (think) {
            fill.color = Color.WHITE
            for (i in 0..2) {
                fill.alpha = (110 + 140 * abs(sin(t * 3f + i))).toInt().coerceIn(0, 255)
                val bx = 73f + i * 6f
                val by = 24f - i * 7f
                c.drawCircle(bx, by, 1.4f + i * 0.8f, fill)
            }
        }

        // hearts while talking / listening
        if ((state == OrbAnimationView.State.SPEAKING || happy) && t - lastSpawn > 0.45f) {
            lastSpawn = t
            spawn(t, 50f + Random.nextFloat() * 36f - 18f, 26f)
        }
    }

    private fun drawBow(c: Canvas, x: Float, y: Float) {
        fill.color = PINK
        path.reset()
        path.moveTo(x, y); path.lineTo(x - 10f, y - 6f); path.lineTo(x - 10f, y + 6f); path.close()
        path.moveTo(x, y); path.lineTo(x + 10f, y - 6f); path.lineTo(x + 10f, y + 6f); path.close()
        c.drawPath(path, fill)
        fill.color = Color.parseColor("#FF8FB8")
        c.drawCircle(x, y, 3f, fill)
    }

    private fun drawEye(c: Canvas, cx: Float, cy: Float, openness: Float, happy: Boolean, ox: Float, oy: Float) {
        if (happy || openness < 0.12f) {
            stroke.color = LASH
            stroke.strokeWidth = 2f
            rect.set(cx - 6f, cy - 4f, cx + 6f, cy + 6f)
            if (happy) c.drawArc(rect, 200f, 140f, false, stroke) else c.drawArc(rect, 20f, 140f, false, stroke)
            return
        }
        val rx = 6.4f
        val ry = 7.6f * openness
        fill.color = Color.WHITE
        c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, fill)
        fill.color = IRIS
        c.drawOval(cx + ox - 4.7f, cy + oy - 5.8f * openness, cx + ox + 4.7f, cy + oy + 5.8f * openness, fill)
        fill.color = PUPIL
        c.drawOval(cx + ox - 2.4f, cy + oy - 3f * openness, cx + ox + 2.4f, cy + oy + 3f * openness, fill)
        fill.color = Color.WHITE
        c.drawCircle(cx + ox - 1.8f, cy + oy - 2.2f * openness, 1.5f, fill)
        c.drawCircle(cx + ox + 1.6f, cy + oy + 2.4f * openness, 0.8f, fill)
        stroke.color = LASH
        stroke.strokeWidth = 1.8f
        rect.set(cx - rx - 0.2f, cy - ry - 0.2f, cx + rx + 0.2f, cy + ry + 0.2f)
        c.drawArc(rect, 195f, 150f, false, stroke)
    }

    private fun drawFloaters(c: Canvas, t: Float) {
        var i = 0
        while (i < floaters.size) {
            val f = floaters[i]
            val age = t - f.born
            val life = 1.9f
            if (age > life) {
                floaters.removeAt(i)
                continue
            }
            val p = age / life
            val x = f.x + f.vx * age + sin(age * 4f) * 2f
            val y = f.y + f.vy * age
            path.reset()
            HeartShape.add(path, x, y, f.size)
            fill.color = f.color
            fill.alpha = (230 * (1f - p)).toInt().coerceIn(0, 255)
            c.drawPath(path, fill)
            i++
        }
    }
}
