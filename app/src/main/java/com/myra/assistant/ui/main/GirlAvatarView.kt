package com.myra.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * MYRA's face. Two modes:
 *  - drawn, semi-realistic portrait (shaded skin, natural eyes, lips, long hair) that blinks, breathes and lip-syncs;
 *  - real photo chosen by the user (gallery) with breathing, voice-reactive glow and floating hearts.
 * Everything is drawn in a 100x100 box scaled to the view. Tap = wink / hearts.
 */
class GirlAvatarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class Floater(var x: Float, var y: Float, val vx: Float, val vy: Float, val size: Float, val born: Float, val color: Int)

    private val HAIR_TOP = Color.parseColor("#2A1A15")
    private val HAIR_BOTTOM = Color.parseColor("#5B3A2B")
    private val SKIN_LIGHT = Color.parseColor("#FCE4D4")
    private val SKIN_EDGE = Color.parseColor("#E8B7A0")
    private val SKIN_SHADE = Color.parseColor("#D9A38B")
    private val BROW = Color.parseColor("#3A2417")
    private val LASH = Color.parseColor("#1E1210")
    private val GOLD = Color.parseColor("#FFD54F")

    // ---- state ----
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

    private var themeP = Color.parseColor("#FF4D8D")
    private var themeS = Color.parseColor("#B388FF")
    private var glow1 = themeP
    private var glow2 = themeS
    private var heartColors = intArrayOf(themeP, themeS, Color.parseColor("#FFD1E3"), Color.parseColor("#FF8FB8"))

    // ---- photo mode ----
    private var photoPaint: Paint? = null

    // ---- paints / shaders / paths (100-unit space) ----
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rect = RectF()

    private val hairShader: Shader = LinearGradient(0f, 14f, 0f, 100f, HAIR_TOP, HAIR_BOTTOM, Shader.TileMode.CLAMP)
    private val faceShader: Shader = RadialGradient(46f, 42f, 42f, SKIN_LIGHT, SKIN_EDGE, Shader.TileMode.CLAMP)
    private val neckShader: Shader = LinearGradient(0f, 64f, 0f, 84f, SKIN_SHADE, SKIN_LIGHT, Shader.TileMode.CLAMP)
    private val bodyShader: Shader = LinearGradient(0f, 80f, 0f, 100f, SKIN_LIGHT, SKIN_EDGE, Shader.TileMode.CLAMP)
    private val blushLeft: Shader = RadialGradient(
        33f, 60f, 8f, Color.argb(150, 255, 120, 150), Color.argb(0, 255, 120, 150), Shader.TileMode.CLAMP
    )
    private val blushRight: Shader = RadialGradient(
        67f, 60f, 8f, Color.argb(150, 255, 120, 150), Color.argb(0, 255, 120, 150), Shader.TileMode.CLAMP
    )

    private val facePath = Path().apply {
        moveTo(50f, 22f)
        cubicTo(36f, 22f, 30f, 34f, 31f, 46f)
        cubicTo(32f, 60f, 40f, 74f, 50f, 78f)
        cubicTo(60f, 74f, 68f, 60f, 69f, 46f)
        cubicTo(70f, 34f, 64f, 22f, 50f, 22f)
        close()
    }
    private val hairBackPath = Path().apply {
        moveTo(20f, 46f)
        cubicTo(14f, 64f, 14f, 86f, 20f, 100f)
        lineTo(80f, 100f)
        cubicTo(86f, 86f, 86f, 64f, 80f, 46f)
        cubicTo(80f, 26f, 66f, 14f, 50f, 14f)
        cubicTo(34f, 14f, 20f, 26f, 20f, 46f)
        close()
    }
    private val bangsPath = Path().apply {
        moveTo(28f, 52f)
        cubicTo(25f, 28f, 38f, 15f, 52f, 15f)
        cubicTo(66f, 15f, 76f, 26f, 72f, 50f)
        cubicTo(70f, 36f, 62f, 29f, 53f, 28f)
        cubicTo(47f, 33f, 36f, 40f, 31f, 56f)
        close()
    }
    private val lockLeftPath = Path().apply {
        moveTo(30f, 46f)
        cubicTo(24f, 62f, 24f, 82f, 28f, 98f)
        cubicTo(35f, 84f, 33f, 62f, 33f, 50f)
        close()
    }
    private val lockRightPath = Path().apply {
        moveTo(70f, 46f)
        cubicTo(76f, 62f, 76f, 82f, 72f, 98f)
        cubicTo(65f, 84f, 67f, 62f, 67f, 50f)
        close()
    }
    private val neckPath = Path().apply {
        moveTo(43f, 64f); lineTo(57f, 64f)
        cubicTo(57f, 74f, 58f, 79f, 62f, 83f)
        lineTo(38f, 83f)
        cubicTo(42f, 79f, 43f, 74f, 43f, 64f)
        close()
    }
    private val shouldersPath = Path().apply {
        moveTo(18f, 100f)
        cubicTo(20f, 88f, 32f, 82f, 42f, 80f)
        lineTo(58f, 80f)
        cubicTo(68f, 82f, 80f, 88f, 82f, 100f)
        close()
    }
    private val dressPath = Path().apply {
        moveTo(14f, 100f)
        cubicTo(16f, 90f, 28f, 85f, 36f, 84f)
        lineTo(50f, 97f); lineTo(64f, 84f)
        cubicTo(72f, 85f, 84f, 90f, 86f, 100f)
        close()
    }

    init {
        setOnClickListener { burst() }
    }

    // ------------------------------------------------------------------ public API

    fun setState(s: OrbAnimationView.State) {
        if (s == state) return
        state = s
        applyStateColors()
        invalidate()
    }

    fun setAmplitude(a: Float) {
        targetAmp = a.coerceIn(0f, 1f)
    }

    fun setThemeColors(p: Int, s: Int) {
        themeP = p
        themeS = s
        applyStateColors()
        invalidate()
    }

    /** null = back to the drawn portrait. */
    fun setPhoto(b: Bitmap?) {
        if (b == null) {
            photoPaint = null
        } else {
            val shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val dstAspect = PW / PH
            val srcAspect = b.width.toFloat() / b.height
            val sw: Float
            val sh: Float
            if (srcAspect > dstAspect) {
                sh = b.height.toFloat(); sw = sh * dstAspect
            } else {
                sw = b.width.toFloat(); sh = sw / dstAspect
            }
            val sx = (b.width - sw) / 2f
            val sy = (b.height - sh) * 0.2f // keep faces near the top
            val m = Matrix()
            m.setRectToRect(RectF(sx, sy, sx + sw, sy + sh), RectF(PL, PT, PL + PW, PT + PH), Matrix.ScaleToFit.FILL)
            shader.setLocalMatrix(m)
            photoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader; isFilterBitmap = true }
        }
        invalidate()
    }

    // ------------------------------------------------------------------ colours

    private fun lighten(c: Int) = ColorUtils.blendARGB(c, Color.WHITE, 0.35f)

    private fun applyStateColors() {
        when (state) {
            OrbAnimationView.State.IDLE -> { glow1 = lighten(themeP); glow2 = themeS }
            OrbAnimationView.State.LISTENING, OrbAnimationView.State.ACTIVE -> { glow1 = themeP; glow2 = themeS }
            OrbAnimationView.State.HEARING -> { glow1 = Color.parseColor("#FFD54F"); glow2 = Color.parseColor("#FF8A65") }
            OrbAnimationView.State.SPEAKING -> { glow1 = themeS; glow2 = themeP }
            OrbAnimationView.State.THINKING -> { glow1 = Color.parseColor("#80D8FF"); glow2 = themeS }
        }
        heartColors = intArrayOf(themeP, lighten(themeP), themeS, Color.parseColor("#FFD1E3"))
        glowPaint.shader = RadialGradient(
            50f, 50f, 50f,
            intArrayOf(ColorUtils.setAlphaComponent(glow1, 170), ColorUtils.setAlphaComponent(glow2, 0)),
            floatArrayOf(0.45f, 1f), Shader.TileMode.CLAMP
        )
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyStateColors()
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

    // ------------------------------------------------------------------ drawing

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

        glowPaint.alpha = (200 + 55 * sin(t * 2f)).toInt().coerceIn(0, 255)
        canvas.drawCircle(50f, 50f, 50f, glowPaint)

        if (photoPaint != null) drawPhoto(canvas, t) else drawPortrait(canvas, t)
        drawFloaters(canvas, t)
        maybeSpawnHearts(t)
        canvas.restore()
    }

    private fun maybeSpawnHearts(t: Float) {
        val talking = state == OrbAnimationView.State.SPEAKING || state == OrbAnimationView.State.HEARING
        if (talking && t - lastSpawn > 0.45f) {
            lastSpawn = t
            spawn(t, 50f + Random.nextFloat() * 36f - 18f, 26f)
        }
    }

    private fun drawPhoto(c: Canvas, t: Float) {
        val ph = photoPaint ?: return
        val scale = 1f + 0.012f * sin(t * 2f) + 0.03f * amp
        c.save()
        c.scale(scale, scale, 50f, 52f)
        c.rotate(sin(t * 0.8f) * 0.8f, 50f, 52f)
        ringPaint.color = glow1
        ringPaint.strokeWidth = 1.4f + 2.2f * amp
        ringPaint.alpha = (170 + 70 * sin(t * 2.5f)).toInt().coerceIn(0, 255)
        rect.set(PL - 2.5f, PT - 2.5f, PL + PW + 2.5f, PT + PH + 2.5f)
        c.drawRoundRect(rect, 24f, 24f, ringPaint)
        rect.set(PL, PT, PL + PW, PT + PH)
        c.drawRoundRect(rect, 21f, 21f, ph)
        c.restore()
        if (state == OrbAnimationView.State.THINKING) drawThoughts(c, t)
    }

    private fun drawPortrait(c: Canvas, t: Float) {
        val breathe = 1f + 0.012f * sin(t * 2.2f)
        val bob = sin(t * 1.6f) * 1.0f
        val tilt = sin(t * 0.9f) * (if (state == OrbAnimationView.State.THINKING) 4f else 2f)
        val sway = sin(t * 1.9f) * (0.6f + 2.2f * amp)

        c.save()
        c.scale(1f, breathe, 50f, 100f)
        c.translate(0f, bob)

        // long hair behind
        c.save()
        c.rotate(sway, 50f, 30f)
        fill.shader = hairShader
        c.drawPath(hairBackPath, fill)
        fill.shader = null
        c.restore()

        drawBody(c)

        c.save()
        c.rotate(tilt, 50f, 82f)
        drawHead(c, t)
        c.restore()

        c.restore()
    }

    private fun drawBody(c: Canvas) {
        fill.shader = bodyShader
        c.drawPath(shouldersPath, fill)
        fill.shader = neckShader
        c.drawPath(neckPath, fill)
        fill.shader = null
        fill.color = themeP
        c.drawPath(dressPath, fill)
        // necklace with a little heart
        stroke.color = GOLD
        stroke.strokeWidth = 0.6f
        path.reset(); path.moveTo(41f, 81f); path.quadTo(50f, 95f, 59f, 81f)
        c.drawPath(path, stroke)
        fill.color = GOLD
        path.reset(); HeartShape.add(path, 50f, 89.4f, 1.9f)
        c.drawPath(path, fill)
    }

    private fun drawHead(c: Canvas, t: Float) {
        // face with soft shading
        fill.shader = faceShader
        c.drawPath(facePath, fill)
        fill.shader = null

        val happy = state == OrbAnimationView.State.HEARING
        val think = state == OrbAnimationView.State.THINKING
        val talking = state == OrbAnimationView.State.SPEAKING

        // cheeks
        fill.shader = blushLeft
        fill.alpha = if (happy || talking) 255 else 190
        c.drawCircle(33f, 60f, 8f, fill)
        fill.shader = blushRight
        fill.alpha = if (happy || talking) 255 else 190
        c.drawCircle(67f, 60f, 8f, fill)
        fill.shader = null

        // eyebrows
        fill.color = BROW
        path.reset()
        path.moveTo(32.5f, 44.5f); path.cubicTo(36f, 40.2f, 43f, 40f, 47.5f, 42.4f)
        path.cubicTo(43f, 41.9f, 37f, 42.6f, 32.5f, 46f); path.close()
        c.drawPath(path, fill)
        path.reset()
        path.moveTo(67.5f, 44.5f); path.cubicTo(64f, 40.2f, 57f, 40f, 52.5f, 42.4f)
        path.cubicTo(57f, 41.9f, 63f, 42.6f, 67.5f, 46f); path.close()
        c.drawPath(path, fill)

        // eyes
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
        val ox = if (think) 1.2f else 0f
        val oy = if (think) -1.2f else 0f
        val rightOpen = if (t < winkUntil) 0f else open
        drawEye(c, 39f, 50f, open, happy, ox, oy, -1f)
        drawEye(c, 61f, 50f, rightOpen, happy, ox, oy, 1f)

        // nose
        stroke.color = ColorUtils.setAlphaComponent(SKIN_SHADE, 110)
        stroke.strokeWidth = 0.8f
        path.reset(); path.moveTo(48.4f, 47f); path.quadTo(47.6f, 55f, 46.8f, 58.5f)
        c.drawPath(path, stroke)
        fill.color = ColorUtils.setAlphaComponent(Color.parseColor("#B9806A"), 170)
        c.drawOval(47.2f, 60.4f, 49.2f, 61.9f, fill)
        c.drawOval(50.8f, 60.4f, 52.8f, 61.9f, fill)
        fill.color = ColorUtils.setAlphaComponent(Color.WHITE, 80)
        c.drawOval(49f, 57.4f, 51f, 59.2f, fill)

        drawMouth(c, happy, think)

        // hair in front
        fill.shader = hairShader
        c.drawPath(bangsPath, fill)
        c.drawPath(lockLeftPath, fill)
        c.drawPath(lockRightPath, fill)
        fill.shader = null
        stroke.color = ColorUtils.setAlphaComponent(Color.WHITE, 55)
        stroke.strokeWidth = 0.8f
        path.reset(); path.moveTo(36f, 24f); path.quadTo(46f, 18f, 58f, 22f)
        c.drawPath(path, stroke)
        path.reset(); path.moveTo(27f, 58f); path.quadTo(25.5f, 74f, 28f, 90f)
        c.drawPath(path, stroke)
        path.reset(); path.moveTo(73f, 58f); path.quadTo(74.5f, 74f, 72f, 90f)
        c.drawPath(path, stroke)

        if (think) drawThoughts(c, t)
    }

    private fun drawEye(c: Canvas, cx: Float, cy: Float, open: Float, happy: Boolean, ox: Float, oy: Float, dir: Float) {
        if (happy || open < 0.12f) {
            stroke.color = LASH
            stroke.strokeWidth = 1.7f
            rect.set(cx - 5.6f, cy - 3.2f, cx + 5.6f, cy + 4.2f)
            if (happy) c.drawArc(rect, 200f, 140f, false, stroke) else c.drawArc(rect, 20f, 140f, false, stroke)
            return
        }
        c.save()
        c.scale(1f, open, cx, cy)

        // almond shape
        path.reset()
        path.moveTo(cx - 5.6f, cy)
        path.cubicTo(cx - 2.5f, cy - 5.2f, cx + 2.5f, cy - 5.2f, cx + 5.6f, cy)
        path.cubicTo(cx + 2.5f, cy + 3.6f, cx - 2.5f, cy + 3.6f, cx - 5.6f, cy)
        path.close()
        fill.color = Color.parseColor("#F8F2F0")
        c.drawPath(path, fill)

        c.save()
        c.clipPath(path)
        fill.color = Color.parseColor("#3A2012")
        c.drawCircle(cx + ox, cy + oy, 3.4f, fill)
        fill.color = Color.parseColor("#8A5A34")
        c.drawCircle(cx + ox, cy + oy, 2.6f, fill)
        fill.color = Color.parseColor("#140A06")
        c.drawCircle(cx + ox, cy + oy, 1.3f, fill)
        fill.color = Color.WHITE
        c.drawCircle(cx + ox - 1f, cy + oy - 1.3f, 0.9f, fill)
        c.drawCircle(cx + ox + 1.1f, cy + oy + 1.1f, 0.4f, fill)
        c.restore()

        // eyeliner + lashes
        stroke.color = LASH
        stroke.strokeWidth = 1.4f
        path.reset()
        path.moveTo(cx - 5.6f, cy)
        path.cubicTo(cx - 2.5f, cy - 5.2f, cx + 2.5f, cy - 5.2f, cx + 5.6f, cy)
        c.drawPath(path, stroke)
        stroke.strokeWidth = 0.7f
        val ex = cx + dir * 5.2f // outer corner side
        c.drawLine(ex, cy - 0.8f, ex + dir * 1.9f, cy - 2.4f, stroke)
        c.drawLine(cx + dir * 3.6f, cy - 2.7f, cx + dir * 4.9f, cy - 4.5f, stroke)
        c.drawLine(cx + dir * 1.6f, cy - 3.5f, cx + dir * 2.4f, cy - 5.3f, stroke)
        // eyelid crease
        stroke.color = ColorUtils.setAlphaComponent(SKIN_SHADE, 150)
        stroke.strokeWidth = 0.6f
        path.reset(); path.moveTo(cx - 4.6f, cy - 5.4f); path.quadTo(cx, cy - 7.8f, cx + 4.6f, cy - 5.2f)
        c.drawPath(path, stroke)
        c.restore()
    }

    private fun drawMouth(c: Canvas, happy: Boolean, think: Boolean) {
        val lift = if (happy) 1.6f else if (think) 0.2f else 0.7f
        val jaw = mouth * 3.8f
        val yc = 67.6f - lift

        if (jaw > 0.3f) {
            fill.color = Color.parseColor("#4A1018")
            c.drawOval(44.6f, 67.4f, 55.4f, 68.8f + jaw, fill)
            fill.color = Color.parseColor("#F7F2EE")
            rect.set(46.6f, 67.6f, 53.4f, 67.6f + min(jaw, 1.8f))
            c.drawRoundRect(rect, 0.6f, 0.6f, fill)
        }
        // lower lip
        c.save()
        c.translate(0f, jaw)
        path.reset()
        path.moveTo(43.4f, yc + 0.3f)
        path.cubicTo(46.5f, yc + 1.3f, 53.5f, yc + 1.3f, 56.6f, yc + 0.3f)
        path.cubicTo(54f, yc + 4.8f, 46f, yc + 4.8f, 43.4f, yc + 0.3f)
        path.close()
        fill.color = Color.parseColor("#D9667B")
        c.drawPath(path, fill)
        if (jaw < 0.3f) {
            fill.color = ColorUtils.setAlphaComponent(Color.WHITE, 70)
            c.drawOval(47.5f, yc + 2f, 52.5f, yc + 3.1f, fill)
        }
        c.restore()
        // upper lip
        path.reset()
        path.moveTo(43f, yc)
        path.cubicTo(46f, yc - 2.8f, 48.6f, yc - 2.4f, 50f, yc - 1.2f)
        path.cubicTo(51.4f, yc - 2.4f, 54f, yc - 2.8f, 57f, yc)
        path.cubicTo(54f, yc + 1f, 46f, yc + 1f, 43f, yc)
        path.close()
        fill.color = Color.parseColor("#C24A63")
        c.drawPath(path, fill)
        if (jaw < 0.3f) {
            stroke.color = Color.parseColor("#9E3A4D")
            stroke.strokeWidth = 0.5f
            path.reset(); path.moveTo(43f, yc); path.cubicTo(46f, yc + 0.9f, 54f, yc + 0.9f, 57f, yc)
            c.drawPath(path, stroke)
        }
    }

    private fun drawThoughts(c: Canvas, t: Float) {
        fill.color = Color.WHITE
        for (i in 0..2) {
            fill.alpha = (110 + 140 * abs(sin(t * 3f + i))).toInt().coerceIn(0, 255)
            c.drawCircle(76f + i * 5f, 22f - i * 6f, 1.4f + i * 0.8f, fill)
        }
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
            val x = f.x + f.vx * age + sin(age * 4f) * 2f
            val y = f.y + f.vy * age
            path.reset()
            HeartShape.add(path, x, y, f.size)
            fill.color = f.color
            fill.alpha = (230 * (1f - age / life)).toInt().coerceIn(0, 255)
            c.drawPath(path, fill)
            i++
        }
    }

    private companion object {
        const val PL = 14f
        const val PT = 4f
        const val PW = 72f
        const val PH = 92f
    }
}
