package app.leafreader.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

internal data class FooterPlacement(val leftText: String, val timeText: String, val leftX: Float,
    val timeX: Float, val baseline: Float, val textSize: Float, val inset: Float,
    val battery: RectF, val batteryPercent: Int?)

/** Visual-only footer geometry, independent of adjustable body margins. */
internal class ReaderFooter {
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    fun draw(canvas: Canvas, width: Int, height: Int, footerTop: Float, density: Float,
             fontPx: Float, cornerRadius: Float, left: String, status: ReadingStatusSnapshot,
             text: Paint, color: Int): FooterPlacement {
        val inset = min(max(30*density, cornerRadius + 4*density), width*.28f)
        val available = (width - inset*2).coerceAtLeast(1f)
        text.textSize = min(fontPx, (height-footerTop)/1.6f); text.color = color
        val measured = text.measureText(left) + text.measureText(status.timeLabel) + 36*density
        val scale = min(1f, available/measured.coerceAtLeast(1f))
        text.textSize *= scale
        val metrics = text.fontMetrics
        val centerY = (height+footerTop)/2
        val baseline = centerY - (metrics.descent+metrics.ascent)/2
        val iconWidth = 18*density*scale; val iconHeight = 9*density*scale
        val gap = 6*density*scale
        val icon = RectF(width-inset-iconWidth, centerY-iconHeight/2, width-inset, centerY+iconHeight/2)
        val timeX = icon.left-gap-text.measureText(status.timeLabel)
        canvas.drawText(left, inset, baseline, text)
        canvas.drawText(status.timeLabel, timeX, baseline, text)
        val percent = status.batteryLabel.removeSuffix("%").toIntOrNull()?.takeIf { it in 0..100 }
        drawBattery(canvas, icon, density*scale, percent, color)
        return FooterPlacement(left, status.timeLabel, inset, timeX, baseline, text.textSize, inset, icon, percent)
    }
    private fun drawBattery(canvas: Canvas, box: RectF, unit: Float, percent: Int?, color: Int) {
        outline.color = color; outline.style = Paint.Style.STROKE; outline.strokeWidth = max(.2f, unit)
        fill.color = color; fill.style = Paint.Style.FILL
        val body = RectF(box.left, box.top, box.right-2*unit, box.bottom)
        canvas.drawRoundRect(body, 1.5f*unit, 1.5f*unit, outline)
        canvas.drawRoundRect(RectF(body.right+unit, box.top+box.height()*.3f, box.right, box.bottom-box.height()*.3f), unit*.5f, unit*.5f, fill)
        val inside = RectF(body).apply { inset(2*unit, 2*unit) }
        if (percent != null && percent > 0) {
            inside.right = inside.left+inside.width()*percent/100f
            canvas.drawRect(inside, fill)
        } else if (percent == null) {
            canvas.drawLine(inside.left, inside.bottom, inside.right, inside.top, outline)
        }
    }
}
