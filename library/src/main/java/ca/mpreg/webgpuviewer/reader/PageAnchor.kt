package ca.mpreg.webgpuviewer.reader

/**
 * Reading-position anchor portable across viewer modes (paged index + continuous
 * document scroll). All floats are sanitized on construction: non-finite values
 * fall back to defaults, ranges match the position store's clamps so a round-trip
 * through serialize/parse never poisons a record.
 */
data class PageAnchor(
    val pageIndex: Int = 0,
    val documentY: Float = 0f,
    val offsetX: Float = 0f,
    val scale: Float = 1f,
    val fraction: Float = 0f,
) {
    init {
        require(pageIndex >= 0 || pageIndex == UNSET_INDEX) { "pageIndex must be >= 0" }
    }

    fun sanitized(): PageAnchor = PageAnchor(
        pageIndex = if (pageIndex < 0) 0 else pageIndex.coerceAtMost(MAX_PAGE_INDEX),
        documentY = documentY.sanitizeDocumentY(),
        offsetX = offsetX.sanitizeOffsetX(),
        scale = scale.sanitizeScale(),
        fraction = fraction.sanitizeFraction(),
    )

    companion object {
        const val UNSET_INDEX: Int = -1
        const val MAX_PAGE_INDEX: Int = 9999

        fun Float.sanitizeDocumentY(): Float =
            if (!isFinite()) 0f else coerceIn(0f, 1e7f)

        fun Float.sanitizeOffsetX(): Float =
            if (!isFinite()) 0f else coerceIn(-1f, 1f)

        fun Float.sanitizeScale(): Float =
            if (!isFinite()) 1f else coerceIn(0.5f, 8f)

        fun Float.sanitizeFraction(): Float =
            if (!isFinite()) 0f else coerceIn(0f, 1f)
    }
}
