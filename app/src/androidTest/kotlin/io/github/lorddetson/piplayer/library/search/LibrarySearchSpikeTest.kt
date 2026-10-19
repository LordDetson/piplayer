package io.github.lorddetson.piplayer.library.search

import android.util.Log
import androidx.room3.Room
import androidx.room3.RoomRawQuery
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.measureTime
import kotlin.time.measureTimedValue
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The M0 Room spike (features M0): search over normalized columns on a generated 20 000-row table. Every query runs
 * with LIKE and with instr() on the platform's SQLite and on the bundled one; all must find the right rows, and on the
 * platform's SQLite at least one of the two must answer every query of a search-as-you-type within 50 ms (95th
 * percentile), or the library needs a full-text index. The timings of all four variants go to the log.
 */
@RunWith(AndroidJUnit4::class)
class LibrarySearchSpikeTest {

    enum class Driver { FRAMEWORK, BUNDLED }

    enum class Method { LIKE, INSTR }

    companion object {
        private const val TAG = "LibrarySearchSpike"
        private const val ROWS = 20_000
        private const val PAGE = 50
        private const val WARM_UP = 3
        private const val RUNS = 20
        private const val MAX_P95_MS = 50L

        private lateinit var databases: Map<Driver, SpikeLibraryDatabase>

        /** The rows the correctness checks look for; the generator never makes these words. */
        private val hedgehogInFog = row(1, "Ёжик в тумане", "Сказки", "Мультфильмы")
        private val hedgehog = row(2, "Ежик", "Детские песни", "Сборник")
        private val halo = row(3, "Halo", "Beyoncé", "I Am... Sasha Fierce")

        /**
         * Typed letter by letter, whole words, the middle of a word, two words, nothing to find, and the most words a
         * query keeps, all of them common, that no row has together (every row is tested against several of them).
         */
        val QUERIES = listOf(
            "е", "еж", "ежи", "ежик", "ежик тум", "жик", "b", "be", "beyonce", "ка", "lo ka", "zzzq",
            "ка ло ми ра не то су ви",
        )

        @JvmStatic
        @BeforeClass
        fun createDatabases() {
            val context = TestFiles.targetContext
            val rows = listOf(hedgehogInFog, hedgehog, halo) + generatedRows(ROWS - 3)
            databases = Driver.entries.associateWith { driver ->
                val name = "search-spike-${driver.name.lowercase()}.db"
                context.deleteDatabase(name)
                val builder = Room.databaseBuilder<SpikeLibraryDatabase>(context, name)
                val database = when (driver) {
                    Driver.FRAMEWORK -> builder.setDriver(AndroidSQLiteDriver())
                    Driver.BUNDLED -> builder.setDriver(BundledSQLiteDriver())
                }.build()
                val time = measureTime { runBlocking { database.tracks().insertAll(rows) } }
                Log.i(TAG, "$driver: inserted ${rows.size} rows in $time")
                // The platform driver runs EXPLAIN as a statement, not a query, so the plan comes from the bundled one.
                if (driver == Driver.BUNDLED) {
                    val plan = runBlocking {
                        database.tracks().plan(
                            RoomRawQuery(
                                "EXPLAIN QUERY PLAN SELECT * FROM track_search WHERE instr(search_text, ?) > 0 " +
                                    "ORDER BY title_key LIMIT $PAGE",
                            ) { it.bindText(1, "zzzq") },
                        )
                    }
                    Log.i(TAG, "$driver: plan ${plan.map { it.detail }}")
                }
                database
            }
        }

        @JvmStatic
        @AfterClass
        fun closeDatabases() {
            if (::databases.isInitialized) databases.values.forEach { it.close() }
        }

        private fun row(id: Long, title: String, artist: String, album: String) = TrackSearchRow(
            id = id,
            title = title,
            artist = artist,
            album = album,
            titleKey = SearchText.of(title),
            searchText = SearchText.of(title, artist, album),
        )

        /** Made-up titles, artists, albums, composers and genres in Latin and Cyrillic, some with diacritics. */
        private fun generatedRows(count: Int): List<TrackSearchRow> {
            val random = Random(2026)
            val latin = listOf("ka", "lo", "mi", "ra", "ne", "to", "su", "vi", "an", "el", "or", "is", "ul", "dan", "fel",
                "gor", "hen", "kel", "mar", "sol", "tin", "ver", "wal", "rö", "lé", "ná", "sü")
            val cyrillic = listOf("ка", "ло", "ми", "ра", "не", "то", "су", "ви", "ан", "ел", "ор", "ис", "ул", "дан",
                "фел", "гор", "хен", "кел", "мар", "сол", "тин", "вер", "вал", "лё", "рё", "ня", "сю")
            val genres = listOf("Rock", "Pop", "Jazz", "Электроника", "Шансон", "Classical", "Hip-Hop", "Фолк")
            fun word(syllables: List<String>) = buildString {
                repeat(random.nextInt(2, 4)) { append(syllables[random.nextInt(syllables.size)]) }
            }.replaceFirstChar { it.uppercase() }
            fun words(min: Int, max: Int, syllables: List<String>) =
                List(random.nextInt(min, max + 1)) { word(syllables) }.joinToString(" ")
            return List(count) { index ->
                val syllables = if (random.nextBoolean()) latin else cyrillic
                val title = words(1, 4, syllables)
                val artist = words(1, 3, syllables)
                val album = words(1, 3, syllables)
                val composer = if (random.nextInt(10) < 3) words(2, 2, syllables) else null
                val genre = genres[random.nextInt(genres.size)]
                val file = "%02d - %s - %s.mp3".format(random.nextInt(1, 20), artist, title)
                TrackSearchRow(
                    id = index + 4L,
                    title = title,
                    artist = artist,
                    album = album,
                    titleKey = SearchText.of(title),
                    searchText = SearchText.of(title, artist, album, composer, genre, file),
                )
            }
        }

        /** The first page of results, ordered by title, for [query] with [method]. */
        fun search(database: SpikeLibraryDatabase, method: Method, query: String): List<TrackSearchRow> {
            val terms = SearchTerms.of(query)
            if (terms.isEmpty()) return emptyList()
            val condition = terms.joinToString(" AND ") {
                when (method) {
                    Method.LIKE -> "search_text LIKE ?"
                    Method.INSTR -> "instr(search_text, ?) > 0"
                }
            }
            val sql = "SELECT * FROM track_search WHERE $condition ORDER BY title_key LIMIT $PAGE"
            val raw = RoomRawQuery(sql) { statement ->
                terms.forEachIndexed { index, term ->
                    statement.bindText(index + 1, if (method == Method.LIKE) "%$term%" else term)
                }
            }
            return runBlocking { database.tracks().search(raw) }
        }
    }

    private fun eachVariant(block: (Driver, Method, SpikeLibraryDatabase) -> Unit) {
        for ((driver, database) in databases) for (method in Method.entries) block(driver, method, database)
    }

    private fun ids(driver: Driver, method: Method, database: SpikeLibraryDatabase, query: String) =
        search(database, method, query).map { it.id }.also { Log.d(TAG, "$driver $method '$query': $it") }

    @Test
    fun yoAndYeAreTheSameLetter() = eachVariant { driver, method, database ->
        assertThat(ids(driver, method, database, "ежик")).describedAs("$driver $method").containsExactlyInAnyOrder(1, 2)
        assertThat(ids(driver, method, database, "ёжик")).describedAs("$driver $method").containsExactlyInAnyOrder(1, 2)
    }

    @Test
    fun latinDiacriticsAreIgnored() = eachVariant { driver, method, database ->
        assertThat(ids(driver, method, database, "Beyonce")).describedAs("$driver $method").containsExactly(3)
    }

    @Test
    fun theMiddleOfAWordIsFound() = eachVariant { driver, method, database ->
        assertThat(ids(driver, method, database, "жик")).describedAs("$driver $method").containsExactlyInAnyOrder(1, 2)
    }

    @Test
    fun everyWordMustMatch() = eachVariant { driver, method, database ->
        assertThat(ids(driver, method, database, "ежик тум")).describedAs("$driver $method").containsExactly(1)
    }

    @Test
    fun aQueryWithoutMatchesFindsNothing() = eachVariant { driver, method, database ->
        assertThat(ids(driver, method, database, "zzzq")).describedAs("$driver $method").isEmpty()
    }

    @Test
    fun resultsComeInTitleOrderOnePageAtATime() = eachVariant { driver, method, database ->
        val page = search(database, method, "ка")
        assertThat(page).describedAs("$driver $method").hasSize(PAGE)
        assertThat(page.map { it.titleKey }).describedAs("$driver $method").isSorted()
    }

    @Test
    fun searchAsYouTypeAnswersWithinFiftyMillisecondsOnThePlatformSqlite() {
        val p95 = mutableMapOf<Pair<Driver, Method>, Map<String, Long>>()
        eachVariant { driver, method, database ->
            p95[driver to method] = QUERIES.associateWith { query ->
                repeat(WARM_UP) { search(database, method, query) }
                val times = List(RUNS) { measureTimedValue { search(database, method, query) }.duration }.sorted()
                val median = times[RUNS / 2]
                val worst = times[(RUNS * 95 + 99) / 100 - 1]
                Log.i(TAG, "$driver $method '$query': median ${median.ms()} ms, p95 ${worst.ms()} ms")
                worst.inWholeMilliseconds
            }
        }
        val framework = Method.entries.associateWith { method -> p95.getValue(Driver.FRAMEWORK to method).values.max() }
        Log.i(TAG, "Platform SQLite, slowest p95 per method: $framework")
        assertThat(framework.values.min()).describedAs("slowest p95 of the better method").isLessThanOrEqualTo(MAX_P95_MS)
    }

    private fun Duration.ms() = "%.1f".format(inWholeMicroseconds / 1000.0)
}
