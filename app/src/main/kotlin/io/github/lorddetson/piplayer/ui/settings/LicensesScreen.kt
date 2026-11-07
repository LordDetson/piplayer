package io.github.lorddetson.piplayer.ui.settings

import androidx.compose.runtime.Composable

object LicensesTags {
    const val LIST = "licenses_list"
    const val TEXT = "license_text"
}

@Composable
fun LicenseTextScreen(title: String, notice: String?, texts: List<String>, onBack: () -> Unit) {
}
