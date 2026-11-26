package io.github.lorddetson.piplayer.settings

internal class UiGestureTapRecognizer(enabled: Set<UiGesture>, private val intervalMillis: Long) {
    private val taps = enabledByCount(enabled, UiBuiltInGesture.TAP, UiBuiltInGesture.DOUBLE_TAP, UiBuiltInGesture.TRIPLE_TAP)
    private val holds = enabledByCount(enabled, UiBuiltInGesture.LONG_PRESS, UiBuiltInGesture.TAP_THEN_LONG_PRESS,
        UiBuiltInGesture.DOUBLE_TAP_THEN_LONG_PRESS)
    private var shortCount = 0
    private var completed: UiGesture? = null
    var deadlineMillis: Long? = null
        private set

    init {
        require(intervalMillis > 0)
    }

    /** A timely down can extend an existing prefix past the interval while the finger is held. */
    fun pressStarted(now: Long): List<UiGesture> = advance(now).also { deadlineMillis = null }

    fun shortReleased(now: Long): List<UiGesture> {
        val expired = advance(now)
        val count = shortCount + 1
        if (hasContinuation(count)) {
            shortCount = count
            completed = taps[count - 1] ?: completed
            deadlineMillis = if (Long.MAX_VALUE - intervalMillis < now) Long.MAX_VALUE else now + intervalMillis
            return expired
        }
        val exact = taps[count - 1]
        if (exact != null) {
            cancel()
            return expired + exact
        }
        if (shortCount == 0) return expired
        return expired + complete() + shortReleased(now)
    }

    fun longPressed(now: Long): List<UiGesture> {
        val expired = advance(now)
        val exact = holds[shortCount]
        if (exact != null) {
            cancel()
            return expired + exact
        }
        return expired + complete() + listOfNotNull(holds[0])
    }

    fun advance(now: Long): List<UiGesture> =
        if (deadlineMillis?.let { now >= it } == true) complete() else emptyList()

    fun cancel() {
        shortCount = 0
        completed = null
        deadlineMillis = null
    }

    private fun hasContinuation(count: Int): Boolean =
        taps.keys.any { it >= count } || holds.keys.any { it >= count }

    private fun complete(): List<UiGesture> = listOfNotNull(completed).also { cancel() }

    private fun enabledByCount(enabled: Set<UiGesture>, vararg gestures: UiBuiltInGesture): Map<Int, UiGesture> =
        gestures.mapIndexedNotNull { index, gesture ->
            UiGesture.builtIn(gesture).takeIf { it in enabled }?.let { index to it }
        }.toMap()
}
