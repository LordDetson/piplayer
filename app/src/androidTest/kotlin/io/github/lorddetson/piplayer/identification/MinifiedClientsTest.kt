package io.github.lorddetson.piplayer.identification

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.BuildConfig
import io.github.lorddetson.piplayer.fakes.FakeAcoustId
import io.github.lorddetson.piplayer.fakes.FakeCaa
import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.Fixture
import io.github.lorddetson.piplayer.fakes.RecordingRequestLog
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.fakes.TestTls
import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdClient
import io.github.lorddetson.piplayer.identification.caa.CoverEntity
import io.github.lorddetson.piplayer.identification.caa.CoverSize
import io.github.lorddetson.piplayer.identification.caa.CoverSource
import io.github.lorddetson.piplayer.identification.musicbrainz.RecordingSearchResponse
import io.github.lorddetson.piplayer.identification.text.MusicBrainzRef
import io.github.lorddetson.piplayer.net.RetryPolicy
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every identification client decodes the answers of its service in a build shrunk, optimized and obfuscated by R8
 * (TI §9, §11.2; M3 step 7). The fixtures of the AcoustID lookup, the MusicBrainz search, recording, ISRC and release
 * lookups and paged release browses, and the Cover Art Archive front image and release index are served by the HTTPS
 * fakes; nothing reaches a real host.
 *
 * The check runs against the minified build through AGP's own switch, not a self-test inside the app: with
 * `-Ppiplayer.minifiedTests=true` the instrumented tests run against the `benchmark` build (the release build with R8,
 * signed with the debug key), whose keep rules for this run (`app/proguard-minified-tests.pro`) keep only the entry
 * points these tests call. The DTOs and the parsing code below them are shrunk and renamed as in a release, so the
 * results are read through their `toString`, which R8 keeps with every field it prints, never through members that a
 * release would be free to drop. Locally, on the emulator (the install tasks serve every connected device, so
 * ANDROID_SERIAL names the emulator):
 *
 * ```
 * ANDROID_SERIAL=<emulator> gradlew :app:installBenchmark :app:installBenchmarkAndroidTest -Ppiplayer.minifiedTests=true
 * python <main folder>/tools/instrumented/run_instrumented.py <emulator> MinifiedClientsTest --no-install -e minified true
 * ```
 *
 * With `-e minified true` the run fails unless the build under test is the benchmark one, so a debug build reinstalled
 * by mistake cannot pass for the minified check. The normal run (and CI) runs the same tests against the debug build.
 */
@RunWith(AndroidJUnit4::class)
class MinifiedClientsTest {

    private val acoustIdServer = MockWebServer()
    private val musicBrainzServer = MockWebServer()
    private val caaServer = MockWebServer()
    private val archiveServer = MockWebServer()
    private val servers = listOf(acoustIdServer, musicBrainzServer, caaServer, archiveServer)
    private val log = RecordingRequestLog()

    private val acoustId = FakeAcoustId().on(INVALID_KEY_FINGERPRINT, FakeAcoustId.ERROR_4)
    private val musicBrainz = FakeMusicBrainz()
        .on("recording/$LIVE", mb("recording-lookup.json"))
        .on("release/$LIVE_RELEASE", mb("release-lookup.json"))
        .onBrowse(
            "recording", STUDIO,
            mapOf(0 to mb("release-browse-paging-1.json"), 8 to mb("release-browse-paging-2.json")), status = "official",
        )
        .onBrowse("release-group", LIVE_GROUP, mapOf(0 to mb("release-group-browse.json")))

    @Before
    fun start() {
        val caa = FakeCaa { archiveServer.url("/") }.withImages(COVER_RELEASE).withIndex(FakeCaa.INDEX_RELEASE)
        acoustIdServer.dispatcher = log.wrap(acoustId)
        musicBrainzServer.dispatcher = log.wrap(musicBrainz)
        caaServer.dispatcher = log.wrap(caa)
        archiveServer.dispatcher = log.wrap(caa.archive)
        servers.forEach {
            TestTls.useHttps(it)
            it.start()
        }
    }

    @After
    fun stop() {
        servers.forEach { it.close() }
    }

    /** No retries: a body the minified parser cannot read fails the call at once, with the parser's error as cause. */
    private fun services() = TestServices.services(
        baseUrls = ProviderBaseUrls(acoustIdServer.url("/v2/"), musicBrainzServer.url("/ws/2/"), caaServer.url("/")),
        retryPolicy = RetryPolicy(maxRetries = 0),
        acoustIdApiKey = KEY,
        configureHttp = { TestTls.trustTestServers(this) },
    )

    private fun acoustIdClient(): AcoustIdClient = (services().fingerprintStage as FingerprintStage.Available).acoustId

    @Test
    fun theBuildUnderTestIsMinifiedExactlyWhenItIsTheBenchmarkBuild() {
        // R8 renames the classes it does not have to keep; the names stay in the debug build.
        val renamed = RecordingSearchResponse::class.java.name !=
            "io.github.lorddetson.piplayer.identification.musicbrainz.RecordingSearchResponse"
        assertThat(renamed).describedAs("renamed in the ${BuildConfig.BUILD_TYPE} build")
            .isEqualTo(BuildConfig.BUILD_TYPE == "benchmark")
    }

    @Test
    fun aRunThatNamesTheBuildTestsThatBuild() {
        // `-e minified true` says the run is meant for the minified build: a reinstalled debug build must not pass it.
        val minified = InstrumentationRegistry.getArguments().getString(MINIFIED_ARGUMENT)?.toBooleanStrict() ?: return
        assertThat(BuildConfig.BUILD_TYPE).describedAs("the build under a run with -e $MINIFIED_ARGUMENT $minified")
            .isEqualTo(if (minified) "benchmark" else "debug")
    }

    @Test
    fun anAcoustIdLookupDecodesTracksRecordingsReleaseGroupsAndReleases() {
        val text = runBlocking { acoustIdClient().lookup(FINGERPRINT, 214_400) }.toString()

        assertThat(text).startsWith("AcoustIdResponse(status=ok, results=[")
        assertThat(text).contains(
            "id=6f1c1f4e-3b0d-4c8e-9a52-1d2e3f405162, score=0.97",
            "title=Paper Lanterns, duration=214.4",
            "name=The Quiet Harbors, joinPhrase= feat. ",
            "name=Mira Vale, joinPhrase=)",
            "title=Harbor Lights, type=Album, secondaryTypes=[], releases=[",
            "country=XW, date=AcoustIdDateDto(year=2019, month=3, day=1), trackCount=11",
            "secondaryTypes=[Compilation]",
            "isrcs=[XXA001900003]",
            "AcoustIdRecordingDto(id=b0e4d1f2-1111-4111-8111-000000000003, title=null, duration=null",
            "id=7a2b3c4d-5e6f-4071-8293-a4b5c6d7e8f9, score=0.41, recordings=[]",
        )
        assertThat(text.occurrences("AcoustIdResultDto(")).isEqualTo(2)
        assertThat(text.occurrences("AcoustIdRecordingDto(")).isEqualTo(3)
        assertThat(log.entries.toString()).doesNotContain(KEY)
    }

    @Test
    fun anAcoustIdErrorAnswerIsDecodedIntoItsError() {
        val error = runCatching { runBlocking { acoustIdClient().lookup(INVALID_KEY_FINGERPRINT, 214_400) } }.exceptionOrNull()
        assertThat(error?.message).isEqualTo("AcoustID refused the application key: invalid API key")
    }

    @Test
    fun aMusicBrainzSearchDecodesRecordingsCreditsAndReleases() {
        val text = runBlocking { services().musicBrainz.searchRecordings("recording:(paper lanterns)", 25) }.toString()

        assertThat(text).startsWith("RecordingSearchResponse(")
        assertThat(text).contains(
            "count=2, offset=0",
            "id=11111111-1111-4111-8111-111111111111",
            "title=Paper Lanterns, length=214000",
            "NameCreditDto(name=The Quiet Harbors",
            "NameCreditDto(name=Ada Vale",
            "title=Live at the Pier",
            "trackOffset=10",
            "id=12121212-1212-4212-8212-121212121212",
        )
        assertThat(text.occurrences("RecordingDto(id=")).isEqualTo(2)
    }

    @Test
    fun aRecordingLookupDecodesItsCreditAndIsrcs() {
        val text = runBlocking { services().musicBrainz.lookupRecording(LIVE) }.toString()

        assertThat(text).startsWith("RecordingDto(id=$LIVE")
        assertThat(text).contains("title=Bohemian Rhapsody, length=280893", "name=Queen", "isrcs=[GBCEE8600016]")
    }

    @Test
    fun anIsrcLookupDecodesItsRecordingsWithTheirCredits() {
        val text = runBlocking { services().musicBrainz.lookupIsrc(FakeMusicBrainz.KNOWN_ISRC) }.toString()

        assertThat(text).startsWith("[RecordingDto(id=11111111-1111-4111-8111-111111111111")
        assertThat(text).contains("title=Paper Lanterns, length=214000", "NameCreditDto(name=The Quiet Harbors")
        assertThat(log.entries.single().path).contains("inc=artists")
    }

    @Test
    fun aReleaseLookupDecodesLabelsEventsGroupCoverFlagsAndEveryTrack() {
        val text = runBlocking { services().musicBrainz.lookupRelease(LIVE_RELEASE) }.toString()

        assertThat(text).startsWith("ReleaseDto(id=$LIVE_RELEASE, title=Live Magic, status=Official")
        assertThat(text).contains(
            "barcode=4988031330043",
            "catalogNumber=UICY-15820, label=LabelDto(",
            "name=Virgin",
            "AreaDto(isoCodes=[JP])",
            "ReleaseGroupDto(id=$LIVE_GROUP, title=Live Magic, primaryType=Album, secondaryTypes=[Live], " +
                "firstReleaseDate=1986-12-01",
            "coverArtArchive=CoverArtArchiveDto(count=0, front=false, back=false, artwork=false, darkened=false)",
            "sortName=Queen",
            "position=9",
            "recording=RecordingDto(id=$LIVE",
        )
        assertThat(text.occurrences("TrackDto(")).isEqualTo(15)
    }

    @Test
    fun aPastedIdIsTriedAsARecordingThenAsARelease() {
        val text = runBlocking { services().musicBrainz.lookup(MusicBrainzRef.AnyEntity(LIVE_RELEASE)) }.toString()
        assertThat(text).startsWith("Release(release=ReleaseDto(id=$LIVE_RELEASE, title=Live Magic")
    }

    @Test
    fun aRecordingsReleasesAreBrowsedPageByPage() {
        val text = runBlocking { services().musicBrainz.releasesOfRecording(STUDIO) }.toString()

        assertThat(text).startsWith("ReleaseList(releases=[ReleaseDto(")
        assertThat(text).endsWith("totalCount=14, pages=2, capped=false, nonOfficialFallback=false)")
        assertThat(text.occurrences("ReleaseDto(id=")).isEqualTo(14)
        assertThat(log.entries.map { it.path.substringAfter("offset=").substringBefore('&') }).containsExactly("0", "8")
    }

    @Test
    fun aReleaseGroupsReleasesAreBrowsedInEveryStatus() {
        val text = runBlocking { services().musicBrainz.releasesOfReleaseGroup(LIVE_GROUP) }.toString()

        assertThat(text).startsWith("ReleaseList(releases=[ReleaseDto(")
        assertThat(text).contains("totalCount=12, pages=1, capped=false")
        assertThat(text.occurrences("ReleaseDto(id=")).isEqualTo(12)
        assertThat(text).contains("MediumDto(position=1, title=, format=SHM-CD, trackCount=15")
    }

    @Test
    fun aFrontImageIsFetchedThroughTheRedirectAndItsSizeRead() {
        val source = CoverSource(CoverEntity.RELEASE, COVER_RELEASE)
        val text = runBlocking { services().coverArtArchive.cover(source, CoverSize.DISPLAY) }.toString()

        val size = FakeCaa.FRONT_500.fixture.bytes().size
        assertThat(text).startsWith("Found(value=CoverImage(JPEG, PictureSize(width=500, height=500), $size bytes, ")
        assertThat(text).endsWith("_thumb500.jpg, original=false))")
        assertThat(log.entries.map { it.path.substringAfterLast('/') })
            .containsExactly("front-500", "mbid-$COVER_RELEASE-1234567890_thumb500.jpg")
    }

    @Test
    fun theReleaseIndexDecodesEveryImageOntoTheConfiguredAddress() {
        val id = FakeCaa.INDEX_RELEASE
        val text = runBlocking { services().coverArtArchive.releaseIndex(id) }.toString()

        val prefix = caaServer.url("/release/$id").toString()
        assertThat(text).startsWith("Found(value=CaaReleaseIndex(images=[CaaImage(id=1234567890, front=true, types=[Front]")
        assertThat(text).contains(
            "original=$prefix/1234567890.jpg, thumbnails={250=$prefix/1234567890-250.jpg, " +
                "500=$prefix/1234567890-500.jpg, 1200=$prefix/1234567890-1200.jpg}",
            "CaaImage(id=2234567890, front=false, types=[Back, Tray], comment=tray, approved=true, " +
                "original=$prefix/2234567890.png, thumbnails={250=$prefix/2234567890-250.jpg, 500=$prefix/2234567890-500.jpg})",
        )
        assertThat(text.occurrences("CaaImage(")).isEqualTo(2)
    }

    private fun String.occurrences(part: String): Int = windowed(part.length).count { it == part }

    private companion object {
        /** The instrumentation argument that names the build a run is meant for: `true` minified, `false` debug. */
        const val MINIFIED_ARGUMENT = "minified"
        const val KEY = "minifiedTestKey1"
        const val FINGERPRINT = "AQAAT0mUaEkSRZEGAA"
        const val INVALID_KEY_FINGERPRINT = "AQAAinvalidKey"

        // The recording, release, release group and studio recording of the MusicBrainz fixtures (TI §4.2, §5.1).
        const val LIVE = "cc7867d1-34a5-4bdd-a9b5-faff27ce77fb"
        const val LIVE_RELEASE = "157cb7bb-217f-49d4-a305-64e49df5a0a7"
        const val LIVE_GROUP = "c88dba71-c542-3424-b746-05f6c8f3e060"
        const val STUDIO = "b1a9c0e9-d987-4042-ae91-78d6a3267d69"
        const val COVER_RELEASE = "44444444-4444-4444-8444-444444444444"

        fun mb(name: String) = Fixture("musicbrainz/$name")
    }
}
