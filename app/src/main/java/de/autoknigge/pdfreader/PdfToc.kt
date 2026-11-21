package de.autoknigge.pdfreader

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File

/** Inhaltsverzeichnis (Lesezeichen-Baum) einer PDF. */
object PdfToc {
    class Item(val title: String, val level: Int, val page: Int)

    fun load(file: File): List<Item> = try {
        PDDocument.load(file, "", MemoryUsageSetting.setupMixed(48L * 1024 * 1024)).use { doc ->
            val outline = doc.documentCatalog.documentOutline
            val out = ArrayList<Item>()
            fun walk(first: PDOutlineItem?, level: Int) {
                var it = first
                while (it != null && out.size < 600) {
                    val page = try {
                        val pg = it.findDestinationPage(doc)
                        if (pg == null) -1 else doc.pages.indexOf(pg)
                    } catch (e: Exception) {
                        -1
                    }
                    val title = (it.title ?: "").trim()
                    if (page >= 0 && title.isNotEmpty()) out.add(Item(title, level, page))
                    walk(it.firstChild, level + 1)
                    it = it.nextSibling
                }
            }
            if (outline != null) walk(outline.firstChild, 0)
            out
        }
    } catch (e: Throwable) {
        emptyList()
    }
}
