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
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.net.URI
import kotlin.coroutines.coroutineContext
import java.util.concurrent.ConcurrentHashMap

data class OpdsSubscription(val name:String,val url:String,val retainedRights:Boolean) {
    val id:String get()="opds-v1-"+digest(CatalogOrigin(url).root.toString()).take(32)
}
class OpdsSubscriptions(private val context:Context) {
    private val prefs=context.getSharedPreferences("opds-subscriptions-v1",Context.MODE_PRIVATE)
    fun all():List<OpdsSubscription> = runCatching {val a=JSONArray(prefs.getString("items","[]"));(0 until a.length()).map{a.getJSONObject(it).let{j->OpdsSubscription(j.getString("name"),j.getString("url"),j.getBoolean("rights"))}}}.getOrDefault(emptyList())
    @android.annotation.SuppressLint("ApplySharedPref")
    fun save(value:OpdsSubscription):Boolean {
        require(value.retainedRights && value.name.isNotBlank() && value.name.length<=80)
        val rows=(all().filterNot{it.id==value.id}+value);require(rows.size<=30)
        return prefs.edit().putString("items",JSONArray().apply{rows.forEach{put(JSONObject().put("name",it.name).put("url",it.url).put("rights",it.retainedRights))}}.toString()).commit()
    }
}

/** OPDS 1, without accounts or cookies. Same-origin acquisition and explicit independent rights only. */
@android.annotation.SuppressLint("ApplySharedPref")
class OpdsSource(context:Context,val subscription:OpdsSubscription,private val network:ConnectionState=AndroidConnectionState(context),transport:WireTransport?=null):IndependentBookSource {
    private val origin=CatalogOrigin(subscription.url)
    private val wire=transport?:AnonymousHttp(origin)
    override fun trimDownloadMemory(){synchronized(memory){memory.clear()}}
    override val supportsAutomaticDownload:Boolean get()=subscription.retainedRights
    override val id=subscription.id
    override val label=subscription.name
    override val kindLabel="OPDS 1 · 无需登录"
    override val independentOffline=subscription.retainedRights
    private val root=File(context.noBackupFilesDir,"independent-epub-v1/$id").apply{mkdirs()}
    private val prefs=context.getSharedPreferences("opds-cache-$id",Context.MODE_PRIVATE)
    private val sharedIo=IndependentSourceIo.forRoot(root)
    private val requestLock=sharedIo.requests;private val locks=sharedIo.books
    private val memory=object:LinkedHashMap<String,PublicEpubArchive>(2,.75f,true){override fun removeEldestEntry(e:MutableMap.MutableEntry<String,PublicEpubArchive>?)=size>2}
    private val thumbs=object:android.util.LruCache<String,Bitmap>(2*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}
    private var startFeed:OpdsFeed?=null;private var searchXml:String?=null
    private fun folder(book:String):File {if(!book.matches(Regex("opds:[a-f0-9]{64}")))bad("书籍标识无效");return File(root,book.substringAfter(':')).apply{mkdirs()}}
    private fun lock(book:String)=locks.getOrPut(book){Mutex()}
    private fun epoch(book:String)=prefs.getLong("epoch:$book",0)
    private fun hash(bytes:ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun bad(s:String):Nothing=throw SourceProblem(FailureKind.PROTOCOL,s,diagnostic="OPDS_PROTOCOL")
    private suspend fun fetch(url:String,cap:Int):ByteArray=requestLock.withLock {
        if(!network.online)throw SourceProblem(FailureKind.NETWORK,"当前离线，此资源尚未缓存",diagnostic="OPDS_OFFLINE")
        if(System.currentTimeMillis()<prefs.getLong("retry-after",0))throw SourceProblem(FailureKind.NETWORK,"此目录暂时不可用，请稍后再试",diagnostic="OPDS_BACKOFF")
        checkDownloadPolicy()
        val response=try{wire.execute(origin.resolve(url),"GET",mapOf("Accept" to "application/atom+xml,application/epub+zip,image/*;q=0.8"),null,cap)}catch(e:CatalogBackoff){prefs.edit().putLong("retry-after",System.currentTimeMillis()+e.waitMillis).commit();throw SourceProblem(FailureKind.NETWORK,"此目录暂时不可用，请稍后再试",diagnostic="OPDS_BACKOFF")}
        checkDownloadPolicy()
        if(response.status in setOf(401,403))throw SourceProblem(FailureKind.AUTH,"此目录需要授权；本版 OPDS 不传账号或 Cookie",diagnostic="OPDS_AUTH")
        if(response.status in setOf(429,503)){prefs.edit().putLong("retry-after",System.currentTimeMillis()+21600_000).commit();throw SourceProblem(FailureKind.NETWORK,"此目录暂时不可用，请稍后再试",diagnostic="OPDS_BACKOFF")}
        if(response.status!=200)throw SourceProblem(FailureKind.NETWORK,"目录资源暂时无法取得，请重试",diagnostic="OPDS_HTTP_${response.status}")
        if(response.bytes.size>cap)bad("目录资源过大")
        response.bytes
    }
    suspend fun verify():String=withContext(Dispatchers.IO) {
        require(subscription.retainedRights)
        val p=feed(origin.root.toString());startFeed=p;p.title
    }
    private suspend fun feed(url:String):OpdsFeed {
        val raw=fetch(url,1024*1024).toString(Charsets.UTF_8)
        val p=OpdsFeed.parse(raw,url,origin)
        p.records.forEach { r->atomic(File(folder(r.bookId),"metadata.json"),r.toJson().toString().toByteArray()) }
        return p
    }
    override suspend fun books():List<Book> = emptyList()
    override suspend fun browse(query:String,cursor:String?):BookPage=withContext(Dispatchers.IO) {
        val start=startFeed?:feed(origin.root.toString()).also{startFeed=it}
        val url=when {
            cursor!=null->origin.resolve(cursor.removePrefix("nav:")).toString()
            query.isNotBlank()->{
                val search=start.search?:throw SourceProblem(FailureKind.UNSUPPORTED,"此目录未提供搜索，请浏览目录",diagnostic="OPDS_NO_SEARCH")
                val xml=searchXml?:fetch(search,256*1024).toString(Charsets.UTF_8).also{searchXml=it}
                OpdsFeed.searchUrl(xml,search,query,origin)
            }
            else->origin.root.toString()
        }
        val p=if(url==origin.root.toString())start else feed(url)
        BookPage(p.records.map{r->Book(r.bookId,r.title,r.author,r.summary,label,0,0xFF78664F,"EPUB",id)},p.next,p.next!=null,sections=p.sections)
    }
    private fun record(book:String)=runCatching{OpdsRecord.from(JSONObject(File(folder(book),"metadata.json").readText()))}.getOrElse{throw SourceProblem(FailureKind.NETWORK,"本机缺少此书的目录记录，请先联网浏览该订阅",diagnostic="OPDS_METADATA")}.also{if(it.bookId!=book)bad("书目记录不匹配")}
    private fun validLocal(book:String):PublicEpubArchive? {
        val dir=folder(book);val f=File(dir,"book.epub");val m=File(dir,"complete.json")
        if(!f.isFile || !m.isFile)return null
        if(f.length()>32*1024*1024)bad("本机副本大小无效")
        val bytes=f.readBytes();val mark=JSONObject(m.readText())
        if(mark.optString("book")!=book || mark.optString("sha256")!=hash(bytes))bad("本机副本损坏，请清除后重新下载")
        return PublicEpubArchive(bytes)
    }
    private suspend fun archive(book:String):PublicEpubArchive=withContext(Dispatchers.IO){lock(book).withLock {
        synchronized(memory){memory[book]}?.let{return@withLock it}
        validLocal(book)?.let{a->synchronized(memory){memory[book]=a};return@withLock a}
        if(!subscription.retainedRights)throw SourceProblem(FailureKind.FORBIDDEN,"此订阅未确认独立离线保留权",diagnostic="OPDS_RIGHTS")
        val generation=epoch(book);val r=record(book)
        val bytes=fetch(r.acquisition,32*1024*1024);val a=PublicEpubArchive(bytes)
        coroutineContext.ensureActive();if(generation!=epoch(book))throw CancellationException("Catalog copy invalidated")
        atomic(File(folder(book),"book.epub"),bytes)
        atomic(File(folder(book),"complete.json"),JSONObject().put("book",book).put("sha256",hash(bytes)).toString().toByteArray())
        synchronized(memory){memory[book]=a};OfflineEvents.changed();a
    }}
    override suspend fun chapters(bookId:String)=archive(bookId).chapters
    override suspend fun chapter(bookId:String,chapterId:String)=withContext(Dispatchers.Default){archive(bookId).chapter(chapterId)}
    override suspend fun chapterLengths(bookId:String)=withContext(Dispatchers.Default){archive(bookId).lengths()}
    override suspend fun download(book:String,allowMetered:Boolean){if(!allowMetered && !network.wifi)throw SourceProblem(FailureKind.NETWORK,"请连接不计费 Wi-Fi 或明确允许流量",diagnostic="OPDS_WIFI");withContext(DownloadPolicy{allowMetered || network.wifi}){archive(book)}}
    override suspend fun status(book:String):OfflineStatus=withContext(Dispatchers.IO){lock(book).withLock{try{if(validLocal(book)!=null)OfflineStatus(1,1,true)else OfflineStatus()}catch(_:Exception){OfflineStatus(0,1,false)}}}
    override suspend fun clear(book:String)=withContext(Dispatchers.IO){synchronized(prefs){check(prefs.edit().putLong("epoch:$book",epoch(book)+1).commit())};lock(book).withLock {synchronized(memory){memory.remove(book)};thumbs.remove(book);folder(book).listFiles()?.filter{it.name!="metadata.json"}?.sortedBy{if(it.name=="complete.json")0 else 1}?.forEach{check(it.deleteRecursively()){"本机副本未能清除，请重试"}};OfflineEvents.changed()}}
    override suspend fun thumbnail(book:String):Bitmap?=withContext(Dispatchers.IO) {
        thumbs.get(book)?.let{return@withContext it}
        try {
            val generation=epoch(book)
            var bytes=lock(book).withLock{runCatching{validLocal(book)?.coverBytes()}.getOrNull()}
            val cache=File(folder(book),"thumb.jpg")
            if(bytes==null && cache.isFile)bytes=cache.readBytes()
            if(bytes==null && network.online){val cover=record(book).cover;if(cover!=null)bytes=fetch(cover,512*1024)}
            if(bytes==null || epoch(book)!=generation)return@withContext null
            val decoded=SafeRaster.decode(bytes);val scale=minOf(1f,180f/decoded.width,270f/decoded.height)
            val small=Bitmap.createScaledBitmap(decoded,(decoded.width*scale).toInt().coerceAtLeast(1),(decoded.height*scale).toInt().coerceAtLeast(1),true);if(small!==decoded)decoded.recycle()
            lock(book).withLock {if(epoch(book)!=generation){small.recycle();return@withContext null};val out=java.io.ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,88,out);atomic(cache,out.toByteArray());thumbs.put(book,small)}
            small
        }catch(e:CancellationException){throw e}catch(_:Exception){null}
    }
}

internal data class OpdsRecord(val entryId:String,val title:String,val author:String,val summary:String,val acquisition:String,val cover:String?) {
    val bookId get()="opds:"+digest(entryId)
    fun toJson()=JSONObject().put("entry",entryId).put("title",title).put("author",author).put("summary",summary).put("acquisition",acquisition).put("cover",cover?:JSONObject.NULL)
    companion object {fun from(j:JSONObject)=OpdsRecord(j.getString("entry"),j.getString("title"),j.getString("author"),j.getString("summary"),j.getString("acquisition"),j.optString("cover").takeUnless{it.isBlank()||it=="null"})}
}
internal data class OpdsFeed(val title:String,val records:List<OpdsRecord>,val sections:List<CatalogSection>,val next:String?,val search:String?) {
    companion object {
        private fun Element.tagLocal()=normalName().substringAfter(':')
        private fun Element.child(name:String)=children().firstOrNull{it.tagLocal()==name}
        private fun invalid():Nothing=throw SourceProblem(FailureKind.PROTOCOL,"此地址未返回受支持的 OPDS 1 目录",diagnostic="OPDS_XML")
        fun parse(xml:String,url:String,scope:CatalogOrigin):OpdsFeed {
            val doc=Jsoup.parse(xml,"",Parser.xmlParser());val root=doc.children().firstOrNull{it.tagLocal()=="feed"}?:invalid()
            val prefix=root.tagName().substringBefore(':',"");if(root.attr(if(prefix.isEmpty())"xmlns"else"xmlns:$prefix")!="http://www.w3.org/2005/Atom")invalid()
            val records=mutableListOf<OpdsRecord>();val sections=mutableListOf<CatalogSection>()
            val entries=root.children().filter{it.tagLocal()=="entry"};if(entries.size>2000)invalid()
            for(e in entries){val title=e.child("title")?.text()?.take(600).orEmpty();val entry=e.child("id")?.text()?.takeIf{it.isNotBlank()&&it.length<=2048}?:continue
                val links=e.children().filter{it.tagLocal()=="link"}
                val acq=links.firstOrNull{it.attr("rel") in setOf("http://opds-spec.org/acquisition","http://opds-spec.org/acquisition/open-access") && it.attr("type").substringBefore(';')=="application/epub+zip"}
                if(acq!=null) {
                    val target=runCatching{scope.resolve(acq.attr("href"),url).toString()}.getOrNull()?:continue
                    val art=links.firstOrNull{it.attr("rel")in setOf("http://opds-spec.org/image/thumbnail","http://opds-spec.org/thumbnail","http://opds-spec.org/image")}
                    val cover=art?.let{runCatching{scope.resolve(it.attr("href"),url).toString()}.getOrNull()}
                    val author=e.children().filter{it.tagLocal()=="author"}.mapNotNull{it.child("name")?.text()}.joinToString("、").take(600).ifBlank{"作者未注明"}
                    records+=OpdsRecord(entry,title.ifBlank{"未命名图书"},author,e.child("summary")?.text().orEmpty().take(2000),target,cover)
                }else {
                    val link=links.firstOrNull{it.attr("rel")in setOf("subsection","alternate","http://opds-spec.org/sort/new","http://opds-spec.org/sort/popular")&&it.attr("type").startsWith("application/atom+xml")}
                    link?.let{runCatching{scope.resolve(it.attr("href"),url).toString()}.getOrNull()?.let{href->sections+=CatalogSection(title.ifBlank{"目录"},href)}}
                }
            }
            val links=root.children().filter{it.tagLocal()=="link"}
            fun relation(rel:String)=links.firstOrNull{it.attr("rel")==rel}?.let{scope.resolve(it.attr("href"),url).toString()}
            return OpdsFeed(root.child("title")?.text().orEmpty(),records.distinctBy{it.bookId},sections.distinctBy{it.href},relation("next"),relation("search"))
        }
        fun searchUrl(xml:String,base:String,query:String,scope:CatalogOrigin):String {
            val doc=Jsoup.parse(xml,"",Parser.xmlParser())
            val template=doc.getAllElements().firstOrNull{it.tagLocal()=="url"&&it.attr("type").startsWith("application/atom+xml")}?.attr("template")?:invalid()
            var result=template.replace("{searchTerms}",encoded(query)).replace("{count?}","20").replace("{startIndex?}","1").replace("{startPage?}","1").replace("{language?}","*").replace("{inputEncoding?}","UTF-8").replace("{outputEncoding?}","UTF-8")
            for(key in listOf("atom:author","atom:contributor","atom:title"))result=result.replace("{$key}","").replace("{$key?}","")
            if(result.contains('{')||result.contains('}'))throw SourceProblem(FailureKind.UNSUPPORTED,"此目录的搜索扩展暂不支持，请浏览目录",diagnostic="OPDS_SEARCH_TEMPLATE")
            return scope.resolve(result,base).toString()
        }
    }
}
