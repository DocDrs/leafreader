package app.leafreader.data

import android.content.Context

data class ShelfUpdateRecord(val state:String="NONE",val attemptAt:Long=0,val successAt:Long=0,val count:Int=0,val failure:String="")

data class ShelfOperationRecord(val present:Boolean=true,val state:String="NONE",val at:Long=0,val reason:String="")

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
    /** Progress removal and optional hiding commit atomically before any file cleanup. */
    fun requestCleanup(bookId:String,hide:Boolean=false,remoteHide:Boolean=false,cleanupId:String?=null):Boolean = synchronized(prefs) {
        val edit=prefs.edit().putString("cleanup-pending-v1:$bookId",cleanupId?:java.util.UUID.randomUUID().toString())
            .putLong("$bookId.progress-cleared-v1",System.currentTimeMillis())
        for(key in listOf("chapter","offset","time","graphic","entered"))edit.remove("$bookId.$key")
        for(key in prefs.all.keys)if(key.startsWith("$bookId.history.") || key.startsWith("recovery-$bookId-"))edit.remove(key)
        if(lastBook()==bookId)edit.remove("lastBook")
        if(recentBook()?.id==bookId)edit.remove("recentBook")
        if(cleanupId!=null)edit.putString("$bookId.membership-cleanup-nonce-v3",cleanupId)
        if(hide)edit.putStringSet("shelfHidden-v1",hiddenShelfIds()+bookId)
        if(remoteHide)edit.putStringSet("shelfHidden-v1",hiddenShelfIds()-bookId)
            .putStringSet("shelfRemoteRemoved-v2",remoteRemovedIds()+bookId)
            .putStringSet("shelfRemoteKnown-v2",remoteKnownIds()+bookId)
            .putStringSet("shelfLocalOnly-v2",localOnlyIds()-bookId)
        edit.commit()
    }
    fun membershipCleanupNonce(bookId:String)=prefs.getString("$bookId.membership-cleanup-nonce-v3",null)
    fun cleanupToken(bookId:String):String? = when(val raw=prefs.all["cleanup-pending-v1:$bookId"]){is String->raw;true->"legacy";else->null}
    fun cleanupPending(bookId:String)=cleanupToken(bookId)!=null
    fun pendingCleanups():List<String> = prefs.all.filter{it.key.startsWith("cleanup-pending-v1:") && (it.value is String || it.value==true)}.keys.map{it.removePrefix("cleanup-pending-v1:")}
    fun finishCleanup(bookId:String,expected:String?=cleanupToken(bookId)):Boolean = synchronized(prefs) {
        if(cleanupToken(bookId)==null)true else if(cleanupToken(bookId)!=expected)false else prefs.edit().remove("cleanup-pending-v1:$bookId").commit()
    }
    fun shelfUpdateRecord():ShelfUpdateRecord = runCatching {
        val j=org.json.JSONObject(prefs.getString("shelf-update-v1",null)?:"{}")
        ShelfUpdateRecord(j.optString("state","NONE"),j.optLong("attempt"),j.optLong("success"),j.optInt("count"),j.optString("failure"))
    }.getOrDefault(ShelfUpdateRecord())
    fun saveShelfUpdate(value:ShelfUpdateRecord):Boolean=prefs.edit().putString("shelf-update-v1",org.json.JSONObject()
        .put("state",value.state).put("attempt",value.attemptAt).put("success",value.successAt).put("count",value.count).put("failure",value.failure).toString()).commit()
    fun rememberBook(book: Book) {
        val b = org.json.JSONObject().put("id", book.id).put("title", book.title).put("author", book.author)
            .put("description", book.description).put("category", book.category).put("count", book.chapterCount).put("color", book.color).put("format", book.format).put("sourceId", sourceId).put("readAllowed",book.readAllowed?:org.json.JSONObject.NULL)
        prefs.edit().putString("recentBook", b.toString()).commit()
    }
    fun preserveRecovery(bookId:String) {
        val p=position(bookId)?:return
        val key="recovery-$bookId-${p.updatedAt}"
        if(!prefs.contains(key))prefs.edit().putString(key,org.json.JSONObject().put("chapter",p.chapterId).put("offset",p.offset).put("graphic",p.graphicId).toString()).commit()
    }
    fun preservedPosition(bookId:String):ReadingPosition? = prefs.all.entries.filter{it.key.startsWith("recovery-$bookId-")}.mapNotNull { entry->
        runCatching{val j=org.json.JSONObject(entry.value as String);ReadingPosition(j.getString("chapter"),j.getInt("offset"),entry.key.substringAfterLast('-').toLong(),j.optString("graphic").takeIf{it.isNotBlank() && it!="null"})}.getOrNull()
    }.maxByOrNull{it.updatedAt}
    fun restorePreserved(bookId:String):Boolean {
        val old=preservedPosition(bookId)?:return false
        preserveRecovery(bookId);save(bookId,old.chapterId,old.offset,old.graphicId);return true
    }
    fun recentBook(): Book? = try {
        val b = org.json.JSONObject(prefs.getString("recentBook", null) ?: "{}")
        Book(b.getString("id"), b.getString("title"), b.getString("author"), b.getString("description"), b.getString("category"),
            b.getInt("count"), b.getLong("color"), b.optString("format", "TXT"), sourceId,b.opt("readAllowed") as? Boolean)
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
        .put("description", book.description).put("category", book.category).put("count", book.chapterCount).put("color", book.color).put("format", book.format).put("sourceId", sourceId).put("readAllowed",book.readAllowed?:org.json.JSONObject.NULL)
    private fun decodeBooks(value: String?): List<Book> = try {
        val list = org.json.JSONArray(value ?: "[]")
        (0 until list.length()).map { index ->
            val b = list.getJSONObject(index)
            Book(b.getString("id"), b.getString("title"), b.getString("author"), b.optString("description"), b.optString("category"),
                b.optInt("count"), b.getLong("color"), b.optString("format", "TXT"), sourceId,b.opt("readAllowed") as? Boolean)
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
    fun knownReadPermission(bookId:String):Boolean?=if(prefs.contains("$bookId.readAllowed-v1"))prefs.getBoolean("$bookId.readAllowed-v1",false) else (savedServiceShelf()+localShelf()).firstOrNull{it.id==bookId && it.readAllowed!=null}?.readAllowed
    fun confirmReadPermission(bookId:String,allowed:Boolean) {
        prefs.edit().putBoolean("$bookId.readAllowed-v1",allowed).commit()
        for(key in listOf("serviceShelf","localShelf")) {
            val old=decodeBooks(prefs.getString(key,null));if(old.any{it.id==bookId})writeBooks(key,old.map{if(it.id==bookId)it.copy(readAllowed=allowed)else it})
        }
    }
    fun remoteRemovedIds():Set<String> = prefs.getStringSet("shelfRemoteRemoved-v2",emptySet())!!.toSet()
    fun localOnlyIds():Set<String> = prefs.getStringSet("shelfLocalOnly-v2",emptySet())!!.toSet()
    fun remoteKnownIds():Set<String> = prefs.getStringSet("shelfRemoteKnown-v2",emptySet())!!.toSet()+savedServiceShelf().map{it.id}
    fun allHiddenShelfIds():Set<String> = hiddenShelfIds()+remoteRemovedIds()
    /** Full read reconciliation changes membership metadata only; no progress/content writes. */
    fun acceptRemoteSnapshot(fresh:List<Book>,removed:Set<String>):Boolean = synchronized(prefs) {
        ensureOrder(fresh)
        val present=fresh.map{it.id}.toSet();val merged=(fresh+savedServiceShelf()).distinctBy{it.id}
        val data=org.json.JSONArray();merged.forEach{data.put(encodeBook(it))}
        prefs.edit().putString("serviceShelf",data.toString())
            .putStringSet("shelfRemoteKnown-v2",remoteKnownIds()+present)
            .putStringSet("shelfRemoteRemoved-v2",(remoteRemovedIds()+removed)-present)
            .putStringSet("shelfLocalOnly-v2",localOnlyIds()-present).commit()
    }
    fun shelfChoicePending()=prefs.getBoolean("shelf-choice-pending-v2",false)
    fun requireShelfChoice()=prefs.edit().putBoolean("shelf-choice-pending-v2",true).commit()
    /** Explicit login choice: merge only; preserved local-only IDs are exempt from remote absence. */
    fun acceptShelfChoice(fresh:List<Book>,retained:Set<String>):Boolean = synchronized(prefs) {
        ensureOrder(fresh)
        val present=fresh.map{it.id}.toSet();val array=org.json.JSONArray();(fresh+savedServiceShelf()).distinctBy{it.id}.forEach{array.put(encodeBook(it))}
        prefs.edit().putString("serviceShelf",array.toString())
            .putStringSet("shelfRemoteKnown-v2",remoteKnownIds()+present)
            .putStringSet("shelfRemoteRemoved-v2",remoteRemovedIds()-present)
            .putStringSet("shelfHidden-v1",hiddenShelfIds()-present)
            .putStringSet("shelfLocalOnly-v2",(localOnlyIds()+retained)-present)
            .putBoolean("shelf-choice-pending-v2",false).commit()
    }
    fun recordRemoteAbsent(id:String):Boolean=prefs.edit().putStringSet("shelfRemoteRemoved-v2",remoteRemovedIds()+id)
        .putStringSet("shelfRemoteKnown-v2",remoteKnownIds()+id).commit()
    fun shelfOperation(bookId:String):ShelfOperationRecord = runCatching {
        val j=org.json.JSONObject(prefs.getString("shelf-operation-v2:$bookId",null)?:"{}")
        ShelfOperationRecord(j.optBoolean("present",true),j.optString("state","NONE"),j.optLong("at"),j.optString("reason"))
    }.getOrDefault(ShelfOperationRecord())
    fun saveShelfOperation(bookId:String,value:ShelfOperationRecord):Boolean = prefs.edit().putString("shelf-operation-v2:$bookId",org.json.JSONObject()
        .put("present",value.present).put("state",value.state).put("at",value.at).put("reason",value.reason).toString()).commit()
    fun hiddenShelfIds():Set<String> = prefs.getStringSet("shelfHidden-v1",emptySet())!!.toSet()
    fun hideFromShelf(ids:Set<String>):Boolean = prefs.edit().putStringSet("shelfHidden-v1",hiddenShelfIds()+ids).commit()
    fun addToShelf(book: Book,remoteConfirmed:Boolean=false,localOverride:Boolean=false):Boolean {
        ensureOrder(listOf(book))
        val array=org.json.JSONArray();(listOf(book)+localShelf()).distinctBy{it.id}.forEach{array.put(encodeBook(it))}
        val edit=prefs.edit().putString("localShelf",array.toString()).putStringSet("shelfHidden-v1",hiddenShelfIds()-book.id)
            .putStringSet("shelfRemoteRemoved-v2",remoteRemovedIds()-book.id)
        if(remoteConfirmed)edit.putStringSet("shelfRemoteKnown-v2",remoteKnownIds()+book.id).putStringSet("shelfLocalOnly-v2",localOnlyIds()-book.id)
        else if(localOverride)edit.putStringSet("shelfLocalOnly-v2",localOnlyIds()+book.id)
        return edit.commit()
    }
    private fun ensureOrder(books: List<Book>) {
        var next = prefs.getInt("nextShelfOrder", 0)
        val edit = prefs.edit()
        books.distinctBy { it.id }.forEach { book -> if (!prefs.contains("${book.id}.shelfOrder")) edit.putInt("${book.id}.shelfOrder", next++) }
        edit.putInt("nextShelfOrder", next).commit()
    }
    fun orderedShelf(serviceBooks: List<Book> = savedServiceShelf()): List<Book> {
        val hidden=allHiddenShelfIds()
        val unique = (serviceBooks + localShelf()).distinctBy { it.id }.filterNot{it.id in hidden}
        ensureOrder(unique)
        return unique.sortedWith(compareByDescending<Book> { prefs.contains("${it.id}.chapter") || prefs.contains("${it.id}.entered") }
            .thenByDescending { lastEnteredAt(it.id) }.thenBy { prefs.getInt("${it.id}.shelfOrder", Int.MAX_VALUE) })
    }
    /** Read-only inventory for the unified shelf: do not rewrite legacy data during discovery. */
    fun peekShelf():List<Book> {
        for(key in listOf("serviceShelf","localShelf")) {
            val raw=prefs.getString(key,null)?:continue
            val array=org.json.JSONArray(raw)
            if(decodeBooks(raw).size!=array.length())throw java.io.IOException("本机书架元数据不完整")
        }
        return (savedServiceShelf()+localShelf()).distinctBy{it.id}.filterNot{it.id in allHiddenShelfIds()}
    }
    fun enteredReadOnly(bookId:String):Long = if(prefs.contains("$bookId.entered"))prefs.getLong("$bookId.entered",0).coerceAtLeast(0)
        else if(prefs.contains("$bookId.chapter"))prefs.getLong("$bookId.time",0).coerceAtLeast(0) else 0L
    fun shelfOrder(bookId:String):Int=prefs.getInt("$bookId.shelfOrder",Int.MAX_VALUE)
    fun settings() = ReaderSettings(prefs.getFloat("font", 21f), prefs.getFloat("spacing", 1.55f), prefs.getInt("palette", 0),
        prefs.getFloat("paragraphGap", 18f), prefs.getFloat("marginHorizontal", 26f), prefs.getFloat("marginVertical", 25f)).normalized()
    fun saveSettings(value: ReaderSettings) {
        val s = value.normalized()
        prefs.edit().putFloat("font", s.fontSp).putFloat("spacing", s.lineSpacing).putInt("palette", s.palette)
            .putFloat("paragraphGap", s.paragraphSpacingDp).putFloat("marginHorizontal", s.horizontalMarginDp)
            .putFloat("marginVertical", s.verticalMarginDp).commit()
    }
}
