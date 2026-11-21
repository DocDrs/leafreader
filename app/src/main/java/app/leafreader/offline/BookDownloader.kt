package app.leafreader.offline

import app.leafreader.data.*
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/** Uses only the observed access/reading-package/resource contracts, never original-file export. */
class BookDownloader(private val client:BookHubClient,private val store:OfflineStore,private val connection:ConnectionState) {
    suspend fun download(bookId:String,allowMetered:Boolean,valid:()->Boolean={true},progress:(String)->Unit={}) {
        withContext(DownloadPolicy{valid() && connection.online && (allowMetered || connection.wifi)}){downloadChecked(bookId,allowMetered,valid,progress)}
    }
    private suspend fun downloadChecked(bookId:String,allowMetered:Boolean,valid:()->Boolean,progress:(String)->Unit) {
        fun allowed()=valid() && connection.online && (allowMetered || connection.wifi)
        fun check(){if(!allowed())throw DownloadStopped()}
        check();val epoch=store.generation(bookId)
        progress("正在准备下载")
        val access=client.api("/books/${encoded(bookId)}/access")
        if(access.optString("bookId")!=bookId)protocol("下载清单与当前书籍不一致")
        // Reuse the unmodified parser contract before committing any manifest.
        BookHubSource(client,store,connection).validateAccess(access)
        if(!store.saveManifest(bookId,access,epoch))throw DownloadStopped()
        val version=access.getString("contentVersion");val resources=access.getJSONArray("resources")
        if(store.complete(bookId,access,epoch)){progress("整本已下载");return}
        var bytesBudget=0L
        fun own(path:String) {
            val uri=client.origin.resolve(path,resource=true)
            if(!uri.path.startsWith("/api/v1/books/$bookId/"))protocol("下载链接不属于当前书籍")
        }
        val bundle=try {check();client.apiPost("/books/${encoded(bookId)}/reading-package")}
        catch(e:SourceProblem) {if(e.httpStatus in setOf(404,405))null else throw e}
        if(bundle!=null) {
            val size=bundle.optLong("sizeBytes",-1);val hash=bundle.optString("sha256");val url=bundle.optString("downloadUrl")
            if(bundle.optString("kind")!="READING_ZIP" || bundle.optInt("schemaVersion")!=1 || bundle.optString("contentVersion")!=version || size !in 22..134217728 || !Regex("[a-f0-9]{64}").matches(hash))
                throw SourceProblem(FailureKind.PROTOCOL,"整本阅读包描述无效，未标记为已下载",diagnostic="BH_DOWNLOAD_PACKAGE")
            own(url);store.requireSpace(size);val temp=store.temporary(bookId,"bundle")
            try {
                client.downloadFile(url,temp,size,::allowed,{n->progress("下载阅读包 ${(n*100/size).coerceAtMost(100)}%")})
                check();if(temp.length()!=size || sha(temp)!=hash)throw SourceProblem(FailureKind.PROTOCOL,"整本阅读包不完整或校验失败",diagnostic="BH_DOWNLOAD_HASH")
                ReadingPackage.install(temp,access,store,epoch,::allowed){done,total->progress("正在校验资源 $done/$total")}
            }finally{store.finishTemporary(temp)}
        } else {
            // The supplied client explicitly falls back only when this optional route is absent.
            for(i in 0 until resources.length()) {
                coroutineContext.ensureActive();check();if(!store.isCurrent(bookId,epoch))throw DownloadStopped()
                val r=resources.getJSONObject(i);val id=r.getString("resourceId");val url=r.getString("url");own(url)
                if(!store.hasResource(bookId,version,id)) {
                    val temp=store.temporary(bookId,"resource-$id")
                    try {
                        client.downloadFile(url,temp,67108864,::allowed,expectedType=r.optString("mediaType"))
                        bytesBudget+=temp.length();if(bytesBudget>276824064)throw SourceProblem(FailureKind.UNSUPPORTED,"整本资源超过本版大小限制",diagnostic="BH_DOWNLOAD_SIZE")
                        val type=r.optString("mediaType")
                        if(type in listOf("application/xhtml+xml","text/html","text/plain") && temp.length()<=4*1024*1024) {
                            val text=client.textBytes(temp.readBytes())
                            val json=if(text.trimStart().startsWith("{"))runCatching{JSONObject(text)}.getOrNull() else null
                            if(json?.has("code")==true && (json.has("data") || json.has("message")))throw SourceProblem(FailureKind.PROTOCOL,"资源返回接口响应，未标记为已下载",diagnostic="BH_DOWNLOAD_JSON")
                            if(type!="text/plain")EpubTextDocument(text)
                        }
                        if(!store.acceptFile(bookId,version,id,temp,epoch,false))throw DownloadStopped()
                    }finally{store.finishTemporary(temp)}
                }
                progress("正在下载资源 ${i+1}/${resources.length()}")
            }
        }
        check();if(!store.complete(bookId,access,epoch))throw DownloadStopped();OfflineEvents.changed();progress("整本已下载")
    }
}
