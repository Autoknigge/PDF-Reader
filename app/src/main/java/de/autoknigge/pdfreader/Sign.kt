package de.autoknigge.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Eine Unterschrift auf einer Seite: Mittelpunkt und Breite als Bruchteil der (sichtbaren) Seite. */
class Placement(val page: Int, var cx: Float, var cy: Float, var w: Float, val bmp: Bitmap)

/** Gespeicherte Unterschriften (nur auf dem Gerät, als PNG mit transparentem Hintergrund). */
object SignatureStore {
    private const val MAX = 6
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("signatur", Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.filesDir, "unterschriften").apply { mkdirs() }

    /** Alle gespeicherten Unterschriften, die neueste zuerst. */
    fun list(ctx: Context): List<File> {
        val old = File(ctx.filesDir, "unterschrift.png")
        if (old.exists()) {
            // frühere Version: eine einzige Unterschrift übernehmen
            old.copyTo(File(dir(ctx), "sig_${old.lastModified()}.png"), overwrite = true)
            old.delete()
        }
        return dir(ctx).listFiles { f -> f.extension == "png" }?.sortedByDescending { it.name } ?: emptyList()
    }

    fun add(ctx: Context, bmp: Bitmap): File {
        val f = File(dir(ctx), "sig_${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        list(ctx).drop(MAX).forEach { it.delete() }
        setCurrent(ctx, f)
        return f
    }

    fun delete(ctx: Context, f: File) {
        f.delete()
        if (prefs(ctx).getString("aktuell", null) == f.name) prefs(ctx).edit().remove("aktuell").apply()
    }

    fun current(ctx: Context): File? {
        val name = prefs(ctx).getString("aktuell", null)
        val all = list(ctx)
        return all.firstOrNull { it.name == name } ?: all.firstOrNull()
    }

    fun setCurrent(ctx: Context, f: File) = prefs(ctx).edit().putString("aktuell", f.name).apply()
    fun load(f: File): Bitmap? = BitmapFactory.decodeFile(f.path)
    fun place(ctx: Context): String = prefs(ctx).getString("ort", "") ?: ""
    fun setPlace(ctx: Context, v: String) = prefs(ctx).edit().putString("ort", v).apply()
}

/** Bildverarbeitung: Foto einer Unterschrift freistellen, Text (Datum, Ort) als Bild. */
object SignatureImage {
    const val BLUE = 0xFF10205E.toInt()
    const val BLACK = 0xFF111111.toInt()

    fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val m = max(src.width, src.height)
        if (m <= maxSide) return src
        val f = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(src, max(1, (src.width * f).toInt()), max(1, (src.height * f).toInt()), true)
    }

    fun rotate(src: Bitmap, deg: Float): Bitmap =
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(deg) }, true)

    /**
     * Macht aus einem Foto (dunkle Schrift auf hellem Papier) eine freigestellte Unterschrift.
     * [sensitivity] 0..100: höher = auch hellere Striche bleiben erhalten.
     */
    fun fromPhoto(src: Bitmap, sensitivity: Int, ink: Int): Bitmap? {
        val bmp = scaleDown(src, 1400)
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h)
        val hist = IntArray(256)
        for (i in px.indices) {
            val p = px[i]
            val l = ((Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000)
            lum[i] = l
            hist[l]++
        }
        // Papierhelligkeit: 85. Perzentil
        var acc = 0
        var bg = 255
        val target = (lum.size * 0.85).toInt()
        for (v in 0..255) { acc += hist[v]; if (acc >= target) { bg = v; break } }
        val t = bg * (0.55f + 0.40f * sensitivity / 100f)
        val out = IntArray(w * h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        val r = Color.red(ink); val g = Color.green(ink); val b = Color.blue(ink)
        for (y in 0 until h) for (x in 0 until w) {
            val l = lum[y * w + x]
            if (l >= t) continue
            val a = min(1f, (t - l) / (t * 0.5f))
            val alpha = (a * 255).toInt()
            out[y * w + x] = Color.argb(alpha, r, g, b)
            if (alpha > 60) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return null
        val pad = max(6, ((maxX - minX) * 0.03f).toInt())
        val x0 = max(0, minX - pad); val y0 = max(0, minY - pad)
        val x1 = min(w - 1, maxX + pad); val y1 = min(h - 1, maxY + pad)
        val cw = x1 - x0 + 1; val ch = y1 - y0 + 1
        val res = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        res.setPixels(out, y0 * w + x0, w, 0, 0, cw, ch)
        return res
    }

    /** Text (zum Beispiel „Cappeln, 31.10.2026“) als freigestelltes Bild. */
    fun textBitmap(text: String, ink: Int): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = 72f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)
        }
        val w = max(40, p.measureText(text).toInt() + 24)
        val fm = p.fontMetrics
        val h = (fm.descent - fm.ascent).toInt() + 20
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, 12f, 10f - fm.ascent, p)
        return bmp
    }
}

/** Zeichenfläche für die Unterschrift mit dem Finger. */
class SignaturePad(ctx: Context) : View(ctx) {
    private val strokes = ArrayList<Path>()
    private var current: Path? = null
    private var lastX = 0f
    private var lastY = 0f
    private val density = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3.2f * density
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x55000000
        strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        textSize = 14 * density
        textAlign = Paint.Align.CENTER
    }

    var ink: Int = 0xFF10205E.toInt()
        set(v) {
            field = v
            invalidate()
        }

    val isEmpty get() = strokes.isEmpty()

    fun clear() {
        strokes.clear()
        current = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val y = height * 0.72f
        canvas.drawLine(width * 0.06f, y, width * 0.94f, y, guide)
        if (strokes.isEmpty()) canvas.drawText("Hier mit dem Finger unterschreiben", width / 2f, height * 0.5f, hint)
        paint.color = ink
        for (p in strokes) canvas.drawPath(p, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val p = Path().apply { moveTo(e.x, e.y); lineTo(e.x + 0.1f, e.y + 0.1f) }
                strokes.add(p)
                current = p
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val p = current ?: return true
                for (i in 0 until e.historySize) segment(p, e.getHistoricalX(i), e.getHistoricalY(i))
                segment(p, e.x, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> current = null
        }
        invalidate()
        return true
    }

    /** Glättet die Linie über Zwischenpunkte. */
    private fun segment(p: Path, x: Float, y: Float) {
        p.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f)
        lastX = x
        lastY = y
    }

    /** Die Unterschrift als zugeschnittenes Bild mit transparentem Hintergrund (null, wenn nichts gezeichnet wurde). */
    fun toBitmap(): Bitmap? {
        if (strokes.isEmpty()) return null
        val b = RectF()
        var first = true
        val tmp = RectF()
        for (p in strokes) {
            p.computeBounds(tmp, true)
            if (first) b.set(tmp) else b.union(tmp)
            first = false
        }
        val margin = paint.strokeWidth * 2f
        b.inset(-margin, -margin)
        val scale = (900f / max(b.width(), 1f)).coerceIn(0.6f, 3f)
        val w = max(8, (b.width() * scale).toInt())
        val h = max(8, (b.height() * scale).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val m = Matrix().apply {
            postTranslate(-b.left, -b.top)
            postScale(scale, scale)
        }
        val ink = Paint(paint).apply {
            color = this@SignaturePad.ink
            strokeWidth = paint.strokeWidth * scale
        }
        for (p in strokes) {
            val q = Path(p)
            q.transform(m)
            c.drawPath(q, ink)
        }
        return bmp
    }
}

/** Zeigt die Seite und die Unterschriften darauf; verschieben mit einem, vergrößern mit zwei Fingern. */
class PlaceView(ctx: Context) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private var page: Bitmap? = null
    var pageIndex = 0
    var placements: MutableList<Placement> = ArrayList()
    var selected: Placement? = null
        set(v) {
            field = v
            onSelect?.invoke(v)
            invalidate()
        }
    var onSelect: ((Placement?) -> Unit)? = null
    var emptyText = ""
    private val dest = RectF()
    private val rect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFD4AF37.toInt()
        strokeWidth = 2 * density
        pathEffect = DashPathEffect(floatArrayOf(10 * density, 6 * density), 0f)
    }
    private val msg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCFC38E.toInt()
        textSize = 16 * density
        textAlign = Paint.Align.CENTER
    }
    private var scaling = false
    private var lastX = 0f
    private var lastY = 0f
    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            scaling = selected != null
            return scaling
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            val s = selected ?: return false
            s.w = (s.w * d.scaleFactor).coerceIn(0.08f, 0.9f)
            onResize?.invoke(s)
            invalidate()
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            scaling = false
        }
    })
    var onResize: ((Placement) -> Unit)? = null

    fun setPage(bmp: Bitmap?, index: Int) {
        page = bmp
        pageIndex = index
        invalidate()
    }

    private fun layoutPage() {
        val b = page ?: return
        val m = 10 * density
        val aw = width - 2 * m
        val ah = height - 2 * m
        val s = min(aw / b.width, ah / b.height)
        val w = b.width * s
        val h = b.height * s
        dest.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    private fun boxOf(p: Placement, out: RectF): RectF {
        val s = p.bmp
        val w = p.w * dest.width()
        val h = w * s.height / s.width
        val x = dest.left + p.cx * dest.width()
        val y = dest.top + p.cy * dest.height()
        return out.apply { set(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f) }
    }

    override fun onDraw(canvas: Canvas) {
        val b = page
        if (b == null) {
            val lines = emptyText.split("\n")
            lines.forEachIndexed { i, t -> canvas.drawText(t, width / 2f, height / 2f + (i - (lines.size - 1) / 2f) * 24 * density, msg) }
            return
        }
        layoutPage()
        paint.color = 0x66000000
        canvas.drawRect(dest.left + 3 * density, dest.top + 4 * density, dest.right + 3 * density, dest.bottom + 4 * density, paint)
        canvas.drawBitmap(b, null, dest, null)
        for (p in placements) {
            if (p.page != pageIndex) continue
            boxOf(p, rect)
            canvas.drawBitmap(p.bmp, null, rect, null)
            if (p === selected) {
                val r = RectF(rect)
                r.inset(-5 * density, -5 * density)
                canvas.drawRect(r, border)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (page == null) return false
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                val slop = 10 * density
                selected = placements.lastOrNull { p ->
                    p.page == pageIndex && boxOf(p, rect).also { it.inset(-slop, -slop) }.contains(e.x, e.y)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val s = selected
                if (s != null && !scaling && e.pointerCount == 1) {
                    s.cx = (s.cx + (e.x - lastX) / dest.width()).coerceIn(0.02f, 0.98f)
                    s.cy = (s.cy + (e.y - lastY) / dest.height()).coerceIn(0.02f, 0.98f)
                    invalidate()
                }
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // nach dem Zoomen nicht springen: Position des verbleibenden Fingers übernehmen
                val i = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(i)
                lastY = e.getY(i)
            }
        }
        return true
    }
}
