package io.github.lorddetson.piplayer.library.details

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import android.graphics.Bitmap
import android.graphics.Color
import io.github.lorddetson.piplayer.covers.AndroidCoverSources
import io.github.lorddetson.piplayer.covers.CoverCopy
import io.github.lorddetson.piplayer.covers.CoverLoader
import io.github.lorddetson.piplayer.covers.CoverSources
import io.github.lorddetson.piplayer.covers.ImageCodec
import io.github.lorddetson.piplayer.covers.CoverPlan
import io.github.lorddetson.piplayer.covers.CoverRequest
import io.github.lorddetson.piplayer.covers.CoverStep
import io.github.lorddetson.piplayer.covers.LibraryCoverFacts
import io.github.lorddetson.piplayer.covers.OwnCovers
import io.github.lorddetson.piplayer.covers.SavedCovers
import io.github.lorddetson.piplayer.library.db.DecidedMirrorEntity
import io.github.lorddetson.piplayer.library.db.replaceDecided
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.details.DetailField.ADDED
import io.github.lorddetson.piplayer.library.details.DetailField.ALBUM
import io.github.lorddetson.piplayer.library.details.DetailField.ARTIST
import io.github.lorddetson.piplayer.library.details.DetailField.BITRATE
import io.github.lorddetson.piplayer.library.details.DetailField.CODEC
import io.github.lorddetson.piplayer.library.details.DetailField.COVER_SIZE
import io.github.lorddetson.piplayer.library.details.DetailField.COVER_SOURCE
import io.github.lorddetson.piplayer.library.details.DetailField.DATE
import io.github.lorddetson.piplayer.library.details.DetailField.FAVORITE
import io.github.lorddetson.piplayer.library.details.DetailField.FILE_NAME
import io.github.lorddetson.piplayer.library.details.DetailField.FOLDER
import io.github.lorddetson.piplayer.library.details.DetailField.FORMAT
import io.github.lorddetson.piplayer.library.details.DetailField.INDEX_LENGTH
import io.github.lorddetson.piplayer.library.details.DetailField.LAST_PLAYED
import io.github.lorddetson.piplayer.library.details.DetailField.LENGTH
import io.github.lorddetson.piplayer.library.details.DetailField.MIME_TYPE
import io.github.lorddetson.piplayer.library.details.DetailField.MODIFIED
import io.github.lorddetson.piplayer.library.details.DetailField.PLAY_COUNT
import io.github.lorddetson.piplayer.library.details.DetailField.RECORDING_ID
import io.github.lorddetson.piplayer.library.details.DetailField.SAMPLE_RATE
import io.github.lorddetson.piplayer.library.details.DetailField.SIZE
import io.github.lorddetson.piplayer.library.details.DetailField.TAGS
import io.github.lorddetson.piplayer.library.details.DetailField.TAG_ENCODING
import io.github.lorddetson.piplayer.library.details.DetailField.TITLE
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.LibraryTrackRefs
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.userdata.PlayStats
import io.github.lorddetson.piplayer.userdata.TrackIdentityEntity
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Track details read from the real databases (docs/library.md §18.2, §18.3), with a probe in place of the pictures. */
@RunWith(AndroidJUnit4::class)
class LibraryTrackDetailsTest {

    private val context = TestFiles.targetContext
    private val database = LibraryDatabase.open(context, file = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(database, index, mayRead = { true }, FakeRules())
    private val user = UserDatabase.open(context, name = null)
    private val ownDir = File(context.cacheDir, "details-own-covers")
    private val own = OwnCovers(user, ownDir)
    private val probe = FakeProbe()
    private val access = object : LibraryTrackRefs.Access {
        override fun <T> read(block: (LibraryDatabase) -> T): T = block(database)
    }
    private val savedBase = File(context.cacheDir, "details-saved-covers")
    private val saved = SavedCovers(user, SavedCovers.directory(savedBase))

    /** Whether online lookups are on, as the details and their plan see it. */
    @Volatile
    private var online = false
    private val details = LibraryTrackDetails(access, user, own, probe, saved) { online }

    private val recording = "8f3471b5-7e6a-48da-86a9-c1c07a0f47ae"
    private val key = "%064x".format(77)

    /** Answers for the pictures, and the URIs asked. */
    private class FakeProbe : PictureProbe {
        var embedded: PictureSize? = null
        var thumbnail = false
        var caa = false
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun online(step: CoverStep.Caa): Boolean {
            asked += "caa ${step.release.release}"
            return caa
        }

        override suspend fun embedded(uri: String): PictureSize? {
            asked += "embedded $uri"
            return embedded
        }

        override suspend fun hasThumbnail(uri: String): Boolean {
            asked += "thumbnail $uri"
            return thumbnail
        }
    }

    @Before
    fun fill() {
        ownDir.deleteRecursively()
        ownDir.mkdirs()
        savedBase.deleteRecursively()
        index.volume().rows = listOf(
            row(1, "Index title", path = "Music/Ann/Red/", name = "03 Song.flac", artist = "Index artist", album = "Index album",
                durationMs = 205_000, year = 1999, trackNumber = 3),
            row(2, "Other", path = "Music/Bob/", name = "other.mp3", artist = "Bob", album = "Plain"),
        )
        runBlocking { sync.sync() }
    }

    @After
    fun close() {
        sync.close()
        database.close()
        user.close()
        ownDir.deleteRecursively()
        savedBase.deleteRecursively()
    }

    private fun fileOf(mediaStoreId: Long) = runBlocking { database.library().files() }.single { it.mediaStoreId == mediaStoreId }.id

    private fun read(fileId: Long = fileOf(1)) = checkNotNull(runBlocking { details.read(fileId) })

    private fun TrackDetails.text(field: DetailField) = (value(field) as? DetailValue.Text)?.text

    /** What the tag pass would store for the file of [mediaStoreId]: its tags, technical data, key and walked length. */
    private fun readTags(
        mediaStoreId: Long = 1,
        coverPresent: Boolean = false,
        coverBytes: Long? = null,
        tagEncoding: String? = null,
        release: String? = null,
    ): Long {
        val fileId = fileOf(mediaStoreId)
        val tags = TrackTags.from(
            mapOf(
                "TITLE" to listOf("Tag title"),
                "ARTIST" to listOf("Ann"),
                "ALBUM" to listOf("Red"),
                "DATE" to listOf("2003-05-12"),
                "MUSICBRAINZ_TRACKID" to listOf(recording),
            ) + listOfNotNull(release?.let { "MUSICBRAINZ_ALBUMID" to listOf(it) }),
        )
        runBlocking {
            val dao = database.library()
            dao.addTrack(TrackEntity.of(fileId, tags))
            dao.markRead(fileId, at = 1, readerVersion = 2, format = "flac", codec = "flac", sampleRate = 96_000, bitDepth = 24,
                channels = 2, bitrate = 2_400, tagEncoding = tagEncoding, coverPresent = coverPresent, coverBytes = coverBytes,
                tagLengthMs = 205_000)
            dao.setContent(fileId, key, 205_412, DurationSource.WALK)
            TrackDisplayWriter(database).refresh(listOf(fileId))
        }
        return fileId
    }

    private fun addIdentity(createdAt: Long = 1_600_000_000_000): Long = runBlocking {
        user.identities().add(
            TrackIdentityEntity(contentKey = Hex.bytes(key), durationMs = 205_412, recordingMbid = null, hintVolumeName = null,
                hintRelativePath = null, hintDisplayName = null, hintSize = null, createdAt = createdAt, missingSince = null),
        )
    }

    @Test
    fun anUnreadFileShowsTheIndexValuesAndThatItsTagsAreNotRead() {
        val details = read()

        assertThat(details.text(TITLE)).isEqualTo("Index title")
        assertThat(details.text(ARTIST)).isEqualTo("Index artist")
        assertThat(details.text(ALBUM)).isEqualTo("Index album")
        assertThat(details.text(DATE)).isEqualTo("1999")
        assertThat(details.value(LENGTH)).isEqualTo(DetailValue.Length(205_000, LengthSource.INDEX))
        assertThat(details.value(TAGS)).isEqualTo(DetailValue.Tags(TagState.NOT_READ))
        assertThat(details.text(FILE_NAME)).isEqualTo("03 Song.flac")
        assertThat(details.value(FOLDER)).isEqualTo(DetailValue.Folder(FakeMediaIndex.PRIMARY, "Music/Ann/Red/"))
        assertThat(details.value(SIZE)).isEqualTo(DetailValue.Bytes(1_001))
        assertThat(details.value(MODIFIED)).isEqualTo(DetailValue.Time(1_700_000_001_000))
        assertThat(details.text(MIME_TYPE)).isEqualTo("audio/mpeg")
        assertThat(details.sections.map { it.first }).doesNotContain(DetailSection.PLAYS)
        assertThat(details.value(ADDED)).isNull()
        assertThat(details.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.NONE))
    }

    @Test
    fun aFailedReadSaysWhy() {
        val fileId = fileOf(1)
        runBlocking { database.library().markFailed(fileId, "unsupported", readerVersion = 2) }
        assertThat(read(fileId).value(TAGS)).isEqualTo(DetailValue.Tags(TagState.UNSUPPORTED))

        runBlocking { database.library().markFailed(fileId, "open", readerVersion = null) }
        assertThat(read(fileId).value(TAGS)).isEqualTo(DetailValue.Tags(TagState.CANNOT_READ))

        runBlocking { database.library().markFailed(fileId, "crash", readerVersion = null) }
        assertThat(read(fileId).value(TAGS)).isEqualTo(DetailValue.Tags(TagState.FAILED))
    }

    @Test
    fun aReadFileShowsItsMergedTagsAndTechnicalData() {
        val fileId = readTags(tagEncoding = "windows-1251")

        val details = read(fileId)

        assertThat(details.text(TITLE)).isEqualTo("Tag title")
        assertThat(details.text(ARTIST)).isEqualTo("Ann")
        assertThat(details.text(ALBUM)).isEqualTo("Red")
        assertThat(details.text(DATE)).isEqualTo("2003-05-12")
        assertThat(details.text(FORMAT)).isEqualTo("flac")
        assertThat(details.text(CODEC)).isEqualTo("flac")
        assertThat(details.value(SAMPLE_RATE)).isEqualTo(DetailValue.Hertz(96_000))
        assertThat(details.value(BITRATE)).isEqualTo(DetailValue.Kbps(2_400))
        assertThat(details.value(LENGTH)).isEqualTo(DetailValue.Length(205_412, LengthSource.WALK))
        assertThat(details.value(INDEX_LENGTH)).isEqualTo(DetailValue.Length(205_000, LengthSource.INDEX))
        assertThat(details.text(TAG_ENCODING)).isEqualTo("windows-1251")
        assertThat(details.value(TAGS)).isNull()
        assertThat(details.values(RECORDING_ID)).containsExactly(DetailValue.MusicBrainz(MusicBrainzKind.RECORDING, recording))
        // The same values as the lists show.
        val shown = runBlocking { database.library().displays() }.single { it.fileId == fileId }
        assertThat(details.title).isEqualTo(shown.title)
        assertThat(details.artist).isEqualTo(shown.artist)
        assertThat(details.album).isEqualTo(shown.album)
    }

    @Test
    fun theIdentityGivesTheDateAddedThePlaysAndTheFavorite() {
        val fileId = readTags()
        val identity = addIdentity(createdAt = 1_600_000_000_000)
        val now = System.currentTimeMillis()
        runBlocking {
            user.favorites().add(identity, now)
            // One play long folded into the totals, one recent: both count, the recent one is the last.
            user.plays().addEvent(identity, startedAt = 1_000, listenedMs = 100_000)
            user.plays().addEvent(identity, startedAt = now - 60_000, listenedMs = 100_000)
            PlayStats(user).fold(now)
        }

        val details = read(fileId)

        assertThat(details.value(ADDED)).isEqualTo(DetailValue.Time(1_600_000_000_000))
        assertThat(details.value(FAVORITE)).isEqualTo(DetailValue.Flag(true))
        assertThat(details.value(PLAY_COUNT)).isEqualTo(DetailValue.Count(2))
        assertThat(details.value(LAST_PLAYED)).isEqualTo(DetailValue.Time(now - 60_000))
    }

    @Test
    fun anIdentityNeverPlayedShowsZeroAndNo() {
        val fileId = readTags()
        addIdentity()

        val details = read(fileId)

        assertThat(details.value(FAVORITE)).isEqualTo(DetailValue.Flag(false))
        assertThat(details.value(PLAY_COUNT)).isEqualTo(DetailValue.Count(0))
        assertThat(details.value(LAST_PLAYED)).isNull()
    }

    @Test
    fun theSongsOwnCoverComesFirstWithItsStoredSize() {
        val fileId = readTags(coverPresent = true, coverBytes = 48_213)
        val identity = addIdentity()
        probe.embedded = PictureSize(600, 600)
        File(ownDir, "song.jpg").writeBytes(byteArrayOf(1))
        File(ownDir, "album.jpg").writeBytes(byteArrayOf(1))
        runBlocking {
            own.setTrack(identity, CoverCopy("song.jpg", 1500, 1000))
            own.setAlbum(albumKey(fileId), CoverCopy("album.jpg", 800, 800))
        }

        val details = read(fileId)

        assertThat(details.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.SONG_OWN))
        assertThat(details.value(COVER_SIZE)).isEqualTo(DetailValue.Pixels(1500, 1000, null))
        assertThat(probe.asked).isEmpty()
    }

    @Test
    fun theAlbumsOwnCoverComesNext() {
        val fileId = readTags(coverPresent = true, coverBytes = 48_213)
        addIdentity()
        File(ownDir, "album.jpg").writeBytes(byteArrayOf(1))
        runBlocking { own.setAlbum(albumKey(fileId), CoverCopy("album.jpg", 800, 700)) }

        val details = read(fileId)

        assertThat(details.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.ALBUM_OWN))
        assertThat(details.value(COVER_SIZE)).isEqualTo(DetailValue.Pixels(800, 700, null))
    }

    @Test
    fun anOwnCoverWhoseCopyIsGoneDoesNotCount() {
        val fileId = readTags(coverPresent = true, coverBytes = 48_213)
        val identity = addIdentity()
        probe.embedded = PictureSize(600, 600)
        File(ownDir, "song.jpg").writeBytes(byteArrayOf(1))
        runBlocking { own.setTrack(identity, CoverCopy("song.jpg", 1500, 1000)) }
        File(ownDir, "song.jpg").delete()

        assertThat(read(fileId).value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.EMBEDDED))
    }

    @Test
    fun theEmbeddedPictureHasItsSizeAndBytes() {
        val fileId = readTags(coverPresent = true, coverBytes = 48_213)
        probe.embedded = PictureSize(600, 500)

        val details = read(fileId)

        assertThat(details.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.EMBEDDED))
        assertThat(details.value(COVER_SIZE)).isEqualTo(DetailValue.Pixels(600, 500, 48_213))
        assertThat(probe.asked).containsExactly("embedded content://media/external/audio/media/1")
    }

    @Test
    fun withoutAnEmbeddedPictureTheThumbnailCountsWithoutASize() {
        val fileId = readTags(coverPresent = false)
        probe.thumbnail = true

        val details = read(fileId)

        assertThat(details.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.THUMBNAIL))
        assertThat(details.value(COVER_SIZE)).isNull()
        // The tag pass found no picture: it is not looked for.
        assertThat(probe.asked).containsExactly("thumbnail content://media/external/audio/media/1")
    }

    @Test
    fun anUnreadFileIsProbedForBothPictures() {
        probe.thumbnail = true

        val details = read()

        assertThat(details.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.THUMBNAIL))
        assertThat(probe.asked).containsExactly(
            "embedded content://media/external/audio/media/1",
            "thumbnail content://media/external/audio/media/1",
        )
    }

    @Test
    fun aFileNotInTheLibraryHasNoDetails() {
        assertThat(runBlocking { details.read(9_999) }).isNull()
    }

    private fun albumKey(fileId: Long) = checkNotNull(LibraryLists(database).links(fileId)?.albumKey)

    // M3 (docs/library.md §13.1, §18.3): the decided cover, Cover Art Archive and the plan the details share with the loader.

    /** A JPEG header with a start of frame of [width] by [height] pixels. */
    private fun jpeg(width: Int, height: Int) = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08,
        (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(),
        0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
        0x00, 0xFF.toByte(), 0xD9.toByte(),
    )

    /** Saves a cover of [width] by [height] and decides it for [fileId], as the decision mirror would (TI §7.1). */
    private fun decideCover(fileId: Long, width: Int = 640, height: Int = 480): Long = runBlocking {
        val id = checkNotNull(saved.save(jpeg(width, height), DECIDED_RELEASE, SavedCovers.Source.RELEASE))
        decide(fileId, "cover" to id.toString())
        id
    }

    private fun decide(fileId: Long, vararg values: Pair<String, String?>) = runBlocking {
        val rows = database.decided().valuesOf(listOf(fileId)).filter { row -> values.none { it.first == row.field } } +
            values.map { (field, value) -> DecidedMirrorEntity(fileId, field, value, DecidedMirrorEntity.LEVEL_IDENTIFICATION, 1) }
        database.replaceDecided(listOf(fileId), rows)
    }

    @Test
    fun aDecidedCoverComesBeforeTheEmbeddedPictureAsSavedInPiPlayerWithItsStoredSize() {
        val fileId = readTags(coverPresent = true, coverBytes = 48_213)
        probe.embedded = PictureSize(600, 600)
        decideCover(fileId, 640, 480)

        val details = read(fileId)

        assertThat(details.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.SAVED))
        assertThat(details.value(COVER_SIZE)).isEqualTo(DetailValue.Pixels(640, 480, jpeg(640, 480).size.toLong()))
        assertThat(probe.asked).isEmpty()
    }

    @Test
    fun theOwnCoversStillComeBeforeADecidedCover() {
        val fileId = readTags(coverPresent = true, coverBytes = 48_213)
        addIdentity()
        decideCover(fileId)
        File(ownDir, "album.jpg").writeBytes(byteArrayOf(1))
        runBlocking { own.setAlbum(albumKey(fileId), CoverCopy("album.jpg", 800, 700)) }

        assertThat(read(fileId).value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.ALBUM_OWN))
    }

    @Test
    fun aDecidedCoverWhoseFileIsGoneOrThatIsEmptyDoesNotCount() {
        val fileId = readTags(coverPresent = true, coverBytes = 48_213)
        probe.embedded = PictureSize(600, 600)
        decideCover(fileId)
        savedBase.deleteRecursively()

        assertThat(read(fileId).value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.EMBEDDED))

        // An identification that saved no cover decides it empty: it never hides the file's picture.
        decide(fileId, "cover" to null)
        assertThat(read(fileId).value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.EMBEDDED))
    }

    @Test
    fun coverArtArchiveComesLastWithoutASizeAndOnlyWhileOnlineLookupsAreOn() {
        val fileId = readTags(coverPresent = false, release = TAG_RELEASE)
        probe.caa = true

        assertThat(read(fileId).value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.NONE))
        assertThat(probe.asked).noneMatch { it.startsWith("caa") }

        online = true
        probe.asked.clear()
        val details = read(fileId)

        assertThat(details.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.CAA))
        assertThat(details.value(COVER_SIZE)).isNull()
        assertThat(probe.asked).containsExactly("thumbnail content://media/external/audio/media/1", "caa $TAG_RELEASE")
    }

    @Test
    fun theDecidedReleaseComesBeforeTheTagsOnesAndADecidedFieldWithoutAReleaseHasNone() {
        val fileId = readTags(coverPresent = false, release = TAG_RELEASE)
        online = true
        decide(fileId, "release_mbid" to DECIDED_RELEASE)

        read(fileId)
        assertThat(probe.asked).contains("caa $DECIDED_RELEASE").doesNotContain("caa $TAG_RELEASE")

        probe.asked.clear()
        decide(fileId, "release_mbid" to null)
        read(fileId)
        assertThat(probe.asked).noneMatch { it.startsWith("caa") }
    }

    /** A real JPEG of 8 by 8 pixels, which the loader's sources decode; [shade] makes its bytes differ. */
    private fun realJpeg(shade: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(shade, 0, 0)) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** Loaders whose pictures are the steps that gave them, so a test can tell which source the loader shows. */
    private object StepCodec : ImageCodec<CoverStep> {
        override fun encode(image: CoverStep): ByteArray? = null
        override fun decode(bytes: ByteArray, size: Int): CoverStep? = null
        override fun weight(image: CoverStep): Long = 1
    }

    @Test
    fun theSourceIsAlwaysTheOneTheLoaderShows() {
        // The loader with the production plan (the facts, the own covers and the index of saved covers it is built with)
        // and the production sources for own and saved covers; the file's pictures and Cover Art Archive are the probe's.
        val fileId = readTags(coverPresent = true, coverBytes = 1_000, release = TAG_RELEASE)
        val identity = addIdentity()
        val album = albumKey(fileId)
        online = true
        val facts = LibraryCoverFacts(access, user)
        val local = AndroidCoverSources(context, ownDir, saved)
        val sources = CoverSources { step, size ->
            val shown = when (step) {
                is CoverStep.Own, is CoverStep.Saved -> local.load(step, size) != null
                is CoverStep.Embedded -> probe.embedded != null
                is CoverStep.Thumbnail -> probe.thumbnail
                is CoverStep.Caa -> probe.caa
            }
            if (shown) step else null
        }
        val savedBytes = realJpeg(200)
        for (case in 0 until 3 * 3 * 3 * 8) {
            // Each own cover and the decided cover: none, there, or its file gone (after a restore).
            val songOwn = case % 3
            val albumOwn = case / 3 % 3
            val decided = case / 9 % 3
            val pictures = case / 27
            probe.embedded = if (pictures and 1 != 0) PictureSize(500, 500) else null
            probe.thumbnail = pictures and 2 != 0
            probe.caa = pictures and 4 != 0
            runBlocking {
                when (songOwn) {
                    0 -> own.removeTrack(identity)
                    else -> {
                        File(ownDir, "song$case.jpg").writeBytes(realJpeg(case % 200))
                        own.setTrack(identity, CoverCopy("song$case.jpg", 8, 8))
                        if (songOwn == 2) File(ownDir, "song$case.jpg").delete()
                    }
                }
                when (albumOwn) {
                    0 -> own.removeAlbum(album)
                    else -> {
                        File(ownDir, "album$case.jpg").writeBytes(realJpeg(case % 200 + 1))
                        own.setAlbum(album, CoverCopy("album$case.jpg", 8, 8))
                        if (albumOwn == 2) File(ownDir, "album$case.jpg").delete()
                    }
                }
                when (decided) {
                    0 -> decide(fileId, "cover" to null)
                    else -> {
                        val id = checkNotNull(saved.save(savedBytes, DECIDED_RELEASE, SavedCovers.Source.RELEASE))
                        decide(fileId, "cover" to id.toString())
                        if (decided == 2) checkNotNull(saved.file(id)).delete()
                    }
                }
            }
            val loader = CoverLoader(
                plan = { request -> CoverPlan.of(request, facts, own.also { it.load() }, saved.names(), online) },
                sources = sources,
                codec = StepCodec,
                disk = null,
                memoryWeight = 1_000,
            )

            val step = runBlocking { loader.load(CoverRequest.Track(fileId), 96) }
            val expected = when (step) {
                null -> CoverSource.NONE
                is CoverStep.Own -> if (step.fileName == own.track(identity)) CoverSource.SONG_OWN else CoverSource.ALBUM_OWN
                is CoverStep.Saved -> CoverSource.SAVED
                is CoverStep.Embedded -> CoverSource.EMBEDDED
                is CoverStep.Thumbnail -> CoverSource.THUMBNAIL
                is CoverStep.Caa -> CoverSource.CAA
            }

            assertThat(read(fileId).value(COVER_SOURCE))
                .describedAs("case $case: song own $songOwn, album own $albumOwn, decided $decided, pictures $pictures")
                .isEqualTo(DetailValue.Cover(expected))
        }
    }

    private companion object {
        const val TAG_RELEASE = "3a6f1a2b-1111-4222-8333-000000000001"
        const val DECIDED_RELEASE = "3a6f1a2b-1111-4222-8333-000000000002"
    }
}
