package com.example.kotlintest.common

import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort

/**
 * Project-standard JSON shape of a page of a collection:
 * `{"content": [...], "page": {"size": 20, "number": 0, "totalElements": 1, "totalPages": 1}}`.
 */
data class PageResponse<T>(
    val content: List<T>,
    val page: PageMetadata,
) {
    data class PageMetadata(
        val size: Int,
        val number: Int,
        val totalElements: Long,
        val totalPages: Int,
    )

    companion object {
        fun <E : Any, T> from(page: Page<E>, mapper: (E) -> T): PageResponse<T> = PageResponse(
            content = page.content.map(mapper),
            page = PageMetadata(page.size, page.number, page.totalElements, page.totalPages),
        )
    }
}

/**
 * Validates the requested sort against [allowed] properties and makes the order deterministic.
 *
 * - An unsorted request is sorted by `id` ascending.
 * - `id` is appended as a tie-breaker when it is not already part of the sort.
 * - Any property outside [allowed] raises [InvalidSortPropertyException] (`400`).
 */
fun Pageable.withValidatedSort(allowed: Set<String>): Pageable {
    sort.forEach { order ->
        if (order.property !in allowed) throw InvalidSortPropertyException(order.property, allowed)
    }
    val effectiveSort = when {
        sort.isUnsorted -> Sort.by(Sort.Direction.ASC, "id")
        sort.getOrderFor("id") == null -> sort.and(Sort.by(Sort.Direction.ASC, "id"))
        else -> sort
    }
    return PageRequest.of(pageNumber, pageSize, effectiveSort)
}
