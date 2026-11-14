package io.github.lorddetson.piplayer.library.values

import java.nio.charset.Charset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The code pages of the manual legacy-encoding choice (TI §3.6). */
class LegacyEncodingsTest {

    @Test
    fun `the list holds the code pages old taggers used`() {
        assertThat(LegacyEncodings.ALL.map { it.label }).contains(
            "Windows-1251", "KOI8-R", "Windows-1250", "Windows-1252", "ISO-8859-2", "CP866", "Shift_JIS", "GBK", "Big5",
            "EUC-KR",
        )
    }

    @Test
    fun `names are unique, in lower case, and the charsets are known here`() {
        val names = LegacyEncodings.ALL.map { it.name }

        assertThat(names).doesNotHaveDuplicates()
        assertThat(names).allSatisfy { assertThat(it).isEqualTo(it.lowercase()) }
        assertThat(LegacyEncodings.ALL).allSatisfy { assertThat(Charset.isSupported(it.charset)).describedAs(it.charset).isTrue() }
    }

    @Test
    fun `Windows-1251 is stored under the name the automatic mode stores`() {
        assertThat(LegacyEncodings.ALL.first().name).isEqualTo(LegacyText.WINDOWS_1251)
    }

    @Test
    fun `only the code pages the device supports are offered, in the order of the list`() {
        val available = LegacyEncodings.available(isSupported = { it != "TIS-620" && it != "GBK" })

        assertThat(available.map { it.name }).isEqualTo(LegacyEncodings.ALL.map { it.name } - setOf("tis-620", "gbk"))
    }

    @Test
    fun `everything is offered where everything is supported`() {
        assertThat(LegacyEncodings.available(isSupported = { true })).isEqualTo(LegacyEncodings.ALL)
    }

    @Test
    fun `no stored value is Automatic`() {
        assertThat(LegacyEncodings.fromStored(null)).isNull()
    }

    @Test
    fun `a stored name is its code page`() {
        assertThat(LegacyEncodings.fromStored("koi8-r")?.label).isEqualTo("KOI8-R")
        assertThat(LegacyEncodings.fromStored("shift_jis")?.label).isEqualTo("Shift_JIS")
    }

    @Test
    fun `an unknown name is Automatic`() {
        assertThat(LegacyEncodings.fromStored("x-unknown")).isNull()
        assertThat(LegacyEncodings.fromStored("")).isNull()
    }

    @Test
    fun `a name this device cannot decode is Automatic`() {
        assertThat(LegacyEncodings.fromStored("gbk", isSupported = { it != "GBK" })).isNull()
    }
}
