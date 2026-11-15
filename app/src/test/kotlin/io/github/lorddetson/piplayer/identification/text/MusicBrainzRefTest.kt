package io.github.lorddetson.piplayer.identification.text

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** Pasted MusicBrainz links and IDs in manual search (TI §3.3). */
class MusicBrainzRefTest {

    private val id = "b1a9c0e9-d987-4042-ae91-78d6a3267d69"

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "https://musicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69|recording",
            "http://musicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69|recording",
            "musicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69|recording",
            "https://beta.musicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69/|recording",
            "https://MusicBrainz.org/recording/B1A9C0E9-D987-4042-AE91-78D6A3267D69|recording",
            "https://musicbrainz.org/ws/2/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69?inc=isrcs&fmt=json|recording",
            "https://musicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69#details|recording",
            "Look: https://musicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69 this one|recording",
            "https://musicbrainz.org/release/b1a9c0e9-d987-4042-ae91-78d6a3267d69|release",
            "https://musicbrainz.org/release/b1a9c0e9-d987-4042-ae91-78d6a3267d69/disc/1#disc1|release",
            "www.musicbrainz.org/release/b1a9c0e9-d987-4042-ae91-78d6a3267d69|release",
            "https://musicbrainz.org:443/release/b1a9c0e9-d987-4042-ae91-78d6a3267d69|release",
            "b1a9c0e9-d987-4042-ae91-78d6a3267d69|any",
            "  B1A9C0E9-D987-4042-AE91-78D6A3267D69  |any",
            // Punctuation around a pasted link or ID is not part of it.
            "see musicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69.|recording",
            "(https://musicbrainz.org/release/b1a9c0e9-d987-4042-ae91-78d6a3267d69)|release",
            "<https://musicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69>|recording",
            "\"b1a9c0e9-d987-4042-ae91-78d6a3267d69\",|any",
            // A bare ID inside other pasted words.
            "id: b1a9c0e9-d987-4042-ae91-78d6a3267d69 Night Ferry|any",
            // A link that can be looked up wins over another MusicBrainz link or a bare ID in the same text.
            "musicbrainz.org/artist/b1a9c0e9-d987-4042-ae91-78d6a3267d69 https://musicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69|recording",
            "00000000-0000-4000-8000-000000000000 musicbrainz.org/release/b1a9c0e9-d987-4042-ae91-78d6a3267d69|release",
            // A link without a scheme whose query holds another link.
            "musicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69?returnto=https://x|recording",
            "beta.musicbrainz.org/release/b1a9c0e9-d987-4042-ae91-78d6a3267d69#https://x|release",
            // A label glued to the link without a space.
            "Ссылка:https://musicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69|recording",
            "Ссылкаhttps://musicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69|recording",
            "Ссылка:musicbrainz.org/release/b1a9c0e9-d987-4042-ae91-78d6a3267d69|release",
        ],
    )
    fun `a recording or release link or a bare ID becomes a lookup`(text: String, kind: String) {
        val expected = when (kind) {
            "recording" -> MusicBrainzRef.Recording(id)
            "release" -> MusicBrainzRef.Release(id)
            else -> MusicBrainzRef.AnyEntity(id)
        }
        assertThat(MusicBrainzRef.parse(text)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "https://musicbrainz.org/artist/b1a9c0e9-d987-4042-ae91-78d6a3267d69|artist",
            "https://musicbrainz.org/release-group/b1a9c0e9-d987-4042-ae91-78d6a3267d69|release-group",
            "https://musicbrainz.org/work/b1a9c0e9-d987-4042-ae91-78d6a3267d69|work",
            "https://musicbrainz.org/track/b1a9c0e9-d987-4042-ae91-78d6a3267d69|track",
            "https://musicbrainz.org/recording/not-an-id|recording",
            "https://musicbrainz.org/search?query=paper+lanterns&type=recording|search",
            "https://musicbrainz.org/|''",
            // A link no URI parser accepts is still a MusicBrainz link, never search text.
            "https://musicbrainz.org/search?query=a%zz|search",
            "musicbrainz.org|''",
            // With only links that cannot be looked up, the first one is refused.
            "https://musicbrainz.org/artist/b1a9c0e9-d987-4042-ae91-78d6a3267d69 musicbrainz.org/work/x|artist",
        ],
    )
    fun `another MusicBrainz link is refused`(text: String, entity: String) {
        assertThat(MusicBrainzRef.parse(text)).isEqualTo(MusicBrainzRef.Unsupported(entity))
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
        strings = [
            "Paper Lanterns",
            "https://evilmusicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69",
            "https://musicbrainz.org.example.com/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69",
            "https://example.com/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69",
            "b1a9c0e9-d987-4042-ae91-78d6a3267d6",
            "b1a9c0e9d9874042ae9178d6a3267d69",
            "ftp://musicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69",
            "x:ftp://musicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69",
            "https://example.com/?u=https://musicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69",
            "example.com/musicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69",
            // With a scheme the host ends at the first ':'; what follows is a port, never a host after a label.
            "https://example.com:musicbrainz.org/recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69",
            "Link:http://example.com:musicbrainz.org/release/b1a9c0e9-d987-4042-ae91-78d6a3267d69",
            "",
        ],
    )
    fun `other text is not a MusicBrainz reference`(text: String) {
        assertThat(MusicBrainzRef.parse(text)).isNull()
    }

    @Test
    fun `a manual request is a lookup for a reference, a refusal for another MusicBrainz link, and else a search`() {
        assertThat(ManualRequest.of("https://musicbrainz.org/release/$id"))
            .isEqualTo(ManualRequest.Lookup(MusicBrainzRef.Release(id)))
        assertThat(ManualRequest.of("https://musicbrainz.org/artist/$id"))
            .isEqualTo(ManualRequest.Refused(MusicBrainzRef.Unsupported("artist")))
        assertThat(ManualRequest.of(" Paper Lanterns "))
            .isEqualTo(ManualRequest.Search(TextQueryBuilder.manual("Paper Lanterns")!!))
        assertThat(ManualRequest.of("   ")).isNull()
    }
}
