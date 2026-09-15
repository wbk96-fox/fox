package com.foxtv.app.core.plugin.aniyomi

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object AniyomiApkTestFixtures {
    const val PACKAGE_NAME = "eu.kanade.tachiyomi.animeextension.all.jellyfin"
    const val FACTORY_CLASS = "$PACKAGE_NAME.JellyfinFactory"
    const val CERTIFICATE_SHA256 =
        "50ab1d1e3a20d204d0ad6d334c7691c632e41b98dfa132bf385695fdfa63839c"
    private const val FACTORY_INTERFACE =
        "Leu/kanade/tachiyomi/animesource/AnimeSourceFactory;"

    fun writeApk(
        directory: File,
        name: String = "extension.apk",
        includeManifest: Boolean = true,
        dex: ByteArray = buildDex(),
        extraEntries: Map<String, ByteArray> = emptyMap(),
    ): File {
        val file = File(directory, name)
        file.writeBytes(
            buildApkBytes(
                includeManifest = includeManifest,
                dex = dex,
                extraEntries = extraEntries,
            ),
        )
        return file.canonicalFile
    }

    fun buildApkBytes(
        includeManifest: Boolean = true,
        dex: ByteArray = buildDex(),
        extraEntries: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            if (includeManifest) {
                zip.add("AndroidManifest.xml", byteArrayOf(0x03, 0x00, 0x08, 0x00))
            }
            zip.add("resources.arsc", byteArrayOf(0x02, 0x00, 0x0c, 0x00))
            zip.add("classes.dex", dex)
            extraEntries.forEach { (path, bytes) -> zip.add(path, bytes) }
        }
        return output.toByteArray()
    }

    fun buildDex(
        factoryClassName: String = FACTORY_CLASS,
        implementsFactoryContract: Boolean = true,
        additionalDefinedClasses: Set<String> = emptySet(),
    ): ByteArray {
        val factoryDescriptor = descriptor(factoryClassName)
        val additionalDescriptors = additionalDefinedClasses.map(::descriptor)
        val descriptors = linkedSetOf(factoryDescriptor, "Ljava/lang/Object;", FACTORY_INTERFACE)
            .apply { addAll(additionalDescriptors) }
            .toList()
        val factoryTypeIndex = descriptors.indexOf(factoryDescriptor)
        val objectTypeIndex = descriptors.indexOf("Ljava/lang/Object;")
        val interfaceTypeIndex = descriptors.indexOf(FACTORY_INTERFACE)
        val definedDescriptors = listOf(factoryDescriptor) + additionalDescriptors

        val stringIdsOffset = 0x70
        val typeIdsOffset = align4(stringIdsOffset + descriptors.size * 4)
        val classDefsOffset = align4(typeIdsOffset + descriptors.size * 4)
        val dataOffset = align4(classDefsOffset + definedDescriptors.size * 32)

        val stringData = ByteArrayOutputStream()
        val stringOffsets = IntArray(descriptors.size)
        descriptors.forEachIndexed { index, value ->
            stringOffsets[index] = dataOffset + stringData.size()
            stringData.writeUleb128(value.length)
            stringData.write(value.toByteArray(Charsets.UTF_8))
            stringData.write(0)
        }
        while ((dataOffset + stringData.size()) % 4 != 0) stringData.write(0)
        val interfaceListOffset = dataOffset + stringData.size()
        if (implementsFactoryContract) {
            stringData.writeLe32(1)
            stringData.writeLe16(interfaceTypeIndex)
            stringData.writeLe16(0)
        }
        while ((dataOffset + stringData.size()) % 4 != 0) stringData.write(0)
        val mapOffset = dataOffset + stringData.size()

        val mapItems = mutableListOf(
            MapItem(0x0000, 1, 0),
            MapItem(0x0001, descriptors.size, stringIdsOffset),
            MapItem(0x0002, descriptors.size, typeIdsOffset),
            MapItem(0x0006, definedDescriptors.size, classDefsOffset),
            MapItem(0x2002, descriptors.size, dataOffset),
        )
        if (implementsFactoryContract) mapItems += MapItem(0x1001, 1, interfaceListOffset)
        mapItems += MapItem(0x1000, 1, mapOffset)
        mapItems.sortBy(MapItem::offset)

        val mapBytes = ByteArrayOutputStream().apply {
            writeLe32(mapItems.size)
            mapItems.forEach { item ->
                writeLe16(item.type)
                writeLe16(0)
                writeLe32(item.size)
                writeLe32(item.offset)
            }
        }.toByteArray()
        val fileSize = mapOffset + mapBytes.size
        val bytes = ByteArray(fileSize)

        bytes[0] = 'd'.code.toByte()
        bytes[1] = 'e'.code.toByte()
        bytes[2] = 'x'.code.toByte()
        bytes[3] = '\n'.code.toByte()
        bytes[4] = '0'.code.toByte()
        bytes[5] = '3'.code.toByte()
        bytes[6] = '5'.code.toByte()
        bytes[7] = 0
        putLe32(bytes, 32, fileSize)
        putLe32(bytes, 36, 0x70)
        putLe32(bytes, 40, 0x12345678)
        putLe32(bytes, 52, mapOffset)
        putLe32(bytes, 56, descriptors.size)
        putLe32(bytes, 60, stringIdsOffset)
        putLe32(bytes, 64, descriptors.size)
        putLe32(bytes, 68, typeIdsOffset)
        putLe32(bytes, 96, definedDescriptors.size)
        putLe32(bytes, 100, classDefsOffset)
        putLe32(bytes, 104, fileSize - dataOffset)
        putLe32(bytes, 108, dataOffset)

        stringOffsets.forEachIndexed { index, offset -> putLe32(bytes, stringIdsOffset + index * 4, offset) }
        descriptors.indices.forEach { index -> putLe32(bytes, typeIdsOffset + index * 4, index) }
        definedDescriptors.forEachIndexed { index, classDescriptor ->
            val offset = classDefsOffset + index * 32
            putLe32(bytes, offset, descriptors.indexOf(classDescriptor))
            putLe32(bytes, offset + 4, 0x0001)
            putLe32(bytes, offset + 8, objectTypeIndex)
            putLe32(
                bytes,
                offset + 12,
                if (index == 0 && implementsFactoryContract) interfaceListOffset else 0,
            )
            putLe32(bytes, offset + 16, -1)
        }
        val dataBytes = stringData.toByteArray()
        dataBytes.copyInto(bytes, dataOffset)
        mapBytes.copyInto(bytes, mapOffset)

        val signature = MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(32, bytes.size))
        signature.copyInto(bytes, 12)
        val checksum = Adler32().apply { update(bytes, 12, bytes.size - 12) }.value
        putLe32(bytes, 8, checksum.toInt())
        return bytes
    }

    fun policyFor(file: File, supportedAbis: Set<String> = setOf("armeabi-v7a")): AniyomiApkPolicy =
        AniyomiApkPolicy(
            expectedSizeBytes = file.length(),
            expectedSha256 = sha256(file.readBytes()),
            expectedPackageName = PACKAGE_NAME,
            expectedVersionCode = 17,
            expectedVersionName = "14.17",
            expectedMinSdk = 21,
            expectedTargetSdk = 32,
            expectedFactoryClassName = FACTORY_CLASS,
            expectedCertificateSha256 = CERTIFICATE_SHA256,
            supportedDeviceAbis = supportedAbis,
        )

    fun validPackageFacts(
        policy: AniyomiApkPolicy,
        packageName: String = policy.expectedPackageName,
        factoryMetadata: String = ".JellyfinFactory",
        requiredFeatures: Set<String> = setOf(policy.requiredExtensionFeature),
        certificateSha256: Set<String> = setOf(policy.expectedCertificateSha256),
        signatureVerified: Boolean = true,
    ): AniyomiPackageArchiveFacts = AniyomiPackageArchiveFacts(
        packageName = packageName,
        versionCode = policy.expectedVersionCode,
        versionName = policy.expectedVersionName,
        minSdk = policy.expectedMinSdk,
        targetSdk = policy.expectedTargetSdk,
        requiredFeatures = requiredFeatures,
        metadata = mapOf(
            policy.factoryMetadataKey to factoryMetadata,
            policy.nsfwMetadataKey to policy.expectedNsfwValue,
        ),
        requestedPermissions = emptySet(),
        activities = emptySet(),
        services = emptySet(),
        receivers = emptySet(),
        providers = emptySet(),
        signerCertificateSha256 = certificateSha256,
        signatureVerified = signatureVerified,
    )

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun descriptor(className: String): String =
        if (className.startsWith('L') && className.endsWith(';')) className
        else "L${className.replace('.', '/')};"

    private fun ZipOutputStream.add(path: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(bytes)
        closeEntry()
    }

    private fun ByteArrayOutputStream.writeUleb128(input: Int) {
        var value = input
        do {
            var current = value and 0x7F
            value = value ushr 7
            if (value != 0) current = current or 0x80
            write(current)
        } while (value != 0)
    }

    private fun ByteArrayOutputStream.writeLe16(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) {
        repeat(4) { shift -> write((value ushr (shift * 8)) and 0xFF) }
    }

    private fun putLe32(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { shift -> bytes[offset + shift] = (value ushr (shift * 8)).toByte() }
    }

    private fun align4(value: Int): Int = (value + 3) and -4

    private data class MapItem(val type: Int, val size: Int, val offset: Int)
}
