package de.autoknigge.pdfreader

import android.graphics.Bitmap
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

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val image: ZoomableImageView = v.findViewById(R.id.ivPage)
        val progress: ProgressBar = v.findViewById(R.id.progress)
    }

    override fun getItemCount(): Int = renderer.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_reader_page, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        h.image.resetZoom()
        h.image.setImageBitmap(null)
        h.image.tag = position

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
}
