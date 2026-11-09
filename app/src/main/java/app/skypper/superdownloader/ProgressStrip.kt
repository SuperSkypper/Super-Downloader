package app.skypper.superdownloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * The progress bar of the status block, hand-drawn.
 *
 * Android's `ProgressBar` did not work: it honors `maxHeight` and the drawable's
 * intrinsic size, so the fill covered neither the height nor the
 * width of the block (the user saw "the bar doesn't fill the whole bar"). Drawing
 * two rounded rectangles here removes the doubt: a track the size of the View,
 * a fill proportional to progress, the same radius as the card.
 */
class ProgressStrip(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val radius = 12f * density

    // Track in the border tone and fill in the accent with alpha: without this the
    // bar at 0% was invisible (surface_variant was nearly the same as the card) and
    // the block looked like an empty rectangle with no purpose.
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.border)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        alpha = 130
    }

    private val rect = RectF()

    /** 0f..1f. */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val r = radius.coerceAtMost(height / 2f)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, trackPaint)

        val filled = width * progress
        if (filled > 0.5f) {
            rect.set(0f, 0f, filled, height.toFloat())
            canvas.drawRoundRect(rect, r, r, fillPaint)
        }
    }
}
