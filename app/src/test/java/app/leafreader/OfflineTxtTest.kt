package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class OfflineTxtTest {
    @Test fun fullPackageTxtKeepsLiteralAttributesEmojiAndExactCanonicalTextOffline()=runBlocking {
        val app:Application=ApplicationProvider.getApplicationContext();val network=OfflineFixtures.Network()
        val a="这是纯文本，src='a' href=\"b\"。🌙\n第一段。";val b="第二段，继续。"
        val count=a.codePointCount(0,a.length);val end=count+b.codePointCount(0,b.length)
        val items=mapOf("a" to a.toByteArray(),"b" to b.toByteArray())
        val access=BookHubFixtures.txtManifest().put("contentVersion","txt-attribute-fixture")
            .put("metadata",JSONObject().put("codePointLength",end).put("segments",JSONArray()
                .put(JSONObject().put("resourceId","a").put("startCodePoint",0).put("endCodePoint",count))
                .put(JSONObject().put("resourceId","b").put("startCodePoint",count).put("endCodePoint",end))))
            .put("toc",JSONArray().put(JSONObject().put("label","甲").put("codePointOffset",0)).put(JSONObject().put("label","乙").put("codePointOffset",count)))
        val resources=JSONArray().apply{items.forEach{(id,bytes)->put(JSONObject().put("resourceId",id).put("path","resources/$id").put("mediaType","text/plain").put("sizeBytes",bytes.size).put("sha256",sha(bytes)))}}
        val manifest=JSONObject().put("schemaVersion",1).put("bookId","public:1").put("contentVersion",access.getString("contentVersion")).put("format","TXT").put("spine",access.getJSONArray("spine")).put("resources",resources)
        val output=ByteArrayOutputStream();ZipOutputStream(output).use{z->
            fun add(name:String,bytes:ByteArray){z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()}
            add("manifest.json",manifest.toString().toByteArray());items.forEach{(id,bytes)->add("resources/$id",bytes)}
        };val zip=output.toByteArray();var calls=0
        val desc=JSONObject().put("kind","READING_ZIP").put("schemaVersion",1).put("contentVersion",access.getString("contentVersion")).put("sizeBytes",zip.size).put("sha256",sha(zip)).put("downloadUrl","/api/v1/books/public%3A1/reading-package/content?ticket=fixture")
        val wire=WireTransport{uri,_,_,_,_->check(network.online);calls++
            when{uri.path.endsWith("/access")->WireResponse(200,BookHubFixtures.envelope(access).toByteArray());uri.path.endsWith("/reading-package")->WireResponse(200,BookHubFixtures.envelope(desc).toByteArray());uri.path.endsWith("/content")->WireResponse(200,zip,"application/zip");else->WireResponse(200,items.getValue(uri.path.substringAfterLast('/')),"text/plain")}
        }
        fun client()=BookHubClient(ServiceOrigin("https://offline-txt.fixture.invalid"),wire).also{it.restore(SavedSession(it.origin.value,"fixture-account","fixture-device","fixture-access","fixture-refresh"))}
        val c=client();val online=BookHubSource(c);val refs=online.chapters("public:1");val expected=online.chapter("public:1",refs[0].id).text
        val cache=OfflineStore(File(app.noBackupFilesDir,"txt"),BookHubSource.ownerId(c));BookDownloader(c,cache,network).download("public:1",false)
        network.online=false;val before=calls;val cold=BookHubSource(client(),cache,network);cold.chapters("public:1")
        val actual=cold.chapter("public:1",refs[0].id).text
        assertEquals(expected,actual);assertTrue(actual.contains("src='a' href=\"b\""));assertTrue(actual.contains("🌙"));assertEquals(before,calls)
    }
    @Test fun epubPortableRestorationChangesAttributesWithoutRewritingNovelTextOrEscapedExamples() {
        val original="<html><body><p>这是正文 src='pic' href=\"pic\"，以及 &lt;img src='pic'&gt;。</p><img src='pic'/></body></html>"
        val restored=ReadingPackage.restoreLinks(original,mapOf("pic" to "/api/v1/books/public%3A1/resources/pic?ticket=fixture"))
        assertEquals(EpubPlainText.extract(original),EpubPlainText.extract(restored))
        assertEquals("/api/v1/books/public%3A1/resources/pic",org.jsoup.Jsoup.parse(restored).selectFirst("img")!!.attr("src"))
    }
}
