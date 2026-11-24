package app.leafreader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.io.File
import java.io.FileOutputStream
import app.leafreader.offline.checkDownloadPolicy
import app.leafreader.offline.DownloadStopped

data class ShelfMembershipDetail(val book:Book,val present:Boolean,val revision:Long)
data class ShelfMembershipResult(val present:Boolean,val revision:Long)

enum class FailureKind { NETWORK, AUTH, FORBIDDEN, PREPARING, RATE_LIMIT, PROTOCOL, EMPTY, UNSUPPORTED, VERSION }
class SourceProblem(val kind: FailureKind, message: String, val expired: Boolean = false,
                    val diagnostic: String = "BH_${kind.name}", val sessionRevoked: Boolean = false, val httpStatus:Int?=null) : IOException(message)
internal fun protocol(message: String = "服务返回的数据不完整，请联系服务管理员"): Nothing = throw SourceProblem(FailureKind.PROTOCOL, message)
internal fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

/** Exact server origin: published default or an explicit user override. */
class ServiceOrigin(value: String) {
    val uri: URI
    val value: String
    init {
        val u = try { URI(value.trim()) } catch (_: Exception) { protocol("请输入完整的 HTTPS 服务地址") }
        val scheme=u.scheme?.lowercase(java.util.Locale.ROOT)
        val local = u.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
        if (u.host.isNullOrBlank() || u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null ||
            u.rawPath !in listOf(null, "", "/") || (scheme != "https" && !(local && scheme == "http")) ||
            u.port !in -1..65535 || u.port == 0) protocol("服务地址只填写 HTTPS 协议、主机和端口；本机测试可用 HTTP")
        val host = u.host.lowercase(java.util.Locale.ROOT).removeSurrounding("[","]")
        val authority = if (host.contains(':')) "[$host]" else host
        this.value = "${u.scheme.lowercase()}://$authority" + if (u.port != -1 && !(scheme == "https" && u.port == 443)) ":${u.port}" else ""
        uri = URI(this.value)
    }
    fun resolve(path: String, resource: Boolean = false): URI {
        val r = try { URI(path) } catch (_: Exception) { protocol("阅读资源地址无效") }
        val decoded = r.path ?: protocol()
        if (r.isAbsolute || r.rawAuthority != null || r.rawFragment != null || !decoded.startsWith(if (resource) "/api/v1/books/" else "/api/v1/") ||
            decoded.split('/').any { it == ".." || it == "." } || decoded.any { it == '\\' || it.code < 32 } ||
            Regex("(?i)%2e|%2f|%5c|%25").containsMatchIn(r.rawPath)) protocol("已拒绝不属于当前服务的资源地址")
        return uri.resolve(r)
    }
}

data class WireResponse(val status: Int, val bytes: ByteArray, val contentType: String? = null)
fun interface WireTransport { suspend fun execute(uri: URI, method: String, headers: Map<String, String>, body: ByteArray?, maxBytes: Int): WireResponse }
interface StreamingWireTransport:WireTransport {
    suspend fun download(uri:URI,headers:Map<String,String>,file:File,maxBytes:Long,valid:()->Boolean,progress:(Long)->Unit):WireResponse
}

class UrlConnectionTransport : StreamingWireTransport {
    override suspend fun execute(uri: URI, method: String, headers: Map<String, String>, body: ByteArray?, maxBytes: Int): WireResponse = withContext(Dispatchers.IO) {
        val c = uri.toURL().openConnection() as HttpURLConnection
        try {
            checkDownloadPolicy()
            c.requestMethod = method; c.instanceFollowRedirects = false; c.connectTimeout = 10000; c.readTimeout = 20000
            c.useCaches = false
            headers.forEach { (key, value) -> c.setRequestProperty(key, value) }
            if (body != null) { c.doOutput = true; c.setFixedLengthStreamingMode(body.size);checkDownloadPolicy(); c.outputStream.use { it.write(body) } }
            checkDownloadPolicy()
            val status = c.responseCode
            // Read only a bounded error code for SESSION_EXPIRED; never echo a raw service response.
            if (status !in 200..299) return@withContext WireResponse(status, c.errorStream?.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (output.size() < 65536) {
                    val n = input.read(buffer, 0, minOf(buffer.size, 65536 - output.size()))
                    if (n < 0) break
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            } ?: ByteArray(0))
            if (c.contentLengthLong > maxBytes) protocol("章节或响应过大，本版暂不能处理")
            val output = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    checkDownloadPolicy()
                    val count = input.read(buffer); if (count < 0) break
                    checkDownloadPolicy()
                    if (output.size() + count > maxBytes) protocol("章节或响应过大，本版暂不能处理")
                    output.write(buffer, 0, count)
                }
            }
            WireResponse(status, output.toByteArray(), c.contentType)
        } catch (e: SourceProblem) { throw e }
        catch (e:DownloadStopped){throw e}
        catch (_: IOException) { throw SourceProblem(FailureKind.NETWORK, "连接失败或超时，请检查网络后重试") }
        finally { c.disconnect() }
    }
    override suspend fun download(uri:URI,headers:Map<String,String>,file:File,maxBytes:Long,valid:()->Boolean,progress:(Long)->Unit):WireResponse=withContext(Dispatchers.IO) {
        val c=uri.toURL().openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects=false;c.connectTimeout=10000;c.readTimeout=20000;c.useCaches=false
            headers.forEach{(k,v)->c.setRequestProperty(k,v)}
            if(!valid())throw app.leafreader.offline.DownloadStopped()
            val status=c.responseCode
            if(status!=200)return@withContext WireResponse(status,c.errorStream?.use { input->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                while(output.size()<65536){val n=input.read(buffer,0,minOf(buffer.size,65536-output.size()));if(n<0)break;output.write(buffer,0,n)}
                output.toByteArray()
            }?:byteArrayOf(),c.contentType)
            if(c.contentLengthLong>maxBytes)throw SourceProblem(FailureKind.PROTOCOL,"下载资源超过大小限制",diagnostic="BH_DOWNLOAD_SIZE")
            var count=0L
            c.inputStream.use { input->FileOutputStream(file).use { output->
                val buffer=ByteArray(65536)
                while(true){coroutineContext.ensureActive();if(!valid())throw app.leafreader.offline.DownloadStopped();val n=input.read(buffer);if(n<0)break;coroutineContext.ensureActive();if(!valid())throw app.leafreader.offline.DownloadStopped();count+=n;if(count>maxBytes)throw SourceProblem(FailureKind.PROTOCOL,"下载资源超过大小限制",diagnostic="BH_DOWNLOAD_SIZE");output.write(buffer,0,n);progress(count)}
                output.fd.sync()
            } }
            WireResponse(status,byteArrayOf(),c.contentType)
        }finally{c.disconnect()}
    }
}

/** Independent implementation of the observed protocol; persistence is supplied by the owning ViewModel. */
class BookHubClient(val origin: ServiceOrigin, private val transport: WireTransport = UrlConnectionTransport()) {
    private val readPermissions=java.util.concurrent.ConcurrentHashMap<String,Boolean>()
    fun observedReadPermission(bookId:String)=readPermissions[bookId]
    fun observeReadPermission(bookId:String,allowed:Boolean){readPermissions[bookId]=allowed}
    private var accessToken: String? = null
    private var refreshToken: String? = null
    var accountId: String? = null; private set
    private var deviceId = "leafreader-${UUID.randomUUID()}"
    private var sessionEpoch = 0L
    var storageOrigin: String = origin.value; private set
    fun retainStorageOrigin(value:String) { require(ServiceOrigin(value).value==origin.value);storageOrigin=value }
    private val refreshMutex = Mutex()
    var onSessionChanged: ((SavedSession?) -> Unit)? = null
    fun snapshot(): SavedSession? {
        val account = accountId ?: return null
        return SavedSession(origin.value, account, deviceId, accessToken ?: return null, refreshToken ?: return null, storageOrigin)
    }
    fun restore(session: SavedSession) {
        session.validate()
        if (ServiceOrigin(session.origin).value != origin.value) protocol("保存的会话不属于当前服务器")
        accountId = session.accountId; deviceId = session.deviceId
        retainStorageOrigin(session.storageOrigin)
        accessToken = session.accessToken; refreshToken = session.refreshToken
    }
    fun clearSession() {
        sessionEpoch++; accessToken = null; refreshToken = null; accountId = null
        onSessionChanged?.invoke(null)
    }
    private fun checkEpoch(epoch: Long) { if (epoch != sessionEpoch) throw CancellationException("Session was replaced") }

    suspend fun login(username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) protocol("请填写此服务的账号和密码")
        val epoch = sessionEpoch
        val data = send("/auth/login", "POST", JSONObject().put("username", username.trim()).put("password", password).put("deviceId", deviceId), null)
        coroutineContext.ensureActive()
        checkEpoch(epoch)
        acceptSession(data)
    }
    private fun acceptSession(data: JSONObject) {
        val account = data.optJSONObject("account") ?: protocol()
        if (account.optBoolean("mustChangePassword")) throw SourceProblem(FailureKind.AUTH, "账号需要先改密，请在原服务完成后再连接")
        val id = account.optString("id").takeIf { it.isNotBlank() } ?: protocol()
        val token = data.optString("accessToken").takeIf { it.isNotBlank() } ?: protocol()
        val refresh = data.optString("refreshToken").takeIf { it.isNotBlank() } ?: protocol()
        if (listOf(id, token, refresh).any { value -> value.length > 16384 || value.any { it.code < 32 } }) protocol()
        data.optString("deviceId").takeIf { it.isNotBlank() && it.length <= 256 && it.none { c -> c.code < 32 } }?.let { deviceId = it }
        accountId = id; accessToken = token; refreshToken = refresh
        onSessionChanged?.invoke(snapshot())
    }
    suspend fun api(path: String): JSONObject = authorized(path,"GET")
    suspend fun apiPost(path:String):JSONObject = authorized(path,"POST")
    private suspend fun authorized(path:String,method:String,body:JSONObject?=null):JSONObject {
        val token = accessToken ?: throw SourceProblem(FailureKind.AUTH, "请先连接服务并登录")
        val epoch = sessionEpoch
        try { return send(path, method, body, token).also { checkEpoch(epoch) } }
        catch (e: SourceProblem) {
            checkEpoch(epoch)
            if (e.sessionRevoked || (e.kind == FailureKind.AUTH && !e.expired)) { clearSession(); throw e }
            if (e.kind != FailureKind.AUTH || !e.expired) throw e
            refreshMutex.withLock {
                checkEpoch(epoch)
                if (accessToken == token) refreshSession()
            }
            checkEpoch(epoch)
            val fresh = accessToken ?: throw SourceProblem(FailureKind.AUTH, "登录已失效，请重新登录")
            if(body?.has("deviceId")==true)body.put("deviceId",deviceId)
            try { return send(path, method, body, fresh).also { checkEpoch(epoch) } }
            catch (failure: SourceProblem) {
                if (failure.kind == FailureKind.AUTH || failure.sessionRevoked) clearSession()
                throw failure
            }
        }
    }
    /** Verified against the supplied original api.ts detailPath/command and library shelfToggle. */
    suspend fun verifyShelfAccount() {
        val page=api("/me/bookshelf?limit=20")
        if(page.opt("items") !is org.json.JSONArray || page.opt("hasMore") !is Boolean)protocol("账号书架响应无效，未更改名单")
    }
    suspend fun shelfDetail(bookId:String,verifyAccount:Boolean=true):ShelfMembershipDetail {
        requireShelfBook(bookId)
        if(verifyAccount)verifyShelfAccount()
        val path=if(bookId.startsWith("private:"))"/me/private-books/" else "/catalog/books/"
        val data=api(path+encoded(bookId))
        if(data.optString("bookId")!=bookId || data.opt("inBookshelf") !is Boolean)protocol("服务未提供本书的个人书架状态，未更改名单")
        val raw=data.opt("shelfRevision")
        val revision=if(raw==null || raw==JSONObject.NULL)0L else shelfRevision(raw)
        val permission=data.optJSONObject("capabilities")?.opt("canRead")
        if(permission!=null && permission!=JSONObject.NULL && permission !is Boolean)protocol("书籍授权字段无效")
        if(permission is Boolean)observeReadPermission(bookId,permission)
        val book=Book(bookId,data.optString("title").takeUnless{it.isBlank() || it=="null"}?:"未命名图书",
            data.optString("author").takeUnless{it.isBlank() || it=="null"}?:"作者未注明",data.optString("description").takeUnless{it=="null"}.orEmpty(),"我的服务",0,0xFF516D66,
            data.optString("format").takeIf{it.isNotBlank() && it!="null"}?:protocol("书籍格式字段缺失"),readAllowed=permission as? Boolean)
        return ShelfMembershipDetail(book,data.getBoolean("inBookshelf"),revision)
    }
    suspend fun changeShelf(bookId:String,present:Boolean,baseRevision:Long):ShelfMembershipResult {
        requireShelfBook(bookId);if(baseRevision<0)protocol("书架修订号无效")
        val command=JSONObject().put("operationId",UUID.randomUUID().toString().replace("-",""))
            .put("deviceId",deviceId).put("baseRevision",baseRevision)
        val data=try{authorized("/me/bookshelf/"+encoded(bookId),if(present)"PUT" else "DELETE",command)}
        catch(e:SourceProblem){if(e.httpStatus==409)throw SourceProblem(FailureKind.VERSION,"书架已在其他设备变化，请刷新后重试",diagnostic="BH_SHELF_REVISION_CONFLICT",httpStatus=409);throw e}
        val state=data.optJSONObject("state")?:protocol("服务未确认书架操作，保留本机记录")
        val revision=shelfRevision(state.opt("revision"))
        if(revision<baseRevision || !state.has("deletedAt"))protocol("书架操作修订无效，保留本机记录")
        val deleted=state.opt("deletedAt")
        if(deleted!=JSONObject.NULL && (deleted !is String || deleted.isBlank() || deleted.length>128 || deleted.any{it.code<32}))protocol("服务未确认书架状态")
        val actual=deleted==JSONObject.NULL
        if(actual!=present)protocol("服务返回的书架状态与操作不一致，保留本机记录")
        return ShelfMembershipResult(actual,revision)
    }
    private fun requireShelfBook(id:String) {
        if(id.length>128 || !Regex("^(public:[0-9]+|(?:private|shared):[a-fA-F0-9-]{36})$").matches(id))protocol("书籍标识无效，未发出书架操作")
    }
    private fun shelfRevision(raw:Any?):Long {
        val n=raw as? Number?:protocol("书架修订号缺失或无效")
        val d=n.toDouble();val value=n.toLong()
        if(!d.isFinite() || value<0 || value>9007199254740991L || d!=value.toDouble())protocol("书架修订号无效")
        return value
    }
    private suspend fun refreshSession() {
        val refresh = refreshToken ?: throw SourceProblem(FailureKind.AUTH, "登录已失效，请重新连接服务")
        val epoch = sessionEpoch
        try {
            val oldAccount = accountId
            val data = send("/auth/refresh", "POST", JSONObject().put("refreshToken", refresh), null)
            checkEpoch(epoch)
            if (data.optJSONObject("account")?.optString("id") != oldAccount) { clearSession(); protocol("会话账号发生变化，请重新连接") }
            acceptSession(data)
        } catch (e: SourceProblem) { if (e.kind == FailureKind.AUTH || e.kind == FailureKind.FORBIDDEN) clearSession(); throw e }
    }
    private suspend fun send(path: String, method: String, body: JSONObject?, token: String?): JSONObject {
        checkDownloadPolicy()
        val headers = mutableMapOf("Accept" to "application/json")
        if (body != null) headers["Content-Type"] = "application/json; charset=utf-8"
        if (token != null) headers["Authorization"] = "Bearer $token"
        val response = transport.execute(origin.resolve("/api/v1$path"), method, headers, body?.toString()?.toByteArray(), 2 * 1024 * 1024)
        status(response)
        return try {
            val envelope = JSONObject(utf8(response.bytes))
            if (envelope.opt("code") !is String) protocol()
            envelope.optJSONObject("data") ?: protocol()
        } catch (e: SourceProblem) { throw e } catch (_: Exception) { protocol() }
    }
    suspend fun resource(path: String, expectedFormat: String? = null): String {
        // Resources use their server-issued ticket; never forward the account Bearer to a URL from a manifest.
        val epoch = sessionEpoch
        val response = transport.execute(origin.resolve(path, resource = true), "GET", mapOf("Accept" to "text/plain, application/xhtml+xml, text/html"), null, 4 * 1024 * 1024)
        checkEpoch(epoch); coroutineContext.ensureActive()
        status(response)
        if (response.status != 200) throw SourceProblem(FailureKind.PROTOCOL, "正文未返回完整内容，旧进度已保留", diagnostic = "BH_RESOURCE_PARTIAL")
        val media = response.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (media.contains("json")) throw SourceProblem(FailureKind.PROTOCOL, "正文请求返回接口数据，未当作书籍文字", diagnostic = "BH_RESOURCE_JSON")
        if (media.startsWith("image/") || media == "application/pdf" || media.startsWith("audio/"))
            throw SourceProblem(FailureKind.UNSUPPORTED, "当前章节返回非文字资源，本版无法排版", diagnostic = "BH_RESOURCE_BINARY")
        if (expectedFormat == "TXT" && media in listOf("text/html", "application/xhtml+xml"))
            throw SourceProblem(FailureKind.PROTOCOL, "TXT 正文请求返回网页，旧进度已保留", diagnostic = "BH_TXT_HTML")
        val text = utf8(response.bytes)
        if (text.isBlank()) throw SourceProblem(FailureKind.EMPTY, "服务器返回空白正文，旧进度已保留", diagnostic = "BH_RESOURCE_EMPTY")
        if (text.trimStart().startsWith("{")) {
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (json?.has("code") == true && (json.has("message") || json.has("data")))
                throw SourceProblem(FailureKind.PROTOCOL, "正文请求返回接口响应，未当作书籍文字", diagnostic = "BH_RESOURCE_JSON")
        }
        return text
    }
    /** Only explicit current-manifest raster resources; no bearer, redirects, SVG execution or disk cache. */
    suspend fun raster(path: String): ByteArray {
        val epoch = sessionEpoch
        val response = transport.execute(origin.resolve(path, resource = true), "GET",
            mapOf("Accept" to "image/png, image/jpeg, image/webp, image/gif"), null, 8 * 1024 * 1024)
        checkEpoch(epoch); coroutineContext.ensureActive(); status(response)
        if (response.status != 200 || response.bytes.size !in 1..8*1024*1024)
            throw SourceProblem(FailureKind.PROTOCOL,"图片未返回完整内容，原进度已保留",diagnostic="BH_IMAGE_RESPONSE")
        val type=response.contentType?.substringBefore(';')?.trim()?.lowercase()
        if(type != null && type !in setOf("image/jpeg","image/png","image/webp","image/gif","application/octet-stream"))
            throw SourceProblem(FailureKind.PROTOCOL,"图片返回了其他内容，原进度已保留",diagnostic="BH_IMAGE_TYPE")
        return response.bytes
    }
    /** Observed single-cover contract. Passive thumbnail reads never log in, refresh or revoke a session. */
    internal suspend fun coverVersion(bookId:String):String {
        val r=passiveCoverRequest(bookId,"access",2*1024*1024,"application/json")
        val j=JSONObject(utf8(r.bytes));val d=j.optJSONObject("data")?:protocol()
        if(j.optString("code")!="OK" || d.optString("bookId")!=bookId || d.optString("format")!="EPUB")protocol()
        return d.optString("contentVersion").takeIf{it.isNotBlank() && it.length<=512 && it.none{c->c.code<32}}?:protocol()
    }
    internal suspend fun coverBytes(bookId:String):ByteArray {
        val r=passiveCoverRequest(bookId,"cover",8*1024*1024,"image/png, image/jpeg, image/webp, image/gif")
        val type=r.contentType?.substringBefore(';')?.trim()?.lowercase()
        if(type!=null && type !in setOf("image/png","image/jpeg","image/webp","image/gif","application/octet-stream"))protocol()
        return r.bytes
    }
    private suspend fun passiveCoverRequest(bookId:String,part:String,limit:Int,accept:String):WireResponse {
        coroutineContext.ensureActive()
        if(!Regex("^(public:[0-9]+|(?:private|shared):[a-fA-F0-9-]{36})$").matches(bookId))protocol()
        val epoch=sessionEpoch;val token=accessToken?:throw SourceProblem(FailureKind.AUTH,"请先登录")
        val r=transport.execute(origin.resolve("/api/v1/books/${encoded(bookId)}/$part"),"GET",mapOf("Authorization" to "Bearer $token","Accept" to accept),null,limit)
        checkEpoch(epoch);coroutineContext.ensureActive();status(r)
        if(r.status!=200 || r.bytes.size !in 1..limit)protocol()
        return r
    }
    fun textBytes(bytes:ByteArray):String=utf8(bytes)
    suspend fun downloadFile(path:String,file:File,maxBytes:Long,valid:()->Boolean,progress:(Long)->Unit={},expectedType:String?=null) {
        val epoch=sessionEpoch;val uri=origin.resolve(path,resource=true)
        val headers=mapOf("Accept" to "application/octet-stream, application/zip, text/plain, application/xhtml+xml, image/*")
        val response=if(transport is StreamingWireTransport) transport.download(uri,headers,file,maxBytes,valid,progress) else {
            if(!valid())throw app.leafreader.offline.DownloadStopped()
            val r=transport.execute(uri,"GET",headers,null,maxBytes.toInt());checkEpoch(epoch);status(r)
            if(!valid())throw app.leafreader.offline.DownloadStopped()
            if(r.bytes.size>maxBytes)throw SourceProblem(FailureKind.PROTOCOL,"下载资源超过大小限制",diagnostic="BH_DOWNLOAD_SIZE")
            if(r.status==200)FileOutputStream(file).use{it.write(r.bytes);it.fd.sync();progress(r.bytes.size.toLong())}
            r
        }
        checkEpoch(epoch);coroutineContext.ensureActive();status(response)
        if(!valid())throw app.leafreader.offline.DownloadStopped()
        if(response.status!=200)throw SourceProblem(FailureKind.PROTOCOL,"下载未返回完整资源",diagnostic="BH_DOWNLOAD_PARTIAL")
        if(response.contentType?.contains("json",ignoreCase=true)==true && expectedType?.contains("json",ignoreCase=true)!=true)
            throw SourceProblem(FailureKind.PROTOCOL,"下载返回接口错误而非书籍资源",diagnostic="BH_DOWNLOAD_JSON")
        if(expectedType=="text/plain" && response.contentType?.substringBefore(';')?.trim()?.lowercase() in listOf("text/html","application/xhtml+xml"))
            throw SourceProblem(FailureKind.PROTOCOL,"TXT 下载返回网页，未标记为已下载",diagnostic="BH_TXT_HTML")
    }
    private fun status(response: WireResponse) {
        val code = response.status
        val errorCode = if (code in listOf(401, 403)) runCatching { JSONObject(utf8(response.bytes)).optString("code") }.getOrNull() else null
        val expired = code == 401 && errorCode == "SESSION_EXPIRED"
        val revoked = errorCode in listOf("SESSION_REVOKED", "ACCOUNT_DISABLED")
        when {
            code == 202 -> throw SourceProblem(FailureKind.PREPARING, "图书正在准备，请稍后重试")
            code == 401 -> throw SourceProblem(FailureKind.AUTH, "登录或阅读凭证已失效，请重新连接或重试", expired, "BH_HTTP_401", revoked)
            code == 403 -> throw SourceProblem(FailureKind.FORBIDDEN, "当前账号无权读取此内容，请联系服务管理员", diagnostic = "BH_HTTP_403", sessionRevoked = revoked)
            code == 429 -> throw SourceProblem(FailureKind.RATE_LIMIT, "请求过于频繁，请稍后重试")
            code !in 200..299 -> throw SourceProblem(FailureKind.NETWORK, "服务暂时不可用，请稍后重试",httpStatus=code)
        }
    }
    private fun utf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (_: Exception) { throw SourceProblem(FailureKind.PROTOCOL, "正文不是有效的 UTF-8 文本", diagnostic = "BH_UTF8") }
}
