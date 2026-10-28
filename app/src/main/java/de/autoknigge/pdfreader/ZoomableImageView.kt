package de.autoknigge.pdfreader

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/**
 * Eine Seite im Lesebildschirm: Doppeltippen zoomt, zwei Finger ziehen zoomt stufenlos,
 * ein Finger verschiebt im gezoomten Zustand. Ohne Zoom gibt die Ansicht das Wischen an
 * den seitlichen Seitenwechsel weiter.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val matrixValues = FloatArray(9)
    private val baseMatrix = Matrix()
    private val drawMatrix = Matrix()
    private var minScale = 1f
    private val maxScale = 5f
    private var fitted = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val current = currentScale()
            var factor = d.scaleFactor
            if (current * factor < minScale) factor = minScale / current
            if (current * factor > maxScale) factor = maxScale / current
            drawMatrix.postScale(factor, factor, d.focusX, d.focusY)
            clampAndApply()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (currentScale() > minScale * 1.3f) minScale else minScale * 2.6f
            val f = target / currentScale()
            drawMatrix.postScale(f, f, e.x, e.y)
            clampAndApply()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (currentScale() <= minScale * 1.02f) return false
            drawMatrix.postTranslate(-dx, -dy)
            clampAndApply()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        super.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            // Parent (ViewPager) nur dann wischen lassen, wenn wir am Rand oder ungezoomt sind.
            val zoomed = currentScale() > minScale * 1.02f
            parent?.requestDisallowInterceptTouchEvent(zoomed && !atHorizontalEdge(event))
            true
        }
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        fitted = false
        if (bm != null) post { fitToView() }
    }

    private fun fitToView() {
        val d = drawable ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val iw = d.intrinsicWidth.toFloat(); val ih = d.intrinsicHeight.toFloat()
        if (iw <= 0 || ih <= 0) return
        baseMatrix.reset()
        val scale = min(vw / iw, vh / ih)
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate((vw - iw * scale) / 2f, (vh - ih * scale) / 2f)
        drawMatrix.set(baseMatrix)
        minScale = 1f
        imageMatrix = drawMatrix
        fitted = true
    }

    private fun currentScale(): Float {
        drawMatrix.getValues(matrixValues)
        val sx = matrixValues[Matrix.MSCALE_X]
        baseMatrix.getValues(matrixValues)
        val base = matrixValues[Matrix.MSCALE_X]
        return if (base != 0f) sx / base else 1f
    }

    private fun imageRect(): RectF {
        val d = drawable ?: return RectF()
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(r)
        return r
    }

    private fun atHorizontalEdge(event: MotionEvent): Boolean {
        val r = imageRect()
        // Am linken oder rechten Rand: Seitenwechsel zulassen.
        return r.left >= -1f || r.right <= width + 1f
    }

    private fun clampAndApply() {
        val r = imageRect()
        var dx = 0f; var dy = 0f
        val vw = width.toFloat(); val vh = height.toFloat()
        if (r.width() <= vw) dx = (vw - r.width()) / 2f - r.left
        else {
            if (r.left > 0) dx = -r.left
            if (r.right < vw) dx = vw - r.right
        }
        if (r.height() <= vh) dy = (vh - r.height()) / 2f - r.top
        else {
            if (r.top > 0) dy = -r.top
            if (r.bottom < vh) dy = vh - r.bottom
        }
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (drawable != null) fitToView()
    }

    /** Zurück auf Seitengröße (z. B. beim Seitenwechsel). */
    fun resetZoom() {
        if (fitted) {
            drawMatrix.set(baseMatrix)
            imageMatrix = drawMatrix
        }
    }
}
