package io.github.lorddetson.piplayer.identification.text

import io.github.lorddetson.piplayer.identification.score.TrackHint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Manual search of TI §3.3: free text for `dismax=true`, limit 50, marked as manual. */
class ManualQueryTest {

    @Test
    fun `typed text becomes one manual query with the manual limit`() {
        val query = TextQueryBuilder.manual("  Paper   Lanterns quiet harbors ")
        assertThat(query).isEqualTo(RecordingQuery(QueryVariant.MANUAL, "Paper Lanterns quiet harbors", 50))
        assertThat(query?.manual).isTrue()
        assertThat(TextQueryBuilder.MANUAL_LIMIT).isEqualTo(50)
    }

    @Test
    fun `manual text is not escaped, because dismax reads it as plain words`() {
        assertThat(TextQueryBuilder.manual("AC/DC: Back In Black (Live)!")?.query).isEqualTo("AC/DC: Back In Black (Live)!")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", " \t"])
    fun `blank text sends nothing`(text: String) {
        assertThat(TextQueryBuilder.manual(text)).isNull()
    }

    @Test
    fun `automatic queries are not manual`() {
        val queries = TextQueryBuilder.build(TrackHint("Paper Lanterns", "The Quiet Harbors", 214_000))
        assertThat(queries).isNotEmpty
        assertThat(queries).allSatisfy {
            assertThat(it.manual).isFalse()
            assertThat(it.variant).isNotEqualTo(QueryVariant.MANUAL)
        }
    }
}
