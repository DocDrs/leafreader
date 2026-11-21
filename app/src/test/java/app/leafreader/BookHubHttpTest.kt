package app.leafreader

import app.leafreader.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookHubHttpTest {
    @Test fun loopbackHttpLoginPaginationDirectoryAndTextRoundTrip() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            listOf(BookHubFixtures.session(), BookHubFixtures.page(next = "cursor-2"), BookHubFixtures.page("public:2"), BookHubFixtures.txtManifest())
                .forEach { server.enqueue(MockResponse().setBody(BookHubFixtures.envelope(it))) }
            server.enqueue(MockResponse().setBody(BookHubFixtures.firstText))
            val client = BookHubClient(ServiceOrigin("http://127.0.0.1:${server.port}"))
            client.login("fixture-user", "fixture-password")
            val source = BookHubSource(client)
            val page = source.browse("灯", null); source.browse("灯", page.nextCursor)
            val directory = source.chapters("public:1")
            assertTrue(source.chapter("public:1", directory[0].id).text.contains("灯亮了"))
            val requests = (0 until 5).map { server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertEquals("POST", requests[0].method); assertEquals("/api/v1/auth/login", requests[0].path)
            assertTrue(requests[0].body.readUtf8().contains("fixture-password"))
            assertTrue(requests[2].path!!.contains("cursor=cursor-2"))
            assertEquals("Bearer fixture-access", requests[1].getHeader("Authorization"))
            assertNull(requests[4].getHeader("Authorization"))
        } finally { server.shutdown() }
    }
    @Test fun redirectAuthenticationPreparingAndMalformedResponsesAreExplicit() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val client = BookHubClient(ServiceOrigin("http://127.0.0.1:${server.port}"))
            for ((response, kind) in listOf(
                MockResponse().setResponseCode(302).setHeader("Location", "https://invalid.example/no-follow") to FailureKind.NETWORK,
                MockResponse().setResponseCode(401).setBody("{\"code\":\"SESSION_REVOKED\"}") to FailureKind.AUTH,
                MockResponse().setResponseCode(403) to FailureKind.FORBIDDEN,
                MockResponse().setResponseCode(202).setBody("{}") to FailureKind.PREPARING,
                MockResponse().setBody("not-json") to FailureKind.PROTOCOL
            )) {
                server.enqueue(MockResponse().setBody(BookHubFixtures.envelope(BookHubFixtures.session())))
                client.login("fixture", "fixture-password") // each revoked-session case starts independently
                server.enqueue(response)
                try { client.api("/me/bookshelf"); fail("Expected $kind") } catch (e: SourceProblem) { assertEquals(kind, e.kind) }
            }
            assertEquals(10, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun disconnectedLoopbackServerProducesNetworkFailure() = runBlocking {
        val server = MockWebServer(); server.start(); val port = server.port; server.shutdown()
        val client = BookHubClient(ServiceOrigin("http://127.0.0.1:$port"))
        try { client.login("fixture", "fixture-password"); fail("Expected network failure") }
        catch (e: SourceProblem) { assertEquals(FailureKind.NETWORK, e.kind) }
    }
}
