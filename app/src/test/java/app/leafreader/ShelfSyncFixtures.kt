package app.leafreader

import app.leafreader.data.*
import app.leafreader.offline.*
import org.json.JSONObject
import org.json.JSONArray
import java.net.URI

/** Self-authored service model, account IDs and responses. Never contacts a real server. */
class ShelfSyncFixtures:WireTransport {
 val content=OfflineFixtures()
 val session=SavedSession("https://shelf.fixture.invalid","synthetic-account","synthetic-device","synthetic-access","synthetic-refresh")
 val network=content.network
 data class Call(val method:String,val path:String,val query:String?,val body:JSONObject?,val account:String?)
 val calls=java.util.Collections.synchronizedList(mutableListOf<Call>())
 val members=linkedSetOf<String>("public:1")
 val revisions=mutableMapOf<String,Long>()
 var pageSize=20
 var pageOverride:((String?)->JSONObject)?=null
 var responseOverride:((Call)->WireResponse?)?=null
 var beforeResponse:(suspend (Call)->Unit)?=null
 var mutationCount=0
 var failure=0
 var timeoutAfterCommit=false
 var detailOverride:((String,JSONObject)->JSONObject)?=null
 fun client(account:String=session.accountId,origin:String=session.origin)=BookHubClient(ServiceOrigin(origin),this).apply{restore(session.copy(accountId=account,origin=origin,storageOrigin=origin))}
 fun gate()=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,this)})
 fun book(id:String="public:1",owner:String=BookHubSource.ownerId(client()))=Book(id,"自写书目 ${id.substringAfter(':')}","自写作者","","",0,0,"EPUB",owner)
 fun page(cursor:String?):JSONObject {
  val start=cursor?.substringAfter("cursor=")?.substringBefore('&')?.toIntOrNull()?:0
  val ids=members.toList();val more=start+pageSize<ids.size
  return JSONObject().put("items",JSONArray().apply{ids.drop(start).take(pageSize).forEach{put(BookHubFixtures.book(it,"自写书目 ${it.substringAfter(':')}").put("format","EPUB").put("inBookshelf",true))}})
   .put("hasMore",more).put("nextCursor",if(more)(start+pageSize).toString() else JSONObject.NULL)
 }
 override suspend fun execute(uri:URI,method:String,headers:Map<String,String>,body:ByteArray?,maxBytes:Int):WireResponse {
  check(network.online){"No HTTP permitted while offline"}
  val c=Call(method,uri.path,uri.rawQuery,body?.let{JSONObject(String(it))},headers["Authorization"]);calls+=c
  responseOverride?.invoke(c)?.let{beforeResponse?.invoke(c);return it}
  val id=uri.path.substringAfterLast('/')
  val data=when {
   uri.path.endsWith("/auth/login")->BookHubFixtures.session(c.body!!.getString("username"))
   uri.path.endsWith("/auth/refresh")->BookHubFixtures.session(session.accountId)
   uri.path.endsWith("/me/bookshelf")->pageOverride?.invoke(uri.rawQuery)?:page(uri.rawQuery)
   uri.path.endsWith("/catalog/search")->JSONObject().put("items",JSONArray().put(BookHubFixtures.book("public:2").put("format","EPUB"))).put("hasMore",false)
   uri.path.contains("/catalog/books/") || uri.path.contains("/me/private-books/")->detailOverride?.invoke(id,detail(id))?:detail(id)
   uri.path.contains("/me/bookshelf/")-> {
    check(method in listOf("PUT","DELETE"));check(c.account!=null);check(c.body!!.getString("operationId").matches(Regex("[a-f0-9]{32}")));check(c.body.getString("deviceId").isNotBlank())
    if(failure!=0)return WireResponse(failure,JSONObject().put("code",if(failure==401)"SESSION_REVOKED" else "REVISION_CONFLICT").toString().toByteArray())
    val revision=revisions[id]?:0
    if(c.body.getLong("baseRevision")!=revision)return WireResponse(409,"{\"code\":\"REVISION_CONFLICT\"}".toByteArray())
    mutationCount++;if(method=="PUT")members+=id else members-=id;revisions[id]=revision+1
    if(timeoutAfterCommit){timeoutAfterCommit=false;throw SourceProblem(FailureKind.NETWORK,"合成回包丢失")}
    JSONObject().put("state",JSONObject().put("revision",revision+1).put("deletedAt",if(method=="PUT")JSONObject.NULL else "2026-11-24T00:00:00Z"))
   }
   else->return content.execute(uri,method,headers,body,maxBytes)
  }
  beforeResponse?.invoke(c)
  return WireResponse(200,BookHubFixtures.envelope(data).toByteArray())
 }
 private fun detail(id:String)=BookHubFixtures.book(id,"自写书目 ${id.substringAfter(':')}").put("format","EPUB").put("inBookshelf",id in members)
  .put("shelfRevision",revisions[id]?:0).put("capabilities",JSONObject().put("canRead",true))
}
