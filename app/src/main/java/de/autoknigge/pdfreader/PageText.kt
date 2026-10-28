package de.autoknigge.pdfreader

import kotlin.math.max
import kotlin.math.sqrt

/** Rechteck in Seitenbruchteilen (0 … 1): links, oben, rechts, unten. */
class Box(val l: Float, val t: Float, val r: Float, val b: Float)

/** Eine vorlesbare Einheit (zusammengehörige Zeilen bis zum Satzende) mit Lage und Absatznummer. */
class Block(val text: String, val boxes: List<Box>, val para: Int)

/** Eine Textzeile aus der Auslese, Maße in Seitenpunkten (Ursprung oben links). */
class RawLine(
    val text: String,
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
    val h: Float,
    val hasGlyphs: Boolean
)

/** Treffer beim Antippen einer Seitenstelle. */
class Located(val page: Int, val block: Int, val paraFirst: Int, val paraLast: Int, val boxes: List<Box>)

class PageText(val blocks: List<Block>) {

    /** Block unter dem Punkt ([fx], [fy] als Seitenbruchteile) oder -1, wenn dort kein Text steht. */
    fun hit(fx: Float, fy: Float): Int {
        var best = -1
        var bestD = 0.03f
        for ((i, blk) in blocks.withIndex()) {
            for (b in blk.boxes) {
                val dx = when {
                    fx < b.l -> b.l - fx
                    fx > b.r -> fx - b.r
                    else -> 0f
                }
                val dy = when {
                    fy < b.t -> b.t - fy
                    fy > b.b -> fy - b.b
                    else -> 0f
                }
                val d = sqrt(dx * dx + dy * dy)
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
        }
        return best
    }

    /** Erster und letzter Block des Absatzes, zu dem [block] gehört. */
    fun paragraphOf(block: Int): Pair<Int, Int> {
        val p = blocks[block].para
        var a = block
        var z = block
        while (a > 0 && blocks[a - 1].para == p) a--
        while (z < blocks.lastIndex && blocks[z + 1].para == p) z++
        return a to z
    }
}

object PageTextBuilder {

    private val symbols = Regex("[\\uE000-\\uF8FF\\u25CF\\u25A0\\u2022\\u00B7\\u2023\\u2043]")
    private val invisible = Regex("[\\u00AD\\u200B]")
    private val spaces = Regex("[ \\t]+")

    /** Entfernt Symbolschrift-Zeichen (Aufzählungspunkte u. Ä.), die Sprachausgaben stören. */
    fun clean(raw: String): String =
        raw.replace(symbols, " ").replace(invisible, "").replace(spaces, " ").trim()

    /**
     * Fasst Zeilen zu Satzgruppen ("Blöcken") zusammen. Ein Block endet nach einer Zeile mit Satzzeichen
     * am Ende, nach einer Aufzählungszeile (Komma) oder nach einer kurzen Überschriftenzeile; ein neuer
     * Absatz beginnt nach einer Leerzeile oder bei großem Zeilenabstand.
     */
    fun build(lines: List<RawLine>, pw: Float, ph: Float): PageText {
        val blocks = ArrayList<Block>()
        var text = StringBuilder()
        var boxes = ArrayList<Box>()
        var lineCount = 0
        var para = 0
        var prev: RawLine? = null
        var forceBreak = false

        fun base(l: RawLine) = l.b - 0.25f * l.h

        // Normaler Zeilenabstand der Seite: kleiner Wert der Abstände zwischen aufeinanderfolgenden Zeilen.
        val glyphLines = lines.filter { it.hasGlyphs && clean(it.text).isNotEmpty() }
        val diffs = glyphLines.zipWithNext { a, b -> base(b) - base(a) }.filter { it > 0f }.sorted()
        val pitch = if (diffs.isEmpty()) 0f else diffs[(diffs.size * 35 / 100).coerceAtMost(diffs.lastIndex)]

        fun emit() {
            val t = text.toString().trim()
            if (t.isNotEmpty()) blocks.add(Block(t, boxes.toList(), para))
            text = StringBuilder()
            boxes = ArrayList()
            lineCount = 0
        }

        for (raw in lines) {
            val txt = clean(raw.text)
            // Leerzeilen der Auslese sind unzuverlässig (auch mitten im Absatz): nur der Zeilenabstand zählt.
            if (txt.isEmpty() || !raw.hasGlyphs) continue
            val p = prev
            if (p != null) {
                val gap = base(raw) - base(p)
                val limit = if (pitch > 0f) pitch * 1.45f else 3.0f * max(raw.h, p.h)
                if (forceBreak || gap > limit) {
                    emit()
                    para++
                }
            }
            forceBreak = false
            if (text.isNotEmpty()) text.append(' ')
            text.append(txt)
            lineCount++
            val base = raw.b - 0.25f * raw.h
            boxes.add(
                Box(
                    (raw.l / pw).coerceIn(0f, 1f),
                    ((base - 1.45f * raw.h) / ph).coerceIn(0f, 1f),
                    (raw.r / pw).coerceIn(0f, 1f),
                    ((base + 0.45f * raw.h) / ph).coerceIn(0f, 1f)
                )
            )
            prev = raw

            val last = txt.last()
            val endsSentence = last in ".!?:;"
            // Kurze Zeile mit Komma am Ende = Aufzählungspunkt; lange Zeilen mit Komma laufen weiter.
            val isListItem = last == ',' && txt.length < 60
            when {
                endsSentence || isListItem -> emit()
                // Kurze Zeile allein ohne Satzzeichen = Überschrift: mit Punkt abschließen (ergibt eine
                // Pause) und der nächste Text beginnt einen neuen Absatz.
                lineCount == 1 && last.isLetterOrDigit() && txt.length < 48 -> {
                    text.append('.')
                    emit()
                    forceBreak = true
                }
            }
        }
        emit()
        return PageText(blocks)
    }
}
