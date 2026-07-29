package com.erenkng.mccamera.palette

import android.graphics.Bitmap
import android.graphics.Canvas
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
     *
     * The search runs in OKLab so the block the shader ends up drawing is the
     * one a person would call closest, not merely the closest in RGB.
     */
    fun buildLut(): Bitmap {
        val count = entries.size
        val paletteLab = FloatArray(count * 3)
        val lumas = IntArray(count)
        for (i in 0 until count) {
            val c = entries[i].avgColor
            Oklab.convert(c, paletteLab, i * 3)
            lumas[i] = luminance(c).coerceIn(0, 255)
        }

        val pixels = IntArray(LUT_WIDTH * LUT_HEIGHT)
        val sample = FloatArray(3)
        for (bi in 0 until LUT_STEPS) {
            val b = bi * 255 / (LUT_STEPS - 1)
            for (gi in 0 until LUT_STEPS) {
                val g = gi * 255 / (LUT_STEPS - 1)
                for (ri in 0 until LUT_STEPS) {
                    val r = ri * 255 / (LUT_STEPS - 1)
                    Oklab.fromRgb(r, g, b, sample, 0)

                    var best = 0
                    var bestDist = Float.MAX_VALUE
                    for (i in 0 until count) {
                        val dist = Oklab.distance(sample, 0, paletteLab, i * 3)
                        if (dist < bestDist) {
                            bestDist = dist
                            best = i
                        }
                    }
                    val col = best % TILES_PER_ROW
                    val row = best / TILES_PER_ROW
                    val x = bi * LUT_STEPS + ri
                    pixels[gi * LUT_WIDTH + x] = Oklab.pack(col, row, lumas[best])
                }
            }
        }
        return Bitmap.createBitmap(pixels, LUT_WIDTH, LUT_HEIGHT, Bitmap.Config.ARGB_8888)
    }

    /**
     * A [MAX_BLOCKS] x 1 strip of the average colours, indexed by atlas slot.
     * The map-art render mode fills each cell with this instead of the texture.
     */
    fun buildPaletteStrip(): Bitmap {
        val pixels = IntArray(MAX_BLOCKS)
        entries.forEachIndexed { index, entry -> pixels[index] = entry.avgColor or (0xFF shl 24) }
        return Bitmap.createBitmap(pixels, MAX_BLOCKS, 1, Bitmap.Config.ARGB_8888)
    }

    companion object {
        const val TILE_SIZE = 16
        const val TILES_PER_ROW = 16
        const val MAX_BLOCKS = TILES_PER_ROW * TILES_PER_ROW
        const val ATLAS_SIZE = TILE_SIZE * TILES_PER_ROW

        const val LUT_STEPS = 32
        const val LUT_WIDTH = LUT_STEPS * LUT_STEPS
        const val LUT_HEIGHT = LUT_STEPS

        /** Palette sizes the user can pick between. */
        val BLOCK_LIMITS = intArrayOf(16, 32, 64, 128, 256)

        fun luminance(color: Int): Int = (
            0.299f * Oklab.red(color) +
                0.587f * Oklab.green(color) +
                0.114f * Oklab.blue(color)
            ).toInt()
    }
}

/** A candidate block texture before it is selected into a palette. */
class BlockTile(val name: String, val bitmap: Bitmap)

object PaletteBuilder {

    /**
     * Picks the most colour-diverse subset of [tiles] and packs it into a
     * [BlockPalette]. Returns null when nothing usable was supplied.
     */
    fun build(name: String, tiles: List<BlockTile>, limit: Int = BlockPalette.MAX_BLOCKS): BlockPalette? {
        if (tiles.isEmpty()) return null

        val colors = tiles.map { averageColor(it.bitmap) }
        val chosen = pickDiverse(colors, limit.coerceIn(2, BlockPalette.MAX_BLOCKS))

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
     * Farthest-point selection in OKLab: repeatedly takes the colour that is the
     * most different from everything picked so far, so a pack with 300 shades of
     * grey still yields a palette that spans the whole colour space.
     */
    fun pickDiverse(colors: List<Int>, limit: Int): List<Int> {
        if (colors.size <= limit) return colors.indices.toList()

        val lab = FloatArray(colors.size * 3)
        colors.forEachIndexed { index, color -> Oklab.convert(color, lab, index * 3) }

        // Seed with the darkest colour so the result is deterministic.
        var seed = 0
        for (i in colors.indices) {
            if (lab[i * 3] < lab[seed * 3]) seed = i
        }

        val picked = ArrayList<Int>(limit)
        picked += seed
        val minDist = FloatArray(colors.size) { Oklab.distance(lab, it * 3, lab, seed * 3) }
        minDist[seed] = -1f

        while (picked.size < limit) {
            var best = -1
            var bestDist = 0f
            for (i in colors.indices) {
                if (minDist[i] > bestDist) {
                    bestDist = minDist[i]
                    best = i
                }
            }
            if (best < 0) break
            picked += best
            for (i in colors.indices) {
                if (minDist[i] < 0f) continue
                val d = Oklab.distance(lab, i * 3, lab, best * 3)
                if (d < minDist[i]) minDist[i] = d
            }
            minDist[best] = -1f
        }
        return picked
    }

    /**
     * Trims an existing palette down to [limit] blocks, keeping the most
     * colour-diverse ones. Lets the block-count setting apply to a pack that was
     * already imported, without re-reading the zip.
     */
    fun subset(palette: BlockPalette, limit: Int): BlockPalette {
        if (palette.entries.size <= limit) return palette

        val chosen = pickDiverse(palette.entries.map { it.avgColor }, limit)
        val atlas = Bitmap.createBitmap(
            BlockPalette.ATLAS_SIZE,
            BlockPalette.ATLAS_SIZE,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(atlas)
        val entries = ArrayList<BlockEntry>(chosen.size)
        chosen.forEachIndexed { slot, index ->
            canvas.drawBitmap(palette.atlas, tileRect(index), tileRect(slot), null)
            entries += palette.entries[index]
        }
        return BlockPalette(palette.name, entries, atlas)
    }

    private fun tileRect(slot: Int): Rect {
        val col = slot % BlockPalette.TILES_PER_ROW
        val row = slot / BlockPalette.TILES_PER_ROW
        return Rect(
            col * BlockPalette.TILE_SIZE,
            row * BlockPalette.TILE_SIZE,
            (col + 1) * BlockPalette.TILE_SIZE,
            (row + 1) * BlockPalette.TILE_SIZE,
        )
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
            r += Oklab.red(p)
            g += Oklab.green(p)
            b += Oklab.blue(p)
        }
        val n = pixels.size.coerceAtLeast(1)
        return Oklab.pack((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
