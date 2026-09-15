package eu.kanade.tachiyomi

import com.foxtv.app.BuildConfig

/** Parent-owned application information exposed to Aniyomi extensions. */
object AppInfo {
    val versionName: String
        get() = BuildConfig.VERSION_NAME.ifBlank { "dev" }
}
