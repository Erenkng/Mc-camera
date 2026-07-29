package com.erenkng.mccamera.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.erenkng.mccamera.R

/**
 * A single-choice row of pill chips.
 *
 * Selection is expressed with colour *and* scale — the picked chip grows
 * slightly and the row scrolls it into view — so the current mode is readable in
 * a glance while the eye is on the subject rather than on the controls.
 */
class ChipStrip(
    private val container: LinearLayout,
    private val scroller: HorizontalScrollView?,
    private val onSelect: (Int) -> Unit,
) {

    private val chips = ArrayList<TextView>()

    fun setItems(labels: List<String>, selectedIndex: Int) {
        container.removeAllViews()
        chips.clear()

        val context = container.context
        labels.forEachIndexed { index, label ->
            val chip = createChip(context, label)
            chip.setOnClickListener {
                if (index == selected()) return@setOnClickListener
                Motion.tick(it)
                select(index)
                onSelect(index)
            }
            Motion.springy(chip, 0.94f)
            container.addView(chip)
            chips += chip
        }
        select(selectedIndex)
    }

    fun select(index: Int) {
        chips.forEachIndexed { i, chip ->
            val active = i == index
            if (chip.isSelected != active) {
                chip.isSelected = active
                chip.animate()
                    .scaleX(if (active) 1.06f else 1f)
                    .scaleY(if (active) 1.06f else 1f)
                    .setDuration(220)
                    .start()
            }
        }
        chips.getOrNull(index)?.let { chip ->
            scroller?.post {
                val target = chip.left - (scroller.width - chip.width) / 2
                scroller.smoothScrollTo(target.coerceAtLeast(0), 0)
            }
        }
    }

    private fun selected(): Int = chips.indexOfFirst { it.isSelected }

    private fun createChip(context: Context, label: String): TextView =
        TextView(context).apply {
            text = label
            isAllCaps = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.06f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColorStateList(context, R.color.chip_text_color))
            setBackgroundResource(R.drawable.bg_chip)
            val h = dp(context, 16)
            val v = dp(context, 9)
            setPadding(h, v, h, v)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(context, 8) }
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
