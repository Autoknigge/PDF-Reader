package de.autoknigge.pdfreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Ein zuletzt geöffnetes Dokument mit der Seite, auf der man aufgehört hat (0-basiert). */
class RecentDoc(val uri: String, val name: String, val page: Int, val pages: Int)

/**
 * Merkt sich zuletzt geöffnete PDFs, die Lese-Position und Lesezeichen – nur auf dem Gerät
 * (Einstellungsdatei der App). Von den Dateien selbst wird keine Kopie angelegt.
 */
class Library(context: Context) {

    private val prefs = context.getSharedPreferences("pdfreader_lib", Context.MODE_PRIVATE)

    companion object {
        private const val MAX_RECENT = 6
    }

    fun recents(): List<RecentDoc> = try {
        val arr = JSONArray(prefs.getString("recent", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RecentDoc(o.getString("uri"), o.getString("name"), o.optInt("page", 0), o.optInt("pages", 0))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveRecents(list: List<RecentDoc>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("uri", it.uri).put("name", it.name).put("page", it.page).put("pages", it.pages)
            )
        }
        prefs.edit().putString("recent", arr.toString()).apply()
    }

    /** Das Dokument steht ab jetzt ganz oben; die gemerkte Seite bleibt erhalten. */
    fun touch(uri: String, name: String, pages: Int) {
        val old = recents()
        val page = old.firstOrNull { it.uri == uri }?.page ?: 0
        val list = listOf(RecentDoc(uri, name, page.coerceIn(0, (pages - 1).coerceAtLeast(0)), pages)) +
            old.filter { it.uri != uri }
        saveRecents(list.take(MAX_RECENT))
    }

    fun page(uri: String): Int = recents().firstOrNull { it.uri == uri }?.page ?: 0

    fun setPage(uri: String, page: Int) {
        val list = recents()
        if (list.none { it.uri == uri }) return
        saveRecents(list.map { if (it.uri == uri) RecentDoc(it.uri, it.name, page, it.pages) else it })
    }

    fun removeRecent(uri: String) = saveRecents(recents().filter { it.uri != uri })

    // ---------- Lesezeichen ----------

    fun bookmarks(uri: String): List<Int> = try {
        val arr = JSONObject(prefs.getString("marks", "{}")).optJSONArray(uri) ?: JSONArray()
        (0 until arr.length()).map { arr.getInt(it) }.sorted()
    } catch (e: Exception) {
        emptyList()
    }

    fun isBookmarked(uri: String, page: Int) = page in bookmarks(uri)

    /** Setzt oder entfernt das Lesezeichen; gibt zurück, ob die Seite jetzt ein Lesezeichen hat. */
    fun toggleBookmark(uri: String, page: Int): Boolean {
        val all = try {
            JSONObject(prefs.getString("marks", "{}"))
        } catch (e: Exception) {
            JSONObject()
        }
        val set = bookmarks(uri).toMutableSet()
        val now = if (page in set) {
            set.remove(page)
            false
        } else {
            set.add(page)
            true
        }
        all.put(uri, JSONArray(set.sorted()))
        prefs.edit().putString("marks", all.toString()).apply()
        return now
    }
}
