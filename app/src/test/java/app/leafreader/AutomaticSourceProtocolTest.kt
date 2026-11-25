package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutomaticSourceProtocolTest {
 private val app=ApplicationProvider.getApplicationContext<Application>()
 @Test fun actualGutenbergOpdsAndBasicLegadoAdaptersCompleteFromControlledInterfacesAndReadOffline()=runBlocking {
  GlobalDownloadPreferences(app).set(true)
  val fixture=PublicSourceFixtures();val network=fixture.network
  val pg=fixture.source(app);var opdsCalls=0
  val subscription=OpdsSubscription("自写 OPDS","https://opds.fixture.invalid/catalog",true)
  val opds=OpdsSource(app,subscription,network,WireTransport{u,m,h,b,cap->
   opdsCalls++;assertEquals("GET",m);assertNull(b);assertFalse(h.keys.any{it.equals("Authorization",true)||it.equals("Cookie",true)})
   val bytes=if(u.path=="/book.epub")fixture.epub else """<feed xmlns="http://www.w3.org/2005/Atom"><id>urn:fixture</id><title>自写目录</title><entry><id>urn:self:1</id><title>自寫測試書</title><author><name>自寫作者</name></author><link rel="http://opds-spec.org/acquisition/open-access" type="application/epub+zip" href="/book.epub"/></entry></feed>""".toByteArray()
   assertTrue(bytes.size<=cap);WireResponse(200,bytes)
  })
  val rules=LegadoFixtures();rules.traditional=true
  val definition=LegadoRules.parse(rules.rules().toString(),true).single();val legado=LegadoSource(app,definition,network)
  try {
   val pgBook=pg.browse("自写").items.first();val opdsBook=opds.browse("").items.single();val legadoBook=legado.browse("中文 + 自写").items.single()
   val pairs=listOf(pg to pgBook,opds to opdsBook,legado to legadoBook)
   val queue=AutomaticSourceDownloads(app,network,false){id->pairs.firstOrNull{it.first.id==id}?.first}
   for((source,book)in pairs){assertTrue(source.supportsAutomaticDownload);ReadingStore(app,source.id).addToShelf(book)
    assertTrue(queue.enqueue(source,book));assertFalse(queue.execute(queue.request(source.id,book.id)!!));assertTrue(source.status(book.id).complete);assertEquals("COMPLETE",queue.request(source.id,book.id)!!.state)}
   val before=Triple(fixture.calls.size,opdsCalls,rules.calls.size);network.up=false;rules.server.shutdown()
   var forbiddenHttp=0
   val forbidden=WireTransport{_,_,_,_,_->forbiddenHttp++;error("Cold offline HTTP forbidden")}
   val coldPg=fixture.source(app)
   val coldPairs=listOf(coldPg to pgBook,OpdsSource(app,subscription,network,forbidden) to opdsBook,LegadoSource(app,definition,network,forbidden) to legadoBook)
   for((source,book)in coldPairs){val refs=source.chapters(book.id);assertTrue(refs.isNotEmpty());assertTrue(source.status(book.id).complete)
    val chapter=source.chapter(book.id,refs.last().id);assertTrue(chapter.text.isNotEmpty())}
   coldPg.close();assertEquals(0,forbiddenHttp)
   assertEquals(before,Triple(fixture.calls.size,opdsCalls,rules.calls.size))
  } finally {pg.close();rules.server.shutdown()}
 }
 @Test fun globalOffDoesNotDisableExplicitMeteredWholeBookDownload()=runBlocking {
  GlobalDownloadPreferences(app).set(false);val fixture=PublicSourceFixtures();fixture.network.onWifi=false;val source=fixture.source(app)
  try{val book=source.browse("自写").items.first();val before=fixture.calls.size
   try{source.download(book.id,false);fail("Metered must require explicit choice")}catch(e:SourceProblem){assertEquals("PG_WIFI",e.diagnostic)}
   assertEquals(before,fixture.calls.size);source.download(book.id,true);assertTrue(source.status(book.id).complete);assertFalse(GlobalDownloadPreferences(app).enabled())
  }finally{source.close()}
 }
 @Test fun relaxedAutomaticPolicyDoesNotOverrideManualSingleDownloadChoice()=runBlocking {
  GlobalDownloadPreferences(app).apply{set(true);setWifiOnly(false)}
  val fixture=PublicSourceFixtures();fixture.network.onWifi=false;val source=fixture.source(app)
  try{val book=source.browse("自写").items.first();val before=fixture.calls.size
   try{source.download(book.id,false);fail("Manual restriction remains independent")}catch(e:SourceProblem){assertEquals("PG_WIFI",e.diagnostic)}
   assertEquals(before,fixture.calls.size);source.download(book.id,true);assertTrue(source.status(book.id).complete)
  }finally{source.close()}
 }

}
