package com.myra.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.random.Random

/** 20-bar waveform; bars lerp toward amplitude-driven targets. */
class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bars = 20
    private val heights = FloatArray(bars) { 0.05f }
    private val targets = FloatArray(bars) { 0.05f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF4D8D") }
    private var animator: ValueAnimator? = null

    fun setColor(c: Int) {
        paint.color = c
    }

    fun setAmplitude(rms: Float) {
        val a = rms.coerceIn(0f, 1f)
        for (i in 0 until bars) targets[i] = max(0.05f, a * (0.4f + 0.6f * Random.nextFloat()))
    }

    fun startAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                for (i in 0 until bars) {
                    heights[i] += (targets[i] - heights[i]) * 0.3f
                    targets[i] = max(0.05f, targets[i] * 0.9f)
                }
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val barW = width / (bars * 2f - 1f)
        for (i in 0 until bars) {
            val h = max(heights[i] * height, barW)
            val left = i * 2f * barW
            paint.alpha = (150 + 105 * heights[i].coerceIn(0f, 1f)).toInt()
            canvas.drawRoundRect(left, (height - h) / 2f, left + barW, (height + h) / 2f, barW / 2f, barW / 2f, paint)
        }
    }
}
