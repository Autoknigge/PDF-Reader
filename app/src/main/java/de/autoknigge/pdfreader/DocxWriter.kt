package de.autoknigge.pdfreader

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Ein Textabschnitt mit gleicher Formatierung. */
class RichRun(val text: String, val bold: Boolean, val italic: Boolean)

/** Ein Absatz: level 0 = normal, 1 = Überschrift, 2 = Unterüberschrift; bullet = Listenpunkt. */
class RichPara(val level: Int, val bullet: Boolean, val runs: List<RichRun>)

/** Schreibt eine einfache, gültige Word-Datei (.docx) aus Absätzen. */
object DocxWriter {

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> if (c >= ' ' || c == '\t') sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun runXml(r: RichRun, sb: StringBuilder) {
        if (r.text.isEmpty()) return
        val parts = r.text.split('\t')
        parts.forEachIndexed { i, part ->
            if (i > 0) sb.append("<w:r><w:tab/></w:r>")
            if (part.isNotEmpty()) {
                sb.append("<w:r>")
                if (r.bold || r.italic) {
                    sb.append("<w:rPr>")
                    if (r.bold) sb.append("<w:b/>")
                    if (r.italic) sb.append("<w:i/>")
                    sb.append("</w:rPr>")
                }
                sb.append("<w:t xml:space=\"preserve\">").append(esc(part)).append("</w:t></w:r>")
            }
        }
    }

    private fun documentXml(paras: List<RichPara>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<w:document xmlns:w=\"").append(W).append("\"><w:body>")
        for (p in paras) {
            sb.append("<w:p>")
            if (p.level > 0) sb.append("<w:pPr><w:pStyle w:val=\"Heading").append(if (p.level == 1) 1 else 2).append("\"/></w:pPr>")
            if (p.bullet) runXml(RichRun("• ", false, false), sb)
            for (r in p.runs) runXml(r, sb)
            sb.append("</w:p>")
        }
        sb.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
        sb.append("<w:pgMar w:top=\"1417\" w:right=\"1417\" w:bottom=\"1134\" w:left=\"1417\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>")
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    private const val STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<w:styles xmlns:w=\"$W\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/>" +
        "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"de-DE\"/></w:rPr></w:rPrDefault>" +
        "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"36\"/><w:szCs w:val=\"36\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"100\"/><w:outlineLvl w:val=\"1\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"28\"/><w:szCs w:val=\"28\"/></w:rPr></w:style>" +
        "</w:styles>"

    private const val CONTENT_TYPES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
        "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
        "</Types>"

    private const val RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>" +
        "</Relationships>"

    private const val DOC_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    private const val APP = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>PDF-Reader</Application></Properties>"

    fun write(paras: List<RichPara>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, content: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", RELS)
            put("word/document.xml", documentXml(paras))
            put("word/_rels/document.xml.rels", DOC_RELS)
            put("word/styles.xml", STYLES)
            put("docProps/app.xml", APP)
        }
        return out.toByteArray()
    }
}
