package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.identification.release.ReleaseFixtures.browse
import io.github.lorddetson.piplayer.identification.release.ReleaseFixtures.lookup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** TI §4.2 step 6 and the §11.1 M3 "Edition picker" row. */
class EditionPickerTest {

    private val recording = FakeMusicBrainz.HARBOR_RECORDING
    private val group = browse("release-group-harbor.json").releases
    private val harbor = lookup("release-harbor-lights.json")

    @Test
    fun `every release of the group is listed once, the suggested one first, then official ones`() {
        val editions = EditionPicker.editions(
            groupReleases = group,
            suggestedId = FakeMusicBrainz.HARBOR_RELEASE,
            recordingReleaseIds = setOf(FakeMusicBrainz.HARBOR_RELEASE),
            context = ReleaseContext(country = "GB"),
        )
        assertThat(editions.map { it.release.id }).containsExactly(
            FakeMusicBrainz.HARBOR_RELEASE,
            FakeMusicBrainz.HARBOR_CD_RELEASE,
            FakeMusicBrainz.HARBOR_PROMO_RELEASE,
        )
        assertThat(editions.map { it.suggested }).containsExactly(true, false, false)
        assertThat(editions.map { it.knownToContain }).containsExactly(true, false, false)
    }

    @Test
    fun `the recorded release group lists all 12 editions, the suggested one first, the others ranked`() {
        val releases = browse("release-group-browse.json").releases
        val suggested = releases.single { it.id.startsWith("b663aa15") }.id
        val ofRecording = browse("release-browse-live.json").releases.map { it.id }.toSet()
        val editions = EditionPicker.editions(releases, suggested, ofRecording, ReleaseContext(country = "GB"))

        assertThat(editions).hasSize(12)
        assertThat(editions.map { it.release.id }).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(releases.map { it.id })
        assertThat(editions.first().release.id).isEqualTo(suggested)
        // After the suggested one, the UK CD leads as it does in the recording's own ranking.
        assertThat(editions[1].release.id).startsWith("388e1d01")
        // The group's Italian release is not among the recording's releases: it needs its tracklist first.
        val notKnown = editions.filterNot { it.knownToContain }.map { it.release.id }
        assertThat(notKnown).isEqualTo(releases.map { it.id }.filterNot { it in ofRecording })
        assertThat(notKnown).isNotEmpty()
    }

    @Test
    fun `a non-official edition the ranking puts first still follows the official ones`() {
        val suggested = ReleaseFixtures.release("s", primary = "Album", date = "2010")
        // The promo is an album from 1990, so the release ranking alone puts it ahead of the official single.
        val promo = ReleaseFixtures.release("p", primary = "Album", date = "1990", status = "Promotion")
        val bootleg = ReleaseFixtures.release("b", primary = "Album", date = "1991", status = "Bootleg")
        val single = ReleaseFixtures.release("o", primary = "Single", date = "2005")
        val group = listOf(promo, bootleg, suggested, single)
        assertThat(ReleaseRanking.rank(listOf(promo, single), ReleaseContext()).first().release.id).isEqualTo("p")

        val editions = EditionPicker.editions(group, "s", setOf("s"), ReleaseContext())
        assertThat(editions.map { it.release.id }).containsExactly("s", "o", "p", "b")
    }

    @Test
    fun `an edition with the identified recording maps to its own track`() {
        val mapping = EditionPicker.map(harbor, recording, "Paper Lanterns", TrackPosition(1, 3), trackNumberTag = 11)
        assertThat((mapping as EditionMapping.SameRecording).track.at).isEqualTo(TrackPosition(1, 11))
    }

    @Test
    fun `an edition with another recording of the song maps by position and title`() {
        val mapping = EditionPicker.map(lookup("release-harbor-cd.json"), recording, "Paper Lanterns", TrackPosition(1, 3), null)
        val other = mapping as EditionMapping.OtherRecording
        assertThat(other.recordingId).isEqualTo("13131313-1313-4313-8313-131313131313")
        assertThat(other.track.at).isEqualTo(TrackPosition(1, 3))
    }

    @Test
    fun `a slightly different title still maps, a version on one side does not`() {
        val cd = lookup("release-harbor-cd.json")
        assertThat(EditionPicker.map(cd, recording, "Paper Lantern", TrackPosition(1, 3), null))
            .isInstanceOf(EditionMapping.OtherRecording::class.java)
        assertThat(EditionPicker.map(cd, recording, "Paper Lanterns (live)", TrackPosition(1, 3), null))
            .isEqualTo(EditionMapping.DoesNotContain)
    }

    @Test
    fun `an edition whose track at that position is another song does not contain this song`() {
        val promo = lookup("release-harbor-promo.json")
        assertThat(EditionPicker.map(promo, recording, "Paper Lanterns", TrackPosition(1, 3), 3))
            .isEqualTo(EditionMapping.DoesNotContain)
    }

    @Test
    fun `the same title at another position does not count`() {
        val cd = lookup("release-harbor-cd.json")
        assertThat(EditionPicker.map(cd, recording, "Paper Lanterns", TrackPosition(1, 2), null))
            .isEqualTo(EditionMapping.DoesNotContain)
        assertThat(EditionPicker.map(cd, recording, "Paper Lanterns", TrackPosition(2, 3), null))
            .isEqualTo(EditionMapping.DoesNotContain)
    }

    @Test
    fun `without the suggested position the track number tag on the first disc counts`() {
        val cd = lookup("release-harbor-cd.json")
        assertThat(EditionPicker.map(cd, recording, "Paper Lanterns", null, 3))
            .isInstanceOf(EditionMapping.OtherRecording::class.java)
        assertThat(EditionPicker.map(cd, recording, "Paper Lanterns", null, null)).isEqualTo(EditionMapping.DoesNotContain)
    }
}
