package app.leafreader.data

import android.content.Context

data class ReadingPosition(val chapterId: String, val offset: Int, val updatedAt: Long = System.currentTimeMillis(), val graphicId: String? = null)
data class ReaderSettings(val fontSp: Float = 21f, val lineSpacing: Float = 1.55f, val palette: Int = 0,
    val paragraphSpacingDp: Float = 18f, val horizontalMarginDp: Float = 26f, val verticalMarginDp: Float = 25f) {
    fun normalized(): ReaderSettings {
        fun safe(value: Float, default: Float, range: ClosedFloatingPointRange<Float>) = if (value.isFinite()) value.coerceIn(range.start, range.endInclusive) else default
        return copy(fontSp = safe(fontSp, 21f, 16f..32f), lineSpacing = safe(lineSpacing, 1.55f, 1.2f..2f), palette = palette.coerceIn(0, 4),
            paragraphSpacingDp = safe(paragraphSpacingDp, 18f, 0f..48f), horizontalMarginDp = safe(horizontalMarginDp, 26f, 8f..48f),
            verticalMarginDp = safe(verticalMarginDp, 25f, 0f..64f))
    }
    fun sameLayoutAs(other: ReaderSettings) = fontSp == other.fontSp && lineSpacing == other.lineSpacing &&
        paragraphSpacingDp == other.paragraphSpacingDp && horizontalMarginDp == other.horizontalMarginDp && verticalMarginDp == other.verticalMarginDp
}

@android.annotation.SuppressLint("ApplySharedPref") // Page progress must survive an immediate process stop.
class ReadingStore(context: Context, val sourceId: String) {
    private val prefs = context.getSharedPreferences("reader-$sourceId", Context.MODE_PRIVATE)
    fun position(bookId: String): ReadingPosition? {
        val chapter = prefs.getString("$bookId.chapter", null) ?: return null
        return ReadingPosition(chapter, prefs.getInt("$bookId.offset", 0).coerceAtLeast(0), prefs.getLong("$bookId.time", 0), prefs.getString("$bookId.graphic", null))
    }
    fun save(bookId: String, chapterId: String, offset: Int, graphicId: String? = null) {
        // Small atomic write: it must survive immediate process termination after a page turn.
        prefs.edit().putString("$bookId.chapter", chapterId).putInt("$bookId.offset", offset.coerceAtLeast(0))
            .putString("$bookId.graphic", graphicId)
            .putInt("$bookId.history.$chapterId", offset.coerceAtLeast(0))
            .putLong("$bookId.time", System.currentTimeMillis()).putString("lastBook", bookId).commit()
    }
    fun rememberBook(book: Book) {
        val b = org.json.JSONObject().put("id", book.id).put("title", book.title).put("author", book.author)
            .put("description", book.description).put("category", book.category).put("count", book.chapterCount).put("color", book.color).put("format", book.format).put("sourceId", sourceId)
        prefs.edit().putString("recentBook", b.toString()).commit()
    }
    fun recentBook(): Book? = try {
        val b = org.json.JSONObject(prefs.getString("recentBook", null) ?: "{}")
        Book(b.getString("id"), b.getString("title"), b.getString("author"), b.getString("description"), b.getString("category"),
            b.getInt("count"), b.getLong("color"), b.optString("format", "TXT"), sourceId)
    } catch (_: Exception) { null }
    fun lastBook(): String? = prefs.getString("lastBook", null)
    /** The old time key is evidence of reading in 0.2; migrate it once, never invent an unread timestamp. */
    fun lastEnteredAt(bookId: String): Long {
        val key = "$bookId.entered"
        if (prefs.contains(key)) return prefs.getLong(key, 0).coerceAtLeast(0)
        val old = if (prefs.contains("$bookId.chapter")) prefs.getLong("$bookId.time", 0).coerceAtLeast(0) else 0
        if (old > 0) prefs.edit().putLong(key, old).commit()
        return old
    }
    fun markEntered(book: Book, at: Long) {
        rememberBook(book)
        prefs.edit().putLong("${book.id}.entered", at.coerceAtLeast(0)).commit()
    }
    private fun encodeBook(book: Book) = org.json.JSONObject().put("id", book.id).put("title", book.title).put("author", book.author)
        .put("description", book.description).put("category", book.category).put("count", book.chapterCount).put("color", book.color).put("format", book.format).put("sourceId", sourceId)
    private fun decodeBooks(value: String?): List<Book> = try {
        val list = org.json.JSONArray(value ?: "[]")
        (0 until list.length()).map { index ->
            val b = list.getJSONObject(index)
            Book(b.getString("id"), b.getString("title"), b.getString("author"), b.optString("description"), b.optString("category"),
                b.optInt("count"), b.getLong("color"), b.optString("format", "TXT"), sourceId)
        }
    } catch (_: Exception) { emptyList() }
    private fun writeBooks(key: String, books: List<Book>) {
        val array = org.json.JSONArray(); books.distinctBy { it.id }.forEach { array.put(encodeBook(it)) }
        prefs.edit().putString(key, array.toString()).commit()
    }
    fun rememberServiceShelf(books: List<Book>) {
        ensureOrder(books)
        writeBooks("serviceShelf", books)
    }
    fun savedServiceShelf(): List<Book> = decodeBooks(prefs.getString("serviceShelf", null))
    fun localShelf(): List<Book> = decodeBooks(prefs.getString("localShelf", null))
    /** Local visibility only; serviceShelf/localShelf and all reading/download records remain intact. */
    fun hiddenShelfIds():Set<String> = prefs.getStringSet("shelfHidden-v1",emptySet())!!.toSet()
    fun hideFromShelf(ids:Set<String>):Boolean = prefs.edit().putStringSet("shelfHidden-v1",hiddenShelfIds()+ids).commit()
    fun addToShelf(book: Book):Boolean {
        ensureOrder(listOf(book))
        val array=org.json.JSONArray();(localShelf()+book).distinctBy{it.id}.forEach{array.put(encodeBook(it))}
        return prefs.edit().putString("localShelf",array.toString()).putStringSet("shelfHidden-v1",hiddenShelfIds()-book.id).commit()
    }
    private fun ensureOrder(books: List<Book>) {
        var next = prefs.getInt("nextShelfOrder", 0)
        val edit = prefs.edit()
        books.distinctBy { it.id }.forEach { book -> if (!prefs.contains("${book.id}.shelfOrder")) edit.putInt("${book.id}.shelfOrder", next++) }
        edit.putInt("nextShelfOrder", next).commit()
    }
    fun orderedShelf(serviceBooks: List<Book> = savedServiceShelf()): List<Book> {
        val hidden=hiddenShelfIds()
        val unique = (serviceBooks + localShelf()).distinctBy { it.id }.filterNot{it.id in hidden}
        ensureOrder(unique)
        return unique.sortedWith(compareByDescending<Book> { prefs.contains("${it.id}.chapter") || prefs.contains("${it.id}.entered") }
            .thenByDescending { lastEnteredAt(it.id) }.thenBy { prefs.getInt("${it.id}.shelfOrder", Int.MAX_VALUE) })
    }
    fun settings() = ReaderSettings(prefs.getFloat("font", 21f), prefs.getFloat("spacing", 1.55f), prefs.getInt("palette", 0),
        prefs.getFloat("paragraphGap", 18f), prefs.getFloat("marginHorizontal", 26f), prefs.getFloat("marginVertical", 25f)).normalized()
    fun saveSettings(value: ReaderSettings) {
        val s = value.normalized()
        prefs.edit().putFloat("font", s.fontSp).putFloat("spacing", s.lineSpacing).putInt("palette", s.palette)
            .putFloat("paragraphGap", s.paragraphSpacingDp).putFloat("marginHorizontal", s.horizontalMarginDp)
            .putFloat("marginVertical", s.verticalMarginDp).commit()
    }
}
