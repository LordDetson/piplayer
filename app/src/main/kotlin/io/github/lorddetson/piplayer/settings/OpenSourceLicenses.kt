package io.github.lorddetson.piplayer.settings

import android.content.Context
import android.util.Log
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The open-source licence list the build writes into the assets (docs/library.md §14.2). */
@Serializable
data class LicenseList(
    val libraries: List<LicensedModule>,
    val native: List<NativeComponent>,
    val kinds: List<LicenseKindText>,
)

/** A module of the runtime classpath with the SPDX ids of its licences and, for some kinds, its copyright notice. */
@Serializable
data class LicensedModule(
    val group: String,
    val name: String,
    val version: String,
    val licenses: List<String>,
    val notice: String? = null,
)

/** A native component with its licence and its own licence files (asset paths). */
@Serializable
data class NativeComponent(val name: String, val version: String, val license: String, val texts: List<String>)

/** A licence kind: its SPDX id, the title shown, and the asset path of its text. */
@Serializable
data class LicenseKindText(val id: String, val title: String, val text: String)

/** Reads the licence list and texts from the assets (docs/library.md §14.3). */
object OpenSourceLicenses {

    /** The list's asset path. */
    const val LIST = "licenses/dependencies.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): LicenseList = json.decodeFromString(LicenseList.serializer(), text)

    /** The list, or null when it cannot be read; call off the main thread. */
    fun read(context: Context): LicenseList? = try {
        parse(context.assets.open(LIST).use { it.reader().readText() })
    } catch (e: IOException) {
        Log.w(TAG, "The licence list cannot be read", e)
        null
    } catch (e: SerializationException) {
        Log.w(TAG, "The licence list cannot be read", e)
        null
    }

    /** The text at the asset [path], or null when it cannot be read; call off the main thread. */
    fun text(context: Context, path: String): String? = try {
        context.assets.open(path).use { it.reader().readText() }
    } catch (e: IOException) {
        Log.w(TAG, "The licence text $path cannot be read", e)
        null
    }

    private const val TAG = "OpenSourceLicenses"
}
