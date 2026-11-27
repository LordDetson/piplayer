package io.github.lorddetson.piplayer.settings.configuration

import android.content.Context
import android.util.AtomicFile
import io.github.lorddetson.piplayer.settings.AppLanguage
import io.github.lorddetson.piplayer.settings.AppLanguageChange
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.Serializable

/** The activity's resource override and Android's native locale API have different raw representations of System. */
internal interface ConfigurationLocale {
    val backend: String
    fun read(): String?
    fun write(value: String?): Boolean

    fun selected(raw: String?) = AppLanguage.fromTag(raw?.substringBefore(','))
    fun target(language: AppLanguage): String? = if (backend == "platform") language.tag.orEmpty() else language.tag
    val change: AppLanguageChange
        get() = if (backend == "platform") AppLanguageChange.APPLIED else AppLanguageChange.RECREATE
}

internal data class ConfigurationLocaleChange(val before: String?, val after: String?) {
    fun reversed() = ConfigurationLocaleChange(after, before)
}

@Serializable
internal data class ConfigurationLocaleJournal(
    val installation: String,
    val backend: String,
    val before: String?,
    val after: String?,
)

@Synchronized
internal fun configurationInstallation(context: Context): String {
    val marker = AtomicFile(File(context.noBackupFilesDir, "configuration-locale-installation"))
    val stored = try {
        marker.readFully().decodeToString()
    } catch (_: FileNotFoundException) {
        null
    }
    if (stored != null && runCatching { UUID.fromString(stored).toString() == stored }.getOrDefault(false)) return stored
    val identity = UUID.randomUUID().toString()
    val output = marker.startWrite()
    try {
        output.write(identity.encodeToByteArray())
        output.fd.sync()
        marker.finishWrite(output)
    } catch (error: IOException) {
        marker.failWrite(output)
        throw error
    }
    if (marker.readFully().decodeToString() != identity) throw IOException("Could not persist the installation marker")
    return identity
}
