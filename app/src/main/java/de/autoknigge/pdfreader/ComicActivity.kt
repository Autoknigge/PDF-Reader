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
    private lateinit var library: Library
    private var uriKey = ""
    private var docName = "Comic"
    private var srcUri: Uri? = null

    private val saveCopy = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/vnd.comicbook+zip")
    ) { target ->
        if (target != null) {
            worker.execute {
                val ok = try {
                    contentResolver.openOutputStream(target, "wt")?.use { out ->
                        File(cacheDir, "comic.cbz").inputStream().use { it.copyTo(out, 64 * 1024) }
                    } ?: throw IOException()
                    true
                } catch (e: Exception) {
                    false
                }
                runOnUiThread { Toast.makeText(this, if (ok) "Gespeichert." else "Das Speichern ist fehlgeschlagen.", Toast.LENGTH_LONG).show() }
            }
        }
    }

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
        top.addView(TextView(this).apply {
            text = "⋮"; textSize = 24f; setTextColor(0xFFD4AF37.toInt()); gravity = Gravity.CENTER
            contentDescription = "Mehr"; setOnClickListener { openMenu() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        pager = ViewPager2(this).apply {
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updatePage()
                    if (uriKey.isNotEmpty()) library.setPage(uriKey, position)
                }
            })
        }
        root.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val adBox = FrameLayout(this).apply { setBackgroundResource(R.drawable.bg_ad); minimumHeight = dp(60) }
        root.addView(adBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        adManager = AdManager(this, adBox, null)
        Intro.run(this) { adManager.start() }

        library = Library(this)
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
        srcUri = u
        uriKey = u.toString()
        docName = queryName(u) ?: "Comic"
        tvTitle.text = docName
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
                    val last = library.page(uriKey)
                    library.touch(uriKey, docName, list.size, "comic")
                    pager.adapter = Adapter()
                    pager.setCurrentItem(last.coerceIn(0, list.size - 1), false)
                    updatePage()
                }.onFailure {
                    Toast.makeText(this, it.message ?: "Das Comic konnte nicht geöffnet werden.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    /** Gleiche Reihenfolge wie in den anderen Menüs; nicht Zutreffendes (Suche, Inhaltsverzeichnis, Drucken) fehlt einfach. */
    private fun openMenu() {
        if (names.isEmpty()) return
        val page = pager.currentItem
        val marked = library.isBookmarked(uriKey, page)
        val marks = library.bookmarks(uriKey)
        val e = ArrayList<MenuUi.Entry>()
        e.add(MenuUi.Entry("🔖", if (marked) "Lesezeichen entfernen (Seite ${page + 1})" else "Lesezeichen setzen (Seite ${page + 1})") {
            val now = library.toggleBookmark(uriKey, page)
            Toast.makeText(this, if (now) "Lesezeichen gesetzt." else "Lesezeichen entfernt.", Toast.LENGTH_SHORT).show()
        })
        if (marks.isNotEmpty()) e.add(MenuUi.Entry("📚", "Lesezeichen anzeigen (${marks.size})") { bookmarkList() })
        e.add(MenuUi.Entry("📝", "Notizen und Zitate") {
            startActivity(
                android.content.Intent(this, TextActivity::class.java)
                    .putExtra(TextActivity.EXTRA_PDFNOTE_KEY, Notes.key(uriKey))
                    .putExtra(TextActivity.EXTRA_PDFNOTE_TITLE, docName)
                    .putExtra(TextActivity.EXTRA_PDFNOTE_PAGE, page)
            )
        })
        e.add(MenuUi.Entry("📤", "Senden an …") { shareComic() })
        e.add(MenuUi.Entry("💾", "Speichern unter …") { saveCopy.launch(docName) })
        // Nur bei Comics
        e.add(MenuUi.Entry("🔢", "Zu Seite springen …") { gotoDialog() })
        MenuUi.show(this, docName, e)
    }

    private fun bookmarkList() {
        val marks = library.bookmarks(uriKey)
        if (marks.isEmpty()) return
        MenuUi.show(this, "Lesezeichen", marks.map { m ->
            MenuUi.Entry("🔖", "Seite ${m + 1}") { pager.setCurrentItem(m.coerceIn(0, names.size - 1), false) }
        })
    }

    private fun shareComic() {
        val u = srcUri ?: return
        try {
            startActivity(
                android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/vnd.comicbook+zip"
                        putExtra(android.content.Intent.EXTRA_STREAM, u)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Senden an"
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Keine App zum Senden gefunden.", Toast.LENGTH_LONG).show()
        }
    }

    private fun gotoDialog() {
        val box = android.widget.EditText(this).apply {
            hint = "Seite (1 bis ${names.size})"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        val wrap = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(box) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Zu Seite springen")
            .setView(wrap)
            .setPositiveButton("Los") { _, _ ->
                val n = box.text.toString().toIntOrNull()
                if (n != null) pager.setCurrentItem((n - 1).coerceIn(0, names.size - 1), false)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
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
