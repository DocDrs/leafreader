package app.leafreader.data

import android.content.Context
import android.graphics.Bitmap
import app.leafreader.offline.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

@android.annotation.SuppressLint("ApplySharedPref")
class LegadoSources(context:Context) {
    private val prefs=context.getSharedPreferences("legado-sources-v1",Context.MODE_PRIVATE)
    fun all():List<LegadoDefinition> = runCatching{val a=JSONArray(prefs.getString("items","[]"));(0 until a.length()).flatMap{ i->runCatching{LegadoRules.parse(a.getJSONObject(i).getString("rules"),a.getJSONObject(i).getBoolean("rights"))}.getOrDefault(emptyList())}}.getOrDefault(emptyList())
    fun save(definitions:List<LegadoDefinition>):Boolean {
        val rows=(all()+definitions).distinctBy{it.id};if(rows.size>30)ruleError("最多保存 30 个基础规则来源")
        return prefs.edit().putString("items",JSONArray().apply{rows.forEach{put(JSONObject().put("rules",it.json).put("rights",it.retainedRights))}}.toString()).commit()
    }
}

/** An accountless, pinned-TOC text source. Never uses the BookHub transport/session. */
@android.annotation.SuppressLint("ApplySharedPref")
class LegadoSource(context:Context,val definition:LegadoDefinition,private val network:ConnectionState=AndroidConnectionState(context),transport:WireTransport?=null):IndependentBookSource {
    private val scope=CatalogOrigin(definition.origin)
    private val wire=transport?:AnonymousHttp(scope,30_000)
    private val config=definition.config
    override val supportsAutomaticDownload:Boolean get()=definition.retainedRights
    override val id=definition.id
    override val label=definition.name
    override val independentOffline=definition.retainedRights
    override val canBrowse=false
    override val kindLabel="Legado 基础 · 无需登录"
    private val root=File(context.noBackupFilesDir,"legado-text-v1/$id").apply{mkdirs()}
    private val prefs=context.getSharedPreferences("legado-cache-$id",Context.MODE_PRIVATE)
    private val sharedIo=IndependentSourceIo.forRoot(root)
    private val requestLock=sharedIo.requests
    private var lastRequest:Long
        get()=sharedIo.lastRequest.get()
        set(value){sharedIo.lastRequest.set(value)}
    private val locks=sharedIo.books
    private val thumb=object:android.util.LruCache<String,Bitmap>(2*1024*1024){override fun sizeOf(k:String,v:Bitmap)=v.allocationByteCount}
    private val fingerprints=linkedMapOf<String,MutableMap<Int,String>>()
    private fun problem(message:String,kind:FailureKind=FailureKind.PROTOCOL,code:String="LG_PROTOCOL"):Nothing=throw SourceProblem(kind,message,diagnostic=code)
    private fun directory(book:String):File {if(!book.matches(Regex("lg:[a-f0-9]{64}")))problem("书籍标识无效");return File(root,book.substringAfter(':')).apply{mkdirs()}}
    private fun lock(book:String)=locks.getOrPut(book){Mutex()}
    private fun epoch(book:String)=prefs.getLong("epoch:$book",0)
    private fun hash(b:ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
    private suspend fun fetch(url:String,cap:Int=512*1024):ByteArray=requestLock.withLock {
        coroutineContext.ensureActive();if(!network.online)problem("当前离线，这部分尚未缓存",FailureKind.NETWORK,"LG_OFFLINE")
        if(System.currentTimeMillis()<prefs.getLong("retry-after",0))problem("来源暂不可用，请稍后重试",FailureKind.NETWORK,"LG_BACKOFF")
        checkDownloadPolicy();val wait=500-(System.currentTimeMillis()-lastRequest);if(wait>0)delay(wait);lastRequest=System.currentTimeMillis()
        val response=try{wire.execute(scope.resolve(url),"GET",mapOf("Accept" to "text/html,application/json;q=0.9,image/*;q=0.5"),null,cap)}catch(e:CatalogBackoff){prefs.edit().putLong("retry-after",System.currentTimeMillis()+e.waitMillis).commit();problem("来源暂不可用，请稍后重试",FailureKind.NETWORK,"LG_BACKOFF")}catch(e:SourceProblem){throw e}catch(e:java.net.SocketTimeoutException){problem("来源响应超时，请重试",FailureKind.NETWORK,"LG_TIMEOUT")}catch(e:java.io.IOException){problem("来源连接失败，请重试",FailureKind.NETWORK,"LG_NETWORK")}
        checkDownloadPolicy()
        if(response.status in setOf(401,403))problem("来源要求授权；基础规则不支持登录或 Cookie",FailureKind.AUTH,"LG_AUTH")
        if(response.status in setOf(429,503)){prefs.edit().putLong("retry-after",System.currentTimeMillis()+21600_000).commit();problem("来源暂不可用，请稍后重试",FailureKind.NETWORK,"LG_BACKOFF")}
        if(response.status!=200)problem("来源请求失败，请重试",FailureKind.NETWORK,"LG_HTTP_${response.status}")
        if(response.bytes.size>cap)problem("来源资源大小超限",code="LG_SIZE");response.bytes
    }
    private fun text(bytes:ByteArray)=try{Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")}catch(_:java.nio.charset.CharacterCodingException){problem("基础规则只支持 UTF-8 响应",FailureKind.UNSUPPORTED,"LG_ENCODING")}
    private suspend fun page(url:String)=BasicRule.document(text(fetch(url)))
    private fun rules(group:String)=config.getJSONObject(group)
    private fun value(doc:Any,group:String,key:String)=BasicRule.value(doc,rules(group).optString(key))
    private fun bookId(url:String)="lg:"+digest(url)
    private fun localJson(file:File):JSONObject {if(file.length()>1024*1024)problem("本机记录大小超限",code="LG_CACHE");val t=JSONTokener(file.readText());val j=t.nextValue()as?JSONObject?:problem("本机缓存格式无效",code="LG_CACHE");if(t.nextClean().code!=0)problem("本机缓存格式无效",code="LG_CACHE");return j}
    private fun meta(book:String):JSONObject=try{val j=localJson(File(directory(book),"metadata.json"));if(bookId(j.getString("url"))!=book)problem("书目记录不匹配");scope.resolve(j.getString("url"));j}catch(e:SourceProblem){throw e}catch(_:Exception){problem("缺少书目记录，请重新搜索此来源",FailureKind.NETWORK,"LG_METADATA")}
    private fun asBook(book:String,j:JSONObject)=Book(book,j.getString("name"),j.optString("author").ifBlank{"作者未注明"},j.optString("intro"),label,0,0xFF78664F,"TXT",id)
    override suspend fun books():List<Book> = emptyList()
    override suspend fun browse(query:String,cursor:String?):BookPage=withContext(Dispatchers.IO) {
        if(query.isBlank())return@withContext BookPage(emptyList())
        if(query.length>200)problem("关键词过长")
        val q=digest(query);val c=cursor?.let{try{JSONObject(it)}catch(_:Exception){problem("搜索分页无效")}}
        val at=c?.optInt("page",0)?:1;if(at !in 1..100 || c!=null&&c.optString("query")!=q)problem("搜索分页已失效",FailureKind.VERSION)
        val template=config.getString("searchUrl")
        fun searchUrl(n:Int)=scope.resolve(template.replace("{{key}}",encoded(query)).replace("{{page}}",n.toString())).toString()
        val url=c?.optString("url")?.takeIf{it.isNotBlank()}?.let{scope.resolve(it).toString()}?:searchUrl(at)
        val doc=page(url);val nodes=BasicRule.nodes(doc,rules("ruleSearch").getString("bookList"),requiredPath=true);if(nodes.size>200)problem("单页搜索条目过多",code="LG_SIZE")
        val books=nodes.map{n->val target=scope.resolve(value(n,"ruleSearch","bookUrl"),url).toString();val name=value(n,"ruleSearch","name").trim().take(600);if(name.isBlank()||value(n,"ruleSearch","bookUrl").isBlank())problem("搜索规则未取到书名或链接",code="LG_FIELDS")
            val book=bookId(target);lock(book).withLock {val old=runCatching{meta(book)}.getOrNull();val j=old?:JSONObject().put("url",target)
            if(old?.optBoolean("detail",false)!=true){j.put("name",name).put("author",value(n,"ruleSearch","author").take(600)).put("intro",org.jsoup.Jsoup.parse(value(n,"ruleSearch","intro")).text().take(4000));val art=value(n,"ruleSearch","coverUrl");if(art.isNotBlank())runCatching{scope.resolve(art,url).toString()}.getOrNull()?.let{j.put("cover",it)};atomic(File(directory(book),"metadata.json"),j.toString().toByteArray())}
            asBook(book,j)}
        }
        if(books.map{it.id}.distinct().size!=books.size)problem("搜索返回重复书籍标识",code="LG_DUPLICATE")
        val stamp=digest(books.joinToString("|"){it.id})
        synchronized(fingerprints){if(at==1)fingerprints[q]=mutableMapOf();val seen=fingerprints.getOrPut(q){mutableMapOf()};if(at>1&&books.isNotEmpty()&&stamp in seen.filterKeys{it!=at}.values)problem("来源分页重复，请重新搜索",code="LG_PAGE_LOOP");seen[at]=stamp;while(fingerprints.size>4)fingerprints.remove(fingerprints.keys.first())}
        val advertised=value(doc,"ruleSearch","nextUrl")
        val next=if(books.isEmpty()||at>=100)null else if(advertised.isNotBlank())scope.resolve(advertised,url).toString()else if(template.contains("{{page}}"))searchUrl(at+1)else null
        BookPage(books, next?.let{JSONObject().put("query",q).put("page",at+1).put("url",it).toString()},next!=null)
    }
    private suspend fun detailLocked(book:String):JSONObject {
        val j=meta(book);if(j.optBoolean("detail",false))return j
        val generation=epoch(book);val doc=page(j.getString("url"));for(k in listOf("name","author","intro")){val v=value(doc,"ruleBookInfo",k).trim();if(v.isNotEmpty())j.put(k,if(k=="intro")org.jsoup.Jsoup.parse(v).text().take(4000)else v.take(600))}
        val cover=value(doc,"ruleBookInfo","coverUrl");if(cover.isNotBlank())runCatching{scope.resolve(cover,j.getString("url")).toString()}.getOrNull()?.let{j.put("cover",it)}
        val toc=value(doc,"ruleBookInfo","tocUrl");j.put("toc",if(toc.isBlank())j.getString("url")else scope.resolve(toc,j.getString("url")).toString()).put("detail",true)
        coroutineContext.ensureActive();if(generation!=epoch(book))throw CancellationException("Source copy invalidated")
        atomic(File(directory(book),"metadata.json"),j.toString().toByteArray());return j
    }
    override suspend fun details(book:Book):Book=withContext(Dispatchers.IO){lock(book.id).withLock {asBook(book.id,detailLocked(book.id))}}
    private suspend fun tocLocked(book:String):JSONObject {
        val f=File(directory(book),"toc.json");if(f.isFile)return readToc(f)
        val generation=epoch(book);val j=detailLocked(book);val url=j.getString("toc");val doc=page(url)
        val nodes=BasicRule.nodes(doc,rules("ruleToc").getString("chapterList"),requiredPath=true);if(nodes.isEmpty())problem("目录规则未取到章节",FailureKind.EMPTY,"LG_TOC_EMPTY");if(nodes.size>1000)problem("目录超过 1000 章限制",FailureKind.UNSUPPORTED,"LG_SIZE")
        val entries=nodes.map{n->val title=value(n,"ruleToc","chapterName").trim().take(600);val href=value(n,"ruleToc","chapterUrl");if(title.isBlank()||href.isBlank())problem("目录规则未取到章名或链接",code="LG_FIELDS");JSONObject().put("title",title).put("url",scope.resolve(href,url).toString())}
        if(entries.map{it.getString("url")}.distinct().size!=entries.size)problem("目录重复链接不在基础规则支持范围",FailureKind.UNSUPPORTED,"LG_TOC_DUPLICATE")
        val version=digest(entries.joinToString("|"){it.toString()});val a=JSONArray();entries.forEach{e->e.put("id","lgc:${version.take(16)}:${digest(e.getString("url")).take(32)}");a.put(e)}
        val result=JSONObject().put("version",version).put("chapters",a)
        coroutineContext.ensureActive();if(generation!=epoch(book))throw CancellationException("TOC invalidated")
        atomic(f,result.toString().toByteArray());OfflineEvents.changed();return result
    }
    private fun readToc(f:File):JSONObject=try{if(f.length()>1024*1024)problem("本机目录过大");val j=localJson(f);val a=j.getJSONArray("chapters");if(a.length() !in 1..1000)problem("本机目录不完整");val rows=(0 until a.length()).map{a.getJSONObject(it)}
        val expected=digest(rows.joinToString("|"){JSONObject().put("title",it.getString("title")).put("url",it.getString("url")).toString()});if(j.getString("version")!=expected)problem("本机目录校验失败")
        for(r in rows)if(r.getString("id")!="lgc:${expected.take(16)}:${digest(r.getString("url")).take(32)}")problem("本机章节标识无效");j
    }catch(e:SourceProblem){throw e}catch(_:Exception){problem("本机目录损坏，请清除本书下载后重新取得",code="LG_CACHE")}
    private fun entries(toc:JSONObject)=(0 until toc.getJSONArray("chapters").length()).map{toc.getJSONArray("chapters").getJSONObject(it)}
    override suspend fun chapters(bookId:String):List<ChapterRef> = withContext(Dispatchers.IO){lock(bookId).withLock {entries(tocLocked(bookId)).map{ChapterRef(it.getString("id"),it.getString("title"))}}}
    private fun chapterFile(book:String,chapter:String)=File(directory(book),"chapter-${digest(chapter)}.json")
    private fun localChapter(book:String,row:JSONObject):Chapter? {
        val f=chapterFile(book,row.getString("id"));if(!f.isFile)return null
        try{if(f.length()>1024*1024)problem("本机章节过大");val j=localJson(f);val text=j.getString("text");if(j.getString("id")!=row.getString("id")||j.getString("sha256")!=hash(text.toByteArray())||text.isBlank())problem("本机章节校验失败",code="LG_CACHE");return Chapter(row.getString("id"),row.getString("title"),listOf(text),headingOnly=true)}catch(e:SourceProblem){throw e}catch(_:Exception){problem("本机章节损坏，请清除下载后重试",code="LG_CACHE")}
    }
    override suspend fun chapter(bookId:String,chapterId:String):Chapter=withContext(Dispatchers.IO){lock(bookId).withLock {
        val row=entries(tocLocked(bookId)).firstOrNull{it.getString("id")==chapterId}?:problem("原章节已失效，请主动选择目录；原进度保留",FailureKind.VERSION,"LG_VERSION")
        localChapter(bookId,row)?.let{return@withLock it};val generation=epoch(bookId)
        val body=BasicRule.content(page(row.getString("url")),rules("ruleContent").getString("content"));if(body.isBlank())problem("正文规则未取到文字；本版不显示脚本或图片正文",FailureKind.EMPTY,"LG_CONTENT_EMPTY")
        if(body.toByteArray().size>512*1024)problem("章节正文大小超限",FailureKind.UNSUPPORTED,"LG_SIZE")
        coroutineContext.ensureActive();if(generation!=epoch(bookId))throw CancellationException("Chapter invalidated")
        val payload=JSONObject().put("id",chapterId).put("text",body).put("sha256",hash(body.toByteArray())).toString().toByteArray()
        val dir=directory(bookId);if((dir.listFiles()?.filter{it.name.startsWith("chapter-")}?.sumOf{it.length()}?:0)+payload.size>64L*1024*1024)problem("此书缓存超过 64 MB 限制",FailureKind.UNSUPPORTED,"LG_SIZE")
        atomic(chapterFile(bookId,chapterId),payload);OfflineEvents.changed();Chapter(chapterId,row.getString("title"),listOf(body),headingOnly=true)
    }}
    override suspend fun chapterStatus(book:String,chapter:String):String=withContext(Dispatchers.IO){lock(book).withLock{try{val f=File(directory(book),"toc.json");val row=if(f.isFile)entries(readToc(f)).firstOrNull{it.getString("id")==chapter}else null;if(row!=null&&localChapter(book,row)!=null)"可离线读"else"未缓存"}catch(e:CancellationException){throw e}catch(_:Exception){"缓存损坏"}}}
    override suspend fun status(book:String):OfflineStatus=withContext(Dispatchers.IO){lock(book).withLock {
        val f=File(directory(book),"toc.json");if(!f.isFile)return@withLock OfflineStatus()
        try{val rows=entries(readToc(f));val saved=rows.count{runCatching{localChapter(book,it)!=null}.getOrDefault(false)};OfflineStatus(saved,rows.size,saved==rows.size)}catch(e:CancellationException){throw e}catch(_:Exception){OfflineStatus()}
    }}
    override suspend fun download(book:String,allowMetered:Boolean) {
        if(!allowMetered&&!network.wifi)problem("请连接不计费 Wi-Fi 或明确允许流量",FailureKind.NETWORK,"LG_WIFI")
        val generation=epoch(book)
        withContext(DownloadPolicy{(allowMetered||network.wifi)&&generation==epoch(book)}){for(ref in chapters(book)){checkDownloadPolicy();chapter(book,ref.id)}}
    }
    override suspend fun clear(book:String)=withContext(Dispatchers.IO){synchronized(prefs){check(prefs.edit().putLong("epoch:$book",epoch(book)+1).commit())};lock(book).withLock{thumb.remove(book);directory(book).listFiles()?.filter{it.name!="metadata.json"}?.forEach{check(it.deleteRecursively()){"本机副本未能清除，请重试"}};runCatching{meta(book)}.getOrNull()?.let{j->j.put("detail",false);j.remove("toc");atomic(File(directory(book),"metadata.json"),j.toString().toByteArray())};OfflineEvents.changed()}}
    override suspend fun thumbnail(book:String):Bitmap?=withContext(Dispatchers.IO) {
        thumb.get(book)?.let{return@withContext it}
        try{val generation=epoch(book);val f=File(directory(book),"thumb.jpg");val bytes=if(f.isFile)f.readBytes()else meta(book).optString("cover").takeIf{it.isNotBlank()}?.let{fetch(it,512*1024)}?:return@withContext null
            val decoded=SafeRaster.decode(bytes);val scale=minOf(1f,180f/decoded.width,270f/decoded.height);val small=Bitmap.createScaledBitmap(decoded,(decoded.width*scale).toInt().coerceAtLeast(1),(decoded.height*scale).toInt().coerceAtLeast(1),true);if(small!==decoded)decoded.recycle()
            lock(book).withLock{if(generation!=epoch(book)){small.recycle();return@withContext null};val out=java.io.ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,88,out);atomic(f,out.toByteArray());thumb.put(book,small)};small
        }catch(e:CancellationException){throw e}catch(_:Exception){null}
    }
}
