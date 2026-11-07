package io.github.lorddetson.piplayer.settings

import kotlinx.serialization.Serializable

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

/** Reads the licence list. */
object OpenSourceLicenses {

    /** The list's asset path. */
    const val LIST = "licenses/dependencies.json"

    fun parse(json: String): LicenseList = TODO("step 14")
}
