package app.leafreader.reader

/** Display-only progress. It never changes the independently saved reading anchor. */
data class ReadingProgressSnapshot(
    val chapterPercent: Int?,
    val bookPercent: Int?,
    val bookApproximate: Boolean = false,
) {
    val chapterLabel: String get() = chapterPercent?.let { "本章 $it%" } ?: "本章 —"
    val bookLabel: String get() = bookPercent?.let { if (bookApproximate) "全书约 $it%" else "全书 $it%" } ?: "全书 —"
}

object ReadingProgress {
    /**
     * pageEnd is the current displayed page's half-open end offset in the canonical UTF-16 text.
     * A complete ordered directory is required before making any whole-book claim. Complete local
     * chapter lengths allow exact character weighting; otherwise the chapter-count estimate is marked.
     */
    fun calculate(
        pageEnd: Int,
        chapterLength: Int,
        chapterIndex: Int,
        chapterCount: Int,
        chaptersComplete: Boolean,
        chapterLengths: List<Int>? = null,
    ): ReadingProgressSnapshot {
        if (chapterLength <= 0) return ReadingProgressSnapshot(null, null)
        val end = pageEnd.coerceIn(0, chapterLength)
        val within = end.toDouble() / chapterLength
        val chapter = percent(within)
        if (!chaptersComplete || chapterCount <= 0 || chapterIndex !in 0 until chapterCount) {
            return ReadingProgressSnapshot(chapter, null)
        }
        val weights = chapterLengths?.takeIf {
            it.size == chapterCount && it.all { length -> length >= 0 } &&
                it[chapterIndex] == chapterLength && it.sumOf { length -> length.toLong() } > 0
        }
        if (weights != null) {
            val before = weights.take(chapterIndex).sumOf { it.toLong() }
            val total = weights.sumOf { it.toLong() }
            return ReadingProgressSnapshot(chapter, percent((before + end).toDouble() / total))
        }
        return ReadingProgressSnapshot(chapter, percent((chapterIndex + within) / chapterCount), bookApproximate = true)
    }

    // Do not round an unfinished page up to 100%. The true last-page end is exactly chapterLength.
    private fun percent(ratio: Double): Int = (ratio.coerceIn(0.0, 1.0) * 100).toInt().coerceIn(0, 100)
}
