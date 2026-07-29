package com.erenkng.mccamera.palette

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect

/**
 * A single block that can be painted into the mosaic.
 *
 * [avgColor] is the colour the block "reads as" from a distance, it is what the
 * nearest-colour lookup matches the camera against.
 */
data class BlockEntry(val name: String, val avgColor: Int)

/**
 * A ready-to-upload palette: an atlas holding every block texture plus the
 * average colour of each of them.
 *
 * The atlas is a [TILES_PER_ROW] x [TILES_PER_ROW] grid of [TILE_SIZE] px
 * tiles, tile `i` living at column `i % TILES_PER_ROW`, row `i / TILES_PER_ROW`
 * counted from the top.
 */
class BlockPalette(
    val name: String,
    val entries: List<BlockEntry>,
    val atlas: Bitmap,
) {
    val blockCount: Int get() = entries.size

    /**
     * Builds the colour lookup table consumed by the mosaic shader.
     *
     * The table is a 32x32x32 RGB cube flattened into a [LUT_WIDTH] x
     * [LUT_HEIGHT] image: blue picks the 32 px wide slice, red the column
     * inside it, green the row. Every texel stores `R = atlas column`,
     * `G = atlas row`, `B = luminance of the block` (used for light matching).
     */
    fun buildLut(): Bitmap {
        val count = entries.size
        val reds = IntArray(count)
        val greens = IntArray(count)
        val blues = IntArray(count)
        val lumas = IntArray(count)
        for (i in 0 until count) {
            val c = entries[i].avgColor
            reds[i] = Color.red(c)
            greens[i] = Color.green(c)
            blues[i] = Color.blue(c)
            lumas[i] = luminance(c).coerceIn(0, 255)
        }

        val pixels = IntArray(LUT_WIDTH * LUT_HEIGHT)
        for (bi in 0 until LUT_STEPS) {
            val b = bi * 255 / (LUT_STEPS - 1)
            for (gi in 0 until LUT_STEPS) {
                val g = gi * 255 / (LUT_STEPS - 1)
                for (ri in 0 until LUT_STEPS) {
                    val r = ri * 255 / (LUT_STEPS - 1)
                    var best = 0
                    var bestDist = Int.MAX_VALUE
                    for (i in 0 until count) {
                        val dr = r - reds[i]
                        val dg = g - greens[i]
                        val db = b - blues[i]
                        val dist = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                        if (dist < bestDist) {
                            bestDist = dist
                            best = i
                        }
                    }
                    val col = best % TILES_PER_ROW
                    val row = best / TILES_PER_ROW
                    val x = bi * LUT_STEPS + ri
                    pixels[gi * LUT_WIDTH + x] = Color.argb(255, col, row, lumas[best])
                }
            }
        }
        return Bitmap.createBitmap(pixels, LUT_WIDTH, LUT_HEIGHT, Bitmap.Config.ARGB_8888)
    }

    companion object {
        const val TILE_SIZE = 16
        const val TILES_PER_ROW = 8
        const val MAX_BLOCKS = TILES_PER_ROW * TILES_PER_ROW
        const val ATLAS_SIZE = TILE_SIZE * TILES_PER_ROW

        const val LUT_STEPS = 32
        const val LUT_WIDTH = LUT_STEPS * LUT_STEPS
        const val LUT_HEIGHT = LUT_STEPS

        fun luminance(color: Int): Int =
            (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)).toInt()
    }
}

/** A candidate block texture before it is selected into a palette. */
class BlockTile(val name: String, val bitmap: Bitmap)

object PaletteBuilder {

    /**
     * Picks the most colour-diverse subset of [tiles] and packs it into a
     * [BlockPalette]. Returns null when nothing usable was supplied.
     */
    fun build(name: String, tiles: List<BlockTile>): BlockPalette? {
        if (tiles.isEmpty()) return null

        val colors = tiles.map { averageColor(it.bitmap) }
        val chosen = pickDiverse(colors, BlockPalette.MAX_BLOCKS)

        val atlas = Bitmap.createBitmap(
            BlockPalette.ATLAS_SIZE,
            BlockPalette.ATLAS_SIZE,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(atlas)
        val entries = ArrayList<BlockEntry>(chosen.size)
        chosen.forEachIndexed { slot, index ->
            val col = slot % BlockPalette.TILES_PER_ROW
            val row = slot / BlockPalette.TILES_PER_ROW
            val dst = Rect(
                col * BlockPalette.TILE_SIZE,
                row * BlockPalette.TILE_SIZE,
                (col + 1) * BlockPalette.TILE_SIZE,
                (row + 1) * BlockPalette.TILE_SIZE,
            )
            canvas.drawBitmap(tiles[index].bitmap, null, dst, null)
            entries += BlockEntry(tiles[index].name, colors[index])
        }
        return BlockPalette(name, entries, atlas)
    }

    /**
     * Farthest-point selection: repeatedly takes the colour that is the most
     * different from everything picked so far, so a pack with 300 shades of
     * grey still yields a palette that spans the whole colour space.
     */
    private fun pickDiverse(colors: List<Int>, limit: Int): List<Int> {
        if (colors.size <= limit) return colors.indices.toList()

        // Seed with the darkest colour so the result is deterministic.
        var seed = 0
        for (i in colors.indices) {
            if (BlockPalette.luminance(colors[i]) < BlockPalette.luminance(colors[seed])) seed = i
        }

        val picked = ArrayList<Int>(limit)
        picked += seed
        val minDist = IntArray(colors.size) { distance(colors[it], colors[seed]) }

        while (picked.size < limit) {
            var best = -1
            var bestDist = -1
            for (i in colors.indices) {
                if (minDist[i] > bestDist) {
                    bestDist = minDist[i]
                    best = i
                }
            }
            if (best < 0 || bestDist <= 0) break
            picked += best
            for (i in colors.indices) {
                val d = distance(colors[i], colors[best])
                if (d < minDist[i]) minDist[i] = d
            }
            minDist[best] = -1
        }
        return picked
    }

    private fun distance(a: Int, b: Int): Int {
        val dr = Color.red(a) - Color.red(b)
        val dg = Color.green(a) - Color.green(b)
        val db = Color.blue(a) - Color.blue(b)
        return 2 * dr * dr + 4 * dg * dg + 3 * db * db
    }

    fun averageColor(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var r = 0L
        var g = 0L
        var b = 0L
        for (p in pixels) {
            r += Color.red(p)
            g += Color.green(p)
            b += Color.blue(p)
        }
        val n = pixels.size.coerceAtLeast(1)
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
