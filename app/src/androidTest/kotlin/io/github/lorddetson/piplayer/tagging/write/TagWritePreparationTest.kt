package io.github.lorddetson.piplayer.tagging.write

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.tagging.TagReadGuard
import io.github.lorddetson.piplayer.tagging.TaggerResult
import io.github.lorddetson.piplayer.tagging.TaggerTesting
import java.io.File
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagWritePreparationTest {
    private val opened = TaggerTesting.freshDatabase("tag-write-preparation-test.db")
    private val tagger = TaggerTesting.client(opened.first, TagReadGuard.DEFAULT_TIMEOUT)
    private val workingDirectory = File(TestFiles.targetContext.cacheDir, "tag-write-preparation-test")
    private val source = TestFiles.copy("playback/tagged-44100-stereo.mp3")
    private val target = TagTarget.Outside("test://tag-write/source", source.length(), 1)

    @After
    fun close() {
        tagger.close()
        opened.first.close()
        workingDirectory.listFiles()?.forEach { it.delete() }
        workingDirectory.delete()
    }

    @Test
    fun preparesAndVerifiesTheCopyWithoutOpeningTheOriginalForWrite() = runBlocking<Unit> {
        val original = source.readBytes()
        val before = TestFiles.open(source).use {
            (tagger.read(target, it, source.name) as TaggerResult.Replied).value.report
        }
        val key = checkNotNull(before.content).key
        val result = TagWritePreparation(workingDirectory, tagger) { Long.MAX_VALUE }.prepare(
            target, { TestFiles.open(source) }, source.name, key,
        ) { TagDiff.between(it.properties, mapOf("TITLE" to listOf("Changed only in the copy"))) }

        assertThat(result).isInstanceOf(PreparedTagCopy::class.java)
        result as PreparedTagCopy
        assertThat(result.file.canonicalFile.parentFile).isEqualTo(workingDirectory.canonicalFile)
        assertThat(result.before.properties).isEqualTo(before.properties)
        assertThat(result.after.properties["TITLE"]).containsExactly("Changed only in the copy")
        assertThat(result.after.content?.key).isEqualTo(key)
        assertThat(result.sha256).hasSize(32)
        assertThat(source.readBytes()).isEqualTo(original)
    }

    @Test
    fun refusesToKeepAWorkingCopyOfDifferentAudio() = runBlocking<Unit> {
        val result = TagWritePreparation(workingDirectory, tagger) { Long.MAX_VALUE }.prepare(
            target, { TestFiles.open(source) }, source.name, "wrong audio key",
        ) { error("Must check content before editing") }

        assertThat(result).isEqualTo(PreparationFailure.CONTENT_CHANGED)
        assertThat(workingDirectory.listFiles().orEmpty()).isEmpty()
    }
}
