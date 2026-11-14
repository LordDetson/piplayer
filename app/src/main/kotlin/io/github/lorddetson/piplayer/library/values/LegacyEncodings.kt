package io.github.lorddetson.piplayer.library.values

import java.nio.charset.Charset

/** The script a code page is for; the settings name it with the code page (docs/library.md §5.7). */
enum class LegacyScript {
    CYRILLIC,
    CENTRAL_EUROPEAN,
    WESTERN,
    GREEK,
    TURKISH,
    HEBREW,
    ARABIC,
    BALTIC,
    VIETNAMESE,
    THAI,
    JAPANESE,
    SIMPLIFIED_CHINESE,
    TRADITIONAL_CHINESE,
    KOREAN,
}

/**
 * A code page the user can choose for text in legacy fields (TI §3.6). [name] is what the setting and
 * `audio_file.tag_encoding` store, [charset] what `Charset` knows it by, [label] how the settings show it.
 */
class LegacyEncoding(val name: String, val charset: String, val label: String, val script: LegacyScript) {
    override fun toString() = name
}

/** The code pages of the manual choice (TI §3.6). */
object LegacyEncodings {

    val ALL: List<LegacyEncoding> = listOf(
        LegacyEncoding(LegacyText.WINDOWS_1251, "windows-1251", "Windows-1251", LegacyScript.CYRILLIC),
        LegacyEncoding("koi8-r", "KOI8-R", "KOI8-R", LegacyScript.CYRILLIC),
        LegacyEncoding("koi8-u", "KOI8-U", "KOI8-U", LegacyScript.CYRILLIC),
        LegacyEncoding("ibm866", "IBM866", "CP866", LegacyScript.CYRILLIC),
        LegacyEncoding("iso-8859-5", "ISO-8859-5", "ISO-8859-5", LegacyScript.CYRILLIC),
        LegacyEncoding("windows-1250", "windows-1250", "Windows-1250", LegacyScript.CENTRAL_EUROPEAN),
        LegacyEncoding("iso-8859-2", "ISO-8859-2", "ISO-8859-2", LegacyScript.CENTRAL_EUROPEAN),
        LegacyEncoding("windows-1252", "windows-1252", "Windows-1252", LegacyScript.WESTERN),
        LegacyEncoding("iso-8859-15", "ISO-8859-15", "ISO-8859-15", LegacyScript.WESTERN),
        LegacyEncoding("windows-1253", "windows-1253", "Windows-1253", LegacyScript.GREEK),
        LegacyEncoding("windows-1254", "windows-1254", "Windows-1254", LegacyScript.TURKISH),
        LegacyEncoding("windows-1255", "windows-1255", "Windows-1255", LegacyScript.HEBREW),
        LegacyEncoding("windows-1256", "windows-1256", "Windows-1256", LegacyScript.ARABIC),
        LegacyEncoding("windows-1257", "windows-1257", "Windows-1257", LegacyScript.BALTIC),
        LegacyEncoding("windows-1258", "windows-1258", "Windows-1258", LegacyScript.VIETNAMESE),
        LegacyEncoding("tis-620", "TIS-620", "TIS-620", LegacyScript.THAI),
        LegacyEncoding("shift_jis", "Shift_JIS", "Shift_JIS", LegacyScript.JAPANESE),
        LegacyEncoding("euc-jp", "EUC-JP", "EUC-JP", LegacyScript.JAPANESE),
        LegacyEncoding("gbk", "GBK", "GBK", LegacyScript.SIMPLIFIED_CHINESE),
        LegacyEncoding("big5", "Big5", "Big5", LegacyScript.TRADITIONAL_CHINESE),
        LegacyEncoding("euc-kr", "EUC-KR", "EUC-KR", LegacyScript.KOREAN),
    )

    /** The code pages this device can decode, in the order of [ALL]. */
    fun available(isSupported: (String) -> Boolean = ::supported): List<LegacyEncoding> = ALL.filter { isSupported(it.charset) }

    /** The stored choice; null for Automatic, also when the name is unknown or this device cannot decode it. */
    fun fromStored(value: String?, isSupported: (String) -> Boolean = ::supported): LegacyEncoding? =
        ALL.firstOrNull { it.name.equals(value, ignoreCase = true) }?.takeIf { isSupported(it.charset) }

    private fun supported(charset: String): Boolean = try {
        Charset.isSupported(charset)
    } catch (_: IllegalArgumentException) {
        false
    }
}
