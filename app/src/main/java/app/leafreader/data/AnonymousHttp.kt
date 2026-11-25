package app.leafreader.data

import app.leafreader.offline.checkDownloadPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/** Exact user-selected origin, no credentials, cookies, downgrade or cross-origin redirects. */
class CatalogOrigin(value:String) {
    val root:URI
    val origin:String
    init {
        val u=try{URI(value.trim())}catch(_:Exception){invalid()}
        val loopback=u.host in setOf("localhost","127.0.0.1","10.0.2.2")
        if(u.host.isNullOrBlank() || u.scheme!="https" && !(loopback && u.scheme=="http") || u.rawUserInfo!=null || u.rawFragment!=null || u.port !in -1..65535 || u.port==0 || value.length>2048)invalid()
        val port=if(u.port==-1 || u.scheme=="https" && u.port==443)""else":${u.port}"
        origin="${u.scheme}://${u.host.lowercase()}$port"
        root=URI(origin+(u.rawPath.orEmpty().ifBlank{"/"})+(u.rawQuery?.let{"?$it"}?:""))
    }
    fun resolve(value:String,base:String=root.toString()):URI {
        var u=try{URI(base).resolve(value).normalize()}catch(_:Exception){invalid()}
        // Some publishers publish legacy same-host HTTP links inside an HTTPS feed.
        // Upgrade the same host only, then enforce the exact configured origin.
        if(root.scheme=="https" && u.scheme=="http" && u.host?.equals(root.host,true)==true && u.port in listOf(-1,80))u=URI("https://"+u.host+u.rawPath+(u.rawQuery?.let{"?$it"}?:""))
        val p=if(u.port==-1 || u.scheme=="https" && u.port==443)""else":${u.port}"
        if("${u.scheme}://${u.host?.lowercase()}$p"!=origin || u.rawUserInfo!=null || u.rawFragment!=null || u.path.orEmpty().split('/').any{it==".."} || u.toString().length>4096)invalid()
        return u
    }
    private fun invalid():Nothing=throw SourceProblem(FailureKind.PROTOCOL,"只支持明确选定的 HTTPS 目录及同域资源；跨域、凭据或无效地址已拒绝",diagnostic="CATALOG_ORIGIN")
}
class AnonymousHttp(private val scope:CatalogOrigin,private val totalMillis:Long=180_000):WireTransport {
    override suspend fun execute(uri:URI,method:String,headers:Map<String,String>,body:ByteArray?,maxBytes:Int):WireResponse=withContext(Dispatchers.IO) {
        require(method=="GET" && body==null && headers.keys.none{it.equals("Authorization",true)||it.equals("Cookie",true)})
        val deadline=System.nanoTime()+totalMillis*1_000_000
        fun remaining():Int {val r=(deadline-System.nanoTime())/1_000_000;if(r<=0)throw SourceProblem(FailureKind.NETWORK,"来源连接超时，请重试",diagnostic="CATALOG_TIMEOUT");return r.coerceAtMost(30000).toInt().coerceAtLeast(1)}
        var at=scope.resolve(uri.toString())
        repeat(4){n->
            checkDownloadPolicy();val c=at.toURL().openConnection()as HttpURLConnection
            try {
                c.instanceFollowRedirects=false;c.connectTimeout=minOf(15000,remaining());c.readTimeout=remaining();c.useCaches=false;c.setRequestProperty("Cookie","")
                c.setRequestProperty("User-Agent","LeafReader/1.1.6 (public reader)");headers.forEach{(k,v)->c.setRequestProperty(k,v)}
                val status=c.responseCode
                if(status in setOf(301,302,303,307,308)) {
                    if(n==3)throw SourceProblem(FailureKind.PROTOCOL,"目录重定向过多",diagnostic="CATALOG_REDIRECT")
                    at=scope.resolve(c.getHeaderField("Location")?:"",at.toString());return@repeat
                }
                if(status in setOf(429,503))throw CatalogBackoff(retryDelay(c.getHeaderField("Retry-After")))
                if(status!=200)return@withContext WireResponse(status,byteArrayOf(),c.contentType)
                val out=java.io.ByteArrayOutputStream()
                c.inputStream.use{input->val buf=ByteArray(16384);while(true){checkDownloadPolicy();c.readTimeout=remaining();val count=input.read(buf);checkDownloadPolicy();if(count<0)break;if(out.size()+count>maxBytes)throw SourceProblem(FailureKind.UNSUPPORTED,"资源超过本版大小限制",diagnostic="CATALOG_SIZE");out.write(buf,0,count)}}
                return@withContext WireResponse(200,out.toByteArray(),c.contentType)
            }finally{c.disconnect()}
        }
        error("Unreachable")
    }
    companion object {
        private fun retryDelay(value:String?):Long {
            val raw=value.orEmpty();raw.toLongOrNull()?.takeIf{it in 1..31_536_000}?.let{return it*1000}
            return runCatching{java.time.ZonedDateTime.parse(raw,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-System.currentTimeMillis()}.getOrNull()?.coerceAtLeast(2000)?:21600_000
        }
    }
}
class CatalogBackoff(val waitMillis:Long):java.io.IOException("Catalog temporarily unavailable")
