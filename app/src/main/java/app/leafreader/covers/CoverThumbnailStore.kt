package app.leafreader.covers

import app.leafreader.offline.atomic
import app.leafreader.offline.sha
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Optional bounded thumbnails; never shares the reading-resource index or changes its completeness. */
internal class CoverThumbnailStore(private val root:File) {
    companion object {private val locks=ConcurrentHashMap<String,Any>();const val MAX_BYTES=512*1024;const val DISK_BYTES=16L*1024*1024;const val DISK_ITEMS=96}
    private val lock=locks.getOrPut(root.absolutePath){Any()}
    private fun dir(owner:String,book:String)=File(File(root,sha(owner.toByteArray())),sha(book.toByteArray()))
    private fun name(version:String,epoch:Long)=sha(version.toByteArray())+"-$epoch"
    fun latest(owner:String,book:String,epoch:Long):String?=synchronized(lock){runCatching{JSONObject(File(dir(owner,book),"active.json").readText()).takeIf{it.getLong("epoch")==epoch}?.getString("version")}.getOrNull()}
    fun get(owner:String,book:String,version:String,epoch:Long):ByteArray?=synchronized(lock) {
        val folder=dir(owner,book);val stem=name(version,epoch);val meta=File(folder,"$stem.json");val image=File(folder,"$stem.png")
        runCatching {
            if(!meta.isFile || meta.length()>4096 || !image.isFile || image.length() !in 1..MAX_BYTES)return@synchronized null
            val j=JSONObject(meta.readText());if(j.getString("version")!=version || j.getLong("epoch")!=epoch || j.getString("owner")!=sha(owner.toByteArray()) || j.getString("book")!=sha(book.toByteArray()))return@synchronized null
            val bytes=image.readBytes();if(sha(bytes)!=j.getString("sha256")){image.delete();meta.delete();return@synchronized null}
            image.setLastModified(System.currentTimeMillis());bytes
        }.getOrNull()
    }
    fun put(owner:String,book:String,version:String,epoch:Long,bytes:ByteArray,valid:()->Boolean):Boolean=synchronized(lock) {
        if(bytes.size !in 1..MAX_BYTES || version.isBlank() || version.length>512 || version.any{it.code<32} || !valid())return@synchronized false
        val folder=dir(owner,book);val stem=name(version,epoch);val image=File(folder,"$stem.png");val meta=File(folder,"$stem.json")
        atomic(image,bytes)
        atomic(meta,JSONObject().put("version",version).put("epoch",epoch).put("owner",sha(owner.toByteArray())).put("book",sha(book.toByteArray())).put("sha256",sha(bytes)).toString().toByteArray())
        if(!valid()){image.delete();meta.delete();return@synchronized false}
        atomic(File(folder,"active.json"),JSONObject().put("version",version).put("epoch",epoch).toString().toByteArray());trim();true
    }
    fun clear(owner:String,book:String)=synchronized(lock){val folder=dir(owner,book);folder.deleteRecursively();folder.parentFile?.takeIf{it.listFiles().isNullOrEmpty()}?.delete();Unit}
    private fun trim() {
        val all=root.walkTopDown().maxDepth(3).filter{it.isFile}.toList();val files=all.filter{it.extension=="png"}.sortedBy{it.lastModified()};var total=all.sumOf{it.length()};var count=files.size
        for(f in files) {if(total<=DISK_BYTES && count<=DISK_ITEMS)break;val meta=File(f.parentFile,f.nameWithoutExtension+".json");total-=f.length()+meta.length();count--;f.delete();meta.delete()}
        root.listFiles().orEmpty().filter{it.isDirectory}.forEach{owner->owner.listFiles().orEmpty().filter{it.isDirectory && it.listFiles().orEmpty().none{f->f.extension=="png"}}.forEach{it.deleteRecursively()};if(owner.listFiles().isNullOrEmpty())owner.delete()}
    }
}
