package com.erenkng.mccamera.ui

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * The app's motion language.
 *
 * Every control shrinks under the finger and springs back past its resting size
 * on release. Material 3 Expressive leans on exactly this — spring physics plus
 * haptics — because a control that answers the touch is found and trusted faster
 * than a static one.
 */
object Motion {

    private const val PRESS_MS = 90L
    private const val RELEASE_MS = 320L

    fun pressIn(view: View, scale: Float = 0.92f) {
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(PRESS_MS)
            .start()
    }

    fun pressOut(view: View) {
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(RELEASE_MS)
            .setInterpolator(OvershootInterpolator(2.4f))
            .start()
    }

    /** Gives a view the standard press feel without stealing its click. */
    fun springy(view: View, scale: Float = 0.92f) {
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> pressIn(target, scale)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressOut(target)
            }
            false
        }
    }

    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** Fades a view in or out without leaving it taking touches while hidden. */
    fun fade(view: View, visible: Boolean, duration: Long = 220L) {
        if (visible && view.visibility == View.VISIBLE && view.alpha == 1f) return
        if (visible) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(duration).start()
        } else {
            view.animate().alpha(0f).setDuration(duration)
                .withEndAction { view.visibility = View.GONE }
                .start()
        }
    }
}
