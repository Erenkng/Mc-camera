package com.erenkng.mccamera.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import com.erenkng.mccamera.R
import com.erenkng.mccamera.gl.MosaicRenderer
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/** The settings sheet: grid density, light matching and resource pack picking. */
class SettingsSheet(
    context: Context,
    private val state: State,
    private val callbacks: Callbacks,
) {

    class State(
        val density: Int,
        val shadeEnabled: Boolean,
        val packName: String,
        val blockCount: Int,
    )

    interface Callbacks {
        fun onDensityChanged(density: Int)
        fun onShadeChanged(enabled: Boolean)
        fun onImportPack()
        fun onResetPack()
    }

    private val dialog = BottomSheetDialog(context)

    fun show() {
        val view = LayoutInflater.from(dialog.context).inflate(R.layout.sheet_settings, null)
        dialog.setContentView(view)

        val steps = MosaicRenderer.DENSITY_STEPS
        val densityLabel = view.findViewById<TextView>(R.id.densityValue)
        val slider = view.findViewById<Slider>(R.id.densitySlider)

        slider.valueFrom = 0f
        slider.valueTo = (steps.size - 1).toFloat()
        slider.stepSize = 1f
        val startIndex = steps.indexOfFirst { it >= state.density }.let { if (it < 0) steps.size - 1 else it }
        slider.value = startIndex.toFloat()
        densityLabel.text = dialog.context.getString(R.string.density_value, steps[startIndex])

        slider.addOnChangeListener { _, value, _ ->
            val blocks = steps[value.toInt().coerceIn(0, steps.size - 1)]
            densityLabel.text = dialog.context.getString(R.string.density_value, blocks)
            callbacks.onDensityChanged(blocks)
        }

        view.findViewById<MaterialSwitch>(R.id.shadeSwitch).apply {
            isChecked = state.shadeEnabled
            setOnCheckedChangeListener { _, checked -> callbacks.onShadeChanged(checked) }
        }

        view.findViewById<TextView>(R.id.packName).text =
            dialog.context.getString(R.string.pack_summary, state.packName, state.blockCount)

        view.findViewById<MaterialButton>(R.id.importPack).setOnClickListener {
            dialog.dismiss()
            callbacks.onImportPack()
        }
        view.findViewById<MaterialButton>(R.id.resetPack).setOnClickListener {
            dialog.dismiss()
            callbacks.onResetPack()
        }

        dialog.show()
    }
}
