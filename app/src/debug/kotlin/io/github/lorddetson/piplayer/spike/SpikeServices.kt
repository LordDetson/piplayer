package io.github.lorddetson.piplayer.spike

import android.content.Context
import io.github.lorddetson.piplayer.BuildConfig
import io.github.lorddetson.piplayer.identification.FingerprintStage
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.ProviderBaseUrls
import io.github.lorddetson.piplayer.identification.pipeline.AppPipeline
import io.github.lorddetson.piplayer.identification.pipeline.FileRef
import io.github.lorddetson.piplayer.identification.pipeline.IdentificationPipeline
import io.github.lorddetson.piplayer.identification.pipeline.PipelineProgress
import io.github.lorddetson.piplayer.identification.pipeline.PipelineResult
import io.github.lorddetson.piplayer.net.AndroidConnectivity
import io.github.lorddetson.piplayer.net.Connectivity
import io.github.lorddetson.piplayer.net.UserAgent
import kotlin.time.TimeSource

/**
 * The spike's side of identification (debug builds only): the app's pipeline (TI §3 "One run") over services of its
 * own, with one rate limiter per provider (TI §8, §9), behind [SpikeGate], which is open only while a spike run goes on.
 * The app's services stay behind the user's own choice, so in a debug process the spike can send while lookups are off.
 */
object SpikeServices {

    @Volatile
    private var services: IdentificationServices? = null

    /** True when the spike's services can look fingerprints up: the build carries an AcoustID application key. */
    fun hasAcoustIdKey(context: Context): Boolean = services(context).fingerprintStage is FingerprintStage.Available

    fun services(context: Context): IdentificationServices = services ?: synchronized(this) {
        services ?: create(UserAgent.forBuild(), AndroidConnectivity(context), BuildConfig.ACOUSTID_API_KEY)
            .also { services = it }
    }

    /**
     * The spike's services at [baseUrls], with the application key [acoustIdApiKey] (empty for none); the wiring test
     * points them at the fakes.
     */
    fun create(
        userAgent: String,
        connectivity: Connectivity,
        acoustIdApiKey: String,
        baseUrls: ProviderBaseUrls = ProviderBaseUrls.PRODUCTION,
        timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    ): IdentificationServices = IdentificationServices.create(
        userAgent = userAgent,
        gate = SpikeGate,
        connectivity = connectivity,
        baseUrls = baseUrls,
        timeSource = timeSource,
        acoustIdApiKey = acoustIdApiKey,
    )

    /** The app's pipeline over the spike's services; open [SpikeGate] to run it. */
    fun pipeline(context: Context): IdentificationPipeline = AppPipeline.over(context, services(context))

    /** Runs [pipeline] on the file at [uri], with [SpikeGate] open for the run only. */
    suspend fun run(
        uri: String,
        pipeline: IdentificationPipeline,
        onProgress: (PipelineProgress) -> Unit = {},
    ): PipelineResult = SpikeGate.open().use { pipeline.run(FileRef.Opened(uri), onProgress) }
}
