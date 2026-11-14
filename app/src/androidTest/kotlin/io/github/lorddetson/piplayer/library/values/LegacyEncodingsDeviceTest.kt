package io.github.lorddetson.piplayer.library.values

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.charset.Charset
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every code page of the manual choice is decoded by this device's `Charset` (TI §3.6): Android promises only a few, so
 * this records which API levels have the others. The failure lists every code page that fails, not only the first.
 */
@RunWith(AndroidJUnit4::class)
class LegacyEncodingsDeviceTest {

    private val samples = mapOf(
        "windows-1251" to "Кино", "koi8-r" to "Кино", "koi8-u" to "Біла хата", "ibm866" to "Кино", "iso-8859-5" to "Кино",
        "windows-1250" to "Łódź", "iso-8859-2" to "Łódź", "windows-1252" to "Beyoncé €", "iso-8859-15" to "€uro",
        "windows-1253" to "Ελλάδα", "windows-1254" to "İstanbul ğ", "windows-1255" to "שלום", "windows-1256" to "سلام",
        "windows-1257" to "Ąžuolas", "windows-1258" to "Đà", "tis-620" to "สวัสดี", "shift_jis" to "ソニー東京事変",
        "euc-jp" to "東京事変", "gbk" to "王菲", "big5" to "周杰倫", "euc-kr" to "서울",
    )

    private fun marked(bytes: ByteArray): String = buildString {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append(if (value < 0x80) value.toChar() else (LegacyText.MARK_BASE + value).toChar())
        }
    }

    @Test
    fun everyCodePageOfTheListIsSupportedAndDecodesItsSample() {
        val api = "API ${Build.VERSION.SDK_INT}"
        val problems = mutableListOf<String>()
        for (encoding in LegacyEncodings.ALL) {
            val sample = samples.getValue(encoding.name)
            if (!Charset.isSupported(encoding.charset)) {
                problems += "$api does not support ${encoding.charset}"
                continue
            }
            val decoded = LegacyText.decode(
                mapOf("TITLE" to listOf(marked(sample.toByteArray(Charset.forName(encoding.charset))))),
                cyrillicLanguage = false,
                manual = encoding,
            )
            val text = decoded.properties.getValue("TITLE").single()
            if (text != sample) problems += "$api decodes ${encoding.name} as \"$text\", not \"$sample\""
            if (decoded.encoding != encoding.name) problems += "$api stores ${encoding.name} as ${decoded.encoding}"
        }
        if (LegacyEncodings.available() != LegacyEncodings.ALL) problems += "$api offers ${LegacyEncodings.available()}"
        assertThat(problems).isEmpty()
    }
}
