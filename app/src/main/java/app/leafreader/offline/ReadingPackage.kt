package app.leafreader.offline

import app.leafreader.data.*
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/** Independently written implementation of the supplied client's READING_ZIP v1 contract. */
object ReadingPackage {
    private fun invalid():Nothing=throw SourceProblem(FailureKind.PROTOCOL,"整本阅读包校验失败，未标记为已下载",diagnostic="BH_DOWNLOAD_PACKAGE")
    private fun requireValid(ok:Boolean){if(!ok)invalid()}
    private fun safeDirectory(file:File):Set<String> {
        requireValid(file.length() in 22..134217728)
        RandomAccessFile(file,"r").use { raf->
            val start=(file.length()-65557).coerceAtLeast(0);val tail=ByteArray((file.length()-start).toInt());raf.seek(start);raf.readFully(tail)
            val t=ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            fun u16(b:ByteBuffer,p:Int)=b.getShort(p).toInt() and 65535
            fun u32(b:ByteBuffer,p:Int)=b.getInt(p).toLong() and 0xffffffffL
            val end=(tail.size-22 downTo 0).firstOrNull { u32(t,it)==0x06054b50L && it+22+u16(t,it+20)==tail.size }?:invalid()
            val count=u16(t,end+10);val length=u32(t,end+12);val offset=u32(t,end+16)
            requireValid(u16(t,end+4)==0 && u16(t,end+6)==0 && count==u16(t,end+8) && count in 2..20001 && length<=8388608 && offset+length==start+end)
            val bytes=ByteArray(length.toInt());raf.seek(offset);raf.readFully(bytes);val cd=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var at=0;var expanded=0L;val names=mutableSetOf<String>();val ranges=mutableListOf<Pair<Long,Long>>()
            repeat(count) {
                requireValid(at+46<=bytes.size && u32(cd,at)==0x02014b50L)
                val flags=u16(cd,at+8);val method=u16(cd,at+10);val compressed=u32(cd,at+20);val size=u32(cd,at+24)
                val n=u16(cd,at+28);val extra=u16(cd,at+30);val comment=u16(cd,at+32);val local=u32(cd,at+42);val mode=(u32(cd,at+38) ushr 16).toInt()
                requireValid(at+46+n+extra+comment<=bytes.size)
                val name=String(bytes,at+46,n,Charsets.UTF_8)
                requireValid((name=="manifest.json" || Regex("resources/[A-Za-z0-9-]{1,160}").matches(name)) && names.add(name))
                requireValid(flags and 1==0 && method in listOf(0,8) && mode and 0xf000 in listOf(0,0x8000) && u16(cd,at+34)==0)
                requireValid(size<=67108864 && compressed<=67108864 && local+30+n+compressed<=offset)
                if(name=="manifest.json")requireValid(size<=8388608)
                expanded+=size;requireValid(expanded<=276824064)
                val header=ByteArray(30+n);raf.seek(local);raf.readFully(header);val h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                requireValid(u32(h,0)==0x04034b50L && u16(h,26)==n && String(header,30,n,Charsets.UTF_8)==name && u16(h,6) and 1==0 && u16(h,8)==method)
                val until=local+30+n+u16(h,28)+compressed;requireValid(until<=offset);ranges+=local to until
                at+=46+n+extra+comment
            }
            requireValid(at==bytes.size && "manifest.json" in names)
            val ordered=ranges.sortedBy{it.first};requireValid(ordered.zipWithNext().all{(a,b)->a.second<=b.first})
            return names
        }
    }
    suspend fun install(file:File,access:JSONObject,store:OfflineStore,epoch:Long,valid:()->Boolean,onResource:(Int,Int)->Unit) {
        val names=safeDirectory(file);val book=access.getString("bookId");val version=access.getString("contentVersion")
        ZipFile(file).use { zip->
            val raw=zip.getInputStream(zip.getEntry("manifest.json")).use { input->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536)
                while(true){val n=input.read(buffer);if(n<0)break;requireValid(output.size()+n<=8388608);output.write(buffer,0,n)}
                output.toByteArray()
            }
            requireValid(raw.size<=8388608);val manifest=try{JSONObject(String(raw,Charsets.UTF_8))}catch(_:Exception){invalid()}
            requireValid(manifest.optInt("schemaVersion")==1 && manifest.optString("bookId")==book && manifest.optString("contentVersion")==version && manifest.optString("format")==access.optString("format") && manifest.optJSONArray("spine")?.toString()==access.getJSONArray("spine").toString())
            val resources=manifest.optJSONArray("resources")?:invalid();val declared=access.getJSONArray("resources")
            val byId=(0 until declared.length()).associate{val r=declared.getJSONObject(it);r.getString("resourceId") to r}
            requireValid(resources.length()==names.size-1 && resources.length()==byId.size)
            store.requireSpace((0 until resources.length()).sumOf { resources.getJSONObject(it).optLong("sizeBytes",0).coerceAtLeast(0) })
            val ids=mutableSetOf<String>()
            for(i in 0 until resources.length()) {
                coroutineContext.ensureActive();if(!valid() || !store.isCurrent(book,epoch))throw DownloadStopped()
                val r=resources.getJSONObject(i);val id=r.optString("resourceId");val path=r.optString("path");val size=r.optLong("sizeBytes",-1);val hash=r.optString("sha256")
                requireValid(Regex("[A-Za-z0-9-]{1,160}").matches(id) && ids.add(id) && path=="resources/$id" && path in names && byId.containsKey(id) && Regex("[a-f0-9]{64}").matches(hash) && zip.getEntry(path).size==size && r.opt("mediaType") is String)
                if(!store.portable(book,version,id) || !store.matches(book,version,id,hash,size)) {
                    val temp=store.temporary(book,"extract-$id")
                    try {
                        var written=0L
                        zip.getInputStream(zip.getEntry(path)).use { input->FileOutputStream(temp).use { output->
                            val buffer=ByteArray(65536)
                            while(true) {coroutineContext.ensureActive();if(!valid())throw DownloadStopped();val n=input.read(buffer);if(n<0)break;written+=n;if(written>size || written>67108864)invalid();output.write(buffer,0,n)}
                            output.fd.sync()
                        } }
                        requireValid(written==size && sha(temp)==hash)
                        if(!store.acceptFile(book,version,id,temp,epoch,true))throw DownloadStopped()
                    }finally{store.finishTemporary(temp)}
                }
                onResource(i+1,resources.length())
            }
            requireValid(ids==byId.keys)
        }
    }
    fun restoreLinks(source:String,urls:Map<String,String>):String {
        // Only real element attributes, never examples in text nodes or escaped markup.
        val doc=org.jsoup.Jsoup.parse(source);doc.outputSettings().prettyPrint(false)
        for(element in doc.getAllElements())for(attr in listOf("src","href","xlink:href","poster")) {
            val value=element.attr(attr);val id=value.substringBefore('#')
            val url=urls[id]?.substringBefore('?')?:continue
            element.attr(attr,url+(if(value.contains('#'))"#"+value.substringAfter('#') else ""))
        }
        return doc.outerHtml()
    }
}
class DownloadStopped:java.io.IOException("下载已暂停，已校验的内容保留")
