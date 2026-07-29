package com.erenkng.mccamera.util

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import kotlin.math.max

/** One photo or video the app has produced. */
data class MediaItem(val uri: Uri, val isVideo: Boolean, val takenAt: Long)

/**
 * Lists what MC Camera has saved to the gallery.
 *
 * Only the app's own contributions are read, which on Android 10 and up needs no
 * storage permission at all.
 */
object MediaLibrary {

    private const val TAG = "MediaLibrary"
    private const val FOLDER = "MC Camera"

    fun recent(context: Context, limit: Int = 90): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        items += query(context, video = false, limit = limit)
        items += query(context, video = true, limit = limit)
        return items.sortedByDescending { it.takenAt }.take(limit)
    }

    private fun query(context: Context, video: Boolean, limit: Int): List<MediaItem> {
        val collection = if (video) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val idColumn = MediaStore.MediaColumns._ID
        val dateColumn = MediaStore.MediaColumns.DATE_ADDED

        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            args = arrayOf("%$FOLDER%")
        } else {
            @Suppress("DEPRECATION")
            selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
            args = arrayOf("%/$FOLDER/%")
        }

        val result = ArrayList<MediaItem>()
        try {
            context.contentResolver.query(
                collection,
                arrayOf(idColumn, dateColumn),
                selection,
                args,
                "$dateColumn DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(idColumn)
                val dateIndex = cursor.getColumnIndexOrThrow(dateColumn)
                while (cursor.moveToNext() && result.size < limit) {
                    val id = cursor.getLong(idIndex)
                    result += MediaItem(
                        ContentUris.withAppendedId(collection, id),
                        video,
                        cursor.getLong(dateIndex),
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Galeri okunamadı", e)
        }
        return result
    }

    /** Best-effort square-ish thumbnail. Returns null when the item is gone. */
    fun thumbnail(context: Context, item: MediaItem, size: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(item.uri, Size(size, size), null)
        } else if (item.isVideo) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, item.uri)
                retriever.frameAtTime
            } finally {
                retriever.release()
            }
        } else {
            decodeScaled(context, item.uri, size)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Küçük resim üretilemedi", e)
        null
    }

    private fun decodeScaled(context: Context, uri: Uri, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / sample > size * 2) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }
}
