package io.github.lorddetson.piplayer.covers

/** The sizes covers are loaded at (docs/library.md §13.3). */
object CoverSizes {

    /** Requested sizes round up to these, so that views of nearly one size share their pictures. */
    val STEPS = intArrayOf(96, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048)

    /** The largest step that the caches keep; larger pictures are Now Playing's, decoded each time. */
    const val CACHED_MAX = 768

    /** The step for a view [px] pixels wide. */
    fun step(px: Int): Int = TODO()

    /** Whether the caches keep pictures of [step]. */
    fun cached(step: Int): Boolean = TODO()

    /**
     * The size to decode a picture of [width] x [height] at for [step]: its shorter side at [step], the longer one in
     * proportion, as views crop to a square. A picture no larger keeps its size.
     */
    fun target(width: Int, height: Int, step: Int): Pair<Int, Int> = TODO()
}
