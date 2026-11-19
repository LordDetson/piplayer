package io.github.lorddetson.piplayer.tagging.write

import io.github.lorddetson.piplayer.userdata.AppliedChangeEntity
import io.github.lorddetson.piplayer.userdata.TagBackupEntity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TagWriteArchiveCodecTest {
    private val diff = TagWriteRecord(
        ByteArray(32) { it.toByte() }, 42, 65,
        mapOf("TITLE" to TagChange(listOf("Before"), listOf("After")), "COMMENT" to TagChange(emptyList(), listOf("New"))),
        undoOf = 12, conflicts = setOf("ALBUM"), markedTagHash = true,
        coverChange = TagChange(listOf("old-front-hash"), listOf("new-front-hash")),
        legacyId3Change = LegacyId3Change("old-id3v1-bytes", null),
        id3v2Changes = mapOf("TITLE" to TagChange(emptyList(), listOf("After"))),
    )
    private val entry = AppliedChangeEntity(
        id = 16, identityId = 6, decisionId = 10, batchUuid = ByteArray(16) { 1 }, backupId = 14,
        volumeName = "external_primary", relativePath = "Music/Album/", displayName = "1 Before.mp3",
        state = "verified", copyPath = "old.copy", copySize = 800, copySha256 = ByteArray(32) { 9 },
        expectedContentKey = ByteArray(32) { 3 }, tagHash = ByteArray(32) { 7 }, diff = diff.encode(),
        actualSize = 800, actualMtime = 81_300, createdAt = 123, verifiedAt = 130, undoneAt = 140,
    )
    private val backup = TagBackupEntity(
        id = entry.backupId, identityId = entry.identityId, volumeName = entry.volumeName, relativePath = entry.relativePath,
        displayName = entry.displayName, createdAt = entry.createdAt, tagsPath = "snapshots/original.json",
        tagsHash = ByteArray(32) { 6 }, coverPath = "snapshots/front.jpg", coverHash = ByteArray(32) { 4 }, id3Version = 3,
    )

    @Test
    fun archivedWritesKeepUndoReferencesSnapshotsAndOwnWriteRecognition() {
        val original = ArchivedTagWrite(entry, backup)
        val restored = TagWriteArchiveCodec.decode(TagWriteArchiveCodec.encode(listOf(original))).single()
        assertThat(restored.backup).usingRecursiveComparison().isEqualTo(backup)
        assertThat(restored.entry).usingRecursiveComparison()
            .ignoringFields("copyPath", "copySha256", "diff").isEqualTo(entry)
        assertThat(restored.entry.copyPath).isEmpty()
        assertThat(restored.entry.copySha256).isEmpty()
        val restoredDiff = requireNotNull(TagWriteRecord.decode(restored.entry.diff))
        assertThat(restoredDiff.changes).isEqualTo(diff.changes)
        assertThat(restoredDiff.coverChange).isEqualTo(diff.coverChange)
        assertThat(restoredDiff.legacyId3Change).isEqualTo(diff.legacyId3Change)
        assertThat(restoredDiff.id3v2Changes).isEqualTo(diff.id3v2Changes)
        assertThat(restoredDiff.conflicts).containsExactly("ALBUM")
        assertThat(restoredDiff.undoOf).isEqualTo(12L)
        assertThat(restoredDiff.markedTagHash).isTrue()
        assertThat(restored.entry.matchesWrite(TagWriteObservation(
            entry.volumeName, entry.relativePath, entry.displayName, entry.copySize, 81, entry.expectedContentKey, entry.tagHash,
        ))).isTrue()
    }

    @Test
    fun incompleteWritesKeepTheirWorkingCopyAndCannotBeArchived() {
        val interrupted = ArchivedTagWrite(entry.copy(state = "streaming"), backup)
        assertThatThrownBy { TagWriteArchiveCodec.encode(listOf(interrupted)) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
