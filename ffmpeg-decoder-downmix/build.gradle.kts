import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

private val EXPECTED_FFMPEG_TAG = "n7.1.5"
private val EXPECTED_FFMPEG_COMMIT = "3a0867c2bfda4a4d4309ca1a8cbdc6175e67f587"
private val EXPECTED_NDK_REVISION = "28.2.13676358"
private val EXPECTED_ANDROID_API = "24"
private val REQUIRED_FFMPEG_LIBRARIES = listOf("avcodec", "avutil", "swresample")
private val REQUIRED_FFMPEG_DECODERS = listOf(
    "aac", "mp3", "ac3", "eac3", "truehd", "dca", "vorbis", "opus",
    "amrnb", "amrwb", "flac", "alac", "pcm_mulaw", "pcm_alaw", "h264", "hevc"
)
private val FOX_ABIS = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

// settings.gradle.kts is the single source for this decision; fail with an explicit contract
// instead of an opaque cast/missing-property exception if it was ever not published.
val ffmpegDependencyAuditOnly: Boolean = gradle.extensions.extraProperties.let { extras ->
    check(extras.has("foxtvFfmpegDependencyAuditOnly")) {
        "Build flag 'foxtvFfmpegDependencyAuditOnly' was not published by settings.gradle.kts"
    }
    val value = extras.get("foxtvFfmpegDependencyAuditOnly")
    value as? Boolean
        ?: throw GradleException(
            "Build flag 'foxtvFfmpegDependencyAuditOnly' must be a Boolean, " +
                "found ${value?.javaClass?.name}: $value"
        )
}

val localProperties = Properties().apply {
    val source = rootProject.file("local.properties")
    if (source.isFile) {
        source.inputStream().use(::load)
    }
}

fun requiredLocalPath(name: String): File {
    val configured = providers.environmentVariable(name).orNull
        ?: localProperties.getProperty(name)
        ?: throw GradleException(
            "$name is required when USE_LOCAL_FFMPEG_DECODER=true. " +
                "See ffmpeg-decoder-downmix/README.md."
        )
    val configuredFile = File(configured)
    require(configuredFile.isAbsolute) {
        "$name must be an absolute path, got: $configured"
    }
    return configuredFile.canonicalFile
}

fun commandOutput(vararg command: String): String {
    val process = ProcessBuilder(*command)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    val exitCode = process.waitFor()
    require(exitCode == 0) {
        "Command failed with exit $exitCode: ${command.joinToString(" ")}\n$output"
    }
    return output
}

val ffmpegSourceDir = if (ffmpegDependencyAuditOnly) null else requiredLocalPath("FFMPEG_SOURCE_DIR")
val ffmpegBuildDir = if (ffmpegDependencyAuditOnly) null else requiredLocalPath("FFMPEG_BUILD_DIR")

val verifyFfmpegSourceInputs = if (!ffmpegDependencyAuditOnly) {
    val sourceDir = requireNotNull(ffmpegSourceDir)
    val buildDir = requireNotNull(ffmpegBuildDir)
    tasks.register("verifyFfmpegSourceInputs") {
        group = "verification"
        description = "Validates the pinned FFmpeg checkout and four-ABI static build inputs."

        inputs.dir(sourceDir)
        inputs.dir(buildDir)

        doLast {
            require(sourceDir.isDirectory) {
                "FFMPEG_SOURCE_DIR does not exist or is not a directory: $sourceDir"
            }
            require(sourceDir.resolve("configure").canExecute()) {
                "FFMPEG_SOURCE_DIR has no executable FFmpeg configure script: $sourceDir"
            }
            require(sourceDir.resolve(".git").exists()) {
                "FFMPEG_SOURCE_DIR must be a Git checkout: $sourceDir"
            }

            val sourceRevision = commandOutput(
                "git", "-C", sourceDir.path, "rev-parse", "HEAD"
            )
            require(sourceRevision == EXPECTED_FFMPEG_COMMIT) {
                "Unsupported FFmpeg source revision $sourceRevision; expected $EXPECTED_FFMPEG_COMMIT"
            }
            val sourceStatus = commandOutput(
                "git", "-C", sourceDir.path, "status", "--porcelain", "--untracked-files=normal"
            )
            require(sourceStatus.isEmpty()) {
                "FFMPEG_SOURCE_DIR is dirty; use an unmodified $EXPECTED_FFMPEG_COMMIT checkout"
            }
            require(buildDir.isDirectory) {
                "FFMPEG_BUILD_DIR does not exist or is not a directory: $buildDir"
            }

            fun requireMarker(name: String, expected: String) {
                val marker = buildDir.resolve(name)
                require(marker.isFile) {
                    "FFMPEG_BUILD_DIR has no $name marker; run src/main/jni/build_ffmpeg.sh first"
                }
                val actual = marker.readText().trim()
                require(actual == expected) {
                    "Unsupported $name value '$actual'; expected '$expected'"
                }
            }

            requireMarker("SOURCE_COMMIT", EXPECTED_FFMPEG_COMMIT)
            requireMarker("SOURCE_TAG", EXPECTED_FFMPEG_TAG)
            requireMarker("ANDROID_API", EXPECTED_ANDROID_API)
            requireMarker("NDK_VERSION", EXPECTED_NDK_REVISION)
            requireMarker("ENABLED_DECODERS", REQUIRED_FFMPEG_DECODERS.joinToString(" "))

            FOX_ABIS.forEach { abi ->
                val componentsFile = buildDir.resolve("$abi/build/config_components.h")
                require(componentsFile.isFile) {
                    "Missing generated FFmpeg codec configuration for $abi: $componentsFile"
                }
                val components = componentsFile.readText()
                val enabledDecoders = Regex(
                    "^#define CONFIG_([A-Z0-9_]+)_DECODER 1$",
                    RegexOption.MULTILINE
                ).findAll(components).map { match -> match.groupValues[1].lowercase() }.toSet()
                require(enabledDecoders == REQUIRED_FFMPEG_DECODERS.toSet()) {
                    "Unexpected FFmpeg decoder set for $abi: $enabledDecoders; " +
                        "expected ${REQUIRED_FFMPEG_DECODERS.toSet()}"
                }
                require("#define CONFIG_AC3_ENCODER 1" in components) {
                    "Required AC-3 encoder is not enabled for $abi"
                }

                val installDir = buildDir.resolve("$abi/install")
                require(installDir.resolve("include/libavcodec/avcodec.h").isFile) {
                    "Missing installed FFmpeg headers for $abi under $installDir"
                }
                REQUIRED_FFMPEG_LIBRARIES.forEach { library ->
                    require(installDir.resolve("lib/lib$library.a").isFile) {
                        "Missing lib$library.a for $abi under $installDir/lib"
                    }
                }
            }
        }
    }
} else {
    null
}

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters += FOX_ABIS
        }
        if (!ffmpegDependencyAuditOnly) {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DFFMPEG_SOURCE_DIR=${requireNotNull(ffmpegSourceDir).path.replace('\\', '/')}",
                        "-DFFMPEG_BUILD_DIR=${requireNotNull(ffmpegBuildDir).path.replace('\\', '/')}"
                    )
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = false
    }

    if (!ffmpegDependencyAuditOnly) {
        externalNativeBuild {
            cmake {
                path = file("src/main/jni/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

if (verifyFfmpegSourceInputs != null) {
    tasks.named("preBuild").configure {
        dependsOn(verifyFfmpegSourceInputs)
    }
} else {
    tasks.named("preBuild").configure {
        doFirst {
            throw GradleException(
                "FFmpeg dependency-audit-only mode may resolve Gradle dependencies but must " +
                    "not build an AAR. Use -PuseLocalFfmpegDecoder=true with pinned source inputs."
            )
        }
    }
}

dependencies {
    api(libs.media3.decoder)
    implementation(libs.media3.common)
    compileOnly(files("../app/libs/lib-exoplayer-release.aar"))
    implementation(libs.androidx.annotation)
    compileOnly(libs.checker.qual)
}
