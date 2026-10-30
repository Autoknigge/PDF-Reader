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
class Placement(val page: Int, var cx: Float, var cy: Float, var w: Float)

/** Die gespeicherte Unterschrift (nur auf dem Gerät, als PNG mit transparentem Hintergrund). */
object SignatureStore {
    fun file(ctx: Context) = File(ctx.filesDir, "unterschrift.png")

    fun load(ctx: Context): Bitmap? = file(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun save(ctx: Context, bmp: Bitmap) {
        file(ctx).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
    var sig: Bitmap? = null
        set(v) {
            field = v
            invalidate()
        }
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
        val s = sig ?: return out.apply { setEmpty() }
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
        val s = sig ?: return
        for (p in placements) {
            if (p.page != pageIndex) continue
            boxOf(p, rect)
            canvas.drawBitmap(s, null, rect, null)
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
