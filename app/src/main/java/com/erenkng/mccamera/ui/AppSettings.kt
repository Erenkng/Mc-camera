package com.erenkng.mccamera.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.erenkng.mccamera.gl.MosaicRenderer
import com.erenkng.mccamera.palette.BlockPalette
import com.erenkng.mccamera.palette.PackLibrary
import com.erenkng.mccamera.video.VideoRecorder

/** Every user-facing preference, in one typed place. */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mccamera", Context.MODE_PRIVATE)

    var density: Int
        get() = prefs.getInt(KEY_DENSITY, MosaicRenderer.DEFAULT_DENSITY)
        set(value) = prefs.edit { putInt(KEY_DENSITY, value) }

    var mode: MosaicRenderer.Mode
        get() = MosaicRenderer.Mode.entries.getOrElse(prefs.getInt(KEY_MODE, 0)) {
            MosaicRenderer.Mode.BLOCKS
        }
        set(value) = prefs.edit { putInt(KEY_MODE, value.ordinal) }

    var shade: Float
        get() = prefs.getFloat(KEY_SHADE, MosaicRenderer.DEFAULT_SHADE)
        set(value) = prefs.edit { putFloat(KEY_SHADE, value) }

    var dither: Float
        get() = prefs.getFloat(KEY_DITHER, MosaicRenderer.DEFAULT_DITHER)
        set(value) = prefs.edit { putFloat(KEY_DITHER, value) }

    var bevel: Boolean
        get() = prefs.getBoolean(KEY_BEVEL, true)
        set(value) = prefs.edit { putBoolean(KEY_BEVEL, value) }

    var outline: Boolean
        get() = prefs.getBoolean(KEY_OUTLINE, false)
        set(value) = prefs.edit { putBoolean(KEY_OUTLINE, value) }

    var brightness: Float
        get() = prefs.getFloat(KEY_BRIGHTNESS, 0f)
        set(value) = prefs.edit { putFloat(KEY_BRIGHTNESS, value) }

    var contrast: Float
        get() = prefs.getFloat(KEY_CONTRAST, 1f)
        set(value) = prefs.edit { putFloat(KEY_CONTRAST, value) }

    var saturation: Float
        get() = prefs.getFloat(KEY_SATURATION, 1f)
        set(value) = prefs.edit { putFloat(KEY_SATURATION, value) }

    /** 0 = screen resolution, otherwise pixels rendered per block. */
    var captureDetail: Int
        get() = prefs.getInt(KEY_CAPTURE, MosaicRenderer.CAPTURE_STEPS[1])
        set(value) = prefs.edit { putInt(KEY_CAPTURE, value) }

    /** Short side of recorded video in pixels. */
    var videoQuality: Int
        get() = prefs.getInt(KEY_VIDEO, VideoRecorder.QUALITIES[0])
        set(value) = prefs.edit { putInt(KEY_VIDEO, value) }

    var blockLimit: Int
        get() = prefs.getInt(KEY_BLOCK_LIMIT, BlockPalette.MAX_BLOCKS)
        set(value) = prefs.edit { putInt(KEY_BLOCK_LIMIT, value) }

    var selectedPackId: String
        get() = prefs.getString(KEY_PACK, PackLibrary.BUILT_IN_ID) ?: PackLibrary.BUILT_IN_ID
        set(value) = prefs.edit { putString(KEY_PACK, value) }

    var timerSeconds: Int
        get() = prefs.getInt(KEY_TIMER, 0)
        set(value) = prefs.edit { putInt(KEY_TIMER, value) }

    var hintShown: Boolean
        get() = prefs.getBoolean(KEY_HINT, false)
        set(value) = prefs.edit { putBoolean(KEY_HINT, value) }

    fun resetColour() {
        brightness = 0f
        contrast = 1f
        saturation = 1f
    }

    private companion object {
        const val KEY_DENSITY = "density"
        const val KEY_MODE = "mode"
        const val KEY_SHADE = "shade_strength"
        const val KEY_DITHER = "dither"
        const val KEY_BEVEL = "bevel"
        const val KEY_OUTLINE = "outline"
        const val KEY_BRIGHTNESS = "brightness"
        const val KEY_CONTRAST = "contrast"
        const val KEY_SATURATION = "saturation"
        const val KEY_CAPTURE = "capture_detail"
        const val KEY_VIDEO = "video_quality"
        const val KEY_BLOCK_LIMIT = "block_limit"
        const val KEY_PACK = "pack_id"
        const val KEY_TIMER = "timer"
        const val KEY_HINT = "hint_shown"
    }
}
