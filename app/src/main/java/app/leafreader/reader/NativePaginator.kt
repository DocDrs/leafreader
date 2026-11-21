package app.leafreader.reader

import android.os.Build
import android.graphics.text.LineBreakConfig
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface

data class ChapterLayout(val layout: StaticLayout, val map: PageMap, val lineTops: IntArray, val lineBottoms: IntArray) {
    fun lineTop(line: Int) = lineTops[line]
    fun lineBottom(line: Int) = lineBottoms[line]
    fun lineBaseline(line: Int) = lineTops[line] + layout.getLineBaseline(line) - layout.getLineTop(line)
}

object NativePaginator {
    fun layout(text: String, paint: TextPaint, width: Int, height: Int, lineSpacing: Float, paragraphGapPx: Float = 18f, styleTitle: Boolean = true): ChapterLayout {
        val styled = SpannableString(text)
        val titleEnd = text.indexOf('\n').let { if (it < 0) text.length else it }
        if(styleTitle) {
            styled.setSpan(RelativeSizeSpan(1.2f), 0, titleEnd, 0)
            styled.setSpan(StyleSpan(Typeface.BOLD), 0, titleEnd, 0)
        }
        val builder = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setLineSpacing(0f, 1f).setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (Build.VERSION.SDK_INT >= 33) builder.setLineBreakConfig(LineBreakConfig.Builder()
            .setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_STRICT).build())
        val layout = builder.build()
        val blank = BooleanArray(layout.lineCount) { line -> text.substring(layout.getLineStart(line), layout.getLineEnd(line)).all { it.isWhitespace() } }
        // Shape the unchanged text once. Vertical positions are separate so line and paragraph gaps
        // cannot accumulate through platform LineHeightSpan font-metric callbacks.
        val tops = IntArray(layout.lineCount); val bottoms = IntArray(layout.lineCount)
        var vertical = 0
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line); val end = layout.getLineEnd(line)
            tops[line] = vertical
            if (blank[line]) {
                bottoms[line] = vertical
                if (start > 0 && text[start - 1] == '\n' && (start < 2 || text[start - 2] != '\n')) vertical += paragraphGapPx.toInt().coerceAtLeast(0)
            } else {
                val natural = layout.getLineBottom(line) - layout.getLineTop(line)
                bottoms[line] = vertical + natural
                vertical += natural
                if (end < text.length && end > start && text[end - 1] != '\n') vertical += (natural * (lineSpacing - 1f)).toInt().coerceAtLeast(0)
            }
        }
        val pages = mutableListOf<TextPage>()
        var first = 0
        while (first < layout.lineCount) {
            val visible = (first until layout.lineCount).firstOrNull { !blank[it] }
            if (visible == null) {
                // Trailing separators belong to the preceding page, never a new blank page.
                if (pages.isNotEmpty()) pages[pages.lastIndex] = pages.last().copy(lastLine = layout.lineCount - 1, end = text.length)
                else pages += TextPage(0, layout.lineCount - 1, 0, text.length)
                break
            }
            val drawFirst: Int = visible
            var lastContent = drawFirst
            var next = drawFirst + 1
            while (next < layout.lineCount) {
                if (!blank[next] && bottoms[next] - tops[drawFirst] > height.coerceAtLeast(1)) break
                if (!blank[next]) lastContent = next
                next++
            }
            val last = next - 1
            pages += TextPage(first, last, layout.getLineStart(first), layout.getLineEnd(last), drawFirst, lastContent)
            first = next
        }
        return ChapterLayout(layout, PageMap(pages), tops, bottoms)
    }
}
