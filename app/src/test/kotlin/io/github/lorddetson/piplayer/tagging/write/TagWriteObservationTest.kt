package io.github.lorddetson.piplayer.tagging.write

import io.github.lorddetson.piplayer.userdata.AppliedChangeEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TagWriteObservationTest {
    private val key = ByteArray(32) { 1 }
    private val hash = ByteArray(32) { 2 }
    private val journal = AppliedChangeEntity(
        identityId = 1, decisionId = 2, batchUuid = null, backupId = 3, volumeName = "external_primary",
        relativePath = "Music/", displayName = "track.mp3", state = "pending", copyPath = "tag.copy",
        copySize = 9_000, copySha256 = ByteArray(32) { 3 }, expectedContentKey = key, tagHash = hash,
        diff = byteArrayOf(), createdAt = 4,
    )
    private val observed = TagWriteObservation("external_primary", "Music/", "track.mp3", 9_000, 42, key, hash)

    @Test
    fun anObservedWriteMatchesBeforeTheVerifiedCommit() {
        for (state in listOf("pending", "streaming", "streamed")) {
            assertThat(journal.copy(state = state).matchesWrite(observed)).isTrue()
        }
    }

    @Test
    fun aVerifiedWriteComparesTheObservedTimeInWholeSeconds() {
        val verified = journal.copy(state = "verified", actualSize = 9_000, actualMtime = 42_999)

        assertThat(verified.matchesWrite(observed)).isTrue()
        assertThat(verified.matchesWrite(observed.copy(modifiedSeconds = 43))).isFalse()
    }

    @Test
    fun anExternalEditOrADifferentLocationDoesNotMatch() {
        assertThat(journal.matchesWrite(observed.copy(tagHash = ByteArray(32) { 5 }))).isFalse()
        assertThat(journal.matchesWrite(observed.copy(tagHash = null))).isFalse()
        assertThat(journal.matchesWrite(observed.copy(contentKey = ByteArray(32) { 6 }))).isFalse()
        assertThat(journal.matchesWrite(observed.copy(relativePath = "Download/"))).isFalse()
        assertThat(journal.matchesWrite(observed.copy(size = 9_001))).isFalse()
        assertThat(journal.copy(state = "failed").matchesWrite(observed)).isFalse()
    }
}
