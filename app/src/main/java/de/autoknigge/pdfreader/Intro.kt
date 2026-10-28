package de.autoknigge.pdfreader

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog

/** Hinweis beim ersten Start, vor der Zustimmungsabfrage und der Werbung. */
object Intro {
    private const val PREFS = "pdfreader"
    private const val KEY_SEEN = "intro_seen"

    fun run(activity: Activity, then: () -> Unit) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEN, false)) {
            then()
            return
        }
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_intro, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<View>(R.id.btnIntroOk).setOnClickListener {
            prefs.edit().putBoolean(KEY_SEEN, true).apply()
            dialog.dismiss()
            then()
        }
        dialog.show()
    }
}
