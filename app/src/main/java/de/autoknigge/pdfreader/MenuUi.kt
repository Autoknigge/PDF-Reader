package de.autoknigge.pdfreader

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** Auswahlmenü im gleichen Stil wie im PDF-Bildschirm: Symbol links, große Schrift; Zeilen mit − und +. */
object MenuUi {

    class Entry(
        val icon: String,
        val text: String,
        /** Beschriftung, die sich nach − oder + ändert (z. B. „Schrift 16“). */
        val label: (() -> String)? = null,
        val minus: (() -> Unit)? = null,
        val plus: (() -> Unit)? = null,
        /** Wird beim Tippen auf die Zeile ausgeführt (als letzter Parameter, damit { … } hier landet). */
        val onClick: (() -> Unit)? = null
    )

    fun show(ctx: Context, title: String, entries: List<Entry>, closeLabel: String = "Schließen", checked: Int = -1, keepOpen: Boolean = false) {
        var sel = checked
        val d = ctx.resources.displayMetrics.density
        lateinit var dialog: AlertDialog
        val list = ListView(ctx).apply {
            divider = null
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(i: Int) = entries[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, parent: ViewGroup): View {
                val e = entries[i]
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = (56 * d).toInt()
                    setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
                }
                row.addView(TextView(ctx).apply {
                    text = e.icon
                    textSize = 24f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                row.addView(TextView(ctx).apply {
                    text = e.label?.invoke() ?: e.text
                    textSize = 19f
                    setTextColor(ctx.getColor(R.color.text_hell))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (i == sel) {
                    row.setBackgroundColor(0x33D4AF37)
                    row.addView(TextView(ctx).apply {
                        text = "✓"
                        textSize = 22f
                        setTextColor(ctx.getColor(R.color.gold))
                    })
                }
                if (e.minus != null && e.plus != null) {
                    fun btn(t: String, action: () -> Unit) = TextView(ctx).apply {
                        text = t
                        textSize = 26f
                        gravity = Gravity.CENTER
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(ctx.getColor(R.color.gold))
                        setBackgroundResource(R.drawable.bg_box_dark)
                        isClickable = true
                        setOnClickListener { action(); notifyDataSetChanged() }
                        layoutParams = LinearLayout.LayoutParams((56 * d).toInt(), (44 * d).toInt()).apply { marginStart = (8 * d).toInt() }
                    }
                    row.addView(btn("−", e.minus))
                    row.addView(btn("+", e.plus))
                }
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, which, _ ->
            val e = entries[which]
            if (e.minus != null) return@setOnItemClickListener
            if (keepOpen) {
                sel = which
                adapter.notifyDataSetChanged()
                e.onClick?.invoke()
            } else {
                dialog.dismiss()
                e.onClick?.invoke()
            }
        }
        val titleView = TextView(ctx).apply {
            text = title
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.getColor(R.color.gold))
            setPadding((20 * d).toInt(), (18 * d).toInt(), (20 * d).toInt(), (6 * d).toInt())
        }
        dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(titleView)
            .setView(list)
            .setNegativeButton(closeLabel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.textSize = 17f
    }
}
