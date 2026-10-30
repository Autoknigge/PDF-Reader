package de.autoknigge.pdfreader

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException

/** Setzt die gespeicherte Unterschrift in eine PDF und legt das Ergebnis in „Downloads/PDF-Reader“ ab. */
object Signer {
    const val FOLDER = "PDF-Reader"

    class Result(val uri: Uri, val name: String)

    fun sign(
        ctx: Context,
        file: File,
        displayName: String,
        placements: List<Placement>,
        sigFile: File,
        progress: (Int) -> Unit
    ): Result {
        val name = Docs.fileNameFor(displayName).removeSuffix(".pdf") + "_unterschrieben.pdf"
        val bmp = BitmapFactory.decodeFile(sigFile.path)
            ?: throw IOException("Die Unterschrift konnte nicht gelesen werden.")
        val aspect = bmp.height.toFloat() / bmp.width
        val byPage = placements.groupBy { it.page }
        return PDDocument.load(file, "", MemoryUsageSetting.setupMixed(48L * 1024 * 1024)).use { doc ->
            val img = LosslessFactory.createFromImage(doc, bmp)
            var done = 0
            for ((index, list) in byPage) {
                val page = doc.getPage(index)
                val box = page.cropBox
                val w = box.width
                val h = box.height
                val rot = ((page.rotation % 360) + 360) % 360
                val vw = if (rot == 90 || rot == 270) h else w
                val vh = if (rot == 90 || rot == 270) w else h
                val m = when (rot) {
                    90 -> Matrix(0f, 1f, -1f, 0f, w + box.lowerLeftX, box.lowerLeftY)
                    180 -> Matrix(-1f, 0f, 0f, -1f, w + box.lowerLeftX, h + box.lowerLeftY)
                    270 -> Matrix(0f, -1f, 1f, 0f, box.lowerLeftX, h + box.lowerLeftY)
                    else -> Matrix(1f, 0f, 0f, 1f, box.lowerLeftX, box.lowerLeftY)
                }
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.transform(m)
                    for (p in list) {
                        val sw = p.w * vw
                        val sh = sw * aspect
                        cs.drawImage(img, p.cx * vw - sw / 2f, vh - p.cy * vh - sh / 2f, sw, sh)
                    }
                }
                done++
                progress(done)
            }
            val uri = saveToDownloads(ctx, name) { doc.save(it) }
            Result(uri, name)
        }
    }

    private fun saveToDownloads(ctx: Context, fileName: String, block: (java.io.OutputStream) -> Unit): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Die Datei konnte nicht angelegt werden.")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Die Datei konnte nicht geschrieben werden.")
            BufferedOutputStream(stream, 64 * 1024).use { block(it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (t: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (ignored: Exception) {
            }
            throw t
        }
    }
}
