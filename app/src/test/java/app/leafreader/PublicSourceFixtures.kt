package app.leafreader

import android.content.Context
import android.graphics.Bitmap
import app.leafreader.data.*
import app.leafreader.offline.ConnectionState
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.net.URI
import java.io.ByteArrayOutputStream
import java.util.zip.*

/** Self-authored protocol/images/content, never copies Project Gutenberg books. */
internal class PublicSourceFixtures:WireTransport {
    class Network:ConnectionState {var up=true;var onWifi=true;override val online get()=up;override val wifi get()=onWifi}
    val network=Network();var clock=1_000_000L
    val calls=java.util.Collections.synchronizedList(mutableListOf<String>())
    var before:(suspend(String)->Unit)?=null
    var failure=0
    val image:ByteArray=ByteArrayOutputStream().also {out->val b=Bitmap.createBitmap(12,18,Bitmap.Config.ARGB_8888);b.eraseColor(0xff8b4437.toInt());b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle()}.toByteArray()
    val epub:ByteArray=getEpub()
    fun source(context:Context)=GutenbergSource(context,this,network,bootstrap=false){clock+=10_000;clock}
    fun getEpub(external:Boolean=false,unsafe:Boolean=false):ByteArray {
        val files=linkedMapOf("mimetype" to "application/epub+zip".toByteArray(),"META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>".toByteArray(),
            "OPS/book.opf" to """<package><metadata><dc:identifier xmlns:dc="http://purl.org/dc/elements/1.1/">http://www.gutenberg.org/1</dc:identifier><meta name="cover" content="cover-image"/></metadata><manifest>
            <item id="cover-image" href="cover.png" media-type="image/png" properties="cover-image"/>
            <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
            <item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>
            <item id="two" href="two.xhtml" media-type="application/xhtml+xml"/>
            </manifest><spine><itemref idref="cover"/><itemref idref="one"/><itemref idref="two"/></spine></package>""".toByteArray(),
            "OPS/cover.png" to image,
            "OPS/cover.xhtml" to "<html><body><svg><image href='${if(external)"https://outside.invalid/private.png"else"cover.png"}'/></svg></body></html>".toByteArray(),
            "OPS/one.xhtml" to ("<html><body><h1>自写第一章</h1><p>"+"这是独立公开源的自写中文测试。".repeat(400)+"</p><script>ignored()</script></body></html>").toByteArray(),
            "OPS/two.xhtml" to "<html><body><h1>自写第二章</h1><p>只用于离线和章节顺序回归。</p></body></html>".toByteArray())
        if(unsafe)files["../escape"]=byteArrayOf(1)
        return ByteArrayOutputStream().also{out->ZipOutputStream(out).use{z->files.forEach{(n,b)->z.putNextEntry(ZipEntry(n).apply{time=0});z.write(b);z.closeEntry()}}}.toByteArray()
    }
    fun rdf(number:String)="""<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:pgterms="http://www.gutenberg.org/2009/pgterms/"><pgterms:ebook rdf:about="ebooks/$number"><dcterms:rights>Public domain in the USA.</dcterms:rights><pgterms:file rdf:about="https://www.gutenberg.org/cache/epub/$number/pg$number.cover.small.jpg"/></pgterms:ebook></rdf:RDF>"""
    val catalog:String get()="Text#,Type,Issued,Title,Language,Authors,Subjects,LoCC,Bookshelves\r\n"+(1..27).joinToString("\r\n"){"$it,Text,2026-01-01,自写公版书 $it,zh,自写作者,自写测试,PL,测试"}+"\r\n28,Text,2026-01-01,English title,en,Writer,Test,P,Test\r\n"
    override suspend fun execute(uri:URI,method:String,headers:Map<String,String>,body:ByteArray?,maxBytes:Int):WireResponse {
        check(network.up){"No request while offline"};check(method=="GET"&&body==null);check(headers.keys.none{it.equals("Authorization",true)||it.equals("Cookie",true)})
        check(uri.host in setOf("www.gutenberg.org","gutenberg.pglaf.org"));calls+=uri.toString();before?.invoke(uri.path)
        if(uri.host=="www.gutenberg.org")return WireResponse(503,ByteArray(0))
        if(failure!=0)return WireResponse(failure,ByteArray(0))
        val n=uri.path.substringAfter("/cache/epub/").substringBefore('/')
        val b=when {
            uri.path.endsWith("pg_catalog.csv.gz")->ByteArrayOutputStream().also{out->GZIPOutputStream(out).use{it.write(catalog.toByteArray())}}.toByteArray()
            uri.path.endsWith(".rdf")->rdf(n).toByteArray()
            uri.path.endsWith(".cover.small.jpg")->image
            uri.path.endsWith(".epub")->epub
            uri.path.endsWith("/")->"<a href='pg$n-images-3.epub'>EPUB</a>".toByteArray()
            else->error("Unexpected public path")
        };check(b.size<=maxBytes);return WireResponse(200,b)
    }
}
