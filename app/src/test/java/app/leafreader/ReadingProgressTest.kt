package app.leafreader

import app.leafreader.reader.ReadingProgress
import org.junit.Assert.*
import org.junit.Test

class ReadingProgressTest {
    @Test fun pageEndClampsAndOnlyACompleteLastPageReaches100() {
        assertEquals(0, ReadingProgress.calculate(-1, 1000, 0, 1, true).chapterPercent)
        assertEquals(99, ReadingProgress.calculate(999, 1000, 0, 1, true).chapterPercent)
        assertEquals(100, ReadingProgress.calculate(1000, 1000, 0, 1, true).chapterPercent)
        assertEquals(100, ReadingProgress.calculate(9999, 1000, 0, 1, true).bookPercent)
    }

    @Test fun aOnePageChapterAndChapterTransitionsUsePageEndNotSavedPageStart() {
        val first = ReadingProgress.calculate(1, 1, 0, 2, true)
        assertEquals("本章 100%", first.chapterLabel)
        assertEquals("全书约 50%", first.bookLabel)
        val nextStart = ReadingProgress.calculate(0, 99, 1, 2, true)
        assertEquals("本章 0%", nextStart.chapterLabel)
        assertEquals("全书约 50%", nextStart.bookLabel)
        assertEquals("全书约 100%", ReadingProgress.calculate(99, 99, 1, 2, true).bookLabel)
    }

    @Test fun completeLocalLengthsUseCharacterWeightsAcrossUnequalChapters() {
        val weights = listOf(100, 900)
        val first = ReadingProgress.calculate(100, 100, 0, 2, true, weights)
        assertEquals("全书 10%", first.bookLabel)
        assertFalse(first.bookApproximate)
        assertEquals("全书 55%", ReadingProgress.calculate(450, 900, 1, 2, true, weights).bookLabel)
        assertEquals("全书 100%", ReadingProgress.calculate(900, 900, 1, 2, true, weights).bookLabel)
    }

    @Test fun incompleteOrUnknownDirectoriesNeverPretendToKnowWholeBookProgress() {
        for (value in listOf(
            ReadingProgress.calculate(70, 100, 0, 2, false),
            ReadingProgress.calculate(70, 100, 0, 0, false),
            ReadingProgress.calculate(70, 100, -1, 2, true),
            ReadingProgress.calculate(70, 100, 2, 2, true),
            ReadingProgress.calculate(70, 100, 0, 2, false, listOf(100, 100)),
        )) {
            assertEquals("本章 70%", value.chapterLabel)
            assertEquals("全书 —", value.bookLabel)
        }
        val empty = ReadingProgress.calculate(0, 0, 0, 0, false)
        assertEquals("本章 —", empty.chapterLabel)
        assertEquals("全书 —", empty.bookLabel)
    }

    @Test fun invalidWeightsAreMarkedAsEstimateAndLargeWeightsDoNotOverflow() {
        assertEquals("全书约 25%", ReadingProgress.calculate(50, 100, 0, 2, true, listOf(100)).bookLabel)
        assertEquals("全书约 25%", ReadingProgress.calculate(50, 100, 0, 2, true, listOf(50, 100)).bookLabel)
        val weights = listOf(Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals("全书 100%", ReadingProgress.calculate(Int.MAX_VALUE, Int.MAX_VALUE, 1, 2, true, weights).bookLabel)
    }

    @Test fun compactPercentageUsesActualRatioAndNeverRoundsUnfinishedBookTo100() {
        assertEquals("12.34%",ReadingProgress.calculate(2468,20000,0,1,true).compactBookLabel)
        assertEquals("0.00%",ReadingProgress.calculate(0,20000,0,1,true).compactBookLabel)
        assertEquals("99.99%",ReadingProgress.calculate(19999,20000,0,1,true).compactBookLabel)
        assertEquals("100.00%",ReadingProgress.calculate(20000,20000,0,1,true).compactBookLabel)
        assertEquals("—",ReadingProgress.calculate(10,20,0,1,false).compactBookLabel)
        assertEquals("—",ReadingProgress.calculate(0,0,0,0,false).compactBookLabel)
    }

}
