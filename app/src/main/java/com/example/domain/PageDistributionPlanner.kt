package com.example.domain

data class PageAllocation<T>(
    val pageId: Int,
    val page: T,
    val code: String
)

data class PageDistributionResult<T>(
    val allocations: List<PageAllocation<T>>,
    val nextLastProcessedPageId: Int?
)

object PageDistributionPlanner {

    /**
     * Distributes copied [codes] across [eligiblePages] (sorted by pageId ascending):
     * - If more codes than pages: processes 1 code per page until pages end, then stops.
     * - If fewer codes than pages: processes only [codes.size] pages and stops.
     * - If codes arrive one by one: advances round-robin via [lastProcessedPageId] so each
     *   subsequent code is processed on the next different page.
     */
    fun <T> distribute(
        codes: List<String>,
        eligiblePages: List<Pair<Int, T>>,
        lastProcessedPageId: Int?
    ): PageDistributionResult<T> {
        if (codes.isEmpty() || eligiblePages.isEmpty()) {
            return PageDistributionResult(
                allocations = emptyList(),
                nextLastProcessedPageId = lastProcessedPageId
            )
        }

        val sortedPages = eligiblePages.sortedBy { it.first }
        val count = minOf(codes.size, sortedPages.size)
        val startIndex = if (lastProcessedPageId == null) {
            0
        } else {
            val nextIdx = sortedPages.indexOfFirst { it.first > lastProcessedPageId }
            if (nextIdx == -1) 0 else nextIdx
        }

        val allocations = ArrayList<PageAllocation<T>>(count)
        var updatedLastId = lastProcessedPageId

        for (i in 0 until count) {
            val (pageId, page) = sortedPages[(startIndex + i) % sortedPages.size]
            allocations.add(
                PageAllocation(
                    pageId = pageId,
                    page = page,
                    code = codes[i]
                )
            )
            updatedLastId = pageId
        }

        return PageDistributionResult(
            allocations = allocations,
            nextLastProcessedPageId = updatedLastId
        )
    }
}
