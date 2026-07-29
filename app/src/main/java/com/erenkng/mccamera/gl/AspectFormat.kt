package com.erenkng.mccamera.gl

/**
 * Framing of the preview and of everything captured from it.
 *
 * [ratio] is width / height; [FULL] means "whatever the screen is", so it has no
 * fixed ratio of its own.
 */
enum class AspectFormat(val ratio: Float, val label: String) {
    FULL(0f, "TAM"),
    SQUARE(1f, "1:1"),
    FOUR_FIVE(4f / 5f, "4:5"),
    THREE_FOUR(3f / 4f, "3:4"),
    NINE_SIXTEEN(9f / 16f, "9:16");

    companion object {
        fun of(ordinal: Int): AspectFormat = entries.getOrElse(ordinal) { FULL }
    }
}
