package io.github.lorddetson.piplayer.userdata

import io.github.lorddetson.piplayer.identification.contract.LicenseClass
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.release.Credit
import io.github.lorddetson.piplayer.identification.release.ReleaseDetails
import io.github.lorddetson.piplayer.identification.release.TrackPosition
import io.github.lorddetson.piplayer.identification.score.HintField
import io.github.lorddetson.piplayer.identification.score.Reason
import io.github.lorddetson.piplayer.identification.score.Source
import io.github.lorddetson.piplayer.identification.score.Tier
import io.github.lorddetson.piplayer.library.values.Decided
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecidedField.ALBUM
import io.github.lorddetson.piplayer.library.values.DecidedField.ARTIST
import io.github.lorddetson.piplayer.library.values.DecidedField.DISC_SUBTITLE
import io.github.lorddetson.piplayer.library.values.DecidedField.TITLE
import io.github.lorddetson.piplayer.library.values.DecidedField.TRACK_TOTAL
import io.github.lorddetson.piplayer.library.values.DecisionContent
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.library.values.PartKind
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.settings.ProtectedField
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The stored forms of user database version 6 and the "changed in the file" references (TI §7.1, §10.2). */
class DecisionCodecTest {

    private val mbid = "0b8a3a2e-1111-4222-8333-444455556666"
    private val other = "1c9b4b3f-2222-4333-8444-555566667777"

    @Test
    fun `every kind of part round-trips, MBIDs as 16 bytes each and the rest as UTF-8`() {
        val samples = mapOf(
            PartKind.TEXT to "Ёжик в тумане",
            PartKind.LIST to DecidedField.list(listOf("A", "Б")),
            PartKind.CREDITS to DecidedField.credits(listOf("Ann" to " & ", "Bob" to "")),
            PartKind.NUMBER to "12",
            PartKind.FLAG to "1",
            PartKind.MBID to mbid,
            PartKind.MBID_LIST to DecidedField.list(listOf(mbid, other)),
            PartKind.COVER to "42",
        )
        for (field in DecidedField.entries) {
            val text = samples.getValue(field.kind)
            assertThat(DecisionCodec.decode(field, DecisionCodec.encode(field, text))).describedAs(field.stored).isEqualTo(text)
            assertThat(DecisionCodec.encode(field, null)).isNull()
            assertThat(DecisionCodec.decode(field, null)).isNull()
        }
        assertThat(DecisionCodec.encode(DecidedField.RECORDING_MBID, mbid)).hasSize(16)
        assertThat(DecisionCodec.encode(DecidedField.ARTIST_IDS, DecidedField.list(listOf(mbid, other)))).hasSize(32)
        assertThat(DecisionCodec.encode(TITLE, "Ёж")).isEqualTo("Ёж".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `an MBID part drops what is not an MBID and an empty text is stored as no value`() {
        assertThat(DecisionCodec.encode(DecidedField.RECORDING_MBID, "not an id")).isNull()
        assertThat(DecisionCodec.decode(DecidedField.ARTIST_IDS, DecisionCodec.encode(DecidedField.ARTIST_IDS, DecidedField.list(listOf("x", mbid)))))
            .isEqualTo(mbid)
        assertThat(DecisionCodec.encode(TITLE, "")).isNull()
    }

    @Test
    fun `the fields are bits in the order of the protected-fields list`() {
        assertThat(DecisionCodec.bits(setOf(ProtectedField.TITLE))).isEqualTo(1)
        assertThat(DecisionCodec.bits(setOf(ProtectedField.ARTIST, ProtectedField.COVER))).isEqualTo(2 or (1 shl 15))
        assertThat(DecisionCodec.fields(DecisionCodec.bits(ProtectedField.entries.toSet()))).isEqualTo(ProtectedField.entries.toSet())
        // A bit of a later build is left out.
        assertThat(DecisionCodec.fields(1 or (1 shl 20))).containsExactly(ProtectedField.TITLE)
    }

    @Test
    fun `providers, license classes and confidences have their codes`() {
        assertThat(ProviderId.entries.map { DecisionCodec.provider(DecisionCodec.providerCode(it)) }).isEqualTo(ProviderId.entries)
        assertThat(DecisionCodec.providerCode(ProviderId.MUSICBRAINZ)).isEqualTo(1)
        assertThat(DecisionCodec.providerCode(ProviderId.ACOUSTID)).isEqualTo(2)
        assertThat(DecisionCodec.providerCode(ProviderId.COVER_ART_ARCHIVE)).isEqualTo(3)
        assertThat(LicenseClass.entries.map { DecisionCodec.license(DecisionCodec.licenseCode(it)) }).isEqualTo(LicenseClass.entries)
        assertThat(DecisionCodec.licenseCode(LicenseClass.CC0)).isEqualTo(1)
        assertThat(DecisionCodec.provider(99)).isNull()
        assertThat(DecisionCodec.confidenceCode(0.9649)).isEqualTo(965)
        assertThat(DecisionCodec.confidence(965)).isEqualTo(0.965)
        assertThat(DecisionKind.entries.map { it.code }).containsExactly(1, 2, 3, 4, 5)
    }

    @Test
    fun `a reason round-trips in a short text`() {
        val reasons = listOf(
            Reason(Source.FINGERPRINT, Tier.IDENTIFIER, setOf(Source.FINGERPRINT, Source.TEXT_SEARCH), 0.97, -1_200, 0.92, 0.875, emptySet()),
            Reason(Source.TEXT_SEARCH, Tier.SIMILARITY, setOf(Source.TEXT_SEARCH), null, null, null, null, setOf(HintField.TITLE, HintField.ARTIST)),
            Reason(Source.ISRC, Tier.SIMILARITY, setOf(Source.ISRC, Source.TAG_RECORDING_ID), null, 0, 0.5, null, setOf(HintField.ARTIST)),
        )
        for (reason in reasons) {
            val text = DecisionCodec.reason(reason)
            assertThat(DecisionCodec.reason(text)).isEqualTo(reason)
            assertThat(text.length).isLessThanOrEqualTo(40)
        }
        assertThat(DecisionCodec.reason("garbage")).isNull()
        assertThat(DecisionCodec.reason(null as String?)).isNull()
    }

    @Test
    fun `release values round-trip through gzip JSON and pick their disc`() {
        val values = ReleaseValues(
            mapOf(ALBUM to "Record", DecidedField.LABEL to DecidedField.list(listOf("L1", "L2")), DecidedField.BARCODE to null),
            mapOf(1 to mapOf(TRACK_TOTAL to "9", DISC_SUBTITLE to null), 2 to mapOf(TRACK_TOTAL to "7", DISC_SUBTITLE to "Live")),
        )

        assertThat(ReleaseValues.decode(values.encode())).isEqualTo(values)
        assertThat(values.forDisc(2)).containsEntry(TRACK_TOTAL, "7").containsEntry(DISC_SUBTITLE, "Live").containsEntry(ALBUM, "Record")
        assertThat(values.forDisc(3)).doesNotContainKey(TRACK_TOTAL).containsEntry(ALBUM, "Record")
        assertThat(values.forDisc(null)).doesNotContainKey(TRACK_TOTAL)
    }

    @Test
    fun `release values share a row when all but the discs agree and no disc differs`() {
        val one = ReleaseValues(mapOf(ALBUM to "Record"), mapOf(1 to mapOf(TRACK_TOTAL to "9")))
        val two = ReleaseValues(mapOf(ALBUM to "Record"), mapOf(2 to mapOf(TRACK_TOTAL to "7")))

        assertThat(one.merged(two)).isEqualTo(ReleaseValues(mapOf(ALBUM to "Record"), mapOf(1 to mapOf(TRACK_TOTAL to "9"), 2 to mapOf(TRACK_TOTAL to "7"))))
        assertThat(one.merged(one)).isEqualTo(one)
        assertThat(one.merged(ReleaseValues(mapOf(ALBUM to "Other"), emptyMap()))).isNull()
        assertThat(one.merged(ReleaseValues(mapOf(ALBUM to "Record"), mapOf(1 to mapOf(TRACK_TOTAL to "10"))))).isNull()
    }

    @Test
    fun `release values of a part map keep only the release-level parts, the per-disc ones under the disc`() {
        val values = ReleaseValues.of(mapOf(TITLE to "Song", ALBUM to "Record", TRACK_TOTAL to "9", DecidedField.TRACK_NUMBER to "3"), disc = 2)

        assertThat(values).isEqualTo(ReleaseValues(mapOf(ALBUM to "Record"), mapOf(2 to mapOf(TRACK_TOTAL to "9"))))
    }

    private val details = ReleaseDetails(
        releaseId = mbid, releaseTitle = "Record", releaseGroupId = other, status = "Official", country = "GB",
        date = "2001-05-04", originalDate = "1999", originalYear = 1999,
        releaseCredits = listOf(Credit("Ann", "Ann", "aaaaaaaa-1111-4222-8333-444455556666", "Ann, sort", "")),
        trackId = "bbbbbbbb-1111-4222-8333-444455556666", recordingId = "cccccccc-1111-4222-8333-444455556666", title = "Song",
        trackCredits = listOf(
            Credit("Ann", "Ann", "aaaaaaaa-1111-4222-8333-444455556666", "Ann, sort", " feat. "),
            Credit("Bobby", "Bob", null, "Bob, sort", ""),
        ),
        at = TrackPosition(disc = 2, position = 5), trackTotal = 11, discTotal = 2, discSubtitle = null,
        labels = listOf("Label"), catalogNumbers = emptyList(), barcode = "123", isrcs = listOf("GBAAA0000001", "GBAAA0000002"),
        compilation = false, lengthMs = 200_000, lengthDiffers = false, coverArt = null,
    )

    @Test
    fun `an identification gives every part, empty ones included`() {
        val parts = DecisionContent.ofRelease(details, savedCoverId = 4)

        assertThat(parts.keys).containsExactlyInAnyOrderElementsOf(DecidedField.entries)
        assertThat(parts[TITLE]).isEqualTo("Song")
        assertThat(parts[ARTIST]).isEqualTo("Ann feat. Bobby")
        assertThat(DecidedField.creditedNames(parts[DecidedField.ARTIST_CREDITS])).containsExactly("Ann", "Bobby")
        assertThat(DecidedField.printed(parts[DecidedField.ARTIST_CREDITS])).isEqualTo("Ann feat. Bobby")
        assertThat(parts[DecidedField.ARTIST_SORT]).isEqualTo("Ann, sort feat. Bob, sort")
        assertThat(parts[ALBUM]).isEqualTo("Record")
        assertThat(parts[DecidedField.ALBUM_ARTIST]).isEqualTo("Ann")
        assertThat(parts[DecidedField.DATE]).isEqualTo("2001-05-04")
        assertThat(parts[DecidedField.ORIGINAL_DATE]).isEqualTo("1999")
        assertThat(parts[DecidedField.TRACK_NUMBER]).isEqualTo("5")
        assertThat(parts[TRACK_TOTAL]).isEqualTo("11")
        assertThat(parts[DecidedField.DISC_NUMBER]).isEqualTo("2")
        assertThat(parts[DecidedField.DISC_TOTAL]).isEqualTo("2")
        assertThat(parts[DISC_SUBTITLE]).isNull()
        assertThat(parts[DecidedField.COMPILATION]).isEqualTo("0")
        assertThat(parts[DecidedField.LABEL]).isEqualTo("Label")
        assertThat(parts[DecidedField.CATALOG_NUMBER]).isNull()
        assertThat(DecidedField.items(parts[DecidedField.ISRC])).containsExactly("GBAAA0000001", "GBAAA0000002")
        assertThat(parts[DecidedField.RECORDING_MBID]).isEqualTo(details.recordingId)
        assertThat(parts[DecidedField.RELEASE_MBID]).isEqualTo(mbid)
        assertThat(parts[DecidedField.RELEASE_GROUP_MBID]).isEqualTo(other)
        assertThat(parts[DecidedField.RELEASE_TRACK_MBID]).isEqualTo(details.trackId)
        // Credits without an ID give none.
        assertThat(DecidedField.items(parts[DecidedField.ARTIST_IDS])).containsExactly("aaaaaaaa-1111-4222-8333-444455556666")
        assertThat(parts[DecidedField.ALBUM_ARTIST_IDS]).isEqualTo("aaaaaaaa-1111-4222-8333-444455556666")
        assertThat(parts[DecidedField.COVER]).isEqualTo("4")
        assertThat(DecisionContent.ofRelease(details)[DecidedField.COVER]).isNull()
    }

    @Test
    fun `a file's tags give their parts as text`() {
        val tags = TrackTags.from(
            mapOf(
                "TITLE" to listOf("t"), "ARTIST" to listOf("a"), "TRACKNUMBER" to listOf("3/9"), "COMPILATION" to listOf("1"),
                "MUSICBRAINZ_TRACKID" to listOf(mbid),
            ),
        )

        val parts = DecisionContent.ofTags(tags)

        assertThat(parts[TITLE]).isEqualTo("t")
        assertThat(parts[ARTIST]).isEqualTo("a")
        assertThat(parts[DecidedField.TRACK_NUMBER]).isEqualTo("3")
        assertThat(parts[TRACK_TOTAL]).isEqualTo("9")
        assertThat(parts[DecidedField.COMPILATION]).isEqualTo("1")
        assertThat(parts[DecidedField.RECORDING_MBID]).isEqualTo(mbid)
        assertThat(parts[ALBUM]).isNull()
        assertThat(parts).doesNotContainKey(DecidedField.COVER)
        assertThat(DecisionContent.ofTags(null).values).allSatisfy { assertThat(it).isNull() }
    }

    // "Changed in the file", per location (TI §7.1).

    private fun decided(vararg values: Pair<DecidedField, String?>, decision: Long = 1) =
        values.associate { (part, value) -> part to Decided(value, DecisionKind.LEVEL_IDENTIFICATION, decision) }

    @Test
    fun `a reference is a CRC-32 of the text, an empty value 0`() {
        val crc = java.util.zip.CRC32().apply { update("Song".toByteArray(Charsets.UTF_8)) }.value.toInt()
        assertThat(FileReferences.hash("Song")).isEqualTo(crc)
        assertThat(FileReferences.hash(null)).isZero()
        assertThat(FileReferences.hash("")).isZero()
    }

    @Test
    fun `only parts whose file value differs from the decided one get a reference, and references round-trip`() {
        val references = FileReferences.of(
            decided = mapOf(TITLE to "Song", ARTIST to "Ann", ALBUM to "Record"),
            file = mapOf(TITLE to "track07", ARTIST to "Ann", ALBUM to null),
        )

        assertThat(references).isEqualTo(mapOf(TITLE to FileReferences.hash("track07"), ALBUM to 0))
        assertThat(FileReferences.decode(FileReferences.encode(references))).isEqualTo(references)
        // A mask of the parts whose file value was empty, then a code and a hash for each other part.
        assertThat(FileReferences.encode(references)).hasSize(4 + 5)
        assertThat(FileReferences.encode(emptyMap())).isNull()
        assertThat(FileReferences.decode(null)).isEmpty()
    }

    @Test
    fun `a file without tags keeps its references in the 4 bytes of the mask`() {
        val decided = DecidedField.entries.associateWith { "value ${it.code}" }

        val references = FileReferences.of(decided, DecisionContent.ofTags(null))

        assertThat(references.keys).containsExactlyInAnyOrderElementsOf(DecidedField.entries - DecidedField.COVER)
        assertThat(FileReferences.encode(references)).hasSize(4)
        assertThat(FileReferences.decode(FileReferences.encode(references))).isEqualTo(references)
    }

    @Test
    fun `a field whose file value changed after the decision is flagged, per location`() {
        val winners = decided(TITLE to "Song", ARTIST to "Ann")
        // At the decision the file had "track07" as its title and the decided artist.
        val atDecision = mapOf(1L to FileReferences.of(mapOf(TITLE to "Song", ARTIST to "Ann"), mapOf(TITLE to "track07", ARTIST to "Ann")))

        // The decision's location, unchanged: nothing.
        assertThat(FileReferences.changed(winners, atDecision, emptyMap(), mapOf(TITLE to "track07", ARTIST to "Ann"))).isEmpty()
        // A tagger changed the title there.
        assertThat(FileReferences.changed(winners, atDecision, emptyMap(), mapOf(TITLE to "Other", ARTIST to "Ann"))).containsExactly(TITLE)
        // A tagger changed the artist, which had the decided value.
        assertThat(FileReferences.changed(winners, atDecision, emptyMap(), mapOf(TITLE to "track07", ARTIST to "Bob"))).containsExactly(ARTIST)
        // A copy elsewhere had other values at the decision: its own references count, not the decision's.
        val copy = FileReferences.of(mapOf(TITLE to "Song", ARTIST to "Ann"), mapOf(TITLE to "copy title", ARTIST to "Ann"))
        assertThat(FileReferences.changed(winners, atDecision, copy, mapOf(TITLE to "copy title", ARTIST to "Ann"))).isEmpty()
        assertThat(FileReferences.changed(winners, atDecision, copy, mapOf(TITLE to "track07", ARTIST to "Ann"))).containsExactly(TITLE)
    }

    @Test
    fun `a moved file whose tags stayed the same is not flagged`() {
        val winners = decided(TITLE to "Song")
        val atDecision = mapOf(1L to FileReferences.of(mapOf(TITLE to "Song"), mapOf(TITLE to "track07")))

        // A location without references of its own compares with the decision's.
        assertThat(FileReferences.changed(winners, atDecision, emptyMap(), mapOf(TITLE to "track07"))).isEmpty()
    }

    @Test
    fun `Keep PiPlayer's value takes the present value as the location's reference`() {
        val winners = decided(TITLE to "Song")
        val atDecision = mapOf(1L to FileReferences.of(mapOf(TITLE to "Song"), mapOf(TITLE to "track07")))
        val now = mapOf(TITLE to "Changed")
        assertThat(FileReferences.changed(winners, atDecision, emptyMap(), now)).containsExactly(TITLE)

        val kept = mapOf(TITLE to FileReferences.hash("Changed"))

        assertThat(FileReferences.changed(winners, atDecision, kept, now)).isEmpty()
    }

    @Test
    fun `a decision without a reference for a part compares with the decided value, and the cover is never compared`() {
        val winners = decided(TITLE to "Song", DecidedField.COVER to "4", decision = 2)

        assertThat(FileReferences.changed(winners, emptyMap(), emptyMap(), mapOf(TITLE to "Song"))).isEmpty()
        assertThat(FileReferences.changed(winners, emptyMap(), emptyMap(), mapOf(TITLE to "Other"))).containsExactly(TITLE)
    }

    @Test
    fun `the reference is that of the decision whose value wins the part now`() {
        val winners = decided(TITLE to "Newer", decision = 2)
        val references = mapOf(
            1L to mapOf(TITLE to FileReferences.hash("old file title")),
            2L to mapOf(TITLE to FileReferences.hash("file title at the newer decision")),
        )

        assertThat(FileReferences.changed(winners, references, emptyMap(), mapOf(TITLE to "file title at the newer decision"))).isEmpty()
        assertThat(FileReferences.changed(winners, references, emptyMap(), mapOf(TITLE to "old file title"))).containsExactly(TITLE)
    }
}
