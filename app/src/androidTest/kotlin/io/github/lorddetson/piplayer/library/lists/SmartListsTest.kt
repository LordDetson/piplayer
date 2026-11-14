package io.github.lorddetson.piplayer.library.lists

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.covers.CoverCopy
import io.github.lorddetson.piplayer.covers.OwnCovers
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.IdentityAttacher
import io.github.lorddetson.piplayer.library.mirror.UserMirror
import io.github.lorddetson.piplayer.library.sync.IndexRow
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.MediaKinds
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.userdata.Favorites
import io.github.lorddetson.piplayer.userdata.PlayStats
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The smart lists on a library database filled from a fake media index, with the real attach pass and mirror
 * (docs/library.md §17.1, §17.4). The keys the tag pass would store are set directly.
 */
@RunWith(AndroidJUnit4::class)
class SmartListsTest {

    private val context = TestFiles.targetContext
    private val library = LibraryDatabase.open(context, file = null)
    private val user = UserDatabase.open(context, name = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(library, index, mayRead = { true }, FakeRules())
    private var now = 1_800_000_000_000L
    private val attacher = IdentityAttacher(library, user, sync, clock = { now })
    private val dir = File(context.cacheDir, "smart-covers").apply { deleteRecursively(); mkdirs() }
    private val covers = OwnCovers(user, dir) { now }
    private val mirror = UserMirror(library, user, sync, covers, clock = { now })
    private val favorites = Favorites(user) { now }
    private val plays = PlayStats(user)

    @After
    fun close() {
        mirror.close()
        attacher.close()
        sync.close()
        library.close()
        user.close()
        dir.deleteRecursively()
    }

    private fun key(n: Long) = "%064x".format(n)

    private suspend fun fileId(mediaStoreId: Long) = library.library().files().single { it.mediaStoreId == mediaStoreId }.id

    private suspend fun mediaIds(songs: List<io.github.lorddetson.piplayer.library.songs.Song>) = songs.map { it.mediaStoreId }

    /** Syncs [rows], gives each file the key of its media ID (or [keys]) and attaches identities. */
    private suspend fun library(rows: List<IndexRow>, keys: Map<Long, Long> = emptyMap()) {
        index.volume().rows = rows
        sync.sync()
        for (row in rows) {
            val k = keys[row.mediaStoreId] ?: row.mediaStoreId
            library.library().setContent(fileId(row.mediaStoreId), key(k), 200_000, DurationSource.HEADER)
        }
        attacher.attach()
    }

    private suspend fun identityOf(n: Long) = checkNotNull(user.identities().idOfKey(Hex.bytes(key(n))))

    private fun lists(own: OwnCoverKeys = OwnCoverKeys.NONE) = LibraryLists(library, HiddenFiles(), own)

    /** The list of [query] by media index IDs, after checking that its count says as much. */
    private suspend fun shown(query: SongQuery, own: OwnCoverKeys = OwnCoverKeys.NONE): List<Long?> {
        val songs = lists(own).songs(query)
        assertThat(lists(own).count(query)).describedAs("the count of $query").isEqualTo(songs.size)
        return mediaIds(songs)
    }

    /** Has the tag pass read [mediaStoreId] with [tags], and found a picture in it when [cover]. */
    private suspend fun read(mediaStoreId: Long, tags: Map<String, String>, cover: Boolean) {
        val dao = library.library()
        val fileId = fileId(mediaStoreId)
        dao.addTrack(TrackEntity.of(fileId, TrackTags.from(tags.mapValues { listOf(it.value) })))
        dao.markRead(fileId, at = 1, readerVersion = 1, format = null, codec = null, sampleRate = null, bitDepth = null,
            channels = null, bitrate = null, tagEncoding = null, coverPresent = cover, coverBytes = null, tagLengthMs = null)
        TrackDisplayWriter(library).refresh(listOf(fileId))
    }

    private val ringtone = MediaKinds(music = false, ringtone = true)

    @Test
    fun theSyncStoresTheDateTheIndexAddedEachFile() = runBlocking<Unit> {
        library(listOf(row(1, dateAdded = 1_650_000_000), row(2, dateAdded = 1_700_000_123)))

        val dates = library.library().files().associate { it.mediaStoreId to it.dateAdded }
        assertThat(dates).containsEntry(1L, 1_650_000_000L).containsEntry(2L, 1_700_000_123L)
    }

    @Test
    fun favoritesHoldTheVisibleFavoriteSongsInTheChosenOrderWithEveryCopy() = runBlocking<Unit> {
        // File 6 is a byte-identical copy of file 3; file 5 is a ringtone, hidden by default.
        library(
            listOf(row(1, "Delta"), row(2, "Charlie"), row(3, "Bravo"), row(4, "Alpha"), row(5, "Echo", path = "Ringtones/", kinds = ringtone), row(6, "Bravo copy")),
            keys = mapOf(6L to 3L),
        )
        favorites.set(identityOf(1), favorite = true)
        favorites.set(identityOf(3), favorite = true)
        favorites.set(identityOf(5), favorite = true)
        mirror.mirror()

        assertThat(shown(SongQuery.favorites())).containsExactly(3L, 6L, 1L)
        assertThat(shown(SongQuery.favorites().copy(reversed = true))).containsExactly(1L, 6L, 3L)
        assertThat(shown(SongQuery.favorites().copy(search = "delta"))).containsExactly(1L)
        assertThat(shown(SongQuery.favorites().copy(sort = SongSort.LENGTH))).containsExactlyInAnyOrder(1L, 3L, 6L)

        // A favorite that goes leaves the list with the next run of the mirror.
        favorites.set(identityOf(1), favorite = false)
        mirror.mirror()
        assertThat(shown(SongQuery.favorites())).containsExactly(3L, 6L)
    }

    @Test
    fun recentlyAddedHoldsTheNewest100VisibleSongsByTheirDateAdded() = runBlocking<Unit> {
        // Songs 1 to 105 are added one second apart; 106, the newest, is a ringtone; 107 shares 105's date.
        val rows = (1L..105L).map { row(it, "Song $it", dateAdded = 1_700_000_000 + it) } +
            row(106, "Ring", path = "Ringtones/", kinds = ringtone, dateAdded = 1_800_000_000) +
            row(107, "Twin", dateAdded = 1_700_000_105)
        index.volume().rows = rows
        sync.sync()

        val newest = shown(SongQuery.recentlyAdded())
        // The same date: the higher file ID first, which the sync gave 107 after 105.
        assertThat(newest).hasSize(SmartLists.RANKED_LIMIT).startsWith(107L, 105L, 104L).endsWith(8L, 7L)
        // Reversed, the same 100 songs, the oldest of them first.
        assertThat(shown(SongQuery.recentlyAdded().copy(reversed = true))).isEqualTo(newest.reversed())
        // A search looks within the 100 only.
        assertThat(shown(SongQuery.recentlyAdded().copy(search = "song 5"))).contains(105L, 95L, 50L).doesNotContain(5L)
    }

    @Test
    fun recentlyPlayedAndMostPlayedHoldTheVisibleSongsWithCountedPlays() = runBlocking<Unit> {
        library(listOf(row(1), row(2), row(3), row(4), row(5, path = "Ringtones/", kinds = ringtone)))
        plays.record(identityOf(1), startedAt = 100, listenedMs = 1)
        plays.record(identityOf(1), startedAt = 200, listenedMs = 1)
        plays.record(identityOf(2), startedAt = 500, listenedMs = 1)
        plays.record(identityOf(3), startedAt = 50, listenedMs = 1)
        plays.record(identityOf(3), startedAt = 300, listenedMs = 1)
        plays.record(identityOf(5), startedAt = 900, listenedMs = 1)
        mirror.mirror()

        assertThat(shown(SongQuery.recentlyPlayed())).containsExactly(2L, 3L, 1L)
        // Two plays each for 1 and 3: the later last play first.
        assertThat(shown(SongQuery.mostPlayed())).containsExactly(3L, 1L, 2L)
        assertThat(shown(SongQuery.mostPlayed().copy(reversed = true))).containsExactly(2L, 1L, 3L)
        assertThat(shown(SongQuery.recentlyPlayed().copy(search = "song 3"))).containsExactly(3L)
    }

    @Test
    fun recentlyPlayedAndMostPlayedHoldAtMost100Songs() = runBlocking<Unit> {
        library((1L..102L).map { row(it, "Song $it") })
        for (n in 1L..102L) plays.record(identityOf(n), startedAt = n, listenedMs = 1)
        plays.record(identityOf(1), startedAt = 1_000, listenedMs = 1)
        mirror.mirror()

        val recent = shown(SongQuery.recentlyPlayed())
        assertThat(recent).hasSize(SmartLists.RANKED_LIMIT).startsWith(1L, 102L, 101L).doesNotContain(2L, 3L)
        val most = shown(SongQuery.mostPlayed())
        assertThat(most).hasSize(SmartLists.RANKED_LIMIT).startsWith(1L, 102L, 101L).doesNotContain(2L, 3L)
    }

    /**
     * 1 has everything; 2 lacks its album; 3 has no tags and a name the parser cannot split; 4 lacks a cover and a
     * MusicBrainz ID; 5 is unread; 6 is a hidden ringtone without tags; 7 has an album but no cover.
     */
    private suspend fun missingLibrary() {
        library(
            listOf(
                row(1), row(2), row(3, name = "Recording.mp3"), row(4), row(5),
                row(6, path = "Ringtones/", name = "Beep.mp3", kinds = ringtone), row(7),
            ),
        )
        val full = mapOf("TITLE" to "Alpha", "ARTIST" to "Ann", "ALBUM" to "First", "MUSICBRAINZ_TRACKID" to "m1")
        read(1, full, cover = true)
        read(2, full - "ALBUM" + ("TITLE" to "Bravo"), cover = true)
        read(3, emptyMap(), cover = false)
        read(4, full + ("TITLE" to "Delta") - "MUSICBRAINZ_TRACKID", cover = false)
        read(6, emptyMap(), cover = false)
        read(7, full + ("TITLE" to "Golf") + ("ALBUM" to "Gee"), cover = false)
    }

    @Test
    fun songsMissingInformationHoldTheReadVisibleSongsWithAChosenReason() = runBlocking<Unit> {
        missingLibrary()

        // By title: Bravo, Recording.
        assertThat(shown(SongQuery.missingInfo())).containsExactly(2L, 3L)
        assertThat(shown(SongQuery.missingInfo(MissingReasons.COVER))).containsExactly(4L, 7L, 3L)
        assertThat(shown(SongQuery.missingInfo(MissingReasons.RECORDING_ID))).containsExactly(4L, 3L)
        assertThat(shown(SongQuery.missingInfo(MissingReasons.ALL))).containsExactly(2L, 4L, 7L, 3L)
        assertThat(shown(SongQuery.missingInfo(MissingReasons.ALL).copy(search = "golf"))).containsExactly(7L)
        assertThat(shown(SongQuery.missingInfo(0))).isEmpty()
    }

    @Test
    fun eachSongOfSongsMissingInformationCarriesItsChosenReasons() = runBlocking<Unit> {
        missingLibrary()

        val byDefault = lists().songs(SongQuery.missingInfo()).associate { it.mediaStoreId to it.reasons }
        assertThat(byDefault).containsEntry(2L, MissingReasons.ALBUM)
            .containsEntry(3L, MissingReasons.TITLE or MissingReasons.ARTIST or MissingReasons.ALBUM)
        val all = lists().songs(SongQuery.missingInfo(MissingReasons.ALL)).associate { it.mediaStoreId to it.reasons }
        assertThat(all).containsEntry(3L, MissingReasons.ALL).containsEntry(4L, MissingReasons.COVER or MissingReasons.RECORDING_ID)
            .containsEntry(7L, MissingReasons.COVER)
        // Other lists carry no reasons.
        assertThat(lists().songs(SongQuery.SONGS).map { it.reasons }).containsOnlyNulls()
    }

    @Test
    fun ownCoversOfASongAndOfAnAlbumTakeAwayNoCover() = runBlocking<Unit> {
        missingLibrary()
        covers.setTrack(identityOf(4), CoverCopy(cover("delta.jpg"), 10, 10))
        val gee = checkNotNull(lists().links(fileId(7))?.albumKey)
        covers.setAlbum(gee, CoverCopy(cover("gee.jpg"), 10, 10))

        val own = OwnCoverKeys.read(user)
        assertThat(own).isEqualTo(OwnCoverKeys(albumKeys = setOf(gee), contentKeys = setOf(key(4))))
        assertThat(shown(SongQuery.missingInfo(MissingReasons.COVER), own)).containsExactly(3L)
        // Delta still lacks its MusicBrainz ID, and shows only that.
        val songs = lists(own).songs(SongQuery.missingInfo(MissingReasons.COVER or MissingReasons.RECORDING_ID))
        assertThat(songs.associate { it.mediaStoreId to it.reasons })
            .isEqualTo(mapOf(4L to MissingReasons.RECORDING_ID, 3L to (MissingReasons.COVER or MissingReasons.RECORDING_ID)))
    }

    @Test
    fun everySmartListWalksAnIndex() = runBlocking<Unit> {
        missingLibrary()
        val queries = listOf(
            SongQuery.favorites(), SongQuery.favorites().copy(sort = SongSort.ALBUM), SongQuery.recentlyAdded(),
            SongQuery.recentlyPlayed(), SongQuery.mostPlayed(), SongQuery.missingInfo(), SongQuery.missingInfo(MissingReasons.ALL),
        )
        val own = OwnCoverKeys(albumKeys = setOf("a"), contentKeys = setOf(key(4)))
        for (query in queries) {
            for (covers in listOf(OwnCoverKeys.NONE, own)) {
                val plan = lists(covers).plan(query)
                // No table of the library is read whole; the lookups and joins go by indexes and primary keys.
                assertThat(plan).describedAs("$query: $plan").noneMatch { it.matches(Regex("""SCAN [dfm]( .*)?""")) && "USING" !in it }
                // SQLite says "COVERING INDEX" when the index alone holds what the step needs.
                val driver = when (query.kind) {
                    ListKind.FAVORITES -> "SEARCH m USING (COVERING )?INDEX index_track_mirror_favorite "
                    ListKind.RECENTLY_ADDED -> "SCAN f USING (COVERING )?INDEX index_audio_file_date_added"
                    ListKind.RECENTLY_PLAYED -> "SCAN m USING (COVERING )?INDEX index_track_mirror_last_played_at"
                    ListKind.MOST_PLAYED -> "SCAN m USING (COVERING )?INDEX index_track_mirror_play_count"
                    else -> "SEARCH d USING (COVERING )?INDEX index_track_display_missing_info_reasons "
                }
                assertThat(plan).describedAs("$query: $plan").anyMatch { Regex(driver).containsMatchIn(it) && it.startsWith(driver.substringBefore(" USING")) }
            }
        }
    }

    /** A copy of an own cover named [name] in the covers folder. */
    private fun cover(name: String): String {
        File(dir, name).writeBytes(byteArrayOf(1, 2, 3))
        return name
    }
}
