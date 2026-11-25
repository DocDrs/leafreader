package app.leafreader
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
class LegadoSourceTest {
 private val app:Application=ApplicationProvider.getApplicationContext()
 private fun definition(f:LegadoFixtures,json:Boolean=false)=LegadoRules.parse(f.rules(json).toString(),true).single()
 @Test fun htmlRulesRealHttpDetailsTocContentPartialFullAndColdOffline()=runBlocking {
  val f=LegadoFixtures();try{
   val d=definition(f);val source=LegadoSource(app,d,AlwaysOnline);assertTrue(source.browse().items.isEmpty());assertTrue(f.calls.isEmpty())
   val first=source.browse("自写");val book=first.items.single();assertTrue(first.hasMore);assertTrue(source.browse("自写",first.nextCursor).items.isEmpty())
   assertEquals("自写详情书",source.details(book).title);assertNotNull(source.thumbnail(book.id));val refs=source.chapters(book.id);assertEquals(2,refs.size)
   val chapter=source.chapter(book.id,refs[0].id);assertTrue(chapter.text.contains("段落保持独立"));assertFalse(chapter.text.contains("neverExecute"))
   assertEquals("可离线读",source.chapterStatus(book.id,refs[0].id));assertEquals("未缓存",source.chapterStatus(book.id,refs[1].id));assertFalse(source.status(book.id).complete);assertEquals(1,source.status(book.id).saved)
   val firstReads=f.calls.count{it.substringBefore('?')=="/chapter/1"};source.download(book.id);assertTrue(source.status(book.id).complete);assertEquals(firstReads,f.calls.count{it.substringBefore('?')=="/chapter/1"})
   assertTrue(LegadoSources(app).save(listOf(d)));val count=f.calls.size
   val cold=LegadoSource(app,LegadoSources(app).all().single(),object:ConnectionState{override val online=false;override val wifi=false},WireTransport{_,_,_,_,_->error("Offline HTTP forbidden")})
   assertEquals(refs,cold.chapters(book.id));assertEquals(chapter,cold.chapter(book.id,refs[0].id));assertNotNull(cold.thumbnail(book.id));assertTrue(cold.status(book.id).complete);assertEquals(count,f.calls.size)
   cold.clear(book.id);assertFalse(cold.status(book.id).complete)
  }finally{f.server.shutdown()}
 }
 @Test fun jsonPathsFetchAllFourStagesAndKeepSourceNamespacesDistinct()=runBlocking {
  val f=LegadoFixtures();try{val d=definition(f,true);val source=LegadoSource(app,d,AlwaysOnline);val b=source.browse("JSON").items.single();assertEquals("JSON 详情书",source.details(b).title);val refs=source.chapters(b.id);assertEquals(2,refs.size);assertTrue(source.chapter(b.id,refs[0].id).text.contains("第二段自写正文"))
   val other=LegadoRules.parse(f.rules(true).put("bookSourceName","另一个自写来源").toString(),true).single();assertNotEquals(d.id,other.id);ReadingStore(app,d.id).save(b.id,refs[0].id,73);assertNull(ReadingStore(app,other.id).position(b.id))
  }finally{f.server.shutdown()}
 }
 @Test fun scriptsCredentialsCustomRequestsAndUnsupportedRulesFailBeforePersistenceOrHttp() {
  val f=LegadoFixtures();try{
   val changes=listOf<(JSONObject)->Unit>(
    {it.put("loginUrl","https://account.invalid/login")},{it.put("header","{Cookie: secret}")},{it.put("enabledCookieJar",true)},
    {it.put("searchUrl","/find?q={{java.encodeURI(key)}}")},{it.getJSONObject("ruleContent").put("content","@js:java.ajax(baseUrl)")},
    {it.getJSONObject("ruleSearch").put("bookList","//div")},{it.getJSONObject("ruleContent").put("replaceRegex","##unsafe")},
    {it.getJSONObject("ruleSearch").put("name",".name:matches((a+)+)@text")},{it.put("searchUrl","/search,{\"method\":\"POST\"}")},
    {it.put("searchUrl","/search?token=secret&q={{key}}")},{it.put("bookSourceUrl","https://user:password@catalog.invalid")},{it.put("searchUrl","https://outside.invalid/find?q={{key}}")})
   for(change in changes){val j=f.rules();change(j);assertTrue(runCatching{LegadoRules.parse(j.toString(),true)}.exceptionOrNull()is SourceProblem)}
   assertTrue(LegadoSources(app).all().isEmpty());assertTrue(f.calls.isEmpty());assertTrue(runCatching{LegadoRules.parse(f.rules().toString(),false)}.isFailure)
   val huge=" ".repeat(LegadoRules.MAX_IMPORT+1);assertTrue(runCatching{LegadoRules.parse(huge,true)}.isFailure)
   assertTrue(runCatching{LegadoRules.parse("[".repeat(50)+"]".repeat(50),true)}.isFailure)
  }finally{f.server.shutdown()}
 }
 @Test fun authFailureIsExplicitAndDoesNotFabricateOrSaveAChapter()=runBlocking {
  val f=LegadoFixtures();try{val source=LegadoSource(app,definition(f),AlwaysOnline);val b=source.browse("自写").items.single();val refs=source.chapters(b.id);source.chapter(b.id,refs[0].id);f.failChapter=401
   val failure=runCatching{source.chapter(b.id,refs[1].id)}.exceptionOrNull()as SourceProblem;assertEquals("LG_AUTH",failure.diagnostic);assertEquals(1,source.status(b.id).saved);assertEquals("未缓存",source.chapterStatus(b.id,refs[1].id));assertTrue(source.chapter(b.id,refs[0].id).text.isNotEmpty())
  }finally{f.server.shutdown()}
 }
 @Test fun delayedChapterCannotRestoreClearedFilesAndNewDownloadWorks()=runBlocking {
  val f=LegadoFixtures();try{val d=definition(f);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var held=false;val http=AnonymousHttp(CatalogOrigin(d.origin))
   val source=LegadoSource(app,d,AlwaysOnline,WireTransport{u,m,h,b,c->if(u.path=="/chapter/1"&&!held){held=true;entered.complete(Unit);release.await()};http.execute(u,m,h,b,c)})
   val book=source.browse("自写").items.single();val refs=source.chapters(book.id);val downloading=async{runCatching{source.chapter(book.id,refs[0].id)}};entered.await();val clearing=async{source.clear(book.id)}
   withTimeout(5000){while(app.getSharedPreferences("legado-cache-${d.id}",0).getLong("epoch:${book.id}",0)==0L)yield()};release.complete(Unit);assertTrue(downloading.await().isFailure);clearing.await();assertFalse(source.status(book.id).complete)
   source.download(book.id);assertTrue(source.status(book.id).complete)
  }finally{f.server.shutdown()}
 }
 @Test fun corruptionAndWifiNeverPretendCompleteOrRedownloadSilently()=runBlocking {
  val f=LegadoFixtures();try{val d=definition(f);val source=LegadoSource(app,d,AlwaysOnline);val book=source.browse("自写").items.single();source.download(book.id);val refs=source.chapters(book.id)
   val file=File(app.noBackupFilesDir,"legado-text-v1/${d.id}/${book.id.substringAfter(':')}/chapter-${digest(refs[0].id)}.json");file.appendText("damaged");val calls=f.calls.size
   assertFalse(source.status(book.id).complete);assertTrue(runCatching{source.chapter(book.id,refs[0].id)}.isFailure);assertEquals(calls,f.calls.size)
   val cellular=LegadoSource(app,d,object:ConnectionState{override val online=true;override val wifi=false});assertEquals("LG_WIFI",(runCatching{cellular.download(book.id)}.exceptionOrNull()as SourceProblem).diagnostic);assertEquals(calls,f.calls.size)
  }finally{f.server.shutdown()}
 }
 @Test fun malformedJsonMissingListAndUnsupportedPaginationNeverMasqueradeAsNoMatch()=runBlocking {
  val f=LegadoFixtures();try{val d=definition(f,true)
   for(body in listOf("{\"books\":[]} trailing","{\"error\":\"synthetic failure\"}")) {
    val source=LegadoSource(app,d,AlwaysOnline,WireTransport{_,_,_,_,_->WireResponse(200,body.toByteArray())})
    assertTrue(runCatching{source.browse("self")}.exceptionOrNull()is SourceProblem)
   }
   assertTrue(LegadoSource(app,d,AlwaysOnline,WireTransport{_,_,_,_,_->WireResponse(200,"{\"books\":[]}".toByteArray())}).browse("self").items.isEmpty())
   for(group in listOf("ruleToc","ruleContent")){val j=f.rules();j.getJSONObject(group).put(if(group=="ruleToc")"nextTocUrl"else"nextContentUrl","a.next@href");assertTrue(runCatching{LegadoRules.parse(j.toString(),true)}.isFailure)}
  }finally{f.server.shutdown()}
 }
 @Test fun storedDefinitionValidationIsStrictAndIgnoresInertMetadata() {
  val f=LegadoFixtures();try{val first=definition(f);val changed=LegadoRules.parse(f.rules().put("lastUpdateTime",123).put("bookSourceComment","ignored metadata").toString(),true).single();assertEquals(first.id,changed.id);assertFalse(changed.json.contains("ignored metadata"))
   val invalid=f.rules().put("loginUrl","https://account.invalid");val array=JSONArray().put(f.rules()).put(invalid)
   assertTrue(runCatching{LegadoRules.parse(array.toString(),true)}.isFailure);assertTrue(LegadoSources(app).all().isEmpty());assertTrue(f.calls.isEmpty())
   val file=File(app.cacheDir,"too-large.json").apply{writeText("x".repeat(LegadoRules.MAX_IMPORT+1))};assertTrue(runCatching{readLegadoDocument(app.contentResolver,android.net.Uri.fromFile(file))}.isFailure)
   file.writeBytes(byteArrayOf(0xc3.toByte(),0x28));assertTrue(runCatching{readLegadoDocument(app.contentResolver,android.net.Uri.fromFile(file))}.isFailure)
  }finally{f.server.shutdown()}
 }

 @Test fun optionalForeignCoverFallsBackAndOversizedResourcesRemainErrors()=runBlocking {
  val f=LegadoFixtures();try{val d=definition(f);var calls=0
   val source=LegadoSource(app,d,AlwaysOnline,WireTransport{_,_,_,_,_->calls++;WireResponse(200,"<div class='book'><a href='/own'><b class='name'>自写书</b></a><img src='https://outside.invalid/cover.png'/></div>".toByteArray())})
   val book=source.browse("self").items.single();assertNull(source.thumbnail(book.id));assertEquals(1,calls)
   val huge=LegadoSource(app,d,AlwaysOnline,WireTransport{_,_,_,_,_->WireResponse(200,ByteArray(512*1024+1))})
   assertEquals("LG_SIZE",(runCatching{huge.browse("self")}.exceptionOrNull()as SourceProblem).diagnostic)
  }finally{f.server.shutdown()}
 }

 @Test fun mixedAndroidRulesReportNamesAndFieldsWithoutRejectingValidSources() {
  val f=LegadoFixtures();try{val good=f.rules().put("bookSourceName","有效源");val js=f.rules().put("bookSourceName","脚本源");js.getJSONObject("ruleContent").put("content","@js:java.ajax(baseUrl)")
   val old=f.rules().put("bookSourceName","旧式源");old.getJSONObject("ruleSearch").put("bookList","div.book@a@href")
   val result=LegadoRules.review(JSONArray().put(good).put(js).put(old).toString(),true);assertEquals(listOf("有效源"),result.accepted.map{it.name});assertEquals(listOf("脚本源","旧式源"),result.rejected.map{it.name});assertTrue(result.rejected[0].reason.contains("ruleContent.content")&&result.rejected[0].reason.contains("JS"));assertTrue(result.rejected[1].reason.contains("ruleSearch.bookList")&&result.rejected[1].reason.contains("多级 @"));assertTrue(f.calls.isEmpty())
   assertTrue(LegadoSources(app).save(result.accepted));assertEquals(1,LegadoSources(app).all().size)
  }finally{f.server.shutdown()}
 }
 @Test fun actualSocketTimeoutIsDifferentFromUnsupportedRulesAndARealEmptyResult()=runBlocking {
  val f=LegadoFixtures();try{val d=definition(f)
   f.server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(r:okhttp3.mockwebserver.RecordedRequest)=okhttp3.mockwebserver.MockResponse().setHeadersDelay(700,java.util.concurrent.TimeUnit.MILLISECONDS).setBody("<div></div>")}
   val source=LegadoSource(app,d,AlwaysOnline,AnonymousHttp(CatalogOrigin(d.origin),200));val error=runCatching{source.browse("中文+测试")}.exceptionOrNull()as SourceProblem;assertEquals(FailureKind.NETWORK,error.kind);assertTrue(error.diagnostic in listOf("LG_TIMEOUT","CATALOG_TIMEOUT"));assertTrue(error.message!!.contains("超时"));assertTrue(f.server.requestCount<=1)
   val empty=LegadoSource(app,d,AlwaysOnline,WireTransport{_,_,_,_,_->WireResponse(200,"<html><body></body></html>".toByteArray())});assertTrue(empty.browse("没有这本自写书").items.isEmpty())
   val invalid=f.rules();invalid.getJSONObject("ruleContent").put("content","@js:unsupported()");val rejected=LegadoRules.review(invalid.toString(),true);assertTrue(rejected.accepted.isEmpty());assertTrue(rejected.rejected.single().reason.contains("JS"))
  }finally{f.server.shutdown()}
 }

}
