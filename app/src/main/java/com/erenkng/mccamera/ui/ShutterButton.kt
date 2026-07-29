package com.erenkng.mccamera.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.min

/**
 * The capture button.
 *
 * White in both photo and video mode; pressing record morphs the inner disc into
 * a red rounded square. The morph is one spring animation rather than an icon
 * swap, so the control reads as the same object changing state.
 */
class ShutterButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    enum class State { PHOTO, VIDEO_IDLE, RECORDING }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val innerRect = RectF()

    /** 0 = idle disc, 1 = recording square. */
    private var morph = 0f
    private var animator: ValueAnimator? = null

    var state: State = State.PHOTO
        set(value) {
            if (field == value) return
            field = value
            animateMorph(if (value == State.RECORDING) 1f else 0f)
            contentDescription = context.getString(
                if (value == State.RECORDING) {
                    com.erenkng.mccamera.R.string.stop_recording
                } else {
                    com.erenkng.mccamera.R.string.shutter
                }
            )
        }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(com.erenkng.mccamera.R.string.shutter)
    }

    private fun animateMorph(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(morph, target).apply {
            duration = 320
            interpolator = OvershootInterpolator(1.6f)
            addUpdateListener {
                morph = (it.animatedValue as Float).coerceIn(0f, 1f)
                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> Motion.pressIn(this, 0.90f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Motion.pressOut(this)
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val size = min(width, height).toFloat()

        val strokeWidth = size * 0.075f
        ringPaint.strokeWidth = strokeWidth
        canvas.drawCircle(cx, cy, size / 2f - strokeWidth * 1.2f, ringPaint)

        // Shrinks and squares off as it becomes the stop button.
        val idleRadius = size * 0.345f
        val recordHalf = size * 0.23f
        val half = idleRadius + (recordHalf - idleRadius) * morph
        val corner = idleRadius + (size * 0.07f - idleRadius) * morph

        innerPaint.color = blend(Color.WHITE, RECORD_RED, morph)
        innerRect.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRoundRect(innerRect, corner, corner, innerPaint)
    }

    private fun blend(from: Int, to: Int, t: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt(),
    )

    private companion object {
        val RECORD_RED = Color.rgb(0xE5, 0x39, 0x35)
    }
}
