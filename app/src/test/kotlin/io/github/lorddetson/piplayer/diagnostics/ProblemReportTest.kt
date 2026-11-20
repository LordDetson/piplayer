package io.github.lorddetson.piplayer.diagnostics

import io.github.lorddetson.piplayer.crash.DeviceInfo
import io.github.lorddetson.piplayer.library.details.TraceRun
import io.github.lorddetson.piplayer.library.details.TraceStep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProblemReportTest {

    private val device = DeviceInfo(
        appVersion = "1.0.0 (2)",
        buildType = "release",
        manufacturer = "Google",
        model = "Pixel 8",
        device = "shiba",
        release = "16",
        sdk = 36,
        fingerprint = "google/shiba/shiba:16/BP2A/123:user/release-keys",
        abis = listOf("arm64-v8a"),
    )

    @Test
    fun `the default report contains device and version information without an identification trace`() {
        assertThat(ProblemReport.text(device))
            .contains("PiPlayer 1.0.0 (2), release", "Device: Google Pixel 8 (shiba)", "Android 16 (API 36)")
            .doesNotContain("Identification trace")
    }

    @Test
    fun `an explicitly supplied trace preserves ordered runs and diagnostic fields`() {
        val trace = listOf(
            TraceRun(
                startedAt = 1_700_000_003_000,
                outcome = "provider_stopped=invalid_api_key",
                retryAt = 1_700_000_100_000,
                steps = listOf(
                    TraceStep(stage = "local_read", elapsedMs = 11),
                    TraceStep(stage = "known_ids", provider = "musicbrainz", skipped = "no_value"),
                    TraceStep(
                        stage = "fingerprint", provider = "acoustid", httpStatus = 403,
                        resultCount = 0, bestScore = 0.0, elapsedMs = 40, failed = "service_4",
                    ),
                ),
                provider = "acoustid",
            ),
            TraceRun(startedAt = 1_700_000_000_000, outcome = "found=high", retryAt = null, steps = emptyList()),
        )

        val text = ProblemReport.text(device, trace)

        assertThat(text).contains(
            "Identification trace", "2023-11-14T22:13:23Z", "provider_stopped=invalid_api_key", "provider=acoustid",
            "retry_at=2023-11-14T22:15:00Z", "local_read", "elapsed_ms=11", "known_ids", "provider=musicbrainz",
            "skipped=no_value", "fingerprint", "provider=acoustid", "http_status=403", "result_count=0",
            "best_score=0.0", "elapsed_ms=40", "failed=service_4", "2023-11-14T22:13:20Z", "found=high",
        )
        assertThat(text.indexOf("2023-11-14T22:13:23Z")).isLessThan(text.indexOf("2023-11-14T22:13:20Z"))
    }

    @Test
    fun `only known codes and numeric fields leave a trace even if stored strings contain private details`() {
        val trace = listOf(
            TraceRun(
                startedAt = 1_700_000_000_000,
                outcome = "found=high private-title",
                retryAt = null,
                provider = "musicbrainz?artist=private-artist",
                steps = listOf(
                    TraceStep(stage = "/storage/emulated/0/Music/private-file.mp3"),
                    TraceStep(
                        stage = "fingerprint", provider = "acoustid", httpStatus = 403,
                        failed = "http_403?client=private-key", skipped = "no_value\nprivate-album",
                    ),
                ),
            ),
        )

        assertThat(ProblemReport.text(device, trace))
            .contains("Identification trace", "fingerprint provider=acoustid http_status=403")
            .doesNotContain("private-title", "private-artist", "private-file.mp3", "private-key", "private-album")
            .doesNotContain("/storage/", "found=high", "failed=http_403", "skipped=no_value")
    }
}
