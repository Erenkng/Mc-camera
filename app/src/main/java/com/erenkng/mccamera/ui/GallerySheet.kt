package com.erenkng.mccamera.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.erenkng.mccamera.R
import com.erenkng.mccamera.util.MediaItem
import com.erenkng.mccamera.util.MediaLibrary
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.concurrent.Executors

/**
 * The gallery of everything MC Camera has made.
 *
 * Opens as a half sheet over the live preview and expands to full screen when
 * dragged up, so glancing at the last shot never means leaving the viewfinder.
 */
class GallerySheet(
    private val activity: Activity,
    private val onImportPhoto: () -> Unit,
) {

    private val dialog = BottomSheetDialog(activity)
    private val executor = Executors.newFixedThreadPool(3)
    private val handler = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun show() {
        val view = LayoutInflater.from(dialog.context).inflate(R.layout.sheet_gallery, null)
        dialog.setContentView(view)

        val behavior = dialog.behavior
        behavior.isFitToContents = false
        behavior.halfExpandedRatio = 0.55f
        behavior.expandedOffset = 0
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED

        view.findViewById<View>(R.id.galleryImport).setOnClickListener {
            dialog.dismiss()
            onImportPhoto()
        }

        val list = view.findViewById<RecyclerView>(R.id.galleryList)
        val empty = view.findViewById<TextView>(R.id.galleryEmpty)

        list.layoutManager = GridLayoutManager(activity, SPAN)

        executor.execute {
            val items = MediaLibrary.recent(activity)
            handler.post {
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                list.adapter = Adapter(items)
            }
        }

        dialog.setOnDismissListener { executor.shutdownNow() }
        dialog.show()
    }

    private inner class Adapter(private val items: List<MediaItem>) :
        RecyclerView.Adapter<Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media, parent, false)
            // Square cells, whatever the screen width turns out to be.
            val cell = parent.measuredWidth / SPAN
            if (cell > 0) view.layoutParams.height = cell
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    }

    private inner class Holder(view: View) : RecyclerView.ViewHolder(view) {

        private val image: ImageView = view.findViewById(R.id.mediaImage)
        private val badge: View = view.findViewById(R.id.mediaVideoBadge)

        fun bind(item: MediaItem) {
            badge.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            image.setImageDrawable(null)

            val key = item.uri.toString()
            val cached = cache.get(key)
            if (cached != null) {
                image.setImageBitmap(cached)
            } else {
                image.tag = key
                executor.execute {
                    val bitmap = MediaLibrary.thumbnail(activity, item, THUMB_PX) ?: return@execute
                    cache.put(key, bitmap)
                    handler.post {
                        // The holder may have been recycled onto another item.
                        if (image.tag == key) image.setImageBitmap(bitmap)
                    }
                }
            }

            itemView.setOnClickListener {
                Motion.tick(it)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(item.uri, if (item.isVideo) "video/*" else "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { activity.startActivity(intent) }
            }
            Motion.springy(itemView, 0.95f)
        }
    }

    private companion object {
        const val SPAN = 3
        const val THUMB_PX = 320
    }
}
