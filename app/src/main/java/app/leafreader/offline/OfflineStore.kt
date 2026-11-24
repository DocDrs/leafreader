package app.leafreader.offline

import app.leafreader.data.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
internal fun sha(file:File):String {
    val hash=MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)} }
    return hash.digest().joinToString(""){"%02x".format(it)}
}
internal fun atomic(file:File,bytes:ByteArray) {
    file.parentFile!!.mkdirs();val temp=File(file.parentFile,file.name+".part")
    try { FileOutputStream(temp).use { it.write(bytes);it.fd.sync() }
        Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    } finally { temp.delete() }
}
data class OfflineDiskUsage(val totalBytes:Long=0,val bytesByBook:Map<String,Long> = emptyMap(),val formats:Map<String,String> = emptyMap(),val payloadBooks:Set<String> = emptySet())
data class OfflineStatus(val saved:Int=0,val total:Int=0,val complete:Boolean=false) {
    val label:String get()=if(complete) "整本已下载" else if(saved>0) "部分离线" else "未下载"
}
/** App-private/no-backup storage. Identity, version, manifest and each resource are checked independently. */
@android.annotation.SuppressLint("UsableSpace") // Conservative free bytes; never evict unrelated/system data to make room.
class OfflineStore(private val root:File,val owner:String,private val freeSpace:()->Long={root.usableSpace}) {
    companion object {private val locks=ConcurrentHashMap<String,Any>();private val temporaryFiles=ConcurrentHashMap.newKeySet<String>()}
    private val lock=locks.getOrPut(root.absolutePath){Any()}
    private fun owner(write:Boolean=false):Boolean {
        val marker=File(root,"owner")
        if(marker.isFile) {
            if(marker.readText()!=sha(owner.toByteArray()))throw SourceProblem(FailureKind.AUTH,"离线内容不属于当前账号",diagnostic="BH_OFFLINE_OWNER")
            return true
        }
        if(!write)return false
        if(root.walkTopDown().any{it.isFile && (it.name=="manifest.json" || it.name=="index.json")})throw SourceProblem(FailureKind.AUTH,"离线内容身份无法确认",diagnostic="BH_OFFLINE_OWNER")
        atomic(marker,sha(owner.toByteArray()).toByteArray());return true
    }
    private fun book(id:String)=File(root,sha(id.toByteArray()))
    private fun version(id:String,v:String)=File(book(id),sha(v.toByteArray()))
    private fun json(f:File):JSONObject?=try {if(f.isFile && f.length()<=8*1024*1024)JSONObject(f.readText()) else null}catch(_:Exception){null}
    private fun rawGeneration(id:String)=File(book(id),"epoch").takeIf{it.isFile}?.readText()?.toLongOrNull()?:0L
    /** Finish an interrupted cancellation before exposing cache metadata. Numeric epochs remain compatible. */
    private fun recoverCancellation(id:String) {
        val journal=File(book(id),"cancel-journal.json");if(!journal.isFile)return
        owner(true) // Recovery writes must never run against another account's directory.
        val pending=json(journal)?:throw IOException("离线任务状态暂无法恢复，请重试；原文件保留")
        val from=pending.optLong("from",-1);val to=pending.optLong("to",-1)
        if(from<0 || to<=from || to-from!=1L)throw IOException("离线任务状态暂无法恢复，请重试；原文件保留")
        val current=rawGeneration(id)
        if(current==from || current==to) {
            // Existing committed files survive; only their metadata receives the new write generation.
            for(dir in book(id).listFiles().orEmpty().filter{it.isDirectory}) {
                for((name,key) in listOf("manifest.json" to "_cacheEpoch","index.json" to "_epoch")) {
                    val file=File(dir,name);val data=json(file)?:continue
                    if(data.optLong(key,-1)==from)atomic(file,data.put(key,to).toString().toByteArray())
                }
            }
            atomic(File(book(id),"epoch"),to.toString().toByteArray())
        }
        journal.delete()
    }
    /** Totals use stat only; resource bytes are never read or hashed for the Settings overview. */
    fun totalDiskBytes():Long=synchronized(lock) {
        if(!owner()) {
            if(root.exists() && root.walkTopDown().any{it.isFile})throw IOException("离线内容身份无法确认，未显示占用统计")
            return@synchronized 0L
        }
        var total=0L
        Files.walk(root.toPath()).use {stream->stream.forEach {path->
            try {
                val attr=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
                if(attr.isRegularFile)total=Math.addExact(total,attr.size().coerceAtLeast(0))
            }catch(_:java.nio.file.NoSuchFileException){/* Concurrent temporary file completion. */}
        }}
        total
    }
    /** Focused cleanup inventory: only explicitly hidden book directories, stat only. */
    fun diskUsageForBooks(ids:Set<String>):OfflineDiskUsage=synchronized(lock) {
        if(!owner()) {
            if(root.exists() && root.walkTopDown().any{it.isFile})throw IOException("离线内容身份无法确认，未列出清理项目")
            return@synchronized OfflineDiskUsage()
        }
        val bytes=mutableMapOf<String,Long>();val payload=mutableSetOf<String>();var total=0L
        for(id in ids) {
            val dir=book(id).toPath()
            if(!Files.isDirectory(dir,java.nio.file.LinkOption.NOFOLLOW_LINKS))continue
            var size=0L
            Files.walk(dir).use{stream->stream.forEach{path->
                try {
                    val attr=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    if(attr.isRegularFile) {
                        size=Math.addExact(size,attr.size().coerceAtLeast(0))
                        if(path.fileName.toString() !in setOf("epoch","cancel-journal.json"))payload+=id
                    }
                }catch(_:java.nio.file.NoSuchFileException){/* Concurrent temporary file completion. */}
            }}
            bytes[id]=size;total=Math.addExact(total,size)
        }
        OfflineDiskUsage(total,bytes,payloadBooks=payload)
    }
    /** Logical file sizes in this exact owner directory; never traverse another owner or follow links. */
    fun diskUsage(knownIds:Set<String> = emptySet()):OfflineDiskUsage=synchronized(lock) {
        if(!owner()) {
            if(root.exists() && root.walkTopDown().any{it.isFile})throw IOException("离线内容身份无法确认，未显示占用统计")
            return@synchronized OfflineDiskUsage()
        }
        val rootPath=root.toPath().toAbsolutePath().normalize()
        val ids=knownIds.associateBy{sha(it.toByteArray())}.toMutableMap()
        val formats=mutableMapOf<String,String>()
        val files=mutableListOf<Pair<java.nio.file.Path,Long>>()
        java.nio.file.Files.walk(rootPath).use { stream->stream.forEach { path->
            try {
                val attr=java.nio.file.Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
                if(attr.isRegularFile)files+=path to attr.size().coerceAtLeast(0)
            }catch(_:java.nio.file.NoSuchFileException){/* A completed temporary file may disappear during the snapshot. */}
        }}
        for((path,_) in files) {
            val rel=rootPath.relativize(path)
            if(rel.nameCount==3 && path.fileName.toString()=="manifest.json") {
                val data=json(path.toFile())?:continue
                val id=data.optString("bookId")
                if(Regex("^(public:[0-9]+|(?:private|shared):[a-fA-F0-9-]{36})$").matches(id) && sha(id.toByteArray())==rel.getName(0).toString()) {
                    ids[rel.getName(0).toString()]=id
                    data.optString("format").takeIf{it in listOf("TXT","EPUB")}?.let{formats[id]=it}
                }
            }
        }
        val bytes=mutableMapOf<String,Long>();val payload=mutableSetOf<String>();var total=0L
        for((path,size) in files) {
            total=Math.addExact(total,size)
            val rel=rootPath.relativize(path);val id=ids[rel.getName(0).toString()]?:continue
            bytes[id]=Math.addExact(bytes[id]?:0L,size)
            if(path.fileName.toString() !in setOf("epoch","cancel-journal.json"))payload+=id
        }
        OfflineDiskUsage(total,bytes,formats,payload)
    }
    fun generation(id:String):Long=synchronized(lock){recoverCancellation(id);rawGeneration(id)}
    fun isCurrent(id:String,epoch:Long)=generation(id)==epoch
    private fun checkSpace(size:Long) {root.mkdirs();if(freeSpace()<size+1024*1024)throw IOException("本机存储空间不足，请释放空间后重试")}
    fun requireSpace(size:Long)=synchronized(lock){checkSpace(size)}
    fun saveManifest(id:String,data:JSONObject,epoch:Long):Boolean=synchronized(lock) {
        if(!isCurrent(id,epoch))return@synchronized false
        owner(true)
        val v=data.getString("contentVersion");val safe=JSONObject(sanitize(data)).put("_cacheEpoch",epoch).toString()
        val previous=json(File(version(id,v),"manifest.json"))?.takeIf{it.optLong("_cacheEpoch",-1)==epoch}
        if(previous==null && version(id,v).exists())version(id,v).deleteRecursively()
        if(previous!=null && identity(previous)!=identity(JSONObject(safe)))throw SourceProblem(FailureKind.VERSION,"服务清单变化但内容版本未更新，旧离线副本已保留",diagnostic="BH_OFFLINE_VERSION")
        checkSpace(safe.length.toLong());atomic(File(version(id,v),"manifest.json"),safe.toByteArray())
        atomic(File(book(id),"active.json"),JSONObject().put("version",v).toString().toByteArray());true
    }
    fun manifest(id:String,chapterId:String?=null):JSONObject?=synchronized(lock) {
        if(!owner())return@synchronized null
        val active=json(File(book(id),"active.json"))?.optString("version")
        val epoch=generation(id)
        val candidates=book(id).listFiles().orEmpty().filter{it.isDirectory}.mapNotNull{dir->
            val data=json(File(dir,"manifest.json"))
            if(data?.optLong("_cacheEpoch",-1)!=epoch){dir.deleteRecursively();null}else data
        }
        val selected=if(chapterId!=null) candidates.firstOrNull { data->
            chapterId.split(':').getOrNull(1)==sha(data.optString("contentVersion").toByteArray()).take(16)
        } else null
        (selected?:candidates.firstOrNull{it.optString("contentVersion")==active})?.takeIf{it.optString("bookId")==id}
    }
    private fun index(id:String,v:String):JSONObject {owner(true);val epoch=generation(id);return json(File(version(id,v),"index.json"))?.takeIf{it.optLong("_epoch",-1)==epoch}?:JSONObject().put("items",JSONObject()).put("complete",false).put("_epoch",epoch)}
    fun resource(id:String,v:String,rid:String,maxBytes:Long=64L*1024*1024):ByteArray?=synchronized(lock) {
        val idx=index(id,v);val entry=idx.getJSONObject("items").optJSONObject(rid)?:return@synchronized null
        val file=File(version(id,v),sha(rid.toByteArray())+".bin")
        if(file.isFile && file.length()==entry.optLong("size") && file.length()>maxBytes)
            throw SourceProblem(FailureKind.UNSUPPORTED,"该离线资源超过本版阅读大小限制，副本和进度已保留",diagnostic="BH_OFFLINE_RESOURCE_LIMIT")
        if(!file.isFile || file.length()!=entry.optLong("size") || file.length()>64*1024*1024 || sha(file)!=entry.optString("sha256")) {
            idx.getJSONObject("items").remove(rid);idx.put("complete",false);atomic(File(version(id,v),"index.json"),idx.toString().toByteArray())
            OfflineEvents.changed()
            throw SourceProblem(FailureKind.NETWORK,"离线副本缺失或损坏，请重新下载；阅读进度已保留",diagnostic="BH_OFFLINE_CORRUPT")
        }
        file.readBytes()
    }
    fun portable(id:String,v:String,rid:String):Boolean=synchronized(lock){index(id,v).getJSONObject("items").optJSONObject(rid)?.optBoolean("portable",false)?:false}
    fun put(id:String,v:String,rid:String,bytes:ByteArray,epoch:Long,portable:Boolean=false):Boolean=synchronized(lock) {
        if(!isCurrent(id,epoch))return@synchronized false
        if(bytes.size>64*1024*1024)throw IOException("单份离线资源过大")
        checkSpace(bytes.size.toLong());val dir=version(id,v);atomic(File(dir,sha(rid.toByteArray())+".bin"),bytes)
        val idx=index(id,v);idx.getJSONObject("items").put(rid,JSONObject().put("size",bytes.size).put("sha256",sha(bytes)).put("portable",portable))
        atomic(File(dir,"index.json"),idx.toString().toByteArray());true
    }
    fun acceptFile(id:String,v:String,rid:String,file:File,epoch:Long,portable:Boolean):Boolean=synchronized(lock) {
        if(!isCurrent(id,epoch))return@synchronized false
        if(!file.isFile || file.length()>64*1024*1024)throw IOException("离线资源长度无效")
        val length=file.length();val hash=sha(file);val dir=version(id,v);dir.mkdirs()
        Files.move(file.toPath(),File(dir,sha(rid.toByteArray())+".bin").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        val idx=index(id,v);idx.getJSONObject("items").put(rid,JSONObject().put("size",length).put("sha256",hash).put("portable",portable))
        atomic(File(dir,"index.json"),idx.toString().toByteArray());true
    }
    fun evict(id:String,v:String,rid:String,epoch:Long?=null)=synchronized(lock) {
        if(epoch!=null && !isCurrent(id,epoch))return@synchronized
        val idx=index(id,v);idx.getJSONObject("items").remove(rid);idx.put("complete",false)
        File(version(id,v),sha(rid.toByteArray())+".bin").delete();atomic(File(version(id,v),"index.json"),idx.toString().toByteArray())
    }
    fun complete(id:String,data:JSONObject,epoch:Long):Boolean=synchronized(lock) {
        if(!isCurrent(id,epoch))return@synchronized false
        val v=data.getString("contentVersion");val array=data.getJSONArray("resources")
        for(i in 0 until array.length()) if(!hasResource(id,v,array.getJSONObject(i).getString("resourceId")))return@synchronized false
        val idx=index(id,v).put("complete",true);atomic(File(version(id,v),"index.json"),idx.toString().toByteArray());true
    }
    fun hasResource(id:String,v:String,rid:String):Boolean=synchronized(lock) {
        val entry=index(id,v).getJSONObject("items").optJSONObject(rid)?:return@synchronized false
        val file=File(version(id,v),sha(rid.toByteArray())+".bin")
        file.isFile && file.length()==entry.optLong("size") && sha(file)==entry.optString("sha256")
    }
    fun matches(id:String,v:String,rid:String,expected:String,size:Long):Boolean=synchronized(lock) {
        val entry=index(id,v).getJSONObject("items").optJSONObject(rid)?:return@synchronized false
        entry.optString("sha256")==expected && entry.optLong("size")==size && hasResource(id,v,rid)
    }
    fun status(id:String,verify:Boolean=false,chapterId:String?=null):OfflineStatus=synchronized(lock) {
        val manifest=manifest(id,chapterId)?:return@synchronized OfflineStatus();val v=manifest.getString("contentVersion")
        val total=manifest.getJSONArray("resources").length();val items=index(id,v).getJSONObject("items")
        val saved=(0 until manifest.getJSONArray("resources").length()).count { n->
            val rid=manifest.getJSONArray("resources").getJSONObject(n).getString("resourceId")
            val entry=items.optJSONObject(rid);val file=File(version(id,v),sha(rid.toByteArray())+".bin")
            entry!=null && file.isFile && file.length()==entry.optLong("size") && (!verify || sha(file)==entry.optString("sha256"))
        }
        val complete=index(id,v).optBoolean("complete")
        if(verify && complete && saved!=total) {val idx=index(id,v).put("complete",false);atomic(File(version(id,v),"index.json"),idx.toString().toByteArray());OfflineEvents.changed()}
        OfflineStatus(saved,total,saved==total && total>0 && complete)
    }
    fun clear(id:String)=synchronized(lock) {
        owner(true)
        val epoch=generation(id)+1;book(id).mkdirs();atomic(File(book(id),"epoch"),epoch.toString().toByteArray())
        book(id).listFiles().orEmpty().filter{it.name!="epoch"}.forEach { entry ->
            // Never follow a link out of this exact book directory; deletion errors remain actionable.
            Files.walk(entry.toPath()).use { stream ->
                stream.sorted(java.util.Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
            }
        }
    }
    fun cancelWrites(id:String)=synchronized(lock) {
        owner(true);book(id).mkdirs();val from=generation(id)
        if(from==Long.MAX_VALUE)throw IOException("离线任务状态暂无法更新，请重试")
        atomic(File(book(id),"cancel-journal.json"),JSONObject().put("from",from).put("to",from+1).toString().toByteArray())
        recoverCancellation(id)
    }
    fun temporary(id:String,name:String):File=synchronized(lock) {
        book(id).mkdirs()
        book(id).listFiles().orEmpty().filter{it.name.startsWith("tmp-") && it.name.endsWith(".part") && it.absolutePath !in temporaryFiles}.forEach{it.delete()}
        File(book(id),"tmp-${sha(name.toByteArray())}-${java.util.UUID.randomUUID()}.part").also{temporaryFiles.add(it.absolutePath)}
    }
    fun finishTemporary(file:File){temporaryFiles.remove(file.absolutePath);file.delete()}
    private fun identity(data:JSONObject):String {
        val copy=JSONObject(data.toString());copy.remove("expiresAt");copy.remove("_cacheEpoch")
        copy.optJSONArray("resources")?.let { array->copy.put("resources",JSONArray((0 until array.length()).map{array.getJSONObject(it)}.sortedBy{it.optString("resourceId")})) }
        copy.optJSONObject("metadata")?.let { metadata->
            metadata.optJSONArray("segments")?.let { array->metadata.put("segments",JSONArray((0 until array.length()).map{array.getJSONObject(it)}.sortedBy{it.optString("resourceId")})) }
            metadata.optJSONArray("readingSegments")?.let { array->for(i in 0 until array.length())array.optJSONObject(i)?.optJSONArray("anchors")?.let{anchors->array.getJSONObject(i).put("anchors",JSONArray((0 until anchors.length()).map{anchors.getString(it)}.distinct().sorted()))} }
        }
        fun canonical(v:Any?):String=when(v){is JSONObject->v.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(v.opt(it))};is JSONArray->(0 until v.length()).joinToString(",","[","]"){canonical(v.opt(it))};is String->JSONObject.quote(v);else->v.toString()}
        return canonical(copy)
    }
    private fun sanitize(data:JSONObject):String {
        val copy=JSONObject(data.toString())
        fun strip(value:Any?) {
            when(value) {
                is JSONObject -> value.keys().asSequence().toList().forEach { key->
                    val v=value.opt(key)
                    if(Regex("(?i)accessToken|refreshToken|token|password|cookie|authorization").matches(key))value.remove(key)
                    else if(v is String && (key.lowercase().endsWith("url") || key=="href")) value.put(key,v.substringBefore('#').substringBefore('?')+(if(v.contains('#')) "#"+v.substringAfter('#') else ""))
                    else strip(v)
                }
                is JSONArray -> (0 until value.length()).forEach{strip(value.opt(it))}
            }
        }
        strip(copy)
        // Only protocol fields used by this reader. Unknown backend metadata never becomes a credential store.
        val out=JSONObject();for(k in listOf("bookId","format","contentVersion","expiresAt","spine","resources","toc"))if(copy.has(k))out.put(k,copy.get(k))
        fun selected(value:JSONObject,keys:List<String>):JSONObject=JSONObject().apply{keys.forEach{if(value.has(it))put(it,value.get(it))}}
        copy.optJSONArray("resources")?.let{a->out.put("resources",JSONArray((0 until a.length()).map{selected(a.getJSONObject(it),listOf("resourceId","mediaType","url"))}))}
        copy.optJSONArray("toc")?.let{a->out.put("toc",JSONArray((0 until a.length()).map{selected(a.getJSONObject(it),listOf("label","resourceId","href","codePointOffset"))}))}
        val metadata=JSONObject();val input=copy.optJSONObject("metadata")?:JSONObject()
        for(k in listOf("layout","segments","readingSegments","hrefs","tocPaths","codePointLength"))if(input.has(k))metadata.put(k,input.get(k))
        input.optJSONArray("segments")?.let{a->metadata.put("segments",JSONArray((0 until a.length()).map{selected(a.getJSONObject(it),listOf("resourceId","startCodePoint","endCodePoint"))}))}
        input.optJSONArray("readingSegments")?.let{a->metadata.put("readingSegments",JSONArray((0 until a.length()).map{selected(a.getJSONObject(it),listOf("resourceId","originalResourceId","originalSpineIndex","href","label","anchors","startPath"))}))}
        return out.put("metadata",metadata).toString()
    }
}
