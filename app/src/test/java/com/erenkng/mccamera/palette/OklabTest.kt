package com.erenkng.mccamera.palette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OklabTest {

    private fun lab(color: Int): FloatArray = FloatArray(3).also { Oklab.convert(color, it, 0) }

    @Test
    fun `channel helpers round trip`() {
        val color = Oklab.pack(12, 200, 255)
        assertEquals(12, Oklab.red(color))
        assertEquals(200, Oklab.green(color))
        assertEquals(255, Oklab.blue(color))
        assertEquals(0xFF, (color ushr 24) and 0xFF)
    }

    @Test
    fun `white and black sit at the ends of the lightness axis`() {
        val white = lab(Oklab.pack(255, 255, 255))
        val black = lab(Oklab.pack(0, 0, 0))

        assertEquals(1f, white[0], 0.01f)
        assertEquals(0f, white[1], 0.01f)
        assertEquals(0f, white[2], 0.01f)
        assertEquals(0f, black[0], 0.01f)
    }

    @Test
    fun `lightness increases along a grey ramp`() {
        var previous = -1f
        for (v in 0..255 step 15) {
            val l = lab(Oklab.pack(v, v, v))[0]
            assertTrue("L should rise at $v", l > previous)
            previous = l
        }
    }

    @Test
    fun `distance is zero for identical colours`() {
        val a = lab(Oklab.pack(120, 40, 200))
        assertEquals(0f, Oklab.distance(a, 0, a, 0), 1e-6f)
    }

    @Test
    fun `neighbouring greys are closer than a saturated hue`() {
        val grey = lab(Oklab.pack(128, 128, 128))
        val nearGrey = lab(Oklab.pack(140, 138, 136))
        val red = lab(Oklab.pack(220, 20, 20))

        assertTrue(
            "a near-grey must beat saturated red",
            Oklab.distance(grey, 0, nearGrey, 0) < Oklab.distance(grey, 0, red, 0),
        )
    }

    @Test
    fun `dark tones stay apart instead of collapsing`() {
        // Plain RGB squashes these together; OKLab keeps them distinguishable.
        val nearBlack = lab(Oklab.pack(10, 10, 10))
        val darkGrey = lab(Oklab.pack(40, 40, 40))
        assertTrue(Oklab.distance(nearBlack, 0, darkGrey, 0) > 0.005f)
    }
}
