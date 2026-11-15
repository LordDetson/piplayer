package io.github.lorddetson.piplayer.playback

/**
 * The service's main player paused for a snippet of the review card (TI §6): the pause is the snippet's, so the sleep
 * timer treats it as playback and a swipe from recents keeps the service. This keeps what happened during the hold and
 * says what its end does. Not thread-safe: the service uses it on the main thread.
 */
class SnippetHold {

    /** What the end of a hold does to the main player. */
    enum class End {
        /** There was no hold: the main player played again by other means, or the hold had already ended. */
        NONE,

        RESUME,

        STAY_PAUSED,

        /** Stays paused, and the service stops as the swipe from recents during the hold would have stopped it. */
        STOP_SERVICE,
    }

    var held = false
        private set

    private var timerStopped = false
    private var taskRemoved = false

    /** The main player pauses for a snippet. A second request while held keeps what the first one learned. */
    fun hold() {
        if (!held) {
            timerStopped = false
            taskRemoved = false
        }
        held = true
    }

    /** The sleep timer's time came during the hold: its aim holds, and the main player must not resume. */
    fun timerStopped() {
        if (held) timerStopped = true
    }

    /** The main player plays again by other means (its notification, another controller): the hold is over. */
    fun playedOtherwise() {
        held = false
    }

    /** PiPlayer was swiped away from recents; true when the service stays for the hold. */
    fun taskRemoved(): Boolean {
        if (held) taskRemoved = true
        return held
    }

    /** The snippet has ended; [resume] when its rules resume the main player. */
    fun end(resume: Boolean): End {
        if (!held) return End.NONE
        held = false
        return when {
            resume && !timerStopped -> End.RESUME
            taskRemoved -> End.STOP_SERVICE
            else -> End.STAY_PAUSED
        }
    }
}
