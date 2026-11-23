package app.leafreader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.IOException
import javax.net.ssl.HttpsURLConnection

/** Service targets come only from explicit local configuration or an existing saved session. */
fun interface LoginOriginVerifier { suspend fun verify(origin: ServiceOrigin) }

/** No credentials, cookies or redirect following; standard certificate-chain and hostname validation. */
class SystemHttpsOriginVerifier(
    private val openConnection: (ServiceOrigin) -> HttpsURLConnection = { it.uri.resolve("/").toURL().openConnection() as HttpsURLConnection }
) : LoginOriginVerifier {
    override suspend fun verify(origin: ServiceOrigin): Unit = withContext(Dispatchers.IO) {
        if (origin.uri.scheme != "https") throw SourceProblem(FailureKind.PROTOCOL, "登录必须使用 HTTPS；尚未发送账号密码")
        val connection = try { openConnection(origin) }
        catch (_: IOException) { throw SourceProblem(FailureKind.NETWORK, "无法验证当前服务器的 HTTPS 连接；尚未发送账号密码") }
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.useCaches = false
            connection.setRequestProperty("Cookie", "")
            val status = connection.responseCode
            if (status in 300..399) throw SourceProblem(FailureKind.PROTOCOL, "服务器入口发生跳转，请确认最终地址；尚未发送账号密码")
            if (status !in 200..299 && status !in listOf(401, 403, 404, 405))
                throw SourceProblem(FailureKind.NETWORK, "当前服务器暂不可用；尚未发送账号密码")
        } catch (e: SourceProblem) { throw e }
        catch (_: IOException) { throw SourceProblem(FailureKind.NETWORK, "无法验证当前服务器的 HTTPS 连接；尚未发送账号密码") }
        finally { connection.disconnect() }
    }
}

/** A fresh client is created only after the exact selected origin passes verification. */
class BookHubConnectionGate(
    private val verifier: LoginOriginVerifier = SystemHttpsOriginVerifier(),
    private val clientFactory: (ServiceOrigin) -> BookHubClient = { BookHubClient(it) }
) {
    /** No password, no login request. Subsequent exact-origin HTTPS requests validate TLS normally. */
    fun restore(session: SavedSession): BookHubClient {
        session.validate()
        val target = ServiceOrigin(session.origin)
        val client = clientFactory(target)
        if (client.origin.value != target.value) protocol("保存的会话目标发生变化")
        return client.also { it.restore(session) }
    }
    suspend fun login(origin: String, username: String, password: String): BookHubClient {
        val target = ServiceOrigin(origin)
        if (target.uri.scheme != "https") throw SourceProblem(FailureKind.PROTOCOL, "登录必须使用 HTTPS；尚未发送账号密码")
        verifier.verify(target)
        val client = clientFactory(target)
        if (client.origin.value != target.value) protocol("登录目标发生变化；尚未发送账号密码")
        try { client.login(username, password); coroutineContext.ensureActive(); return client }
        catch (e: Exception) { client.clearSession(); throw e }
    }
}
