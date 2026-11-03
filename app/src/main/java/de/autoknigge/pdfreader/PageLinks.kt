package de.autoknigge.pdfreader

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import java.io.File

/** Liest die Links (Weblinks) einer PDF aus, damit sie in der Seitenansicht angetippt werden können. */
class PageLinks(private val file: File) {
    /** Rechteck in Bruchteilen der Seite (0..1, von oben links) und die Adresse. */
    class Box(val l: Float, val t: Float, val r: Float, val b: Float, val url: String)

    private var doc: PDDocument? = null
    private val cache = HashMap<Int, List<Box>>()

    /** Aufruf nur von einem Hintergrund-Thread. Fehler führen zu einer leeren Liste. */
    @Synchronized
    fun forPage(index: Int): List<Box> {
        cache[index]?.let { return it }
        val out = ArrayList<Box>()
        try {
            val d = doc ?: PDDocument.load(file, MemoryUsageSetting.setupMixed(48L * 1024 * 1024)).also { doc = it }
            val page = d.getPage(index)
            val box = page.cropBox ?: page.mediaBox
            val w = box.width; val h = box.height
            val rot = ((page.rotation % 360) + 360) % 360
            for (a in page.annotations) {
                if (a !is PDAnnotationLink) continue
                val act = a.action as? PDActionURI ?: continue
                val url = act.uri ?: continue
                val rc = a.rectangle ?: continue
                // Eckpunkte in Bruchteilen der ungedrehten Seite (u nach rechts, v nach oben)
                val us = floatArrayOf((rc.lowerLeftX - box.lowerLeftX) / w, (rc.upperRightX - box.lowerLeftX) / w)
                val vs = floatArrayOf((rc.lowerLeftY - box.lowerLeftY) / h, (rc.upperRightY - box.lowerLeftY) / h)
                var xmin = 1f; var xmax = 0f; var ymin = 1f; var ymax = 0f
                for (u in us) for (v in vs) {
                    val (x, y) = when (rot) {
                        90 -> v to u
                        180 -> (1f - u) to v
                        270 -> (1f - v) to (1f - u)
                        else -> u to (1f - v)
                    }
                    xmin = minOf(xmin, x); xmax = maxOf(xmax, x); ymin = minOf(ymin, y); ymax = maxOf(ymax, y)
                }
                out.add(Box(xmin, ymin, xmax, ymax, url))
            }
        } catch (e: Exception) {
        } catch (e: OutOfMemoryError) {
        }
        cache[index] = out
        return out
    }

    @Synchronized
    fun close() {
        try { doc?.close() } catch (e: Exception) {}
        doc = null
    }
}
