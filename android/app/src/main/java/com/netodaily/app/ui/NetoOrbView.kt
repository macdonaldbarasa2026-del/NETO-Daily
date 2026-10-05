package com.netodaily.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

class NetoOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State {
        IDLE,
        LISTENING,
        THINKING,
        SPEAKING
    }

    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var state = State.IDLE
    private var audioLevel = 0f
    private var pulsePhase = 0f

    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        contentDescription = "NETO Gemini Live Assistant"

        animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 3200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulsePhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setState(value: State) {
        state = value
        if (value == State.IDLE) {
            audioLevel = 0f
        }
        invalidate()
    }

    fun setAudioLevel(value: Float) {
        audioLevel = value.coerceIn(0f, 1f)
        invalidate()
    }

    fun currentState(): State = state

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val size = min(width, height).toFloat()

        val pulse = sin(pulsePhase) * 0.04f
        val level = if (state == State.LISTENING || state == State.SPEAKING) audioLevel else 0f

        val baseRadius = size * (0.32f + pulse + level * 0.10f)

        // 1. Exterior Glowing Aura (Gemini Radial Gradient)
        val outerColors = when (state) {
            State.IDLE -> intArrayOf(0x55087F68, 0x22054D3F, 0x000A1210)
            State.LISTENING -> intArrayOf(0x8800E5A3, 0x33087F68, 0x000A1210)
            State.THINKING -> intArrayOf(0x773B82F6, 0x331E3A8A, 0x000A1210)
            State.SPEAKING -> intArrayOf(0x9900F2FE, 0x444FACFE, 0x000A1210)
        }
        val auraRadius = baseRadius * 1.6f
        auraPaint.shader = RadialGradient(cx, cy, auraRadius, outerColors, floatArrayOf(0.1f, 0.65f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, auraRadius, auraPaint)

        // 2. Middle Fluid Wave Ring
        wavePaint.style = Paint.Style.STROKE
        wavePaint.strokeWidth = size * (0.015f + level * 0.02f)
        val waveColor = when (state) {
            State.IDLE -> 0x6600E5A3.toInt()
            State.LISTENING -> 0xCC00E5A3.toInt()
            State.THINKING -> 0xAA60A5FA.toInt()
            State.SPEAKING -> 0xEE38BDF8.toInt()
        }
        wavePaint.color = waveColor
        canvas.drawCircle(cx, cy, baseRadius * 1.15f, wavePaint)

        // 3. Inner Luminous Core
        val coreColors = when (state) {
            State.IDLE -> intArrayOf(0xFF1B6B58.toInt(), 0xFF0B332A.toInt())
            State.LISTENING -> intArrayOf(0xFF00E5A3.toInt(), 0xFF087F68.toInt())
            State.THINKING -> intArrayOf(0xFF93C5FD.toInt(), 0xFF2563EB.toInt())
            State.SPEAKING -> intArrayOf(0xFF67E8F9.toInt(), 0xFF0284C7.toInt())
        }
        corePaint.shader = RadialGradient(cx, cy, baseRadius, coreColors, null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, baseRadius, corePaint)

        // 4. Center Gemini Spark Shape
        val sparkRadius = baseRadius * (0.36f + level * 0.08f)
        sparkPaint.color = Color.WHITE
        sparkPaint.alpha = 240
        sparkPaint.style = Paint.Style.FILL

        val sparkPath = Path().apply {
            moveTo(cx, cy - sparkRadius)
            quadTo(cx, cy, cx + sparkRadius, cy)
            quadTo(cx, cy, cx, cy + sparkRadius)
            quadTo(cx, cy, cx - sparkRadius, cy)
            quadTo(cx, cy, cx, cy - sparkRadius)
            close()
        }
        canvas.drawPath(sparkPath, sparkPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
