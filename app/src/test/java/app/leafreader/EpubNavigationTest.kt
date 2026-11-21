package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Independent BookHub Access/stream fixtures, not OPF spine or real book responses. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class EpubNavigationTest {
    private val app:Application=ApplicationProvider.getApplicationContext()
    private class Wire : WireTransport {
        var manifest=BookHubFixtures.epubManifest()
        val pages=mutableMapOf("a" to BookHubFixtures.epubText)
        var failure:String?=null;var failAt:String?=null;var status=200;var bodyCalls=0
        val requested=mutableListOf<String>()
        override suspend fun execute(uri:java.net.URI,method:String,headers:Map<String,String>,body:ByteArray?,maxBytes:Int):WireResponse {
            val payload=when {
                uri.path.endsWith("/bookshelf")->BookHubFixtures.envelope(BookHubFixtures.page())
                uri.path.endsWith("/access")->BookHubFixtures.envelope(manifest)
                else->{
                    bodyCalls++;val id=uri.path.substringAfterLast('/');requested+=id
                    assertNull(headers["Authorization"])
                    if(id==failAt)throw SourceProblem(FailureKind.AUTH,"fixture denied")
                    if(failure!=null)throw SourceProblem(FailureKind.valueOf(failure!!),"fixture failure")
                    return WireResponse(status,pages.getValue(id).toByteArray(),"application/xhtml+xml")
                }
            }
            return WireResponse(200,payload.toByteArray())
        }
        fun source():BookHubSource {
            val client=BookHubClient(ServiceOrigin("https://nav.fixture.invalid"),this)
            client.restore(SavedSession(client.origin.value,"fixture-account","fixture-device","fixture-access","fixture-refresh"))
            return BookHubSource(client)
        }
        fun shape(spine:List<String>,toc:List<JSONObject>,segments:List<JSONObject> = emptyList()) {
            manifest.put("spine",JSONArray(spine)).put("resources",JSONArray().apply{spine.distinct().forEach{put(BookHubFixtures.resource(it).put("mediaType","application/xhtml+xml"))}})
                .put("toc",JSONArray(toc)).put("metadata",JSONObject().apply{if(segments.isNotEmpty())put("readingSegments",JSONArray(segments))})
        }
    }
    private fun toc(id:String,fragment:String="",label:String=id)=JSONObject().put("resourceId",id).put("label",label).put("href","/api/v1/books/public%3A1/resources/$id"+if(fragment.isEmpty())"" else "#$fragment")
    private fun segment(id:String,original:String,index:Int,href:String,anchors:List<String>)=JSONObject().put("resourceId",id).put("originalResourceId",original)
        .put("originalSpineIndex",index).put("href",href).put("label",original).put("weight",100).put("startPath","/4/2").put("anchors",JSONArray(anchors))
    private suspend fun failure(code:String,action:suspend()->Unit){try{action();fail("Expected $code")}catch(e:SourceProblem){assertEquals(code,e.diagnostic)}}
    @Test fun nonMonotonicNavigationKeepsOrderButSlicesByPhysicalAnchorAndResourcePositions()=runBlocking {
        val w=Wire();w.pages["b"]="<html><body><p>末尾资源正文。</p></body></html>"
        w.shape(listOf("a","b"),listOf(toc("a","two","先列后半"),toc("a","one","再列前半"),toc("b",label="最后")))
        val s=w.source();val refs=s.chapters("public:1");assertEquals(listOf("先列后半","再列前半","最后"),refs.map{it.title})
        val later=s.chapter("public:1",refs[0].id);val earlier=s.chapter("public:1",refs[1].id)
        assertTrue(later.text.contains("到达对岸"));assertFalse(later.text.contains("走向江边"));assertFalse(later.text.contains("末尾资源正文"))
        assertTrue(earlier.text.contains("走向江边"));assertFalse(earlier.text.contains("到达对岸"))
        assertTrue(s.chapter("public:1",refs[2].id).text.contains("末尾资源正文"))
        assertEquals(refs[1].id,s.initialReading("public:1",refs).chapter.id)
        assertEquals(refs[0].id,s.readingNeighbor("public:1",refs[1].id,1,refs))
        assertEquals(refs[2].id,s.readingNeighbor("public:1",refs[0].id,1,refs))
        assertEquals(refs[0].id,s.readingNeighbor("public:1",refs[2].id,-1,refs))
        assertNull(s.readingNeighbor("public:1",refs[1].id,-1,refs))
    }
    @Test fun backwardResourceNavigationIsNotSortedOrStretchedAcrossUnrelatedText()=runBlocking {
        val w=Wire();w.pages["a"]="<html><body><p>前资源正文。</p></body></html>";w.pages["b"]="<html><body><p>后资源正文。</p></body></html>"
        w.shape(listOf("a","b"),listOf(toc("b"),toc("a")))
        val s=w.source();val refs=s.chapters("public:1");assertEquals(listOf("b","a"),refs.map{it.title})
        assertTrue(s.chapter("public:1",refs[0].id).text.contains("后资源正文"));assertFalse(s.chapter("public:1",refs[1].id).text.contains("后资源正文"))
    }
    @Test fun wholeResourceParentCanAliasTheFirstHeadingWithoutManufacturingAnEmptyChapter()=runBlocking {
        val w=Wire();w.shape(listOf("a"),listOf(toc("a",label="全资源入口"),toc("a","one","第一节"),toc("a","two","第二节")))
        val s=w.source();val refs=s.chapters("public:1");assertEquals(3,refs.size)
        val parent=s.chapter("public:1",refs[0].id);assertTrue(parent.text.contains("走向江边"));assertFalse(parent.text.contains("到达对岸"))
        assertTrue(s.chapter("public:1",refs[1].id).text.contains("走向江边"))
        assertEquals(refs[2].id,s.readingNeighbor("public:1",refs[0].id,1,refs))
        assertEquals(refs[0].id,s.readingNeighbor("public:1",refs[2].id,-1,refs))
    }
    @Test fun aliasesPreserveEveryNavigationRowAndFirstLegacyIdAndDoNotCreateEmptySlices()=runBlocking {
        val w=Wire();w.shape(listOf("a"),listOf(toc("a","one","原标签"),toc("a","one","别处入口"),toc("a","two","第二节")))
        val s=w.source();val refs=s.chapters("public:1");assertEquals(3,refs.size);assertEquals(3,refs.map{it.id}.distinct().size)
        assertEquals("epub:"+digest("epub-fixture-v1").take(16)+":"+digest("0#one").take(16),refs.first().id)
        for(ref in refs.take(2)){val c=s.chapter("public:1",ref.id);assertTrue(c.text.contains("走向江边"));assertFalse(c.text.contains("到达对岸"))}
        assertEquals(refs,s.chapters("public:1"))
    }
    @Test fun repeatedStreamResourceIsSupportedWhenOriginalHrefAndOccurrenceMakeTargetsUnique()=runBlocking {
        val w=Wire();w.pages["shared"]="<html><body><p>复用的物理资源内容。</p></body></html>"
        w.shape(listOf("shared","shared"),listOf(JSONObject().put("resourceId","first").put("href","first.xhtml").put("label","第一次"),JSONObject().put("resourceId","second").put("href","second.xhtml").put("label","第二次")),
            listOf(segment("shared","first",0,"first.xhtml",emptyList()),segment("shared","second",1,"second.xhtml",emptyList())))
        w.manifest.getJSONObject("metadata").put("hrefs",JSONObject().put("first.xhtml","first").put("second.xhtml","second"))
        val s=w.source();val refs=s.chapters("public:1");assertEquals(2,refs.size)
        refs.forEach{val text=s.chapter("public:1",it.id).text;assertEquals(1,Regex("复用的物理资源内容").findAll(text).count())}
        assertEquals(1,w.bodyCalls) // Byte cache reuses identity; physical occurrences are not erased.
    }
    @Test fun repeatedPhysicalResourcesWithoutTocKeepAllOccurrences()=runBlocking {
        val w=Wire();w.shape(listOf("a","a"),emptyList());val s=w.source();val refs=s.chapters("public:1")
        assertEquals(2,refs.size);assertNotEquals(refs[0].id,refs[1].id);refs.forEach{assertTrue(s.chapter("public:1",it.id).text.contains("走向江边"))}
    }
    @Test fun sameXhtmlReusedByDifferentFragmentsOfOneOriginalOccurrenceIsSlicedOnce()=runBlocking {
        val w=Wire();w.shape(listOf("a","a"),listOf(toc("original","one","第一节"),toc("original","two","第二节")),
            listOf(segment("a","original",0,"same.xhtml",listOf("one")),segment("a","original",0,"same.xhtml",listOf("two"))))
        val s=w.source();val refs=s.chapters("public:1");val a=s.chapter("public:1",refs[0].id);val b=s.chapter("public:1",refs[1].id)
        assertTrue(a.text.contains("走向江边"));assertFalse(a.text.contains("到达对岸"))
        assertTrue(b.text.contains("到达对岸"));assertFalse(b.text.contains("走向江边"));assertEquals(1,w.bodyCalls)
        val bad=Wire();bad.pages["b"]="<p>中间分段</p>";bad.shape(listOf("a","b","a"),listOf(toc("original","one"),toc("original","two")),
            listOf(segment("a","original",0,"same.xhtml",listOf("one")),segment("b","original",0,"same.xhtml",emptyList()),segment("a","original",0,"same.xhtml",listOf("two"))))
        failure("BH_EPUB_AMBIGUOUS_TARGET"){bad.source().chapters("public:1")}
    }
    @Test fun streamDocumentPathCanDisambiguateAnchorsWithinOneOriginalSpineOccurrence()=runBlocking {
        val w=Wire();w.pages["s0"]="<html><body><p id='one'>较早分段文字。</p></body></html>";w.pages["s1"]="<html><body><p id='one'>路径指定分段文字。</p></body></html>"
        w.shape(listOf("s0","s1"),listOf(toc("original","one")),listOf(segment("s0","original",0,"same.xhtml",listOf("one")),segment("s1","original",0,"same.xhtml",listOf("one")).put("startPath","/4/10")))
        w.manifest.getJSONObject("metadata").put("tocPaths",JSONObject().put("original#one","/4/10/2"))
        val s=w.source();val refs=s.chapters("public:1");val c=s.chapter("public:1",refs.last().id)
        assertTrue(c.text.contains("路径指定分段文字"));assertFalse(c.text.contains("较早分段文字"));assertTrue(refs.last().id.startsWith("epub2:"))
        w.manifest.getJSONObject("metadata").getJSONArray("readingSegments").getJSONObject(1).put("originalSpineIndex",1)
        failure("BH_EPUB_AMBIGUOUS_TARGET"){w.source().chapters("public:1")}
    }
    @Test fun ambiguousRepeatedTargetAndDuplicateDefinitionsOrBadSegmentAlignmentStillFail()=runBlocking {
        val ambiguous=Wire();ambiguous.shape(listOf("a","a"),listOf(toc("a","one")))
        failure("BH_EPUB_AMBIGUOUS_TARGET"){ambiguous.source().chapters("public:1")};assertEquals(0,ambiguous.bodyCalls)
        val definitions=Wire();definitions.manifest.getJSONArray("resources").put(BookHubFixtures.resource("a"))
        failure("BH_PROTOCOL"){definitions.source().chapters("public:1")}
        val misaligned=Wire();misaligned.shape(listOf("a"),listOf(toc("a")),listOf(segment("wrong","a",0,"a.xhtml",emptyList())))
        failure("BH_PROTOCOL"){misaligned.source().chapters("public:1")}
    }
    @Test fun resolvedHrefTakesPriorityAndUnsafeOldMappingIdIsNotSilentlyReused()=runBlocking {
        val w=Wire();w.pages["b"]="<html><body><p id='one'>正确链接目标。</p></body></html>"
        w.shape(listOf("a","b"),listOf(toc("b","one").put("resourceId","a")))
        val s=w.source();val refs=s.chapters("public:1");val target=refs.last()
        assertTrue(target.id.startsWith("epub2:"));assertTrue(s.chapter("public:1",target.id).text.contains("正确链接目标"))
    }
    @Test fun fragmentDecodingMissingAndDuplicateAnchorsAndReversedDirectRangesAreExplicit()=runBlocking {
        val w=Wire();w.pages["a"]="<html><body><h1 id='章+一'>标题</h1><p>准确锚点正文。</p></body></html>";w.shape(listOf("a"),listOf(toc("a","%E7%AB%A0%2B%E4%B8%80")))
        val s=w.source();val ref=s.chapters("public:1").single();assertTrue(s.chapter("public:1",ref.id).text.contains("准确锚点正文"))
        failure("BH_EPUB_ANCHOR"){EpubPlainText.extract("<p id='x'>甲</p><p id='x'>乙</p>","x")}
        failure("BH_EPUB_ANCHOR"){EpubPlainText.extract("<p>甲</p>","missing")}
        failure("BH_EPUB_RANGE"){EpubPlainText.extract(BookHubFixtures.epubText,"two","one")}
        val bad=Wire();bad.shape(listOf("a"),listOf(toc("a","%GG")));failure("BH_EPUB_HREF"){bad.source().chapters("public:1")}
    }
    @Test fun firstAutomaticEntrySkipsOnlyLeadingKnownTextlessItemsAndSavesActualReadableTarget()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val w=Wire();w.pages["cover"]="<html><body><img src='/not-loaded.png'/></body></html>";w.shape(listOf("cover","a"),listOf(toc("a","one","正文一"),toc("a","two","正文二")))
            val vm=ReaderViewModel(app,w.source(),sessionVault=MemorySessionVault());advanceUntilIdle();val book=vm.ui.value.shelfBooks.single()
            val refs=vm.chapterList(book);vm.open(book,refs.first().id);advanceUntilIdle();assertTrue(vm.ui.value.error!!.contains("BH_EPUB_IMAGE_ONLY"));assertNull(vm.store.position(book.id))
            vm.open(book);advanceUntilIdle();assertNull(vm.ui.value.error);assertEquals(refs[1].id,vm.ui.value.chapter!!.id);assertTrue(vm.ui.value.notice!!.contains("略过 1"))
            assertEquals(refs[1].id,vm.store.position(book.id)!!.chapterId)
            vm.position(5);vm.backToShelf();vm.open(book);advanceUntilIdle();assertEquals(5,vm.ui.value.offset);assertNull(vm.ui.value.notice)
        }finally{Dispatchers.resetMain()}
    }
    @Test fun savedImageOrInvalidProgressIsNeverSkippedOrOverwritten()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val w=Wire();w.pages["cover"]="<html><body><svg><text>不能当小说文本</text></svg></body></html>";w.shape(listOf("cover","a"),listOf(toc("a","one")))
            val vm=ReaderViewModel(app,w.source(),sessionVault=MemorySessionVault());advanceUntilIdle();val b=vm.ui.value.shelfBooks.single();val refs=vm.chapterList(b)
            vm.store.save(b.id,refs.first().id,0);val saved=vm.store.position(b.id);vm.open(b);advanceUntilIdle()
            assertTrue(vm.ui.value.error!!.contains("BH_EPUB_IMAGE_ONLY"));assertEquals(saved,vm.store.position(b.id))
            vm.store.save(b.id,"invalid-old-chapter",12);val invalid=vm.store.position(b.id);vm.open(b);advanceUntilIdle();assertTrue(vm.ui.value.error!!.contains("已失效"));assertEquals(invalid,vm.store.position(b.id))
        }finally{Dispatchers.resetMain()}
    }
    @Test fun automaticProbeStopsAtFiveAndAllImageBooksRemainExplicitFailures()=runBlocking {
        for(size in listOf(2,7)) {
            val w=Wire();val ids=(0 until size).map{"image$it"};w.shape(ids,emptyList());ids.forEach{w.pages[it]="<html><body><img src='/not-loaded.png'/></body></html>"}
            val s=w.source();val refs=s.chapters("public:1");failure(if(size==2)"BH_EPUB_NO_READABLE_START" else "BH_EPUB_START_LIMIT"){s.initialReading("public:1",refs)}
            assertTrue(w.requested.distinct().size<=5)
        }
    }
    @Test fun networkAuthorizationAndProtocolFailuresAreNeverTreatedAsSkippablePages()=runBlocking {
        for(kind in listOf("NETWORK","AUTH","PROTOCOL","FORBIDDEN")) {
            val w=Wire();w.failure=kind;val s=w.source();val refs=s.chapters("public:1")
            failure("BH_$kind"){s.initialReading("public:1",refs)};assertTrue(w.requested.all{it=="a"})
        }
    }
    @Test fun automaticFrontMatterProbeHasAnActualResourceRequestBudget()=runBlocking {
        val w=Wire();val ids=(0..13).map{"image$it"}+"body"
        w.shape(ids,listOf(toc("body")));ids.forEach{w.pages[it]=if(it=="body")"<html><body><p>可读文字。</p></body></html>" else "<html><body><img src='/never'/></body></html>"}
        val s=w.source();val refs=s.chapters("public:1")
        failure("BH_EPUB_START_RESOURCE_LIMIT"){s.initialReading("public:1",refs)}
        assertTrue(w.bodyCalls<=12)
        val denied=Wire();denied.shape(ids,listOf(toc("body")));denied.pages.putAll(w.pages);denied.failAt="image10"
        val source=denied.source();val rows=source.chapters("public:1")
        failure("BH_AUTH"){source.initialReading("public:1",rows)};assertTrue(denied.bodyCalls<=12)
    }
    @Test fun viewModelCrossChapterPagingUsesPhysicalOrderWhileDirectoryStaysNonMonotonic()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val w=Wire();w.pages["b"]="<html><body><p>最后正文。</p></body></html>";w.shape(listOf("a","b"),listOf(toc("a","two","后半"),toc("a","one","前半"),toc("b",label="最后")))
            val vm=ReaderViewModel(app,w.source(),sessionVault=MemorySessionVault());advanceUntilIdle();val book=vm.ui.value.shelfBooks.single()
            vm.open(book);advanceUntilIdle();assertEquals("前半",vm.ui.value.chapter!!.title)
            val rows=vm.ui.value.chapters;assertEquals(listOf("后半","前半","最后"),rows.map{it.title})
            vm.boundary(1);advanceUntilIdle();assertEquals("后半",vm.ui.value.chapter!!.title)
            vm.boundary(1);advanceUntilIdle();assertEquals("最后",vm.ui.value.chapter!!.title)
            vm.boundary(-1);advanceUntilIdle();assertEquals("后半",vm.ui.value.chapter!!.title);assertTrue(vm.ui.value.offset>0)
            assertEquals(rows,vm.ui.value.chapters)
        }finally{Dispatchers.resetMain()}
    }
    @Test fun imageAtAnUnrelatedLaterAnchorDoesNotMisclassifyAnEmptyEarlierSlice()=runBlocking {
        val html="<html><body><span id='empty'></span><h1 id='body'>正文</h1><p>文字</p><div id='image'><img src='/never'/></div></body></html>"
        assertTrue(EpubPlainText.extract(html,"empty","body").isBlank())
        failure("BH_EPUB_IMAGE_ONLY"){EpubPlainText.extract(html,"image")}
    }
    @Test fun accessRenewalMayReplaceTicketsButCannotSilentlyRemapTheSameVersion()=runBlocking {
        val valid=Wire();valid.manifest.put("expiresAt","2000-01-01T00:00:00Z")
        val s=valid.source();val refs=s.chapters("public:1")
        valid.manifest.put("expiresAt","2099-01-01T00:00:00Z").getJSONArray("resources").getJSONObject(0).put("url","/api/v1/books/public%3A1/resources/a?ticket=renewed-fixture")
        assertTrue(s.chapter("public:1",refs.first().id).text.contains("走向江边"))
        val changed=Wire();changed.manifest.put("expiresAt","2000-01-01T00:00:00Z")
        val old=changed.source();val oldRefs=old.chapters("public:1")
        changed.manifest.put("expiresAt","2099-01-01T00:00:00Z").getJSONArray("toc").getJSONObject(0).put("label","changed")
        failure("BH_VERSION"){old.chapter("public:1",oldRefs.first().id)};assertEquals(0,changed.bodyCalls)
        val invalid=Wire();invalid.shape(listOf("a"),listOf(toc("a")),listOf(segment("a","a",0,"a.xhtml",emptyList()).put("originalSpineIndex",0.5)))
        failure("BH_PROTOCOL"){invalid.source().chapters("public:1")}
    }
}
