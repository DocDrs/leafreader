package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.AlwaysOnline
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PublicSourceTest {
 private val app:Application=ApplicationProvider.getApplicationContext()
 @Test fun officialMaintenanceFallsBackToMachineCatalogWithSearchPagingAndNoCredential()=runBlocking {
  val w=PublicSourceFixtures();val s=w.source(app)
  val first=s.browse();assertEquals(20,first.items.size);assertTrue(first.hasMore)
  assertTrue(first.items.all{it.sourceId==GutenbergSource.SOURCE});assertEquals(7,s.browse("",first.nextCursor).items.size)
  assertEquals(1,s.browse("书 27").items.size);assertTrue(s.books().isEmpty())
  assertEquals(1,w.calls.count{it.contains("search.opds")});assertEquals(1,w.calls.count{it.endsWith(".csv.gz")})
  w.network.up=false;assertEquals(20,w.source(app).browse().items.size)
 }
 @Test fun coverAndAllSpineContentSurviveColdOfflineWithoutAnyHttp()=runBlocking {
  val w=PublicSourceFixtures();val s=w.source(app);val refs=s.chapters("pg:1");assertEquals(3,refs.size);assertEquals("封面",refs.first().title)
  assertTrue(s.chapter("pg:1",refs.first().id).graphics.isNotEmpty());assertTrue(s.chapter("pg:1",refs[1].id).text.contains("自写第一章"));assertTrue(s.status("pg:1").complete)
  val count=w.calls.size;w.network.up=false;val cold=w.source(app)
  assertEquals(refs,cold.chapters("pg:1"));assertTrue(cold.chapter("pg:1",refs[2].id).text.contains("自写第二章"));assertNotNull(cold.thumbnail("pg:1"));assertEquals(count,w.calls.size)
  assertEquals(3,cold.chapterLengths("pg:1")!!.size)
 }
 @Test fun clearInvalidatesLateDownloadAndNewExplicitDownloadStillWorks()=runBlocking {
  val w=PublicSourceFixtures();val s=w.source(app);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  w.before={path->if(path.endsWith(".epub")){entered.complete(Unit);release.await()}}
  val job=async{runCatching{s.download("pg:1")}}
  entered.await();val clearing=async{s.clear("pg:1")};withTimeout(5000){while(app.getSharedPreferences("gutenberg-offline-v1",0).getLong("epoch:pg:1",0)==0L)yield()};release.complete(Unit)
  assertTrue(job.await().isFailure);clearing.await();assertFalse(w.source(app).status("pg:1").complete)
  w.before=null;s.download("pg:1");assertTrue(s.status("pg:1").complete)
 }
 @Test fun wifiAndCorruptionRemainExplicitAndNeverFabricateComplete()=runBlocking {
  val w=PublicSourceFixtures();val s=w.source(app);w.network.onWifi=false
  val e=runCatching{s.download("pg:1")}.exceptionOrNull()as SourceProblem;assertEquals("PG_WIFI",e.diagnostic);assertTrue(w.calls.isEmpty())
  s.download("pg:1",true);val file=java.io.File(app.noBackupFilesDir,"gutenberg-public-v1/1/book.epub");file.appendText("damage")
  val cold=w.source(app);val bad=cold.status("pg:1");assertFalse(bad.complete);assertEquals(0,bad.saved)
  val requests=w.calls.count{it.endsWith(".epub")};val damaged=file.readBytes();assertNotNull(cold.thumbnail("pg:1"));assertEquals(requests,w.calls.count{it.endsWith(".epub")});assertArrayEquals(damaged,file.readBytes());w.network.up=false
  assertTrue(runCatching{w.source(app).chapters("pg:1")}.exceptionOrNull()is SourceProblem)
 }
 @Test fun archiveRejectsExternalResourcesAndTraversalWithoutFetchingThem() {
  val w=PublicSourceFixtures();assertTrue(runCatching{PublicEpubArchive(w.getEpub(unsafe=true))}.isFailure)
  assertTrue(runCatching{PublicEpubArchive(w.getEpub(external=true))}.isFailure);assertTrue(runCatching{PublicEpubArchive(w.epub,"2")}.isFailure);assertTrue(w.calls.isEmpty())
 }
 @Test fun opdsAcquisitionSearchAndPagingAreBoundToOfficialOrigin() {
  val feed="""<feed xmlns="http://www.w3.org/2005/Atom"><link rel="next" href="?page=2"/><link rel="search" type="application/opensearchdescription+xml" href="/ebooks/search.opensearch"/><entry><title>自写中文目录</title><author><name>自写作者</name></author><language>zh</language><link rel="http://opds-spec.org/acquisition/open-access" type="application/epub+zip" href="/ebooks/123.epub3.images"/></entry></feed>"""
  val parsed=GutenbergOpds.parse(feed);assertEquals("pg:123",parsed.books.single().id);assertTrue(parsed.next!!.endsWith("?page=2"))
  val search=GutenbergOpds.searchTemplate("""<OpenSearchDescription><Url type="application/atom+xml" template="https://www.gutenberg.org/ebooks/search.opds/?query={searchTerms}&amp;start={startIndex?}"/></OpenSearchDescription>""",GutenbergOpds.ROOT,"自写")
  assertTrue(search.contains(encoded("自写")));assertFalse(search.contains('{'))
  for(url in listOf("http://www.gutenberg.org/ebooks/","https://other.invalid/ebooks/","https://www.gutenberg.org@other.invalid/ebooks/"))assertTrue(runCatching{GutenbergOpds.official(url)}.isFailure)
 }
 @Test fun opdsSourceUsesDiscoveredSearchAndNextWithoutCatalogPolling()=runBlocking {
  val requests=mutableListOf<URI>()
  fun feed(number:Int,next:Boolean=false)="""<feed xmlns="http://www.w3.org/2005/Atom"><link rel="search" type="application/opensearchdescription+xml" href="/ebooks/search.opensearch"/>${if(next)"<link rel='next' href='?page=2'/>"else""}<entry><title>自写目录 $number</title><language>zh</language><link rel="http://opds-spec.org/acquisition/open-access" type="application/epub+zip" href="/ebooks/$number.epub3.images"/></entry></feed>"""
  val wire=WireTransport{u,m,h,b,c->requests+=u;check(h.keys.none{it.equals("Authorization",true)});WireResponse(200,(when {
   u.path.endsWith(".opensearch")->"""<OpenSearchDescription><Url type="application/atom+xml" template="https://www.gutenberg.org/ebooks/search.opds/?query={searchTerms}"/></OpenSearchDescription>"""
   u.query=="page=2"->feed(2)
   u.query!=null->feed(1,true)
   else->feed(1)
  }).toByteArray())}
  var tick=0L;val s=GutenbergSource(app,wire,AlwaysOnline,bootstrap=false){tick+=10000;tick}
  val first=s.browse("自写");assertTrue(first.hasMore);assertEquals("pg:2",s.browse("自写",first.nextCursor).items.single().id)
  s.browse("第二次");assertEquals(1,requests.count{it.path.endsWith(".opensearch")});assertTrue(requests.all{it.host=="www.gutenberg.org"});assertFalse(requests.any{it.path.endsWith(".csv.gz")})
 }
 @Test fun maintenanceBackoffStopsRepeatedNetworkAttempts()=runBlocking {
  val w=PublicSourceFixtures();var mirrorCalls=0
  val wire=WireTransport{u,m,h,b,c->if(u.host=="gutenberg.pglaf.org"){mirrorCalls++;throw GutenbergMaintenance(3600_000)}else w.execute(u,m,h,b,c)}
  val s=GutenbergSource(app,wire,w.network,bootstrap=false){w.clock+=10000;w.clock}
  assertEquals("PG_MAINTENANCE",(runCatching{s.browse()}.exceptionOrNull()as SourceProblem).diagnostic)
  val calls=w.calls.size;runCatching{s.browse()};assertEquals(1,mirrorCalls);assertEquals(calls,w.calls.size)
 }
 @Test fun publicHttpRejectsCredentialsAndOtherHostsBeforeConnecting()=runBlocking {
  val http=GutenbergHttp()
  assertTrue(runCatching{http.execute(URI("https://gutenberg.pglaf.org/cache/epub/1/"),"GET",mapOf("Authorization" to "synthetic"),null,100)}.isFailure)
  assertTrue(runCatching{http.execute(URI("https://other.invalid/"),"GET",emptyMap(),null,100)}.isFailure)
 }
 @Test fun csvPreservesQuotedMultilineAndRejectsTruncation() {
  assertEquals(listOf(listOf("a","b"),listOf("x,y","line1\nline2 \"quoted\"")),CsvRows.parse("a,b\r\n\"x,y\",\"line1\nline2 \"\"quoted\"\"\"\r\n"))
  assertTrue(runCatching{CsvRows.parse("a,\"unfinished")}.isFailure)
 }
}
