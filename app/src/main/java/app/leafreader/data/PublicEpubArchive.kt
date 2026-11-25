package app.leafreader.data

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Bounded, in-memory EPUB container. Never extracts paths, executes scripts or loads external links. */
internal class PublicEpubArchive(bytes:ByteArray,expectedBook:String?=null) {
    val version=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private val entries=linkedMapOf<String,ByteArray>()
    private data class Item(val path:String,val type:String,val properties:String)
    private val items:Map<String,Item>
    private val spine:List<Item>
    private val cover:Item?
    private val labels:List<String>
    val chapters:List<ChapterRef>
    init {
        var total=0L;var count=0
        ZipInputStream(ByteArrayInputStream(bytes)).use { z->while(true) {
            val e=z.nextEntry?:break
            if(++count>4096)bad("EPUB 文件数量超限")
            val name=e.name
            if(name.startsWith('/') || name.contains('\\') || name.split('/').any{it==".." || it=="."} || name.any{it.code<32})bad("EPUB 包含无效路径")
            if(name in entries)bad("EPUB 包含重复文件")
            val data=java.io.ByteArrayOutputStream();val buffer=ByteArray(16384)
            while(true){val n=z.read(buffer);if(n<0)break;total+=n;if(data.size()+n>8*1024*1024 || total>32L*1024*1024)bad("EPUB 解压大小超限");data.write(buffer,0,n)}
            if(e.isDirectory){if(data.size()!=0)bad("EPUB 目录项无效");continue}
            entries[name]=data.toByteArray();z.closeEntry() // ZipInputStream verifies each CRC.
        }}
        if(entries["mimetype"]?.toString(Charsets.US_ASCII)?.trim()!="application/epub+zip")bad("不是有效 EPUB")
        if("META-INF/encryption.xml" in entries)bad("不支持加密 EPUB")
        val container=xml("META-INF/container.xml")
        val opfPath=container.getAllElements().firstOrNull{it.normalName().substringAfter(':')=="rootfile"}?.attr("full-path")?:bad("EPUB 缺少内容清单")
        val opf=xml(internalPath("",opfPath))
        if(expectedBook!=null) {
            val matches=opf.getAllElements().filter{it.normalName().substringAfter(':')=="identifier"}.any { e->
                runCatching{val u=URI(e.text());u.scheme in setOf("http","https") && u.host in setOf("www.gutenberg.org","gutenberg.org") && u.path=="/$expectedBook"}.getOrDefault(false)
            }
            if(!matches)bad("EPUB 与当前书籍标识不一致")
        }
        if(opf.getAllElements().any{it.attr("property")=="rendition:layout" && it.text()=="pre-paginated"})bad("固定版式 EPUB 暂不支持")
        val manifest=opf.getAllElements().filter{it.normalName().substringAfter(':')=="item"}
        if(manifest.size>4096 || manifest.map{it.id()}.toSet().size!=manifest.size)bad("EPUB 清单无效")
        items=manifest.associate { it.id() to Item(internalPath(opfPath,it.attr("href")),it.attr("media-type"),it.attr("properties")) }
        val spineNode=opf.getAllElements().firstOrNull{it.normalName().substringAfter(':')=="spine"}?:bad("EPUB 缺少阅读顺序")
        spine=spineNode.children().filter{it.normalName().substringAfter(':')=="itemref"}.map { items[it.attr("idref")]?:bad("EPUB 阅读资源缺失") }
        if(spine.isEmpty() || spine.size>2000 || spine.any{it.type !in setOf("application/xhtml+xml","text/html") || it.path !in entries})bad("EPUB 正文类型或顺序不受支持")
        val coverId=opf.getAllElements().firstOrNull{it.normalName().substringAfter(':')=="meta" && it.attr("name")=="cover"}?.attr("content")
        cover=items.values.firstOrNull{"cover-image" in it.properties.split(' ')}?:coverId?.let(items::get)
        labels=spine.mapIndexed { i,item->
            val doc=Jsoup.parse(text(item.path));val heading=doc.select("h1,h2,h3").firstOrNull()?.text().orEmpty()
            when { i==0 && cover!=null && EpubTextDocument(text(item.path)).slice().let{it.text.isBlank() && it.graphics.isNotEmpty()}->"封面"
                heading.isNotBlank()->heading.take(120)
                doc.title().isNotBlank()->doc.title().take(120)
                else->"正文 ${i+1}" }
        }
        chapters=spine.mapIndexed { i,item->ChapterRef("epub:${version.take(16)}:$i:${digest(item.path).take(16)}",labels[i]) }
        // Validate every reading resource and declared local raster before marking the package complete.
        spine.forEach { item->EpubTextDocument(text(item.path)).slice().graphics.forEach { g->
            val path=internalPath(item.path,g.source);val image=items.values.firstOrNull{it.path==path}?:bad("EPUB 插图未在清单中声明")
            if(image.type !in RASTER_TYPES || path !in entries)bad("EPUB 插图缺失或格式不受支持")
        }}
    }
    private fun text(path:String)=try{Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(entries[path]?:bad("EPUB 资源缺失"))).toString()}catch(e:java.nio.charset.CharacterCodingException){bad("EPUB 文字编码不受支持")}
    private fun xml(path:String)=Jsoup.parse(text(path),"",Parser.xmlParser())
    suspend fun chapter(id:String):Chapter {
        val index=chapters.indexOfFirst{it.id==id};if(index<0)throw SourceProblem(FailureKind.VERSION,"原 EPUB 章节版本已变化，请从目录选择；原进度保留",diagnostic="PG_VERSION")
        val item=spine[index];val slice=EpubTextDocument(text(item.path)).slice()
        val graphics=mutableListOf<ChapterGraphic>()
        if(slice.graphics.size>8)bad("本章图片超过安全显示范围")
        var pixels=0L
        for((i,g)in slice.graphics.withIndex()) {
            val path=internalPath(item.path,g.source);val bitmap=SafeRaster.decode(entries[path]?:bad("EPUB 插图缺失"))
            pixels+=bitmap.width.toLong()*bitmap.height
            if(pixels>8_000_000){bitmap.recycle();graphics.forEach{it.bitmap.recycle()};bad("本章图片尺寸超过安全显示范围")}
            graphics+=ChapterGraphic("${version.take(16)}:$index:$i",if(slice.text.isBlank())i else g.offset,bitmap,g.description)
        }
        if(slice.text.isBlank() && graphics.isEmpty())throw SourceProblem(FailureKind.EMPTY,"这一节没有可显示的内容",diagnostic="PG_EMPTY")
        return Chapter(id,labels[index],if(slice.text.isBlank())emptyList()else listOf(slice.text),headingOnly=true,graphics=graphics)
    }
    fun lengths():List<Int> = spine.map { item->val slice=EpubTextDocument(text(item.path)).slice();if(slice.text.isBlank())slice.graphics.size else slice.text.length }
    fun coverBytes():ByteArray?=cover?.takeIf{it.type in RASTER_TYPES}?.let{entries[it.path]}
    companion object {
        private val RASTER_TYPES=setOf("image/png","image/jpeg","image/gif","image/webp")
        private fun bad(message:String):Nothing=throw SourceProblem(FailureKind.PROTOCOL,message,diagnostic="PG_EPUB")
        private fun internalPath(base:String,href:String):String {
            if(href.isBlank() || href.contains('\\') || href.any{it.code<32})bad("EPUB 资源路径无效")
            val raw=try{URI(href)}catch(_:Exception){bad("EPUB 资源路径无效")}
            if(raw.isAbsolute || raw.rawAuthority!=null || raw.rawQuery!=null)bad("EPUB 外部资源未加载")
            val resolved=URI("https://epub.invalid/"+base).resolve(raw).normalize()
            val path=resolved.path.removePrefix("/")
            if(resolved.host!="epub.invalid" || path.split('/').any{it==".." || it=="."} || path.contains('\\') || Regex("(?i)%2e|%2f|%5c|%25").containsMatchIn(resolved.rawPath))bad("EPUB 资源路径无效")
            return path
        }
    }
}
