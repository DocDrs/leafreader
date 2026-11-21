package app.leafreader.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

internal data class EpubGraphicTarget(val event: Int, val offset: Int, val source: String, val description: String)
internal data class EpubSlice(val text: String, val hasImages: Boolean, val graphics: List<EpubGraphicTarget> = emptyList())
internal data class EpubPoint(val event: Int, val offset: Int)

/** One local DOM traversal. No WebView, external loads, scripts, CSS, images or OCR. */
internal class EpubTextDocument(html: String) {
    private val text: String
    private val anchors = mutableMapOf<String, MutableList<EpubPoint>>()
    private val imageEvents = mutableListOf<Int>()
    private val graphics = mutableListOf<EpubGraphicTarget>()
    private val rootAnchors: Set<String>
    init {
        val doc = Jsoup.parse(html)
        if (doc.select("form input[type=password]").isNotEmpty())
            throw SourceProblem(FailureKind.AUTH, "正文请求返回登录页面，请重新登录；旧进度已保留", diagnostic = "BH_RESOURCE_LOGIN_PAGE")
        val body = doc.getAllElements().firstOrNull { it.tagName().substringAfter(':').equals("body", true) } ?: doc.body()
        rootAnchors = setOf(body.id(),body.attr("name")).filter { it.isNotEmpty() }.toSet()
        body.getAllElements().toList().filter { it.tagName().substringAfter(':').lowercase() in setOf("script","style","iframe","object","nav") }.forEach { it.remove() }
        // Retain identities and explicit raster references. Never execute or render the SVG document.
        val svgAnchors = java.util.IdentityHashMap<Element,List<String>>()
        val svgImages = java.util.IdentityHashMap<Element,List<Pair<String,String>>>()
        body.getAllElements().toList().filter { it.tagName().substringAfter(':').equals("svg", true) &&
            it.parents().none { p -> p.tagName().substringAfter(':').equals("svg",true) } }.forEach { svg ->
            svgAnchors[svg]=svg.getAllElements().drop(1).flatMap { setOf(it.id(),it.attr("name")).filter(String::isNotEmpty) }
            svgImages[svg]=svg.getAllElements().filter { it.tagName().substringAfter(':').equals("image",true) }.map {
                (it.attr("href").ifBlank { it.attr("xlink:href") }) to it.attr("aria-label").ifBlank { "书中图片" }
            }
            svg.empty(); svg.attr("data-leaf-graphic", "1")
        }
        val out = StringBuilder(); var event = 0
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                val point = EpubPoint(event++, out.length)
                if (node is Element) {
                    setOf(node.id(), node.attr("name")).filter { it.isNotEmpty() }.forEach { anchors.getOrPut(it) { mutableListOf() }.add(point) }
                    svgAnchors[node].orEmpty().forEach { anchors.getOrPut(it) { mutableListOf() }.add(point) }
                    if (node.tagName().substringAfter(':').lowercase() in setOf("img","image","svg") || node.hasAttr("data-leaf-graphic")) imageEvents += point.event
                    if(node.tagName().substringAfter(':').equals("img",true)) graphics += EpubGraphicTarget(point.event,out.length,node.attr("src"),node.attr("alt").ifBlank { "书中图片" })
                    svgImages[node].orEmpty().forEach { (source,description) -> graphics += EpubGraphicTarget(point.event,out.length,source,description) }
                    if (node.isBlock || node.tagName() == "br") out.append('\n')
                }
                if (node is TextNode) out.append(node.wholeText)
            }
            override fun tail(node: Node, depth: Int) { if (node is Element && node.isBlock) out.append('\n') }
        }, body)
        text = out.toString()
    }
    fun hasNoAnchor(fragment: String) = fragment.isNotEmpty() && anchors[fragment].isNullOrEmpty()
    fun point(fragment: String): EpubPoint {
        if (fragment.isEmpty()) return EpubPoint(-1, 0)
        val matches = anchors[fragment].orEmpty()
        if (matches.size != 1) throw SourceProblem(FailureKind.PROTOCOL,
            if (matches.isEmpty()) "EPUB 目录锚点不存在，原进度已保留" else "EPUB 目录锚点重复，无法安全定位",
            diagnostic = "BH_EPUB_ANCHOR")
        return matches.single()
    }
    fun nextFragment(start: String, candidates: List<String>): String? {
        val at = point(start)
        return candidates.distinct().map { it to point(it) }.filter { it.second.event > at.event }
            .filter { (_,end) ->
                // Whole-document navigation can alias a first heading at the text start.
                // An actual image/graphic prefix remains a separate unreadable page.
                (start.isNotEmpty() && start !in rootAnchors) || text.substring(at.offset,end.offset).isNotBlank() ||
                    imageEvents.any { it >= at.event && it < end.event }
            }.minByOrNull { it.second.event }?.first
    }
    fun readingEvent(fragment: String, candidates: List<String>): Int {
        val start = point(fragment)
        if(fragment.isNotEmpty() && fragment !in rootAnchors) return start.event
        return candidates.distinct().map { point(it) }.filter { end ->
            end.event > start.event && text.substring(start.offset,end.offset).isBlank() &&
                imageEvents.none { it >= start.event && it < end.event }
        }.maxByOrNull { it.event }?.event ?: start.event
    }
    fun slice(start: String = "", end: String = ""): EpubSlice {
        val a = point(start); val b = if (end.isEmpty()) EpubPoint(Int.MAX_VALUE, text.length) else point(end)
        if (b.event <= a.event || b.offset < a.offset) throw SourceProblem(FailureKind.PROTOCOL, "EPUB 章节范围无法安全定位", diagnostic = "BH_EPUB_RANGE")
        return EpubSlice(text.substring(a.offset,b.offset), imageEvents.any { it >= a.event && it < b.event },
            graphics.filter { it.event >= a.event && it.event < b.event }.map { it.copy(offset=it.offset-a.offset) })
    }
}

object EpubPlainText {
    fun extract(html: String, start: String = "", end: String = ""): String {
        val slice = EpubTextDocument(html).slice(start,end)
        if (slice.text.isBlank() && slice.hasImages) throw SourceProblem(FailureKind.UNSUPPORTED,
            "未提取到文字，资源含图片或图形；本版暂不支持", diagnostic = "BH_EPUB_IMAGE_ONLY")
        return slice.text
    }
}
