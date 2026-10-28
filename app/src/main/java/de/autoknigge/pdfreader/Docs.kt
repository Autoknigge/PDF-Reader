package de.autoknigge.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/** Eine geöffnete PDF als unverschlüsselte Datei im App-Speicher. */
class PdfSource(
    val file: File,
    val name: String,
    val pages: Int,
    val wasEncrypted: Boolean,
    /** Die Datei genau so, wie sie war (bei Passwortschutz: noch verschlüsselt) – zum Senden und Speichern. */
    val original: File
)

/** Die PDF ist mit einem Passwort geschützt; [wrong] = das eingegebene Passwort stimmte nicht. */
class PasswordNeeded(val name: String, val wrong: Boolean) : Exception()

object Docs {

    private val counter = AtomicLong(System.currentTimeMillis())

    private fun memory(): MemoryUsageSetting = MemoryUsageSetting.setupMixed(48L * 1024 * 1024)

    private fun workDir(ctx: Context) = File(ctx.cacheDir, "lesen").apply { mkdirs() }

    /** Löscht Zwischendateien, die älter als ein Tag sind. */
    fun cleanup(ctx: Context) {
        val limit = System.currentTimeMillis() - 24 * 3600 * 1000L
        workDir(ctx).listFiles()?.forEach { if (it.lastModified() < limit) it.deleteRecursively() }
    }

    /**
     * Kopiert die PDF in den App-Speicher. Ist sie verschlüsselt, wird sie mit [password]
     * geöffnet und ohne Schutz gespeichert, damit der Android-Betrachter sie darstellen kann.
     */
    fun prepare(ctx: Context, uri: Uri, password: String?): PdfSource {
        val name = displayName(ctx, uri)
        val id = counter.incrementAndGet()
        val raw = File(workDir(ctx), "q$id.pdf")
        val input = ctx.contentResolver.openInputStream(uri)
            ?: throw IOException("Die Datei konnte nicht gelesen werden.")
        input.use { inp -> raw.outputStream().use { inp.copyTo(it, 64 * 1024) } }

        // Schnellweg: unverschlüsselte PDF direkt mit dem Android-Betrachter prüfen.
        if (password == null && canRenderDirectly(raw)) {
            val pages = pageCount(raw)
            if (pages > 0) return PdfSource(raw, name, pages, false, raw)
        }

        val doc = try {
            PDDocument.load(raw, password ?: "", memory())
        } catch (e: InvalidPasswordException) {
            raw.delete()
            throw PasswordNeeded(name, password != null)
        } catch (e: IOException) {
            raw.delete()
            throw IOException("„$name“ ist keine gültige PDF-Datei oder beschädigt.")
        } catch (e: OutOfMemoryError) {
            raw.delete()
            throw IOException("„$name“ ist zu groß für dieses Gerät.")
        }

        return doc.use {
            val pages = it.numberOfPages
            if (pages == 0) {
                raw.delete()
                throw IOException("„$name“ enthält keine Seiten.")
            }
            if (!it.isEncrypted) {
                PdfSource(raw, name, pages, false, raw)
            } else {
                it.isAllSecurityToBeRemoved = true
                val plain = File(workDir(ctx), "q${id}_offen.pdf")
                it.save(plain)
                // Das verschlüsselte Original bleibt für „Senden an“ / „Speichern unter“ erhalten.
                PdfSource(plain, name, pages, true, raw)
            }
        }
    }

    private fun canRenderDirectly(file: File): Boolean = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).close()
        }
        true
    } catch (e: Exception) {
        false
    }

    private fun pageCount(file: File): Int = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }
    } catch (e: Exception) {
        0
    }

    /** Dateiname für Senden/Speichern: ungültige Zeichen ersetzen, auf „.pdf“ enden lassen. */
    fun fileNameFor(name: String): String {
        val base = name.trim().replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).trim().ifBlank { "Dokument" }
        return "$base.pdf"
    }

    fun displayName(ctx: Context, uri: Uri): String {
        var name: String? = null
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) name = it.getString(0)
            }
        } catch (e: Exception) {
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment?.substringAfterLast('/')
        return name?.takeIf { it.isNotBlank() } ?: "Dokument.pdf"
    }
}

/** Zeichnet Seiten einer PDF als Bild (Android-eigener PDF-Renderer). Nicht threadsicher. */
class PageRenderer(file: File) : Closeable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(pfd)
    } catch (e: Exception) {
        pfd.close()
        throw IOException("Die PDF lässt sich nicht darstellen.")
    }

    val pageCount: Int get() = renderer.pageCount

    /** Seitenverhältnis Höhe/Breite der Seite. */
    fun aspect(index: Int): Float = renderer.openPage(index).use { p ->
        if (p.width > 0) p.height.toFloat() / p.width else 1.4142f
    }

    /** Zeichnet die Seite auf weißem Grund, die Breite hat [widthPx] Pixel. */
    fun render(index: Int, widthPx: Int): Bitmap = renderer.openPage(index).use { page ->
        val w = widthPx.coerceIn(64, 4000)
        val h = max(1, (w * (if (page.width > 0) page.height.toFloat() / page.width else 1.4142f)).roundToInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bmp
    }

    override fun close() {
        try {
            renderer.close()
        } finally {
            pfd.close()
        }
    }
}
