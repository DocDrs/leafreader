package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
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
class OpdsSubscriptionTest {
 private val app:Application=ApplicationProvider.getApplicationContext()
 private fun feed(base:String)="""<feed xmlns="http://www.w3.org/2005/Atom"><title>自有目录</title><id>urn:test</id><link rel="search" href="$base/search.xml" type="application/opensearchdescription+xml"/><entry><id>urn:book:one</id><title>自写书一</title><author><name>自写作者</name></author><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="$base/book.epub"/></entry><entry><id>urn:sub</id><title>作者</title><link rel="subsection" type="application/atom+xml" href="$base/authors"/></entry></feed>"""
 @Test fun realLoopbackHttpFeedAcquisitionAndColdOfflineKeepNoCredentials()=runBlocking {
  val server=MockWebServer();server.start();try {
   val base=server.url("").toString().removeSuffix("/");val f=PublicSourceFixtures()
   server.enqueue(MockResponse().setHeader("Content-Type","application/atom+xml").setBody(feed(base)))
   server.enqueue(MockResponse().setHeader("Content-Type","application/epub+zip").setBody(Buffer().write(f.epub)))
   val descriptor=OpdsSubscription("自有目录","$base/opds",true);val source=OpdsSource(app,descriptor,AlwaysOnline)
   assertEquals("自有目录",source.verify());assertTrue(OpdsSubscriptions(app).save(descriptor))
   val page=source.browse();assertEquals(1,page.items.size);assertEquals("作者",page.sections.single().title)
   val book=page.items.single();val chapters=source.chapters(book.id);assertTrue(source.chapter(book.id,chapters[1].id).text.contains("自写第一章"));assertTrue(source.status(book.id).complete)
   repeat(2){val r=server.takeRequest();assertNull(r.getHeader("Authorization"));assertTrue(r.getHeader("Cookie").isNullOrEmpty())}
   val cold=OpdsSource(app,OpdsSubscriptions(app).all().single(),object:ConnectionState{override val online=false;override val wifi=false},WireTransport{_,_,_,_,_->error("No offline HTTP")})
   assertEquals(chapters,cold.chapters(book.id));assertNotNull(cold.thumbnail(book.id));assertTrue(cold.status(book.id).complete)
   cold.clear(book.id);assertFalse(cold.status(book.id).complete)
  }finally{server.shutdown()}
 }
 @Test fun navigationSearchAndNextFollowOnlyAdvertisedSameOriginUris()=runBlocking {
  val scope=CatalogOrigin("https://catalog.invalid/opds/")
  val p=OpdsFeed.parse(feed("https://catalog.invalid"),scope.root.toString(),scope)
  assertEquals("作者",p.sections.single().title)
  val search=OpdsFeed.searchUrl("""<OpenSearchDescription><Url type="application/atom+xml" template="http://catalog.invalid/find?q={searchTerms}&amp;author={atom:author}"/></OpenSearchDescription>""",scope.root.toString(),"中文",scope)
  assertTrue(search.startsWith("https://catalog.invalid/find?"));assertTrue(search.contains(encoded("中文")))
  assertTrue(runCatching{scope.resolve("https://outside.invalid/book.epub")}.isFailure)
  assertTrue(runCatching{scope.resolve("http://outside.invalid/book.epub")}.isFailure)
  assertTrue(runCatching{CatalogOrigin("https://user:password@catalog.invalid/opds")}.isFailure)
 }
 @Test fun authAndBackoffNeverTurnIntoAnEmptyCatalogOrAStoredSubscription()=runBlocking {
  val d=OpdsSubscription("授权目录","https://catalog.invalid/opds",true)
  val denied=OpdsSource(app,d,AlwaysOnline,WireTransport{_,_,_,_,_->WireResponse(401,byteArrayOf())})
  assertEquals("OPDS_AUTH",(runCatching{denied.verify()}.exceptionOrNull()as SourceProblem).diagnostic);assertTrue(OpdsSubscriptions(app).all().isEmpty())
  var calls=0;val maintenance=OpdsSource(app,d,AlwaysOnline,WireTransport{_,_,_,_,_->calls++;throw CatalogBackoff(3600_000)})
  runCatching{maintenance.verify()};runCatching{maintenance.verify()};assertEquals(1,calls)
 }
 @Test fun twoSubscriptionsKeepSameEntryIdInDifferentNamespaces()=runBlocking {
  val a=OpdsSubscription("甲","https://a.invalid/opds",true);val b=OpdsSubscription("乙","https://b.invalid/opds",true)
  assertNotEquals(a.id,b.id);val book="opds:"+digest("urn:same")
  ReadingStore(app,a.id).save(book,"chapter",37);assertNull(ReadingStore(app,b.id).position(book))
  val http=AnonymousHttp(CatalogOrigin(a.url))
  assertTrue(runCatching{http.execute(URI(a.url),"GET",mapOf("Authorization" to "synthetic"),null,100)}.isFailure)
 }
 @Test fun clearInvalidatesLateArchiveAndExplicitRetryRemainsPossible()=runBlocking {
  val f=PublicSourceFixtures();val d=OpdsSubscription("自写","https://catalog.invalid/opds",true)
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var held=false
  val source=OpdsSource(app,d,AlwaysOnline,WireTransport{u,_,_,_,_->
   if(u.path.endsWith(".epub")){if(!held){held=true;entered.complete(Unit);release.await()};WireResponse(200,f.epub)}else WireResponse(200,feed("https://catalog.invalid").toByteArray())
  })
  val book=source.browse().items.single();val downloading=async{runCatching{source.download(book.id)}}
  entered.await();val clearing=async{source.clear(book.id)}
  withTimeout(5000){while(app.getSharedPreferences("opds-cache-${d.id}",0).getLong("epoch:${book.id}",0)==0L)yield()}
  release.complete(Unit);assertTrue(downloading.await().isFailure);clearing.await();assertFalse(source.status(book.id).complete)
  source.browse();source.download(book.id);assertTrue(source.status(book.id).complete)
 }
 @Test fun serverPagingFailureIsAnErrorAndBorrowLinksAreNotAcquisitions()=runBlocking {
  val d=OpdsSubscription("自写","https://catalog.invalid/opds",true);var pages=0
  val source=OpdsSource(app,d,AlwaysOnline,WireTransport{u,_,_,_,_->
   if(u.path=="/next"){pages++;WireResponse(502,byteArrayOf())}else WireResponse(200,feed("https://catalog.invalid").replace("<id>urn:test</id>","<id>urn:test</id><link rel='next' href='/next'/>").toByteArray())
  })
  val first=source.browse();assertTrue(first.hasMore);assertEquals(1,first.items.size)
  assertTrue(runCatching{source.browse("",first.nextCursor)}.exceptionOrNull()is SourceProblem);assertEquals(1,pages)
  val scope=CatalogOrigin(d.url)
  val borrow=OpdsFeed.parse(feed("https://catalog.invalid").replace("http://opds-spec.org/acquisition\"","http://opds-spec.org/acquisition/borrow\""),d.url,scope)
  assertTrue(borrow.records.isEmpty())
 }
}
