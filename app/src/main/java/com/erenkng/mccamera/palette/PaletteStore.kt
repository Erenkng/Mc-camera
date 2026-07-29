package com.erenkng.mccamera.palette

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * Keeps the imported palette on disk so the app does not have to re-read the
 * user's resource pack on every launch.
 */
object PaletteStore {

    private const val TAG = "PaletteStore"
    private const val ATLAS_FILE = "palette_atlas.png"
    private const val META_FILE = "palette_meta.txt"

    fun save(context: Context, palette: BlockPalette) {
        try {
            File(context.filesDir, ATLAS_FILE).outputStream().use {
                palette.atlas.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val meta = buildString {
                appendLine(palette.name)
                palette.entries.forEach { appendLine("${it.name}\t${it.avgColor}") }
            }
            File(context.filesDir, META_FILE).writeText(meta)
        } catch (e: Exception) {
            Log.w(TAG, "Palet kaydedilemedi", e)
        }
    }

    fun load(context: Context): BlockPalette? {
        return try {
            val atlasFile = File(context.filesDir, ATLAS_FILE)
            val metaFile = File(context.filesDir, META_FILE)
            if (!atlasFile.exists() || !metaFile.exists()) return null

            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val atlas = BitmapFactory.decodeFile(atlasFile.absolutePath, options) ?: return null

            val lines = metaFile.readLines().filter { it.isNotBlank() }
            if (lines.size < 2) return null
            val entries = lines.drop(1).mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 2) null else BlockEntry(parts[0], parts[1].toIntOrNull() ?: return@mapNotNull null)
            }
            if (entries.isEmpty()) return null

            BlockPalette(lines[0], entries, atlas)
        } catch (e: Exception) {
            Log.w(TAG, "Palet okunamadı", e)
            null
        }
    }

    fun clear(context: Context) {
        File(context.filesDir, ATLAS_FILE).delete()
        File(context.filesDir, META_FILE).delete()
    }
}
