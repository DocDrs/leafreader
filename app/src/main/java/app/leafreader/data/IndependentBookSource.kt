package app.leafreader.data

import android.graphics.Bitmap
import app.leafreader.offline.OfflineStatus

/** Public/owned sources with explicitly confirmed independent retention rights; never a BookHub session. */
interface IndependentBookSource:BookSource {
    fun close() {}
    fun trimDownloadMemory() {}
    val label:String
    val canBrowse:Boolean get()=true
    val supportsAutomaticDownload:Boolean get()=false
    val kindLabel:String get()="公开源 · 无需登录"
    suspend fun details(book:Book):Book=book
    suspend fun chapterStatus(book:String,chapter:String):String=if(status(book).complete)"可离线读"else"未缓存"
    suspend fun thumbnail(book:String):Bitmap?
    suspend fun status(book:String):OfflineStatus
    suspend fun clear(book:String)
    suspend fun download(book:String,allowMetered:Boolean=false)
}
data class CatalogSection(val title:String,val href:String)
