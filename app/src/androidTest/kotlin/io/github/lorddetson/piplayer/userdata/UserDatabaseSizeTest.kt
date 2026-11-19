package io.github.lorddetson.piplayer.userdata

import android.util.Log
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionContent
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.tagging.write.TagChange
import io.github.lorddetson.piplayer.tagging.write.TagWriteRecord
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.details
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The size budget of `user.db` (TI §10.2; §11.2): generated data at the volumes of the
 * estimate stays under 16 MB including one year of tag writes, measured on the file after a checkpoint. The rows
 * are made by the codecs the repository stores with, so their sizes are those of real decisions: every field but the
 * cover ticked, titles of about 18 characters and artists of about 12. Half of the decisions are made on files without
 * tags, whose references all fit the mask, and half on files whose tags differ from the decided values in 8 parts, which
 * take 5 bytes each (TI §10.2); a tenth of the tracks has a second location whose values differ in 4 parts, as
 * `file_state` rows. A year of listening adds 9 000 listening outcomes of 90 days and the folded counts of 15 000
 * identities, 12 000 of which have folded plays too (docs/library.md §15.7). Each decision's reason is the 7-byte blob
 * of `DecisionCodec`. Measured on the API 36 emulator in M3 before the references and `file_state` rows were added:
 * 13.0 MB; on API 30 and 36 with the listening outcomes and the reason as a short text: 14.64 MB, over the limit; with
 * the reason blob: 14.28 MB (14 282 752 bytes) on both.
 */
@RunWith(AndroidJUnit4::class)
class UserDatabaseSizeTest {

    private val context = TestFiles.targetContext
    private val name = "user-size-test.db"

    @After
    fun delete() {
        context.deleteDatabase(name)
    }

    private val random = Random(12)

    private fun bytes(n: Int) = random.nextBytes(n)

    /** A plausible name of [count] words of 3 to 8 letters, so a title of three is about 18 characters long. */
    private fun words(count: Int) = List(count) {
        String(CharArray(random.nextInt(3, 9)) { i -> (if (i == 0) 'A' else 'a') + random.nextInt(26) })
    }.joinToString(" ")

    @Test
    fun twentyThousandIdentifiedTracksStayWithinTheBudget() = runBlocking<Unit> {
        context.deleteDatabase(name)
        val database = UserDatabase.open(context, name)
        val (baseline, total) = try {
            fill(database)
            val beforeWrites = checkpointSize(database)
            fillWrites(database)
            beforeWrites to checkpointSize(database)
        } finally {
            database.close()
        }
        Log.i(TAG, "user.db: $baseline bytes before writes, ${total - baseline} for $WRITTEN_FILES tag writes, $total total")

        assertThat(total).describedAs("user.db bytes with %s tag writes (baseline %s)", WRITTEN_FILES, baseline).isLessThan(BUDGET_BYTES)
    }

    private suspend fun checkpointSize(database: UserDatabase): Long {
        database.useWriterConnection { connection -> connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { it.step() } }
        val file = context.getDatabasePath(name)
        return file.length() + File(file.path + "-wal").length()
    }

    private suspend fun fillWrites(database: UserDatabase) {
        val journal = TagWriteJournal(database)
        val queue = TagApplyQueue(database)
        val tracks = database.identities().identities().take(WRITTEN_FILES)
        for (batch in tracks.chunked(500)) {
            val uuid = bytes(16)
            val plan = requireNotNull(Hex.mbidText(uuid))
            val items = batch.map { track ->
                TagApplyItem(
                    plan, track.id, Hex.text(track.contentKey), track.id,
                    requireNotNull(track.hintVolumeName), requireNotNull(track.hintRelativePath), requireNotNull(track.hintDisplayName),
                )
            }
            queue.save(items)
            for ((track, item) in batch.zip(items)) {
                val size = requireNotNull(track.hintSize)
                val at = track.createdAt + 10
                val copyPath = "tag-write-${random.nextLong().toULong()}"
                val backup = TagBackupEntity(
                    identityId = track.id, volumeName = item.volumeName, relativePath = item.relativePath, displayName = item.displayName,
                    createdAt = at, tagsPath = "snapshots/$copyPath.json", tagsHash = bytes(32), coverPath = null, coverHash = null,
                    id3Version = 3,
                )
                val fields = mapOf(
                    "TITLE" to TagChange(listOf(words(3)), listOf(words(3))),
                    "ARTIST" to TagChange(listOf(words(2)), listOf(words(2))),
                    "ALBUM" to TagChange(listOf(words(3)), listOf(words(3))),
                    "TRACKNUMBER" to TagChange(listOf("1"), listOf("3")),
                )
                val parts = mapOf(
                    DecidedField.TITLE to "TITLE", DecidedField.ARTIST to "ARTIST",
                    DecidedField.ALBUM to "ALBUM", DecidedField.TRACK_NUMBER to "TRACKNUMBER",
                )
                val written = parts.entries.associate { (part, key) ->
                    part.code to FileReferences.hash(fields.getValue(key).after.single())
                }
                val record = TagWriteRecord(bytes(32), 80, track.id + 55_000, fields, markedTagHash = true, writtenPartHashes = written)
                val change = AppliedChangeEntity(
                    identityId = track.id, decisionId = track.id, batchUuid = uuid, backupId = 0,
                    volumeName = item.volumeName, relativePath = item.relativePath, displayName = item.displayName, state = "pending",
                    copyPath = "$copyPath.copy", copySize = size, copySha256 = bytes(32), expectedContentKey = track.contentKey,
                    tagHash = bytes(32), diff = record.encode(), createdAt = at,
                )
                val id = checkNotNull(journal.prepareForApply(backup, change, item))
                check(journal.beginStreaming(id))
                check(journal.streamed(id))
                check(journal.verified(id, size, at, at + 1, writtenPartHashes = written))
            }
            assertThat(queue.results(plan)).isEqualTo(mapOf("written" to items.size))
        }
    }

    private suspend fun fill(database: UserDatabase) {
        val identities = database.identities()
        val decisions = database.decisions()
        database.withWriteTransaction {
            // 20 000 identities with their hints (TI §10.2: about 200 bytes each with the key index).
            identities.addAll(
                List(TRACKS) { i ->
                    TrackIdentityEntity(
                        contentKey = bytes(32), durationMs = 180_000L + i, recordingMbid = bytes(16),
                        hintVolumeName = "external_primary", hintRelativePath = "Music/${words(2)}/${words(3)}/",
                        hintDisplayName = "%02d %s.mp3".format(i % 20 + 1, words(3)), hintSize = 7_000_000L + i,
                        createdAt = 1_800_000_000_000L + i, missingSince = null,
                    )
                },
            )
            // 2 000 release choices, about ten tracks each.
            for (release in 1..RELEASES) {
                val parts = DecisionContent.ofRelease(
                    details(album = words(3), artist = words(2), release = release, recording = release, trackTotal = 12),
                )
                decisions.addReleaseChoice(
                    ReleaseChoiceEntity(
                        releaseMbid = bytes(16), releaseGroupMbid = bytes(16),
                        values = ReleaseValues.of(parts, disc = 1).encode(), savedCoverId = null, fetchedAt = 1_800_000_000_000L,
                    ),
                )
            }
            // One identification per track as Decisions.apply stores it: the per-track rows and the references of the
            // location it was made on, then the file_state rows of a second location.
            val ticked = ProtectedField.identificationFields.toSet() - ProtectedField.COVER
            val fields = DecisionCodec.bits(ticked)
            val reason = DecisionCodec.reason(DecisionFixtures.reason)
            for (i in 1..TRACKS) {
                val parts = DecisionContent.ofRelease(details(title = words(3), artist = words(2), recording = i, track = i % 12 + 1))
                val atDecision = if (i % 2 == 0) taggedDifferently(i) else DecisionContent.ofTags(null)
                val references = FileReferences.of(parts.filterKeys { it.group in ticked }, atDecision)
                val id = decisions.add(
                    DecisionEntity(
                        identityId = i.toLong(), kind = DecisionKind.APPLY.code, fields = fields,
                        releaseChoiceId = (i % RELEASES + 1).toLong(), recordingMbid = bytes(16), releaseTrackMbid = bytes(16),
                        acoustIdId = bytes(16), confidence = 965, reason = reason, provider = 1,
                        fetchedAt = 1_800_000_000_000L + i, licenseClass = 1, fileValues = FileReferences.encode(references),
                        createdAt = 1_800_000_000_000L + i,
                    ),
                )
                decisions.addMetadata(
                    parts.filterKeys { !it.release && !it.decisionColumn }
                        .map { (part, text) -> TrackMetadataEntity(id, part.code, DecisionCodec.encode(part, text)) },
                )
                if (i % 10 == 0) {
                    val path = "Music/${words(2)}/${words(3)}/"
                    val name = "%02d %s.mp3".format(i % 20 + 1, words(3))
                    decisions.saveFileStates(
                        SECOND_LOCATION_PARTS.map { part ->
                            FileStateEntity(i.toLong(), id, "external_primary", path, name, part.code, hash(words(3)), false)
                        },
                    )
                }
            }
            // A year of listening: play events of 90 days, statistics, favorites, members, own covers and playlists.
            val plays = database.plays()
            for (i in 1..PLAY_EVENTS) {
                usePrepared("INSERT INTO play_event (identity_id, started_at, listened_ms) VALUES (?, ?, ?)") { s ->
                    s.bindLong(1, (i % TRACKS + 1).toLong()); s.bindLong(2, 1_800_000_000_000L + i * 1_000L); s.bindLong(3, 180_000)
                    s.step()
                }
            }
            // The folded plays of 12 000 identities and the folded listenings of 15 000, those 12 000 among them: an
            // identity without a play has a play count of 0 (docs/library.md §15.7).
            for (i in 1..LISTENED) {
                usePrepared(
                    "INSERT INTO play_stats (identity_id, play_count, listened_ms, last_played_at, early_skips, late_skips, finished) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                ) { s ->
                    val played = i <= PLAYED
                    s.bindLong(1, i.toLong()); s.bindLong(2, if (played) (i % 50 + 1).toLong() else 0); s.bindLong(3, if (played) i * 180_000L else 0)
                    if (played) s.bindLong(4, 1_800_000_000_000L + i) else s.bindNull(4)
                    s.bindLong(5, (i % 9).toLong()); s.bindLong(6, (i % 5).toLong()); s.bindLong(7, (i % 40).toLong())
                    s.step()
                }
            }
            // Listening outcomes of 90 days, 100 a day (docs/library.md §15.7).
            for (i in 1..LISTEN_EVENTS) {
                usePrepared(
                    "INSERT INTO listen_event (started_at, identity_id, listened_ms, duration_ms, outcome) VALUES (?, ?, ?, ?, ?)",
                ) { s ->
                    s.bindLong(1, 1_800_000_000_000L + i * 864_000L); s.bindLong(2, (i * 7L % TRACKS) + 1)
                    s.bindLong(3, 1_000L + i * 7_919L % 299_000); s.bindLong(4, 180_000L + i % 120_000); s.bindLong(5, (i % 3 + 1).toLong())
                    s.step()
                }
            }
            for (i in 1..FAVORITES) database.favorites().add(i.toLong(), 1_800_000_000_000L + i)
            for (i in 1..FAVORITE_ALBUMS) {
                val key = "mbid:" + Hex.mbidText(bytes(16))
                database.favorites().putAlbum(key, Hex.mbidBytes(key.removePrefix("mbid:")), 1_800_000_000_000L + i)
                for (t in 0 until 10) database.favorites().addMember(key, (i * 10L + t) % TRACKS + 1)
            }
            check(plays.events().size == PLAY_EVENTS)
            check(database.listens().events().size == LISTEN_EVENTS)
        }
        val playlists = Playlists(database)
        for (p in 1..PLAYLISTS) {
            val id = playlists.create("Playlist $p ${words(2)}")
            playlists.add(
                id,
                List(PLAYLIST_ENTRIES / PLAYLISTS) { e ->
                    val identity = ((p * 131L + e) % TRACKS) + 1
                    NewEntry(identity, "external_primary", "Music/${words(2)}/", "${words(3)}.mp3", 180_000)
                },
            )
        }
    }

    /** The values of a tagged file at the decision that differ from the decided ones in 8 parts. */
    private fun taggedDifferently(i: Int): Map<DecidedField, String?> = mapOf(
        DecidedField.TITLE to words(3),
        DecidedField.ARTIST to words(2),
        DecidedField.ARTIST_SORT to words(2),
        DecidedField.ALBUM to words(3),
        DecidedField.ALBUM_ARTIST to words(2),
        DecidedField.DATE to "2003",
        DecidedField.TRACK_NUMBER to (i % 12 + 2).toString(),
        DecidedField.DISC_NUMBER to "2",
    )

    private fun hash(value: String) = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(FileReferences.hash(value)).array()

    private companion object {
        const val TAG = "UserDatabaseSize"
        const val TRACKS = 20_000
        const val RELEASES = 2_000
        const val PLAY_EVENTS = 4_500
        const val PLAYED = 12_000
        const val LISTEN_EVENTS = 9_000
        const val LISTENED = 15_000
        const val FAVORITES = 2_000
        const val FAVORITE_ALBUMS = 200
        const val PLAYLISTS = 50
        const val PLAYLIST_ENTRIES = 5_000

        /** The parts a second location's values differ in. */
        val SECOND_LOCATION_PARTS = listOf(DecidedField.TITLE, DecidedField.ARTIST, DecidedField.ALBUM, DecidedField.TRACK_NUMBER)

        const val WRITTEN_FILES = 5_000
        const val BUDGET_BYTES = 16_000_000L
    }
}
