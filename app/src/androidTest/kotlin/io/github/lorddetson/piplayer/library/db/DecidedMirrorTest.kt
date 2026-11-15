package io.github.lorddetson.piplayer.library.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The decided values beside `track_display` and the mirrored change counter (TI §10.1, §10.3; §11.2 M3 "Library database
 * version 13").
 */
@RunWith(AndroidJUnit4::class)
class DecidedMirrorTest {

    private val database = LibraryDatabase.open(TestFiles.targetContext, file = null)
    private val dao get() = database.decided()

    @After
    fun close() = database.close()

    private fun value(fileId: Long, field: String, value: String?, level: Int = DecidedMirrorEntity.LEVEL_IDENTIFICATION, decision: Long = 10) =
        DecidedMirrorEntity(fileId, field, value, level, decision)

    @Test
    fun aFileKeepsOneValuePerField() = runBlocking<Unit> {
        database.replaceDecided(
            listOf(1, 2),
            listOf(value(1, "title", "A"), value(1, "album", "B"), value(2, "title", "C", DecidedMirrorEntity.LEVEL_USER_EDIT, 11)),
        )
        assertThat(dao.valuesOf(listOf(1))).containsExactly(value(1, "album", "B"), value(1, "title", "A"))
        assertThat(dao.valuesOf(listOf(2))).containsExactly(value(2, "title", "C", DecidedMirrorEntity.LEVEL_USER_EDIT, 11))

        // The same field again replaces its value.
        dao.saveValues(listOf(value(1, "title", "A2", decision = 12)))
        assertThat(dao.valuesOf(listOf(1))).containsExactly(value(1, "album", "B"), value(1, "title", "A2", decision = 12))
    }

    @Test
    fun replacingFilesReplacesTheirRowsWholeAndLeavesTheOthers() = runBlocking<Unit> {
        database.replaceDecided(listOf(1, 2), listOf(value(1, "title", "A"), value(1, "album", "B"), value(2, "title", "C")))

        database.replaceDecided(listOf(1), listOf(value(1, "artist", "D", decision = 13)))
        assertThat(dao.all()).containsExactly(value(1, "artist", "D", decision = 13), value(2, "title", "C"))

        // A file whose decisions were all undone keeps no row.
        database.replaceDecided(listOf(1), emptyList())
        assertThat(dao.all()).containsExactly(value(2, "title", "C"))
    }

    @Test
    fun anEmptyDecidedValueIsKept() = runBlocking<Unit> {
        database.replaceDecided(listOf(3), listOf(value(3, "album", null, DecidedMirrorEntity.LEVEL_USER_EDIT)))
        assertThat(dao.valuesOf(listOf(3)).single().value).isNull()
    }

    @Test
    fun aFullRunReplacesTheWholeMirror() = runBlocking<Unit> {
        database.replaceDecided(listOf(1, 2), listOf(value(1, "title", "A"), value(2, "title", "C")))
        database.replaceAllDecided(listOf(value(4, "title", "E")))
        assertThat(dao.all()).containsExactly(value(4, "title", "E"))
        database.replaceAllDecided(emptyList())
        assertThat(dao.all()).isEmpty()
    }

    @Test
    fun rowsOfAFileThatIsNotReplacedAreRefused() = runBlocking<Unit> {
        assertThatThrownBy { runBlocking { database.replaceDecided(listOf(1), listOf(value(2, "title", "C"))) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(dao.all()).isEmpty()
    }

    @Test
    fun manyFilesAreReplacedInChunks() = runBlocking<Unit> {
        val ids = (1L..1_200L).toList()
        database.replaceDecided(ids, ids.map { value(it, "title", "T$it") })
        assertThat(dao.all()).hasSize(1_200)
        database.replaceDecided(ids, emptyList())
        assertThat(dao.all()).isEmpty()
    }

    @Test
    fun theMirroredChangeCounterMovesApartFromTheRulesCounter() = runBlocking<Unit> {
        val library = database.library()
        assertThat(library.mirroredChangeCounter()).isZero()
        assertThat(library.mirroredRulesCounter()).isZero()

        library.setMirroredChangeCounter(5)
        assertThat(library.mirroredChangeCounter()).isEqualTo(5)
        assertThat(library.mirroredRulesCounter()).isZero()

        library.setMirroredRulesCounter(3)
        assertThat(library.mirroredRulesCounter()).isEqualTo(3)
        assertThat(library.mirroredChangeCounter()).isEqualTo(5)

        // The other values of the row stay.
        library.setDisplayVersion(7)
        library.setMirroredChangeCounter(6)
        assertThat(library.displayVersion()).isEqualTo(7)
        assertThat(library.mirroredRulesCounter()).isEqualTo(3)
    }
}
