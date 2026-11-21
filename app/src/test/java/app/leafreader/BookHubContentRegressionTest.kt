package app.leafreader

import app.leafreader.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookHubContentRegressionTest {
    private class Wire : WireTransport {
        var manifest = BookHubFixtures.epubManifest()
        var text = BookHubFixtures.epubText
        var contentType = "application/xhtml+xml"
        var bytes: ByteArray? = null
        val sections = mutableMapOf<String, String>()
        override suspend fun execute(uri: java.net.URI, method: String, headers: Map<String, String>, body: ByteArray?, maxBytes: Int): WireResponse {
            if (uri.path.endsWith("/auth/login")) return WireResponse(200, BookHubFixtures.envelope(BookHubFixtures.session()).toByteArray())
            if (uri.path.endsWith("/access")) return WireResponse(200, BookHubFixtures.envelope(manifest).toByteArray())
            if (uri.path.endsWith("/bookshelf")) return WireResponse(200, BookHubFixtures.envelope(BookHubFixtures.page()).toByteArray())
            assertNull(headers["Authorization"])
            return WireResponse(200, bytes ?: (sections[uri.path.substringAfterLast('/')] ?: text).toByteArray(), contentType)
        }
    }
    private suspend fun source(wire: Wire): BookHubSource {
        val c = BookHubClient(ServiceOrigin("https://content.fixture.invalid"), wire); c.login("fixture", "fixture-password")
        return BookHubSource(c)
    }
    private suspend fun problem(code: String, wire: Wire) {
        val source = source(wire); val refs = source.chapters("public:1")
        try { source.chapter("public:1", refs.first().id); fail("Expected $code") }
        catch (e: SourceProblem) { assertEquals(code, e.diagnostic); assertFalse(e.message.orEmpty().contains("dont-echo-fixture-secret")) }
    }

    @Test fun firstEpubTocAnchorDoesNotInventAnEmptyChapterAndRealChapterIdsStayStable() = runBlocking {
        val s = source(Wire()); val refs = s.chapters("public:1")
        assertEquals(listOf("一、出发", "二、到达"), refs.map { it.title })
        val expected = "epub:" + digest("epub-fixture-v1").take(16) + ":" + digest("0#one").take(16)
        assertEquals(expected, refs.first().id)
        assertTrue(s.chapter("public:1", refs.first().id).text.contains("走向江边"))
    }

    @Test fun selfClosingXhtmlTitleKeepsTextWithoutExecutingAnything() = runBlocking {
        val wire = Wire().apply {
            text = "<?xml version='1.0'?><html xmlns='http://www.w3.org/1999/xhtml'><head><title/></head><body><h1 id='one'>一、出发</h1><p>自写正文完整存在。🌙</p><script>SECRET_SCRIPT</script><h1 id='two'>二、到达</h1><p>第二章文字。</p></body></html>"
        }
        val s = source(wire); val refs = s.chapters("public:1"); val c = s.chapter("public:1", refs.first().id)
        assertTrue(c.text.contains("自写正文完整存在。🌙")); assertFalse(c.text.contains("第二章文字")); assertFalse(c.text.contains("SECRET"))
    }

    @Test fun actualTitleOnlyPageIsReadableAndIsNotDeletedAsADuplicateHeading() = runBlocking {
        val wire = Wire().apply { text = "<html><body><h1 id='one'>一、出发</h1><h1 id='two'>二、到达</h1><p>正文。</p></body></html>" }
        val s = source(wire); val refs = s.chapters("public:1"); val c = s.chapter("public:1", refs.first().id)
        assertTrue(c.headingOnly); assertEquals("一、出发", c.text); assertEquals(listOf("一、出发"), c.paragraphs)
    }

    @Test fun realEmptyImageOnlyJsonLoginAndEncodingFailuresHaveDistinctSafeDiagnostics() = runBlocking {
        suspend fun single(text: String, type: String = "application/xhtml+xml", bytes: ByteArray? = null, code: String) {
            val wire = Wire().apply {
                manifest.put("toc", JSONArray().put(JSONObject().put("label", "测试章").put("resourceId", "a")))
                this.text = text; this.contentType = type; this.bytes = bytes
            }
            problem(code, wire)
        }
        single("<html><body> \n </body></html>", code = "BH_EPUB_NO_TEXT")
        single("<html><body><img src='/not-requested.png'/></body></html>", code = "BH_EPUB_IMAGE_ONLY")
        single(" ", code = "BH_RESOURCE_EMPTY")
        single("{\"code\":\"AUTH\",\"message\":\"dont-echo-fixture-secret\",\"data\":{}}", "application/json", code = "BH_RESOURCE_JSON")
        single("<html><body><form><input type='password'/></form></body></html>", code = "BH_RESOURCE_LOGIN_PAGE")
        single("", bytes = byteArrayOf(0xFF.toByte()), code = "BH_UTF8")
    }

    @Test fun txtHtmlAndBinaryResponsesAreNotMisreadAsEmptyChapters() = runBlocking {
        val html = Wire().apply { manifest = BookHubFixtures.txtManifest(); contentType = "text/html"; text = "<html>login</html>" }
        problem("BH_TXT_HTML", html)
        val binary = Wire().apply { contentType = "image/png"; bytes = byteArrayOf(0, 1, 2) }
        problem("BH_RESOURCE_BINARY", binary)
    }

    @Test fun productionShapedStreamingEpubResolvesOriginalIdsAnchorsAndCrossChunkText() = runBlocking {
        val wire = Wire(); val resources = JSONArray(); val segments = JSONArray()
        for (i in 0..2) {
            resources.put(BookHubFixtures.resource("s$i").put("mediaType", "application/xhtml+xml"))
            segments.put(JSONObject().put("resourceId", "s$i").put("originalResourceId", "original")
                .put("originalSpineIndex", 0).put("href", "text/chapter.xhtml").put("label", "章节")
                .put("weight", 100).put("startPath", "/4/${i * 2 + 2}")
                .put("anchors", JSONArray().apply { if (i == 0) put("one"); if (i == 2) put("two") }))
        }
        wire.manifest.put("spine", JSONArray().put("s0").put("s1").put("s2")).put("resources", resources)
            .put("toc", JSONArray().put(JSONObject().put("label", "甲章").put("resourceId", "original").put("href", "/api/v1/books/public%3A1/resources/original#one"))
                .put(JSONObject().put("label", "乙章").put("resourceId", "original").put("href", "/api/v1/books/public%3A1/resources/original#two")))
            .put("metadata", JSONObject().put("readingSegments", segments).put("hrefs", JSONObject().put("text/chapter.xhtml", "original")))
        wire.sections["s0"] = "<html><body><h1 id='one'>甲章</h1><p>甲开始。</p></body></html>"
        wire.sections["s1"] = "<html><body><p>甲中段。</p></body></html>"
        wire.sections["s2"] = "<html><body><p>甲末段。</p><h1 id='two'>乙章</h1><p>乙正文。</p></body></html>"
        val s = source(wire); val refs = s.chapters("public:1"); assertEquals(2, refs.size)
        val a = s.chapter("public:1", refs[0].id); val b = s.chapter("public:1", refs[1].id)
        assertTrue(a.text.contains("甲开始")); assertTrue(a.text.contains("甲中段")); assertTrue(a.text.contains("甲末段")); assertFalse(a.text.contains("乙正文"))
        assertTrue(b.text.contains("乙正文")); assertFalse(b.text.contains("甲末段"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun directFirstEpubReadResumesAndRetryRefetchesFailedContentWithoutOverwritingTheAnchor() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val app: Application = ApplicationProvider.getApplicationContext()
            val first = ReaderViewModel(app, source(Wire())); advanceUntilIdle()
            val book = first.ui.value.shelfBooks.single(); first.open(book); advanceUntilIdle()
            assertNull(first.ui.value.error); assertEquals("一、出发", first.ui.value.chapter!!.title)
            first.position(6); first.backToShelf()
            val wire = Wire().apply { text = "<html><body><span id='one'></span><span id='two'></span></body></html>" }
            val cold = ReaderViewModel(app, source(wire)); advanceUntilIdle(); val saved = cold.store.position(book.id)
            cold.open(book); advanceUntilIdle()
            assertTrue(cold.ui.value.error!!.contains("BH_EPUB_NO_TEXT")); assertEquals(saved, cold.store.position(book.id))
            wire.text = BookHubFixtures.epubText; cold.retry(); advanceUntilIdle()
            assertNull(cold.ui.value.error); assertEquals(6, cold.ui.value.offset); assertTrue(cold.ui.value.chapter!!.text.contains("走向江边"))
        } finally { Dispatchers.resetMain() }
    }
}
