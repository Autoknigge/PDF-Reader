package de.autoknigge.pdfreader

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

/** Seiten als zoombare Bilder für den Lesebildschirm (ViewPager2). */
class PageAdapter(file: File, private val renderWidth: Int) : RecyclerView.Adapter<PageAdapter.VH>() {

    // PdfRenderer ist nicht threadsicher: alle Seiten auf einem Thread zeichnen.
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val renderer = PageRenderer(file)
    private val cache = object : LruCache<Int, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 5).toInt().coerceAtLeast(16 * 1024)
    ) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Langes Drücken auf eine Seitenstelle: Seite, Bruchteile x/y. */
    var onLongPress: ((Int, Float, Float) -> Unit)? = null

    private var recycler: RecyclerView? = null
    private var hlPage = -1
    private var hlBoxes: List<Box>? = null

    /** Anzeige: 0 hell, 1 dunkel (Farben umgekehrt), 2 Lesemodus (warmes Papier, weniger Blau). */
    var mode = 0
        set(value) {
            field = value
            val rv = recycler ?: return
            for (i in 0 until rv.childCount) {
                (rv.getChildViewHolder(rv.getChildAt(i)) as? VH)?.image?.colorFilter = filterFor(value)
            }
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val image: ZoomableImageView = v.findViewById(R.id.ivPage)
        val progress: ProgressBar = v.findViewById(R.id.progress)
    }

    override fun getItemCount(): Int = renderer.pageCount

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        recycler = rv
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        if (recycler === rv) recycler = null
    }

    /** Markiert die gelesene Stelle auf [page] (page < 0 oder boxes == null: Markierung entfernen). */
    fun setHighlight(page: Int, boxes: List<Box>?) {
        hlPage = page
        hlBoxes = boxes
        val rv = recycler ?: return
        for (i in 0 until rv.childCount) {
            val h = rv.getChildViewHolder(rv.getChildAt(i)) as? VH ?: continue
            h.image.highlight = if (h.bindingAdapterPosition == hlPage) hlBoxes else null
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_reader_page, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        h.image.colorFilter = filterFor(mode)
        h.image.resetZoom()
        h.image.setImageBitmap(null)
        h.image.tag = position
        h.image.highlight = if (position == hlPage) hlBoxes else null
        h.image.onLongPressFraction = { fx, fy ->
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onLongPress?.invoke(pos, fx, fy)
        }

        val cached = cache.get(position)
        if (cached != null) {
            h.progress.visibility = View.GONE
            h.image.setImageBitmap(cached)
            return
        }
        h.progress.visibility = View.VISIBLE
        worker.execute {
            val bmp = try {
                renderer.render(position, renderWidth)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            if (bmp != null) cache.put(position, bmp)
            main.post {
                if (h.image.tag == position) {
                    h.progress.visibility = View.GONE
                    if (bmp != null) h.image.setImageBitmap(bmp)
                }
            }
        }
    }

    fun shutdown() {
        worker.execute {
            try {
                renderer.close()
            } catch (ignored: Exception) {
            }
        }
        worker.shutdown()
    }

    companion object {
        fun filterFor(mode: Int): ColorMatrixColorFilter? = when (mode) {
            1 -> ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        -0.88f, 0f, 0f, 0f, 235f,
                        0f, -0.88f, 0f, 0f, 235f,
                        0f, 0f, -0.88f, 0f, 235f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
            2 -> ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, 0f,
                        0f, 0.95f, 0f, 0f, 0f,
                        0f, 0f, 0.83f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
            else -> null
        }
    }
}
