package app.leafreader.data

import android.content.Context
import java.io.File

/** Registry contains namespace IDs only. Books, hidden flags and anchors remain in their original stores. */
@android.annotation.SuppressLint("ApplySharedPref") // Keep namespace registration durable before a source switch.
class LocalShelfIndex(context:Context) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("local-shelf-sources-v1",Context.MODE_PRIVATE)
    private val discovered:Set<String> by lazy {
        File(app.applicationInfo.dataDir,"shared_prefs").listFiles().orEmpty().mapNotNull{file->
            Regex("reader-(bookhub-v1-[a-f0-9]{32})\\.xml(?:\\.bak)?").matchEntire(file.name)?.groupValues?.get(1)
        }.toSet()
    }
    fun remember(id:String) {
        require(id.isNotBlank() && id.none{it=='/' || it=='\\' || it.code<32})
        val old=prefs.getStringSet("sources",emptySet())!!.toSet()
        if(id !in old)prefs.edit().putStringSet("sources",old+id).commit()
    }
    var failedSources:Int=0;private set
    fun sources():List<String> {
        return (prefs.getStringSet("sources",emptySet()).orEmpty()+discovered+RetiredBundledBooks.SOURCE_ID).distinct().sorted()
    }
    fun books():List<Book> {
        failedSources=0
        return sources().flatMap{try{ReadingStore(app,it).peekShelf()}catch(_:Exception){failedSources++;emptyList()}}
            .filterNot(RetiredBundledBooks::contains).distinctBy{it.key}
    }
    val warning:String? get()=if(failedSources>0)"部分本机来源记录暂无法读取，原文件未改动；其余书籍仍可使用。" else null
}
