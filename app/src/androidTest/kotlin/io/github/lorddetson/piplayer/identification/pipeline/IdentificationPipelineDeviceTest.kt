package io.github.lorddetson.piplayer.identification.pipeline

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.fakes.FakeAcoustId
import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.RecordingRequestLog
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.fakes.TestTls
import io.github.lorddetson.piplayer.fakes.formFields
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.ProviderBaseUrls
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.release.EditionSource
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.PRIMARY
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.tagpass.PassRead
import io.github.lorddetson.piplayer.library.tagpass.TagPass
import io.github.lorddetson.piplayer.net.InMemoryOnlineGate
import io.github.lorddetson.piplayer.net.OnlineGate
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.Tagger
import io.github.lorddetson.piplayer.tagging.TaggerClient
import io.github.lorddetson.piplayer.tagging.TaggerTesting
import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import java.io.File
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pipeline end to end on a device (TI §3 "One run", §11.2): a fixture file opened by its URI goes through `:tagger`,
 * the platform's decoders, Chromaprint and the native encoder, and the HTTPS fakes of AcoustID and MusicBrainz answer
 * its lookups. The library database is one of the test's own, in memory. A library file is the fixture put into the
 * media index as the app's own file, under a row of the test's media index with the same ID; `:tagger` counts its reads
 * in the test's database. Nothing here reaches a real host.
 */
@RunWith(AndroidJUnit4::class)
class IdentificationPipelineDeviceTest {

    private val context = TestFiles.targetContext
    private val library = LibraryDatabase.open(context, file = null)
    private val acoustIdServer = MockWebServer()
    private val musicBrainzServer = MockWebServer()
    private val servers = listOf(acoustIdServer, musicBrainzServer)
    private val log = RecordingRequestLog()
    private val files = mutableListOf<File>()
    private val resolver = context.contentResolver
    private val inserted = mutableListOf<Uri>()
    private val index = FakeMediaIndex()
    private val sync by lazy { LibrarySync(library, index, mayRead = { true }, FakeRules()) }
    private val libraryClient by lazy { TaggerTesting.client(library, 30.seconds) }
    private var usedLibrary = false

    /** The fingerprints AcoustID was sent, in order; the bodies of the log redact nothing but the key. */
    private val sentFingerprints = CopyOnWriteArrayList<String>()

    @Before
    fun start() {
        // Whatever the device computes, AcoustID links it to the Harbor Lights recording by its ID only.
        val acoustId = FakeAcoustId().otherwise(FakeAcoustId.ID_ONLY)
        acoustIdServer.dispatcher = log.wrap(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                request.formFields()["fingerprint"]?.let(sentFingerprints::add)
                return acoustId.dispatch(request)
            }
        })
        musicBrainzServer.dispatcher = log.wrap(FakeMusicBrainz().withHarborLights())
        servers.forEach {
            TestTls.useHttps(it)
            it.start()
        }
    }

    @After
    fun stop() {
        servers.forEach { it.close() }
        if (usedLibrary) {
            libraryClient.close()
            sync.close()
        }
        inserted.forEach { runCatching { resolver.delete(it, null, null) } }
        library.close()
        files.forEach { it.delete() }
    }

    private fun services(gate: OnlineGate = TestServices.openGate()): IdentificationServices = TestServices.services(
        baseUrls = ProviderBaseUrls(acoustIdServer.url("/v2/"), musicBrainzServer.url("/ws/2/"), acoustIdServer.url("/caa/")),
        gate = gate,
        acoustIdApiKey = KEY,
        configureHttp = { TestTls.trustTestServers(this) },
    )

    private fun pipeline(services: IdentificationServices, client: TaggerClient = Tagger.client(context)) = IdentificationPipeline(
        services = services,
        local = AndroidLocalStage(context, client, LibraryAccess.of(library)),
        fingerprints = LibraryFingerprintCache(context, LibraryAccess.of(library)),
        encoder = ChromaprintEncoder,
        country = { "GB" },
    )

    private fun fixture(): String = Uri.fromFile(TestFiles.copy(FIXTURE).also { files += it }).toString()

    /**
     * The fixture as a library file: put into the media index as the app's own file, synced from a row of the test's
     * index with its ID, and, when [read] is given, read by a tag pass that reports [read] and [TAGS] for it.
     */
    private suspend fun libraryFile(read: TagReport.Content?): Long {
        usedLibrary = true
        deleteLeftovers()
        val collection = MediaStore.Audio.Media.getContentUri(PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(collection, values)) { "Could not insert $NAME" }
        inserted += uri
        resolver.openOutputStream(uri)!!.use { out -> TestFiles.assets.open(FIXTURE).use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)

        index.volume().rows = listOf(row(ContentUris.parseId(uri), path = FOLDER, name = NAME))
        sync.sync()
        if (read != null) {
            val report = TagReport(
                properties = TAGS,
                audio = TagReport.Audio(12_000, 128, 44_100, 2),
                container = "MPEG",
                content = read,
            )
            TagPass(library, sync, { PassRead.Replied(report) }, cyrillicLanguage = { false }).run()
        }
        return library.library().files().single().id
    }

    /** Files of an earlier run that died before its clean-up; the app may delete its own files. */
    private fun deleteLeftovers() {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        resolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            arrayOf(FOLDER, context.packageName),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { resolver.delete(ContentUris.withAppendedId(collection, cursor.getLong(0)), null, null) }
            }
        }
    }

    @Test
    fun aFixtureFileIsIdentifiedEndToEndAndASecondRunTakesItsCachedFingerprint() = runBlocking<Unit> {
        val uri = fixture()
        val progress = Collections.synchronizedList(mutableListOf<PipelineProgress>())

        val first = pipeline(services()).run(FileRef.Opened(uri)) { progress += it }

        val found = first.outcome as IdentificationOutcome.Found
        val fingerprintStage = first.stages.firstOrNull { it.stage == PipelineStage.FINGERPRINT }?.result
        assertThat(fingerprintStage).describedAs("duration %s ms", first.durationMs).isEqualTo(StageResult.Ran(1))
        assertThat(found.band).isEqualTo(Band.HIGH)
        assertThat(found.choice.suggested!!.release.id).isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
        assertThat(found.choice.suggested.source).isEqualTo(EditionSource.RANKING)
        assertThat(found.acoustIdTrackId).isEqualTo(ID_ONLY_TRACK)
        assertThat(first.fingerprint).isEqualTo(FingerprintSource.COMPUTED)
        // A CBR MP3 without a frame-count header: its length is walked before the lookup (TI §3.1).
        assertThat(first.lengthWalked).isTrue()
        val seconds = (first.durationMs!! + 500) / 1_000
        assertThat(seconds).isGreaterThan(3)
        assertThat(progress).contains(
            PipelineProgress.Decoding,
            PipelineProgress.ReleasePages(FakeMusicBrainz.HARBOR_RECORDING, 1),
            PipelineProgress.ReleasePages(FakeMusicBrainz.HARBOR_RECORDING, 2),
        )
        val key = first.file!!.target.contentKey
        val row = library.library().fingerprint(key)!!
        assertThat(row.acoustIdId).isEqualTo(ID_ONLY_TRACK)
        assertThat(row.decoderPath).startsWith("media3-")
        assertThat(sentFingerprints).hasSize(1)
        assertThat(sentFingerprints.single()).startsWith("AQ")
        assertThat(log.entries.single { it.path.startsWith("/v2/") }.body).contains("duration=$seconds&")
        assertThat(log.entries.toString()).doesNotContain(KEY)

        progress.clear()
        val second = pipeline(services()).run(FileRef.Opened(uri)) { progress += it }

        assertThat(second.fingerprint).isEqualTo(FingerprintSource.CACHED)
        assertThat(progress).doesNotContain(PipelineProgress.Decoding)
        assertThat((second.outcome as IdentificationOutcome.Found).band).isEqualTo(Band.HIGH)
        // The cached raw items encode to the string the session sent.
        assertThat(sentFingerprints).hasSize(2)
        assertThat(sentFingerprints[1]).isEqualTo(sentFingerprints[0])
        assertThat(library.library().fingerprint(key)!!.acoustIdId).isEqualTo(ID_ONLY_TRACK)
    }

    @Test
    fun withTheGateClosedNothingIsReadDecodedOrSent() = runBlocking<Unit> {
        val result = pipeline(services(gate = InMemoryOnlineGate())).run(FileRef.Opened(fixture()))

        assertThat(result.outcome).isEqualTo(IdentificationOutcome.Disabled)
        assertThat(result.file).isNull()
        assertThat(servers.sumOf { it.requestCount }).isZero()
        assertThat(library.library().fingerprintKeys()).isEmpty()
    }

    @Test
    fun aLibraryFileTheTagPassReadTakesItsValuesFromTheLibraryAndWalksALengthLeftForAWalk() = runBlocking<Unit> {
        val fileId = libraryFile(TagReport.Content(STORED_KEY, "mp3", DurationPlan(null, DurationCheck.WALK)))
        assertThat(library.library().file(fileId)!!.durationSource).isEqualTo(DurationSource.PENDING_WALK)

        val result = pipeline(services(), libraryClient).run(FileRef.Library(fileId))

        val file = result.file!!
        // The key, the tags and the folder are the library's: a read of the file would give its own key and no tags.
        assertThat(file.target).isEqualTo(IdentificationTarget.LibraryFile(fileId, STORED_KEY))
        assertThat(file.tags!!.title).isEqualTo("Paper Lanterns")
        assertThat(result.hint!!.title).isEqualTo("Paper Lanterns")
        assertThat(result.hint.artist).isEqualTo("The Quiet Harbors")
        assertThat(file.folder).isEqualTo(FolderKey(PRIMARY, FOLDER).key)
        assertThat(file.fileName).isEqualTo(NAME)
        // The length the pass left for a walk is walked in `:tagger` and sent to AcoustID.
        assertThat(result.lengthWalked).isTrue()
        val seconds = (result.durationMs!! + 500) / 1_000
        assertThat(seconds).isGreaterThan(3)
        assertThat(log.entries.single { it.path.startsWith("/v2/") }.body).contains("duration=$seconds&")
        assertThat((result.outcome as IdentificationOutcome.Found).band).isEqualTo(Band.HIGH)
        assertThat(library.library().fingerprint(STORED_KEY)!!.acoustIdId).isEqualTo(ID_ONLY_TRACK)
    }

    @Test
    fun aLibraryFileTheTagPassHasNotReadIsReadThroughTheTagger() = runBlocking<Unit> {
        val fileId = libraryFile(read = null)
        assertThat(library.library().file(fileId)!!.tagPassAt).isNull()

        val result = pipeline(services(), libraryClient).run(FileRef.Library(fileId))

        val file = result.file!!
        val target = file.target as IdentificationTarget.LibraryFile
        assertThat(target.fileId).isEqualTo(fileId)
        // The key comes from the file itself.
        assertThat(target.contentKey).matches("[0-9a-f]{64}").isNotEqualTo(STORED_KEY)
        assertThat(file.folder).isEqualTo(FolderKey(PRIMARY, FOLDER).key)
        assertThat(file.fileName).isEqualTo(NAME)
        assertThat(result.lengthWalked).isTrue()
        assertThat((result.outcome as IdentificationOutcome.Found).band).isEqualTo(Band.HIGH)
    }

    private companion object {
        const val KEY = "testKey123"
        /** Long enough for Chromaprint to give items; 2 s of audio give none. */
        const val FIXTURE = "playback/seek-mp3-cbr-no-header.mp3"
        const val ID_ONLY_TRACK = "6f1c1f4e-3b0d-4c8e-9a52-000000000009"
        const val FOLDER = "Music/PiPlayerPipelineTest/"
        const val NAME = "track.mp3"

        /** A content key no read of the fixture gives. */
        val STORED_KEY = "ab".repeat(32)
        val TAGS = mapOf("TITLE" to listOf("Paper Lanterns"), "ARTIST" to listOf("The Quiet Harbors"))
    }
}
