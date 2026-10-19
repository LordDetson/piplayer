package io.github.lorddetson.piplayer.nativelib

/**
 * Loads libpiplayer_jni.so once. Its JNI_OnLoad registers all natives; the dynamic linker finds libtag.so and
 * libc++_shared.so in the APK by itself.
 */
internal object NativeLibrary {

    init {
        System.loadLibrary("piplayer_jni")
    }

    fun load() = Unit
}
