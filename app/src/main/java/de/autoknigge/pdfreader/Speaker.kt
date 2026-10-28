package de.autoknigge.pdfreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Liest den Text einer PDF vor (Android-Sprachausgabe). Den Text holt PdfBox Seite für Seite;
 * eingescannte Seiten ohne Textschicht werden übersprungen.
 */
class Speaker(
    private val context: Context,
    private val file: File,
    private val pageCount: Int,
    private val onState: (playing: Boolean, page: Int) -> Unit,
    private val onInfo: (String) -> Unit
) {
    enum class State { IDLE, PLAYING, PAUSED }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var doc: PDDocument? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var startRequestedPage = -1

    var state = State.IDLE
        private set
    private var page = 0

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val r = tts?.setLanguage(Locale.GERMAN)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.getDefault()
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onError(id: String?) {}
                    override fun onDone(id: String?) {
                        if (id != null && id.endsWith("_last")) main.post { nextPage() }
                    }
                })
                if (startRequestedPage >= 0) {
                    val p = startRequestedPage; startRequestedPage = -1
                    main.post { start(p) }
                }
            } else {
                main.post { onInfo("Sprachausgabe ist auf diesem Gerät nicht verfügbar.") }
            }
        }
    }

    /** Beginnt ab [fromPage] (0-basiert) vorzulesen. */
    fun start(fromPage: Int) {
        if (!ready) {
            startRequestedPage = fromPage
            return
        }
        page = fromPage.coerceIn(0, pageCount - 1)
        state = State.PLAYING
        onState(true, page)
        speakCurrent()
    }

    fun pause() {
        if (state != State.PLAYING) return
        tts?.stop()
        state = State.PAUSED
        onState(false, page)
    }

    fun resume() {
        if (state != State.PAUSED) return
        state = State.PLAYING
        onState(true, page)
        speakCurrent()
    }

    fun stop() {
        tts?.stop()
        state = State.IDLE
        onState(false, page)
    }

    /** Beim Blättern von Hand: Vorlesen an der neuen Seite fortsetzen. */
    fun moveTo(newPage: Int) {
        if (state == State.PLAYING && newPage != page) {
            page = newPage
            tts?.stop()
            speakCurrent()
        } else {
            page = newPage
        }
    }

    private fun nextPage() {
        if (state != State.PLAYING) return
        if (page + 1 >= pageCount) {
            onInfo("Ende des Dokuments.")
            stop()
            return
        }
        page++
        onState(true, page)
        speakCurrent()
    }

    private fun speakCurrent() {
        val p = page
        worker.execute {
            val text = extract(p)
            main.post {
                if (state != State.PLAYING || page != p) return@post
                if (text.isBlank()) {
                    // Seite ohne Text (z. B. Scan): weiter zur nächsten.
                    nextPage()
                    return@post
                }
                enqueue(p, text)
            }
        }
    }

    private fun enqueue(p: Int, text: String) {
        val t = tts ?: return
        val chunks = chunk(text)
        t.speak("", TextToSpeech.QUEUE_FLUSH, null, "p${p}_flush")
        chunks.forEachIndexed { i, c ->
            val id = if (i == chunks.lastIndex) "p${p}_last" else "p${p}_$i"
            t.speak(c, TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    private fun extract(p: Int): String {
        return try {
            val d = doc ?: PDDocument.load(file, MemoryUsageSetting.setupMixed(32L * 1024 * 1024)).also { doc = it }
            val stripper = PDFTextStripper()
            stripper.startPage = p + 1
            stripper.endPage = p + 1
            stripper.getText(d).replace(Regex("[ \\t]+"), " ").trim()
        } catch (e: Exception) {
            ""
        } catch (e: OutOfMemoryError) {
            ""
        }
    }

    /** Lange Seiten in Stücke unter der TTS-Grenze (~4000 Zeichen) teilen. */
    private fun chunk(text: String): List<String> {
        val max = TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3500)
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > max) {
            var cut = rest.lastIndexOf('.', max).coerceAtLeast(rest.lastIndexOf(' ', max))
            if (cut < max / 2) cut = max
            out.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out.add(rest.trim())
        return out
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        worker.execute {
            try {
                doc?.close()
            } catch (ignored: Exception) {
            }
        }
        worker.shutdown()
    }
}
