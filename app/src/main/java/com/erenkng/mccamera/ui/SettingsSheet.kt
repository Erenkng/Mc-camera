package com.erenkng.mccamera.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.erenkng.mccamera.R
import com.erenkng.mccamera.gl.MosaicRenderer
import com.erenkng.mccamera.palette.BlockPalette
import com.erenkng.mccamera.palette.DefaultPack
import com.erenkng.mccamera.palette.PackLibrary
import com.erenkng.mccamera.video.VideoRecorder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * The settings sheet. Every control applies immediately so the preview behind
 * the sheet shows the result while it is still open.
 */
class SettingsSheet(
    private val context: Context,
    private val settings: AppSettings,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onSettingsChanged()
        fun onImportPack()
        fun onSelectPack(id: String)
        fun onDeletePack(id: String)
    }

    private val dialog = BottomSheetDialog(context)

    fun show() {
        val view = LayoutInflater.from(dialog.context).inflate(R.layout.sheet_settings, null)
        dialog.setContentView(view)

        bindDensity(view)
        bindStyle(view)
        bindColour(view)
        bindOutput(view)
        bindPacks(view)

        dialog.show()
    }

    // ------------------------------------------------------------------ look

    private fun bindDensity(view: View) {
        val steps = MosaicRenderer.DENSITY_STEPS
        val label = view.findViewById<TextView>(R.id.densityValue)
        val slider = view.findViewById<Slider>(R.id.densitySlider)

        slider.valueFrom = 0f
        slider.valueTo = (steps.size - 1).toFloat()
        slider.stepSize = 1f
        val start = steps.indexOfFirst { it >= settings.density }.let {
            if (it < 0) steps.size - 1 else it
        }
        slider.value = start.toFloat()
        label.text = context.getString(R.string.density_value, steps[start])

        slider.addOnChangeListener { _, value, _ ->
            val blocks = steps[value.toInt().coerceIn(0, steps.size - 1)]
            label.text = context.getString(R.string.density_value, blocks)
            settings.density = blocks
            callbacks.onSettingsChanged()
        }
    }

    private fun bindStyle(view: View) {
        percentSlider(
            view.findViewById(R.id.shadeSlider),
            view.findViewById(R.id.shadeValue),
            settings.shade,
        ) {
            settings.shade = it
            callbacks.onSettingsChanged()
        }

        percentSlider(
            view.findViewById(R.id.ditherSlider),
            view.findViewById(R.id.ditherValue),
            settings.dither / MAX_DITHER,
        ) {
            settings.dither = it * MAX_DITHER
            callbacks.onSettingsChanged()
        }

        view.findViewById<MaterialSwitch>(R.id.bevelSwitch).apply {
            isChecked = settings.bevel
            setOnCheckedChangeListener { _, checked ->
                settings.bevel = checked
                callbacks.onSettingsChanged()
            }
        }

        view.findViewById<MaterialSwitch>(R.id.outlineSwitch).apply {
            isChecked = settings.outline
            setOnCheckedChangeListener { _, checked ->
                settings.outline = checked
                callbacks.onSettingsChanged()
            }
        }
    }

    private fun bindColour(view: View) {
        val brightness = view.findViewById<Slider>(R.id.brightnessSlider)
        val contrast = view.findViewById<Slider>(R.id.contrastSlider)
        val saturation = view.findViewById<Slider>(R.id.saturationSlider)

        fun configure(slider: Slider, from: Float, to: Float, value: Float, apply: (Float) -> Unit) {
            slider.valueFrom = from
            slider.valueTo = to
            slider.stepSize = 0f
            slider.value = value.coerceIn(from, to)
            slider.addOnChangeListener { _, v, _ ->
                apply(v)
                callbacks.onSettingsChanged()
            }
        }

        configure(brightness, -0.5f, 0.5f, settings.brightness) { settings.brightness = it }
        configure(contrast, 0.5f, 2f, settings.contrast) { settings.contrast = it }
        configure(saturation, 0f, 2f, settings.saturation) { settings.saturation = it }

        view.findViewById<MaterialButton>(R.id.resetColour).setOnClickListener {
            settings.resetColour()
            brightness.value = 0f
            contrast.value = 1f
            saturation.value = 1f
            callbacks.onSettingsChanged()
        }
    }

    // ---------------------------------------------------------------- output

    private fun bindOutput(view: View) {
        val photoGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.photoGroup)
        photoGroup.check(
            when (settings.captureDetail) {
                MosaicRenderer.CAPTURE_STEPS[0] -> R.id.photoScreen
                MosaicRenderer.CAPTURE_STEPS[2] -> R.id.photoMax
                else -> R.id.photoHigh
            }
        )
        photoGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            settings.captureDetail = when (checkedId) {
                R.id.photoScreen -> MosaicRenderer.CAPTURE_STEPS[0]
                R.id.photoMax -> MosaicRenderer.CAPTURE_STEPS[2]
                else -> MosaicRenderer.CAPTURE_STEPS[1]
            }
        }

        val videoGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.videoGroup)
        videoGroup.check(
            if (settings.videoQuality >= VideoRecorder.QUALITIES[1]) R.id.video1080 else R.id.video720
        )
        videoGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            settings.videoQuality = if (checkedId == R.id.video1080) {
                VideoRecorder.QUALITIES[1]
            } else {
                VideoRecorder.QUALITIES[0]
            }
        }
    }

    // ----------------------------------------------------------------- packs

    private fun bindPacks(view: View) {
        val limits = BlockPalette.BLOCK_LIMITS
        val limitLabel = view.findViewById<TextView>(R.id.blockLimitValue)
        val limitSlider = view.findViewById<Slider>(R.id.blockLimitSlider)
        limitSlider.valueFrom = 0f
        limitSlider.valueTo = (limits.size - 1).toFloat()
        limitSlider.stepSize = 1f
        val limitStart = limits.indexOfFirst { it >= settings.blockLimit }.let {
            if (it < 0) limits.size - 1 else it
        }
        limitSlider.value = limitStart.toFloat()
        limitLabel.text = context.getString(R.string.block_limit_value, limits[limitStart])
        limitSlider.addOnChangeListener { _, value, _ ->
            val limit = limits[value.toInt().coerceIn(0, limits.size - 1)]
            limitLabel.text = context.getString(R.string.block_limit_value, limit)
            settings.blockLimit = limit
            callbacks.onSettingsChanged()
        }

        val container = view.findViewById<LinearLayout>(R.id.packList)
        container.removeAllViews()

        addPackRow(
            container,
            id = PackLibrary.BUILT_IN_ID,
            name = DefaultPack.NAME,
            blockCount = DefaultPack.blockCount,
            deletable = false,
        )
        PackLibrary.list(context).forEach { info ->
            addPackRow(container, info.id, info.name, info.blockCount, deletable = true)
        }

        view.findViewById<MaterialButton>(R.id.importPack).setOnClickListener {
            dialog.dismiss()
            callbacks.onImportPack()
        }
    }

    private fun addPackRow(
        container: ViewGroup,
        id: String,
        name: String,
        blockCount: Int,
        deletable: Boolean,
    ) {
        val row = LayoutInflater.from(context).inflate(R.layout.item_pack, container, false)
        row.findViewById<TextView>(R.id.packTitle).text = name
        row.findViewById<TextView>(R.id.packSubtitle).text =
            context.getString(R.string.pack_blocks, blockCount)

        val selected = settings.selectedPackId == id
        row.findViewById<View>(R.id.packSelected).visibility =
            if (selected) View.VISIBLE else View.INVISIBLE

        row.setOnClickListener {
            settings.selectedPackId = id
            callbacks.onSelectPack(id)
            dialog.dismiss()
        }

        row.findViewById<MaterialButton>(R.id.packDelete).apply {
            visibility = if (deletable) View.VISIBLE else View.GONE
            setOnClickListener {
                callbacks.onDeletePack(id)
                dialog.dismiss()
            }
        }
        container.addView(row)
    }

    // ----------------------------------------------------------------- utils

    private fun percentSlider(
        slider: Slider,
        label: TextView,
        initial: Float,
        apply: (Float) -> Unit,
    ) {
        slider.valueFrom = 0f
        slider.valueTo = 1f
        slider.stepSize = 0f
        slider.value = initial.coerceIn(0f, 1f)
        label.text = context.getString(R.string.percent, (slider.value * 100).roundToInt())
        slider.addOnChangeListener { _, value, _ ->
            label.text = context.getString(R.string.percent, (value * 100).roundToInt())
            apply(value)
        }
    }

    private companion object {
        /** Slider range for the dither amount, mapped onto the shader's 0..1. */
        const val MAX_DITHER = 0.2f
    }
}
