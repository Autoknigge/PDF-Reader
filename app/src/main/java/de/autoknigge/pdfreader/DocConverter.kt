package de.autoknigge.pdfreader

import android.text.Html
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

/** Holt den reinen Text aus Word (.docx), OpenDocument (.odt), E-Books (.epub), Webseiten (.html) und .rtf. */
object DocConverter {

    const val MAX_FILE = 40 * 1024 * 1024
    private const val MAX_ENTRY = 16 * 1024 * 1024
    private const val MAX_TEXT = 6_000_000

    enum class Kind { TEXT, DOCX, ODT, EPUB, HTML, RTF, XLSX, ODS, FB2, PPTX, ODP, PPT }

    fun kindOf(name: String, mime: String?): Kind {
        val n = name.lowercase()
        return when {
            n.endsWith(".docx") -> Kind.DOCX
            n.endsWith(".fb2") -> Kind.FB2
            n.endsWith(".pptx") || n.endsWith(".pptm") || n.endsWith(".ppsx") -> Kind.PPTX
            n.endsWith(".odp") -> Kind.ODP
            n.endsWith(".ppt") || n.endsWith(".pps") -> Kind.PPT
            n.endsWith(".odt") -> Kind.ODT
            n.endsWith(".xlsx") || n.endsWith(".xlsm") -> Kind.XLSX
            n.endsWith(".ods") -> Kind.ODS
            n.endsWith(".epub") -> Kind.EPUB
            n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".xhtml") -> Kind.HTML
            n.endsWith(".rtf") -> Kind.RTF
            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> Kind.DOCX
            mime == "application/x-fictionbook+xml" || mime == "application/x-fictionbook" -> Kind.FB2
            mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> Kind.PPTX
            mime == "application/vnd.openxmlformats-officedocument.presentationml.slideshow" -> Kind.PPTX
            mime == "application/vnd.oasis.opendocument.presentation" -> Kind.ODP
            mime == "application/vnd.ms-powerpoint" -> Kind.PPT
            mime == "application/vnd.oasis.opendocument.text" -> Kind.ODT
            mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> Kind.XLSX
            mime == "application/vnd.oasis.opendocument.spreadsheet" -> Kind.ODS
            mime == "application/epub+zip" -> Kind.EPUB
            mime == "text/html" || mime == "application/xhtml+xml" -> Kind.HTML
            mime == "text/rtf" || mime == "application/rtf" -> Kind.RTF
            else -> Kind.TEXT
        }
    }

    fun readAll(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException("Die Datei ist zu groß.")
        }
        return out.toByteArray()
    }

    fun convert(kind: Kind, bytes: ByteArray): String {
        val text = when (kind) {
            Kind.DOCX -> docx(bytes)
            Kind.ODT -> odt(bytes)
            Kind.XLSX, Kind.ODS -> sheetsToText(sheets(kind, bytes))
            Kind.FB2 -> richToText(fb2Rich(bytes))
            Kind.PPTX -> richToText(pptxRich(bytes))
            Kind.ODP -> richToText(odpRich(bytes))
            Kind.PPT -> throw IOException("Das alte PowerPoint-Format (.ppt) kann ich nicht lesen. Speichere die Präsentation in PowerPoint als .pptx oder exportiere sie als PDF.")
            Kind.EPUB -> epub(bytes)
            Kind.HTML -> html(decode(bytes))
            Kind.RTF -> rtf(decode(bytes))
            Kind.TEXT -> decode(bytes)
        }
        val clean = text.replace("\r\n", "\n").replace(Regex("\n{4,}"), "\n\n\n").trim()
        if (clean.isEmpty()) throw IOException("In dieser Datei wurde kein Text gefunden.")
        return if (clean.length > MAX_TEXT) clean.substring(0, MAX_TEXT) else clean
    }

    private fun decode(bytes0: ByteArray): String {
        var bytes = bytes0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes = bytes.copyOfRange(3, bytes.size)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            String(bytes, charset("windows-1252"))
        }
    }

    // ---------------------------------------------------------------- Zip

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name
                val low = n.lowercase()
                if (low.endsWith(".xml") || low.endsWith(".xhtml") || low.endsWith(".html") || low.endsWith(".htm") ||
                    low.endsWith(".opf") || low.endsWith(".rels") || low.endsWith(".ncx")
                ) {
                    try {
                        map[n] = readAll(z, MAX_ENTRY)
                    } catch (ignored: IOException) {
                    }
                }
            }
        }
        return map
    }

    private fun ln(p: XmlPullParser): String = (p.name ?: "").substringAfter(':')

    private fun attr(p: XmlPullParser, n: String): String? {
        for (i in 0 until p.attributeCount) if (p.getAttributeName(i).substringAfter(':') == n) return p.getAttributeValue(i)
        return null
    }

    private fun parser(b: ByteArray): XmlPullParser {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(ByteArrayInputStream(b), null)
        return p
    }

    // ---------------------------------------------------------------- DOCX

    private fun docx(bytes: ByteArray): String {
        val files = unzip(bytes)
        val doc = files["word/document.xml"] ?: throw IOException("Das ist keine gültige Word-Datei.")
        val sb = StringBuilder()
        val p = parser(doc)
        var ev = p.eventType
        var inText = false
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (ln(p)) {
                    "t" -> inText = true
                    "tab" -> sb.append('\t')
                    "br", "cr" -> sb.append('\n')
                }
                XmlPullParser.END_TAG -> when (ln(p)) {
                    "t" -> inText = false
                    "p" -> sb.append("\n\n")
                }
                XmlPullParser.TEXT -> if (inText) sb.append(p.text)
            }
            ev = p.next()
        }
        return sb.toString().replace(Regex("\n\n(?=\n)"), "\n")
    }

    // ---------------------------------------------------------------- DOCX mit Formatierung

    /** Wurde die Datei von dieser App geschrieben (dann darf sie beim Speichern überschrieben werden)? */
    fun isOwnDocx(bytes: ByteArray): Boolean {
        val app = unzip(bytes)["docProps/app.xml"] ?: return false
        return String(app, Charsets.UTF_8).contains("<Application>PDF-Reader</Application>")
    }

    fun docxRich(bytes: ByteArray): List<RichPara> {
        val files = unzip(bytes)
        val doc = files["word/document.xml"] ?: throw IOException("Das ist keine gültige Word-Datei.")
        val paras = ArrayList<RichPara>()
        val p = parser(doc)
        var ev = p.eventType
        var runs = ArrayList<RichRun>()
        var level = 0
        var bullet = false
        var inPPr = false
        var inRun = false
        var inT = false
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        fun add(t: String) {
            if (t.isEmpty()) return
            val last = runs.lastOrNull()
            if (last != null && last.bold == bold && last.italic == italic && last.underline == underline && last.strike == strike) {
                runs[runs.size - 1] = RichRun(last.text + t, bold, italic, underline, strike)
            } else runs.add(RichRun(t, bold, italic, underline, strike))
        }
        fun on(v: String?) = v == null || (v != "0" && v != "false")
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (ln(p)) {
                    "p" -> { runs = ArrayList(); level = 0; bullet = false }
                    "pPr" -> inPPr = true
                    "pStyle" -> if (inPPr) {
                        val v = (attr(p, "val") ?: "").lowercase()
                        level = when {
                            v == "heading1" || v == "title" || v == "berschrift1" || v == "überschrift1" -> 1
                            v.startsWith("heading") || v.startsWith("berschrift") || v.startsWith("überschrift") || v == "subtitle" -> 2
                            else -> 0
                        }
                    }
                    "numPr" -> if (inPPr) bullet = true
                    "r" -> { inRun = true; bold = false; italic = false; underline = false; strike = false }
                    "b" -> if (inRun && !inPPr) bold = on(attr(p, "val"))
                    "i" -> if (inRun && !inPPr) italic = on(attr(p, "val"))
                    "u" -> if (inRun && !inPPr) underline = (attr(p, "val") ?: "single") != "none"
                    "strike", "dstrike" -> if (inRun && !inPPr) strike = on(attr(p, "val"))
                    "t" -> inT = true
                    "tab" -> if (inRun) add("\t")
                    "br", "cr" -> if (inRun) add("\n")
                }
                XmlPullParser.END_TAG -> when (ln(p)) {
                    "pPr" -> inPPr = false
                    "r" -> inRun = false
                    "t" -> inT = false
                    "p" -> paras.add(RichPara(level, bullet, runs))
                }
                XmlPullParser.TEXT -> if (inT) add(p.text)
            }
            ev = p.next()
        }
        while (paras.isNotEmpty() && paras.last().runs.all { it.text.isBlank() }) paras.removeAt(paras.size - 1)
        if (paras.isEmpty()) throw IOException("In dieser Datei wurde kein Text gefunden.")
        return paras
    }

    // ---------------------------------------------------------------- ODT

    private fun odt(bytes: ByteArray): String {
        val files = unzip(bytes)
        val doc = files["content.xml"] ?: throw IOException("Das ist keine gültige OpenDocument-Datei.")
        val sb = StringBuilder()
        val p = parser(doc)
        var ev = p.eventType
        var inBody = false
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (ln(p)) {
                    "text" -> inBody = true
                    "tab" -> if (inBody) sb.append('\t')
                    "line-break" -> if (inBody) sb.append('\n')
                    "s" -> if (inBody) {
                        val c = attr(p, "c")?.toIntOrNull() ?: 1
                        repeat(minOf(c, 50)) { sb.append(' ') }
                    }
                }
                XmlPullParser.END_TAG -> when (ln(p)) {
                    "p", "h" -> if (inBody) sb.append("\n\n")
                    "text" -> inBody = false
                }
                XmlPullParser.TEXT -> if (inBody) sb.append(p.text)
            }
            ev = p.next()
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- Tabellen

    class Sheet(val name: String, val rows: List<List<String>>)

    private const val MAX_ROWS = 20000
    private const val MAX_COLS = 80

    fun isSheet(k: Kind) = k == Kind.XLSX || k == Kind.ODS

    fun sheets(kind: Kind, bytes: ByteArray): List<Sheet> {
        val list = if (kind == Kind.XLSX) xlsxSheets(bytes) else odsSheets(bytes)
        if (list.all { it.rows.isEmpty() }) throw IOException("In dieser Datei wurde keine Tabelle gefunden.")
        return list
    }

    fun sheetsToText(list: List<Sheet>): String {
        val sb = StringBuilder()
        for (sh in list) {
            if (sh.rows.isEmpty()) continue
            if (list.size > 1) sb.append("Blatt: ").append(sh.name).append("\n\n")
            for (r in sh.rows) {
                val cells = r.filter { it.isNotBlank() }
                if (cells.isNotEmpty()) sb.append(cells.joinToString("; ")).append('\n')
            }
            sb.append("\n\n")
            if (sb.length > MAX_TEXT) break
        }
        return sb.toString()
    }

    private fun colIndex(ref: String?): Int {
        if (ref == null) return -1
        var n = 0
        var any = false
        for (ch in ref) {
            if (ch in 'A'..'Z') { n = n * 26 + (ch - 'A' + 1); any = true } else if (ch in 'a'..'z') { n = n * 26 + (ch - 'a' + 1); any = true } else break
        }
        return if (any) n - 1 else -1
    }

    private fun setCell(row: ArrayList<String>, col: Int, v: String) {
        if (col < 0 || col >= MAX_COLS) return
        while (row.size <= col) row.add("")
        row[col] = v
    }

    private fun xlsxSheets(bytes: ByteArray): List<Sheet> {
        val files = unzip(bytes)
        val wb = files["xl/workbook.xml"] ?: throw IOException("Das ist keine gültige Excel-Datei.")
        val shared = ArrayList<String>()
        files["xl/sharedStrings.xml"]?.let { b ->
            val p = parser(b)
            var ev = p.eventType
            var cur: StringBuilder? = null
            var inT = false
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (ln(p)) {
                        "si" -> cur = StringBuilder()
                        "t" -> inT = true
                    }
                    XmlPullParser.END_TAG -> when (ln(p)) {
                        "t" -> inT = false
                        "si" -> { shared.add(cur?.toString() ?: ""); cur = null }
                    }
                    XmlPullParser.TEXT -> if (inT) cur?.append(p.text)
                }
                ev = p.next()
            }
        }
        val rels = HashMap<String, String>()
        files["xl/_rels/workbook.xml.rels"]?.let { b ->
            val p = parser(b)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && ln(p) == "Relationship") {
                    val id = attr(p, "Id"); val t = attr(p, "Target")
                    if (id != null && t != null) rels[id] = t
                }
                ev = p.next()
            }
        }
        val defs = ArrayList<Pair<String, String>>()
        parser(wb).let { p ->
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && ln(p) == "sheet") {
                    val name = attr(p, "name") ?: "Blatt"
                    val target = attr(p, "id")?.let { rels[it] }
                    if (target != null) {
                        val path = if (target.startsWith("/")) target.trimStart('/') else resolve("xl/", target)
                        defs.add(name to path)
                    }
                }
                ev = p.next()
            }
        }
        val result = ArrayList<Sheet>()
        for ((name, path) in defs) {
            val data = files[path] ?: continue
            val rows = ArrayList<List<String>>()
            val p = parser(data)
            var ev = p.eventType
            var row = ArrayList<String>()
            var type: String? = null
            var col = 0
            var inV = false
            var inT = false
            val cell = StringBuilder()
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (ln(p)) {
                        "row" -> row = ArrayList()
                        "c" -> {
                            type = attr(p, "t"); cell.setLength(0)
                            val c = colIndex(attr(p, "r")); if (c >= 0) col = c
                        }
                        "v" -> inV = true
                        "t" -> inT = true
                    }
                    XmlPullParser.END_TAG -> when (ln(p)) {
                        "v" -> inV = false
                        "t" -> inT = false
                        "c" -> {
                            var v = cell.toString()
                            if (type == "s") v = v.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            else if (type == "b") v = if (v.trim() == "1") "wahr" else "falsch"
                            if (v.isNotBlank()) setCell(row, col, v.trim())
                            col++
                        }
                        "row" -> if (rows.size < MAX_ROWS) rows.add(row)
                    }
                    XmlPullParser.TEXT -> if (inV || inT) cell.append(p.text)
                }
                ev = p.next()
            }
            result.add(Sheet(name, trimRows(rows)))
        }
        return result
    }

    private fun odsSheets(bytes: ByteArray): List<Sheet> {
        val files = unzip(bytes)
        val doc = files["content.xml"] ?: throw IOException("Das ist keine gültige Tabellen-Datei.")
        val result = ArrayList<Sheet>()
        val p = parser(doc)
        var ev = p.eventType
        var rows = ArrayList<List<String>>()
        var name = "Blatt"
        var row = ArrayList<String>()
        var rowRep = 1
        val cell = StringBuilder()
        var inCell = false
        var col = 0
        var colRep = 1
        var inTable = false
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (ln(p)) {
                    "table" -> { inTable = true; name = attr(p, "name") ?: "Blatt"; rows = ArrayList() }
                    "table-row" -> { row = ArrayList(); col = 0; rowRep = attr(p, "number-rows-repeated")?.toIntOrNull() ?: 1 }
                    "table-cell" -> { inCell = true; cell.setLength(0); colRep = attr(p, "number-columns-repeated")?.toIntOrNull() ?: 1 }
                    "covered-table-cell" -> { inCell = true; cell.setLength(0); colRep = 1 }
                    "tab" -> if (inCell) cell.append('\t')
                    "line-break" -> if (inCell) cell.append(' ')
                }
                XmlPullParser.END_TAG -> when (ln(p)) {
                    "p" -> if (inCell && cell.isNotEmpty()) cell.append(' ')
                    "table-cell", "covered-table-cell" -> {
                        inCell = false
                        val v = cell.toString().trim()
                        if (v.isNotEmpty()) {
                            for (k in 0 until minOf(colRep, 20)) setCell(row, col + k, v)
                        }
                        col += colRep
                    }
                    "table-row" -> if (inTable && rows.size < MAX_ROWS) {
                        val n = if (row.any { it.isNotBlank() }) minOf(rowRep, 20) else 1
                        repeat(n) { rows.add(row) }
                    }
                    "table" -> { result.add(Sheet(name, trimRows(rows))); inTable = false }
                }
                XmlPullParser.TEXT -> if (inCell) cell.append(p.text)
            }
            ev = p.next()
        }
        return result
    }

    /** Leere Zeilen am Ende entfernen und alle Zeilen auf gleiche Spaltenzahl bringen. */
    private fun trimRows(rows: List<List<String>>): List<List<String>> {
        var end = rows.size
        while (end > 0 && rows[end - 1].none { it.isNotBlank() }) end--
        val cut = rows.subList(0, end)
        val cols = cut.maxOfOrNull { it.size } ?: 0
        return cut.map { r -> if (r.size < cols) r + List(cols - r.size) { "" } else r }
    }

    // ---------------------------------------------------------------- EPUB

    private fun epub(bytes: ByteArray): String {
        val files = unzip(bytes)
        val container = files["META-INF/container.xml"] ?: throw IOException("Das ist keine gültige EPUB-Datei.")
        var opfPath: String? = null
        parser(container).let { p ->
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT && opfPath == null) {
                if (ev == XmlPullParser.START_TAG && ln(p) == "rootfile") opfPath = attr(p, "full-path")
                ev = p.next()
            }
        }
        val opf = opfPath ?: throw IOException("Das E-Book hat keine Inhaltsliste.")
        val opfBytes = files[opf] ?: throw IOException("Das E-Book hat keine Inhaltsliste.")
        val dir = if (opf.contains('/')) opf.substringBeforeLast('/') + "/" else ""
        val manifest = HashMap<String, String>()
        val spine = ArrayList<String>()
        parser(opfBytes).let { p ->
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (ln(p)) {
                        "item" -> {
                            val id = attr(p, "id")
                            val href = attr(p, "href")
                            if (id != null && href != null) manifest[id] = href
                        }
                        "itemref" -> attr(p, "idref")?.let { spine.add(it) }
                    }
                }
                ev = p.next()
            }
        }
        val sb = StringBuilder()
        for (id in spine) {
            val href = manifest[id] ?: continue
            val path = resolve(dir, URLDecoder.decode(href.substringBefore('#'), "UTF-8"))
            val part = files[path] ?: continue
            val t = html(decode(part)).trim()
            if (t.isNotEmpty()) sb.append(t).append("\n\n\n")
            if (sb.length > MAX_TEXT) break
        }
        return sb.toString()
    }

    private fun resolve(dir: String, rel: String): String {
        val parts = ArrayList<String>()
        for (s in (dir + rel).split('/')) {
            when (s) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(s)
            }
        }
        return parts.joinToString("/")
    }

    // ---------------------------------------------------------------- Präsentationen (PowerPoint, OpenDocument)

    private fun attrFull(p: XmlPullParser, full: String): String? {
        for (i in 0 until p.attributeCount) if (p.getAttributeName(i) == full) return p.getAttributeValue(i)
        return null
    }

    /** Text einer Folien-Datei: Titel und übrige Absätze. */
    private fun slideTexts(xml: ByteArray, skipPh: Set<String> = emptySet()): Pair<String, List<Pair<Int, String>>> {
        val p = parser(xml)
        var ev = p.eventType
        var title = ""
        val body = ArrayList<Pair<Int, String>>()
        var inSp = false
        var phType = ""
        var shape = ArrayList<Pair<Int, String>>()
        var lvl = 0
        val cur = StringBuilder()
        var inT = false
        var inPara = false
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (ln(p)) {
                    "sp" -> { inSp = true; phType = ""; shape = ArrayList() }
                    "ph" -> if (inSp) phType = attr(p, "type") ?: "body"
                    "p" -> { inPara = true; cur.setLength(0); lvl = 0 }
                    "pPr" -> if (inPara) lvl = attr(p, "lvl")?.toIntOrNull() ?: 0
                    "t" -> inT = true
                    "br" -> if (inPara) cur.append(' ')
                }
                XmlPullParser.END_TAG -> when (ln(p)) {
                    "t" -> inT = false
                    "p" -> if (inPara) {
                        inPara = false
                        val t = cur.toString().trim()
                        if (t.isNotEmpty()) (if (inSp) shape else body).add(lvl to t)
                    }
                    "sp" -> if (inSp) {
                        inSp = false
                        if (phType in skipPh) {
                            // z. B. Foliennummer, Folienbild
                        } else if (phType == "title" || phType == "ctrTitle") {
                            if (title.isEmpty()) title = shape.joinToString(" ") { it.second }
                        } else {
                            body.addAll(shape)
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inT && inPara) cur.append(p.text)
            }
            ev = p.next()
        }
        return title to body
    }

    fun pptxRich(bytes: ByteArray): List<RichPara> {
        val files = unzip(bytes)
        val pres = files["ppt/presentation.xml"] ?: throw IOException("Das ist keine gültige PowerPoint-Datei.")
        val rels = HashMap<String, String>()
        files["ppt/_rels/presentation.xml.rels"]?.let { b ->
            val p = parser(b)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && ln(p) == "Relationship") {
                    val id = attr(p, "Id"); val t = attr(p, "Target")
                    if (id != null && t != null) rels[id] = t
                }
                ev = p.next()
            }
        }
        val order = ArrayList<String>()
        parser(pres).let { p ->
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && ln(p) == "sldId") attrFull(p, "r:id")?.let { order.add(it) }
                ev = p.next()
            }
        }
        val out = ArrayList<RichPara>()
        var n = 0
        for (rid in order) {
            val target = rels[rid] ?: continue
            val path = if (target.startsWith("/")) target.trimStart('/') else resolve("ppt/", target)
            val data = files[path] ?: continue
            n++
            val (title, body) = slideTexts(data, setOf("sldNum", "dt", "ftr", "sldImg"))
            out.add(RichPara(1, false, listOf(RichRun("Folie $n" + (if (title.isNotEmpty()) ": $title" else ""), false, false))))
            for ((l, t) in body) out.add(RichPara(0, false, listOf(RichRun("    ".repeat(l.coerceIn(0, 4)) + t, false, false))))
            // Notizen zur Folie
            val relFile = path.substringBeforeLast('/') + "/_rels/" + path.substringAfterLast('/') + ".rels"
            var notesPath: String? = null
            files[relFile]?.let { b ->
                val p = parser(b)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && ln(p) == "Relationship" && (attr(p, "Type") ?: "").endsWith("/notesSlide")) {
                        attr(p, "Target")?.let { notesPath = resolve(path.substringBeforeLast('/') + "/", it) }
                    }
                    ev = p.next()
                }
            }
            val nd = notesPath?.let { files[it] }
            if (nd != null) {
                val (_, nb) = slideTexts(nd, setOf("sldNum", "sldImg", "hdr", "dt", "ftr"))
                val nt = nb.joinToString(" ") { it.second }.trim()
                if (nt.isNotEmpty()) out.add(RichPara(0, false, listOf(RichRun("Notizen: $nt", false, true))))
            }
            out.add(RichPara(0, false, emptyList()))
            if (out.size > 200000) break
        }
        if (out.isEmpty()) throw IOException("In dieser Präsentation wurde kein Text gefunden.")
        return out
    }

    fun odpRich(bytes: ByteArray): List<RichPara> {
        val files = unzip(bytes)
        val doc = files["content.xml"] ?: throw IOException("Das ist keine gültige Präsentation.")
        val out = ArrayList<RichPara>()
        val p = parser(doc)
        var ev = p.eventType
        var n = 0
        var inPage = false
        var depthPara = false
        val cur = StringBuilder()
        var first = true
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (ln(p)) {
                    "page" -> {
                        if (n > 0) out.add(RichPara(0, false, emptyList()))
                        n++; inPage = true; first = true
                        out.add(RichPara(1, false, listOf(RichRun("Folie $n", false, false))))
                    }
                    "p", "h" -> if (inPage) { depthPara = true; cur.setLength(0) }
                    "s" -> if (depthPara) cur.append(' ')
                    "line-break" -> if (depthPara) cur.append(' ')
                }
                XmlPullParser.END_TAG -> when (ln(p)) {
                    "page" -> inPage = false
                    "p", "h" -> if (depthPara) {
                        depthPara = false
                        val t = cur.toString().trim()
                        if (t.isNotEmpty()) out.add(RichPara(0, false, listOf(RichRun(t, false, false))))
                    }
                }
                XmlPullParser.TEXT -> if (depthPara) cur.append(p.text)
            }
            ev = p.next()
        }
        if (n == 0 || out.none { it.level == 0 && it.runs.isNotEmpty() }) throw IOException("In dieser Präsentation wurde kein Text gefunden.")
        return out
    }

    // ---------------------------------------------------------------- FB2 und EPUB mit Überschriften

    fun richToText(paras: List<RichPara>): String =
        paras.joinToString("\n") { p -> (if (p.bullet) "• " else "") + p.runs.joinToString("") { it.text } }

    fun fb2Rich(bytes: ByteArray): List<RichPara> {
        val head = ArrayList<RichPara>()
        val out = ArrayList<RichPara>()
        val p = parser(bytes)
        var ev = p.eventType
        var depth = 0
        var inTitle = false
        var inBinary = false
        var inDesc = false
        var inPara = false
        var paraLevel = 0
        var bold = 0
        var ital = 0
        var runs = ArrayList<RichRun>()
        val titleBuf = StringBuilder()
        var bookTitle = ""
        val author = ArrayList<String>()
        var cur = ""
        var inAuthor = false
        fun add(t: String) {
            if (t.isEmpty()) return
            val b = bold > 0
            val i = ital > 0
            val last = runs.lastOrNull()
            if (last != null && last.bold == b && last.italic == i) runs[runs.size - 1] = RichRun(last.text + t, b, i)
            else runs.add(RichRun(t, b, i))
        }
        fun flushPara() {
            if (runs.isNotEmpty() && runs.any { it.text.isNotBlank() }) out.add(RichPara(paraLevel, false, runs))
            runs = ArrayList()
        }
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    cur = ln(p)
                    when (cur) {
                        "binary" -> inBinary = true
                        "description" -> inDesc = true
                        "author" -> if (inDesc) inAuthor = true
                        "section" -> depth++
                        "title" -> if (!inDesc) { inTitle = true; titleBuf.setLength(0) }
                        "p", "v", "text-author" -> if (!inDesc) { inPara = true; paraLevel = 0; runs = ArrayList() }
                        "subtitle" -> if (!inDesc) { inPara = true; paraLevel = 2; runs = ArrayList() }
                        "strong" -> bold++
                        "emphasis" -> ital++
                        "empty-line" -> out.add(RichPara(0, false, emptyList()))
                    }
                }
                XmlPullParser.END_TAG -> {
                    val n = ln(p)
                    when (n) {
                        "binary" -> inBinary = false
                        "description" -> inDesc = false
                        "author" -> inAuthor = false
                        "section" -> depth--
                        "strong" -> if (bold > 0) bold--
                        "emphasis" -> if (ital > 0) ital--
                        "p", "v", "text-author", "subtitle" -> if (!inDesc) {
                            if (inTitle) {
                                val t = runs.joinToString("") { it.text }.trim()
                                if (t.isNotEmpty()) { if (titleBuf.isNotEmpty()) titleBuf.append(' '); titleBuf.append(t) }
                                runs = ArrayList()
                            } else flushPara()
                            inPara = false
                        }
                        "title" -> if (inTitle) {
                            inTitle = false
                            if (titleBuf.isNotBlank()) out.add(RichPara(if (depth <= 1) 1 else 2, false, listOf(RichRun(titleBuf.toString(), false, false))))
                        }
                    }
                    cur = ""
                }
                XmlPullParser.TEXT -> if (!inBinary) {
                    if (inDesc) {
                        val t = p.text.trim()
                        if (t.isNotEmpty()) when {
                            cur == "book-title" && bookTitle.isEmpty() -> bookTitle = t
                            inAuthor && (cur == "first-name" || cur == "middle-name" || cur == "last-name") -> author.add(t)
                        }
                    } else if (inPara) {
                        add(p.text.replace('\n', ' ').replace(Regex("\\s{2,}"), " "))
                    }
                }
            }
            ev = p.next()
        }
        if (bookTitle.isNotEmpty()) head.add(RichPara(1, false, listOf(RichRun(bookTitle, false, false))))
        if (author.isNotEmpty()) head.add(RichPara(0, false, listOf(RichRun(author.joinToString(" "), false, true))))
        val all = head + out
        if (all.none { it.runs.any { r -> r.text.isNotBlank() } }) throw IOException("In dieser Datei wurde kein Text gefunden.")
        return all
    }

    fun epubRich(bytes: ByteArray): List<RichPara> {
        val files = unzip(bytes)
        val container = files["META-INF/container.xml"] ?: throw IOException("Das ist keine gültige EPUB-Datei.")
        var opfPath: String? = null
        parser(container).let { p ->
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT && opfPath == null) {
                if (ev == XmlPullParser.START_TAG && ln(p) == "rootfile") opfPath = attr(p, "full-path")
                ev = p.next()
            }
        }
        val opf = opfPath ?: throw IOException("Das E-Book hat keine Inhaltsliste.")
        val opfBytes = files[opf] ?: throw IOException("Das E-Book hat keine Inhaltsliste.")
        val dir = if (opf.contains('/')) opf.substringBeforeLast('/') + "/" else ""
        val manifest = HashMap<String, String>()
        var navPath: String? = null
        var ncxPath: String? = null
        val spine = ArrayList<String>()
        parser(opfBytes).let { p ->
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (ln(p)) {
                        "item" -> {
                            val id = attr(p, "id")
                            val href = attr(p, "href")
                            if (id != null && href != null) {
                                manifest[id] = href
                                val props = attr(p, "properties") ?: ""
                                val mt = attr(p, "media-type") ?: ""
                                val full = resolve(dir, URLDecoder.decode(href.substringBefore('#'), "UTF-8"))
                                if (props.split(' ').contains("nav")) navPath = full
                                if (mt == "application/x-dtbncx+xml") ncxPath = full
                            }
                        }
                        "itemref" -> attr(p, "idref")?.let { spine.add(it) }
                    }
                }
                ev = p.next()
            }
        }
        // Kapitelnamen aus Inhaltsverzeichnis (nav oder ncx): Datei -> erster Titel
        val labels = HashMap<String, String>()
        try {
            val tocFile = navPath ?: ncxPath
            val tb = tocFile?.let { files[it] }
            if (tb != null) {
                val base = tocFile!!.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                val p = parser(tb)
                var ev = p.eventType
                var pendingText = StringBuilder()
                var inLabel = false
                var href: String? = null
                while (ev != XmlPullParser.END_DOCUMENT) {
                    when (ev) {
                        XmlPullParser.START_TAG -> when (ln(p)) {
                            "a" -> { href = attr(p, "href"); pendingText = StringBuilder(); inLabel = true }
                            "navPoint" -> { pendingText = StringBuilder() }
                            "text" -> { inLabel = true }
                            "content" -> attr(p, "src")?.let { src ->
                                val path = resolve(base, URLDecoder.decode(src.substringBefore('#'), "UTF-8"))
                                val t = pendingText.toString().trim()
                                if (t.isNotEmpty() && !labels.containsKey(path)) labels[path] = t
                            }
                        }
                        XmlPullParser.END_TAG -> when (ln(p)) {
                            "a" -> {
                                val h = href
                                if (h != null) {
                                    val path = resolve(base, URLDecoder.decode(h.substringBefore('#'), "UTF-8"))
                                    val t = pendingText.toString().trim()
                                    if (t.isNotEmpty() && !labels.containsKey(path)) labels[path] = t
                                }
                                inLabel = false; href = null
                            }
                            "text" -> inLabel = false
                        }
                        XmlPullParser.TEXT -> if (inLabel) pendingText.append(p.text)
                    }
                    ev = p.next()
                }
            }
        } catch (ignored: Exception) {
        }
        val out = ArrayList<RichPara>()
        var n = 0
        for (id in spine) {
            val href = manifest[id] ?: continue
            val path = resolve(dir, URLDecoder.decode(href.substringBefore('#'), "UTF-8"))
            val part = files[path] ?: continue
            val lines = html(decode(part)).trim().split('\n').toMutableList()
            if (lines.all { it.isBlank() }) continue
            n++
            var title = labels[path]
            if (title == null) {
                val first = lines.firstOrNull { it.isNotBlank() }?.trim()
                if (first != null && first.length <= 80) {
                    title = first
                    lines.removeAt(lines.indexOfFirst { it.isNotBlank() })
                }
            } else {
                // erste Zeile ist oft der gleiche Titel: nicht doppelt zeigen
                val idx = lines.indexOfFirst { it.isNotBlank() }
                if (idx >= 0 && lines[idx].trim().equals(title, ignoreCase = true)) lines.removeAt(idx)
            }
            out.add(RichPara(1, false, listOf(RichRun(title ?: "Kapitel $n", false, false))))
            var prevEmpty = true
            for (l in lines) {
                val t = l.trim()
                if (t.isEmpty()) { if (!prevEmpty) out.add(RichPara(0, false, emptyList())); prevEmpty = true } else { out.add(RichPara(0, false, listOf(RichRun(t, false, false)))); prevEmpty = false }
            }
            if (out.size > 400000) break
        }
        if (out.isEmpty()) throw IOException("In dieser Datei wurde kein Text gefunden.")
        return out
    }

    // ---------------------------------------------------------------- HTML

    private fun html(src: String): String {
        var s = src
        s = Regex("(?is)<(script|style|head)\\b.*?</\\1>").replace(s, " ")
        s = Regex("(?is)<!--.*?-->").replace(s, " ")
        s = Regex("(?i)<br\\s*/?>").replace(s, "\n")
        s = Regex("(?i)</(p|div|h[1-6]|li|tr|blockquote|section|article)>").replace(s, "$0\n\n")
        val out = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()
        return out.replace("￼", "").replace(' ', ' ')
            .replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n")
    }

    // ---------------------------------------------------------------- RTF

    private fun rtf(s: String): String {
        val skip = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "footnote", "themedata", "datastore", "latentstyles")
        val sb = StringBuilder()
        var i = 0
        var depth = 0
        var skipDepth = -1
        var uc = 1
        val cp = charset("windows-1252")
        while (i < s.length) {
            val c = s[i]
            when (c) {
                '{' -> { depth++; i++ }
                '}' -> {
                    if (skipDepth == depth) skipDepth = -1
                    depth--; i++
                }
                '\\' -> {
                    i++
                    if (i >= s.length) break
                    val d = s[i]
                    if (d == '\\' || d == '{' || d == '}') {
                        if (skipDepth < 0) sb.append(d)
                        i++
                    } else if (d == '\'') {
                        val hex = s.substring(i + 1, minOf(i + 3, s.length))
                        val v = hex.toIntOrNull(16)
                        if (v != null && skipDepth < 0) sb.append(String(byteArrayOf(v.toByte()), cp))
                        i += 3
                    } else if (d == '*') {
                        if (skipDepth < 0) skipDepth = depth
                        i++
                    } else if (d.isLetter()) {
                        var j = i
                        while (j < s.length && s[j].isLetter()) j++
                        val word = s.substring(i, j)
                        var k = j
                        if (k < s.length && (s[k] == '-' || s[k].isDigit())) {
                            k++
                            while (k < s.length && s[k].isDigit()) k++
                        }
                        val num = s.substring(j, k).toIntOrNull()
                        i = k
                        if (i < s.length && s[i] == ' ') i++
                        if (word in skip && skipDepth < 0) skipDepth = depth
                        if (skipDepth < 0) when (word) {
                            "par", "line" -> sb.append('\n')
                            "tab" -> sb.append('\t')
                            "emdash" -> sb.append('—')
                            "endash" -> sb.append('–')
                            "bullet" -> sb.append('•')
                            "lquote" -> sb.append('‘')
                            "rquote" -> sb.append('’')
                            "ldblquote" -> sb.append('„')
                            "rdblquote" -> sb.append('“')
                            "uc" -> uc = num ?: 1
                            "u" -> if (num != null) {
                                sb.append((if (num < 0) num + 65536 else num).toChar())
                                var n = uc
                                while (n > 0 && i < s.length) {   // Ersatzzeichen überspringen
                                    if (s[i] == '\\' && i + 1 < s.length && s[i + 1] == '\'') i += 4 else i++
                                    n--
                                }
                            }
                        }
                    } else {
                        i++
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (skipDepth < 0) sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }
}
