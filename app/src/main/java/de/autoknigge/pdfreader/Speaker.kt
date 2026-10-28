package de.autoknigge.pdfreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
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
    @Volatile private var texts: List<String>? = null
    private var skipStart = -1
    private var skipEnd = -1
    private var tts: TextToSpeech? = null
    private var ready = false
    private var startRequestedPage = -1
    private val prefs = context.getSharedPreferences("pdfreader", Context.MODE_PRIVATE)
    private var onReady: (() -> Unit)? = null

    var state = State.IDLE
        private set
    private var page = 0

    /** Vorlesegeschwindigkeit (0,5 = langsam … 2,0 = sehr schnell). */
    var rate: Float = prefs.getFloat("tts_rate", 1.0f)
        private set
    private var voiceName: String? = prefs.getString("tts_voice", null)

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val r = tts?.setLanguage(Locale.GERMAN)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.getDefault()
                }
                tts?.setSpeechRate(rate)
                applySavedVoice()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onError(id: String?) {
                        // Fehler der Sprachausgabe bei einem Stück: nicht hängen bleiben, weiter zur nächsten Seite.
                        if (id != null && !id.endsWith("_flush")) main.post { nextPage() }
                    }
                    override fun onDone(id: String?) {
                        if (id != null && id.endsWith("_last")) main.post { nextPage() }
                    }
                })
                main.post { onReady?.invoke() }
                if (startRequestedPage >= 0) {
                    val p = startRequestedPage; startRequestedPage = -1
                    main.post { start(p) }
                }
            } else {
                main.post { onInfo("Sprachausgabe ist auf diesem Gerät nicht verfügbar.") }
            }
        }
    }

    private fun applySavedVoice() {
        val name = voiceName ?: return
        val v = try {
            tts?.voices?.firstOrNull { it.name == name }
        } catch (e: Exception) {
            null
        }
        if (v != null) tts?.voice = v
    }

    /** Wird aufgerufen, sobald die Sprachausgabe bereit ist (für die Einstellungen). */
    fun whenReady(block: () -> Unit) {
        if (ready) block() else onReady = block
    }

    fun isReady(): Boolean = ready

    /** Deutsche Stimmen des Geräts (leer, falls keine installiert). */
    fun germanVoices(): List<Voice> = try {
        tts?.voices.orEmpty()
            .filter { it.locale.language == "de" && !it.isNetworkConnectionRequired }
            .sortedBy { it.name }
    } catch (e: Exception) {
        emptyList()
    }

    fun currentVoiceName(): String? = try {
        voiceName ?: tts?.voice?.name
    } catch (e: Exception) {
        voiceName
    }

    fun setRate(r: Float) {
        rate = r.coerceIn(0.5f, 2.0f)
        prefs.edit().putFloat("tts_rate", rate).apply()
        tts?.setSpeechRate(rate)
        restartIfPlaying()
    }

    fun setVoice(name: String) {
        voiceName = name
        prefs.edit().putString("tts_voice", name).apply()
        applySavedVoice()
        restartIfPlaying()
    }

    private fun restartIfPlaying() {
        if (state == State.PLAYING) {
            tts?.stop()
            speakCurrent()
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

    /** Meldet übersprungene Seiten ohne Text, damit nichts stillschweigend fehlt. */
    private fun reportSkipped() {
        if (skipStart < 0) return
        val a = skipStart + 1
        val b = skipEnd + 1
        skipStart = -1
        skipEnd = -1
        onInfo(
            if (a == b) "Seite $a enthält keinen lesbaren Text und wurde übersprungen."
            else "Seiten $a–$b enthalten keinen lesbaren Text (vermutlich Bilder oder Scans) und wurden übersprungen."
        )
    }

    private fun nextPage() {
        if (state != State.PLAYING) return
        if (page + 1 >= pageCount) {
            reportSkipped()
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
            val all = ensureTexts()
            val text = clean(all.getOrNull(p).orEmpty())
            main.post {
                if (state != State.PLAYING || page != p) return@post
                if (text.isBlank()) {
                    // Seite ohne Text (z. B. Scan): merken und weiter zur nächsten.
                    if (skipStart < 0) skipStart = p
                    skipEnd = p
                    nextPage()
                    return@post
                }
                reportSkipped()
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

    /**
     * Liest den Text aller Seiten in EINEM Durchgang aus und ordnet ihn Seite für Seite zu
     * (zuverlässiger als eine Einzelabfrage je Seite). Läuft nur einmal, auf dem Worker-Thread.
     */
    private fun ensureTexts(): List<String> {
        texts?.let { return it }
        val out = ArrayList<String>(pageCount)
        try {
            val d = doc ?: PDDocument.load(file, MemoryUsageSetting.setupMixed(48L * 1024 * 1024)).also { doc = it }
            val stripper = object : PDFTextStripper() {
                val sb = StringBuilder()
                override fun startPage(page: PDPage) {
                    sb.setLength(0)
                }
                override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                    sb.append(text)
                }
                override fun writeLineSeparator() {
                    sb.append('\n')
                }
                override fun writeWordSeparator() {
                    sb.append(' ')
                }
                override fun endPage(page: PDPage) {
                    out.add(sb.toString())
                }
            }
            stripper.sortByPosition = true
            stripper.startPage = 1
            stripper.endPage = Int.MAX_VALUE
            stripper.getText(d)
        } catch (e: Exception) {
            // Teilergebnis behalten
        } catch (e: OutOfMemoryError) {
            // Teilergebnis behalten
        }
        // Auf die erwartete Seitenzahl auffüllen, falls die Auslese vorzeitig endete.
        while (out.size < pageCount) out.add("")
        val result: List<String> = out
        texts = result
        return result
    }

    /**
     * Macht den Seitentext vorlesbar: Symbolschrift-Zeichen (Aufzählungspunkte aus Wingdings u. Ä.,
     * liegen im Unicode-Privatbereich) entfernen, Zeilenumbrüche innerhalb eines Satzes glätten und
     * kurze Zeilen ohne Satzzeichen (Überschriften) mit einem Punkt abschließen, damit eine Pause entsteht.
     */
    private fun clean(raw: String): String {
        val noSymbols = raw
            .replace(Regex("[\\uE000-\\uF8FF\\u25CF\\u25A0\\u2022\\u00B7\\u2023\\u2043]"), " ")
            .replace(Regex("[\\u00AD\\u200B]"), "")
        val lines = noSymbols.split('\n').map { it.replace(Regex("[ \\t]+"), " ").trim() }
        val sb = StringBuilder()
        for (i in lines.indices) {
            val line = lines[i]
            if (line.isEmpty()) {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                continue
            }
            sb.append(line)
            val last = line.last()
            val endsSentence = last in ".!?:;"
            val isListItem = last == ','
            // Kurze Zeile ohne Satzzeichen (Überschrift): mit Punkt abschließen, damit eine Pause entsteht.
            val isHeading = !endsSentence && !isListItem && last.isLetterOrDigit() && line.length < 48
            val nextBlank = i + 1 >= lines.size || lines[i + 1].isEmpty()
            when {
                isHeading -> sb.append(".\n")
                endsSentence || isListItem || nextBlank -> sb.append('\n')
                else -> sb.append(' ')
            }
        }
        return sb.toString().replace(Regex("\\n{2,}"), "\n").trim()
    }

    /** Teilt den Text in kurze Stücke (um 400 Zeichen) an Satz- und Absatzgrenzen. */
    private fun chunk(text: String): List<String> {
        val max = 400
        val limit = TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3500)
        // Nicht nach Abkürzungen („Nr.“, „z. B.“, „Abs.“ …), einzelnen Buchstaben und Ziffern („1.“) trennen.
        val splitter = Regex(
            "(?<=[.!?:;])(?<!\\bNr\\.)(?<!\\bAbs\\.)(?<!\\bArt\\.)(?<!\\bbzw\\.)(?<!\\bca\\.)(?<!\\busw\\.)" +
                "(?<!\\bvgl\\.)(?<!\\bDr\\.)(?<!\\bTel\\.)(?<!\\bStr\\.)(?<!\\bggf\\.)(?<!\\bevtl\\.)" +
                "(?<!\\b\\p{L}\\.)(?<!\\d\\.)\\s+"
        )
        val sentences = text.split('\n').flatMap { para ->
            para.split(splitter).filter { it.isNotBlank() }
        }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotBlank()) out.add(cur.toString().trim())
            cur.setLength(0)
        }
        for (s in sentences) {
            var sentence = s
            // Sehr lange Sätze notfalls an Leerzeichen teilen.
            while (sentence.length > limit.coerceAtMost(900)) {
                val cut = sentence.lastIndexOf(' ', 800).takeIf { it > 200 } ?: 800
                if (cur.isNotEmpty()) flush()
                out.add(sentence.substring(0, cut).trim())
                sentence = sentence.substring(cut).trim()
            }
            if (cur.isNotEmpty() && cur.length + 1 + sentence.length > max) flush()
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(sentence)
        }
        flush()
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
