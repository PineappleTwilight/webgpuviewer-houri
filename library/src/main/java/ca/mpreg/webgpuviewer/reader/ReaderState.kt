package ca.mpreg.webgpuviewer.reader

/**
 * Lifecycle state reported through [OnReaderStateChanged]. Transitions are
 * monotonic within one surface session: Idle → Ready → Released; Error may
 * replace Ready and is terminal until the next Idle.
 */
sealed interface ReaderState {
    /** Surface not initialized (or after Released). No pages draw. */
    data object Idle : ReaderState

    /** Surface live: pages fetch, decode, and draw. [anchor] is the last visible position. */
    data class Ready(val anchor: PageAnchor) : ReaderState

    /** Surface cleaned up; callbacks will not fire again until Idle → Ready. */
    data object Released : ReaderState

    /** Initialization or render failure. Terminal for this session. */
    data class Error(val message: String) : ReaderState
}

/** Typed replacement for ad-hoc `() -> Unit` lifecycle listeners. */
fun interface OnReaderStateChanged {
    fun invoke(state: ReaderState)
}
