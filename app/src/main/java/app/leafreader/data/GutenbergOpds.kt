package app.leafreader.data

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI

/** OPDS 1 acquisition/next/OpenSearch, scoped to the documented official catalog only. */
internal object GutenbergOpds {
    const val ROOT="https://www.gutenberg.org/ebooks/search.opds/"
    data class Feed(val books:List<Book>,val next:String?,val search:String?)
    fun official(value:String,base:String=ROOT):String {
        val u=try{URI(base).resolve(value).normalize()}catch(_:Exception){invalid()}
        if(u.scheme!="https" || u.host!="www.gutenberg.org" || u.port!=-1 || u.rawUserInfo!=null || u.rawFragment!=null || !u.path.startsWith("/ebooks/"))invalid()
        return u.toASCIIString()
    }
    fun parse(xml:String,url:String=ROOT):Feed {
        val doc=Jsoup.parse(xml,"",Parser.xmlParser());val feed=doc.getAllElements().firstOrNull{it.normalName().substringAfter(':')=="feed"}?:invalid()
        fun org.jsoup.nodes.Element.localTag()=normalName().substringAfter(':')
        fun org.jsoup.nodes.Element.child(tag:String)=children().firstOrNull{it.localTag()==tag}
        val books=feed.children().filter{it.localTag()=="entry"}.mapNotNull{e->
            val links=e.children().filter{it.localTag()=="link"}
            val acquisition=links.firstOrNull{it.attr("rel").startsWith("http://opds-spec.org/acquisition") && it.attr("type").substringBefore(';')=="application/epub+zip"}?:return@mapNotNull null
            val href=official(acquisition.attr("href"),url)
            val number=Regex("/ebooks/([1-9][0-9]{0,6})(?:[./?]|$)").find(URI(href).path)?.groupValues?.get(1)?:return@mapNotNull null
            val language=e.child("language")?.text().orEmpty()
            if(language.isNotBlank() && language!="zh" && !language.startsWith("zh-"))return@mapNotNull null
            val title=e.child("title")?.text()?.takeIf{it.isNotBlank()}?:invalid()
            if(language.isBlank() && title.none{it in '\u3400'..'\u9fff'})return@mapNotNull null
            val author=e.children().filter{it.localTag()=="author"}.mapNotNull{it.child("name")?.text()}.joinToString("、").ifBlank{"作者未注明"}
            Book("pg:$number",title.take(600),author.take(600),e.child("summary")?.text().orEmpty().take(1000),"古登堡计划",0,0xFF78664F,"EPUB",GutenbergSource.SOURCE)
        }.distinctBy{it.key}
        val links=feed.children().filter{it.localTag()=="link"}
        val next=links.firstOrNull{it.attr("rel")=="next"}?.let{official(it.attr("href"),url)}
        val search=links.firstOrNull{it.attr("rel")=="search" && it.attr("type").substringBefore(';')=="application/opensearchdescription+xml"}?.let{official(it.attr("href"),url)}
        return Feed(books,next,search)
    }
    fun searchTemplate(xml:String,base:String,query:String):String {
        val doc=Jsoup.parse(xml,"",Parser.xmlParser())
        val template=doc.getAllElements().firstOrNull{it.normalName().substringAfter(':')=="url" && it.attr("type").contains("application/atom+xml")}?.attr("template")?:invalid()
        var value=template.replace("{searchTerms}",encoded(query)).replace("{count?}","20").replace("{startIndex?}","1").replace("{startPage?}","1").replace("{language?}","zh").replace("{inputEncoding?}","UTF-8").replace("{outputEncoding?}","UTF-8")
        if(value.contains('{')||value.contains('}'))invalid()
        return official(value,base)
    }
    private fun invalid():Nothing=throw SourceProblem(FailureKind.PROTOCOL,"官方 OPDS 目录格式不受支持",diagnostic="PG_OPDS")
}
