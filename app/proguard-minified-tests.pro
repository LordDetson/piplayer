# R8 rules of the minified instrumented run only (-Ppiplayer.minifiedTests=true, TI §11.2): never part of a release.
# They keep, under new names, the app code that only the tests call, so that R8 can neither drop it nor fold it into
# its callers; everything below these entry points (the DTOs, their serializers, the parsing) is shrunk, optimized and
# renamed as in the release build. The test APK follows the new names through the app's mapping.

# The libraries the test APK shares with the app: AGP leaves them out of the test APK, so the tests, the test runner,
# MockWebServer and okhttp-tls use the app's copies, also their internals. Kept whole and by name, since the test APK
# reached renamed OkHttp internals by their old names; the app's own code and kotlinx.serialization are not among them.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class androidx.tracing.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.concurrent.futures.** { *; }
-keep class com.google.common.util.concurrent.ListenableFuture { *; }

# The switches PiPlayerTestRunner turns off before the app starts.
-keepclassmembers,allowobfuscation class io.github.lorddetson.piplayer.crash.CrashReports {
    public static ** Companion;
}
-keepclassmembers,allowobfuscation class io.github.lorddetson.piplayer.crash.CrashReports$Companion {
    public void setPromptEnabled(boolean);
}
-keepclassmembers,allowobfuscation class io.github.lorddetson.piplayer.library.Library {
    public static ** INSTANCE;
    public void setTagPassEnabled(boolean);
}

# What the shared fakes (TestServices) build the services from.
-keep,allowobfuscation class io.github.lorddetson.piplayer.net.UserAgent { public *; }
-keep,allowobfuscation class io.github.lorddetson.piplayer.net.OnlineGate { public *; }
-keep,allowobfuscation class io.github.lorddetson.piplayer.net.OnlineConsent { public <init>(...); }
-keep,allowobfuscation class io.github.lorddetson.piplayer.net.InMemoryOnlineGate { public <init>(...); }
-keep,allowobfuscation class io.github.lorddetson.piplayer.net.Connectivity { public *; }
-keep,allowobfuscation enum io.github.lorddetson.piplayer.net.NetworkState { public *; }
-keep,allowobfuscation class io.github.lorddetson.piplayer.net.RetryPolicy { public <init>(...); }
-keep,allowobfuscation class io.github.lorddetson.piplayer.identification.provider.ProviderStatusStore { public <init>(...); }
-keep,allowobfuscation class io.github.lorddetson.piplayer.identification.provider.ProviderAccess { public <init>(...); }
-keep,allowobfuscation enum io.github.lorddetson.piplayer.identification.provider.ProviderId { public *; }

# The services and the clients' calls (MinifiedClientsTest); not the types they return.
-keep,allowobfuscation class io.github.lorddetson.piplayer.identification.ProviderBaseUrls { public <init>(...); }
-keep,allowobfuscation class io.github.lorddetson.piplayer.identification.IdentificationServices {
    public static ** Companion;
    public ** getFingerprintStage();
    public ** getMusicBrainz();
    public ** getCoverArtArchive();
}
-keep,allowobfuscation class io.github.lorddetson.piplayer.identification.IdentificationServices$Companion {
    public ** create(...);
}
-keep,allowobfuscation class io.github.lorddetson.piplayer.identification.FingerprintStage$Available {
    public ** getAcoustId();
}
-keep,allowobfuscation class io.github.lorddetson.piplayer.identification.acoustid.AcoustIdClient {
    public ** lookup(...);
}
-keep,allowobfuscation class io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzClient {
    public ** searchRecordings(...);
    public static ** searchRecordings$default(...);
    public ** lookupRecording(...);
    public ** lookupIsrc(...);
    public ** lookupRelease(...);
    public ** lookup(...);
    public ** releasesOfRecording(...);
    public ** releasesOfReleaseGroup(...);
}
-keep,allowobfuscation class io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzLookups** { public *; }
-keep,allowobfuscation class io.github.lorddetson.piplayer.identification.text.MusicBrainzRef$AnyEntity { public <init>(...); }
-keep,allowobfuscation class io.github.lorddetson.piplayer.identification.caa.CoverArtArchiveClient {
    public ** cover(...);
    public ** releaseIndex(...);
}
-keep,allowobfuscation class io.github.lorddetson.piplayer.identification.caa.CoverSource { public <init>(...); }
-keep,allowobfuscation enum io.github.lorddetson.piplayer.identification.caa.CoverEntity { public *; }
-keep,allowobfuscation enum io.github.lorddetson.piplayer.identification.caa.CoverSize { public *; }
