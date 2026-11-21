package de.autoknigge.pdfreader

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Notizen und gesammelte Zitate zu einer Datei. Eine Textdatei je Dokument, im App-Speicher. */
object Notes {
    fun key(id: String): String =
        MessageDigest.getInstance("SHA-1").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun file(ctx: Context, key: String): File {
        val dir = File(ctx.filesDir, "pdfnotizen").apply { mkdirs() }
        return File(dir, key.filter { it.isLetterOrDigit() }.ifEmpty { "x" } + ".txt")
    }

    private fun clean(t: String): String =
        t.replace(Regex("(?<=\\p{Ll})-\\s*\\n\\s*(?=\\p{Ll})"), "")
            .replace(Regex("\\s*\\n\\s*"), " ")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()

    /** Hängt ein Zitat mit Quelle (und Seite) an die Notizen der Datei an. */
    fun addQuote(ctx: Context, key: String, text: String, title: String, page: Int? = null): Boolean = try {
        val q = clean(text)
        if (q.isEmpty()) {
            false
        } else {
            val f = file(ctx, key)
            val old = if (f.exists()) f.readText(Charsets.UTF_8) else ""
            val src = title + (if (page != null) ", S. " + page else "")
            val add = "„" + q + "“\n— " + src + "\n"
            f.writeText((if (old.isBlank()) "" else old.trimEnd() + "\n\n") + add, Charsets.UTF_8)
            true
        }
    } catch (e: Exception) {
        false
    }
}
