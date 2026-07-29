package com.erenkng.mccamera.palette

/**
 * sRGB to OKLab conversion.
 *
 * Nearest-colour matching in plain RGB picks visibly wrong blocks — greens beat
 * greys, dark tones collapse together. OKLab distances line up with what the eye
 * actually calls "close", so the mosaic keeps skin tones, sky gradients and
 * shadows recognisable.
 *
 * Deliberately free of `android.graphics`, which keeps it unit-testable on the
 * JVM without a device or Robolectric.
 */
object Oklab {

    fun red(color: Int): Int = (color shr 16) and 0xFF
    fun green(color: Int): Int = (color shr 8) and 0xFF
    fun blue(color: Int): Int = color and 0xFF

    fun pack(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    /** Writes L, a, b of [color] into [out] starting at [offset]. */
    fun convert(color: Int, out: FloatArray, offset: Int) {
        fromRgb(red(color), green(color), blue(color), out, offset)
    }

    fun fromRgb(r8: Int, g8: Int, b8: Int, out: FloatArray, offset: Int) {
        val r = toLinear(r8)
        val g = toLinear(g8)
        val b = toLinear(b8)

        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b

        val lc = Math.cbrt(l.toDouble()).toFloat()
        val mc = Math.cbrt(m.toDouble()).toFloat()
        val sc = Math.cbrt(s.toDouble()).toFloat()

        out[offset] = 0.2104542553f * lc + 0.7936177850f * mc - 0.0040720468f * sc
        out[offset + 1] = 1.9779984951f * lc - 2.4285922050f * mc + 0.4505937099f * sc
        out[offset + 2] = 0.0259040371f * lc + 0.7827717662f * mc - 0.8086757660f * sc
    }

    /** Squared OKLab distance between two triples. */
    fun distance(a: FloatArray, ai: Int, b: FloatArray, bi: Int): Float {
        val dl = a[ai] - b[bi]
        val da = a[ai + 1] - b[bi + 1]
        val db = a[ai + 2] - b[bi + 2]
        return dl * dl + da * da + db * db
    }

    private val LINEAR = FloatArray(256) { index ->
        val c = index / 255f
        if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }

    private fun toLinear(value: Int): Float = LINEAR[value.coerceIn(0, 255)]
}
