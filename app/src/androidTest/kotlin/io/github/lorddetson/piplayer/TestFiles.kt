package io.github.lorddetson.piplayer

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Test assets copied into the cache directory and opened read-only, the way the app gets content files. */
object TestFiles {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** The test APK's assets. */
    val assets = instrumentation.context.assets

    val targetContext = instrumentation.targetContext

    fun copy(assetPath: String): File {
        val target = File(targetContext.cacheDir, assetPath.replace('/', '_'))
        assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    fun open(file: File): ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
}
