package app.leafreader.covers

import app.leafreader.data.*
import app.leafreader.offline.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI

/** No fetches. Only an explicitly identified cover page in existing, integrity-checked EPUB resources. */
internal object EmbeddedCover {
    data class Found(val version:String,val resourceId:String,val bytes:ByteArray)
    fun read(store:OfflineStore,book:String,manifest:JSONObject):Found? {
        if(manifest.optString("bookId")!=book || manifest.optString("format")!="EPUB")return null
        val version=manifest.getString("contentVersion");if(version.isBlank() || version.length>512 || version.any{it.code<32})return null
        val first=manifest.optJSONArray("spine")?.optString(0)?.takeIf{it.isNotBlank()}?:return null
        val array=manifest.getJSONArray("resources");val resources=(0 until array.length()).map{array.getJSONObject(it)}
        val page=resources.singleOrNull{it.optString("resourceId")==first}?:return null
        if(page.optString("mediaType") !in setOf("application/xhtml+xml","text/html"))return null
        if(!store.hasResource(book,version,first))return null
        val raw=store.resource(book,version,first,1024*1024)?:return null
        var html=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString()
        val urls=resources.associate{it.getString("resourceId") to it.getString("url")}
        if(store.portable(book,version,first))html=ReadingPackage.restoreLinks(html,urls)
        val doc=Jsoup.parse(html)
        fun isCover(s:String)=s.trim().lowercase() in setOf("cover","封面")
        val toc=manifest.optJSONArray("toc")
        val named=toc!=null && (0 until toc.length()).any { i->val t=toc.optJSONObject(i);t?.optString("resourceId")==first && isCover(t.optString("label")) }
        val semantic=doc.getAllElements().any{it.attr("epub:type").split(Regex("\\s+")).contains("cover") || it.attr("role")=="doc-cover"}
        if(!named && !semantic && !isCover(doc.title()))return null
        val slice=EpubTextDocument(html).slice("","")
        // Even a misleading title must not turn an ordinary illustrated chapter into a thumbnail.
        if(slice.text.codePointCount(0,slice.text.length)>256)return null
        val targets=slice.graphics.map{it.source}.distinct()
        if(targets.size!=1)return null
        val target=runCatching{URI(targets.single())}.getOrNull()?:return null
        if(target.isAbsolute || target.rawAuthority!=null || target.rawFragment!=null)return null
        val image=resources.singleOrNull { it.optString("mediaType") in setOf("image/png","image/jpeg","image/gif","image/webp") &&
            it.optString("url").substringBefore('?')==targets.single().substringBefore('?') }?:return null
        val rid=image.getString("resourceId");if(!store.hasResource(book,version,rid))return null
        return Found(version,rid,store.resource(book,version,rid,8L*1024*1024)?:return null)
    }
}
