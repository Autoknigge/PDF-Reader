package de.autoknigge.pdfreader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.graphics.Paint
import android.graphics.Typeface as Tf
import android.graphics.pdf.PdfDocument
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.BreakIterator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Textdateien (.txt, .md, .csv …) lesen und schreiben, dazu neue Notizen.
 * Neue Notizen speichert die App selbst in „Downloads/PDF-Reader“ und sichert sie beim Verlassen automatisch.
 * Fremde Dateien werden mit „Speichern“ zurückgeschrieben; geht das nicht, bietet die App „Speichern unter“ an.
 */
class TextActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NEW = "neu"
        const val EXTRA_PDFNOTE_KEY = "pdfnotiz_key"
        const val EXTRA_PDFNOTE_TITLE = "pdfnotiz_titel"
        const val EXTRA_PDFNOTE_PAGE = "pdfnotiz_seite"
        private const val MAX_BYTES = 3 * 1024 * 1024
        private const val GOLD = 0xFFD4AF37.toInt()
        private const val CREME = 0xFFF8E7A4.toInt()
        private const val TANNE_DUNKEL = 0xFF0F1B14.toInt()
        private const val TANNE = 0xFF1A2C20.toInt()
    }

    private lateinit var edit: EditText
    private lateinit var tvTitle: TextView
    private lateinit var adManager: AdManager
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("pdfreader", Context.MODE_PRIVATE) }

    private var uri: Uri? = null
    private var name = "Notiz.txt"
    private var dirty = false
    private var loading = true
    private var ownNote = false      // von der App selbst angelegt: wird automatisch gesichert
    private var lastQuery = ""
    private var sheets: List<DocConverter.Sheet>? = null
    private var tableMode = false
    private var sheetNo = 0
    private var shownRows = 0
    private lateinit var scroll: ScrollView
    private lateinit var tableBox: LinearLayout
    private lateinit var sheetTabs: LinearLayout
    private lateinit var tableGrid: android.widget.TableLayout
    private lateinit var btnMore: TextView
    private var docx = false         // diese Datei wird als Word-Datei gespeichert
    private var pdfNote = false      // Notiz zu einer PDF (liegt intern, wird automatisch gesichert)
    private var pdfNoteFile: java.io.File? = null
    private var pdfNotePage = 0
    private var fmtHintShown = false
    private lateinit var fmtBar: LinearLayout
    private var converted = false    // aus Word/EPUB/HTML/... umgewandelt: das Original wird nie überschrieben

    // Vorlesen
    private enum class Read { IDLE, PLAYING, PAUSED }
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var onTtsReady: (() -> Unit)? = null
    private var reading = Read.IDLE
    private var chunks = ArrayList<IntArray>()
    private var ci = 0
    private var gen = 0
    private lateinit var readBar: LinearLayout
    private lateinit var tvReadStatus: TextView
    private lateinit var btnPlay: TextView
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var focusReq: AudioFocusRequest? = null
    private var dark = true
    private var sizeSp = 16f

    private val saveAs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { u ->
        if (u != null && pdfNote) {
            if (writeTo(u, asDocx = false)) toast("Kopie gespeichert.")
        } else if (u != null) {
            if (writeTo(u, asDocx = false)) {
                uri = u
                docx = false
                name = queryName(u) ?: name
                ownNote = false
                converted = false
                dirty = false
                updateTitle()
                toast("Gespeichert.")
            }
        }
    }

    private val saveDocx = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { u ->
        if (u != null && writeTo(u, asDocx = true)) {
            if (pdfNote) {
                toast("Kopie als Word-Datei gespeichert.")
            } else {
                uri = u
                name = queryName(u) ?: name
                ownNote = false
                converted = false
                docx = true
                dirty = false
                updateTitle()
                toast("Als Word-Datei gespeichert.")
            }
        }
    }

    private val savePdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u ->
        if (u != null) {
            worker.execute {
                val ok = try {
                    val bytes = makePdf()
                    contentResolver.openOutputStream(u, "wt")?.use { it.write(bytes) } ?: throw IOException()
                    true
                } catch (e: Throwable) {
                    false
                }
                runOnUiThread { toast(if (ok) "Als PDF gespeichert." else "Das PDF konnte nicht erstellt werden.") }
            }
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        dark = prefs.getBoolean("text_dunkel", true)
        sizeSp = prefs.getFloat("text_groesse", 16f)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_screen)
        }
        Ui.insets(root)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        top.addView(iconButton("‹", "Zurück") { onBack() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        tvTitle = TextView(this).apply {
            textSize = 18f
            setTextColor(GOLD)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        top.addView(tvTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(iconButton("🔊", "Vorlesen") { startReading() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(iconButton("💾", "Speichern") { save() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(iconButton("⋮", "Mehr") { openMenu() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fmtBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            setBackgroundColor(TANNE)
        }
        fmtBar.addView(iconButton("B", "Fett") { toggleStyle(Tf.BOLD) }.apply { setTypeface(Tf.DEFAULT_BOLD) }, LinearLayout.LayoutParams(dp(48), dp(40)))
        fmtBar.addView(iconButton("I", "Kursiv") { toggleStyle(Tf.ITALIC) }.apply { setTypeface(Tf.DEFAULT, Tf.ITALIC) }, LinearLayout.LayoutParams(dp(48), dp(40)))
        fmtBar.addView(iconButton("U", "Unterstreichen") { toggleDeco(true) }.apply { paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG }, LinearLayout.LayoutParams(dp(48), dp(40)))
        fmtBar.addView(iconButton("S", "Durchstreichen") { toggleDeco(false) }.apply { paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG }, LinearLayout.LayoutParams(dp(48), dp(40)))
        fmtBar.addView(iconButton("Ü", "Überschrift") { cycleHeading() }, LinearLayout.LayoutParams(dp(48), dp(40)))
        fmtBar.addView(iconButton("•", "Liste") { toggleBullets() }, LinearLayout.LayoutParams(dp(48), dp(40)))
        root.addView(fmtBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = null
            isVerticalScrollBarEnabled = true
            setHorizontallyScrolling(false)
            hint = "Hier schreiben …"
            isEnabled = false
        }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(edit, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        buildTableUi()
        root.addView(tableBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        applyLook()

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!loading && reading != Read.IDLE) stopReading()
                if (!loading && !dirty) {
                    dirty = true
                    updateTitle()
                }
            }
        })

        readBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(8), dp(4))
            setBackgroundColor(TANNE)
            visibility = android.view.View.GONE
        }
        tvReadStatus = TextView(this).apply { textSize = 14f; setTextColor(CREME) }
        readBar.addView(tvReadStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnPlay = iconButton("⏸", "Pause") { toggleRead() }
        readBar.addView(btnPlay, LinearLayout.LayoutParams(dp(48), dp(44)))
        readBar.addView(iconButton("⏹", "Stopp") { stopReading() }, LinearLayout.LayoutParams(dp(48), dp(44)))
        root.addView(readBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val adBox = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_ad)
            minimumHeight = dp(60)
        }
        root.addView(adBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        adManager = AdManager(this, adBox, null)
        Intro.run(this) { adManager.start() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        start()
    }

    // ------------------------------------------------------------------ Laden

    private fun start() {
        val i = intent
        when {
            i.hasExtra(EXTRA_PDFNOTE_KEY) -> openPdfNote(i)
            i.getBooleanExtra(EXTRA_NEW, false) -> newNote("")
            i.action == Intent.ACTION_SEND && i.hasExtra(Intent.EXTRA_TEXT) ->
                newNote(i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: "")
            i.data != null -> openFile(i.data!!)
            else -> {
                toast("Keine Datei übergeben.")
                finish()
            }
        }
    }

    private fun newNote(text: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.GERMANY).format(Date())
        name = "Notiz_$stamp.txt"
        ownNote = true
        loading = true
        edit.setText(text)
        edit.isEnabled = true
        loading = false
        dirty = text.isNotEmpty()
        updateTitle()
        edit.requestFocus()
        showKeyboard()
    }

    private fun openPdfNote(i: Intent) {
        val key = (i.getStringExtra(EXTRA_PDFNOTE_KEY) ?: "").filter { it.isLetterOrDigit() }.ifEmpty { "x" }
        val title = i.getStringExtra(EXTRA_PDFNOTE_TITLE) ?: "PDF"
        pdfNotePage = i.getIntExtra(EXTRA_PDFNOTE_PAGE, 0)
        pdfNote = true
        name = "Notizen: $title"
        val dir = java.io.File(filesDir, "pdfnotizen").apply { mkdirs() }
        val file = java.io.File(dir, "$key.txt")
        pdfNoteFile = file
        val t = try { if (file.exists()) file.readText(Charsets.UTF_8) else "" } catch (e: Exception) { "" }
        loading = true
        edit.setText(t)
        edit.isEnabled = true
        loading = false
        dirty = false
        updateTitle()
        if (t.isEmpty()) { edit.requestFocus(); showKeyboard() }
    }

    private fun savePdfNote() {
        val f = pdfNoteFile ?: return
        try {
            val t = edit.text.toString()
            if (t.isEmpty()) f.delete() else f.writeText(t, Charsets.UTF_8)
            dirty = false
            updateTitle()
        } catch (ignored: Exception) {
        }
    }

    private fun openFile(u: Uri) {
        uri = u
        name = queryName(u) ?: (u.lastPathSegment?.substringAfterLast('/') ?: "Text.txt")
        val kind = DocConverter.kindOf(name, contentResolver.getType(u))
        converted = kind != DocConverter.Kind.TEXT
        if (converted && kind != DocConverter.Kind.DOCX) name = name.substringBeforeLast('.', name) + ".txt"
        docx = kind == DocConverter.Kind.DOCX
        updateTitle()
        worker.execute {
            var sh: List<DocConverter.Sheet>? = null
            var own = false
            val result = runCatching<CharSequence> {
                if (kind == DocConverter.Kind.DOCX) {
                    val bytes = contentResolver.openInputStream(u)?.use { DocConverter.readAll(it, DocConverter.MAX_FILE) }
                        ?: throw IOException("Die Datei konnte nicht geöffnet werden.")
                    own = DocConverter.isOwnDocx(bytes)
                    buildSpannable(DocConverter.docxRich(bytes))
                } else if (DocConverter.isSheet(kind)) {
                    val bytes = contentResolver.openInputStream(u)?.use { DocConverter.readAll(it, DocConverter.MAX_FILE) }
                        ?: throw IOException("Die Datei konnte nicht geöffnet werden.")
                    sh = DocConverter.sheets(kind, bytes)
                    DocConverter.sheetsToText(sh!!)
                } else if (converted) convertDoc(u, kind) else readText(u)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { t ->
                    loading = true
                    edit.setText(t)
                    edit.isEnabled = true
                    loading = false
                    dirty = false
                    updateTitle()
                    sheets = sh
                    if (sh != null) { sheetNo = 0; showTable(true) }
                    if (kind == DocConverter.Kind.DOCX) {
                        converted = !own
                        if (!own) toast("Word-Datei geöffnet. Speichern legt eine neue .docx an, das Original bleibt unverändert. Bilder und Tabellen werden nicht übernommen.")
                    } else if (converted) toast("Nur der Text wurde übernommen. Speichern legt eine neue .txt an, das Original bleibt unverändert.")
                }.onFailure {
                    toast(it.message ?: "Die Datei konnte nicht gelesen werden.")
                    finish()
                }
            }
        }
    }

    private fun convertDoc(u: Uri, kind: DocConverter.Kind): String {
        val bytes = contentResolver.openInputStream(u)?.use { DocConverter.readAll(it, DocConverter.MAX_FILE) }
            ?: throw IOException("Die Datei konnte nicht geöffnet werden.")
        return DocConverter.convert(kind, bytes)
    }

    private fun readText(u: Uri): String {
        val out = ByteArrayOutputStream()
        contentResolver.openInputStream(u)?.use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) throw IOException("Die Datei ist größer als 3 MB und wird hier nicht geöffnet.")
            }
        } ?: throw IOException("Die Datei konnte nicht geöffnet werden.")
        var bytes = out.toByteArray()
        // UTF-8-BOM entfernen
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes = bytes.copyOfRange(3, bytes.size)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)   // ältere Dateien (Windows-Zeichensatz)
        }
    }

    private fun queryName(u: Uri): String? = try {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ Speichern

    private fun save(quiet: Boolean = false) {
        if (loading) return
        if (pdfNote) {
            savePdfNote()
            if (!quiet) toast("Notiz gespeichert.")
            return
        }
        val text = edit.text.toString()
        if (ownNote && uri == null && text.isEmpty()) return
        if (converted) {
            if (!quiet) { if (docx) saveDocx.launch(name) else saveAs.launch(name) }
            return
        }
        var target = uri
        if (target == null) {
            target = createNoteFile()
            if (target == null) {
                toast("Die Notiz konnte nicht angelegt werden.")
                return
            }
            uri = target
        }
        if (writeTo(target)) {
            dirty = false
            updateTitle()
            if (!quiet) toast(if (ownNote) "Gespeichert in „Downloads/${Signer.FOLDER}“." else "Gespeichert.")
        } else if (!ownNote) {
            // Original nicht beschreibbar: als neue Datei speichern lassen
            toast("Diese Datei lässt sich nicht überschreiben. Bitte „Speichern unter“ wählen.")
            if (docx) saveDocx.launch(name) else saveAs.launch(name)
        }
    }

    private fun writeTo(u: Uri, asDocx: Boolean = docx): Boolean = try {
        val bytes = if (asDocx) DocxWriter.write(extractParas(edit.text)) else edit.text.toString().toByteArray(Charsets.UTF_8)
        contentResolver.openOutputStream(u, "wt")?.use { it.write(bytes) } ?: throw IOException()
        true
    } catch (e: Exception) {
        false
    }

    private fun createNoteFile(): Uri? = try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + Signer.FOLDER)
        }
        contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ Bedienung

    private fun onBack() {
        if (loading) {
            finish()
            return
        }
        if (pdfNote) {
            if (dirty) savePdfNote()
            finish()
            return
        }
        if (!dirty) {
            finish()
            return
        }
        if (ownNote) {
            if (edit.text.isEmpty()) {
                // leere Notiz: Datei wieder entfernen
                try {
                    uri?.let { contentResolver.delete(it, null, null) }
                } catch (ignored: Exception) {
                }
            } else {
                save(quiet = true)
            }
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Änderungen speichern?")
            .setMessage(name)
            .setPositiveButton("Speichern") { _, _ ->
                save()
                if (!dirty) finish()
            }
            .setNegativeButton("Verwerfen") { _, _ -> finish() }
            .setNeutralButton("Weiter bearbeiten", null)
            .show()
    }

    private fun openMenu() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("Suchen …" to { searchDialog() })
        items.add("Weitersuchen" to { findNext() })
        items.add("Schrift größer" to { setSize(sizeSp + 2f) })
        items.add("Schrift kleiner" to { setSize(sizeSp - 2f) })
        items.add((if (dark) "Heller Hintergrund" else "Dunkler Hintergrund") to {
            dark = !dark; prefs.edit().putBoolean("text_dunkel", dark).apply(); applyLook()
        })
        items.add("Vorlesetempo …" to { tempoDialog() })
        if (pdfNote) items.add("Seitenverweis einfügen (S. ${pdfNotePage + 1})" to { insertAtCursor("S. ${pdfNotePage + 1}: ") })
        items.add("Alles markieren und kopieren" to { copyAll() })
        items.add("Als Text speichern unter …" to { saveAs.launch(baseName() + ".txt") })
        items.add("Als Word-Datei (.docx) speichern …" to { saveDocx.launch(baseName() + ".docx") })
        items.add("Als PDF speichern …" to { savePdf.launch(baseName() + ".pdf") })
        items.add("Teilen / Senden an …" to { share() })
        if (sheets != null) items.add((if (tableMode) "Als Text anzeigen" else "Als Tabelle anzeigen") to { showTable(!tableMode) })
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun baseName() = name.substringBeforeLast('.', name).replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private fun insertAtCursor(t: String) {
        val pos = edit.selectionStart.coerceAtLeast(0)
        edit.text.insert(pos, t)
    }

    // ------------------------------------------------------------------ Formatieren

    private fun markDirty() {
        if (!fmtHintShown && !docx && !pdfNote) {
            fmtHintShown = true
            toast("Fett, kursiv, unterstrichen und Überschriften bleiben nur in einer Word-Datei oder im PDF erhalten (Menü ⋮).")
        }
        if (!dirty) { dirty = true; updateTitle() }
    }

    private fun toggleStyle(style: Int) {
        val e = edit.text
        val a = edit.selectionStart
        val b = edit.selectionEnd
        if (a < 0 || a == b) return toast("Bitte zuerst Text markieren.")
        val start = minOf(a, b)
        val end = maxOf(a, b)
        val spans = e.getSpans(start, end, StyleSpan::class.java).filter { it.style == style }
        val covered = spans.any { e.getSpanStart(it) <= start && e.getSpanEnd(it) >= end }
        for (sp in spans) {
            val s0 = e.getSpanStart(sp)
            val e0 = e.getSpanEnd(sp)
            e.removeSpan(sp)
            if (s0 < start) e.setSpan(StyleSpan(style), s0, start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (e0 > end) e.setSpan(StyleSpan(style), end, e0, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (!covered) {
            e.setSpan(StyleSpan(style), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        markDirty()
    }

    /** Unterstreichen (true) oder Durchstreichen (false) für die Markierung ein- oder ausschalten. */
    private fun toggleDeco(underline: Boolean) {
        val e = edit.text
        val a = edit.selectionStart
        val b = edit.selectionEnd
        if (a < 0 || a == b) return toast("Bitte zuerst Text markieren.")
        val start = minOf(a, b)
        val end = maxOf(a, b)
        fun make(): Any = if (underline) UnderlineSpan() else StrikethroughSpan()
        val spans: List<Any> = if (underline) e.getSpans(start, end, UnderlineSpan::class.java).toList()
            else e.getSpans(start, end, StrikethroughSpan::class.java).toList()
        val covered = spans.any { e.getSpanStart(it) <= start && e.getSpanEnd(it) >= end }
        for (sp in spans) {
            val s0 = e.getSpanStart(sp)
            val e0 = e.getSpanEnd(sp)
            e.removeSpan(sp)
            if (s0 < start) e.setSpan(make(), s0, start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (e0 > end) e.setSpan(make(), end, e0, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (!covered) e.setSpan(make(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        markDirty()
    }

    private fun paraRange(): IntArray {
        val t = edit.text
        val a = minOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
        val b = maxOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
        val s = t.lastIndexOf('\n', (a - 1).coerceAtLeast(0)).let { if (a == 0 || it < 0 || it >= a) 0 else it + 1 }
        val e = t.indexOf('\n', b).let { if (it < 0) t.length else it }
        return intArrayOf(s, e)
    }

    private fun paragraphs(): List<IntArray> {
        val r = paraRange()
        val t = edit.text.toString()
        val out = ArrayList<IntArray>()
        var s = r[0]
        while (s <= r[1]) {
            val e = t.indexOf('\n', s).let { if (it < 0 || it > r[1]) r[1] else it }
            out.add(intArrayOf(s, e))
            if (e >= r[1]) break
            s = e + 1
        }
        return out
    }

    private fun cycleHeading() {
        val e = edit.text
        for (p in paragraphs()) {
            var s = p[0]
            val en = p[1]
            if (en <= s) continue
            if (e.hasAt("• ", s)) s += 2
            if (en <= s) continue
            val sizes = e.getSpans(s, en, RelativeSizeSpan::class.java).filter { e.getSpanStart(it) <= s && e.getSpanEnd(it) >= en }
            val cur = when { sizes.any { it.sizeChange >= 1.5f } -> 1; sizes.any { it.sizeChange >= 1.2f } -> 2; else -> 0 }
            for (sp in e.getSpans(s, en, RelativeSizeSpan::class.java)) e.removeSpan(sp)
            if (cur == 0 || cur == 2) {
                // Fett der Überschrift entfernen, bevor neu gesetzt wird
                for (sp in e.getSpans(s, en, StyleSpan::class.java)) if (sp.style == Tf.BOLD) e.removeSpan(sp)
            }
            val next = when (cur) { 0 -> 1; 1 -> 2; else -> 0 }
            if (next > 0) {
                e.setSpan(RelativeSizeSpan(if (next == 1) 1.6f else 1.3f), s, en, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                e.setSpan(StyleSpan(Tf.BOLD), s, en, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        markDirty()
    }

    private fun toggleBullets() {
        val e = edit.text
        val ps = paragraphs()
        val allBullets = ps.all { e.hasAt("• ", it[0]) }
        // von hinten nach vorn, damit die Positionen stimmen
        for (p in ps.reversed()) {
            if (allBullets) e.delete(p[0], p[0] + 2) else if (!e.hasAt("• ", p[0])) e.insert(p[0], "• ")
        }
        markDirty()
    }

    private fun CharSequence.hasAt(prefix: String, at: Int): Boolean =
        at >= 0 && at + prefix.length <= length && subSequence(at, at + prefix.length).toString() == prefix

    private fun buildSpannable(paras: List<RichPara>): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        paras.forEachIndexed { i, p ->
            if (i > 0) sb.append('\n')
            if (p.bullet) sb.append("• ")
            val contentStart = sb.length
            for (r in p.runs) {
                val s = sb.length
                sb.append(r.text)
                val e = sb.length
                if (e > s) {
                    if (r.bold && p.level == 0) sb.setSpan(StyleSpan(Tf.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (r.italic) sb.setSpan(StyleSpan(Tf.ITALIC), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (r.underline) sb.setSpan(UnderlineSpan(), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (r.strike) sb.setSpan(StrikethroughSpan(), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            val end = sb.length
            if (p.level > 0 && end > contentStart) {
                sb.setSpan(RelativeSizeSpan(if (p.level == 1) 1.6f else 1.3f), contentStart, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Tf.BOLD), contentStart, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun extractParas(t: CharSequence): List<RichPara> {
        val text = t.toString()
        val sp = t as? Spanned
        val out = ArrayList<RichPara>()
        var s = 0
        while (true) {
            val nl = text.indexOf('\n', s)
            val e = if (nl < 0) text.length else nl
            var cs = s
            var bullet = false
            if (text.startsWith("• ", s) && e >= s + 2) { bullet = true; cs = s + 2 }
            var level = 0
            val runs = ArrayList<RichRun>()
            if (sp != null && e > cs) {
                for (r in sp.getSpans(cs, e, RelativeSizeSpan::class.java)) {
                    if (sp.getSpanStart(r) <= cs && sp.getSpanEnd(r) >= e) level = if (r.sizeChange >= 1.5f) 1 else 2
                }
                val cuts = java.util.TreeSet<Int>()
                cuts.add(cs); cuts.add(e)
                for (r in sp.getSpans(cs, e, StyleSpan::class.java)) {
                    cuts.add(sp.getSpanStart(r).coerceIn(cs, e)); cuts.add(sp.getSpanEnd(r).coerceIn(cs, e))
                }
                for (r in sp.getSpans(cs, e, UnderlineSpan::class.java)) {
                    cuts.add(sp.getSpanStart(r).coerceIn(cs, e)); cuts.add(sp.getSpanEnd(r).coerceIn(cs, e))
                }
                for (r in sp.getSpans(cs, e, StrikethroughSpan::class.java)) {
                    cuts.add(sp.getSpanStart(r).coerceIn(cs, e)); cuts.add(sp.getSpanEnd(r).coerceIn(cs, e))
                }
                val pts = cuts.toList()
                for (k in 0 until pts.size - 1) {
                    val a = pts[k]
                    val b = pts[k + 1]
                    if (b <= a) continue
                    var bold = false
                    var ital = false
                    for (r in sp.getSpans(a, b, StyleSpan::class.java)) {
                        if (sp.getSpanStart(r) <= a && sp.getSpanEnd(r) >= b) {
                            if (r.style == Tf.BOLD || r.style == Tf.BOLD_ITALIC) bold = true
                            if (r.style == Tf.ITALIC || r.style == Tf.BOLD_ITALIC) ital = true
                        }
                    }
                    if (level > 0) bold = false
                    val und = sp.getSpans(a, b, UnderlineSpan::class.java).any { sp.getSpanStart(it) <= a && sp.getSpanEnd(it) >= b }
                    val str = sp.getSpans(a, b, StrikethroughSpan::class.java).any { sp.getSpanStart(it) <= a && sp.getSpanEnd(it) >= b }
                    runs.add(RichRun(text.substring(a, b), bold, ital, und, str))
                }
            } else if (e > cs) {
                runs.add(RichRun(text.substring(cs, e), false, false))
            }
            out.add(RichPara(level, bullet, runs))
            if (nl < 0) break
            s = nl + 1
        }
        return out
    }

    // ------------------------------------------------------------------ PDF erzeugen

    private fun makePdf(): ByteArray {
        val src = SpannableString(edit.text)
        if (src.isBlank()) throw IOException("leer")
        val pageW = 595
        val pageH = 842
        val margin = 56
        val width = pageW - 2 * margin
        val usable = pageH - 2 * margin
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = 0xFF000000.toInt() }
        val lay = StaticLayout.Builder.obtain(src, 0, src.length, paint, width)
            .setLineSpacing(0f, 1.15f).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        val doc = PdfDocument()
        try {
            var line = 0
            var pageNo = 1
            val n = lay.lineCount
            while (line < n) {
                val top = lay.getLineTop(line)
                var end = line
                while (end < n && lay.getLineBottom(end) - top <= usable) end++
                if (end == line) end = line + 1
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
                val c = page.canvas
                c.save()
                c.clipRect(margin.toFloat(), margin.toFloat(), (margin + width).toFloat(), (margin + (lay.getLineBottom(end - 1) - top)).toFloat())
                c.translate(margin.toFloat(), (margin - top).toFloat())
                lay.draw(c)
                c.restore()
                doc.finishPage(page)
                line = end
                pageNo++
            }
            val out = ByteArrayOutputStream()
            doc.writeTo(out)
            return out.toByteArray()
        } finally {
            doc.close()
        }
    }

    private fun searchDialog() {
        val box = EditText(this).apply {
            hint = "Suchbegriff"
            setText(lastQuery)
            setSingleLine(true)
            setSelectAllOnFocus(true)
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(box)
        }
        AlertDialog.Builder(this)
            .setTitle("Im Text suchen")
            .setView(wrap)
            .setPositiveButton("Suchen") { _, _ ->
                lastQuery = box.text.toString()
                findFrom(0)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun findNext() {
        if (lastQuery.isEmpty()) return searchDialog()
        findFrom(edit.selectionEnd.coerceAtLeast(0))
    }

    private fun findFrom(start: Int) {
        val text = edit.text.toString()
        var i = text.indexOf(lastQuery, start, ignoreCase = true)
        if (i < 0 && start > 0) i = text.indexOf(lastQuery, 0, ignoreCase = true)
        if (i < 0) {
            toast("„$lastQuery“ nicht gefunden.")
            return
        }
        edit.requestFocus()
        edit.setSelection(i, i + lastQuery.length)
    }

    private fun setSize(v: Float) {
        sizeSp = v.coerceIn(10f, 40f)
        prefs.edit().putFloat("text_groesse", sizeSp).apply()
        applyLook()
    }

    // ------------------------------------------------------------------ Tabellenansicht

    private fun buildTableUi() {
        tableBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        sheetTabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(2), dp(8), dp(2)) }
        tableBox.addView(
            android.widget.HorizontalScrollView(this).apply { addView(sheetTabs); isHorizontalScrollBarEnabled = false; setBackgroundColor(TANNE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        tableGrid = android.widget.TableLayout(this)
        btnMore = TextView(this).apply {
            text = "Weitere Zeilen laden"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setTextColor(GOLD)
            visibility = android.view.View.GONE
            setOnClickListener { addRows(500) }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tableGrid)
            addView(btnMore)
        }
        val h = android.widget.HorizontalScrollView(this).apply { addView(inner) }
        val v = ScrollView(this).apply { addView(h, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        tableBox.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun showTable(on: Boolean) {
        val list = sheets
        tableMode = on && list != null
        scroll.visibility = if (tableMode) android.view.View.GONE else android.view.View.VISIBLE
        tableBox.visibility = if (tableMode) android.view.View.VISIBLE else android.view.View.GONE
        fmtBar.visibility = if (tableMode) android.view.View.GONE else android.view.View.VISIBLE
        if (tableMode) renderSheet()
    }

    private fun renderSheet() {
        val list = sheets ?: return
        tableBox.setBackgroundColor(if (dark) TANNE_DUNKEL else 0xFFFFFFFF.toInt())
        sheetTabs.removeAllViews()
        sheetTabs.visibility = if (list.size > 1) android.view.View.VISIBLE else android.view.View.GONE
        list.forEachIndexed { i, sh ->
            sheetTabs.addView(TextView(this).apply {
                text = sh.name
                textSize = 14f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setTextColor(if (i == sheetNo) TANNE_DUNKEL else CREME)
                setBackgroundColor(if (i == sheetNo) GOLD else TANNE)
                setOnClickListener { sheetNo = i; renderSheet() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4) })
        }
        tableGrid.removeAllViews()
        shownRows = 0
        addRows(500)
    }

    private fun addRows(n: Int) {
        val rows = sheets?.getOrNull(sheetNo)?.rows ?: return
        val grid = if (dark) 0xFF3A4A3F.toInt() else 0xFFBBBBBB.toInt()
        val fg = if (dark) CREME else 0xFF111111.toInt()
        val bg = if (dark) TANNE_DUNKEL else 0xFFFFFFFF.toInt()
        val end = minOf(rows.size, shownRows + n)
        for (r in shownRows until end) {
            val tr = android.widget.TableRow(this).apply { setBackgroundColor(grid) }
            for (c in rows[r]) {
                tr.addView(TextView(this).apply {
                    text = c
                    textSize = (sizeSp - 2f).coerceAtLeast(10f)
                    setTextColor(if (r == 0) GOLD else fg)
                    if (r == 0) setTypeface(typeface, Typeface.BOLD)
                    setBackgroundColor(bg)
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    maxWidth = dp(240)
                    minWidth = dp(56)
                    setTextIsSelectable(false)
                }, android.widget.TableRow.LayoutParams().apply { setMargins(dp(1), dp(1), 0, 0) })
            }
            tableGrid.addView(tr)
        }
        shownRows = end
        btnMore.visibility = if (shownRows < rows.size) android.view.View.VISIBLE else android.view.View.GONE
        btnMore.text = "Weitere Zeilen laden (" + (rows.size - shownRows) + " übrig)"
    }

    private fun applyLook() {
        if (::tableBox.isInitialized && tableMode) renderSheet()
        edit.textSize = sizeSp
        edit.setTextColor(if (dark) CREME else 0xFF111111.toInt())
        edit.setHintTextColor(if (dark) 0xFFCFC38E.toInt() else 0xFF777777.toInt())
        edit.setBackgroundColor(if (dark) TANNE_DUNKEL else 0xFFFFFFFF.toInt())
        edit.highlightColor = 0x66D4AF37
    }

    private fun copyAll() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(name, edit.text.toString()))
        toast("Text kopiert.")
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, edit.text.toString())
            putExtra(Intent.EXTRA_SUBJECT, name)
        }
        try {
            startActivity(Intent.createChooser(send, "Senden an"))
        } catch (e: ActivityNotFoundException) {
            toast("Keine App zum Senden gefunden.")
        }
    }

    private fun showKeyboard() {
        edit.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun updateTitle() {
        tvTitle.text = (if (dirty) "● " else "") + name
    }

    private fun iconButton(label: String, desc: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 24f
        setTextColor(GOLD)
        gravity = Gravity.CENTER
        contentDescription = desc
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ Vorlesen

    private fun startReading() {
        if (tableMode) showTable(false)
        if (loading || edit.text.isBlank()) return toast("Es gibt noch nichts zum Vorlesen.")
        if (reading != Read.IDLE) return
        ensureTts {
            val text = edit.text.toString()
            val from = edit.selectionStart.coerceAtLeast(0)
            chunks = buildChunks(text, from)
            if (chunks.isEmpty()) return@ensureTts toast("Es gibt ab hier nichts zum Vorlesen.")
            ci = 0
            gen++
            reading = Read.PLAYING
            takeFocus()
            showReadState()
            speakChunk()
        }
    }

    private fun ensureTts(then: () -> Unit) {
        if (ttsReady) return then()
        onTtsReady = then
        if (tts != null) return
        tts = TextToSpeech(applicationContext) { status ->
            runOnUiThread {
                val t = tts
                if (status != TextToSpeech.SUCCESS || t == null) {
                    toast("Die Sprachausgabe ist nicht verfügbar.")
                    tts = null
                    return@runOnUiThread
                }
                val r = t.setLanguage(Locale.GERMAN)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.language = Locale.getDefault()
                t.setSpeechRate(prefs.getFloat("tts_rate", 1.0f))
                prefs.getString("tts_voice", null)?.let { n ->
                    try {
                        t.voices?.firstOrNull { it.name == n }?.let { v -> t.voice = v }
                    } catch (ignored: Exception) {
                    }
                }
                t.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        val (g, i) = parse(id) ?: return
                        runOnUiThread { if (g == gen && reading == Read.PLAYING) highlight(i) }
                    }

                    override fun onDone(id: String?) {
                        val (g, i) = parse(id) ?: return
                        runOnUiThread {
                            if (g != gen || reading != Read.PLAYING) return@runOnUiThread
                            if (i + 1 < chunks.size) {
                                ci = i + 1
                                speakChunk()
                            } else {
                                stopReading()
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        runOnUiThread { if (reading != Read.IDLE) { toast("Beim Vorlesen ist ein Fehler aufgetreten."); stopReading() } }
                    }
                })
                ttsReady = true
                onTtsReady?.invoke()
                onTtsReady = null
            }
        }
    }

    private fun parse(id: String?): Pair<Int, Int>? {
        if (id == null || !id.startsWith("g")) return null
        val g = id.substring(1).substringBefore("c").toIntOrNull() ?: return null
        val i = id.substringAfter("c").toIntOrNull() ?: return null
        return g to i
    }

    /** Zerlegt den Text ab Position [from] in Sätze und fasst sie zu Stücken von etwa 300 Zeichen zusammen. */
    private fun buildChunks(text: String, from: Int): ArrayList<IntArray> {
        val out = ArrayList<IntArray>()
        val max = TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3000)
        val bi = BreakIterator.getSentenceInstance(Locale.GERMAN)
        bi.setText(text)
        val bounds = ArrayList<Int>()
        var b = bi.first()
        while (b != BreakIterator.DONE) {
            bounds.add(b)
            b = bi.next()
        }
        var curStart = -1
        var curEnd = -1
        fun flush() {
            if (curStart >= 0 && text.substring(curStart, curEnd).isNotBlank()) out.add(intArrayOf(curStart, curEnd))
            curStart = -1
        }
        for (k in 0 until bounds.size - 1) {
            var s = bounds[k]
            val e = bounds[k + 1]
            if (e <= from) continue
            if (s < from) s = from
            // sehr lange Sätze hart teilen
            var p = s
            while (p < e) {
                val q = minOf(e, p + max)
                if (curStart < 0) { curStart = p; curEnd = q } else curEnd = q
                p = q
                if (curEnd - curStart >= 300) flush()
            }
        }
        flush()
        return out
    }

    private fun speakChunk() {
        val t = tts ?: return
        if (ci >= chunks.size) return stopReading()
        val c = chunks[ci]
        val s = edit.text.toString().substring(c[0], c[1])
        t.speak(s, TextToSpeech.QUEUE_FLUSH, null, "g${gen}c$ci")
    }

    private fun highlight(i: Int) {
        if (i >= chunks.size) return
        val c = chunks[i]
        try {
            edit.setSelection(c[0], c[1])
        } catch (ignored: Exception) {
        }
    }

    private fun toggleRead() {
        when (reading) {
            Read.PLAYING -> {
                gen++
                tts?.stop()
                reading = Read.PAUSED
                dropFocus()
                showReadState()
            }
            Read.PAUSED -> {
                gen++
                reading = Read.PLAYING
                takeFocus()
                showReadState()
                speakChunk()
            }
            else -> {}
        }
    }

    private fun stopReading() {
        gen++
        tts?.stop()
        val was = reading
        reading = Read.IDLE
        dropFocus()
        if (was != Read.IDLE || readBar.visibility == android.view.View.VISIBLE) showReadState()
    }

    private fun showReadState() {
        when (reading) {
            Read.PLAYING -> {
                readBar.visibility = android.view.View.VISIBLE
                tvReadStatus.text = "Wird vorgelesen"
                btnPlay.text = "⏸"
                ReadService.onToggle = { toggleRead() }
                ReadService.onStop = { stopReading() }
                ReadService.show(applicationContext, true, "$name wird vorgelesen")
            }
            Read.PAUSED -> {
                readBar.visibility = android.view.View.VISIBLE
                tvReadStatus.text = "Pause"
                btnPlay.text = "▶"
                ReadService.show(applicationContext, false, "Pause – $name")
            }
            Read.IDLE -> {
                readBar.visibility = android.view.View.GONE
                ReadService.onToggle = null
                ReadService.onStop = null
                ReadService.hide(applicationContext)
            }
        }
    }

    private fun tempoDialog() {
        val labels = arrayOf("Langsam (0,75)", "Normal (1,0)", "Etwas schneller (1,25)", "Schnell (1,5)", "Sehr schnell (2,0)")
        val values = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        AlertDialog.Builder(this)
            .setTitle("Vorlesetempo")
            .setItems(labels) { _, which ->
                prefs.edit().putFloat("tts_rate", values[which]).apply()
                tts?.setSpeechRate(values[which])
                if (reading == Read.PLAYING) {
                    gen++
                    speakChunk()
                }
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if ((change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) && reading == Read.PLAYING) {
            runOnUiThread { toggleRead() }
        }
    }

    private fun takeFocus() {
        try {
            val req = focusReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(focusListener)
                .build().also { focusReq = it }
            audio.requestAudioFocus(req)
        } catch (ignored: Exception) {
        }
    }

    private fun dropFocus() {
        try {
            focusReq?.let { audio.abandonAudioFocusRequest(it) }
        } catch (ignored: Exception) {
        }
    }

    // ------------------------------------------------------------------ Lebenszyklus

    override fun onPause() {
        // Eigene Notizen sichern, damit nichts verloren geht; fremde Dateien nur auf ausdrücklichen Wunsch.
        if (ownNote && dirty && !loading && edit.text.isNotEmpty()) save(quiet = true)
        if (pdfNote && dirty && !loading) savePdfNote()
        adManager.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        adManager.resume()
    }

    override fun onDestroy() {
        stopReading()
        tts?.shutdown()
        tts = null
        adManager.destroy()
        worker.shutdown()
        super.onDestroy()
    }
}
