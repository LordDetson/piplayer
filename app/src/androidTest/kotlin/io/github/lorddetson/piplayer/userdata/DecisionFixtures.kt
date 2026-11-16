package io.github.lorddetson.piplayer.userdata

import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.identification.contract.ApplyRequest
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.contract.LicenseClass
import io.github.lorddetson.piplayer.identification.contract.Provenance
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.release.Credit
import io.github.lorddetson.piplayer.identification.release.ReleaseDetails
import io.github.lorddetson.piplayer.identification.release.TrackPosition
import io.github.lorddetson.piplayer.identification.score.Reason
import io.github.lorddetson.piplayer.identification.score.Source
import io.github.lorddetson.piplayer.identification.score.Tier
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionContent
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.settings.ProtectedField
import java.time.Instant

/** Identification results for the decision tests, without any service (TI §7.1, §10.2). */
object DecisionFixtures {

    /** The [n]th MBID of a test: a valid UUID made of [n]. */
    fun mbid(n: Int, prefix: Int = 0xabc): String = "%08x-1111-4222-8333-%012x".format(prefix, n)

    /**
     * The tags of a file with the parts the lists' columns leave out (TI §7.1): the numbers of tracks and discs, the
     * disc subtitle, album artists with their IDs, the label and the other release IDs. The catalog number is the one
     * [details] decides, the others differ from it.
     */
    fun wideTags(title: String = "Tag 1"): Map<String, List<String>> = mapOf(
        "TITLE" to listOf(title), "ARTIST" to listOf("Tag artist"), "ALBUM" to listOf("Tag album"),
        "TRACKNUMBER" to listOf("3"), "TRACKTOTAL" to listOf("10"), "DISCNUMBER" to listOf("1"), "DISCTOTAL" to listOf("3"),
        "DISCSUBTITLE" to listOf("Tag disc"), "ALBUMARTIST" to listOf("Ann & Bob"), "ALBUMARTISTS" to listOf("Ann", "Bob"),
        "LABEL" to listOf("Tag label"), "CATALOGNUMBER" to listOf("CAT-1"), "BARCODE" to listOf("0000000000000"),
        "ISRC" to listOf("YYBBB0000001"), "MUSICBRAINZ_RELEASEGROUPID" to listOf(mbid(9, 0x6e6)),
        "MUSICBRAINZ_RELEASETRACKID" to listOf(mbid(9, 0x7c7)), "MUSICBRAINZ_ARTISTID" to listOf(mbid(9, 0xa47)),
        "MUSICBRAINZ_ALBUMARTISTID" to listOf(mbid(9, 0xa47), mbid(10, 0xa47)),
    )

    fun details(
        title: String = "Song",
        artist: String = "Ann",
        album: String = "Record",
        release: Int = 1,
        recording: Int = 100,
        disc: Int = 1,
        track: Int = 3,
        trackTotal: Int = 9,
        discSubtitle: String? = null,
    ) = ReleaseDetails(
        releaseId = mbid(release, 0x1e1), releaseTitle = album, releaseGroupId = mbid(release, 0x6e6), status = "Official",
        country = "XW", date = "2001-05-04", originalDate = "1999", originalYear = 1999,
        releaseCredits = listOf(Credit(artist, artist, mbid(1, 0xa47), "$artist, sorted", "")),
        trackId = mbid(recording, 0x7c7), recordingId = mbid(recording, 0x4ec), title = title,
        trackCredits = listOf(Credit(artist, artist, mbid(1, 0xa47), "$artist, sorted", "")),
        at = TrackPosition(disc, track), trackTotal = trackTotal, discTotal = 2, discSubtitle = discSubtitle,
        labels = listOf("Label"), catalogNumbers = listOf("CAT-1"), barcode = "0123456789012", isrcs = listOf("XXAAA0000001"),
        compilation = false, lengthMs = 200_000, lengthDiffers = false, coverArt = null,
    )

    /**
     * One identification for each of [keys] (content keys in hex), written in bulk as `Decisions.apply` stores them:
     * an identity per key, a release choice per ten tracks, the per-track rows and the provenance; the change counter
     * goes up once. The title of key i is "Decided i".
     */
    suspend fun identifyInBulk(database: UserDatabase, keys: List<String>) {
        val dao = database.decisions()
        val fields = DecisionCodec.bits(ProtectedField.entries.toSet() - ProtectedField.COVER)
        database.withWriteTransaction {
            val choices = (0..keys.size / 10).map { release ->
                val parts = DecisionContent.ofRelease(details(album = "Decided album $release", release = release + 1, recording = release + 1))
                dao.addReleaseChoice(
                    ReleaseChoiceEntity(
                        releaseMbid = Hex.mbidBytes(mbid(release + 1, 0x1e1))!!, releaseGroupMbid = null,
                        values = ReleaseValues.of(parts, disc = 1).encode(), savedCoverId = null, fetchedAt = 1_800_000_000_000L,
                    ),
                )
            }
            for ((i, key) in keys.withIndex()) {
                val identity = database.identities().add(
                    TrackIdentityEntity(
                        contentKey = Hex.bytes(key), durationMs = 200_000, recordingMbid = null, hintVolumeName = null,
                        hintRelativePath = null, hintDisplayName = null, hintSize = null, createdAt = 1, missingSince = null,
                    ),
                )
                val parts = DecisionContent.ofRelease(details(title = "Decided $i", artist = "Decided artist ${i % 300}", recording = i + 1))
                val id = dao.add(
                    DecisionEntity(
                        identityId = identity, kind = DecisionKind.APPLY.code, fields = fields, releaseChoiceId = choices[i / 10],
                        recordingMbid = Hex.mbidBytes(parts[DecidedField.RECORDING_MBID]),
                        releaseTrackMbid = Hex.mbidBytes(parts[DecidedField.RELEASE_TRACK_MBID]), confidence = 965,
                        reason = DecisionCodec.reason(reason), provider = 1, fetchedAt = 1_800_000_000_000L, licenseClass = 1,
                        createdAt = 1_800_000_000_000L + i,
                    ),
                )
                dao.addMetadata(
                    parts.filterKeys { !it.release && !it.decisionColumn }
                        .map { (part, text) -> TrackMetadataEntity(id, part.code, DecisionCodec.encode(part, text)) },
                )
            }
            database.user().countChange()
        }
    }

    val reason = Reason(Source.FINGERPRINT, Tier.IDENTIFIER, setOf(Source.FINGERPRINT), 0.97, -800, 0.95, 0.9, emptySet())

    fun request(
        contentKey: String,
        details: ReleaseDetails = details(),
        fields: Set<ProtectedField> = ProtectedField.entries.toSet() - ProtectedField.COVER,
        fileId: Long = 1,
        fetchedAt: Long = 1_800_000_000_000L,
    ) = ApplyRequest(
        target = IdentificationTarget.LibraryFile(fileId, contentKey),
        details = details,
        fields = fields,
        cover = null,
        provenance = Provenance(
            provider = ProviderId.MUSICBRAINZ,
            fetchedAt = Instant.ofEpochMilli(fetchedAt),
            licenseClass = LicenseClass.CC0,
            recordingId = details.recordingId,
            releaseTrackId = details.trackId,
            acoustIdTrackId = mbid(7, 0xac0),
            confidence = 0.965,
            reason = reason,
        ),
    )
}
