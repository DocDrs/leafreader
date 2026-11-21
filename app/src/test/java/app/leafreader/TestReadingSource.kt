package app.leafreader

import app.leafreader.data.*

/** Synthetic paragraphs for layout/gesture tests only. Never packaged in the application. */
class TestReadingSource : BookSource {
    override val id = "test-only-layout-v1"
    override suspend fun books() = (1..2).map { n ->
        Book("fixture-layout-$n", if (n == 1) "排版测试一" else "排版测试二", "测试", "仅测试注入", "测试", 3, 0xFF516D66)
    }
    override suspend fun chapters(bookId: String) = (1..3).map { ChapterRef("layout-0$it", "排版测试第${it}章") }
    override suspend fun chapter(bookId: String, chapterId: String): Chapter = Chapter(chapterId, "排版测试章节",
        List(25) { i -> "第${i + 1}段：甲乙丙丁，中文标点与段落排版；这一段仅用于验证字号、翻页和阅读位置。🌙".repeat(2) })
    override suspend fun chapterLengths(bookId: String) = chapters(bookId).map { chapter(bookId, it.id).text.length }
}
