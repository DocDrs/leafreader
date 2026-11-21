package app.leafreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import app.leafreader.data.*
import app.leafreader.offline.*
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

/** Original pixels/metadata only; never packaged as app assets. */
class CoverFixtures:WireTransport {
    val base=OfflineFixtures();val calls=java.util.Collections.synchronizedList(mutableListOf<String>())
    var version="cover-v1";var color=0xff366553.toInt();var coverStatus=200;var accessStatus=200;var badImage=false;var wrongBook=false
    var afterCover:(suspend()->Unit)?=null;var named=true;var titleMarker=false;var externalImage=false
    val active=AtomicInteger();val peak=AtomicInteger()
    fun image():ByteArray {
        val b=Bitmap.createBitmap(160,240,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(color);val p=Paint().apply{this.color=0xfff5dfb0.toInt();isAntiAlias=true}
        c.drawCircle(111f,61f,27f,p);p.color=0xff1f3631.toInt();c.drawRect(0f,163f,160f,240f,p);p.color=0xfff5dfb0.toInt();p.textSize=24f;c.drawText("LEAF",20f,131f,p)
        return ByteArrayOutputStream().also{b.compress(Bitmap.CompressFormat.PNG,100,it);b.recycle()}.toByteArray()
    }
    fun manifest(book:String="public:1")=base.manifest().put("bookId",book).put("contentVersion",version).apply{getJSONArray("toc").getJSONObject(0).put("label",if(named)"封面" else "第一章")}
    fun resources()=base.bytes().toMutableMap().apply {
        this["pic"]=image()
        this["cover"]=("<html><head><title>"+(if(titleMarker)"Cover" else "")+"</title></head><body><svg><image href='"+(if(externalImage)"https://untrusted.fixture.invalid/track" else "/api/v1/books/public%3A1/resources/pic")+"'/></svg></body></html>").toByteArray()
    }
    fun client(account:String="cover-fixture-account")=BookHubClient(ServiceOrigin("https://covers.fixture.invalid"),this).also{it.restore(SavedSession(it.origin.value,account,"fixture-device","fixture-access","fixture-refresh"))}
    fun seed(store:OfflineStore,all:Boolean=true) {val epoch=store.generation("public:1");store.saveManifest("public:1",manifest(),epoch);resources().filter{all || it.key=="cover"}.forEach{(id,bytes)->store.put("public:1",version,id,bytes,epoch)}}
    override suspend fun execute(uri:java.net.URI,method:String,headers:Map<String,String>,body:ByteArray?,maxBytes:Int):WireResponse {
        check(base.network.online){"Unexpected offline HTTP"};calls+=uri.path
        val book=URLDecoder.decode(uri.path.substringAfter("/books/","").substringBefore('/'),"UTF-8")
        return when {
            uri.path.endsWith("/bookshelf")->WireResponse(200,BookHubFixtures.envelope(BookHubFixtures.page().apply{getJSONArray("items").getJSONObject(0).put("format","EPUB")}).toByteArray())
            uri.path.endsWith("/access")->{check(headers["Authorization"]=="Bearer fixture-access");WireResponse(accessStatus,BookHubFixtures.envelope(manifest(if(wrongBook)"public:999" else book)).toByteArray())}
            uri.path.endsWith("/cover")-> {
                check(headers["Authorization"]=="Bearer fixture-access");check(method=="GET");val n=active.incrementAndGet();peak.updateAndGet{maxOf(it,n)}
                try{delay(25);afterCover?.invoke();WireResponse(coverStatus,if(badImage)"<svg><script>never execute</script></svg>".toByteArray() else image(),"image/png")}finally{active.decrementAndGet()}
            }
            else->error("Unexpected thumbnail path")
        }
    }
}
