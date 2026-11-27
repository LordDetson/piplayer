package io.github.lorddetson.piplayer.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import io.github.lorddetson.piplayer.settings.configuration.ConfigurationLocale
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class AppLanguage(val tag: String?) {
    SYSTEM(null), RUSSIAN("ru"), ENGLISH("en");

    companion object {
        fun fromTag(tag: String?): AppLanguage {
            val language = tag?.let { Locale.forLanguageTag(it).language }
            return entries.firstOrNull { it.tag == language } ?: SYSTEM
        }
    }
}

internal enum class AppLanguageChange { UNCHANGED, APPLIED, RECREATE, FAILED }

/** Uses the platform's per-app choice on Android 13 and a persisted resource override on older versions. */
object AppLanguageSettings {
    private const val FILE = "app_language"
    private const val KEY = "locale"

    internal val mutations = Mutex()

    internal fun read(context: Context): AppLanguage = if (Build.VERSION.SDK_INT >= 33) {
        AppLanguage.fromTag(context.getSystemService(LocaleManager::class.java)?.applicationLocales?.get(0)?.toLanguageTag())
    } else readLegacy(context)

    /** Keeps the legacy locale and the activity's night mode in the same resource override. */
    fun configurationOverride(context: Context, existing: Configuration? = null): Configuration? {
        if (Build.VERSION.SDK_INT >= 33) return existing
        val tag = readLegacy(context).tag ?: return existing
        val chosen = Locale.forLanguageTag(tag)
        val system = context.resources.configuration.locales
        val locales = listOf(chosen) + (0 until system.size()).mapNotNull { system[it] }.filter { it != chosen }
        return (existing ?: Configuration()).apply { setLocales(LocaleList(*locales.toTypedArray())) }
    }

    internal suspend fun set(context: Context, selected: AppLanguage): AppLanguageChange = mutations.withLock {
        withContext(Dispatchers.IO) {
            try {
                val locale = transactionLocale(context)
                if (locale.selected(locale.read()) == selected) return@withContext AppLanguageChange.UNCHANGED
                val target = locale.target(selected)
                if (locale.write(target) && locale.read() == target) locale.change else AppLanguageChange.FAILED
            } catch (_: IOException) {
                AppLanguageChange.FAILED
            } catch (_: SecurityException) {
                AppLanguageChange.FAILED
            }
        }
    }

    internal fun transactionLocale(context: Context): ConfigurationLocale = object : ConfigurationLocale {
        override val backend = if (Build.VERSION.SDK_INT >= 33) "platform" else "legacy"

        override fun read(): String? = if (Build.VERSION.SDK_INT >= 33) {
            manager(context).applicationLocales.toLanguageTags()
        } else {
            preferences(context).getString(KEY, null)
        }

        override fun write(value: String?): Boolean {
            if (Build.VERSION.SDK_INT >= 33) {
                manager(context).applicationLocales = LocaleList.forLanguageTags(value.orEmpty())
                return true
            }
            return preferences(context).edit().putString(KEY, value).commit()
        }
    }

    @androidx.annotation.RequiresApi(33)
    private fun manager(context: Context) =
        context.getSystemService(LocaleManager::class.java) ?: throw IOException("App locale service unavailable")

    /** Hands a restored or pre-upgrade selection to Android, keeping any existing platform choice. */
    internal fun migrateLegacy(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val legacy = readLegacy(context).tag ?: return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        try {
            if (manager.applicationLocales.isEmpty) manager.applicationLocales = LocaleList.forLanguageTags(legacy)
        } catch (_: SecurityException) {
            return
        }
        preferences(context).edit().remove(KEY).apply()
    }

    private fun readLegacy(context: Context): AppLanguage = AppLanguage.fromTag(preferences(context).getString(KEY, null))

    private fun preferences(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
