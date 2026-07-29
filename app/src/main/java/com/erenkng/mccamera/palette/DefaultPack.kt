package com.erenkng.mccamera.palette

import android.graphics.Bitmap
import android.graphics.Color
import java.util.Random

/**
 * The palette the app ships with.
 *
 * Every tile is drawn from code at runtime, nothing is copied from the game, so
 * the repository stays free of third-party artwork. Load your own resource pack
 * from the settings sheet to swap these out for the real thing.
 */
object DefaultPack {

    private enum class Style { NOISE, SOFT, PLANKS, LOG, BRICK, ORE, GEM }

    private class Spec(
        val name: String,
        val base: Int,
        val jitter: Int,
        val style: Style,
        val accent: Int = 0,
    )

    private val SPECS = listOf(
        Spec("stone", 0x7A7A7A, 9, Style.NOISE),
        Spec("cobblestone", 0x828282, 26, Style.NOISE),
        Spec("andesite", 0x8A8C8D, 12, Style.NOISE),
        Spec("deepslate", 0x50535A, 14, Style.NOISE),
        Spec("dirt", 0x866043, 16, Style.NOISE),
        Spec("grass_block", 0x6A9A3B, 14, Style.NOISE),
        Spec("moss_block", 0x5E7B33, 16, Style.NOISE),
        Spec("sand", 0xDBD3A0, 10, Style.NOISE),
        Spec("red_sand", 0xBE6520, 12, Style.NOISE),
        Spec("gravel", 0x837E7C, 26, Style.NOISE),
        Spec("clay", 0x9EA4B0, 8, Style.NOISE),
        Spec("netherrack", 0x6F3634, 18, Style.NOISE),
        Spec("obsidian", 0x150A20, 10, Style.GEM, 0x4B2F7A),
        Spec("snow", 0xF0FAFA, 6, Style.SOFT),
        Spec("ice", 0x7DADFF, 12, Style.SOFT),
        Spec("prismarine", 0x5FA093, 14, Style.NOISE),
        Spec("bone_block", 0xE1DDC8, 10, Style.LOG),
        Spec("purpur_block", 0xA97CA9, 10, Style.NOISE),

        Spec("oak_planks", 0xB8945F, 10, Style.PLANKS),
        Spec("spruce_planks", 0x745237, 10, Style.PLANKS),
        Spec("birch_planks", 0xC6B180, 10, Style.PLANKS),
        Spec("dark_oak_planks", 0x4F3218, 10, Style.PLANKS),
        Spec("oak_log", 0x6B532E, 14, Style.LOG),
        Spec("birch_log", 0xD7D3CA, 12, Style.LOG),

        Spec("bricks", 0x97584B, 8, Style.BRICK, 0xB0A79F),
        Spec("nether_bricks", 0x2E1720, 8, Style.BRICK, 0x453040),
        Spec("stone_bricks", 0x7B7B7B, 8, Style.BRICK, 0x6A6A6A),

        Spec("coal_ore", 0x7A7A7A, 9, Style.ORE, 0x191919),
        Spec("iron_ore", 0x7A7A7A, 9, Style.ORE, 0xD8AF93),
        Spec("gold_ore", 0x7A7A7A, 9, Style.ORE, 0xFCEE4B),
        Spec("lapis_ore", 0x7A7A7A, 9, Style.ORE, 0x2C5FB0),

        Spec("gold_block", 0xF9EC4E, 8, Style.GEM, 0xFFFAA0),
        Spec("diamond_block", 0x63E0D6, 8, Style.GEM, 0xB4FFF6),
        Spec("emerald_block", 0x46E077, 8, Style.GEM, 0x9CFFB4),
        Spec("redstone_block", 0xA91E10, 10, Style.GEM, 0xE2412A),
        Spec("lapis_block", 0x1E4CA1, 10, Style.GEM, 0x3E76D0),
        Spec("copper_block", 0xC06A4E, 8, Style.GEM, 0xE08C6C),
        Spec("iron_block", 0xDCDCDC, 6, Style.GEM, 0xF2F2F2),

        Spec("white_wool", 0xEAEEEE, 7, Style.SOFT),
        Spec("light_gray_wool", 0x9D9D97, 7, Style.SOFT),
        Spec("gray_wool", 0x474F52, 7, Style.SOFT),
        Spec("black_wool", 0x1D1C21, 6, Style.SOFT),
        Spec("brown_wool", 0x7C4E28, 7, Style.SOFT),
        Spec("red_wool", 0xA32C29, 7, Style.SOFT),
        Spec("orange_wool", 0xF07613, 7, Style.SOFT),
        Spec("yellow_wool", 0xF8C627, 7, Style.SOFT),
        Spec("lime_wool", 0x79C216, 7, Style.SOFT),
        Spec("green_wool", 0x536421, 7, Style.SOFT),
        Spec("cyan_wool", 0x157788, 7, Style.SOFT),
        Spec("light_blue_wool", 0x3AAFD9, 7, Style.SOFT),
        Spec("blue_wool", 0x35399D, 7, Style.SOFT),
        Spec("purple_wool", 0x7E3DB5, 7, Style.SOFT),
        Spec("magenta_wool", 0xBD44B3, 7, Style.SOFT),
        Spec("pink_wool", 0xED8DAC, 7, Style.SOFT),

        // Concrete is the most saturated family in the game and does most of the
        // work when the camera sees strong colour.
        Spec("white_concrete", 0xCFD5D6, 4, Style.SOFT),
        Spec("orange_concrete", 0xE06100, 4, Style.SOFT),
        Spec("magenta_concrete", 0xA9309F, 4, Style.SOFT),
        Spec("light_blue_concrete", 0x2489C7, 4, Style.SOFT),
        Spec("yellow_concrete", 0xF1AF15, 4, Style.SOFT),
        Spec("lime_concrete", 0x5EA918, 4, Style.SOFT),
        Spec("pink_concrete", 0xD6658F, 4, Style.SOFT),
        Spec("gray_concrete", 0x373A3E, 4, Style.SOFT),
        Spec("light_gray_concrete", 0x7D7D73, 4, Style.SOFT),
        Spec("cyan_concrete", 0x157788, 4, Style.SOFT),
        Spec("purple_concrete", 0x64209C, 4, Style.SOFT),
        Spec("blue_concrete", 0x2C2E8F, 4, Style.SOFT),
        Spec("brown_concrete", 0x603C20, 4, Style.SOFT),
        Spec("green_concrete", 0x495B24, 4, Style.SOFT),
        Spec("red_concrete", 0x8E2121, 4, Style.SOFT),
        Spec("black_concrete", 0x080A0F, 4, Style.SOFT),

        // Terracotta covers the muted middle of the space: skin, wood, soil.
        Spec("terracotta", 0x985E44, 10, Style.NOISE),
        Spec("white_terracotta", 0xD1B1A1, 8, Style.NOISE),
        Spec("orange_terracotta", 0xA05325, 8, Style.NOISE),
        Spec("magenta_terracotta", 0x95576C, 8, Style.NOISE),
        Spec("light_blue_terracotta", 0x706C8A, 8, Style.NOISE),
        Spec("yellow_terracotta", 0xBA8523, 8, Style.NOISE),
        Spec("lime_terracotta", 0x677535, 8, Style.NOISE),
        Spec("pink_terracotta", 0xA14E4E, 8, Style.NOISE),
        Spec("gray_terracotta", 0x392A24, 8, Style.NOISE),
        Spec("light_gray_terracotta", 0x876B62, 8, Style.NOISE),
        Spec("cyan_terracotta", 0x575C5C, 8, Style.NOISE),
        Spec("purple_terracotta", 0x764656, 8, Style.NOISE),
        Spec("blue_terracotta", 0x4A3A5B, 8, Style.NOISE),
        Spec("brown_terracotta", 0x4D3323, 8, Style.NOISE),
        Spec("green_terracotta", 0x4C532A, 8, Style.NOISE),
        Spec("red_terracotta", 0x8E3C2E, 8, Style.NOISE),
        Spec("black_terracotta", 0x251610, 8, Style.NOISE),

        Spec("sandstone", 0xE0D8A8, 7, Style.LOG),
        Spec("quartz_block", 0xECE9E2, 5, Style.LOG),
        Spec("calcite", 0xDFDEDA, 8, Style.NOISE),
        Spec("tuff", 0x6C6E64, 12, Style.NOISE),
        Spec("basalt", 0x4C4C55, 10, Style.LOG),
        Spec("blackstone", 0x2A2327, 12, Style.NOISE),
        Spec("packed_mud", 0x8C6A4E, 12, Style.NOISE),
        Spec("amethyst_block", 0xA87CD8, 10, Style.GEM, 0xC9A6EE),
        Spec("warped_planks", 0x2B6C64, 9, Style.PLANKS),
        Spec("crimson_planks", 0x6A344B, 9, Style.PLANKS),
        Spec("mangrove_planks", 0x763934, 9, Style.PLANKS),
        Spec("cherry_planks", 0xE6BCAA, 9, Style.PLANKS),
        Spec("bamboo_planks", 0xC3A85A, 9, Style.PLANKS),
        Spec("sculk", 0x0C1114, 10, Style.GEM, 0x1E6E6E),
        Spec("glowstone", 0xF8C15D, 12, Style.ORE, 0xFFE9A8),
        Spec("sea_lantern", 0xB5D0C4, 8, Style.GEM, 0xE4F2EC),
        Spec("magma_block", 0x9E4A20, 14, Style.ORE, 0xF0A030),
        Spec("dried_kelp_block", 0x35461E, 10, Style.NOISE),
        Spec("hay_block", 0xA08A0B, 10, Style.LOG),
        Spec("melon", 0x7BA523, 12, Style.NOISE),
        Spec("pumpkin", 0xC07615, 10, Style.LOG),
        Spec("mycelium", 0x6F6265, 12, Style.NOISE),
        Spec("podzol", 0x573B1A, 12, Style.NOISE),
        Spec("end_stone", 0xDDDDA5, 9, Style.NOISE),
        Spec("netherite_block", 0x443A3B, 8, Style.GEM, 0x5C5052),
        Spec("ancient_debris", 0x5F4136, 10, Style.ORE, 0x8B6B4E),
    )

    const val NAME = "Yerleşik paket"

    val blockCount: Int get() = SPECS.size

    fun create(limit: Int = BlockPalette.MAX_BLOCKS): BlockPalette {
        val tiles = SPECS.mapIndexed { index, spec -> BlockTile(spec.name, render(spec, index)) }
        return PaletteBuilder.build(NAME, tiles, limit)!!
    }

    private fun render(spec: Spec, seed: Int): Bitmap {
        val size = BlockPalette.TILE_SIZE
        val px = IntArray(size * size)
        val rnd = Random(seed * 7919L + 13L)

        for (i in px.indices) px[i] = spec.base or 0xFF000000.toInt()

        when (spec.style) {
            Style.NOISE -> {
                // A few darker/lighter clumps on top of per-pixel grain reads as
                // rough stone or soil at mosaic scale.
                for (i in px.indices) px[i] = shade(px[i], rnd.nextInt(spec.jitter * 2 + 1) - spec.jitter)
                repeat(6) {
                    val cx = rnd.nextInt(size)
                    val cy = rnd.nextInt(size)
                    val r = 2 + rnd.nextInt(3)
                    val delta = if (rnd.nextBoolean()) -spec.jitter else spec.jitter
                    for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
                        val dx = x - cx
                        val dy = y - cy
                        if (dx * dx + dy * dy <= r * r) {
                            val xi = (x + size) % size
                            val yi = (y + size) % size
                            px[yi * size + xi] = shade(px[yi * size + xi], delta)
                        }
                    }
                }
            }

            Style.SOFT -> {
                for (i in px.indices) px[i] = shade(px[i], rnd.nextInt(spec.jitter * 2 + 1) - spec.jitter)
            }

            Style.PLANKS -> {
                val plank = 4
                for (y in 0 until size) {
                    val row = y / plank
                    val rowShade = ((row * 37) % 11) - 5
                    val edge = y % plank == 0
                    for (x in 0 until size) {
                        var c = shade(spec.base or 0xFF000000.toInt(), rowShade)
                        c = shade(c, rnd.nextInt(spec.jitter) - spec.jitter / 2)
                        if (edge) c = shade(c, -28)
                        // Vertical seam between two planks of the same row.
                        if (x == (row * 5 + 3) % size) c = shade(c, -22)
                        px[y * size + x] = c
                    }
                }
            }

            Style.LOG -> {
                for (x in 0 until size) {
                    val colShade = ((x * 53) % 13) - 6
                    val dark = x % 5 == 0
                    for (y in 0 until size) {
                        var c = shade(spec.base or 0xFF000000.toInt(), colShade)
                        c = shade(c, rnd.nextInt(spec.jitter) - spec.jitter / 2)
                        if (dark) c = shade(c, -20)
                        px[y * size + x] = c
                    }
                }
            }

            Style.BRICK -> {
                val mortar = spec.accent or 0xFF000000.toInt()
                for (y in 0 until size) {
                    val brickRow = y / 4
                    val offset = if (brickRow % 2 == 0) 0 else 4
                    for (x in 0 until size) {
                        val isMortar = y % 4 == 0 || (x + offset) % 8 == 0
                        var c = if (isMortar) mortar else spec.base or 0xFF000000.toInt()
                        c = shade(c, rnd.nextInt(spec.jitter) - spec.jitter / 2)
                        px[y * size + x] = c
                    }
                }
            }

            Style.ORE -> {
                for (i in px.indices) px[i] = shade(px[i], rnd.nextInt(spec.jitter * 2 + 1) - spec.jitter)
                val nugget = spec.accent or 0xFF000000.toInt()
                repeat(3) {
                    val cx = 2 + rnd.nextInt(size - 4)
                    val cy = 2 + rnd.nextInt(size - 4)
                    for (y in cy - 1..cy + 1) for (x in cx - 1..cx + 1) {
                        if (rnd.nextInt(4) == 0) continue
                        px[y * size + x] = shade(nugget, rnd.nextInt(17) - 8)
                    }
                }
            }

            Style.GEM -> {
                val hi = spec.accent or 0xFF000000.toInt()
                for (y in 0 until size) for (x in 0 until size) {
                    val onDiagonal = (x + y) % 8 < 2
                    var c = if (onDiagonal) hi else spec.base or 0xFF000000.toInt()
                    if (x == 0 || y == 0) c = shade(c, 14)
                    if (x == size - 1 || y == size - 1) c = shade(c, -18)
                    c = shade(c, rnd.nextInt(spec.jitter) - spec.jitter / 2)
                    px[y * size + x] = c
                }
            }
        }

        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun shade(color: Int, delta: Int): Int = Color.argb(
        255,
        (Color.red(color) + delta).coerceIn(0, 255),
        (Color.green(color) + delta).coerceIn(0, 255),
        (Color.blue(color) + delta).coerceIn(0, 255),
    )
}
