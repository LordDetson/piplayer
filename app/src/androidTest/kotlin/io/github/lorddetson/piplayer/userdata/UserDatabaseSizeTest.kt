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
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.details
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The size budget of `user.db` (TI §10.2; §11.2 M3 "Size budget of user.db"): generated data at the volumes of the
 * estimate stays under 16 MB minus the reserve for the tables of M4b, measured on the file after a checkpoint. The rows
 * are made by the codecs the repository stores with, so their sizes are those of real decisions: every field but the
 * cover ticked, on files without tags (whose references all fit the mask, TI §10.2), titles of about 18 characters and
 * artists of about 12. Measured on the API 36 emulator in M3: 13.0 MB.
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
        try {
            fill(database)
            // Outside a transaction, which a checkpoint cannot run in.
            database.useWriterConnection { connection -> connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { it.step() } }
        } finally {
            database.close()
        }
        val file = context.getDatabasePath(name)
        val size = file.length() + File(file.path + "-wal").length()
        Log.i(TAG, "user.db with $TRACKS identified tracks: $size bytes")

        assertThat(size).describedAs("user.db bytes").isLessThan(BUDGET_BYTES - RESERVE_BYTES)
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
            // One identification per track as Decisions.apply stores it: the per-track rows, and the references of a
            // file without tags, whose every decided part differs from its file value.
            val ticked = ProtectedField.entries.toSet() - ProtectedField.COVER
            val fields = DecisionCodec.bits(ticked)
            val reason = DecisionCodec.reason(DecisionFixtures.reason)
            for (i in 1..TRACKS) {
                val parts = DecisionContent.ofRelease(details(title = words(3), artist = words(2), recording = i, track = i % 12 + 1))
                val references = FileReferences.of(parts.filterKeys { it.group in ticked }, DecisionContent.ofTags(null))
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
            }
            // A year of listening: play events of 90 days, statistics, favorites, members, own covers and playlists.
            val plays = database.plays()
            for (i in 1..PLAY_EVENTS) {
                usePrepared("INSERT INTO play_event (identity_id, started_at, listened_ms) VALUES (?, ?, ?)") { s ->
                    s.bindLong(1, (i % TRACKS + 1).toLong()); s.bindLong(2, 1_800_000_000_000L + i * 1_000L); s.bindLong(3, 180_000)
                    s.step()
                }
            }
            for (i in 1..PLAYED) {
                usePrepared("INSERT INTO play_stats (identity_id, play_count, listened_ms, last_played_at) VALUES (?, ?, ?, ?)") { s ->
                    s.bindLong(1, i.toLong()); s.bindLong(2, (i % 50).toLong()); s.bindLong(3, i * 180_000L); s.bindLong(4, 1_800_000_000_000L + i)
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

    private companion object {
        const val TAG = "UserDatabaseSize"
        const val TRACKS = 20_000
        const val RELEASES = 2_000
        const val PLAY_EVENTS = 4_500
        const val PLAYED = 12_000
        const val FAVORITES = 2_000
        const val FAVORITE_ALBUMS = 200
        const val PLAYLISTS = 50
        const val PLAYLIST_ENTRIES = 5_000

        /** TI §10.2: under 16 MB, and 1.5 MB of it kept for the write journal and tag backups of M4b. */
        const val BUDGET_BYTES = 16_000_000L
        const val RESERVE_BYTES = 1_500_000L
    }
}
