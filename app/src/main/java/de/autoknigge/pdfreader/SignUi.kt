package de.autoknigge.pdfreader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SignResult(val uri: Uri, val name: String)

/** Was die jeweilige App zum Unterschreiben beisteuert (PDF liefern, Seiten zeichnen, Ergebnis speichern). */
interface SignHost {
    /** Nur Werkstatt: PDF wählen lassen. */
    fun pickFile()
    val canPick: Boolean
    /** Läuft im Hintergrund. Zeichnet Seite [index] (0-basiert). */
    fun render(index: Int): Bitmap
    /** Läuft im Hintergrund. Setzt die Unterschriften in die PDF und speichert sie. */
    fun sign(placements: List<Placement>, progress: (Int) -> Unit): SignResult
    fun showDone(result: SignResult)
    fun pickPhoto()
}

/** Gemeinsame Oberfläche zum Unterschreiben: Unterschriften verwalten, Foto, Datum/Ort, Seite mit Verschieben. */
class SignController(private val act: AppCompatActivity, private val host: SignHost) {

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val GOLD = 0xFFD4AF37.toInt()
        private const val TEXT = 0xFFF8E7A4.toInt()
        private const val DIM = 0xFFCFC38E.toInt()
    }

    val worker: ExecutorService = Executors.newSingleThreadExecutor()
    val view: LinearLayout
    private var pages = 0
    private var pageIdx = 0
    private var busy = false
    private var hasFile = false
    private val placements = ArrayList<Placement>()
    private var cur: Bitmap? = null
    private var photoBmp: Bitmap? = null

    private val place: PlaceView
    private val tvFile: TextView
    private val tvPage: TextView
    private val tvSize: TextView
    private val seek: SeekBar
    private val btnSave: TextView
    private val btnAdd: TextView
    private val btnRemove: TextView
    private val btnSig: TextView
    private val progress: ProgressBar
    private var updatingSeek = false

    private fun dp(n: Int) = (n * act.resources.displayMetrics.density).toInt()

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(act).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun darkButton(label: String, onClick: () -> Unit) = text(label, 15f, TEXT, true).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(0xFF1B2E22.toInt())
            setStroke(dp(2), 0xFFC9A648.toInt())
            cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(8), dp(10), dp(8), dp(10))
        minHeight = dp(46)
        isClickable = true
        setOnClickListener { onClick() }
    }

    init {
        cur = SignatureStore.current(act)?.let { SignatureStore.load(it) }
        view = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        tvFile = text("", 15f, TEXT, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            if (host.canPick) {
                text = "Tippen, um ein Formular (PDF) zu wählen"
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_btn_gold)
                setTextColor(act.resources.getColor(R.color.auf_gold, act.theme))
                setPadding(dp(16), dp(10), dp(16), dp(15))
                minHeight = dp(60)
                isClickable = true
                setOnClickListener { if (!busy) host.pickFile() }
            }
        }
        view.addView(tvFile, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(14), dp(4), dp(14), 0) })

        place = PlaceView(act).apply {
            emptyText = if (host.canPick) "Wähle oben ein Formular.\nDanach schiebst du deine Unterschrift\ndorthin, wo sie gebraucht wird." else "Die Seite wird geladen …"
            placements = this@SignController.placements
            onSelect = { updateControls() }
            onResize = { p -> setSeek(p) }
        }
        view.addView(place, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })

        val nav = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        nav.addView(darkButton("‹  Zurück") { gotoPage(pageIdx - 1) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(14), 0, dp(6), 0) })
        tvPage = text("", 15f, TEXT, true).apply { gravity = Gravity.CENTER }
        nav.addView(tvPage, LinearLayout.LayoutParams(0, WRAP, 1f))
        nav.addView(darkButton("Weiter  ›") { gotoPage(pageIdx + 1) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(6), 0, dp(14), 0) })
        view.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        val sizeRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(2), dp(14), 0)
        }
        tvSize = text("Größe", 14f, DIM)
        sizeRow.addView(tvSize, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(10) })
        seek = SeekBar(act).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(GOLD)
            thumbTintList = android.content.res.ColorStateList.valueOf(GOLD)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                    if (!user || updatingSeek) return
                    place.selected?.let { it.w = 0.06f + 0.84f * p / 100f; place.invalidate() }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        sizeRow.addView(seek, LinearLayout.LayoutParams(0, WRAP, 1f))
        view.addView(sizeRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val row1 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(4), dp(14), 0) }
        btnSig = darkButton("✍  Unterschrift") { showManage() }
        row1.addView(btnSig, LinearLayout.LayoutParams(0, WRAP, 1.15f).apply { rightMargin = dp(6) })
        btnAdd = darkButton("＋  Einsetzen") { addPlacement() }
        row1.addView(btnAdd, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
        btnRemove = darkButton("🗑") { removeSelected() }.apply { contentDescription = "Markierte Unterschrift entfernen" }
        row1.addView(btnRemove, LinearLayout.LayoutParams(0, WRAP, 0.45f))
        view.addView(row1, LinearLayout.LayoutParams(MATCH, WRAP))
        val row2 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(6), dp(14), 0) }
        row2.addView(darkButton("🖼  Aus Foto") { host.pickPhoto() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
        row2.addView(darkButton("📅  Datum / Ort") { showDateDialog() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        view.addView(row2, LinearLayout.LayoutParams(MATCH, WRAP))

        btnSave = text("Speichern", 20f, act.resources.getColor(R.color.auf_gold, act.theme), true).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_btn_gold)
            setPadding(dp(16), dp(12), dp(16), dp(18))
            minHeight = dp(58)
            isClickable = true
            setOnClickListener { save() }
        }
        view.addView(btnSave, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(14), dp(8), dp(14), 0) })
        progress = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = View.GONE }
        view.addView(progress, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(14), dp(4), dp(14), 0) })
        updateControls()
    }

    fun shutdown() = worker.shutdown()

    // ------------------------------------------------------------------ Datei und Seiten

    fun setStatus(t: String) { tvFile.text = t }

    /** Die App meldet: PDF ist geöffnet und kann dargestellt werden. */
    fun fileReady(name: String, pageCount: Int) {
        hasFile = true
        pages = pageCount
        pageIdx = 0
        placements.clear()
        place.selected = null
        tvFile.text = if (host.canPick) "$name\n${if (pageCount == 1) "1 Seite" else "$pageCount Seiten"}" else name
        showPage()
        if (SignatureStore.list(act).isEmpty()) showPad()
        updateControls()
    }

    private fun gotoPage(i: Int) {
        if (!hasFile || i !in 0 until pages || busy) return
        pageIdx = i
        place.selected = null
        showPage()
    }

    private fun showPage() {
        val idx = pageIdx
        worker.execute {
            val bmp = runCatching { host.render(idx) }
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed || idx != pageIdx) return@runOnUiThread
                bmp.onSuccess { place.setPage(it, idx) }.onFailure { toast("Seite konnte nicht angezeigt werden") }
                updateControls()
            }
        }
    }

    private fun toast(t: String) = android.widget.Toast.makeText(act, t, android.widget.Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ Unterschriften

    private fun useSignature(f: File) {
        SignatureStore.setCurrent(act, f)
        cur = SignatureStore.load(f)
        updateControls()
    }

    /** Liste der gespeicherten Unterschriften: auswählen, löschen, neu zeichnen, aus Foto. */
    private fun showManage() {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(act).apply { addView(list) }
        box.addView(scroll, LinearLayout.LayoutParams(MATCH, WRAP))
        lateinit var dlg: AlertDialog
        fun fill() {
            list.removeAllViews()
            val files = SignatureStore.list(act)
            val current = SignatureStore.current(act)
            if (files.isEmpty()) list.addView(text("Noch keine Unterschrift gespeichert.", 14f, DIM))
            for (f in files) {
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
                val iv = ImageView(act).apply {
                    setImageBitmap(SignatureStore.load(f))
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.WHITE)
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    val sel = f == current
                    foreground = if (sel) GradientDrawable().apply { setStroke(dp(3), 0xFFD4AF37.toInt()); setColor(Color.TRANSPARENT) } else null
                    setOnClickListener { useSignature(f); dlg.dismiss() }
                }
                row.addView(iv, LinearLayout.LayoutParams(0, dp(84), 1f))
                row.addView(text("✕", 18f, DIM).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "Diese Unterschrift löschen"
                    setOnClickListener {
                        SignatureStore.delete(act, f)
                        cur = SignatureStore.current(act)?.let { SignatureStore.load(it) }
                        fill(); updateControls()
                    }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                list.addView(row)
            }
            list.addView(text("Tippe auf eine Unterschrift, um sie zu verwenden. Sie bleiben nur auf deinem Gerät gespeichert.", 12f, DIM), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        fill()
        dlg = AlertDialog.Builder(act)
            .setTitle("Unterschriften")
            .setView(box)
            .setPositiveButton("Neu zeichnen") { _, _ -> showPad() }
            .setNeutralButton("Aus Foto") { _, _ -> host.pickPhoto() }
            .setNegativeButton("Schließen", null)
            .create()
        dlg.show()
    }

    private fun showPad() {
        val pad = SignaturePad(act)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        box.addView(pad, LinearLayout.LayoutParams(MATCH, dp(220)))
        val ink = LinearLayout(act).apply { gravity = Gravity.CENTER }
        ink.addView(darkButton("Blau") { pad.ink = SignatureImage.BLUE }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(0, dp(8), dp(6), 0) })
        ink.addView(darkButton("Schwarz") { pad.ink = SignatureImage.BLACK }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(6), dp(8), 0, 0) })
        box.addView(ink)
        box.addView(text("Die Unterschrift bleibt nur auf deinem Gerät gespeichert.", 12f, DIM), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        val dlg = AlertDialog.Builder(act)
            .setTitle("Unterschrift zeichnen")
            .setView(box)
            .setPositiveButton("Übernehmen", null)
            .setNeutralButton("Löschen", null)
            .setNegativeButton("Abbrechen", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { pad.clear() }
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val bmp = pad.toBitmap()
            if (bmp == null) {
                toast("Bitte zuerst unterschreiben.")
            } else {
                dlg.dismiss()
                adopt(bmp)
            }
        }
    }

    /** Speichert eine neue Unterschrift, macht sie zur aktuellen und setzt sie ein, wenn die Seite noch keine hat. */
    private fun adopt(bmp: Bitmap) {
        SignatureStore.add(act, bmp)
        cur = bmp
        if (hasFile && placements.none { it.page == pageIdx }) addPlacement()
        updateControls()
    }

    // ------------------------------------------------------------------ Foto

    /** Das Foto wurde gewählt (von der App aus dem System-Dialog); zeigt die Freistell-Vorschau. */
    fun onPhoto(bmp: Bitmap?) {
        if (bmp == null) { toast("Das Foto konnte nicht geladen werden."); return }
        photoBmp = SignatureImage.scaleDown(bmp, 1400)
        var ink = SignatureImage.BLUE
        var sens = 50
        val preview = ImageView(act).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFF3F3F3.toInt())
        }
        var result: Bitmap? = null
        fun update() {
            val small = SignatureImage.scaleDown(photoBmp!!, 700)
            result = SignatureImage.fromPhoto(small, sens, ink)
            preview.setImageBitmap(result)
        }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        box.addView(preview, LinearLayout.LayoutParams(MATCH, dp(180)))
        box.addView(text("Empfindlichkeit (heller = mehr Striche)", 12f, DIM), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        box.addView(SeekBar(act).apply {
            max = 100; progress = sens
            progressTintList = android.content.res.ColorStateList.valueOf(GOLD)
            thumbTintList = android.content.res.ColorStateList.valueOf(GOLD)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) { sens = p; update() } }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })
        val row = LinearLayout(act).apply { gravity = Gravity.CENTER }
        row.addView(darkButton("Blau") { ink = SignatureImage.BLUE; update() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(4) })
        row.addView(darkButton("Schwarz") { ink = SignatureImage.BLACK; update() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(4) })
        row.addView(darkButton("↻ Drehen") { photoBmp = SignatureImage.rotate(photoBmp!!, 90f); update() }, LinearLayout.LayoutParams(0, WRAP, 1.2f))
        box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        box.addView(text("Tipp: Unterschreibe mit dunklem Stift auf weißem Papier und fotografiere es bei gutem Licht, ohne Schatten.", 12f, DIM), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        update()
        val dlg = AlertDialog.Builder(act)
            .setTitle("Unterschrift aus Foto")
            .setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("Übernehmen", null)
            .setNegativeButton("Abbrechen", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val finalBmp = SignatureImage.fromPhoto(photoBmp!!, sens, ink)
            if (finalBmp == null) {
                toast("Auf dem Foto ist keine Schrift zu erkennen. Versuche eine höhere Empfindlichkeit.")
            } else {
                dlg.dismiss()
                adopt(SignatureImage.scaleDown(finalBmp, 1000))
            }
        }
    }

    // ------------------------------------------------------------------ Datum und Ort

    private fun showDateDialog() {
        if (!hasFile) return toast(if (host.canPick) "Wähle zuerst ein Formular." else "Bitte kurz warten, die Seite lädt.")
        val c = Calendar.getInstance()
        val today = String.format(Locale.GERMANY, "%02d.%02d.%d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR))
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val ort = EditText(act).apply { hint = "Ort (optional), zum Beispiel Cappeln"; setText(SignatureStore.place(act)); setSingleLine(true) }
        val datum = EditText(act).apply { hint = "Datum"; setText(today); setSingleLine(true) }
        box.addView(ort)
        box.addView(datum)
        AlertDialog.Builder(act)
            .setTitle("Datum und Ort einsetzen")
            .setView(box)
            .setPositiveButton("Einsetzen") { _, _ ->
                val o = ort.text.toString().trim()
                val d = datum.text.toString().trim()
                val t = listOf(o, d).filter { it.isNotEmpty() }.joinToString(", ")
                if (t.isEmpty()) return@setPositiveButton
                SignatureStore.setPlace(act, o)
                val bmp = SignatureImage.textBitmap(t, SignatureImage.BLUE)
                val p = Placement(pageIdx, 0.3f, 0.9f, 0.26f, bmp)
                placements.add(p)
                place.selected = p
                setSeek(p)
                toast("Mit dem Finger verschieben, mit zwei Fingern oder dem Regler die Größe ändern.")
                updateControls()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ------------------------------------------------------------------ Einsetzen und Speichern

    private fun addPlacement() {
        if (!hasFile) return toast(if (host.canPick) "Wähle zuerst ein Formular." else "Bitte kurz warten, die Seite lädt.")
        val bmp = cur ?: return showPad()
        val p = Placement(pageIdx, 0.5f, 0.82f, 0.30f, bmp)
        placements.add(p)
        place.selected = p
        setSeek(p)
        toast("Mit dem Finger verschieben, mit zwei Fingern oder dem Regler die Größe ändern.")
        updateControls()
    }

    private fun removeSelected() {
        val s = place.selected ?: return
        placements.remove(s)
        place.selected = null
        updateControls()
    }

    private fun setSeek(p: Placement) {
        updatingSeek = true
        seek.progress = ((p.w - 0.06f) / 0.84f * 100f).toInt().coerceIn(0, 100)
        updatingSeek = false
    }

    private fun updateControls() {
        tvPage.text = if (!hasFile) "" else "Seite ${pageIdx + 1} von $pages"
        val sel = place.selected
        seek.isEnabled = sel != null
        seek.alpha = if (sel != null) 1f else 0.4f
        tvSize.alpha = seek.alpha
        if (sel != null) setSeek(sel)
        btnRemove.alpha = if (sel != null) 1f else 0.4f
        btnAdd.alpha = if (hasFile) 1f else 0.5f
        val ready = hasFile && placements.isNotEmpty() && !busy
        btnSave.isEnabled = ready
        btnSave.alpha = if (ready) 1f else 0.5f
        btnSave.text = if (placements.isEmpty()) "Speichern" else "Speichern (${placements.size} Einfügung${if (placements.size == 1) "" else "en"})"
    }

    private fun save() {
        if (busy || placements.isEmpty()) return
        val list = placements.map { Placement(it.page, it.cx, it.cy, it.w, it.bmp) }
        busy = true
        val steps = list.map { it.page }.distinct().size
        progress.isIndeterminate = false
        progress.max = maxOf(1, steps)
        progress.progress = 0
        progress.visibility = View.VISIBLE
        updateControls()
        worker.execute {
            val r = runCatching { host.sign(list) { done -> act.runOnUiThread { progress.progress = done } } }
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                busy = false
                progress.visibility = View.GONE
                updateControls()
                r.onSuccess { host.showDone(it) }.onFailure { toast("Unterschreiben fehlgeschlagen: ${it.message ?: "unbekannter Fehler"}") }
            }
        }
    }
}
