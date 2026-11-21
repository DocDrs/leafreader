package app.leafreader.data

import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import java.time.Instant
import app.leafreader.offline.*
import kotlinx.coroutines.Dispatchers
import java.io.IOException

internal fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
private fun JSONObject.required(key: String): String = (opt(key) as? String)?.takeIf { it.isNotBlank() } ?: protocol()
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { optJSONObject(it) ?: protocol() }

private data class Section(val resourceId: String, val url: String, val originalId: String, val href: String, val label: String,
                           val anchors: Set<String>, val start: Int?, val end: Int?, val originalIndex: Int?, val startPath: String)
private data class ChapterSpec(val id: String, val label: String, val first: Int, val last: Int,
                               val startPoint: Int? = null, val endPoint: Int? = null,
                               val startFragment: String = "", val endFragment: String = "", val navigation: Boolean = false)
private data class ImageResource(val id: String, val url: String, val mediaType: String)
private data class Manifest(val format: String, val version: String, val expiresAt: Instant, val sections: List<Section>, val chapters: List<ChapterSpec>,
    val images: List<ImageResource>, val resources:Map<String,String>,val cacheEpoch:Long?=null,val offlineOnly:Boolean=false)
private class InitialReadBudget : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<InitialReadBudget>
    private var requests = 0
    val canRequest: Boolean get() = requests < 12
    fun charge() {
        if (requests >= 12) throw SourceProblem(FailureKind.UNSUPPORTED,"首读探查已达到 12 次资源请求限制，请从目录选择正文；尚未保存阅读进度",diagnostic="BH_EPUB_START_RESOURCE_LIMIT")
        requests++
    }
}


class BookHubSource(private val client: BookHubClient, val offline:OfflineStore?=null, val connection:ConnectionState=AlwaysOnline) : BookSource {
    companion object {fun ownerId(client:BookHubClient)="bookhub-v1-" + digest(client.origin.value + "|" + (client.accountId ?: protocol("请先登录"))).take(32)}
    override val id = ownerId(client)
    internal fun coverSessionValid()=client.snapshot()!=null && runCatching{ownerId(client)==id}.getOrDefault(false)
    internal suspend fun coverVersion(bookId:String)=client.coverVersion(bookId)
    internal suspend fun coverBytes(bookId:String)=client.coverBytes(bookId)
    init { require(offline==null || offline.owner==id){"Offline identity mismatch"} }
    private val manifests = mutableMapOf<String, Manifest>()
    private var cacheWarning:String?=null
    private fun checkOfflineIdentity() {if(offline!=null && (client.snapshot()==null || ownerId(client)!=id))throw SourceProblem(FailureKind.AUTH,"请重新登录所属账号后读取离线内容",diagnostic="BH_OFFLINE_AUTH")}
    private suspend fun <T> disk(action:()->T):T=withContext(Dispatchers.IO){action()}
    private suspend fun cacheWrite(action:()->Unit) { try{disk(action);OfflineEvents.changed()}catch(e:IOException){if(e is SourceProblem)throw e;cacheWarning="本机空间或存储不可用，本次内容未能离线保存。"} }
    fun forgetDownloaded(bookId:String) {offline?.clear(bookId);manifests.remove(bookId);texts.keys.filter{it.startsWith("$bookId|")}.toList().forEach{texts.remove(it)};OfflineEvents.changed()}
    private val texts = object : LinkedHashMap<String, String>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 16
    }
    override suspend fun books() = browse().items
    override suspend fun browse(query: String, cursor: String?): BookPage {
        checkOfflineIdentity()
        if(offline!=null && !connection.online) {
            if(query.isNotBlank())throw SourceProblem(FailureKind.NETWORK,"当前离线，找书需要联网；已保存的书架仍可阅读",diagnostic="BH_OFFLINE_SEARCH")
            return BookPage(emptyList())
        }
        val path = if (query.isBlank()) "/me/bookshelf" else "/catalog/search"
        val params = "?limit=20&q=${encoded(query.trim())}" + (cursor?.let { "&cursor=${encoded(it)}" } ?: "")
        val data = client.api(path + params)
        val array = data.optJSONArray("items") ?: protocol()
        if (array.length() > 200 || data.opt("hasMore") !is Boolean) protocol()
        val more = data.getBoolean("hasMore")
        val next = data.opt("nextCursor").let { if (it == null || it == JSONObject.NULL) null else it as? String ?: protocol() }
        if (more && (next.isNullOrBlank() || next == cursor || array.length() == 0)) protocol("服务返回的分页游标无效，请重新搜索")
        val books = array.objects().map { b ->
            val bookId = b.required("bookId")
            if (!Regex("^(public:[0-9]+|(?:private|shared):[a-fA-F0-9-]{36})$").matches(bookId)) protocol("服务返回的书籍标识无效")
            Book(bookId, b.optString("title").takeUnless { it.isBlank() || it == "null" } ?: "未命名图书",
                b.optString("author").takeUnless { it.isBlank() || it == "null" } ?: "作者未注明",
                b.optString("description").takeUnless { it == "null" } ?: "", "我的服务", 0, 0xFF516D66,
                b.required("format"))
        }.distinctBy { it.id }
        return BookPage(books, if (more) next else null, more)
    }
    override suspend fun chapters(bookId: String): List<ChapterRef> {
        checkOfflineIdentity()
        val manifest = fetchManifest(bookId)
        manifests[bookId] = manifest
        return manifest.chapters.map { ChapterRef(it.id, it.label) }
    }
    override suspend fun chaptersFor(bookId:String,chapterId:String?):List<ChapterRef> {
        checkOfflineIdentity()
        if(offline!=null && chapterId!=null) {
            val manifest=fetchManifest(bookId,chapterId);manifests[bookId]=manifest
            return manifest.chapters.map { ChapterRef(it.id,it.label) }
        }
        return chapters(bookId)
    }
    override suspend fun chapter(bookId: String, chapterId: String): Chapter {
        checkOfflineIdentity()
        var manifest = manifests[bookId] ?: fetchManifest(bookId).also { manifests[bookId] = it }
        if (connection.online && !manifest.offlineOnly && manifest.expiresAt.isBefore(Instant.now().plusSeconds(20))) manifest = renew(bookId, manifest)
        try { return readChecked(bookId, chapterId, manifest) }
        catch (e: SourceProblem) {
            if (e.kind != FailureKind.AUTH && e.kind != FailureKind.FORBIDDEN) throw e
            if(coroutineContext[InitialReadBudget]?.canRequest == false) throw e
            // One fresh access manifest only; never change a content version or bypass a denied request.
            manifest = renew(bookId, manifest)
            return readChecked(bookId, chapterId, manifest)
        }
    }
    private suspend fun readChecked(bookId: String, chapterId: String, manifest: Manifest): Chapter = try {
        readChapter(bookId, chapterId, manifest).let { chapter->
            val warning=cacheWarning;cacheWarning=null
            if(warning==null)chapter else chapter.copy(navigationNotice=listOfNotNull(chapter.navigationNotice,warning).joinToString(" "))
        }
    } catch (e: SourceProblem) {
        // A failed parse must not make Retry reuse an invalid resource forever.
        manifest.chapters.firstOrNull { it.id == chapterId }?.let { spec ->
            for (index in spec.first..spec.last) texts.remove("$bookId|${manifest.version}|${manifest.sections[index].resourceId}")
            if(e.kind in setOf(FailureKind.PROTOCOL,FailureKind.AUTH)) for(index in spec.first..spec.last)
                offline?.let{cacheWrite{it.evict(bookId,manifest.version,manifest.sections[index].resourceId,manifest.cacheEpoch)}}
        }
        throw e
    }
    private suspend fun renew(bookId: String, old: Manifest): Manifest {
        val fresh = fetchManifest(bookId)
        if (fresh.version != old.version || fresh.format != old.format || fresh.chapters != old.chapters ||
            fresh.sections.map { it.copy(url="") } != old.sections.map { it.copy(url="") })
            throw SourceProblem(FailureKind.VERSION, "图书内容或阅读清单已变化，请返回目录重新打开；旧进度会保留")
        manifests[bookId] = fresh
        return fresh
    }
    private suspend fun fetchManifest(bookId: String,preferredChapter:String?=null): Manifest {
        val cache=offline;val epoch=cache?.generation(bookId)
        if(cache!=null && !connection.online)return parseManifest(disk{cache.manifest(bookId,preferredChapter)}?:offlineMissing()).copy(cacheEpoch=epoch,offlineOnly=true)
        val data = try {client.api("/books/${encoded(bookId)}/access")}
        catch(e:Exception) {
            if(cache!=null && isNetworkFailure(e)) return parseManifest(disk{cache.manifest(bookId,preferredChapter)}?:throw e).copy(cacheEpoch=epoch,offlineOnly=true)
            throw e
        }
        if (data.required("bookId") != bookId) protocol("阅读清单与当前书籍不一致")
        val parsed=parseManifest(data).copy(cacheEpoch=epoch)
        if(cache!=null)cacheWrite{cache.saveManifest(bookId,data,epoch!!)}
        return parsed
    }
    fun validateAccess(data:JSONObject) {parseManifest(data)}
    private fun isNetworkFailure(e:Exception)=e is IOException && (e !is SourceProblem || e.kind==FailureKind.NETWORK && (e.httpStatus==null || e.httpStatus>=500))
    private fun offlineMissing():Nothing=throw SourceProblem(FailureKind.NETWORK,"此内容尚未下载，联网阅读或下载整本后再试；原进度已保留",diagnostic="BH_OFFLINE_MISSING")

    private fun parseManifest(data: JSONObject): Manifest {
        val format = data.required("format")
        if (format !in listOf("TXT", "EPUB")) throw SourceProblem(FailureKind.UNSUPPORTED, "本版原生正文支持 TXT 和流式 EPUB，暂不支持此格式")
        val metadata = data.optJSONObject("metadata") ?: JSONObject()
        if (metadata.optString("layout") == "pre-paginated") throw SourceProblem(FailureKind.UNSUPPORTED, "固定版式 EPUB 暂不支持原生文字阅读")
        val version = data.required("contentVersion")
        val expires = try { Instant.parse(data.required("expiresAt")) } catch (_: Exception) { protocol("阅读凭证缺少有效期限") }
        val spine = data.optJSONArray("spine") ?: protocol()
        val resources = data.optJSONArray("resources")?.objects() ?: protocol()
        if (spine.length() == 0) throw SourceProblem(FailureKind.EMPTY, "这本书尚无可读章节")
        if (spine.length() > 3000 || resources.size > 5000) protocol("图书章节过多，本版暂不能处理")
        val segments = metadata.optJSONArray("segments")?.objects().orEmpty()
        val reading = metadata.optJSONArray("readingSegments")?.objects().orEmpty()
        if (format == "EPUB" && reading.isNotEmpty() && reading.size != spine.length()) protocol("EPUB 分段与阅读顺序不一致")
        val byId = resources.groupBy { it.required("resourceId") }
        if (byId.values.any { it.size != 1 }) protocol("章节资源定义重复，无法安全定位")
        val sections = (0 until spine.length()).map { index ->
            val resourceId = spine.opt(index) as? String ?: protocol()
            val resource = byId[resourceId]?.singleOrNull() ?: protocol("章节资源缺失或重复")
            val url = resource.required("url"); client.origin.resolve(url, resource = true)
            val part = reading.getOrNull(index)?.let {
                if (it.optString("resourceId") == resourceId) it
                else if (format == "EPUB") protocol("EPUB 分段与资源位置不一致") else null
            }
            val range = segments.singleOrNull { it.optString("resourceId") == resourceId }
            Section(resourceId, url, part?.optString("originalResourceId")?.takeIf { it.isNotBlank() } ?: resourceId,
                part?.optString("href") ?: "", part?.optString("label") ?: "",
                part?.optJSONArray("anchors")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
                range?.optInt("startCodePoint", -1)?.takeIf { it >= 0 }, range?.optInt("endCodePoint", -1)?.takeIf { it >= 0 },
                (part?.opt("originalSpineIndex") as? Number)?.let {
                    val n = it.toInt()
                    if (n < 0 || it.toDouble() != n.toDouble()) protocol("EPUB 原始阅读位置无效")
                    n
                }, part?.optString("startPath").orEmpty())
        }
        if (format == "TXT" && sections.map { it.resourceId }.distinct().size != sections.size) protocol("章节顺序中存在重复资源")
        if (format == "EPUB") sections.indices.groupBy { i -> Triple(sections[i].resourceId,sections[i].originalId,sections[i].originalIndex) }
            .filter { (key,indices) -> key.third != null && indices.size > 1 }.values.forEach { indices ->
                if(indices.zipWithNext().any { (a,b) -> b != a+1 }) throw SourceProblem(FailureKind.PROTOCOL,
                    "EPUB 同一原始位置的复用资源非连续，无法安全确定范围",diagnostic="BH_EPUB_AMBIGUOUS_TARGET")
            }
        val toc = data.optJSONArray("toc")?.objects().orEmpty()
        if (toc.size > 6000) protocol("图书目录过多，本版暂不能处理")
        val hash = digest(version).take(16)
        val specs = if (format == "TXT") txtChapters(sections, toc, metadata, hash) else epubChapters(sections, toc, metadata, hash)
        val images=resources.filter { it.optString("mediaType").lowercase() in setOf("image/jpeg","image/png","image/webp","image/gif") }
            .map { ImageResource(it.required("resourceId"),it.required("url"),it.required("mediaType")) }
        images.forEach { client.origin.resolve(it.url,resource=true) }
        return Manifest(format, version, expires, sections, specs, images,resources.associate{it.required("resourceId") to it.required("url")})
    }

    private fun txtChapters(sections: List<Section>, toc: List<JSONObject>, metadata: JSONObject, hash: String): List<ChapterSpec> {
        if (sections.any { it.start == null || it.end == null || it.end <= it.start }) protocol("TXT 清单缺少有效的字符分段范围")
        sections.zipWithNext().forEach { (a, b) -> if (a.end != b.start) protocol("TXT 分段范围不连续") }
        val start = sections.first().start!!; val end = sections.last().end!!
        val length = metadata.optInt("codePointLength", end)
        if (start != 0 || length != end) protocol("TXT 总字符数与分段范围不一致")
        val boundaries = if (toc.isEmpty()) sections.mapIndexed { i, s -> s.start!! to s.label.ifBlank { "第 ${i + 1} 章" } }
        else toc.map { t ->
            val n = t.opt("codePointOffset") as? Number ?: protocol("TXT 目录缺少字符位置")
            n.toInt() to t.required("label")
        }.distinctBy { it.first }
        if (boundaries.any { it.first !in start until end } || boundaries.zipWithNext().any { (a, b) -> a.first >= b.first }) protocol("TXT 目录字符位置无效")
        val all = if (boundaries.first().first > 0) listOf(0 to "书前内容") + boundaries else boundaries
        return all.mapIndexed { index, (at, label) ->
            val until = all.getOrNull(index + 1)?.first ?: end
            val first = sections.indexOfFirst { at >= it.start!! && at < it.end!! }
            val last = sections.indexOfLast { until > it.start!! && at < it.end!! }
            ChapterSpec("txt:$hash:$at:$until", label, first, last, at, until)
        }
    }

    private fun epubChapters(sections: List<Section>, toc: List<JSONObject>, metadata: JSONObject, hash: String): List<ChapterSpec> {
        data class Entry(val index: Int, val fragment: String, val label: String, val legacyIdSafe: Boolean = true)
        fun decode(value: String): String = try { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }
            catch (_: Exception) { throw SourceProblem(FailureKind.PROTOCOL, "EPUB 目录链接编码无效", diagnostic = "BH_EPUB_HREF") }
        fun target(item: JSONObject): Entry {
            val href = item.optString("href"); val fragment = decode(href.substringAfter('#', ""))
            val path = href.substringBefore('#').substringBefore('?'); val decoded = decode(path)
            val link = try { URI(path) } catch (_: Exception) { protocol("EPUB 目录链接无效") }
            if ((link.isAbsolute && (link.scheme != client.origin.uri.scheme || link.rawAuthority != client.origin.uri.rawAuthority)) ||
                (link.rawAuthority != null && !link.isAbsolute))
                protocol("EPUB 目录指向当前服务以外的位置")
            val legacy = item.optString("resourceId")
            val linked = if (path.isEmpty()) legacy else Regex("/resources/([^/?#]+)").find(path)?.groupValues?.get(1)?.let(::decode)
                ?: metadata.optJSONObject("hrefs")?.optString(decoded).orEmpty()
            // A resolvable href is authoritative, as in the supplied BookHub client. Never request it directly.
            val byHref = sections.indices.filter { decoded.isNotEmpty() && sections[it].href == decoded }
            var candidates = if (linked.isNotEmpty()) sections.indices.filter { sections[it].originalId == linked }.ifEmpty {
                sections.indices.filter { sections[it].resourceId == linked }
            } else byHref
            if (byHref.isNotEmpty()) candidates = candidates.filter { it in byHref }
            if (candidates.isEmpty()) throw SourceProblem(FailureKind.PROTOCOL, "EPUB 目录无法对应正文资源", diagnostic = "BH_EPUB_TARGET")
            val anchored = if (fragment.isEmpty()) emptyList() else candidates.filter { fragment in sections[it].anchors }
            fun pathSteps(value: String): List<Int>? {
                if (!Regex("^(/\\d+){1,64}$").matches(value)) return null
                val steps = value.split('/').drop(1).map { it.toIntOrNull() ?: return null }
                return steps
            }
            fun compare(a: List<Int>, b: List<Int>): Int {
                for(i in 0 until minOf(a.size,b.size)) if(a[i] != b[i]) return a[i].compareTo(b[i])
                return a.size.compareTo(b.size)
            }
            val targetPath = metadata.optJSONObject("tocPaths")?.optString("$linked#$fragment").orEmpty()
            val steps = pathSteps(targetPath)
            val pathIndex = if (steps != null && candidates.map { sections[it].originalIndex }.distinct().singleOrNull() != null) {
                val paths = candidates.mapNotNull { i -> pathSteps(sections[i].startPath)?.let { i to it } }
                if(paths.size != candidates.size) null else {
                    val preceding = paths.filter { compare(it.second,steps) <= 0 }
                    val last = preceding.maxWithOrNull { a,b -> compare(a.second,b.second) }
                    last?.first?.takeIf { preceding.count { p -> compare(p.second,last.second) == 0 } == 1 }
                }
            } else null
            val index = when {
                anchored.size == 1 -> anchored.single()
                pathIndex != null && (anchored.isEmpty() || pathIndex in anchored) -> pathIndex
                anchored.size > 1 -> throw SourceProblem(FailureKind.PROTOCOL, "EPUB 锚点对应多个分段，无法安全定位", diagnostic = "BH_EPUB_AMBIGUOUS_TARGET")
                candidates.size == 1 -> candidates.single()
                fragment.isEmpty() && candidates.map { sections[it].originalIndex }.distinct().singleOrNull() != null &&
                    candidates.zipWithNext().all { (a,b) -> b == a+1 } -> candidates.first()
                else -> throw SourceProblem(FailureKind.PROTOCOL, "EPUB 目录对应多个阅读位置，缺少唯一分段信息", diagnostic = "BH_EPUB_AMBIGUOUS_TARGET")
            }
            val oldCandidates = sections.indices.filter { sections[it].resourceId == legacy || sections[it].originalId == legacy }
            val oldIndex = if (fragment.isEmpty()) oldCandidates.firstOrNull() else oldCandidates.firstOrNull { fragment in sections[it].anchors } ?: oldCandidates.firstOrNull()
            return Entry(index,fragment,item.required("label"),legacy.isEmpty() || oldIndex == index)
        }
        val entries = if (toc.isEmpty()) sections.mapIndexed { i,s -> Entry(i,"",s.label.ifBlank { "第 ${i+1} 章" }) } else toc.map(::target)
        // Navigation is never sorted or deduplicated. Its order is independent of physical slice boundaries.
        val all = if (entries.minOf { it.index } > 0) listOf(Entry(0,"","书前内容")) + entries else entries
        val used = mutableMapOf<String,Int>()
        return all.map { item ->
            val base = "epub:$hash:" + digest("${item.index}#${item.fragment}").take(16)
            val count = used.getOrDefault(base,0); used[base] = count+1
            val id = if (!item.legacyIdSafe) "epub2:$hash:" + digest("${item.index}#${item.fragment}#${item.label}#$count").take(16)
                else if (count == 0) base else base + ":nav:" + digest("${item.label}#$count").take(12)
            ChapterSpec(id,item.label,item.index,if(toc.isEmpty()) item.index else sections.lastIndex,
                startFragment=item.fragment,navigation=toc.isNotEmpty())
        }
    }

    private suspend fun sectionText(bookId: String, manifest: Manifest, index: Int): String {
        val section = manifest.sections[index]; val key = "$bookId|${manifest.version}|${section.resourceId}"
        texts[key]?.let { return it }
        val cache=offline
        val saved=cache?.let{disk{it.resource(bookId,manifest.version,section.resourceId,4L*1024*1024)}}
        if(saved!=null) {
            val value=client.textBytes(saved)
            val restored=if(manifest.format=="EPUB" && cache.portable(bookId,manifest.version,section.resourceId)) ReadingPackage.restoreLinks(value,manifest.resources) else value
            if(restored.length<=250000 && manifest.cacheEpoch?.let{cache.isCurrent(bookId,it)}==true)texts[key]=restored
            return restored
        }
        if(cache!=null && (!connection.online || manifest.offlineOnly))offlineMissing()
        val epoch=manifest.cacheEpoch
        if(manifest.format == "EPUB") coroutineContext[InitialReadBudget]?.charge()
        return client.resource(section.url, manifest.format).also {
            if(manifest.format=="EPUB")EpubTextDocument(it) // Reject a returned login page before saving it.
            if(cache!=null)cacheWrite{cache.put(bookId,manifest.version,section.resourceId,it.toByteArray(),epoch!!)}
            if (it.length <= 250000 && (cache==null || epoch!=null && cache.isCurrent(bookId,epoch))) texts[key] = it
        }
    }
    private suspend fun document(bookId: String, manifest: Manifest, index: Int): EpubTextDocument {
        val html = sectionText(bookId,manifest,index)
        return EpubTextDocument(html)
    }
    private suspend fun navigationDocument(bookId: String, expected: Manifest, index: Int): EpubTextDocument {
        var current = manifests[bookId] ?: expected
        if(current.version != expected.version || current.chapters != expected.chapters) throw SourceProblem(FailureKind.VERSION,"目录已变化，原进度已保留")
        if(connection.online && !current.offlineOnly && current.expiresAt.isBefore(Instant.now().plusSeconds(20))) current = renew(bookId,current)
        return try { document(bookId,current,index) } catch(e: SourceProblem) {
            if(e.kind !in setOf(FailureKind.AUTH,FailureKind.FORBIDDEN) || coroutineContext[InitialReadBudget]?.canRequest == false) throw e
            document(bookId,renew(bookId,current),index)
        }
    }

    private fun readingGroup(manifest: Manifest, spec: ChapterSpec): Int {
        val section = manifest.sections[spec.first]
        if(section.originalIndex == null) return spec.first
        return manifest.sections.indexOfFirst { it.originalIndex == section.originalIndex && it.originalId == section.originalId && it.resourceId == section.resourceId }
    }
    /** Original client scrolls only when an anchor exists. Whole-resource fallback is safe only
     * for one distinct logical target on a unique physical resource, never ambiguous fragments. */
    private fun fragment(manifest: Manifest, spec: ChapterSpec, doc: EpubTextDocument): String {
        if(!doc.hasNoAnchor(spec.startFragment)) return spec.startFragment
        val section=manifest.sections[spec.first]
        val physical=manifest.sections.indices.filter { manifest.sections[it].resourceId==section.resourceId }
        val related=manifest.chapters.filter { it.first in physical }.map { it.startFragment }.distinct()
        val original=manifest.sections.filter { it.originalId==section.originalId && it.originalIndex==section.originalIndex }
        return if(physical.size==1 && related.size==1 && original.size==1) "" else spec.startFragment
    }
    private fun fragments(manifest: Manifest, group: List<ChapterSpec>, doc: EpubTextDocument)=group.map { fragment(manifest,it,doc) }
    private suspend fun orderedGroup(bookId: String, manifest: Manifest, group: List<ChapterSpec>): List<ChapterSpec> {
        val doc = navigationDocument(bookId,manifest,group.first().first)
        val fragments = fragments(manifest,group,doc)
        return group.sortedBy { doc.readingEvent(fragment(manifest,it,doc),fragments) }.distinctBy { doc.readingEvent(fragment(manifest,it,doc),fragments) }
    }
    override suspend fun readingNeighbor(bookId: String, chapterId: String, direction: Int, chapters: List<ChapterRef>): String? {
        checkOfflineIdentity()
        val manifest = manifests[bookId] ?: fetchManifest(bookId).also { manifests[bookId] = it }
        if(manifest.format != "EPUB") return super.readingNeighbor(bookId,chapterId,direction,chapters)
        val current = manifest.chapters.firstOrNull { it.id == chapterId } ?: throw SourceProblem(FailureKind.VERSION,"目录已变化，原进度已保留")
        val groups = manifest.chapters.groupBy { readingGroup(manifest,it) }.toSortedMap()
        val key = readingGroup(manifest,current); val group = groups.getValue(key)
        val doc = navigationDocument(bookId,manifest,current.first); val fragments = fragments(manifest,group,doc)
        val at = doc.readingEvent(fragment(manifest,current,doc),fragments)
        val ordered = group.sortedBy { doc.readingEvent(fragment(manifest,it,doc),fragments) }.distinctBy { doc.readingEvent(fragment(manifest,it,doc),fragments) }
        val within = if(direction > 0) ordered.firstOrNull { doc.readingEvent(fragment(manifest,it,doc),fragments) > at }
            else ordered.lastOrNull { doc.readingEvent(fragment(manifest,it,doc),fragments) < at }
        if(within != null) return within.id
        val adjacent = if(direction > 0) groups.keys.firstOrNull { it > key } else groups.keys.lastOrNull { it < key }
        return adjacent?.let { val next = orderedGroup(bookId,manifest,groups.getValue(it)); if(direction > 0) next.first().id else next.last().id }
    }

    override suspend fun initialReading(bookId: String, chapters: List<ChapterRef>): InitialReading {
        checkOfflineIdentity()
        return withContext(InitialReadBudget()) { initialReadingWithinBudget(bookId,chapters) }
    }

    private suspend fun initialReadingWithinBudget(bookId: String, chapters: List<ChapterRef>): InitialReading {
        var manifest = manifests[bookId] ?: fetchManifest(bookId).also { manifests[bookId] = it }
        if (connection.online && !manifest.offlineOnly && manifest.expiresAt.isBefore(Instant.now().plusSeconds(20))) manifest = renew(bookId,manifest)
        if (manifest.format != "EPUB") return super.initialReading(bookId,chapters)
        var skipped = 0
        // At most five leading textless targets. Authentication, transport and mapping failures propagate.
        for (group in manifest.chapters.groupBy { readingGroup(manifest,it) }.toSortedMap().values) {
            if (skipped >= 5) throw SourceProblem(FailureKind.UNSUPPORTED,"前 5 个阅读项均未提取到文字，请从目录选择正文；尚未保存阅读进度",diagnostic="BH_EPUB_START_LIMIT")
            val doc = navigationDocument(bookId,manifest,group.first().first)
            val fragments = fragments(manifest,group,doc)
            val ordered = group.sortedBy { doc.readingEvent(fragment(manifest,it,doc),fragments) }.distinctBy { doc.readingEvent(fragment(manifest,it,doc),fragments) }
            for (spec in ordered) {
                if (skipped >= 5) throw SourceProblem(FailureKind.UNSUPPORTED, "前 5 个阅读项均未提取到文字，请从目录选择正文；尚未保存阅读进度", diagnostic="BH_EPUB_START_LIMIT")
                try {
                    val chapter = chapter(bookId,spec.id)
                    return InitialReading(chapter,if(skipped == 0) null else "已略过 $skipped 个当前无法显示的无文字前置项，进入首个可读页面；可从目录查看。")
                } catch(e: SourceProblem) {
                    if(e.diagnostic !in setOf("BH_EPUB_IMAGE_ONLY","BH_EPUB_NO_TEXT")) throw e
                    skipped++
                }
            }
        }
        throw SourceProblem(FailureKind.UNSUPPORTED,"已检查全部目录项，未提取到可读文字；尚未保存阅读进度",diagnostic="BH_EPUB_NO_READABLE_START")
    }

    private suspend fun readChapter(bookId: String, chapterId: String, manifest: Manifest): Chapter {
        val spec = manifest.chapters.firstOrNull { it.id == chapterId } ?: throw SourceProblem(FailureKind.VERSION, "目录版本已变化，请返回目录重新打开")
        val parts = mutableListOf<String>(); var hasImages = false
        data class PendingGraphic(val target: EpubGraphicTarget, val paragraph: Int, val within: Int)
        val pendingGraphics=mutableListOf<PendingGraphic>()
        val docs = mutableMapOf<Int,EpubTextDocument>()
        suspend fun doc(index: Int) = docs[index] ?: document(bookId,manifest,index).also { docs[index] = it }
        var last = spec.last; var endFragment = spec.endFragment; var startFragment=spec.startFragment
        if (manifest.format == "EPUB" && spec.navigation) {
            val firstDoc = doc(spec.first)
            val startSection = manifest.sections[spec.first]
            val same = manifest.chapters.filter {
                val candidate = manifest.sections[it.first]
                it.first == spec.first || (startSection.originalIndex != null && candidate.originalIndex == startSection.originalIndex &&
                    candidate.originalId == startSection.originalId && candidate.resourceId == startSection.resourceId)
            }
            startFragment=fragment(manifest,spec,firstDoc)
            val nextFragment = firstDoc.nextFragment(startFragment,fragments(manifest,same,firstDoc))
            if (nextFragment != null) { last = spec.first; endFragment = nextFragment }
            else {
                val nextIndex = manifest.chapters.map { it.first }.filter { it > spec.first }.minOrNull()
                if(nextIndex != null) {
                    val nextDoc = doc(nextIndex)
                    val end = manifest.chapters.filter { it.first == nextIndex }.minBy { nextDoc.point(fragment(manifest,it,nextDoc)).event }
                    val effectiveEnd=fragment(manifest,end,nextDoc)
                    last = if (effectiveEnd.isEmpty()) nextIndex-1 else nextIndex; endFragment = effectiveEnd
                }
            }
            if(last-spec.first >= 64) throw SourceProblem(FailureKind.UNSUPPORTED,"EPUB 单章分段超过本版限制，原进度已保留",diagnostic="BH_EPUB_RANGE_LIMIT")
        }
        var aggregate = 0
        for (index in spec.first..last) {
            val section = manifest.sections[index]
            val text = if(manifest.format == "TXT") sectionText(bookId,manifest,index) else ""
            val plain = if (manifest.format == "TXT") {
                val count = text.codePointCount(0, text.length)
                if (count != section.end!! - section.start!!) protocol("TXT 正文字符数与清单不一致，旧进度未变")
                val begin = maxOf(spec.startPoint!!, section.start) - section.start
                val end = minOf(spec.endPoint!!, section.end) - section.start
                text.substring(text.offsetByCodePoints(0, begin), text.offsetByCodePoints(0, end))
            } else {
                // Retain only the prefetched start/end documents, never every DOM in a long chapter.
                val currentDoc = docs.remove(index) ?: document(bookId,manifest,index)
                val slice = currentDoc.slice(if(index == spec.first) startFragment else "", if(index == last) endFragment else "")
                hasImages = hasImages || slice.hasImages
                for(graphic in slice.graphics) {
                    val prefix=slice.text.take(graphic.offset).replace("\r\n","\n").replace('\r','\n')
                    val prefixParts=prefix.split('\n').map { it.trim() }.filter(String::isNotEmpty)
                    val partial=prefix.substringAfterLast('\n').isNotBlank()
                    pendingGraphics += PendingGraphic(graphic,parts.size+prefixParts.size-(if(partial) 1 else 0),
                        if(partial) prefix.substringAfterLast('\n').trimStart().length else 0)
                }
                slice.text
            }
            aggregate += plain.length
            if (aggregate > 100000) protocol("本章过长，本版暂不能排版")
            parts += plain.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        }
        val matchingHeading = parts.firstOrNull()?.filterNot { it.isWhitespace() } == spec.label.filterNot { it.isWhitespace() }
        val headingOnly = matchingHeading && parts.size == 1
        val removedHeading=matchingHeading && parts.size>1
        if (removedHeading) parts.removeAt(0)
        val legacyText=Chapter(spec.id,spec.label,parts,headingOnly).text
        val graphics=mutableListOf<ChapterGraphic>();var pixels=0L;var unmapped=false
        for((ordinal,pending) in pendingGraphics.withIndex()) {
            val uri=runCatching { URI(pending.target.source) }.getOrNull()
            val safe=uri!=null && uri.rawAuthority==null && !uri.isAbsolute && uri.rawFragment==null
            val matches=if(safe) manifest.images.filter { it.url.substringBefore('?')==pending.target.source.substringBefore('?') } else emptyList()
            if(matches.isEmpty()) { unmapped=true;continue }
            if(matches.size!=1) throw SourceProblem(FailureKind.PROTOCOL,"图片对应多个资源，原进度已保留",diagnostic="BH_IMAGE_TARGET")
            if(graphics.size>=8) throw SourceProblem(FailureKind.UNSUPPORTED,"本章图片超过 8 页限制，原进度已保留",diagnostic="BH_IMAGE_LIMIT")
            val image=matches.single();val cache=offline
            val saved=cache?.let{disk{it.resource(bookId,manifest.version,image.id,8L*1024*1024)}}
            if(saved==null && cache!=null && (!connection.online || manifest.offlineOnly))offlineMissing()
            val epoch=manifest.cacheEpoch
            val bytes=saved?:client.raster(image.url)
            val bitmap=SafeRaster.decode(bytes)
            if(saved==null && cache!=null)cacheWrite{cache.put(bookId,manifest.version,image.id,bytes,epoch!!)}
            pixels+=bitmap.width.toLong()*bitmap.height
            if(pixels>8_000_000) throw SourceProblem(FailureKind.UNSUPPORTED,"本章图片尺寸超过限制，原进度已保留",diagnostic="BH_IMAGE_LIMIT")
            val paragraph=(pending.paragraph-(if(removedHeading) 1 else 0)).coerceAtLeast(0)
            val offset=if(parts.isEmpty()) graphics.size else if(paragraph==0 && pending.within==0) 0 else {
                val start=if(headingOnly) 0 else spec.label.length+2
                (start+parts.take(paragraph).sumOf { it.length+(if(headingOnly) 0 else 2)+2 }+
                    (if(pending.within>0 && !headingOnly) 2 else 0)+pending.within).coerceIn(0,legacyText.length)
            }
            graphics += ChapterGraphic(digest("${spec.id}|${image.id}|$ordinal").take(32),offset,bitmap,pending.target.description.take(120))
        }
        if (parts.isEmpty() && graphics.isEmpty() && hasImages) throw SourceProblem(FailureKind.UNSUPPORTED,"本页含图片或图形，但未找到可安全显示的清单内位图资源",diagnostic="BH_EPUB_IMAGE_ONLY")
        if (parts.isEmpty() && graphics.isEmpty()) throw SourceProblem(FailureKind.EMPTY, "本章没有提取到可读文字，旧进度已保留", diagnostic = "BH_${manifest.format}_NO_TEXT")
        // A verified independent-resource fallback is a successful read, not an actionable notice.
        val notice=if(unmapped && graphics.isNotEmpty()) "另有未获清单授权的图片，未加载外部链接。" else null
        return Chapter(spec.id, spec.label, parts, headingOnly = headingOnly,graphics=graphics,navigationNotice=notice)
    }
}
