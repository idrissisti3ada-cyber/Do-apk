package com.example

import com.example.domain.PageDistributionPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageDistributionPlannerTest {

    @Test
    fun `more codes than opened pages processes each page once until pages end and stops`() {
        val pages = listOf(1 to "P1", 2 to "P2", 3 to "P3")
        val codes = listOf("code1", "code2", "code3", "code4", "code5")

        val result = PageDistributionPlanner.distribute(
            codes = codes,
            eligiblePages = pages,
            lastProcessedPageId = null
        )

        assertEquals(3, result.allocations.size)
        assertEquals(1 to "code1", result.allocations[0].pageId to result.allocations[0].code)
        assertEquals(2 to "code2", result.allocations[1].pageId to result.allocations[1].code)
        assertEquals(3 to "code3", result.allocations[2].pageId to result.allocations[2].code)
        assertEquals(3, result.nextLastProcessedPageId)
    }

    @Test
    fun `fewer codes than opened pages processes only the number of codes and stops`() {
        val pages = listOf(1 to "P1", 2 to "P2", 3 to "P3", 4 to "P4")
        val codes = listOf("codeA", "codeB")

        val result = PageDistributionPlanner.distribute(
            codes = codes,
            eligiblePages = pages,
            lastProcessedPageId = null
        )

        assertEquals(2, result.allocations.size)
        assertEquals(1 to "codeA", result.allocations[0].pageId to result.allocations[0].code)
        assertEquals(2 to "codeB", result.allocations[1].pageId to result.allocations[1].code)
        assertEquals(2, result.nextLastProcessedPageId)

        // Next code after partial batch continues on page 3 (different page)
        val nextSingle = PageDistributionPlanner.distribute(
            codes = listOf("codeC"),
            eligiblePages = pages,
            lastProcessedPageId = result.nextLastProcessedPageId
        )
        assertEquals(1, nextSingle.allocations.size)
        assertEquals(3 to "codeC", nextSingle.allocations[0].pageId to nextSingle.allocations[0].code)
        assertEquals(3, nextSingle.nextLastProcessedPageId)
    }

    @Test
    fun `codes copied one by one process each next code in a different page round-robin`() {
        val pages = listOf(1 to "P1", 2 to "P2", 3 to "P3")

        val r1 = PageDistributionPlanner.distribute(listOf("c1"), pages, null)
        val r2 = PageDistributionPlanner.distribute(listOf("c2"), pages, r1.nextLastProcessedPageId)
        val r3 = PageDistributionPlanner.distribute(listOf("c3"), pages, r2.nextLastProcessedPageId)
        val r4 = PageDistributionPlanner.distribute(listOf("c4"), pages, r3.nextLastProcessedPageId)

        assertEquals(1, r1.allocations.single().pageId)
        assertEquals(2, r2.allocations.single().pageId)
        assertEquals(3, r3.allocations.single().pageId)
        assertEquals(1, r4.allocations.single().pageId)
    }

    @Test
    fun `handles closed middle page cleanly without skipping next available page`() {
        val pagesAfterClose = listOf(1 to "P1", 3 to "P3")

        val result = PageDistributionPlanner.distribute(
            codes = listOf("c_next"),
            eligiblePages = pagesAfterClose,
            lastProcessedPageId = 2
        )

        assertEquals(3, result.allocations.single().pageId)
        assertEquals(3, result.nextLastProcessedPageId)
    }

    @Test
    fun `empty pages or empty codes returns no allocations`() {
        val emptyPages = PageDistributionPlanner.distribute<String>(
            codes = listOf("c1"),
            eligiblePages = emptyList(),
            lastProcessedPageId = 1
        )
        assertEquals(0, emptyPages.allocations.size)
        assertEquals(1, emptyPages.nextLastProcessedPageId)

        val emptyCodes = PageDistributionPlanner.distribute(
            codes = emptyList(),
            eligiblePages = listOf(1 to "P1"),
            lastProcessedPageId = null
        )
        assertEquals(0, emptyCodes.allocations.size)
        assertNull(emptyCodes.nextLastProcessedPageId)
    }
}
