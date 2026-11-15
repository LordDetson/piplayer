package io.github.lorddetson.piplayer.identification.provider

import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdClient
import kotlin.time.Duration.Companion.milliseconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The policy flags of TI §9 and the intervals of TI §8. */
class MetadataProviderTest {

    @Test
    fun `every provider has a descriptor, in the order of the attribution line`() {
        assertThat(identificationProviders.map { it.id })
            .containsExactly(ProviderId.ACOUSTID, ProviderId.MUSICBRAINZ, ProviderId.COVER_ART_ARCHIVE)
        assertThat(identificationProviders.joinToString(" · ") { it.attribution })
            .isEqualTo("Identification: AcoustID / Chromaprint · Metadata: MusicBrainz (CC0) · Covers: Cover Art Archive")
    }

    @Test
    fun `the intervals of TI §8`() {
        assertThat(MusicBrainzProvider.minInterval).isEqualTo(1_100.milliseconds)
        assertThat(AcoustIdProvider.minInterval).isEqualTo(350.milliseconds).isEqualTo(AcoustIdClient.MIN_INTERVAL)
        assertThat(CoverArtArchiveProvider.minInterval).isEqualTo(1_000.milliseconds)
    }

    @Test
    fun `AcoustID needs a key, may be kept but not written into files, and is free for non-commercial use only`() {
        with(AcoustIdProvider) {
            assertThat(host).isEqualTo("api.acoustid.org")
            assertThat(requiresSecret).isTrue()
            assertThat(mayPersist).isTrue()
            assertThat(mayWriteToFile).isFalse()
            assertThat(commercialAllowed).isFalse()
            assertThat(maxDisplayAge).isNull()
            assertThat(itemLink(ItemKind.ACOUSTID_TRACK, "t-1")).isEqualTo("https://acoustid.org/track/t-1")
            assertThat(itemLink(ItemKind.RECORDING, "r-1")).isNull()
        }
    }

    @Test
    fun `MusicBrainz core data is CC0 and needs no key`() {
        with(MusicBrainzProvider) {
            assertThat(host).isEqualTo("musicbrainz.org")
            assertThat(requiresSecret).isFalse()
            assertThat(mayPersist).isTrue()
            assertThat(mayWriteToFile).isTrue()
            assertThat(commercialAllowed).isTrue()
            assertThat(maxDisplayAge).isNull()
            assertThat(itemLink(ItemKind.RECORDING, "r-1")).isEqualTo("https://musicbrainz.org/recording/r-1")
            assertThat(itemLink(ItemKind.RELEASE, "x")).isEqualTo("https://musicbrainz.org/release/x")
            assertThat(itemLink(ItemKind.RELEASE_GROUP, "g")).isEqualTo("https://musicbrainz.org/release-group/g")
            assertThat(itemLink(ItemKind.ARTIST, "a")).isEqualTo("https://musicbrainz.org/artist/a")
            assertThat(itemLink(ItemKind.ACOUSTID_TRACK, "t")).isNull()
        }
    }

    @Test
    fun `Cover Art Archive images stay the rights holders' and are linked through MusicBrainz`() {
        with(CoverArtArchiveProvider) {
            assertThat(host).isEqualTo("coverartarchive.org")
            assertThat(requiresSecret).isFalse()
            assertThat(commercialAllowed).isFalse()
            assertThat(mayPersist).isTrue()
            assertThat(mayWriteToFile).isTrue()
            assertThat(itemLink(ItemKind.RELEASE, "x")).isEqualTo("https://musicbrainz.org/release/x/cover-art")
            assertThat(itemLink(ItemKind.RELEASE_GROUP, "g")).isEqualTo("https://musicbrainz.org/release-group/g/cover-art")
            assertThat(itemLink(ItemKind.RECORDING, "r")).isNull()
        }
    }
}
