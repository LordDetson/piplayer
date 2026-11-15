package io.github.lorddetson.piplayer.identification.release

/**
 * The releases already confirmed for other files of a folder, the first key of the release ranking (TI §4.2 step 2,
 * album consistency). The real hint reads the decisions (M3 step 16); until then [NoFolderReleaseHint] stands in.
 */
fun interface FolderReleaseHint {

    /** The release MBIDs confirmed in [folder]; [folder] is the file's folder as the library stores it. */
    suspend fun confirmedReleaseIds(folder: String?): Set<String>
}

/** No folder knows a release yet. */
object NoFolderReleaseHint : FolderReleaseHint {
    override suspend fun confirmedReleaseIds(folder: String?): Set<String> = emptySet()
}
