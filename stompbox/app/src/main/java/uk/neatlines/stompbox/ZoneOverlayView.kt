package uk.neatlines.stompbox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Draws the trigger zone over a FIT_CENTER camera preview and lets the user drag it (one finger
 * inside the box) or resize it (drag the corner handle). The zone is stored in normalised
 * coordinates of the upright image, which is what the analyser expects.
 */
class ZoneOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Normalised (0..1) zone in upright image coordinates. Read from the camera thread. */
    @Volatile var zone = RectF(0.30f, 0.62f, 0.70f, 0.78f)
        private set

    var onZoneChanged: ((RectF) -> Unit)? = null

    /** Detector score relative to its trigger threshold (1.0 = threshold). */
    var level = 0f
        set(value) { field = value; invalidate() }

    var occupied = false
        set(value) { if (field != value) { field = value; invalidate() } }

    private var imageW = 3
    private var imageH = 4
    private val density = resources.displayMetrics.density

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * density; color = Color.WHITE
    }
    private val fillPaint = Paint().apply { color = Color.argb(90, 0, 230, 118) }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val meterBg = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val meterFg = Paint().apply { color = Color.rgb(255, 193, 7) }
    private val meterMark = Paint().apply { color = Color.WHITE; strokeWidth = 2 * density }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 13 * density; setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    private enum class Drag { NONE, MOVE, RESIZE }
    private var drag = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f

    fun setImageSize(width: Int, height: Int) {
        imageW = width; imageH = height; invalidate()
    }

    fun moveZoneTo(r: RectF) {
        zone = RectF(r); invalidate()
    }

    /** Where the image is drawn inside this view (matches PreviewView FIT_CENTER). */
    private fun contentRect(): RectF {
        val scale = minOf(width.toFloat() / imageW, height.toFloat() / imageH)
        val cw = imageW * scale
        val ch = imageH * scale
        val left = (width - cw) / 2
        val top = (height - ch) / 2
        return RectF(left, top, left + cw, top + ch)
    }

    private fun zoneOnScreen(): RectF {
        val c = contentRect()
        val z = zone
        return RectF(
            c.left + z.left * c.width(), c.top + z.top * c.height(),
            c.left + z.right * c.width(), c.top + z.bottom * c.height(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val r = zoneOnScreen()
        if (occupied) canvas.drawRect(r, fillPaint)
        boxPaint.color = if (occupied) Color.rgb(0, 230, 118) else Color.WHITE
        canvas.drawRect(r, boxPaint)
        canvas.drawCircle(r.right, r.bottom, 9 * density, handlePaint)
        canvas.drawText(context.getString(R.string.zone_label), r.left, r.top - 6 * density, textPaint)

        // Level meter under the zone: the white tick is the trigger threshold.
        val mh = 6 * density
        val mt = r.bottom + 8 * density
        canvas.drawRect(r.left, mt, r.right, mt + mh, meterBg)
        val full = 2f // meter spans 0..2x threshold
        val fill = (level / full).coerceIn(0f, 1f)
        canvas.drawRect(r.left, mt, r.left + r.width() * fill, mt + mh, meterFg)
        val tick = r.left + r.width() / full
        canvas.drawLine(tick, mt - 2 * density, tick, mt + mh + 2 * density, meterMark)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = contentRect()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val r = zoneOnScreen()
                drag = when {
                    hypot(e.x - r.right, e.y - r.bottom) < 32 * density -> Drag.RESIZE
                    r.contains(e.x, e.y) -> Drag.MOVE
                    else -> {
                        // Tap outside: centre the zone on the finger.
                        val z = zone
                        val nx = (e.x - c.left) / c.width()
                        val ny = (e.y - c.top) / c.height()
                        update(RectF(z).apply { offset(nx - z.centerX(), ny - z.centerY()) })
                        Drag.MOVE
                    }
                }
                lastX = e.x; lastY = e.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.x - lastX) / c.width()
                val dy = (e.y - lastY) / c.height()
                lastX = e.x; lastY = e.y
                val z = RectF(zone)
                when (drag) {
                    Drag.MOVE -> z.offset(dx, dy)
                    Drag.RESIZE -> {
                        z.right = maxOf(z.left + 0.08f, z.right + dx)
                        z.bottom = maxOf(z.top + 0.04f, z.bottom + dy)
                    }
                    Drag.NONE -> return true
                }
                update(z)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag != Drag.NONE) onZoneChanged?.invoke(RectF(zone))
                drag = Drag.NONE
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun update(z: RectF) {
        // Keep the zone inside the image.
        val w = z.width().coerceAtMost(1f)
        val h = z.height().coerceAtMost(1f)
        val left = z.left.coerceIn(0f, 1f - w)
        val top = z.top.coerceIn(0f, 1f - h)
        zone = RectF(left, top, left + w, top + h)
        invalidate()
    }
}
