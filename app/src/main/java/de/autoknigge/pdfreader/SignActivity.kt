package de.autoknigge.pdfreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import java.io.File

/** Formulare unterschreiben: Unterschrift zeichnen oder fotografieren, auf die Seite schieben, als neue PDF speichern. */
class SignActivity : AppCompatActivity(), SignHost {

    companion object {
        const val EXTRA_FILE = "datei"
        const val EXTRA_NAME = "name"
    }

    private lateinit var ctl: SignController
    private lateinit var adManager: AdManager
    private var srcFile: File? = null
    private var srcName = ""
    private var renderer: PageRenderer? = null

    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) loadPhoto(uri) }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override val canPick = false
    override fun pickFile() {}
    override fun pickPhoto() = photoPicker.launch("image/*")

    override fun render(index: Int): Bitmap = renderer!!.render(index, 1400)

    override fun sign(placements: List<Placement>, progress: (Int) -> Unit): SignResult {
        val r = Signer.sign(this, srcFile!!, srcName, placements, progress)
        return SignResult(r.uri, r.name)
    }

    override fun showDone(result: SignResult) {
        AlertDialog.Builder(this)
            .setTitle("PDF unterschrieben")
            .setMessage("Gespeichert in „Downloads/${Signer.FOLDER}“:\n${result.name}")
            .setPositiveButton("Im Reader öffnen") { _, _ ->
                setResult(RESULT_OK, android.content.Intent().setData(result.uri))
                finish()
            }
            .setNeutralButton("Teilen") { _, _ ->
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(android.content.Intent.EXTRA_STREAM, result.uri)
                    clipData = android.content.ClipData.newRawUri("", result.uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(android.content.Intent.createChooser(send, "Senden an"))
                } catch (e: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(this, "Keine App zum Senden gefunden.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

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
            setPadding(dp(6), 0, dp(14), 0)
        }
        top.addView(TextView(this).apply {
            text = "‹"; textSize = 34f; setTextColor(0xFFD4AF37.toInt()); gravity = Gravity.CENTER
            contentDescription = "Zurück"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(TextView(this).apply {
            text = "PDF unterschreiben"; textSize = 22f; setTextColor(0xFFD4AF37.toInt()); setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        ctl = SignController(this, this)
        root.addView(ctl.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val adBox = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_ad)
            minimumHeight = dp(60)
        }
        root.addView(adBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        setContentView(root)
        adManager = AdManager(this, adBox, null)
        Intro.run(this) { adManager.start() }

        val path = intent.getStringExtra(EXTRA_FILE)
        srcName = intent.getStringExtra(EXTRA_NAME) ?: "Dokument.pdf"
        if (path == null || !File(path).exists()) {
            android.widget.Toast.makeText(this, "Die PDF ist nicht mehr verfügbar.", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        srcFile = File(path)
        ctl.setStatus(srcName)
        ctl.worker.execute {
            val r = runCatching { PageRenderer(srcFile!!) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                r.onSuccess {
                    renderer = it
                    ctl.fileReady(srcName, it.pageCount)
                }.onFailure {
                    android.widget.Toast.makeText(this, it.message ?: "Die PDF lässt sich nicht darstellen.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadPhoto(uri: Uri) {
        ctl.worker.execute {
            val bmp = runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / s > 2400) s *= 2
                val o2 = BitmapFactory.Options().apply { inSampleSize = s }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            }.getOrNull()
            runOnUiThread { if (!isFinishing && !isDestroyed) ctl.onPhoto(bmp) }
        }
    }

    override fun onPause() {
        adManager.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        adManager.resume()
    }

    override fun onDestroy() {
        adManager.destroy()
        val r = renderer
        renderer = null
        ctl.worker.execute { runCatching { r?.close() } }
        ctl.shutdown()
        super.onDestroy()
    }
}
