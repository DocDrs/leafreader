package app.leafreader.reader

data class TextPage(val firstLine: Int, val lastLine: Int, val start: Int, val end: Int,
    val firstContentLine: Int = firstLine, val lastContentLine: Int = lastLine)

/** Half-open UTF-16 intervals. Never persist a page number: layout is device dependent. */
class PageMap(val pages: List<TextPage>) {
    fun pageAt(offset: Int): Int {
        if (pages.isEmpty()) return 0
        val safe = offset.coerceIn(0, pages.last().end.coerceAtLeast(1) - 1)
        var lo = 0; var hi = pages.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (pages[mid].start <= safe) lo = mid else hi = mid - 1
        }
        return lo
    }
}
