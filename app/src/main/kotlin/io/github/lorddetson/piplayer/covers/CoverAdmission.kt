package io.github.lorddetson.piplayer.covers

/**
 * How many covers the provider serves at a time (docs/library.md §13.8.4): each holds two descriptors and a place in the
 * loader's line until it is written, so a caller that opens thousands cannot exhaust the process. At most [max] in all
 * and [perCaller] for one uid, so that one caller that opens many and reads none cannot starve the others.
 */
class CoverAdmission(private val max: Int, private val perCaller: Int) {

    private var count = 0

    /** Places taken by uid; a uid without any has no entry. */
    private val byCaller = HashMap<Int, Int>()

    val inFlight: Int
        @Synchronized get() = count

    @Synchronized
    fun inFlightOf(uid: Int): Int = byCaller[uid] ?: 0

    /** Takes a place for [uid], or false when all [max] are taken or [uid] has its [perCaller]. */
    @Synchronized
    fun tryEnter(uid: Int): Boolean {
        val mine = byCaller[uid] ?: 0
        if (count >= max || mine >= perCaller) return false
        count++
        byCaller[uid] = mine + 1
        return true
    }

    /** Gives a place of [uid] back. */
    @Synchronized
    fun exit(uid: Int) {
        val mine = byCaller[uid] ?: return
        count--
        if (mine <= 1) byCaller.remove(uid) else byCaller[uid] = mine - 1
    }
}
