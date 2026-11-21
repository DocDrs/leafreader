package app.leafreader

import app.leafreader.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookHubSourceTest {
    private class FixtureWire : WireTransport {
        val calls = mutableListOf<Triple<URI, String, Map<String, String>>>()
        var manifest = BookHubFixtures.txtManifest()
        var page = BookHubFixtures.page(next = "下一页 &/?")
        var first = BookHubFixtures.firstText
        var failResource = 0
        var expired = false
        var revoked = false
        override suspend fun execute(uri: URI, method: String, headers: Map<String, String>, body: ByteArray?, maxBytes: Int): WireResponse {
            calls += Triple(uri, method, headers)
            if (uri.path.endsWith("/auth/login") || uri.path.endsWith("/auth/refresh")) return WireResponse(200, BookHubFixtures.envelope(BookHubFixtures.session()).toByteArray())
            if (uri.path.endsWith("/bookshelf") || uri.path.endsWith("/catalog/search")) {
                if (revoked) return WireResponse(401, "{\"code\":\"SESSION_REVOKED\"}".toByteArray())
                if (expired) { expired = false; return WireResponse(401, "{\"code\":\"SESSION_EXPIRED\"}".toByteArray()) }
                return WireResponse(200, BookHubFixtures.envelope(page).toByteArray())
            }
            if (uri.path.endsWith("/access")) return WireResponse(200, BookHubFixtures.envelope(manifest).toByteArray())
            if (failResource != 0) return WireResponse(failResource, ByteArray(0))
            val text = if (manifest.getString("format") == "EPUB") BookHubFixtures.epubText else if (uri.path.endsWith("/a")) first else BookHubFixtures.secondText
            return WireResponse(200, text.toByteArray())
        }
    }
    private suspend fun source(wire: FixtureWire): BookHubSource {
        val client = BookHubClient(ServiceOrigin("https://fixture.invalid"), wire)
        client.login("fixture-user", "fixture-password")
        return BookHubSource(client)
    }
    private suspend fun problem(kind: FailureKind, action: suspend () -> Unit) {
        try { action(); fail("Expected $kind") } catch (e: SourceProblem) { assertEquals(kind, e.kind) }
    }

    @Test fun originAndResourceBoundariesRejectCredentialsRedirectTargetsAndTraversal() {
        for (value in listOf("https://u:p@host.invalid", "http://host.invalid", "https://host.invalid/api/v1", "https://host.invalid?key=x")) {
            try { ServiceOrigin(value); fail(value) } catch (_: SourceProblem) { }
        }
        val origin = ServiceOrigin("https://host.invalid/")
        for (path in listOf("https://else.invalid/api/v1/books/a", "//else.invalid/api/v1/books/a", "/api/v1/books/../me", "/api/v1/books/%2e%2e/me", "/api/v1/books/%252e%252e/me")) {
            try { origin.resolve(path, true); fail(path) } catch (_: SourceProblem) { }
        }
        assertEquals("http://127.0.0.1:1234", ServiceOrigin("http://127.0.0.1:1234").value)
    }
    @Test fun searchUsesEncodedCursorAndRejectsMissingOrRepeatedCursor() = runBlocking {
        val wire = FixtureWire(); val source = source(wire)
        val first = source.browse("山 海&灯", null)
        assertTrue(first.hasMore)
        wire.page = BookHubFixtures.page("public:2")
        val second = source.browse("山 海&灯", first.nextCursor)
        assertEquals("public:2", second.items.single().id)
        val request = wire.calls.last().first.toString()
        assertTrue(request.contains("limit=20")); assertTrue(request.contains("cursor=%E4%B8%8B%E4%B8%80%E9%A1%B5%20%26%2F%3F"))
        assertEquals("Bearer fixture-access", wire.calls.last().third["Authorization"])
        wire.page = BookHubFixtures.page(next = "loop")
        problem(FailureKind.PROTOCOL) { source.browse("x", "loop") }
        wire.page.put("nextCursor", JSONObject.NULL)
        problem(FailureKind.PROTOCOL) { source.browse("x") }
    }
    @Test fun txtDirectorySlicesUnicodeCodePointsAndResourceRequestsHaveNoBearer() = runBlocking {
        val wire = FixtureWire(); val source = source(wire)
        val chapters = source.chapters("public:1")
        assertEquals(2, chapters.size)
        val first = source.chapter("public:1", chapters[0].id)
        val second = source.chapter("public:1", chapters[1].id)
        assertTrue(first.text.contains("🌙")); assertFalse(first.text.contains("第二章"))
        assertTrue(second.text.contains("第二章")); assertFalse(second.text.contains("第一章"))
        assertNull(wire.calls.last().third["Authorization"])
    }
    @Test fun epubUsesExactAnchorsAndNeverLoadsScriptsImagesOrStyles() = runBlocking {
        val wire = FixtureWire().apply { manifest = BookHubFixtures.epubManifest() }; val source = source(wire)
        val chapters = source.chapters("public:1")
        val a = source.chapter("public:1", chapters.first { it.title == "一、出发" }.id)
        val b = source.chapter("public:1", chapters.first { it.title == "二、到达" }.id)
        assertTrue(a.text.contains("走向江边")); assertFalse(a.text.contains("到达对岸"))
        assertTrue(b.text.contains("到达对岸")); assertFalse(b.text.contains("SECRET"))
        assertTrue(wire.calls.all { it.first.host == "fixture.invalid" })
        problem(FailureKind.PROTOCOL) { EpubPlainText.extract("<p>正文</p>", "missing") }
    }
    @Test fun emptyDirectoryUnsupportedFormatAndTextMismatchFailExplicitly() = runBlocking {
        val empty = FixtureWire().apply { manifest.put("spine", org.json.JSONArray()) }
        problem(FailureKind.EMPTY) { source(empty).chapters("public:1") }
        val pdf = FixtureWire().apply { manifest.put("format", "PDF") }
        problem(FailureKind.UNSUPPORTED) { source(pdf).chapters("public:1") }
        val mismatch = FixtureWire().apply { first = "短正文" }; val source = source(mismatch)
        val c = source.chapters("public:1")
        problem(FailureKind.PROTOCOL) { source.chapter("public:1", c[0].id) }
    }
    @Test fun ticketDenialIsBoundedAndDoesNotBecomeDemoContent() = runBlocking {
        val wire = FixtureWire().apply { failResource = 403 }; val source = source(wire)
        val chapters = source.chapters("public:1")
        problem(FailureKind.FORBIDDEN) { source.chapter("public:1", chapters[0].id) }
        assertEquals(2, wire.calls.count { it.first.path.contains("/resources/") })
        assertEquals(2, wire.calls.count { it.first.path.endsWith("/access") })
    }
    @Test fun onlyExpiredSessionsRefreshAndRevokedSessionsStop() = runBlocking {
        val wire = FixtureWire(); val source = source(wire)
        wire.expired = true; source.books()
        assertEquals(1, wire.calls.count { it.first.path.endsWith("/auth/refresh") })
        wire.revoked = true
        problem(FailureKind.AUTH) { source.books() }
        assertEquals(1, wire.calls.count { it.first.path.endsWith("/auth/refresh") })
    }
    @Test fun txtTocCanSplitInsideAResourceAndReadAcrossSegments() = runBlocking {
        val wire = FixtureWire()
        val split = BookHubFixtures.firstText.codePointCount(0, BookHubFixtures.firstText.length)
        wire.manifest.getJSONArray("toc").getJSONObject(1).put("codePointOffset", split - 4)
        val source = source(wire); val chapters = source.chapters("public:1")
        val second = source.chapter("public:1", chapters[1].id)
        assertEquals("轻。🌙", second.paragraphs.first())
        assertTrue(second.paragraphs[1].startsWith("第二章"))
    }
    @Test fun emptyTextAndChangedVersionDoNotProduceAFalseReadingPosition() = runBlocking {
        val empty = FixtureWire().apply { first = " ".repeat(BookHubFixtures.firstText.codePointCount(0, BookHubFixtures.firstText.length)) }
        val emptySource = source(empty); val refs = emptySource.chapters("public:1")
        problem(FailureKind.EMPTY) { emptySource.chapter("public:1", refs[0].id) }
        val changing = FixtureWire().apply { manifest.put("expiresAt", "2000-01-01T00:00:00Z") }
        val changedSource = source(changing); val oldRefs = changedSource.chapters("public:1")
        changing.manifest = BookHubFixtures.txtManifest("fixture-v2")
        problem(FailureKind.VERSION) { changedSource.chapter("public:1", oldRefs[0].id) }
    }
}
