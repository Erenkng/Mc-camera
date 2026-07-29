package com.erenkng.mccamera.palette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteMathTest {

    @Test
    fun `everything is kept when the palette already fits`() {
        val colors = listOf(
            Oklab.pack(0, 0, 0),
            Oklab.pack(255, 0, 0),
            Oklab.pack(0, 255, 0),
        )
        assertEquals(listOf(0, 1, 2), PaletteBuilder.pickDiverse(colors, 8))
    }

    @Test
    fun `selection returns exactly the requested count without duplicates`() {
        val colors = (0 until 200).map { Oklab.pack(it % 256, (it * 7) % 256, (it * 13) % 256) }
        val picked = PaletteBuilder.pickDiverse(colors, 32)

        assertEquals(32, picked.size)
        assertEquals(32, picked.toSet().size)
    }

    @Test
    fun `a wall of near-identical greys still yields the outliers`() {
        // 60 barely distinguishable greys plus three strong hues: the selection
        // must reach for the hues rather than 8 more shades of grey.
        val colors = ArrayList<Int>()
        repeat(60) { colors += Oklab.pack(120 + it % 3, 120 + it % 3, 120 + it % 3) }
        val redIndex = colors.size
        colors += Oklab.pack(230, 20, 20)
        val greenIndex = colors.size
        colors += Oklab.pack(20, 210, 40)
        val blueIndex = colors.size
        colors += Oklab.pack(20, 40, 220)

        val picked = PaletteBuilder.pickDiverse(colors, 8).toSet()

        assertTrue("red missing", picked.contains(redIndex))
        assertTrue("green missing", picked.contains(greenIndex))
        assertTrue("blue missing", picked.contains(blueIndex))
    }

    @Test
    fun `selection is deterministic`() {
        val colors = (0 until 120).map { Oklab.pack((it * 31) % 256, (it * 17) % 256, (it * 3) % 256) }
        assertEquals(
            PaletteBuilder.pickDiverse(colors, 24),
            PaletteBuilder.pickDiverse(colors, 24),
        )
    }

    @Test
    fun `luminance covers the full byte range`() {
        assertEquals(0, BlockPalette.luminance(Oklab.pack(0, 0, 0)))
        // Float weights sum to 1 only to within rounding, so allow the last step.
        assertTrue(BlockPalette.luminance(Oklab.pack(255, 255, 255)) >= 254)
        assertTrue(BlockPalette.luminance(Oklab.pack(0, 255, 0)) > BlockPalette.luminance(Oklab.pack(0, 0, 255)))
    }

    @Test
    fun `the atlas has a slot for every block the palette can hold`() {
        assertEquals(
            BlockPalette.MAX_BLOCKS,
            BlockPalette.TILES_PER_ROW * BlockPalette.TILES_PER_ROW,
        )
        assertEquals(
            BlockPalette.ATLAS_SIZE,
            BlockPalette.TILES_PER_ROW * BlockPalette.TILE_SIZE,
        )
        // The shader packs the atlas column and row into single bytes.
        assertTrue(BlockPalette.TILES_PER_ROW <= 256)
    }

    @Test
    fun `the colour cube maps one texel per rgb sample`() {
        assertEquals(BlockPalette.LUT_STEPS * BlockPalette.LUT_STEPS, BlockPalette.LUT_WIDTH)
        assertEquals(BlockPalette.LUT_STEPS, BlockPalette.LUT_HEIGHT)
    }
}
