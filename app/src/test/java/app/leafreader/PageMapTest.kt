package app.leafreader

import app.leafreader.reader.PageMap
import app.leafreader.reader.TextPage
import org.junit.Assert.*
import org.junit.Test

class PageMapTest {
    @Test fun anchorsUseHalfOpenRangesAndClampStalePositions() {
        val map = PageMap(listOf(TextPage(0, 2, 0, 70), TextPage(3, 5, 70, 180), TextPage(6, 8, 180, 240)))
        assertEquals(0, map.pageAt(-10)); assertEquals(0, map.pageAt(69))
        assertEquals(1, map.pageAt(70)); assertEquals(1, map.pageAt(179))
        assertEquals(2, map.pageAt(180)); assertEquals(2, map.pageAt(99999))
    }
}
