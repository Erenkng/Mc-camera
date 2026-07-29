package com.erenkng.mccamera.palette

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Reads block textures out of a Minecraft resource pack (.zip) the user picked
 * with the system file picker.
 *
 * Only fully opaque, square block textures are kept: anything with holes (glass,
 * leaves, plants, item overlays) would leave gaps in the mosaic, and animated
 * textures are stored as tall strips so they fail the square check anyway.
 */
object ResourcePackLoader {

    class PackException(message: String) : Exception(message)

    private val TEXTURE_PATH = Regex(
        """.*/textures/blocks?/([a-z0-9_\-]+)\.png$""",
        RegexOption.IGNORE_CASE,
    )

    /** Animated textures ship a sidecar file; those frames must not be used. */
    private val ANIMATION_META = Regex(
        """.*/textures/blocks?/([a-z0-9_\-]+)\.png\.mcmeta$""",
        RegexOption.IGNORE_CASE,
    )

    private val SKIP_NAMES = listOf(
        "destroy_stage", "_stage", "fire_", "_flow", "_still", "portal",
        "structure_", "debug", "barrier", "spawner", "_overlay", "vine",
        "sapling", "seagrass", "kelp", "rail", "ladder", "torch", "door",
    )

    private const val MAX_ENTRY_BYTES = 1 shl 20
    private const val MAX_TILES = 2048

    fun load(context: Context, uri: Uri, blockLimit: Int = BlockPalette.MAX_BLOCKS): BlockPalette {
        val tiles = ArrayList<BlockTile>()
        val seen = HashSet<String>()
        val animated = HashSet<String>()

        val stream = context.contentResolver.openInputStream(uri)
            ?: throw PackException("Dosya açılamadı.")

        stream.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (tiles.size >= MAX_TILES) break

                    val path = "/" + entry.name.replace('\\', '/')

                    val meta = ANIMATION_META.matchEntire(path)
                    if (meta != null) {
                        animated += meta.groupValues[1].lowercase()
                        continue
                    }

                    val match = TEXTURE_PATH.matchEntire(path) ?: continue
                    val name = match.groupValues[1].lowercase()
                    if (!seen.add(name)) continue
                    if (SKIP_NAMES.any { name.contains(it) }) continue

                    val bytes = readEntry(zip) ?: continue
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    val tile = normalize(bitmap)
                    if (tile != null) tiles += BlockTile(name, tile)
                }
            }
        }

        // Zip order is not guaranteed, so animated textures are dropped only
        // once the whole archive has been scanned for sidecar files.
        val usable = tiles.filterNot { animated.contains(it.name) }

        if (usable.isEmpty()) {
            throw PackException(
                "Bu zip içinde kullanılabilir blok dokusu bulunamadı. " +
                    "assets/<paket>/textures/block/ klasörü olan bir resource pack seçin."
            )
        }

        return PaletteBuilder.build(displayName(context, uri), usable, blockLimit)
            ?: throw PackException("Palet oluşturulamadı.")
    }

    private fun readEntry(zip: ZipInputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ENTRY_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Returns a 16x16 opaque tile, or null when the texture is unusable. */
    private fun normalize(source: Bitmap): Bitmap? {
        val w = source.width
        val h = source.height
        if (w != h || w < 8 || w > 256) return null

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        var transparent = 0
        for (p in pixels) if (Color.alpha(p) < 250) transparent++
        if (transparent * 50 > pixels.size) return null

        val size = BlockPalette.TILE_SIZE
        return if (w == size) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            // Nearest neighbour keeps the pixel-art look of high resolution packs.
            Bitmap.createScaledBitmap(source, size, size, false)
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        var name: String? = null
        val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && it.moveToFirst()) name = it.getString(index)
        }
        return (name ?: uri.lastPathSegment ?: "Resource pack").removeSuffix(".zip")
    }
}
