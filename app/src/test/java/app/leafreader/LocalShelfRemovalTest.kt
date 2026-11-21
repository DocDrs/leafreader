package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LocalShelfRemovalTest {
    private val app:Application=ApplicationProvider.getApplicationContext()
    private fun book(id:String)=Book(id,"同名书","自写作者","","",1,0)
    @Test fun syncedLocalAndMergedEntriesStayHiddenAcrossRefreshAndColdOpenUntilExplicitReadd() {
        val s=ReadingStore(app,"hide-owner-a");val books=listOf(book("synced"),book("local"),book("both"))
        s.rememberServiceShelf(listOf(books[0],books[2]));s.addToShelf(books[1]);s.addToShelf(books[2])
        books.forEach{s.save(it.id,"old-chapter",73,"old-image");s.markEntered(it,111)}
        val saved=books.associate{it.id to s.position(it.id)};val settings=ReaderSettings(26f,1.7f,4,23f,31f,17f);s.saveSettings(settings)
        assertTrue(s.hideFromShelf(books.map{it.id}.toSet()));s.rememberServiceShelf(listOf(books[0],books[2]))
        repeat(2) {
            val cold=ReadingStore(app,"hide-owner-a");assertTrue(cold.orderedShelf().isEmpty())
            assertEquals(2,cold.localShelf().size);assertEquals(2,cold.savedServiceShelf().size)
            books.forEach{assertEquals(saved[it.id],cold.position(it.id));assertEquals(111L,cold.lastEnteredAt(it.id))};assertEquals(settings,cold.settings())
        }
        assertTrue(s.addToShelf(books[2]));assertEquals(listOf("both"),s.orderedShelf().map{it.id});assertEquals(saved["both"],s.position("both"))
        assertEquals(setOf("synced","local"),s.hiddenShelfIds())
    }
    @Test fun stableIdsDoNotHideSameTitleOrOtherAccountAndService() {
        val a=ReadingStore(app,"server-a-account-a");val b=ReadingStore(app,"server-a-account-b");val c=ReadingStore(app,"server-b-account-a")
        listOf(a,b,c).forEach{it.rememberServiceShelf(listOf(book("same-id"),book("different-id")))}
        a.hideFromShelf(setOf("same-id"));assertEquals(listOf("different-id"),a.orderedShelf().map{it.id})
        assertEquals(2,b.orderedShelf().size);assertEquals(2,c.orderedShelf().size)
        b.addToShelf(book("same-id"));assertEquals(setOf("same-id"),a.hiddenShelfIds())
    }
}
