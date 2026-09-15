package com.foxtv.app.core.fanfilm

import android.media.MediaDrm
import android.media.UnsupportedSchemeException
import android.os.Build
import android.util.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Answers "can this build play that?" for the FanFilm/InputStream adapter.
 *
 * `inputstreamhelper` asks whether Kodi's `inputstream.adaptive` binary addon is
 * installed and whether Widevine is provisioned. FOX.TV has no binary addons — the
 * adaptive pipeline is Media3 and Widevine comes from Android's `MediaDrm`. Rather
 * than stubbing the answer `true` (which would let a source reach the player only to
 * die with an opaque codec error), the question is answered against the real thing
 * (AGENTS.md §27/§28/§30).
 *
 * Protocol support is determined by whether the Media3 source factory for that
 * container is actually on the classpath, checked by reflection so removing a
 * dependency changes the answer instead of silently breaking playback.
 *
 * DRM support is determined by `MediaDrm.isCryptoSchemeSupported`, and the security
 * level is read from a real `MediaDrm` instance. Nothing here provisions, spoofs or
 * works around a CDM.
 */
@Singleton
class FanFilmPlayerCapabilities @Inject constructor() {

    data class Snapshot(
        val dash: Boolean,
        val hls: Boolean,
        val smoothStreaming: Boolean,
        val rtmp: Boolean,
        val widevine: Boolean,
        val widevineSecurityLevel: String,
        val playReady: Boolean,
        val clearKey: Boolean,
    )

    /**
     * Cached because `MediaDrm` instantiation is not free and the answer cannot
     * change while the process lives.
     */
    private val snapshot: Snapshot by lazy { probe() }

    fun snapshot(): Snapshot = snapshot

    /** JSON in the shape `foxtv_fanfilm.inputstream.capabilities` expects. */
    fun toJson(): String {
        val current = snapshot
        val protocols = JSONObject()
            .put("dash", current.dash)
            .put("hls", current.hls)
            .put("smoothstreaming", current.smoothStreaming)
            .put("rtmp", current.rtmp)
        val drm = JSONObject()
            .put("widevine", current.widevine)
            .put("widevineSecurityLevel", current.widevineSecurityLevel)
            .put("playready", current.playReady)
            .put("clearkey", current.clearKey)
        return JSONObject()
            .put("protocols", protocols)
            .put("drm", drm)
            .put("apiLevel", Build.VERSION.SDK_INT)
            .toString()
    }

    private fun probe(): Snapshot {
        val widevineSupported = isSchemeSupported(WIDEVINE_UUID)
        return Snapshot(
            dash = hasClass(DASH_FACTORY),
            hls = hasClass(HLS_FACTORY),
            smoothStreaming = hasClass(SMOOTH_FACTORY),
            // Media3 ships an RTMP data source, but FanFilm's providers do not use
            // it and FOX.TV does not bundle the module. Reported honestly as absent.
            rtmp = hasClass(RTMP_DATA_SOURCE),
            widevine = widevineSupported,
            widevineSecurityLevel = if (widevineSupported) readSecurityLevel() else "",
            playReady = isSchemeSupported(PLAYREADY_UUID),
            clearKey = isSchemeSupported(CLEARKEY_UUID),
        ).also { Log.i(TAG, "player capabilities: $it") }
    }

    private fun hasClass(name: String): Boolean = try {
        Class.forName(name, false, javaClass.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (t: Throwable) {
        Log.w(TAG, "capability probe for $name failed", t)
        false
    }

    private fun isSchemeSupported(uuid: UUID): Boolean = try {
        MediaDrm.isCryptoSchemeSupported(uuid)
    } catch (t: Throwable) {
        Log.w(TAG, "isCryptoSchemeSupported($uuid) failed", t)
        false
    }

    /**
     * Widevine security level as reported by the device ("L1", "L3", …).
     *
     * Used for diagnostics and to explain a DRM failure to the user; it never gates
     * playback on its own, because a stream's required level is a property of the
     * licence policy, not of the client.
     */
    private fun readSecurityLevel(): String {
        var drm: MediaDrm? = null
        return try {
            drm = MediaDrm(WIDEVINE_UUID)
            drm.getPropertyString(PROPERTY_SECURITY_LEVEL)
        } catch (_: UnsupportedSchemeException) {
            ""
        } catch (t: Throwable) {
            Log.w(TAG, "could not read Widevine security level", t)
            ""
        } finally {
            runCatching {
                if (drm != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) drm.close() else drm.release()
                }
            }
        }
    }

    companion object {
        private const val TAG = "FanFilmCaps"
        private const val PROPERTY_SECURITY_LEVEL = "securityLevel"

        private const val DASH_FACTORY = "androidx.media3.exoplayer.dash.DashMediaSource\$Factory"
        private const val HLS_FACTORY = "androidx.media3.exoplayer.hls.HlsMediaSource\$Factory"
        private const val SMOOTH_FACTORY =
            "androidx.media3.exoplayer.smoothstreaming.SsMediaSource\$Factory"
        private const val RTMP_DATA_SOURCE = "androidx.media3.datasource.rtmp.RtmpDataSource"

        val WIDEVINE_UUID: UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
        val PLAYREADY_UUID: UUID = UUID(-0x65fb0f8667bfbd7aL, -0x546d19a41f77a06bL)
        val CLEARKEY_UUID: UUID = UUID(-0x1d8e62a7567a4c37L, 0x781AB030AF78D30EL)
    }
}
