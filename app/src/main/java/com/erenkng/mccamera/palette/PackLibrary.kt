package com.erenkng.mccamera.palette

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * Stores every resource pack the user has imported, so packs can be switched
 * without re-reading a zip each time.
 *
 * Layout on disk: `files/packs/<id>/atlas.png` plus `meta.tsv`, whose first line
 * is the display name and whose remaining lines are `blockName<TAB>argb`.
 */
object PackLibrary {

    private const val TAG = "PackLibrary"
    private const val DIR = "packs"
    private const val ATLAS = "atlas.png"
    private const val META = "meta.tsv"

    /** Identifier reserved for the palette generated in code. */
    const val BUILT_IN_ID = "builtin"

    data class PackInfo(val id: String, val name: String, val blockCount: Int)

    fun list(context: Context): List<PackInfo> {
        val root = File(context.filesDir, DIR)
        val dirs = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir -> readInfo(dir) }.sortedBy { it.name.lowercase() }
    }

    /** Persists [palette] under a fresh id and returns that id. */
    fun save(context: Context, palette: BlockPalette): String? {
        val id = "pack_" + System.currentTimeMillis().toString(36)
        val dir = File(File(context.filesDir, DIR), id)
        return try {
            dir.mkdirs()
            File(dir, ATLAS).outputStream().use {
                palette.atlas.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            File(dir, META).writeText(
                buildString {
                    appendLine(palette.name)
                    palette.entries.forEach { appendLine("${it.name}\t${it.avgColor}") }
                }
            )
            id
        } catch (e: Exception) {
            Log.w(TAG, "Paket kaydedilemedi", e)
            dir.deleteRecursively()
            null
        }
    }

    fun load(context: Context, id: String): BlockPalette? {
        if (id == BUILT_IN_ID) return null
        val dir = File(File(context.filesDir, DIR), id)
        return try {
            val atlasFile = File(dir, ATLAS)
            val metaFile = File(dir, META)
            if (!atlasFile.exists() || !metaFile.exists()) return null

            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val atlas = BitmapFactory.decodeFile(atlasFile.absolutePath, options) ?: return null

            val lines = metaFile.readLines().filter { it.isNotBlank() }
            if (lines.size < 2) return null
            val entries = lines.drop(1).mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 2) return@mapNotNull null
                val color = parts[1].toIntOrNull() ?: return@mapNotNull null
                BlockEntry(parts[0], color)
            }
            if (entries.isEmpty()) return null

            BlockPalette(lines[0], entries, atlas)
        } catch (e: Exception) {
            Log.w(TAG, "Paket okunamadı: $id", e)
            null
        }
    }

    fun delete(context: Context, id: String) {
        if (id == BUILT_IN_ID) return
        File(File(context.filesDir, DIR), id).deleteRecursively()
    }

    private fun readInfo(dir: File): PackInfo? {
        val metaFile = File(dir, META)
        if (!metaFile.exists()) return null
        return try {
            val lines = metaFile.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) null else PackInfo(dir.name, lines[0], lines.size - 1)
        } catch (e: Exception) {
            Log.w(TAG, "Paket bilgisi okunamadı: ${dir.name}", e)
            null
        }
    }
}
