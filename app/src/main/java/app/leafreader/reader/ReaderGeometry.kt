package app.leafreader.reader

import app.leafreader.data.ReaderSettings
import kotlin.math.max
import kotlin.math.min

/** One shared rectangle for both pagination and drawing. Preferences remain unchanged when a tiny viewport adapts. */
data class ReaderGeometry(val left: Float, val top: Float, val right: Float, val bottom: Float,
    val fontPx: Float, val paragraphGapPx: Float, val showChrome: Boolean, val footerTop: Float) {
    val textWidth: Int get() = (right - left).toInt().coerceAtLeast(1)
    val textHeight: Int get() = (bottom - top).toInt().coerceAtLeast(1)

    companion object {
        fun calculate(width: Int, height: Int, density: Float, requestedFontPx: Float, settings: ReaderSettings, statusFontPx: Float = 11 * density): ReaderGeometry {
            val w = width.coerceAtLeast(1).toFloat(); val h = height.coerceAtLeast(1).toFloat()
            val minWidth = min(w, max(24 * density, requestedFontPx * 2))
            val footer = min(max(27 * density, statusFontPx * 1.6f + 8 * density), h * .35f)
            val minHeight = min(h - footer, requestedFontPx * 2.8f)
            val compactHeader = max(22*density, statusFontPx*1.5f + 6*density)
            val chrome = h >= minHeight + compactHeader + footer
            val header = if (chrome) compactHeader else 0f
            val x = min(settings.horizontalMarginDp * density, max(0f, (w - minWidth) / 2))
            val y = min(settings.verticalMarginDp * density, max(0f, (h - header - footer - minHeight) / 2))
            val contentWidth = max(1f, w - x * 2)
            val contentHeight = max(1f, h - header - footer - y * 2)
            val font = min(requestedFontPx, min(contentWidth / 1.3f, contentHeight / 2.8f)).coerceAtLeast(.3f)
            return ReaderGeometry(x, header + y, w - x, h - footer - y, font,
                min(settings.paragraphSpacingDp * density, contentHeight * .35f), chrome, h - footer)
        }
    }
}
