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
    private const val MAX_ENTRY = 8 * 1024 * 1024
    private const val MAX_TEXT = 6_000_000

    enum class Kind { TEXT, DOCX, ODT, EPUB, HTML, RTF }

    fun kindOf(name: String, mime: String?): Kind {
        val n = name.lowercase()
        return when {
            n.endsWith(".docx") -> Kind.DOCX
            n.endsWith(".odt") -> Kind.ODT
            n.endsWith(".epub") -> Kind.EPUB
            n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".xhtml") -> Kind.HTML
            n.endsWith(".rtf") -> Kind.RTF
            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> Kind.DOCX
            mime == "application/vnd.oasis.opendocument.text" -> Kind.ODT
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
                    low.endsWith(".opf") || low.endsWith(".ncx")
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
