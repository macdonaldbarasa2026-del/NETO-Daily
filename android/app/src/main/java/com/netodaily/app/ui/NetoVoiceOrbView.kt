package com.netodaily.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class NetoVoiceOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State {
        IDLE,
        LISTENING,
        THINKING,
        SPEAKING
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var state = State.IDLE
    private var audioLevel = 0f
    private var phase = 0f

    private val particles = Array(42) { index ->
        Particle(
            angle = (index * 2.39996f),
            radius = 0.58f + ((index * 17) % 38) / 100f,
            size = 1.2f + ((index * 13) % 22) / 10f
        )
    }

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    fun setState(newState: State) {
        state = newState
        invalidate()
    }

    fun setAudioLevel(level: Float) {
        audioLevel = level.coerceIn(0f, 1f)
        invalidate()
    }

    private fun accent(): Int {
        return when (state) {
            State.IDLE -> 0xFF6B705C.toInt()
            State.LISTENING -> 0xFF5C8D78.toInt()
            State.THINKING -> 0xFF697A72.toInt()
            State.SPEAKING -> 0xFF2E6A45.toInt()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val size = minOf(width, height).toFloat()

        phase += 0.018f

        val accent = accent()

        val activity = when (state) {
            State.IDLE -> 0.12f
            State.LISTENING -> 0.32f + audioLevel * 0.7f
            State.THINKING -> 0.38f
            State.SPEAKING -> 0.35f + audioLevel * 0.8f
        }

        val baseRadius = size * 0.27f
        val pulse = sin(phase.toDouble() * 2.0).toFloat() * size * 0.008f
        val reactiveRadius = baseRadius + pulse + audioLevel * size * 0.045f

        paint.shader = RadialGradient(
            cx,
            cy,
            reactiveRadius * 1.28f,
            intArrayOf(
                0xFFFDFEFE.toInt(),
                0xFFE7EEEA.toInt(),
                accent,
                0x00000000
            ),
            floatArrayOf(0f, 0.45f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.alpha = 235
        canvas.drawCircle(cx, cy, reactiveRadius * 1.22f, paint)

        corePaint.shader = RadialGradient(
            cx - reactiveRadius * 0.22f,
            cy - reactiveRadius * 0.24f,
            reactiveRadius * 1.15f,
            intArrayOf(
                0xFFFFFFFF.toInt(),
                0xFFEAF1ED.toInt(),
                accent,
                0xFF16372A.toInt()
            ),
            floatArrayOf(0f, 0.34f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        corePaint.alpha = 245

        canvas.drawCircle(cx, cy, reactiveRadius, corePaint)

        paint.shader = null
        paint.style = Paint.Style.FILL

        particles.forEachIndexed { index, particle ->
            val speed = 0.004f + activity * 0.008f
            val angle = particle.angle + phase * speed * (index % 3 + 1)

            val radius = reactiveRadius *
                (1.18f + sin((phase * 2f + index).toDouble()).toFloat() * 0.055f)

            val x = cx + cos(angle.toDouble()).toFloat() * radius
            val y = cy + sin(angle.toDouble()).toFloat() * radius

            val alpha = (45 + activity * 160).toInt().coerceIn(0, 210)

            paint.color = accent
            paint.alpha = alpha

            canvas.drawCircle(
                x,
                y,
                particle.size * (0.7f + activity),
                paint
            )
        }

        if (state == State.LISTENING || state == State.SPEAKING) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = accent
            paint.alpha = 50

            canvas.drawCircle(
                cx,
                cy,
                reactiveRadius * (1.16f + audioLevel * 0.08f),
                paint
            )

            paint.style = Paint.Style.FILL
        }

        postInvalidateOnAnimation()
    }

    private data class Particle(
        val angle: Float,
        val radius: Float,
        val size: Float
    )
}
