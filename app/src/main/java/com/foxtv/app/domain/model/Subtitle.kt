package com.foxtv.app.domain.model

import androidx.compose.runtime.Immutable
import com.foxtv.app.ui.util.languageCodeToName

@Immutable
data class Subtitle(
    val id: String,
    val url: String,
    val lang: String,
    val addonName: String,
    val addonLogo: String?,
    val isStreamProvided: Boolean = false
) {
    fun getDisplayLanguage(): String = languageCodeToName(lang)

    companion object {
        fun languageCodeToName(code: String): String = com.foxtv.app.ui.util.languageCodeToName(code)
    }
}
