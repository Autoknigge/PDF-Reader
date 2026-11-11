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
import android.text.Editable
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
        if (u != null) {
            if (writeTo(u)) {
                uri = u
                name = queryName(u) ?: name
                ownNote = false
                dirty = false
                updateTitle()
                toast("Gespeichert.")
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
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(edit, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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

    private fun openFile(u: Uri) {
        uri = u
        name = queryName(u) ?: (u.lastPathSegment?.substringAfterLast('/') ?: "Text.txt")
        updateTitle()
        worker.execute {
            val result = runCatching { readText(u) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { t ->
                    loading = true
                    edit.setText(t)
                    edit.isEnabled = true
                    loading = false
                    dirty = false
                    updateTitle()
                }.onFailure {
                    toast(it.message ?: "Die Datei konnte nicht gelesen werden.")
                    finish()
                }
            }
        }
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
        val text = edit.text.toString()
        if (ownNote && uri == null && text.isEmpty()) return
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
            saveAs.launch(name)
        }
    }

    private fun writeTo(u: Uri): Boolean = try {
        contentResolver.openOutputStream(u, "wt")?.use { it.write(edit.text.toString().toByteArray(Charsets.UTF_8)) } ?: throw IOException()
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
        val items = arrayOf(
            "Suchen …",
            "Weitersuchen",
            "Schrift größer",
            "Schrift kleiner",
            if (dark) "Heller Hintergrund" else "Dunkler Hintergrund",
            "Vorlesetempo …",
            "Alles markieren und kopieren",
            "Speichern unter …",
            "Teilen / Senden an …"
        )
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> searchDialog()
                    1 -> findNext()
                    2 -> setSize(sizeSp + 2f)
                    3 -> setSize(sizeSp - 2f)
                    4 -> { dark = !dark; prefs.edit().putBoolean("text_dunkel", dark).apply(); applyLook() }
                    5 -> tempoDialog()
                    6 -> copyAll()
                    7 -> saveAs.launch(name)
                    8 -> share()
                }
            }
            .setNegativeButton("Schließen", null)
            .show()
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

    private fun applyLook() {
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
