package app.leafreader.data

import android.content.Context
import android.graphics.Bitmap
import app.leafreader.offline.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/** One public, accountless source. Separate HTTP stack/storage: never receives BookHub credentials. */
@android.annotation.SuppressLint("ApplySharedPref") // Durable cleanup epochs/backoff precede publication; writes run on IO.
class GutenbergSource(context:Context,private val wire:WireTransport=GutenbergHttp(),
    private val network:ConnectionState=AndroidConnectionState(context),private val bootstrap:Boolean=true,private val now:()->Long={System.currentTimeMillis()}) : IndependentBookSource {
    companion object { const val SOURCE="gutenberg-public-v1";private const val BASE="https://gutenberg.pglaf.org" }
    private val displayConverter=app.leafreader.text.ChineseDisplay.converter(context)
    override fun trimDownloadMemory(){synchronized(memory){memory.clear()}}
    override val supportsAutomaticDownload:Boolean get()=true
    override val id=SOURCE
    override val label="古登堡计划"
    override val independentOffline=true // Public-domain EPUB with retained publisher license, checked before acquisition.
    private val root=File(context.noBackupFilesDir,"gutenberg-public-v1").apply{mkdirs()}
    private val prefs=context.getSharedPreferences("gutenberg-offline-v1",Context.MODE_PRIVATE)
    private val appContext=context.applicationContext
    private val catalogScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var catalogRefresh:Job?=null
    override fun close(){catalogScope.cancel()}
    private val sharedIo=IndependentSourceIo.forRoot(root)
    private val catalogMutex=sharedIo.catalog;private val requestMutex=sharedIo.requests
    private val locks=sharedIo.books
    private val metadataLocks=sharedIo.metadata
    private val memory=object:LinkedHashMap<String,PublicEpubArchive>(2,.75f,true){override fun removeEldestEntry(e:MutableMap.MutableEntry<String,PublicEpubArchive>?)=size>2}
    private val thumbs=object:android.util.LruCache<String,Bitmap>(2*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}
    private var lastRequest:Long
        get()=sharedIo.lastRequest.get()
        set(value){sharedIo.lastRequest.set(value)}
    private var opdsRoot:GutenbergOpds.Feed?=null
    private var opdsSearchXml:String?=null
    private suspend fun opds(query:String,cursor:String?):BookPage?=withContext(Dispatchers.IO) {
        if(!network.online || now()<prefs.getLong("opds-retry-after",0))return@withContext null
        suspend fun get(url:String):String {
            val checked=GutenbergOpds.official(url)
            val r=try{requestMutex.withLock {val wait=2000-(now()-lastRequest);if(wait>0)delay(wait);lastRequest=now();wire.execute(URI(checked),"GET",mapOf("User-Agent" to "LeafReader/1.1.4 (OPDS reader)","Accept" to "application/atom+xml,application/opensearchdescription+xml"),null,1024*1024)}}catch(e:GutenbergMaintenance){prefs.edit().putLong("opds-retry-after",now()+e.waitMillis).commit();throw SourceProblem(FailureKind.NETWORK,"官方 OPDS 暂时维护",diagnostic="PG_OPDS_MAINTENANCE")}
            if(r.status in listOf(429,503)){prefs.edit().putLong("opds-retry-after",now()+6*60*60*1000L).commit();throw SourceProblem(FailureKind.NETWORK,"官方 OPDS 暂时维护",diagnostic="PG_OPDS_MAINTENANCE")}
            if(r.status!=200)throw SourceProblem(FailureKind.NETWORK,"官方 OPDS 暂不可用",diagnostic="PG_OPDS_HTTP")
            return r.bytes.toString(Charsets.UTF_8)
        }
        try {
            val root=opdsRoot?:GutenbergOpds.parse(get(GutenbergOpds.ROOT)).also{opdsRoot=it}
            val url=if(cursor!=null)GutenbergOpds.official(cursor.removePrefix("opds:"))else if(query.isNotBlank() && root.search!=null)GutenbergOpds.searchTemplate(opdsSearchXml?:get(root.search).also{opdsSearchXml=it},root.search,query)else GutenbergOpds.ROOT
            val page=if(url==GutenbergOpds.ROOT)root else GutenbergOpds.parse(get(url),url)
            if(page.books.isEmpty() && url==GutenbergOpds.ROOT)return@withContext null
            BookPage(page.books,page.next?.let{"opds:$it"},page.next!=null)
        }catch(e:CancellationException){throw e}catch(_:Exception){if(cursor!=null)throw SourceProblem(FailureKind.NETWORK,"OPDS 分页暂不可用，请重新找书",diagnostic="PG_OPDS_PAGE");null}
    }
    private fun lock(book:String)=locks.getOrPut(book){Mutex()}
    private fun bookId(book:String)=book.removePrefix("pg:").also{if(book!="pg:$it" || !it.matches(Regex("[1-9][0-9]{0,6}")))bad("书籍标识无效")}
    private fun directory(book:String)=File(root,bookId(book)).apply{mkdirs()}
    private fun epoch(book:String)=prefs.getLong("epoch:$book",0)
    private fun sha(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
    private fun bad(s:String):Nothing=throw SourceProblem(FailureKind.PROTOCOL,s,diagnostic="PG_PROTOCOL")
    private suspend fun fetch(path:String,cap:Int):ByteArray=requestMutex.withLock {
        coroutineContext.ensureActive()
        if(!network.online)throw SourceProblem(FailureKind.NETWORK,"当前离线，此内容尚未缓存",diagnostic="PG_OFFLINE")
        val until=prefs.getLong("retry-after",0)
        if(now()<until)throw SourceProblem(FailureKind.NETWORK,"古登堡镜像暂时维护，请稍后再试",diagnostic="PG_MAINTENANCE")
        val uri=URI(BASE).resolve(path)
        if(uri.scheme!="https" || uri.host!="gutenberg.pglaf.org" || uri.port!=-1 || uri.rawUserInfo!=null || uri.rawQuery!=null || uri.rawFragment!=null || !uri.path.startsWith("/cache/epub/"))bad("已拒绝非官方资源")
        val wait=2000-(now()-lastRequest);if(wait>0)delay(wait)
        lastRequest=now()
        val r=try{wire.execute(uri,"GET",mapOf("User-Agent" to "LeafReader/1.1.4 (public catalog reader)","Accept" to "*/*"),null,cap)}catch(e:GutenbergMaintenance){prefs.edit().putLong("retry-after",now()+e.waitMillis).commit();throw SourceProblem(FailureKind.NETWORK,"古登堡镜像暂时维护，请稍后再试",diagnostic="PG_MAINTENANCE")}
        if(r.status in listOf(429,503)){prefs.edit().putLong("retry-after",now()+6*60*60*1000L).commit();throw SourceProblem(FailureKind.NETWORK,"古登堡镜像暂时维护，请稍后再试",diagnostic="PG_MAINTENANCE",httpStatus=r.status)}
        if(r.status!=200)throw SourceProblem(FailureKind.NETWORK,"公开资源暂时无法取得，请重试",diagnostic="PG_HTTP_${r.status}",httpStatus=r.status)
        if(r.bytes.size>cap)bad("公开资源过大")
        r.bytes
    }
    private fun readCatalog(file:File):List<Book> {
        return readCatalogText(file.readText())
    }
    private fun readCatalogText(text:String):List<Book> {
        val a=JSONObject(text).getJSONArray("books")
        if(a.length() !in 1..5000)bad("本机书目不完整")
        return (0 until a.length()).map { i->val b=a.getJSONObject(i);Book(b.getString("id"),b.getString("title"),b.getString("author"),b.optString("description"),"古登堡计划",0,0xFF78664F,"EPUB",id) }
    }
    private suspend fun catalog():List<Book> = withContext(Dispatchers.IO){catalogMutex.withLock {
        val file=File(root,"catalog.json")
        val saved=runCatching{readCatalog(file)}.getOrNull()
        if(saved!=null && (!network.online || now()-file.lastModified()<7*24*60*60*1000L))return@withLock saved
        try {
            val zipped=fetch("/cache/epub/feeds/pg_catalog.csv.gz",8*1024*1024)
            var header=emptyList<String>();val books=mutableListOf<Book>()
            fun field(row:List<String>,name:String)=row.getOrNull(header.indexOf(name)).orEmpty()
            GZIPInputStream(zipped.inputStream()).use { stream->
                CsvRows.forEach(stream.bufferedReader(Charsets.UTF_8)) { row->
                    if(header.isEmpty()) {
                        header=row
                        if(!header.containsAll(listOf("Text#","Type","Title","Language","Authors","Subjects")))bad("官方目录格式已变化")
                    } else if(field(row,"Type")=="Text" && field(row,"Language").split(';',',').any{it.trim()=="zh"}) {
                        val number=field(row,"Text#");if(!number.matches(Regex("[1-9][0-9]{0,6}")))bad("目录书号无效")
                        books+=Book("pg:$number",field(row,"Title").take(600),field(row,"Authors").ifBlank{"作者未注明"}.take(600),field(row,"Subjects").take(1000),"古登堡计划",0,0xFF78664F,"EPUB",id)
                    }
                }
            }
            val unique=books.distinctBy{it.id}.sortedBy{it.id.substringAfter(':').toInt()}
            if(unique.isEmpty() || unique.size>5000)bad("中文目录不完整")
            atomic(file,JSONObject().put("books",JSONArray().apply{unique.forEach{b->put(JSONObject().put("id",b.id).put("title",b.title).put("author",b.author).put("description",b.description))}}).toString().toByteArray())
            unique
        }catch(e:CancellationException){throw e}catch(e:Exception){saved?:throw e}
    }}
    /** First search uses bibliographic facts immediately; a slow server never gates results. */
    private suspend fun availableCatalog():Pair<List<Book>,String?> = withContext(Dispatchers.IO) {
        val file=File(root,"catalog.json")
        val saved=runCatching{readCatalog(file)}.getOrNull()
        val local=saved?:runCatching{appContext.assets.open("gutenberg-zh-catalog.json").bufferedReader(Charsets.UTF_8).use{readCatalogText(it.readText())}}.getOrElse{
            throw SourceProblem(FailureKind.PROTOCOL,"中文书目未能加载，请重试",diagnostic="PG_CATALOG_LOCAL")
        }
        val stale=saved==null || now()-file.lastModified()>=7*24*60*60*1000L
        if(stale && network.online && now()>=prefs.getLong("catalog-next-check",0))synchronized(this@GutenbergSource) {
            if(catalogRefresh?.isActive!=true) {
                // At most one update attempt per day. Never probe OPDS ahead of this Chinese catalog.
                prefs.edit().putLong("catalog-next-check",now()+24*60*60*1000L).commit()
                catalogRefresh=catalogScope.launch {try{withTimeout(12_000){catalog()}}catch(e:CancellationException){throw e}catch(_:Exception){/* Bibliographic fallback remains usable; no empty result fabricated. */}}
            }
        }
        local to if(saved==null)"本机中文书目 · 2026-11-25"else if(stale)"本机中文书目 · 离线后备"else null
    }
    override suspend fun books():List<Book> = emptyList() // Catalog discovery must never populate the shelf.
    override suspend fun browse(query:String,cursor:String?):BookPage {
        if(!bootstrap && (cursor==null || cursor.startsWith("opds:")))opds(query,cursor)?.let{return it}
        val (catalog,note)=if(bootstrap)availableCatalog()else catalog() to null
        val all=catalog.filter{GutenbergSearch.matches(it,query,displayConverter)}
        val stamp=digest(all.joinToString("|"){it.key+it.title+it.author}).take(16)
        val at=if(cursor==null)0 else {val p=cursor.split(':');if(p.size!=2 || p[0]!=stamp)throw SourceProblem(FailureKind.VERSION,"目录已更新，请重新找书",diagnostic="PG_CATALOG_VERSION");p[1].toIntOrNull()?.takeIf{it in 1..all.size}?:bad("分页无效")}
        val more=at+20<all.size
        return BookPage(all.drop(at).take(20),if(more)"$stamp:${at+20}"else null,more,catalogNote=note)
    }
    private fun mirrorPath(url:String,book:String):String {
        val u=try{URI(url)}catch(_:Exception){bad("官方元数据链接无效")}
        val prefix="/cache/epub/${bookId(book)}/"
        if(u.scheme!="https" || u.host !in setOf("www.gutenberg.org","gutenberg.pglaf.org") || u.port!=-1 || u.rawUserInfo!=null || u.rawQuery!=null || u.rawFragment!=null || !u.path.startsWith(prefix) || u.path.split('/').any{it==".."})bad("已拒绝非官方书籍链接")
        return u.rawPath
    }
    private suspend fun metadata(book:String):JSONObject = metadataLocks.getOrPut(book){Mutex()}.withLock {
        val generation=epoch(book);val file=File(directory(book),"metadata.json")
        runCatching{JSONObject(file.readText())}.getOrNull()?.takeIf{it.optString("rights")=="Public domain in the USA."}?.let{return@withLock it}
        val number=bookId(book);val raw=fetch("/cache/epub/$number/pg$number.rdf",256*1024)
        val xml=Jsoup.parse(raw.toString(Charsets.UTF_8),"",Parser.xmlParser())
        val identity=xml.getAllElements().firstOrNull{it.normalName().substringAfter(':')=="ebook"}?.attr("rdf:about")
        if(identity!="ebooks/$number")bad("官方元数据与当前书籍不一致")
        val rights=xml.getAllElements().firstOrNull{it.normalName().substringAfter(':')=="rights"}?.text().orEmpty()
        if(rights!="Public domain in the USA.")throw SourceProblem(FailureKind.FORBIDDEN,"该书未确认公有领域阅读许可",diagnostic="PG_RIGHTS")
        val image=xml.getAllElements().filter{it.normalName().substringAfter(':')=="file"}.map{it.attr("rdf:about")}.firstOrNull{it.endsWith(".cover.small.jpg")}.orEmpty()
        val safeImage=if(image.isEmpty())""else runCatching{mirrorPath(image,book);image}.getOrDefault("")
        coroutineContext.ensureActive();if(epoch(book)!=generation)throw CancellationException("Metadata invalidated")
        JSONObject().put("rights",rights).put("cover",safeImage).also{atomic(file,it.toString().toByteArray())}
    }
    private suspend fun archive(book:String):PublicEpubArchive = withContext(Dispatchers.IO) {
        lock(book).withLock {
            synchronized(memory){memory[book]}?.let{return@withLock it}
            val dir=directory(book);val file=File(dir,"book.epub");val mark=File(dir,"complete.json")
            if(file.isFile && mark.isFile) {
                try {if(file.length()>32*1024*1024)bad("本机副本大小无效");val bytes=file.readBytes();val receipt=JSONObject(mark.readText());if(bytes.size>32*1024*1024 || sha(bytes)!=receipt.getString("sha256") || receipt.optString("rights")!="Public domain in the USA.")bad("本机副本校验失败，请清除后重新下载")
                    return@withLock PublicEpubArchive(bytes,bookId(book)).also{synchronized(memory){memory[book]=it}}
                }catch(e:SourceProblem){throw e}catch(_:Exception){bad("本机副本不完整，请清除后重新下载")}
            }
            val generation=epoch(book)
            val meta=metadata(book);val number=bookId(book)
            val html=fetch("/cache/epub/$number/",128*1024).toString(Charsets.UTF_8)
            val base=URI("$BASE/cache/epub/$number/")
            val links=Jsoup.parse(html).select("a[href]").map{base.resolve(it.attr("href"))}.filter{it.path.endsWith(".epub")}
            val target=links.firstOrNull{it.path.endsWith("-images-3.epub")}?:links.firstOrNull{it.path.endsWith("-images.epub")}?:links.firstOrNull()?:bad("此书暂无可用 EPUB")
            val path=mirrorPath(target.toString(),book)
            val bytes=fetch(path,32*1024*1024);val parsed=PublicEpubArchive(bytes,bookId(book))
            coroutineContext.ensureActive()
            if(epoch(book)!=generation)throw CancellationException("Public download invalidated by cleanup")
            atomic(file,bytes)
            atomic(mark,JSONObject().put("sha256",sha(bytes)).put("rights",meta.getString("rights")).toString().toByteArray())
            synchronized(memory){memory[book]=parsed};OfflineEvents.changed();parsed
        }
    }
    override suspend fun chapters(bookId:String)=archive(bookId).chapters
    override suspend fun chapter(bookId:String,chapterId:String)=withContext(Dispatchers.Default){archive(bookId).chapter(chapterId)}
    override suspend fun chapterLengths(bookId:String)=withContext(Dispatchers.Default){archive(bookId).lengths()}
    override suspend fun download(book:String,allowMetered:Boolean) {
        if(!allowMetered && !network.wifi)throw SourceProblem(FailureKind.NETWORK,"连接不计费 Wi-Fi 后再试，或明确允许流量",diagnostic="PG_WIFI")
        withContext(DownloadPolicy{allowMetered || network.wifi}){archive(book)}
    }
    override suspend fun status(book:String):OfflineStatus=withContext(Dispatchers.IO){lock(book).withLock {
        val dir=directory(book);val f=File(dir,"book.epub");val m=File(dir,"complete.json")
        if(!f.isFile)return@withLock OfflineStatus()
        try{if(f.length()>32*1024*1024)return@withLock OfflineStatus(0,1,false);val b=f.readBytes();val r=JSONObject(m.readText());val ok=b.size<=32*1024*1024 && r.optString("rights")=="Public domain in the USA." && sha(b)==r.getString("sha256")
            if(ok){PublicEpubArchive(b,bookId(book));OfflineStatus(1,1,true)}else OfflineStatus(0,1,false)
        }catch(_:Exception){OfflineStatus(0,1,false)}
    }}
    override suspend fun clear(book:String)=withContext(Dispatchers.IO) {
        // Persist invalidation before waiting for in-flight I/O; old responses cannot recreate a cleared copy.
        synchronized(prefs){check(prefs.edit().putLong("epoch:$book",epoch(book)+1).commit())}
        lock(book).withLock{synchronized(memory){memory.remove(book)};thumbs.remove(book);directory(book).deleteRecursively();OfflineEvents.changed()}
    }
    /** Disk-only cover lookup: deletion between validation and lookup must never cause acquisition. */
    private suspend fun localCover(book:String):ByteArray?=lock(book).withLock {
        try {
            val dir=directory(book);val f=File(dir,"book.epub");val mark=File(dir,"complete.json")
            if(!f.isFile || !mark.isFile || f.length()>32*1024*1024)return@withLock null
            val receipt=JSONObject(mark.readText());val bytes=f.readBytes()
            if(receipt.optString("rights")!="Public domain in the USA." || sha(bytes)!=receipt.optString("sha256"))return@withLock null
            PublicEpubArchive(bytes,bookId(book)).coverBytes()
        }catch(e:CancellationException){throw e}catch(_:Exception){null}
    }
    override suspend fun thumbnail(book:String):Bitmap?=withContext(Dispatchers.IO) {
        thumbs.get(book)?.let{return@withContext it}
        try {
            val generation=epoch(book);val dir=directory(book);val small=File(dir,"thumb.jpg")
            var b=synchronized(memory){memory[book]}?.coverBytes()
            if(b==null)b=localCover(book)
            if(b==null && small.isFile)b=small.readBytes()
            if(b==null && network.online) {
                val cover=metadata(book).optString("cover")
                if(cover.isNotBlank())b=fetch(mirrorPath(cover,book),512*1024)
            }
            if(b==null || epoch(book)!=generation)return@withContext null
            val decoded=SafeRaster.decode(b);val scale=minOf(1f,180f/decoded.width,270f/decoded.height)
            val thumb=Bitmap.createScaledBitmap(decoded,(decoded.width*scale).toInt().coerceAtLeast(1),(decoded.height*scale).toInt().coerceAtLeast(1),true)
            if(thumb!==decoded)decoded.recycle()
            lock(book).withLock {if(epoch(book)!=generation){thumb.recycle();return@withContext null};val bytes=java.io.ByteArrayOutputStream();thumb.compress(Bitmap.CompressFormat.JPEG,88,bytes);atomic(small,bytes.toByteArray());thumbs.put(book,thumb)}
            thumb
        }catch(e:CancellationException){throw e}catch(_:Exception){null}
    }
}

/** RFC 4180 subset with quoted commas, doubled quotes and multiline fields; bounded by catalog input. */
internal object CsvRows {
    fun parse(text:String):List<List<String>> = mutableListOf<List<String>>().also { result->forEach(text.reader()){result+=it} }
    fun forEach(reader:java.io.Reader,accept:(List<String>)->Unit) {
        val input=java.io.PushbackReader(reader,1);val row=mutableListOf<String>();val field=StringBuilder();var quoted=false;var rows=0;var total=0
        fun emit(){row+=field.toString();field.setLength(0);if(row.any{it.isNotEmpty()}){if(++rows>150000)invalid();accept(row.toList())};row.clear()}
        while(true) {
            val n=input.read();if(n<0)break;if(++total>32*1024*1024 || field.length>100000)invalid();val c=n.toChar()
            when {
                c=='"' && quoted->{val next=input.read();if(next=='"'.code)field.append('"')else{quoted=false;if(next>=0)input.unread(next)}}
                c=='"' && field.isEmpty()->quoted=true
                c==',' && !quoted->{row+=field.toString();field.setLength(0)}
                (c=='\n'||c=='\r') && !quoted->{if(c=='\r'){val next=input.read();if(next>=0 && next!='\n'.code)input.unread(next)};emit()}
                else->field.append(c)
            }
        }
        if(quoted)invalid();if(field.isNotEmpty() || row.isNotEmpty())emit()
    }
    private fun invalid():Nothing=throw SourceProblem(FailureKind.PROTOCOL,"目录字段不完整或大小超限",diagnostic="PG_CSV")
}
private suspend fun java.io.InputStream.readNBytesBounded(cap:Int,deadline:Long=Long.MAX_VALUE):ByteArray {
    val out=java.io.ByteArrayOutputStream();val buf=ByteArray(16384)
    while(true){kotlinx.coroutines.currentCoroutineContext().ensureActive();if(System.nanoTime()>deadline)throw java.net.SocketTimeoutException("Catalog request timed out");checkDownloadPolicy();val n=read(buf);checkDownloadPolicy();if(n<0)break;if(out.size()+n>cap)throw SourceProblem(FailureKind.PROTOCOL,"目录解压大小超限",diagnostic="PG_CATALOG_SIZE");out.write(buf,0,n)};return out.toByteArray()
}
internal class GutenbergMaintenance(val waitMillis:Long):java.io.IOException("Public service maintenance")
class GutenbergHttp:WireTransport {
    override suspend fun execute(uri:URI,method:String,headers:Map<String,String>,body:ByteArray?,maxBytes:Int):WireResponse=withContext(Dispatchers.IO) {
        require(uri.scheme=="https" && uri.port==-1 && uri.rawUserInfo==null && uri.rawFragment==null && (uri.host=="gutenberg.pglaf.org" && uri.path.startsWith("/cache/epub/") || uri.host=="www.gutenberg.org" && uri.path.startsWith("/ebooks/")) && method=="GET" && body==null && headers.keys.none{it.equals("Authorization",true)||it.equals("Cookie",true)})
        val c=uri.toURL().openConnection()as HttpURLConnection
        try{c.instanceFollowRedirects=false;val isCatalog=uri.path.endsWith("pg_catalog.csv.gz");val deadline=System.nanoTime()+if(isCatalog)10_000_000_000L else 300_000_000_000L;c.connectTimeout=if(isCatalog)4000 else 15000;c.readTimeout=if(isCatalog)3000 else 30000;c.useCaches=false;c.setRequestProperty("Cookie","");headers.forEach{(k,v)->c.setRequestProperty(k,v)}
            val status=c.responseCode
            if(status in listOf(429,503)) {
                val raw=c.getHeaderField("Retry-After").orEmpty();val current=System.currentTimeMillis()
                val seconds=raw.toLongOrNull()?.takeIf{it in 1..31_536_000}
                val until=runCatching{java.time.ZonedDateTime.parse(raw,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()}.getOrNull()
                throw GutenbergMaintenance(seconds?.times(1000)?:until?.minus(current)?.coerceAtLeast(2000)?:21600_000)
            }
            if(status!=200)return@withContext WireResponse(status,ByteArray(0))
            val bytes=c.inputStream.use{it.readNBytesBounded(maxBytes,deadline)};WireResponse(status,bytes,c.contentType)
        }catch(e:GutenbergMaintenance){throw e}catch(e:SourceProblem){throw e}catch(e:java.io.IOException){throw SourceProblem(FailureKind.NETWORK,"公开书源连接失败，请重试",diagnostic="PG_NETWORK").apply{initCause(e)}}finally{c.disconnect()}
    }
}
