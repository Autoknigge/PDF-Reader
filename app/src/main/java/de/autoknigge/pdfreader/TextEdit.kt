package de.autoknigge.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlin.math.max
import kotlin.math.min

/**
 * Ein Text, der in die PDF geschrieben wird (neu oder als Korrektur).
 * [size] in Punkt (1 pt = 1/72 Zoll; Fließtext der Analysen: 11,5 pt), [pad] Rand um den Text in Punkt,
 * [cover]: ein Feld in [bg] (Hintergrundfarbe der Seite an dieser Stelle) deckt den alten Text ab.
 */
class TextSpec(
    val text: String,
    val size: Float,
    val comic: Boolean,
    val bold: Boolean,
    val color: Int,
    val cover: Boolean,
    val pad: Float,
    val bg: Int
) {
    fun with(size: Float = this.size, bg: Int = this.bg) = TextSpec(text, size, comic, bold, color, cover, pad, bg)
    val lines: List<String> get() = text.split("\n")
    val blank: Boolean get() = text.isBlank()
}

object TextEdit {
    /** Zeilenhöhe und Grundlinie als Vielfaches der Schriftgröße (Vorschau und PDF verwenden dieselben Werte). */
    const val LINE = 1.25f
    const val BASE = 0.95f

    val COLORS: List<Pair<String, Int>> = listOf(
        "Schwarz" to 0xFF111111.toInt(),
        "Dunkelblau" to 0xFF10205E.toInt(),
        "Dunkelgrün" to 0xFF1F5A3D.toInt(),
        "Rot" to 0xFFB00020.toInt(),
        "Weiß" to 0xFFFFFFFF.toInt()
    )

    private val faces = HashMap<String, Typeface>()

    fun typeface(ctx: Context, comic: Boolean, bold: Boolean): Typeface = synchronized(faces) {
        faces.getOrPut("$comic$bold") {
            val style = if (bold) Typeface.BOLD else Typeface.NORMAL
            if (comic) {
                try {
                    Typeface.createFromAsset(ctx.assets, if (bold) "fonts/ComicNeue-Bold.ttf" else "fonts/ComicNeue-Regular.ttf")
                } catch (e: Exception) {
                    Typeface.create(Typeface.SANS_SERIF, style)
                }
            } else {
                Typeface.create(Typeface.SANS_SERIF, style)
            }
        }
    }

    /** Nur Zeichen, die in beiden Schriften (Comic Neue, Helvetica) sicher vorhanden sind. */
    fun clean(t: String): String {
        val sb = StringBuilder()
        for (ch in t.replace("\r", "")) {
            val c = ch.code
            when {
                ch == '\n' -> sb.append(ch)
                ch == '\t' -> sb.append(' ')
                c in 32..126 || c in 160..255 -> sb.append(ch)
                "€„“”‚‘’–—…•".indexOf(ch) >= 0 -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun paint(ctx: Context, s: TextSpec, textSize: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = s.color
        this.textSize = textSize
        typeface = typeface(ctx, s.comic, s.bold)
    }

    fun boxWidth(ctx: Context, s: TextSpec): Float {
        val p = paint(ctx, s, s.size)
        val w = if (s.blank) s.size * 8f else s.lines.maxOf { p.measureText(it) }
        return w + 2f * s.pad
    }

    fun boxHeight(s: TextSpec): Float = s.lines.size * s.size * LINE + 2f * s.pad

    /** Vorschau-Bild des Textes und seine Breite als Bruchteil der Seitenbreite [pageWidthPt]. */
    fun render(ctx: Context, s: TextSpec, pageWidthPt: Float): Pair<Bitmap, Float> {
        val bw = boxWidth(ctx, s)
        val bh = boxHeight(s)
        val k = min(6f, 3000f / max(bw, bh))
        val bmp = Bitmap.createBitmap(max(2, (bw * k).toInt()), max(2, (bh * k).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (s.cover) c.drawColor(s.bg or 0xFF000000.toInt())
        if (!s.blank) {
            val p = paint(ctx, s, s.size * k)
            s.lines.forEachIndexed { i, l ->
                c.drawText(l, s.pad * k, (s.pad + i * s.size * LINE + s.size * BASE) * k, p)
            }
        }
        return bmp to (bw / pageWidthPt)
    }

    /** Schriften für eine PDF (Comic Neue wird als Teilmenge eingebettet). */
    class Fonts(private val ctx: Context, private val doc: PDDocument) {
        private val map = HashMap<String, PDFont>()
        fun get(comic: Boolean, bold: Boolean): PDFont = map.getOrPut("$comic$bold") {
            if (comic) {
                ctx.assets.open(if (bold) "fonts/ComicNeue-Bold.ttf" else "fonts/ComicNeue-Regular.ttf").use {
                    PDType0Font.load(doc, it, true)
                }
            } else {
                if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
            }
        }
    }

    /**
     * Schreibt den Text echt (markierbar) in den Inhalt der Seite. Mittelpunkt ([cx], [cy]) als Bruchteil der
     * sichtbaren Seite mit Breite [vw] und Höhe [vh] in Punkt; der Seitenursprung ist unten links.
     */
    fun draw(cs: PDPageContentStream, fonts: Fonts, s: TextSpec, cx: Float, cy: Float, vw: Float, vh: Float) {
        val font = fonts.get(s.comic, s.bold)
        val lines = s.lines
        val tw = if (s.blank) s.size * 8f else lines.maxOf { font.getStringWidth(it) / 1000f * s.size }
        val bw = tw + 2f * s.pad
        val bh = boxHeight(s)
        val left = cx * vw - bw / 2f
        val top = vh - cy * vh + bh / 2f
        if (s.cover) {
            cs.setNonStrokingColor(Color.red(s.bg), Color.green(s.bg), Color.blue(s.bg))
            cs.addRect(left, top - bh, bw, bh)
            cs.fill()
        }
        if (s.blank) return
        cs.setNonStrokingColor(Color.red(s.color), Color.green(s.color), Color.blue(s.color))
        lines.forEachIndexed { i, l ->
            if (l.isNotEmpty()) {
                cs.beginText()
                cs.setFont(font, s.size)
                cs.newLineAtOffset(left + s.pad, top - s.pad - i * s.size * LINE - s.size * BASE)
                cs.showText(l)
                cs.endText()
            }
        }
    }
}
