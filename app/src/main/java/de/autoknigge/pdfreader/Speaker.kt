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
 * Liest den Text einer PDF vor (Android-Sprachausgabe). Den Text holt PdfBox in einem Durchgang samt
 * Lage auf der Seite; daraus entstehen Satzgruppen ("Blöcke") und Absätze. So lässt sich ab einer
 * angetippten Stelle, nur ein Absatz oder nur eine Seite vorlesen und die gelesene Stelle markieren.
 * Eingescannte Seiten ohne Textschicht werden übersprungen (und gemeldet).
 */
class Speaker(
    private val context: Context,
    private val file: File,
    private val pageCount: Int,
    private val onState: (playing: Boolean, page: Int) -> Unit,
    private val onInfo: (String) -> Unit,
    private val onHighlight: (page: Int, boxes: List<Box>?) -> Unit
) {
    enum class State { IDLE, PLAYING, PAUSED }

    /** Was gelesen wird: bis zum Dokumentende, nur ein Absatz oder nur die Seite. */
    enum class Scope { DOCUMENT, PARAGRAPH, PAGE }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var doc: PDDocument? = null
    @Volatile private var pages: List<PageText>? = null
    private var skipStart = -1
    private var skipEnd = -1
    private var tts: TextToSpeech? = null
    private var ready = false
    private val prefs = context.getSharedPreferences("pdfreader", Context.MODE_PRIVATE)
    private var onReady: (() -> Unit)? = null

    var state = State.IDLE
        private set
    private var page = 0
    private var block = 0
    private var scope = Scope.DOCUMENT
    private var scopePage = 0
    private var endBlock = Int.MAX_VALUE

    /** Zählt bei jedem Start/Stopp hoch: Rückmeldungen alter, abgebrochener Sprachstücke werden so ignoriert. */
    private var gen = 0
    private var pendingStart: (() -> Unit)? = null
    private var errorShown = false

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
                    override fun onStart(id: String?) {
                        val u = Utt.parse(id) ?: return
                        if (u.block >= 0) main.post { highlight(u) }
                    }

                    override fun onError(id: String?) {
                        val u = Utt.parse(id) ?: return
                        if (u.silent) return
                        if (u.last) main.post { if (u.gen == gen) finished(true) }
                    }

                    override fun onDone(id: String?) {
                        val u = Utt.parse(id) ?: return
                        if (u.last) main.post { if (u.gen == gen) finished(false) }
                    }
                })
                main.post { onReady?.invoke() }
                pendingStart?.let { p ->
                    pendingStart = null
                    main.post { p() }
                }
            } else {
                main.post { onInfo("Sprachausgabe ist auf diesem Gerät nicht verfügbar.") }
            }
        }
    }

    /** Kennung eines Sprachstücks: Durchlauf, Seite, Block, ob es das letzte der Seite ist. */
    private class Utt(val gen: Int, val page: Int, val block: Int, val last: Boolean, val silent: Boolean) {
        companion object {
            fun make(gen: Int, page: Int, block: Int, last: Boolean, silent: Boolean = false) =
                "$gen:$page:$block:${if (silent) 'S' else if (last) 'L' else 'n'}"

            fun parse(id: String?): Utt? {
                val p = id?.split(':') ?: return null
                if (p.size != 4) return null
                return try {
                    Utt(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3] == "L", p[3] == "S")
                } catch (e: NumberFormatException) {
                    null
                }
            }
        }
    }

    private fun highlight(u: Utt) {
        if (u.gen != gen || state != State.PLAYING) return
        block = u.block
        val boxes = pages?.getOrNull(u.page)?.blocks?.getOrNull(u.block)?.boxes
        onHighlight(u.page, boxes)
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

    /** Alle in Frage kommenden deutschen Offline-Stimmen, sortiert (Deutschland zuerst, dann Qualität). */
    private fun voiceCandidates(): List<Voice> = try {
        tts?.voices.orEmpty()
            .filter {
                it.locale.language == "de" && !it.isNetworkConnectionRequired &&
                    !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            .sortedWith(
                compareBy<Voice>({ if (it.locale.country == "DE") 0 else 1 }, { -it.quality }, { it.name })
            )
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Bis zu 6 deutsche Stimmen des Geräts, die ohne Internet laufen (der Text verlässt das Gerät nicht).
     * Verweis-Einträge wie „de-DE-language“ (nur ein Zeiger auf die Standardstimme, klingt wie eine andere)
     * entfallen; nach dem Hörtest steht die zuletzt gelistete Stimme vorn.
     */
    fun germanVoices(): List<Voice> {
        val list = voiceCandidates().filterNot { it.name.endsWith("-language", ignoreCase = true) }.take(6)
        return if (list.size > 1) listOf(list.last()) + list.dropLast(1) else list
    }

    /** Spielt einen kurzen Probesatz mit der gewählten Stimme ab. */
    fun preview() {
        tts?.speak("Hallo, so klingt diese Stimme beim Vorlesen.", TextToSpeech.QUEUE_FLUSH, null, "preview")
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
            gen++
            tts?.stop()
            speakPage(page, block)
        }
    }

    // ---------- Steuerung ----------

    /** Ab Seitenanfang bis zum Dokumentende vorlesen. */
    fun start(fromPage: Int) = startFrom(fromPage, 0, Scope.DOCUMENT, Int.MAX_VALUE)

    /**
     * Ab Block [fromBlock] der Seite [fromPage] vorlesen. [sc] bestimmt das Ende: Dokumentende, Ende des
     * Absatzes (bis Block [lastBlock]) oder Seitenende.
     */
    fun startFrom(fromPage: Int, fromBlock: Int, sc: Scope, lastBlock: Int) {
        if (!ready) {
            pendingStart = { startFrom(fromPage, fromBlock, sc, lastBlock) }
            return
        }
        gen++
        tts?.stop()
        page = fromPage.coerceIn(0, pageCount - 1)
        block = fromBlock
        scope = sc
        scopePage = page
        endBlock = lastBlock
        state = State.PLAYING
        onState(true, page)
        speakPage(page, block)
    }

    fun pause() {
        if (state != State.PLAYING) return
        gen++
        tts?.stop()
        state = State.PAUSED
        onState(false, page)
    }

    fun resume() {
        if (state != State.PAUSED) return
        gen++
        state = State.PLAYING
        onState(true, page)
        speakPage(page, block)
    }

    fun stop() {
        gen++
        tts?.stop()
        state = State.IDLE
        onHighlight(-1, null)
        onState(false, page)
    }

    /** Beim Blättern von Hand. */
    fun moveTo(newPage: Int) {
        if (newPage == page) return
        when {
            state == State.PLAYING && scope == Scope.DOCUMENT -> {
                gen++
                tts?.stop()
                page = newPage
                block = 0
                speakPage(page, 0)
            }
            state == State.PLAYING -> {
                page = newPage
                stop()
            }
            else -> {
                page = newPage
                block = 0
            }
        }
    }

    /** Welcher Text liegt an der Stelle ([fx], [fy] als Seitenbruchteile)? Läuft im Hintergrund. */
    fun locate(p: Int, fx: Float, fy: Float, callback: (Located?) -> Unit) {
        worker.execute {
            val pt = ensurePages().getOrNull(p)
            val idx = pt?.hit(fx, fy) ?: -1
            val result = if (pt == null || idx < 0) {
                null
            } else {
                val (a, z) = pt.paragraphOf(idx)
                Located(p, idx, a, z, (a..z).flatMap { pt.blocks[it].boxes })
            }
            main.post { callback(result) }
        }
    }

    // ---------- Vorlesen ----------

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

    private fun finished(failed: Boolean) {
        if (state != State.PLAYING) return
        if (failed && !errorShown) {
            errorShown = true
            onInfo("Die Sprachausgabe meldet einen Fehler bei einem Textstück. Es wird weitergelesen.")
        }
        if (scope == Scope.DOCUMENT) nextPage() else stop()
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
        block = 0
        onState(true, page)
        speakPage(page, 0)
    }

    private class Item(val block: Int, val text: String)

    private fun speakPage(p: Int, fromBlock: Int) {
        val g = gen
        worker.execute {
            val pt = ensurePages().getOrNull(p)
            val blocks = pt?.blocks.orEmpty()
            val last = if (scope == Scope.PARAGRAPH && p == scopePage) minOf(endBlock, blocks.lastIndex) else blocks.lastIndex
            val items = ArrayList<Item>()
            for (b in fromBlock.coerceAtLeast(0)..last) {
                for (c in chunk(blocks[b].text)) items.add(Item(b, c))
            }
            main.post {
                if (g != gen || state != State.PLAYING) return@post
                if (items.isEmpty()) {
                    if (scope == Scope.DOCUMENT) {
                        if (skipStart < 0) skipStart = p
                        skipEnd = p
                        nextPage()
                    } else {
                        onInfo("Hier wurde kein vorlesbarer Text gefunden.")
                        stop()
                    }
                    return@post
                }
                reportSkipped()
                enqueue(g, p, items)
            }
        }
    }

    private fun enqueue(g: Int, p: Int, items: List<Item>) {
        val t = tts ?: return
        // Kurze stille Anlaufpause: sonst gehen die ersten Worte verloren, wenn Sprachausgabe und
        // Lautsprecher noch aufwachen. QUEUE_FLUSH räumt dabei zugleich die Warteschlange.
        t.playSilentUtterance(350, TextToSpeech.QUEUE_FLUSH, Utt.make(g, p, -1, last = false, silent = true))
        items.forEachIndexed { i, it ->
            t.speak(it.text, TextToSpeech.QUEUE_ADD, null, Utt.make(g, p, it.block, last = i == items.lastIndex))
        }
    }

    /**
     * Liest den Text aller Seiten in EINEM Durchgang samt Lage auf der Seite aus (zuverlässiger als
     * eine Einzelabfrage je Seite). Läuft nur einmal, auf dem Worker-Thread.
     */
    private fun ensurePages(): List<PageText> {
        pages?.let { return it }
        val out = ArrayList<PageText>(pageCount)
        try {
            val d = doc ?: PDDocument.load(file, MemoryUsageSetting.setupMixed(48L * 1024 * 1024)).also { doc = it }
            val stripper = object : PDFTextStripper() {
                var lines = ArrayList<RawLine>()
                var sb = StringBuilder()
                var l = Float.MAX_VALUE
                var t = Float.MAX_VALUE
                var r = -Float.MAX_VALUE
                var b = -Float.MAX_VALUE
                var h = 0f
                var has = false
                var pw = 1f
                var ph = 1f

                fun resetLine() {
                    sb = StringBuilder()
                    l = Float.MAX_VALUE; t = Float.MAX_VALUE; r = -Float.MAX_VALUE; b = -Float.MAX_VALUE
                    h = 0f; has = false
                }

                fun flushLine() {
                    if (sb.isNotEmpty()) lines.add(RawLine(sb.toString(), l, t, r, b, h, has))
                    resetLine()
                }

                override fun startPage(page: PDPage) {
                    lines = ArrayList()
                    resetLine()
                    val box = page.cropBox
                    val rot = page.rotation
                    pw = if (rot == 90 || rot == 270) box.height else box.width
                    ph = if (rot == 90 || rot == 270) box.width else box.height
                }

                override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                    sb.append(text)
                    for (tp in textPositions) {
                        if (tp.unicode.isBlank()) continue
                        val x = tp.xDirAdj
                        val y = tp.yDirAdj
                        val hh = tp.heightDir
                        l = minOf(l, x)
                        r = maxOf(r, x + tp.widthDirAdj)
                        t = minOf(t, y - hh)
                        b = maxOf(b, y + hh * 0.25f)
                        h = maxOf(h, hh)
                        has = true
                    }
                }

                override fun writeWordSeparator() {
                    sb.append(' ')
                }

                override fun writeLineSeparator() {
                    flushLine()
                }

                override fun endPage(page: PDPage) {
                    flushLine()
                    out.add(PageTextBuilder.build(lines, pw, ph))
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
        while (out.size < pageCount) out.add(PageText(emptyList()))
        val result: List<PageText> = out
        pages = result
        return result
    }

    /** Teilt einen Block bei Bedarf in Stücke um 400 Zeichen an Satzgrenzen (nicht nach Abkürzungen). */
    private fun chunk(text: String): List<String> {
        val max = 400
        val limit = TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3500)
        if (text.length <= max) return listOf(text)
        val splitter = Regex(
            "(?<=[.!?:;])(?<!\\bNr\\.)(?<!\\bAbs\\.)(?<!\\bArt\\.)(?<!\\bbzw\\.)(?<!\\bca\\.)(?<!\\busw\\.)" +
                "(?<!\\bvgl\\.)(?<!\\bDr\\.)(?<!\\bTel\\.)(?<!\\bStr\\.)(?<!\\bggf\\.)(?<!\\bevtl\\.)" +
                "(?<!\\b\\p{L}\\.)(?<!\\d\\.)\\s+"
        )
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotBlank()) out.add(cur.toString().trim())
            cur.setLength(0)
        }
        for (s in text.split(splitter).filter { it.isNotBlank() }) {
            var sentence = s
            // Sehr lange Sätze notfalls an Leerzeichen teilen.
            while (sentence.length > limit.coerceAtMost(900)) {
                val cut = sentence.lastIndexOf(' ', 800).takeIf { it > 200 } ?: 800
                flush()
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
        gen++
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
