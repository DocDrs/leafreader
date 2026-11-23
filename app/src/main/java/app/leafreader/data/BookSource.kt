package app.leafreader.data

import java.io.IOException

data class Book(val id: String, val title: String, val author: String, val description: String,
                val category: String, val chapterCount: Int, val color: Long, val format: String = "TXT",
                val sourceId: String = "", val readAllowed:Boolean?=null) {
    val key: String get() = if (sourceId.isEmpty()) id else "$sourceId|$id"
}
data class BookPage(val items: List<Book>, val nextCursor: String? = null, val hasMore: Boolean = false)
data class ChapterRef(val id: String, val title: String)
data class InitialReading(val chapter: Chapter, val notice: String? = null)
data class ChapterGraphic(val id: String, val offset: Int, val bitmap: android.graphics.Bitmap, val description: String = "书中图片")
data class Chapter(val id: String, val title: String, val paragraphs: List<String>, val headingOnly: Boolean = false,
    val graphics: List<ChapterGraphic> = emptyList(), val navigationNotice: String? = null) {
    // Stable UTF-16 offsets refer to this exact string, including title and paragraph indentation.
    val text: String get() = if (paragraphs.isEmpty() && graphics.isNotEmpty()) "\uFFFC".repeat(graphics.size)
        else if (headingOnly) paragraphs.joinToString("\n\n") else title + "\n\n" + paragraphs.joinToString("\n\n") { "　　$it" }
}

/** Replace this boundary only after verifying source access and content rights. No guessed endpoints. */
interface BookSource {
    val id: String
    /** Trusted local provider with independently retained reading rights; never inferred from downloaded bytes. */
    val independentOffline:Boolean get()=false
    suspend fun books(): List<Book>
    suspend fun browse(query: String = "", cursor: String? = null): BookPage = BookPage(books().filter {
        query.isBlank() || it.title.contains(query, true) || it.author.contains(query, true)
    })
    suspend fun chapters(bookId: String): List<ChapterRef>
    suspend fun chaptersFor(bookId:String,chapterId:String?):List<ChapterRef> = chapters(bookId)
    suspend fun chapter(bookId: String, chapterId: String): Chapter
    /** Called only for automatic entry with no saved position. Explicit selection and resume never use it. */
    suspend fun initialReading(bookId: String, chapters: List<ChapterRef>): InitialReading =
        InitialReading(chapter(bookId, chapters.first().id))
    /** Reading adjacency can differ from navigation display order; no progress is written here. */
    suspend fun readingNeighbor(bookId: String, chapterId: String, direction: Int, chapters: List<ChapterRef>): String? {
        val index = chapters.indexOfFirst { it.id == chapterId }
        return chapters.getOrNull(index + direction)?.id?.takeIf { index >= 0 }
    }
    /** Only return complete canonical text lengths already available locally; remote sources must not prefetch a book. */
    fun progressIsReliable(bookId:String):Boolean = true
    suspend fun chapterLengths(bookId: String): List<Int>? = null
}

/** The old namespace remains readable; only these exact bundled sample identities are retired. */
object RetiredBundledBooks {
    const val SOURCE_ID = "original-offline-v1"
    private val bookIds = setOf("mountain-letter-v1", "night-log-v1")
    fun contains(book: Book): Boolean = book.sourceId == SOURCE_ID && book.id in bookIds
}

/** Signed-out state, with no catalog, assets or fallback reading content. */
class SignedOutBookSource : BookSource {
    // Keep the namespace used for legacy local preferences; never migrate or clear it.
    override val id = RetiredBundledBooks.SOURCE_ID
    override suspend fun books(): List<Book> = emptyList()
    private fun loginRequired(): Nothing = throw SourceProblem(FailureKind.AUTH, "请先登录 BookHub，再从找书页选择书籍；原阅读进度已保留")
    override suspend fun chapters(bookId: String): List<ChapterRef> = loginRequired()
    override suspend fun chapter(bookId: String, chapterId: String): Chapter = loginRequired()
}

/** Explicitly blocked, never selected or substituted silently. */
class UnverifiedBookHubSource : BookSource {
    override val id = "bookhub-unverified"
    private fun blocked(): Nothing = throw IOException("BookHub 的服务地址、协议与数据许可尚未核验")
    override suspend fun books(): List<Book> = blocked()
    override suspend fun chapters(bookId: String): List<ChapterRef> = blocked()
    override suspend fun chapter(bookId: String, chapterId: String): Chapter = blocked()
}
