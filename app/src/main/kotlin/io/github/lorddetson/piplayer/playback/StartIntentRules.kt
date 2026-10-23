package io.github.lorddetson.piplayer.playback

import android.view.KeyEvent

/**
 * Which starts of the playback service meet the foreground service deadline by themselves, and which are foreign or
 * malformed and have to be stopped safely instead (docs/playback.md §4.6). The actions and extras are Media3's.
 */
object StartIntentRules {

    const val MEDIA_BUTTON_ACTION = "android.intent.action.MEDIA_BUTTON"
    const val MEDIA3_CUSTOM_ACTION = "androidx.media3.session.CUSTOM_NOTIFICATION_ACTION"
    const val MEDIA3_SELF_UID_EXTRA = "androidx.media3.session.intent.uid"

    enum class Kind { MEDIA_BUTTON, CUSTOM_ACTION, SELF_START, FOREIGN }

    /** [action] and whether the intent has Media3's self-start UID extra. A null action without the extra is foreign. */
    fun classify(action: String?, hasSelfUid: Boolean): Kind = when {
        action == MEDIA_BUTTON_ACTION -> Kind.MEDIA_BUTTON
        action == MEDIA3_CUSTOM_ACTION -> Kind.CUSTOM_ACTION
        action == null && hasSelfUid -> Kind.SELF_START
        else -> Kind.FOREIGN
    }

    /** The keys Media3's default session handles; any other media key would leave the service without a foreground. */
    fun isHandledMediaKey(keyCode: Int): Boolean = keyCode in HANDLED_KEYS

    private val HANDLED_KEYS = intArrayOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_STOP,
    )
}
