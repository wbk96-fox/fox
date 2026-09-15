package com.foxtv.app.core.plugin.aniyomi

import android.content.Context
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

internal class AniyomiPackageArchiveReadException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Reason { MANIFEST, SIGNATURE }
}

/**
 * Uses Android's package parser without installing the APK. The second parse requests certificate
 * collection; Android's PackageParser performs cryptographic APK signature verification before it
 * exposes signer certificates.
 */
@Singleton
internal class AndroidAniyomiPackageArchiveReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AniyomiPackageArchiveReader {

    @Suppress("DEPRECATION")
    override fun read(apk: File): AniyomiPackageArchiveFacts {
        val packageManager = context.packageManager
        val manifestFlags = PackageManager.GET_META_DATA or
            PackageManager.GET_PERMISSIONS or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_CONFIGURATIONS

        val parsed = packageManager.getPackageArchiveInfo(apk.absolutePath, manifestFlags)
            ?: throw AniyomiPackageArchiveReadException(
                AniyomiPackageArchiveReadException.Reason.MANIFEST,
                "Android rejected the APK manifest",
            )

        // GET_SIGNATURES is retained on every supported API because Android 13 and older only
        // trigger archive certificate collection from that bit. Newer APIs additionally expose
        // SigningInfo through GET_SIGNING_CERTIFICATES.
        val signingFlags = manifestFlags or PackageManager.GET_SIGNATURES or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                0
            }
        val verified = packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags)
            ?: throw AniyomiPackageArchiveReadException(
                AniyomiPackageArchiveReadException.Reason.SIGNATURE,
                "Android rejected the APK signature",
            )

        if (verified.packageName != parsed.packageName) {
            throw AniyomiPackageArchiveReadException(
                AniyomiPackageArchiveReadException.Reason.MANIFEST,
                "Android package parses produced inconsistent identities",
            )
        }
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            verified.signingInfo?.apkContentsSigners.orEmpty().toList()
        } else {
            verified.signatures.orEmpty().toList()
        }
        if (signers.isEmpty()) {
            throw AniyomiPackageArchiveReadException(
                AniyomiPackageArchiveReadException.Reason.SIGNATURE,
                "Android verified no APK signer certificates",
            )
        }

        val application = verified.applicationInfo
            ?: throw AniyomiPackageArchiveReadException(
                AniyomiPackageArchiveReadException.Reason.MANIFEST,
                "APK manifest has no application declaration",
            )
        return AniyomiPackageArchiveFacts(
            packageName = verified.packageName,
            versionCode = versionCode(verified),
            versionName = verified.versionName,
            minSdk = application.minSdkVersion,
            targetSdk = application.targetSdkVersion,
            requiredFeatures = verified.reqFeatures.orEmpty()
                .filter { it.flags and FeatureInfo.FLAG_REQUIRED != 0 }
                .mapNotNullTo(linkedSetOf()) { it.name },
            metadata = application.metaData?.keySet().orEmpty()
                .associateWith { key -> application.metaData?.get(key)?.toString().orEmpty() },
            requestedPermissions = verified.requestedPermissions.orEmpty().toSet(),
            activities = verified.activities.orEmpty().mapTo(linkedSetOf()) { it.name },
            services = verified.services.orEmpty().mapTo(linkedSetOf()) { it.name },
            receivers = verified.receivers.orEmpty().mapTo(linkedSetOf()) { it.name },
            providers = verified.providers.orEmpty().mapTo(linkedSetOf()) { it.name },
            signerCertificateSha256 = signers.mapTo(linkedSetOf()) { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte) }
            },
            signatureVerified = true,
        )
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()
}
