package io.github.lorddetson.piplayer.covers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.os.Process
import io.github.lorddetson.piplayer.library.songs.AudioPermission

/**
 * Who may open covers (docs/library.md §13.8.3): the callers that may read the library through the session
 * (docs/playback.md §1.4), PiPlayer itself, the callers Android trusts for media control, and apps whose uid holds the
 * audio permission.
 */
object CoverAccess {

    /** A caller of the provider: its uid and pid from the binder, and the package it names, if any. */
    data class Caller(val uid: Int, val pid: Int, val packageName: String?)

    /** The rule itself; [holdsAudio] answers for a uid, [trusted] for the whole caller. */
    fun allows(caller: Caller, ownUid: Int, holdsAudio: (Int) -> Boolean, trusted: (Caller) -> Boolean): Boolean =
        caller.uid == ownUid || holdsAudio(caller.uid) || trusted(caller)

    /** Whether [caller] may open covers on this device. */
    fun allows(context: Context, caller: Caller): Boolean = allows(
        caller,
        Process.myUid(),
        holdsAudio = { uid -> context.checkPermission(AudioPermission.name, -1, uid) == PackageManager.PERMISSION_GRANTED },
        trusted = { trusted(context, it) },
    )

    /** As Media3 asks it of a controller: by package, pid and uid together, so that a claimed name is not enough. */
    private fun trusted(context: Context, caller: Caller): Boolean {
        // Without a package the uid alone decides: the system, or the media control permission.
        val packageName = caller.packageName ?: return caller.uid == Process.SYSTEM_UID ||
            context.checkPermission(Manifest.permission.MEDIA_CONTENT_CONTROL, caller.pid, caller.uid) == PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return false
        return try {
            manager.isTrustedForMediaControl(MediaSessionManager.RemoteUserInfo(packageName, caller.pid, caller.uid))
        } catch (_: RuntimeException) {
            // A package that is not the caller's, or a system that cannot tell now: not trusted.
            false
        }
    }
}
