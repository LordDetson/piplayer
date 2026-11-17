package io.github.lorddetson.piplayer.identification.batch

import androidx.work.Constraints
import androidx.work.NetworkType

/**
 * The WorkManager constraints of the batch jobs (TI §8): battery not low for both phases, and the network type of
 * phase (b) from the batch network choices. Phase (a) is local (stage 0 reads and fingerprints, no requests), so its
 * jobs take no network constraint.
 */

/**
 * The network type of a batch's lookup jobs (TI §8, §11.1): `UNMETERED` while "Run batches on unmetered networks only"
 * is on (the default), `NOT_ROAMING` after the waiting batch's "Use mobile data this time", and `CONNECTED` once the
 * user allowed roaming. A batch makes no network requests while roaming unless the user allows it, so the one-off
 * answers win only over the roaming fallback, never over the unmetered-only setting.
 *
 * Only [unmeteredOnly] is stored and read in M4a step 2; the one-off answers arrive with the Fix tab (TODO M4a step 6),
 * where a waiting batch offers them.
 */
fun batchNetworkType(unmeteredOnly: Boolean, useMobileData: Boolean, allowRoaming: Boolean): NetworkType = when {
    unmeteredOnly -> NetworkType.UNMETERED
    useMobileData && !allowRoaming -> NetworkType.NOT_ROAMING
    else -> NetworkType.CONNECTED
}

/** The constraints of phase (a) (TI §8): battery not low; no network, nothing is sent. */
fun scanConstraints(): Constraints = Constraints.Builder()
    .setRequiresBatteryNotLow(true)
    .build()

/** The constraints of phase (b) (TI §8): battery not low and the network [batchNetworkType] chooses. */
fun lookupConstraints(unmeteredOnly: Boolean, useMobileData: Boolean = false, allowRoaming: Boolean = false): Constraints =
    Constraints.Builder()
        .setRequiredNetworkType(batchNetworkType(unmeteredOnly, useMobileData, allowRoaming))
        .setRequiresBatteryNotLow(true)
        .build()
