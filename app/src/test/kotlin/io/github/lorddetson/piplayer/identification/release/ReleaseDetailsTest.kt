package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.identification.musicbrainz.CoverArtArchiveDto
import io.github.lorddetson.piplayer.identification.release.ReleaseFixtures.lookup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** TI §4.2 step 4 and §5.1: the track on a looked-up release and the album data taken from it. */
class ReleaseDetailsTest {

    private val liveRecording = "cc7867d1-34a5-4bdd-a9b5-faff27ce77fb"
    private val harbor = lookup("release-harbor-lights.json")
    private val various = lookup("release-various-artists.json")

    // The track (TI §4.2 step 4).

    @Test
    fun `a recording on two tracks takes the one at the track number tag, otherwise the first`() {
        val recording = FakeMusicBrainz.HARBOR_RECORDING
        assertThat(ReleaseTracks.choose(harbor, recording, 11)!!.at).isEqualTo(TrackPosition(1, 11))
        assertThat(ReleaseTracks.choose(harbor, recording, 3)!!.at).isEqualTo(TrackPosition(1, 3))
        assertThat(ReleaseTracks.choose(harbor, recording, null)!!.at).isEqualTo(TrackPosition(1, 3))
        assertThat(ReleaseTracks.choose(harbor, recording, 7)!!.at).isEqualTo(TrackPosition(1, 3))
    }

    @Test
    fun `a release without the recording has no track for it`() {
        assertThat(ReleaseTracks.choose(harbor, liveRecording, null)).isNull()
        assertThat(ReleaseDetailsExtractor.extract(harbor, liveRecording, null, 214_000)).isNull()
    }

    @Test
    fun `positions come from position, never from the printed number`() {
        val vinyl = lookup("release-vinyl.json")
        val tracks = ReleaseTracks.of(vinyl)
        assertThat(tracks.first().track.number).isEqualTo("A1")
        assertThat(tracks.map { it.at.position }).isEqualTo((1..15).toList())
        // Disc 2 position 2 is printed "7", because the compilation numbers on across its discs.
        val onDisc2 = ReleaseTracks.choose(various, FakeMusicBrainz.HARBOR_RECORDING, null)!!
        assertThat(onDisc2.track.number).isEqualTo("7")
        assertThat(onDisc2.at).isEqualTo(TrackPosition(2, 2))
    }

    @Test
    fun `the track number tag picks a position on any disc, discs in order`() {
        val tracks = ReleaseTracks.of(various)
        assertThat(tracks.map { it.at }).containsExactly(
            TrackPosition(1, 1), TrackPosition(1, 2), TrackPosition(1, 3), TrackPosition(1, 4), TrackPosition(1, 5),
            TrackPosition(2, 1), TrackPosition(2, 2), TrackPosition(2, 3),
        )
    }

    // The details table (TI §5.1).

    @Test
    fun `a recorded single-disc release`() {
        val details = ReleaseDetailsExtractor.extract(lookup("release-lookup.json"), liveRecording, 9, 280_893)!!

        assertThat(details.releaseId).isEqualTo("157cb7bb-217f-49d4-a305-64e49df5a0a7")
        assertThat(details.releaseTitle).isEqualTo("Live Magic")
        assertThat(details.releaseGroupId).isEqualTo("c88dba71-c542-3424-b746-05f6c8f3e060")
        assertThat(details.status).isEqualTo("Official")
        assertThat(details.country).isEqualTo("JP")
        assertThat(details.date).isEqualTo("2019-04-17")
        assertThat(details.originalDate).isEqualTo("1986-12-01")
        assertThat(details.originalYear).isEqualTo(1986)
        assertThat(details.releaseArtist).isEqualTo("Queen")
        assertThat(details.releaseArtistSort).isEqualTo("Queen")
        assertThat(details.releaseCredits.single().artistId).isEqualTo("0383dadf-2a4e-4d10-a46a-e9e041da8eb3")
        assertThat(details.trackId).isEqualTo(ReleaseTracks.of(lookup("release-lookup.json"))[8].track.id)
        assertThat(details.recordingId).isEqualTo(liveRecording)
        assertThat(details.title).isEqualTo("Bohemian Rhapsody")
        assertThat(details.trackArtist).isEqualTo("Queen")
        assertThat(details.at).isEqualTo(TrackPosition(1, 9))
        assertThat(details.trackTotal).isEqualTo(15)
        assertThat(details.discTotal).isEqualTo(1)
        assertThat(details.discSubtitle).isNull()
        assertThat(details.labels).containsExactly("Virgin")
        assertThat(details.catalogNumbers).containsExactly("UICY-15820")
        assertThat(details.barcode).isEqualTo("4988031330043")
        assertThat(details.isrcs).containsExactly("GBCEE8600016")
        assertThat(details.compilation).isFalse()
        assertThat(details.lengthMs).isEqualTo(280_000)
        assertThat(details.lengthDiffers).isFalse()
        assertThat(details.coverArt).isEqualTo(CoverArtArchiveDto(count = 0, front = false))
    }

    @Test
    fun `a Various Artists compilation on two titled discs`() {
        val details = ReleaseDetailsExtractor.extract(various, FakeMusicBrainz.HARBOR_RECORDING, null, 214_000)!!

        assertThat(details.compilation).isTrue()
        assertThat(details.releaseArtist).isEqualTo("Various Artists")
        assertThat(details.trackArtist).isEqualTo("The Quiet Harbors feat. Ada Vale")
        assertThat(details.trackArtistSort).isEqualTo("Quiet Harbors, The feat. Vale, Ada")
        assertThat(details.trackCredits).containsExactly(
            Credit("The Quiet Harbors", "The Quiet Harbors", "22222222-2222-4222-8222-222222222222", "Quiet Harbors, The", " feat. "),
            Credit("Ada Vale", "Ada Vale", "33333333-3333-4333-8333-333333333333", "Vale, Ada", ""),
        )
        assertThat(details.at).isEqualTo(TrackPosition(2, 2))
        assertThat(details.trackTotal).isEqualTo(3)
        assertThat(details.discTotal).isEqualTo(2)
        assertThat(details.discSubtitle).isEqualTo("Morning")
        assertThat(details.labels).containsExactly("Ferry Music", "Harbor Sound")
        assertThat(details.catalogNumbers).containsExactly("FM-01", "HS 7")
        assertThat(details.barcode).isEqualTo("5012345678900")
        assertThat(details.isrcs).containsExactly("XXA001900001", "XXA001900002")
        assertThat(details.originalYear).isEqualTo(2021)
        assertThat(details.country).isEqualTo("GB")
        assertThat(details.coverArt!!.front).isTrue()
    }

    @Test
    fun `a track 16 s longer than the file is flagged, one within 7 s is not`() {
        val various = ReleaseDetailsExtractor.extract(various, FakeMusicBrainz.HARBOR_RECORDING, null, 214_000)!!
        assertThat(various.lengthMs).isEqualTo(230_000)
        assertThat(various.lengthDiffers).isTrue()
        assertThat(ReleaseDetailsExtractor.extract(this.various, FakeMusicBrainz.HARBOR_RECORDING, null, 223_000)!!.lengthDiffers)
            .isFalse()
        assertThat(ReleaseDetailsExtractor.extract(this.various, FakeMusicBrainz.HARBOR_RECORDING, null, 222_999)!!.lengthDiffers)
            .isTrue()
        assertThat(ReleaseDetailsExtractor.extract(this.various, FakeMusicBrainz.HARBOR_RECORDING, null, null)!!.lengthDiffers)
            .isFalse()
    }

    @Test
    fun `an empty barcode, a none catalog number and a blank disc title give no value`() {
        val details = ReleaseDetailsExtractor.extract(harbor, FakeMusicBrainz.HARBOR_RECORDING, 11, 214_000)!!
        assertThat(details.barcode).isNull()
        assertThat(details.labels).containsExactly("Pier Records", "Lantern Songs")
        assertThat(details.catalogNumbers).containsExactly("PIER-001")
        assertThat(details.discSubtitle).isNull()
        assertThat(details.at).isEqualTo(TrackPosition(1, 11))
        assertThat(details.title).isEqualTo("Paper Lanterns (reprise)")
        assertThat(details.compilation).isFalse()
        assertThat(details.releaseArtist).isEqualTo("The Quiet Harbors")
        assertThat(details.releaseArtistSort).isEqualTo("Quiet Harbors, The")
    }

    @Test
    fun `the original year comes from the release group, apart from the release date`() {
        val details = ReleaseDetailsExtractor.extract(harbor, FakeMusicBrainz.HARBOR_RECORDING, null, 214_000)!!
        assertThat(details.date).isEqualTo("2019-05")
        assertThat(details.originalDate).isEqualTo("2018-11-30")
        assertThat(details.originalYear).isEqualTo(2018)
    }

    @Test
    fun `without a track length the recording's length counts`() {
        val vinyl = lookup("release-vinyl.json")
        val first = ReleaseTracks.of(vinyl).first()
        assertThat(first.track.length).isNull()
        val details = ReleaseDetailsExtractor.extract(vinyl, first.recordingId!!, null, 308_000)!!
        assertThat(details.lengthMs).isEqualTo(first.track.recording!!.length)
        assertThat(details.barcode).isNull()
    }

    @Test
    fun `years are read from MusicBrainz dates`() {
        assertThat(ReleaseDetailsExtractor.year("1975")).isEqualTo(1975)
        assertThat(ReleaseDetailsExtractor.year("1975-11")).isEqualTo(1975)
        assertThat(ReleaseDetailsExtractor.year("1975-11-21")).isEqualTo(1975)
        assertThat(ReleaseDetailsExtractor.year("")).isNull()
        assertThat(ReleaseDetailsExtractor.year(null)).isNull()
        assertThat(ReleaseDetailsExtractor.year("????")).isNull()
    }

    @Test
    fun `a credit without a sort name sorts by its credited name`() {
        val credits = ReleaseDetailsExtractor.credits(
            listOf(io.github.lorddetson.piplayer.identification.musicbrainz.NameCreditDto(name = "Mira", joinphrase = " & ")),
        )
        assertThat(credits.single()).isEqualTo(Credit("Mira", "Mira", null, "Mira", " & "))
    }
}
