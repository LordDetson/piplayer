package io.github.lorddetson.piplayer.identification.caa

import io.github.lorddetson.piplayer.library.details.PictureProbe
import io.github.lorddetson.piplayer.library.details.PictureSize

/** The cover the user sees now, as far as its size is known (TI §5.2). */
sealed interface CurrentCover {

    /** No cover. */
    data object None : CurrentCover

    /** A cover of known size, such as an own cover or a saved one. */
    data class Known(val size: PictureSize) : CurrentCover

    /** Android's thumbnail of the file (for example the folder image); its size is not kept. */
    data object SystemThumbnail : CurrentCover

    /** The picture embedded in the file at [uri], whose header is read only when the rule needs its size. */
    data class Embedded(val uri: String) : CurrentCover
}

/**
 * Whether a proposed cover's checkbox starts ticked (TI §5.2): never replace a cover with a smaller one unless the user
 * asks, where smaller means a shorter short side in pixels.
 */
object CoverSizeRule {

    /** Whether [proposed] has a shorter short side than [current]. */
    fun smaller(proposed: PictureSize, current: PictureSize): Boolean = proposed.shortSide < current.shortSide

    /**
     * Whether a cover of [proposed] size (null when not known) starts ticked over [current]. An embedded picture has its
     * header read through [probe] only when the answer depends on it.
     */
    suspend fun startsTicked(proposed: PictureSize?, current: CurrentCover, probe: PictureProbe): Boolean =
        // Over any cover, a proposed cover whose own size is not known cannot be shown not to be smaller.
        when (current) {
            CurrentCover.None -> true
            is CurrentCover.Known -> proposed != null && !smaller(proposed, current.size)
            // Its size is not kept: treated as not smaller.
            CurrentCover.SystemThumbnail -> proposed != null
            // A header that cannot be read is a picture the user does not see.
            is CurrentCover.Embedded ->
                proposed != null && (probe.embedded(current.uri)?.let { !smaller(proposed, it) } ?: true)
        }

    private val PictureSize.shortSide: Int get() = minOf(width, height)
}
