package app.leafreader

import android.graphics.Bitmap
import app.leafreader.data.*
import app.leafreader.offline.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Entirely self-authored fixture content, pixels and identities. No real book data. */
class OfflineFixtures:WireTransport {
    class Network:ConnectionState {@Volatile override var online=true;@Volatile override var wifi=true}
    val network=Network()
    var version="offline-fixture-v1"
    var bundleStatus=200
    var accessStatus=200
    var accessError="SESSION_REVOKED"
    var wrongHash=false
    var unsafeZip=false
    var onResponse:(suspend (String)->Unit)?=null
    val calls=java.util.Collections.synchronizedList(mutableListOf<String>())
    private val prefix="/api/v1/books/public%3A1/resources/"
    private fun resource(id:String,type:String)=JSONObject().put("resourceId",id).put("mediaType",type).put("url",prefix+id+"?ticket=fixture")
    fun manifest():JSONObject=JSONObject().put("bookId","public:1").put("format","EPUB").put("contentVersion",version).put("expiresAt","2099-01-01T00:00:00Z")
        .put("spine",JSONArray(listOf("cover","a","b","c")))
        .put("resources",JSONArray().put(resource("cover","application/xhtml+xml")).put(resource("a","application/xhtml+xml")).put(resource("b","application/xhtml+xml")).put(resource("c","application/xhtml+xml")).put(resource("pic","image/png")))
        .put("toc",JSONArray().apply{listOf("cover" to "cover-pic","a" to "one","b" to "two","c" to "three").forEachIndexed{i,(id,frag)->put(JSONObject().put("label","测试项$i").put("resourceId",id).put("href",prefix+id+"#"+frag))}}).put("metadata",JSONObject())
    fun bytes(portable:Boolean=false):Map<String,ByteArray> {
        val picture=if(portable) "pic" else prefix+"pic?ticket=fixture"
        val png=ByteArrayOutputStream().also { out->Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff4c765b.toInt())}.compress(Bitmap.CompressFormat.PNG,100,out) }.toByteArray()
        return mapOf("cover" to "<html><body><svg><image id='cover-pic' href='$picture'/></svg></body></html>".toByteArray(),
            "a" to "<html><body><h1 id='one'>甲</h1><p>${("第一段是自写回归文字。🌙"+version).repeat(12)}</p></body></html>".toByteArray(),
            "b" to "<html><body><h1 id='two'>乙</h1><p>${("第二段是自写回归文字。"+version).repeat(12)}</p></body></html>".toByteArray(),
            "c" to "<html><body><h1 id='three'>丙</h1><p>${("第三段是自写回归文字。"+version).repeat(12)}</p></body></html>".toByteArray(),"pic" to png)
    }
    fun packageBytes():ByteArray {
        val content=bytes(true);val access=manifest();val resources=JSONArray()
        access.getJSONArray("resources").let{array->for(i in 0 until array.length()){val r=array.getJSONObject(i);val id=r.getString("resourceId");val raw=content.getValue(id);resources.put(JSONObject().put("resourceId",id).put("path","resources/$id").put("mediaType",r.getString("mediaType")).put("sizeBytes",raw.size).put("sha256",sha(raw)))}}
        val manifest=JSONObject().put("schemaVersion",1).put("bookId","public:1").put("contentVersion",version).put("format","EPUB").put("spine",access.getJSONArray("spine")).put("resources",resources)
        val out=ByteArrayOutputStream();ZipOutputStream(out).use{z->
            fun entry(name:String,data:ByteArray){z.putNextEntry(ZipEntry(name).apply{time=0});z.write(data);z.closeEntry()}
            entry("manifest.json",manifest.toString().toByteArray());content.forEach{(id,raw)->entry("resources/$id",raw)}
            if(unsafeZip)entry("../escape",byteArrayOf(1))
        };return out.toByteArray()
    }
    fun client(account:String="fixture-account",origin:String="https://offline.fixture.invalid")=BookHubClient(ServiceOrigin(origin),this).also{it.restore(SavedSession(origin,account,"fixture-device","fixture-access","fixture-refresh"))}
    override suspend fun execute(uri:java.net.URI,method:String,headers:Map<String,String>,body:ByteArray?,maxBytes:Int):WireResponse {
        check(network.online){"HTTP attempted while offline"};calls+="$method ${uri.path}"
        val response=when {
            uri.path.endsWith("/bookshelf")->WireResponse(200,BookHubFixtures.envelope(BookHubFixtures.page().apply{getJSONArray("items").getJSONObject(0).put("format","EPUB")}).toByteArray())
            uri.path.endsWith("/access")->WireResponse(accessStatus,if(accessStatus==200)BookHubFixtures.envelope(manifest()).toByteArray() else JSONObject().put("code",accessError).toString().toByteArray())
            uri.path.endsWith("/reading-package")-> {
                check(method=="POST");check(headers["Authorization"]!=null)
                val raw=packageBytes();WireResponse(bundleStatus,BookHubFixtures.envelope(JSONObject().put("kind","READING_ZIP").put("schemaVersion",1).put("contentVersion",version).put("sizeBytes",raw.size).put("sha256",if(wrongHash) "0".repeat(64) else sha(raw)).put("downloadUrl","/api/v1/books/public%3A1/reading-package/content?ticket=fixture")).toByteArray())
            }
            uri.path.endsWith("/reading-package/content")->{check(headers["Authorization"]==null);WireResponse(200,packageBytes(),"application/zip")}
            else->{check(headers["Authorization"]==null);val id=uri.path.substringAfterLast('/');WireResponse(200,bytes().getValue(id),if(id=="pic")"image/png" else "application/xhtml+xml")}
        }
        onResponse?.invoke(uri.path);return response
    }
}
