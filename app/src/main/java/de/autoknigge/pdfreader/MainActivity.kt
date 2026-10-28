package de.autoknigge.pdfreader

import android.content.ActivityNotFoundException
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
    private lateinit var tvReadStatus: TextView
    private lateinit var btnPlayPause: TextView
    private var speaker: Speaker? = null
    private var srcFile: File? = null
    private var srcPages = 0

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (ignored: SecurityException) {
            }
            open(uri)
        }
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
        btnPlayPause.setOnClickListener { togglePause() }
        findViewById<View>(R.id.btnStopRead).setOnClickListener { speaker?.stop() }

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageLabel()
                speaker?.moveTo(position)
            }
        })
        tvPage.setOnClickListener { jumpToPage() }

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

        adManager = AdManager(this, findViewById<FrameLayout>(R.id.adContainer), findViewById(R.id.tvAdSettings))
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
            currentTitle = ""
            pager.visibility = View.GONE
            tvPage.visibility = View.GONE
            findViewById<View>(R.id.btnSpeak).visibility = View.GONE
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

    private fun show(src: PdfSource) {
        releaseSpeaker()
        adapter?.shutdown()
        val width = (resources.displayMetrics.widthPixels * 2).coerceAtMost(2200)
        val a = PageAdapter(src.file, width)
        adapter = a
        pager.adapter = a
        srcFile = src.file
        srcPages = src.pages
        empty.visibility = View.GONE
        pager.visibility = View.VISIBLE
        findViewById<View>(R.id.btnSpeak).visibility = View.VISIBLE
        tvPage.visibility = if (src.pages > 1) View.VISIBLE else View.GONE
        currentTitle = src.name
        tvTitle.text = src.name
        updatePageLabel()
    }

    // ---------- Vorlesen ----------

    private fun startSpeaking() {
        val file = srcFile ?: return
        if (speaker == null) {
            speaker = Speaker(this, file, srcPages,
                onState = { playing, page -> runOnUiThread { onSpeakState(playing, page) } },
                onInfo = { msg -> runOnUiThread { toast(msg) } })
        }
        readBar.visibility = View.VISIBLE
        speaker?.start(pager.currentItem)
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
