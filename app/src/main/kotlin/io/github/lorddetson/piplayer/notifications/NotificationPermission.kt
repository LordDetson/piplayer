package io.github.lorddetson.piplayer.notifications

import android.Manifest
import android.os.Build

/**
 * POST_NOTIFICATIONS, the permission a batch asks for (TI §8, PIM-21): from API 33 on, and nothing before it, where every
 * app may post. Its name is read here rather than at each use, as the library reads its own audio permission.
 */
object NotificationPermission {

    /** The permission's name on API 33 and later; empty before, where there is nothing to ask for. */
    val name: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else ""
}
