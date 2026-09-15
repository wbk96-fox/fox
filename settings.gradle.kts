import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun isEnabled(value: String?): Boolean {
    return value?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
}

val localBuildProperties = Properties().apply {
    val source = file("local.properties")
    if (source.isFile) {
        source.inputStream().use(::load)
    }
}

val useLocalFfmpegDecoder = isEnabled(
    providers.gradleProperty("useLocalFfmpegDecoder").orNull
        ?: providers.environmentVariable("USE_LOCAL_FFMPEG_DECODER").orNull
        ?: localBuildProperties.getProperty("USE_LOCAL_FFMPEG_DECODER")
)
val includeFfmpegDecoderForDependencyAudit = isEnabled(
    providers.gradleProperty("includeFfmpegDecoderForDependencyAudit").orNull
)
val ffmpegDependencyAuditOnly =
    includeFfmpegDecoderForDependencyAudit && !useLocalFfmpegDecoder
// `isEnabled` above is the single boolean parser for these flags. Project scripts must consume
// the normalized decisions below instead of re-parsing the raw Gradle properties, so the
// included-project set and the audit gates can never disagree about what was requested.
val includeFfmpegDecoderProject = useLocalFfmpegDecoder || includeFfmpegDecoderForDependencyAudit

gradle.extensions.extraProperties.set("foxtvUseLocalFfmpegDecoder", useLocalFfmpegDecoder)
gradle.extensions.extraProperties.set(
    "foxtvFfmpegDependencyAuditOnly",
    ffmpegDependencyAuditOnly
)
gradle.extensions.extraProperties.set(
    "foxtvIncludeFfmpegDecoderProject",
    includeFfmpegDecoderProject
)

rootProject.name = "FOX.TV"
include(":app")
include(":baselineprofile")
if (includeFfmpegDecoderProject) {
    include(":ffmpeg-decoder-downmix")
}
