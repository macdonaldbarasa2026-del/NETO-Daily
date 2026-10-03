package com.netodaily.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

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

    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var state = State.IDLE
    private var audioLevel = 0f

    init {
        isClickable = true
        contentDescription = "NETO voice assistant"
    }

    fun setState(value: State) {
        state = value

        if (value == State.IDLE) {
            audioLevel = 0f
        }

        invalidate()
    }

    fun setAudioLevel(value: Float) {
        audioLevel =
            value
                .coerceIn(0f, 1f)

        invalidate()
    }

    fun currentState(): State = state

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val size = min(width, height).toFloat()

        val level =
            if (
                state == State.LISTENING ||
                state == State.SPEAKING
            ) {
                audioLevel
            } else {
                0f
            }

        val outerRadius =
            size * (
                0.38f +
                    level * 0.035f
            )

        val coreRadius =
            size * (
                0.28f +
                    level * 0.018f
            )

        val accent =
            when (state) {
                State.IDLE ->
                    0xFF6B705C.toInt()

                State.LISTENING ->
                    0xFF5C8D78.toInt()

                State.THINKING ->
                    0xFF697A72.toInt()

                State.SPEAKING ->
                    0xFF2E6A45.toInt()
            }

        outerPaint.style = Paint.Style.FILL
        outerPaint.color = 0x146B705C

        canvas.drawCircle(
            cx,
            cy,
            outerRadius,
            outerPaint
        )

        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeWidth =
            size * (
                0.012f +
                    level * 0.006f
            )

        ringPaint.color = accent
        ringPaint.alpha =
            (
                90 +
                    level * 100
            ).toInt().coerceIn(0, 190)

        canvas.drawCircle(
            cx,
            cy,
            outerRadius * 0.88f,
            ringPaint
        )

        corePaint.style = Paint.Style.FILL
        corePaint.color =
            when (state) {
                State.IDLE ->
                    0xFFE7EEEA.toInt()

                State.LISTENING ->
                    0xFFDCEBE3.toInt()

                State.THINKING ->
                    0xFFE2E9E6.toInt()

                State.SPEAKING ->
                    0xFFD7E9DF.toInt()
            }

        corePaint.alpha = 255

        canvas.drawCircle(
            cx,
            cy,
            coreRadius,
            corePaint
        )

        corePaint.color = accent
        corePaint.alpha = 235

        val centerRadius =
            coreRadius *
                (
                    0.58f +
                        level * 0.08f
                )

        canvas.drawCircle(
            cx,
            cy,
            centerRadius,
            corePaint
        )

        textPaint.color = 0xFF16372A.toInt()
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = size * 0.055f
        textPaint.alpha = 230

        canvas.drawText(
            when (state) {
                State.IDLE -> "NETO"
                State.LISTENING -> "LISTEN"
                State.THINKING -> "..."
                State.SPEAKING -> "NETO"
            },
            cx,
            cy + size * 0.02f,
            textPaint
        )
    }
}
