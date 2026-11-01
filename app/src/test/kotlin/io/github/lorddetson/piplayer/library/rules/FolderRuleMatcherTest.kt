package io.github.lorddetson.piplayer.library.rules

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FolderRuleMatcherTest {

    private val volume = "external_primary"

    private fun matcher(vararg rules: Pair<String, Boolean>) =
        FolderRuleMatcher(rules.map { (path, include) -> FolderRule(volume, path, include) })

    @Test
    fun `a folder under no rule follows the default`() {
        assertThat(matcher().stateFor(volume, "Music/Rock/")).isEqualTo(FolderRuleState.NONE)
    }

    @Test
    fun `a rule covers its folder and every folder below it`() {
        val matcher = matcher("Music/Rock/" to false)

        assertThat(matcher.stateFor(volume, "Music/Rock/")).isEqualTo(FolderRuleState.EXCLUDE)
        assertThat(matcher.stateFor(volume, "Music/Rock/Live/")).isEqualTo(FolderRuleState.EXCLUDE)
        assertThat(matcher.stateFor(volume, "Music/")).isEqualTo(FolderRuleState.NONE)
    }

    @Test
    fun `a folder name that only starts like a ruled one is not under it`() {
        assertThat(matcher("Music/Rock/" to false).stateFor(volume, "Music/Rockabilly/")).isEqualTo(FolderRuleState.NONE)
    }

    @Test
    fun `the deepest rule above a folder decides`() {
        // "Only these folders": the root excluded, Music included, one of its folders excluded again.
        val matcher = matcher("" to false, "Music/" to true, "Music/Ringtones/" to false)

        assertThat(matcher.stateFor(volume, "Download/")).isEqualTo(FolderRuleState.EXCLUDE)
        assertThat(matcher.stateFor(volume, "")).isEqualTo(FolderRuleState.EXCLUDE)
        assertThat(matcher.stateFor(volume, "Music/Albums/")).isEqualTo(FolderRuleState.INCLUDE)
        assertThat(matcher.stateFor(volume, "Music/Ringtones/Old/")).isEqualTo(FolderRuleState.EXCLUDE)
    }

    @Test
    fun `paths are compared ignoring case`() {
        assertThat(matcher("Music/Рок/" to false).stateFor(volume, "music/РОК/live/")).isEqualTo(FolderRuleState.EXCLUDE)
    }

    @Test
    fun `a rule of another volume does not apply`() {
        assertThat(matcher("Music/" to false).stateFor("1234-5678", "Music/")).isEqualTo(FolderRuleState.NONE)
    }
}
