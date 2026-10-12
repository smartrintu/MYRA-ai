package com.myra.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Soft hearts drifting upward behind everything. Faster while MYRA talks / listens. */
class FloatingHeartsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class H(var x: Float, var y: Float, val size: Float, val speed: Float, val phase: Float, val color: Int, val alpha: Int)

    private val density = resources.displayMetrics.density
    private val hearts = ArrayList<H>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private var colors = intArrayOf(
        Color.parseColor("#FF8FB8"), Color.parseColor("#FFC2D4"), Color.parseColor("#C9A7FF"),
        Color.parseColor("#FF4D8D"), Color.parseColor("#FFD1E3")
    )
    private var lively = false
    private var last = SystemClock.uptimeMillis()
    private var animator: ValueAnimator? = null

    fun setPalette(c: IntArray) {
        if (c.isNotEmpty()) colors = c
    }

    fun setLively(on: Boolean) {
        lively = on
    }

    private fun newHeart(randomY: Boolean): H {
        val w = max(width, 1).toFloat()
        val h = max(height, 1).toFloat()
        val size = (5f + Random.nextFloat() * 10f) * density
        val y = if (randomY) Random.nextFloat() * h else h + size * 2
        return H(
            x = Random.nextFloat() * w, y = y, size = size,
            speed = (14f + Random.nextFloat() * 22f) * density,
            phase = Random.nextFloat() * 6.28f,
            color = colors[Random.nextInt(colors.size)],
            alpha = 40 + Random.nextInt(60)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        hearts.clear()
        repeat(14) { hearts.add(newHeart(true)) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - last) / 1000f).coerceIn(0f, 0.1f)
        last = now
        val t = now / 1000f
        val boost = if (lively) 1.9f else 1f
        for (i in hearts.indices) {
            val h = hearts[i]
            h.y -= h.speed * dt * boost
            if (h.y < -h.size * 2) hearts[i] = newHeart(false)
            val x = h.x + sin(h.phase + t * 0.8f) * 14f * density
            path.reset()
            HeartShape.add(path, x, h.y, h.size)
            paint.color = h.color
            paint.alpha = h.alpha
            canvas.drawPath(path, paint)
        }
    }
}
