package app.leafreader.covers

import android.graphics.Bitmap
import android.util.LruCache
import app.leafreader.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.File

internal class BookCoverRepository(root:File,private val scope:CoroutineScope) {
    private val disk=CoverThumbnailStore(root);private val guard=Any();private val slots=Semaphore(2)
    private val memory=object:LruCache<String,Bitmap>(4*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}
    private fun remember(key:String,image:Bitmap)=synchronized(guard){memory.put(key,image);while(memory.snapshot().size>96)memory.remove(memory.snapshot().keys.first());Unit}
    private data class Pending(val job:Deferred<Bitmap?>,var readers:Int=0)
    private val pending=mutableMapOf<String,Pending>()
    private val misses=object:LinkedHashMap<String,Long>(32,.75f,true){override fun removeEldestEntry(e:MutableMap.MutableEntry<String,Long>?)=size>96}
    private val validated=object:LinkedHashMap<String,Pair<Long,String>>(32,.75f,true){override fun removeEldestEntry(e:MutableMap.MutableEntry<String,Pair<Long,String>>?)=size>96}
    private val embedded=object:LinkedHashMap<String,Boolean>(32,.75f,true){override fun removeEldestEntry(e:MutableMap.MutableEntry<String,Boolean>?)=size>96}
    suspend fun load(source:BookHubSource,book:String,retry:String,stillSelected:()->Boolean):Bitmap? {
        val cache=source.offline?:return null;val owner=source.id;val epoch=withContext(Dispatchers.IO){cache.generation(book)}
        fun valid()=stillSelected() && source.coverSessionValid() && cache.isCurrent(book,epoch)
        if(!valid())return null
        val manifest=withContext(Dispatchers.IO){cache.manifest(book)};val localVersion=manifest?.optString("contentVersion")?.takeIf{it.isNotBlank() && it.length<=512 && it.none{c->c.code<32}}
        if(manifest!=null && localVersion==null)return null
        val key="$owner|$book|$epoch|${localVersion.orEmpty()}|$retry"
        val task=synchronized(guard) {
            pending[key]?.takeUnless{it.job.isCancelled}?.also{it.readers++}?:Pending(scope.async(Dispatchers.IO,start=CoroutineStart.LAZY){slots.withPermit {
                val runningJob=coroutineContext[Job]
                try { if(!valid())null else obtain(source,book,epoch,localVersion,manifest,key){runningJob?.isActive==true && valid()} }
                catch(e:CancellationException){if(!valid())null else throw e}catch(_:Exception){null}
            }}).also{p->p.readers=1;pending[key]=p;p.job.invokeOnCompletion{synchronized(guard){if(pending[key]===p)pending.remove(key)}}}
        }
        task.job.start()
        return try{task.job.await().takeIf{valid()}}finally{synchronized(guard){task.readers--;if(task.readers==0 && !task.job.isCompleted)task.job.cancel()}}
    }
    private suspend fun obtain(source:BookHubSource,book:String,epoch:Long,localVersion:String?,manifest:org.json.JSONObject?,requestKey:String,valid:()->Boolean):Bitmap? {
        val owner=source.id;val cache=source.offline!!
        fun key(version:String)="$owner|$book|$version|$epoch"
        suspend fun saved(version:String):Bitmap? {
            memory.get(key(version))?.let {
                // A previous cancelled/failed optional disk write must not leave a permanently memory-only hit.
                if(valid() && (disk.latest(owner,book,epoch)!=version || disk.get(owner,book,version,epoch)==null))
                    save(owner,book,version,epoch,it,valid)
                return it.takeIf{valid()}
            }
            return disk.get(owner,book,version,epoch)?.let{thumbnail(it)}?.also{if(valid())remember(key(version),it)}
        }
        if(manifest!=null) {
            if(localVersion!=null && synchronized(guard){embedded[key(localVersion)]==true})saved(localVersion)?.takeIf{valid()}?.let{return it}
            val local=runCatching{EmbeddedCover.read(cache,book,manifest)}.getOrNull()
            if(local!=null) {
                val image=runCatching{thumbnail(local.bytes)}.getOrNull()
                if(image!=null && valid()){save(owner,book,local.version,epoch,image,valid);if(!valid())return null;remember(key(local.version),image);synchronized(guard){embedded[key(local.version)]=true};return image}
            }
        }
        if(!source.connection.online) {
            val version=localVersion?:disk.latest(owner,book,epoch)?:return null
            return saved(version).takeIf{valid()}
        }
        val now=android.os.SystemClock.elapsedRealtime()
        synchronized(guard){validated[requestKey]?.takeIf{now-it.first<60000}?.second}?.let { version->saved(version)?.takeIf{valid()}?.let{return it} }
        synchronized(guard){misses[requestKey]?.takeIf{now-it<30000}}?.let{return null}
        try {
            // Metadata only, no chapter, export or reading-package requests. Bind the unversioned cover endpoint to a stable version.
            val version=source.coverVersion(book);if(!valid())return null
            saved(version)?.takeIf{valid()}?.let{synchronized(guard){validated[requestKey]=now to version};return it}
            val image=thumbnail(source.coverBytes(book))
            if(!valid() || source.coverVersion(book)!=version || !valid()){synchronized(guard){misses[requestKey]=now};return null}
            save(owner,book,version,epoch,image,valid);if(!valid())return null;remember(key(version),image);synchronized(guard){validated[requestKey]=now to version};return image
        } catch(e:CancellationException){throw e} catch(_:Exception){synchronized(guard){misses[requestKey]=now};return null}
    }
    private fun save(owner:String,book:String,version:String,epoch:Long,image:Bitmap,valid:()->Boolean) {
        val bytes=ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        runCatching{disk.put(owner,book,version,epoch,bytes,valid)} // Thumbnail failure never changes reading/download state.
    }
    suspend fun localThumbnail(owner:String,book:String,cache:app.leafreader.offline.OfflineStore):Bitmap? {
        val epoch=cache.generation(book);val version=disk.latest(owner,book,epoch)?:return null
        val bytes=disk.get(owner,book,version,epoch)?:return null
        return thumbnail(bytes).takeIf{cache.isCurrent(book,epoch)}
    }
    fun clear(owner:String,book:String){disk.clear(owner,book);synchronized(guard){memory.snapshot().keys.filter{it.startsWith("$owner|$book|")}.forEach{memory.remove(it)};misses.keys.filter{it.startsWith("$owner|$book|")}.toList().forEach{misses.remove(it)};validated.keys.filter{it.startsWith("$owner|$book|")}.toList().forEach{validated.remove(it)};embedded.keys.filter{it.startsWith("$owner|$book|")}.toList().forEach{embedded.remove(it)}}}
    internal suspend fun thumbnail(bytes:ByteArray):Bitmap {
        if(bytes.size !in 1..8*1024*1024)protocol()
        val decoded=SafeRaster.decode(bytes);val scale=minOf(1f,256f/decoded.width,384f/decoded.height)
        val image=Bitmap.createScaledBitmap(decoded,(decoded.width*scale).toInt().coerceAtLeast(1),(decoded.height*scale).toInt().coerceAtLeast(1),true)
        if(image!==decoded)decoded.recycle();return image
    }
}
