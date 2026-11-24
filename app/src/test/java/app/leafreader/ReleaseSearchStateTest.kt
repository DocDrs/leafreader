package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ReleaseSearchStateTest {
    private val app:Application=ApplicationProvider.getApplicationContext()
    private fun book(id:String,source:String="")=Book(id,"相同书名","自写作者","本地测试","",1,0,sourceId=source)
    private class Source(val owner:String="release-search"):BookSource {
        override val id=owner
        val calls=mutableListOf<Pair<String,String?>>()
        var response:suspend(String,String?)->BookPage={_,_->BookPage(emptyList())}
        override suspend fun books()=emptyList<Book>()
        override suspend fun browse(query:String,cursor:String?):BookPage {calls+=query to cursor;return response(query,cursor)}
        override suspend fun chapters(bookId:String)=emptyList<ChapterRef>()
        override suspend fun chapter(bookId:String,chapterId:String):Chapter=error("not read")
    }
    @Test fun blankInputNeverRequestsCatalogAndEditingCancelsLateResults()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source=Source();val late=CompletableDeferred<Unit>()
            source.response={q,_->if(q.isBlank())BookPage(listOf(book("owned"))) else {withContext(NonCancellable){late.await()};BookPage(listOf(book("late")))}}
            val vm=ReaderViewModel(app,source,sessionVault=MemorySessionVault(),linkBookHubShelf=false);advanceUntilIdle()
            assertTrue(vm.ui.value.books.isEmpty());vm.search("  ");advanceUntilIdle();assertEquals(1,source.calls.size)
            vm.search("旧词");runCurrent();vm.clearSearch();late.complete(Unit);advanceUntilIdle()
            assertTrue(vm.ui.value.books.isEmpty());assertEquals("",vm.ui.value.query);assertFalse(vm.ui.value.hasMore);assertFalse(vm.ui.value.findLoading)
        }finally{Dispatchers.resetMain()}
    }
    @Test fun shelfFilteringUsesOwnerAndStableIdAndAddingImmediatelyRemovesResult()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source=Source();source.response={q,_->BookPage(if(q.isBlank())listOf(book("synced")) else listOf(book("synced"),book("local"),book("other"),book("synced","other-account")))}
            val vm=ReaderViewModel(app,source,sessionVault=MemorySessionVault(),linkBookHubShelf=false);advanceUntilIdle();vm.addToShelf(book("local"))
            vm.search("相同");advanceUntilIdle()
            assertEquals(listOf("release-search|other","other-account|synced"),vm.ui.value.books.map{it.key})
            assertFalse(vm.isOnShelf(book("synced","other-account")));assertTrue(vm.addToShelf(book("other")))
            assertEquals(listOf("other-account|synced"),vm.ui.value.books.map{it.key})
            vm.search("相同");advanceUntilIdle();assertEquals(listOf("other-account|synced"),vm.ui.value.books.map{it.key})
        }finally{Dispatchers.resetMain()}
    }
    @Test fun pagesFullyFilteredStillAllowNextPageAndFailuresKeepLoadedResults()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var fail=false;val source=Source()
            source.response={q,c->when{q.isBlank()->BookPage(listOf(book("owned")));c==null->BookPage(listOf(book("owned")),"next",true);c=="next"->BookPage(listOf(book("new")),"last",true);fail->throw SourceProblem(FailureKind.NETWORK,"测试断网");else->BookPage(listOf(book("new2")))}}
            val vm=ReaderViewModel(app,source,sessionVault=MemorySessionVault(),linkBookHubShelf=false);advanceUntilIdle();vm.search("词");advanceUntilIdle()
            assertTrue(vm.ui.value.books.isEmpty());assertTrue(vm.ui.value.hasMore)
            vm.loadMore();advanceUntilIdle();assertEquals(listOf("new"),vm.ui.value.books.map{it.id})
            fail=true;vm.loadMore();advanceUntilIdle();assertNotNull(vm.ui.value.error);assertEquals(listOf("new"),vm.ui.value.books.map{it.id})
            fail=false;vm.retrySearch();advanceUntilIdle();assertNull(vm.ui.value.error);assertEquals(listOf("new","new2"),vm.ui.value.books.map{it.id})
            vm.clearSearch();vm.loadMore();advanceUntilIdle();assertTrue(vm.ui.value.books.isEmpty())
        }finally{Dispatchers.resetMain()}
    }
    @Test fun ownedBookOnNotYetLoadedShelfPageIsFilteredBeforeShowingSearchResults()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source=Source()
            source.response={q,c->when{q.isNotBlank()->BookPage(listOf(book("owned-on-page-two"),book("new")));c==null->BookPage(listOf(book("first")),"shelf-next",true);else->BookPage(listOf(book("owned-on-page-two")))}}
            val vm=ReaderViewModel(app,source,sessionVault=MemorySessionVault(),linkBookHubShelf=false);advanceUntilIdle();assertEquals(1,vm.ui.value.shelfBooks.size)
            vm.search("词");advanceUntilIdle();assertEquals(listOf("new"),vm.ui.value.books.map{it.id});assertEquals(2,vm.ui.value.shelfBooks.size)
            assertEquals(listOf("" to null,"词" to null,"" to "shelf-next"),source.calls)
        }finally{Dispatchers.resetMain()}
    }
    @Test fun shelfFailureKeepsItsAuthClassificationAndSearchRetryCanResyncShelf()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var fail=true;val source=Source()
            source.response={q,_->if(q.isBlank() && fail)throw SourceProblem(FailureKind.AUTH,"测试会话失效") else BookPage(if(q.isBlank())listOf(book("owned")) else listOf(book("owned"),book("new")))}
            val vm=ReaderViewModel(app,source,sessionVault=MemorySessionVault(),linkBookHubShelf=false);advanceUntilIdle();vm.search("词");advanceUntilIdle()
            assertTrue(vm.ui.value.error!!.contains("BH_AUTH"));assertTrue(vm.ui.value.books.isNotEmpty());assertFalse(vm.ui.value.shelfKnownComplete)
            fail=false;vm.retrySearch();advanceUntilIdle();assertNull(vm.ui.value.error);assertEquals(listOf("new"),vm.ui.value.books.map{it.id})
        }finally{Dispatchers.resetMain()}
    }
    @Test fun newerQueryWinsAndOldFailureCannotReplaceIt()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val late=CompletableDeferred<Unit>();val source=Source()
            source.response={q,_->when(q){""->BookPage(emptyList());"old"->{withContext(NonCancellable){late.await()};throw SourceProblem(FailureKind.NETWORK,"旧请求失败")};else->BookPage(listOf(book("fresh")))}}
            val vm=ReaderViewModel(app,source,sessionVault=MemorySessionVault(),linkBookHubShelf=false);advanceUntilIdle();vm.search("old");runCurrent();vm.search("new");runCurrent();late.complete(Unit);advanceUntilIdle()
            assertEquals("new",vm.ui.value.query);assertEquals(listOf("fresh"),vm.ui.value.books.map{it.id});assertNull(vm.ui.value.error)
        }finally{Dispatchers.resetMain()}
    }
    @Test fun changingAccountRejectsOldSearchResponseEvenWhenBookIdsMatch()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val origin="https://search-account.fixture.invalid";val late=CompletableDeferred<Unit>()
            val transport=WireTransport{uri,_,_,body,_->
                val data=when {
                    uri.path.endsWith("/auth/login")->BookHubFixtures.session(org.json.JSONObject(String(body!!)).getString("username"))
                    uri.path.endsWith("/catalog/search")->{withContext(NonCancellable){late.await()};BookHubFixtures.page("public:9")}
                    else->BookHubFixtures.page("public:1")
                }
                WireResponse(200,BookHubFixtures.envelope(data).toByteArray())
            }
            val vault=MemorySessionVault().apply{value=SavedSession(origin,"first","fixture-device","fixture-access","fixture-refresh")}
            val vm=ReaderViewModel(app,connectionGate=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,transport)}),sessionVault=vault,linkBookHubShelf=false)
            advanceUntilIdle();val owner=vm.ui.value.shelfBooks.single().sourceId
            vm.search("old");runCurrent();vm.connect(origin,"second","synthetic-password");runCurrent();late.complete(Unit);advanceUntilIdle()
            assertEquals(2,vm.ui.value.shelfBooks.size);assertNotEquals(owner,vm.ui.value.shelfBooks.single{it.sourceId==vm.store.sourceId}.sourceId);assertTrue(vm.ui.value.books.isEmpty());assertTrue(vm.ui.value.query.isEmpty());assertNull(vm.ui.value.error)
        }finally{Dispatchers.resetMain()}
    }
}
