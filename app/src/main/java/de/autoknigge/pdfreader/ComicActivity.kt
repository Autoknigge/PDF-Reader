package de.autoknigge.pdfreader

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/** Comics als CBZ (Zip mit Bildern): Seite für Seite wischen, zoomen. */
class ComicActivity : AppCompatActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val decoder = Executors.newFixedThreadPool(2)
    private var zip: ZipFile? = null
    private var names: List<String> = emptyList()
    private lateinit var pager: ViewPager2
    private lateinit var tvPage: TextView
    private lateinit var tvTitle: TextView
    private lateinit var adManager: AdManager

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_screen)
        }
        Ui.insets(root)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(12), 0)
        }
        top.addView(TextView(this).apply {
            text = "‹"; textSize = 30f; setTextColor(0xFFD4AF37.toInt()); gravity = Gravity.CENTER
            contentDescription = "Zurück"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        tvTitle = TextView(this).apply {
            textSize = 17f; setTextColor(0xFFD4AF37.toInt()); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        top.addView(tvTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tvPage = TextView(this).apply { textSize = 15f; setTextColor(0xFFF8E7A4.toInt()) }
        top.addView(tvPage)
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        pager = ViewPager2(this).apply {
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) { updatePage() }
            })
        }
        root.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val adBox = FrameLayout(this).apply { setBackgroundResource(R.drawable.bg_ad); minimumHeight = dp(60) }
        root.addView(adBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        adManager = AdManager(this, adBox, null)
        Intro.run(this) { adManager.start() }

        val u = intent.data
        if (u == null) {
            Toast.makeText(this, "Keine Datei übergeben.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        open(u)
    }

    private fun updatePage() {
        tvPage.text = if (names.isEmpty()) "" else "${pager.currentItem + 1} / ${names.size}"
    }

    private fun queryName(u: Uri): String? = try {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun natural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var x = i
                while (x < a.length && a[x].isDigit()) x++
                var y = j
                while (y < b.length && b[y].isDigit()) y++
                val na = a.substring(i, x).trimStart('0')
                val nb = b.substring(j, y).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = x; j = y
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    private fun open(u: Uri) {
        tvTitle.text = queryName(u) ?: "Comic"
        tvPage.text = "wird geöffnet …"
        worker.execute {
            val r = runCatching {
                val f = File(cacheDir, "comic.cbz")
                contentResolver.openInputStream(u)?.use { input -> f.outputStream().use { input.copyTo(it, 64 * 1024) } }
                    ?: throw IOException("Die Datei konnte nicht geöffnet werden.")
                val z = ZipFile(f)
                val list = z.entries().asSequence()
                    .filter { !it.isDirectory }
                    .map { it.name }
                    .filter {
                        val l = it.lowercase()
                        !l.contains("__macosx") && !l.substringAfterLast('/').startsWith(".") &&
                            (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp") || l.endsWith(".gif"))
                    }
                    .toList().sortedWith { a, b -> natural(a, b) }
                if (list.isEmpty()) throw IOException("In dieser Datei wurden keine Bilder gefunden.")
                z to list
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                r.onSuccess { (z, list) ->
                    zip = z
                    names = list
                    pager.adapter = Adapter()
                    updatePage()
                }.onFailure {
                    Toast.makeText(this, it.message ?: "Das Comic konnte nicht geöffnet werden.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun decode(name: String): android.graphics.Bitmap? = try {
        val z = zip ?: return null
        val e = z.getEntry(name) ?: return null
        val bytes = z.getInputStream(e).use { DocConverter.readAll(it, 40 * 1024 * 1024) }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > 2400) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Throwable) {
        null
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val img: ZoomableImageView) : RecyclerView.ViewHolder(img)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ZoomableImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            })

        override fun getItemCount() = names.size

        override fun onBindViewHolder(h: VH, position: Int) {
            h.img.setImageBitmap(null)
            h.img.tag = position
            val name = names[position]
            decoder.execute {
                val bm = decode(name)
                runOnUiThread { if (h.img.tag == position && bm != null) h.img.setImageBitmap(bm) }
            }
        }
    }

    override fun onPause() { adManager.pause(); super.onPause() }
    override fun onResume() { super.onResume(); adManager.resume() }
    override fun onDestroy() {
        adManager.destroy()
        worker.shutdown()
        decoder.shutdown()
        try { zip?.close() } catch (ignored: Exception) {}
        super.onDestroy()
    }
}
