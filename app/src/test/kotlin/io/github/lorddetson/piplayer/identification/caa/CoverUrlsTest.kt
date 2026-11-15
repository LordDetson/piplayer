package io.github.lorddetson.piplayer.identification.caa

import io.github.lorddetson.piplayer.identification.musicbrainz.CoverArtArchiveDto
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** TI §11.1 "Cover URLs": which images a release asks for, and the HTTPS rewrite. */
class CoverUrlsTest {

    private val base = "https://coverartarchive.org/".toHttpUrl()
    private val usable = CoverArtArchiveDto(count = 3, front = true, back = true)

    @Test
    fun `a release with a usable front image asks for its own thumbnails and original`() {
        val source = CoverUrls.source(RELEASE, GROUP, usable)!!

        assertThat(source).isEqualTo(CoverSource(CoverEntity.RELEASE, RELEASE))
        assertThat(source.anotherEdition).isFalse()
        assertThat(CoverSize.entries.map { CoverUrls.image(base, source, it).toString() }).containsExactly(
            "https://coverartarchive.org/release/$RELEASE/front-250",
            "https://coverartarchive.org/release/$RELEASE/front-500",
            "https://coverartarchive.org/release/$RELEASE/front-1200",
            "https://coverartarchive.org/release/$RELEASE/front",
        )
    }

    @Test
    fun `a release without a front image takes the release group's, marked as another edition`() {
        val source = CoverUrls.source(RELEASE, GROUP, CoverArtArchiveDto(count = 1, front = false, back = true))!!

        assertThat(source).isEqualTo(CoverSource(CoverEntity.RELEASE_GROUP, GROUP))
        assertThat(source.anotherEdition).isTrue()
        assertThat(CoverUrls.image(base, source, CoverSize.DISPLAY).toString())
            .isEqualTo("https://coverartarchive.org/release-group/$GROUP/front-500")
        assertThat(CoverUrls.image(base, source, CoverSize.EMBED).toString())
            .isEqualTo("https://coverartarchive.org/release-group/$GROUP/front-1200")
        assertThat(CoverUrls.image(base, source, CoverSize.ORIGINAL).toString())
            .isEqualTo("https://coverartarchive.org/release-group/$GROUP/front")
    }

    @Test
    fun `a darkened release takes the release group's image even with a front flag`() {
        val source = CoverUrls.source(RELEASE, GROUP, usable.copy(darkened = true))
        assertThat(source).isEqualTo(CoverSource(CoverEntity.RELEASE_GROUP, GROUP))
    }

    @Test
    fun `without a usable front image and without a release group there is no online cover`() {
        assertThat(CoverUrls.source(RELEASE, null, CoverArtArchiveDto())).isNull()
        assertThat(CoverUrls.source(RELEASE, "not an mbid", CoverArtArchiveDto())).isNull()
    }

    @Test
    fun `unknown flags ask the release itself`() {
        assertThat(CoverUrls.source(RELEASE, GROUP, null)).isEqualTo(CoverSource(CoverEntity.RELEASE, RELEASE))
        assertThat(CoverUrls.source(RELEASE, null, null)).isEqualTo(CoverSource(CoverEntity.RELEASE, RELEASE))
    }

    @Test
    fun `an ID that is no MBID counts as missing, and MBIDs are used in their plain form`() {
        assertThat(CoverUrls.source("not an mbid", null, null)).isNull()
        assertThat(CoverUrls.source("not an mbid", GROUP, null)).isEqualTo(CoverSource(CoverEntity.RELEASE_GROUP, GROUP))
        assertThat(CoverUrls.source(" ${RELEASE.uppercase()} ", null, usable)).isEqualTo(CoverSource(CoverEntity.RELEASE, RELEASE))
        assertThat(CoverUrls.source(null, null, usable)).isNull()
    }

    @Test
    fun `the release index lives at release slash MBID`() {
        assertThat(CoverUrls.releaseIndex(base, RELEASE).toString()).isEqualTo("https://coverartarchive.org/release/$RELEASE")
        assertThat(CoverUrls.releaseIndex(base, RELEASE.uppercase()).toString()).endsWith("/release/$RELEASE")
        assertThat(CoverUrls.releaseIndex(base, "../admin")).isNull()
    }

    @Test
    fun `the configured base address is used as it is, cleartext and path included`() {
        val local = "http://127.0.0.1:8080/caa/".toHttpUrl()
        val source = CoverSource(CoverEntity.RELEASE, RELEASE)

        assertThat(CoverUrls.image(local, source, CoverSize.LIST).toString())
            .isEqualTo("http://127.0.0.1:8080/caa/release/$RELEASE/front-250")
        assertThat(CoverUrls.releaseIndex(local, RELEASE).toString()).isEqualTo("http://127.0.0.1:8080/caa/release/$RELEASE")
    }

    @Test
    fun `addresses from JSON are rewritten from http to https`() {
        assertThat(CoverUrls.fromJson("http://coverartarchive.org/release/$RELEASE/1.jpg").toString())
            .isEqualTo("https://coverartarchive.org/release/$RELEASE/1.jpg")
        assertThat(CoverUrls.fromJson("http://coverartarchive.org:80/release/$RELEASE/1.jpg").toString())
            .isEqualTo("https://coverartarchive.org/release/$RELEASE/1.jpg")
        assertThat(CoverUrls.fromJson("https://archive.org/download/mbid-$RELEASE/x.jpg").toString())
            .isEqualTo("https://archive.org/download/mbid-$RELEASE/x.jpg")
    }

    @Test
    fun `addresses from JSON with another scheme, or no address at all, are dropped`() {
        assertThat(CoverUrls.fromJson("ftp://coverartarchive.org/1.jpg")).isNull()
        assertThat(CoverUrls.fromJson("javascript:alert(1)")).isNull()
        assertThat(CoverUrls.fromJson("/release/$RELEASE/1.jpg")).isNull()
        assertThat(CoverUrls.fromJson("")).isNull()
        assertThat(CoverUrls.fromJson(null)).isNull()
    }

    private companion object {
        const val RELEASE = "11111111-1111-4111-8111-111111111111"
        const val GROUP = "22222222-2222-4222-8222-222222222222"
    }
}
