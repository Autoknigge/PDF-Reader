package de.autoknigge.pdfreader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.viewpager2.widget.ViewPager2
import java.io.File
import java.util.concurrent.Executors

/** Einfacher PDF-Betrachter: öffnen, blättern, zoomen. */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val URL_HOME = "https://autoknigge.de"
        private const val URL_IMPRINT = "https://autoknigge.de/impressum"
        private const val URL_PRIVACY = "https://autoknigge.de/pdfreader/datenschutz.html"
    }

    private lateinit var adManager: AdManager
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var pager: ViewPager2
    private lateinit var empty: View
    private lateinit var tvTitle: TextView
    private lateinit var tvPage: TextView
    private var adapter: PageAdapter? = null
    private var loading = false
    private var currentTitle = ""

    private lateinit var readBar: View
    private lateinit var btnTheme: TextView
    private var viewMode = 0
    private lateinit var tvReadStatus: TextView
    private lateinit var btnPlayPause: TextView
    private var speaker: Speaker? = null
    private var srcFile: File? = null
    private var srcPages = 0
    private var currentSrc: PdfSource? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (ignored: SecurityException) {
            }
            open(uri)
        }
    }

    private val saveAs = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val src = currentSrc
        if (uri != null && src != null) saveCopy(src, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_reader)
        Ui.insets(findViewById(R.id.root))

        pager = findViewById(R.id.pager)
        empty = findViewById(R.id.empty)
        tvTitle = findViewById(R.id.tvTitle)
        tvPage = findViewById(R.id.tvPage)

        readBar = findViewById(R.id.readBar)
        tvReadStatus = findViewById(R.id.tvReadStatus)
        btnPlayPause = findViewById(R.id.btnPlayPause)

        findViewById<View>(R.id.btnBack).setOnClickListener { goBack() }
        findViewById<View>(R.id.btnOpen).setOnClickListener { pick() }
        findViewById<View>(R.id.btnOpenBig).setOnClickListener { pick() }
        findViewById<View>(R.id.btnSpeak).setOnClickListener { startSpeaking() }
        findViewById<View>(R.id.btnReadSettings).setOnClickListener { openReadSettings() }
        findViewById<View>(R.id.btnMore).setOnClickListener { openMoreMenu() }
        btnPlayPause.setOnClickListener { togglePause() }
        findViewById<View>(R.id.btnStopRead).setOnClickListener { speaker?.stop() }

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageLabel()
                speaker?.moveTo(position)
            }
        })
        tvPage.setOnClickListener { jumpToPage() }
        btnTheme = findViewById(R.id.btnTheme)
        btnTheme.setOnClickListener { setViewMode((viewMode + 1) % 3) }

        mapOf(
            R.id.tvAbout to URL_HOME,
            R.id.tvPrivacy to URL_PRIVACY,
            R.id.tvImprint to URL_IMPRINT
        ).forEach { (id, url) ->
            findViewById<TextView>(id).apply {
                Ui.underline(this)
                setOnClickListener { Ui.openUrl(this@MainActivity, url) }
            }
        }
        Ui.underline(findViewById(R.id.tvAdSettings))
        val versionParts = (packageManager.getPackageInfo(packageName, 0).versionName ?: "").split(".")
        findViewById<TextView>(R.id.tvVersion).text = versionParts.take(2).joinToString(".")

        adManager = AdManager(this, findViewById<FrameLayout>(R.id.adContainer), findViewById(R.id.tvAdSettings), true)
        Intro.run(this) {
            adManager.start()
            if (savedInstanceState == null) handleIncoming(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) open(uri)
    }

    private fun pick() {
        if (!loading) picker.launch(arrayOf("application/pdf"))
    }

    /** Zurück: zuerst vom geöffneten Dokument zum Startbildschirm, dann die App schließen. */
    private fun goBack() {
        if (pager.visibility == View.VISIBLE) {
            releaseSpeaker()
            pager.adapter = null
            adapter?.shutdown()
            adapter = null
            srcFile = null
            srcPages = 0
            currentSrc = null
            findViewById<View>(R.id.btnMore).visibility = View.GONE
            currentTitle = ""
            pager.visibility = View.GONE
            tvPage.visibility = View.GONE
            btnTheme.visibility = View.GONE
            findViewById<View>(R.id.btnSpeak).visibility = View.GONE
            findViewById<View>(R.id.btnReadSettings).visibility = View.GONE
            empty.visibility = View.VISIBLE
            tvTitle.text = getString(R.string.app_name)
        } else {
            finish()
        }
    }

    override fun onBackPressed() {
        if (pager.visibility == View.VISIBLE) goBack() else super.onBackPressed()
    }

    private fun open(uri: Uri, password: String? = null) {
        if (loading) return
        loading = true
        tvTitle.text = "Wird geöffnet …"
        worker.execute {
            val r = runCatching { Docs.prepare(this, uri, password) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading = false
                r.onSuccess { show(it) }.onFailure { e ->
                    if (e is PasswordNeeded) {
                        askPassword(e.name, e.wrong) { pw ->
                            if (pw == null) resetTitle() else open(uri, pw)
                        }
                    } else {
                        resetTitle()
                        toast(e.message ?: "Die PDF konnte nicht geöffnet werden.")
                    }
                }
            }
        }
    }

    private fun setViewMode(mode: Int) {
        viewMode = mode
        getSharedPreferences("pdfreader", MODE_PRIVATE).edit().putInt("view_mode", mode).apply()
        adapter?.mode = mode
        applyViewMode()
    }

    private fun applyViewMode() {
        btnTheme.text = when (viewMode) {
            1 -> "🌙  Dunkel"
            2 -> "📖  Lesemodus (augenschonend)"
            else -> "☀  Hell"
        }
        pager.setBackgroundColor(
            when (viewMode) {
                1 -> 0xFF0B0B0B.toInt()
                2 -> 0xFF3A3024.toInt()
                else -> 0x00000000
            }
        )
    }

    private fun show(src: PdfSource) {
        releaseSpeaker()
        adapter?.shutdown()
        val width = (resources.displayMetrics.widthPixels * 2).coerceAtMost(2200)
        val a = PageAdapter(src.file, width)
        viewMode = getSharedPreferences("pdfreader", MODE_PRIVATE).getInt("view_mode", 0)
        a.mode = viewMode
        a.onLongPress = { page, fx, fy -> onPageLongPress(page, fx, fy) }
        adapter = a
        pager.adapter = a
        srcFile = src.file
        srcPages = src.pages
        currentSrc = src
        findViewById<View>(R.id.btnMore).visibility = View.VISIBLE
        empty.visibility = View.GONE
        pager.visibility = View.VISIBLE
        findViewById<View>(R.id.btnSpeak).visibility = View.VISIBLE
        findViewById<View>(R.id.btnReadSettings).visibility = View.VISIBLE
        ensureSpeaker()
        tvPage.visibility = if (src.pages > 1) View.VISIBLE else View.GONE
        btnTheme.visibility = View.VISIBLE
        applyViewMode()
        currentTitle = src.name
        tvTitle.text = src.name
        updatePageLabel()
    }

    /** Auswahlfenster mit Symbol und größerer Schrift; [checked] markiert den aktuellen Eintrag mit ✓. */
    private fun menuDialog(
        title: String,
        items: List<Pair<String, String>>,
        closeLabel: String,
        checked: Int = -1,
        keepOpen: Boolean = false,
        onDismiss: (() -> Unit)? = null,
        onPick: (Int) -> Unit
    ) {
        val d = resources.displayMetrics.density
        var sel = checked
        lateinit var dialog: AlertDialog
        val list = android.widget.ListView(this).apply {
            divider = null
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
        }
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(i: Int) = items[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, parent: android.view.ViewGroup): View {
                val row = android.widget.LinearLayout(this@MainActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = (56 * d).toInt()
                    setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
                    if (i == sel) setBackgroundColor(0x33D4AF37)
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = items[i].first
                    textSize = 24f
                    gravity = android.view.Gravity.CENTER
                    layoutParams = android.widget.LinearLayout.LayoutParams((40 * d).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                row.addView(TextView(this@MainActivity).apply {
                    text = items[i].second
                    textSize = 19f
                    setTextColor(getColor(R.color.text_hell))
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (i == sel) row.addView(TextView(this@MainActivity).apply {
                    text = "✓"
                    textSize = 22f
                    setTextColor(getColor(R.color.gold))
                })
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, which, _ ->
            onPick(which)
            if (keepOpen) {
                sel = which
                adapter.notifyDataSetChanged()
            } else {
                dialog.setOnDismissListener(null)
                dialog.dismiss()
                onDismiss?.invoke()
            }
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.gold))
            setPadding((20 * d).toInt(), (18 * d).toInt(), (20 * d).toInt(), (6 * d).toInt())
        }
        dialog = AlertDialog.Builder(this)
            .setCustomTitle(titleView)
            .setView(list)
            .setNegativeButton(closeLabel, null)
            .setOnDismissListener { onDismiss?.invoke() }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.textSize = 17f
    }

    // ---------- Senden und Speichern ----------

    private fun openMoreMenu() {
        val src = currentSrc ?: return
        menuDialog(
            src.name,
            listOf("📤" to "Senden an …", "💾" to "Speichern unter …", "🖨" to "Drucken"),
            "Schließen"
        ) { which ->
            when (which) {
                0 -> shareCopy(src)
                1 -> saveAs.launch(Docs.fileNameFor(src.name))
                else -> printPdf(src)
            }
        }
    }

    /** Druckt über den Android-Druckdienst (Drucker im WLAN, Mopria, Hersteller-Plugins). */
    private fun printPdf(src: PdfSource) {
        val pm = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
        val file = src.file
        val adapter = object : android.print.PrintDocumentAdapter() {
            override fun onLayout(
                old: android.print.PrintAttributes?, new: android.print.PrintAttributes?,
                cancel: android.os.CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?
            ) {
                if (cancel?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = android.print.PrintDocumentInfo.Builder(Docs.fileNameFor(src.name))
                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(src.pages)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out android.print.PageRange>?, dest: android.os.ParcelFileDescriptor,
                cancel: android.os.CancellationSignal?, callback: WriteResultCallback
            ) {
                try {
                    file.inputStream().use { i ->
                        java.io.FileOutputStream(dest.fileDescriptor).use { o -> i.copyTo(o) }
                    }
                    callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }
        pm.print(Docs.fileNameFor(src.name), adapter, null)
    }

    /** Gibt die PDF (bei Passwortschutz: die geschützte Originaldatei) an eine App nach Wahl weiter. */
    private fun shareCopy(src: PdfSource) {
        worker.execute {
            val r = runCatching {
                val dir = File(cacheDir, "lesen/teilen").apply { mkdirs() }
                val f = File(dir, Docs.fileNameFor(src.name))
                src.original.copyTo(f, overwrite = true)
                FileProvider.getUriForFile(this, "$packageName.files", f)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                r.onSuccess { uri ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        startActivity(Intent.createChooser(send, "Senden an"))
                    } catch (e: ActivityNotFoundException) {
                        toast("Keine App zum Senden gefunden.")
                    }
                }.onFailure { toast("Die PDF konnte nicht weitergegeben werden.") }
            }
        }
    }

    /** Schreibt eine Kopie der PDF an den Ort, den du im Systemdialog gewählt hast. */
    private fun saveCopy(src: PdfSource, target: Uri) {
        worker.execute {
            val r = runCatching {
                val out = contentResolver.openOutputStream(target)
                    ?: throw java.io.IOException("Zieldatei nicht beschreibbar")
                out.use { o -> src.original.inputStream().use { it.copyTo(o, 64 * 1024) } }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                r.onSuccess { toast("PDF gespeichert.") }
                    .onFailure { toast("Die PDF konnte nicht gespeichert werden.") }
            }
        }
    }

    // ---------- Vorlesen ----------

    private fun ensureSpeaker(): Speaker? {
        val file = srcFile ?: return null
        if (speaker == null) {
            speaker = Speaker(this, file, srcPages,
                onState = { playing, page -> runOnUiThread { onSpeakState(playing, page) } },
                onInfo = { msg -> runOnUiThread { toast(msg) } },
                onHighlight = { page, boxes -> runOnUiThread { adapter?.setHighlight(page, boxes) } })
        }
        return speaker
    }

    private fun startSpeaking() {
        val s = ensureSpeaker() ?: return
        readBar.visibility = View.VISIBLE
        s.start(pager.currentItem)
    }

    private fun openReadSettings() {
        val s = ensureSpeaker() ?: return
        menuDialog("🔊  Vorlesen", listOf("⏩" to "Geschwindigkeit", "🗣" to "Stimme"), "Schließen") { which ->
            if (which == 0) chooseSpeed(s) else chooseVoice(s)
        }
    }

    private fun chooseSpeed(s: Speaker) {
        val labels = arrayOf("Langsam", "Etwas langsamer", "Normal", "Schneller", "Schnell", "Sehr schnell")
        val rates = floatArrayOf(0.7f, 0.85f, 1.0f, 1.25f, 1.5f, 1.8f)
        val current = rates.indices.minByOrNull { kotlin.math.abs(rates[it] - s.rate) } ?: 2
        val icons = arrayOf("🐢", "🚶", "👌", "🏃", "⚡", "🚀")
        menuDialog("⏩  Geschwindigkeit", labels.indices.map { icons[it] to labels[it] }, "Abbrechen", current) { which ->
            s.setRate(rates[which])
        }
    }

    private fun chooseVoice(s: Speaker) {
        if (!s.isReady()) {
            toast("Sprachausgabe wird noch geladen – bitte gleich noch einmal.")
            return
        }
        val voices = s.germanVoices()
        if (voices.isEmpty()) {
            toast("Keine deutsche Stimme gefunden. Du kannst in den Android-Einstellungen unter „Sprachausgabe“ eine installieren.")
            return
        }
        // Während der Auswahl nicht weiterlesen, damit man die Proben hört.
        val wasPlaying = s.state == Speaker.State.PLAYING
        if (wasPlaying) s.pause()
        val labels = voices.mapIndexed { i, v ->
            val quality = when {
                v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> " · sehr gut"
                v.quality >= android.speech.tts.Voice.QUALITY_HIGH -> " · gut"
                else -> ""
            }
            val land = when (v.locale.country) {
                "AT" -> " (Österreich)"
                "CH" -> " (Schweiz)"
                else -> ""
            }
            "Stimme ${i + 1}$quality$land"
        }.toTypedArray()
        val current = voices.indexOfFirst { it.name == s.currentVoiceName() }
        menuDialog(
            "🗣  Stimme – antippen zum Anhören", labels.map { "🔈" to it }, "Fertig", current, keepOpen = true,
            onDismiss = { if (wasPlaying) s.resume() }
        ) { which ->
            s.setVoice(voices[which].name)
            s.preview()
        }
    }

    /** Lang auf eine Seitenstelle gedrückt: erkannten Absatz markieren und Auswahl anbieten. */
    private fun onPageLongPress(page: Int, fx: Float, fy: Float) {
        val s = ensureSpeaker() ?: return
        s.locate(page, fx, fy) { loc ->
            if (loc == null) {
                toast("Hier wurde kein Text erkannt. Bei Bildern oder Scans ist kein Vorlesen möglich.")
                return@locate
            }
            adapter?.setHighlight(page, loc.boxes)
            menuDialog(
                "🔊  Vorlesen",
                listOf("▶️" to "Ab hier vorlesen", "📝" to "Nur diesen Absatz vorlesen", "📄" to "Nur diese Seite vorlesen"),
                "Abbrechen",
                onDismiss = {
                    // Auswahl ohne Vorlesen: Markierung wieder entfernen.
                    if (speaker?.state != Speaker.State.PLAYING) adapter?.setHighlight(-1, null)
                }
            ) { which ->
                readBar.visibility = View.VISIBLE
                when (which) {
                    0 -> s.startFrom(page, loc.block, Speaker.Scope.DOCUMENT, Int.MAX_VALUE)
                    1 -> s.startFrom(page, loc.paraFirst, Speaker.Scope.PARAGRAPH, loc.paraLast)
                    else -> s.startFrom(page, 0, Speaker.Scope.PAGE, Int.MAX_VALUE)
                }
            }
        }
    }

    private fun togglePause() {
        val s = speaker ?: return
        if (s.state == Speaker.State.PLAYING) s.pause() else s.resume()
    }

    private fun onSpeakState(playing: Boolean, page: Int) {
        val s = speaker
        if (playing && page != pager.currentItem) pager.setCurrentItem(page, false)
        btnPlayPause.text = if (playing) "⏸" else "▶"
        btnPlayPause.contentDescription = if (playing) "Pause" else "Weiter"
        when (s?.state) {
            Speaker.State.PLAYING -> tvReadStatus.text = "Seite ${page + 1} wird vorgelesen"
            Speaker.State.PAUSED -> tvReadStatus.text = "Pause – Seite ${page + 1}"
            else -> readBar.visibility = View.GONE
        }
    }

    private fun releaseSpeaker() {
        speaker?.release()
        speaker = null
        readBar.visibility = View.GONE
    }

    private fun updatePageLabel() {
        val total = adapter?.itemCount ?: 0
        if (total > 0) tvPage.text = "${pager.currentItem + 1} / $total"
    }

    private fun jumpToPage() {
        val total = adapter?.itemCount ?: return
        if (total <= 1) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 – $total"
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Zu Seite springen")
            .setView(box)
            .setPositiveButton("Los") { _, _ ->
                val n = input.text.toString().toIntOrNull()
                if (n != null && n in 1..total) pager.setCurrentItem(n - 1, false)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun askPassword(name: String, wrong: Boolean, onResult: (String?) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Passwort"
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Passwort eingeben")
            .setMessage(if (wrong) "Das Passwort stimmt nicht. Bitte noch einmal versuchen." else "„$name“ ist mit einem Passwort geschützt.")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Öffnen") { _, _ -> onResult(input.text.toString()) }
            .setNegativeButton("Abbrechen") { _, _ -> onResult(null) }
            .show()
    }

    private fun resetTitle() {
        tvTitle.text = if (currentTitle.isBlank()) getString(R.string.app_name) else currentTitle
    }

    private fun toast(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    override fun onPause() {
        speaker?.pause()
        adManager.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        adManager.resume()
        val ctx: Context = applicationContext
        worker.execute { Docs.cleanup(ctx) }
    }

    override fun onDestroy() {
        releaseSpeaker()
        adManager.destroy()
        adapter?.shutdown()
        worker.shutdown()
        super.onDestroy()
    }
}
