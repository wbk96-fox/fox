package com.foxtv.app.core.plugin.aniyomi

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException

/** Parses DEX structures as bytes; it never creates a VM DexFile or ClassLoader. */
internal object AniyomiDexInspector {
    private const val DEX_HEADER_SIZE = 0x70
    private const val DEX_ENDIAN_CONSTANT = 0x12345678L
    private const val NO_INDEX = 0xFFFF_FFFFL
    private const val ACC_PUBLIC = 0x0001
    private const val ACC_INTERFACE = 0x0200
    private const val ACC_ABSTRACT = 0x0400
    private const val ANIME_SOURCE_FACTORY_DESCRIPTOR =
        "Leu/kanade/tachiyomi/animesource/AnimeSourceFactory;"

    private val supportedDexVersions = setOf("035", "037", "038", "039", "040", "041")
    private val parentOwnedDescriptorPrefixes = listOf(
        "Ljava/",
        "Ljavax/",
        "Landroid/",
        "Ldalvik/",
        "Lorg/json/",
        "Lorg/w3c/",
        "Lorg/xml/",
        "Lcom/foxtv/",
        "Leu/kanade/tachiyomi/",
        "Landroidx/preference/",
        "Luy/kohesive/injekt/",
        "Lokhttp3/",
        "Lokio/",
        "Lkotlin/",
        "Lkotlinx/",
        "Lrx/",
    )

    fun inspect(
        apk: File,
        dexEntryNames: List<String>,
        policy: AniyomiApkPolicy,
    ): AniyomiApkDexReport {
        ensureAniyomiInspectionActive()
        val expectedFactoryDescriptor = classNameToDescriptor(policy.expectedFactoryClassName)
        val expectedChildDescriptorPrefix =
            "L${policy.expectedPackageName.replace('.', '/')}/"
        val allDefinedClasses = linkedSetOf<String>()
        var totalMethodReferences = 0L
        var factoryDefinition: DexClassDefinition? = null

        try {
            ZipFile(apk).use { zip ->
                dexEntryNames.forEach { entryName ->
                    ensureAniyomiInspectionActive()
                    val entry = zip.getEntry(entryName)
                        ?: rejectAniyomiApk(
                            AniyomiApkInspectionStage.DEX,
                            AniyomiApkRejectionCode.DEX_LAYOUT_INVALID,
                            "Validated DEX entry disappeared before static parsing",
                            entryName,
                        )
                    if (entry.size !in DEX_HEADER_SIZE.toLong()..policy.maxEntryBytes) {
                        rejectAniyomiApk(
                            AniyomiApkInspectionStage.DEX,
                            AniyomiApkRejectionCode.DEX_LIMIT_EXCEEDED,
                            "DEX entry size is outside the static parser budget",
                            entryName,
                        )
                    }
                    val bytes = zip.getInputStream(entry).use { stream ->
                        val output = ByteArray(entry.size.toInt())
                        var cursor = 0
                        while (cursor < output.size) {
                            ensureAniyomiInspectionActive()
                            val count = stream.read(output, cursor, output.size - cursor)
                            if (count < 0) break
                            if (count == 0) continue
                            cursor += count
                        }
                        if (cursor != output.size || stream.read() != -1) {
                            rejectAniyomiApk(
                                AniyomiApkInspectionStage.DEX,
                                AniyomiApkRejectionCode.DEX_MALFORMED,
                                "DEX entry length changed during parsing",
                                entryName,
                            )
                        }
                        output
                    }
                    val dex = parseDex(bytes, entryName, policy)
                    ensureAniyomiInspectionActive()
                    totalMethodReferences += dex.methodReferences
                    dex.classes.forEach { definition ->
                        ensureAniyomiInspectionActive()
                        if (!allDefinedClasses.add(definition.descriptor)) {
                            rejectAniyomiApk(
                                AniyomiApkInspectionStage.DEX,
                                AniyomiApkRejectionCode.DEX_LAYOUT_INVALID,
                                "A class is defined more than once across APK DEX files",
                                descriptorToClassName(definition.descriptor),
                            )
                        }
                        if (isParentOwnedDefinition(
                                definition.descriptor,
                                expectedChildDescriptorPrefix,
                            )
                        ) {
                            rejectAniyomiApk(
                                AniyomiApkInspectionStage.DEX,
                                AniyomiApkRejectionCode.HOST_CLASS_SHADOWING,
                                "APK defines a class owned by the Aniyomi host classpath",
                                descriptorToClassName(definition.descriptor),
                            )
                        }
                        if (definition.descriptor == expectedFactoryDescriptor) {
                            factoryDefinition = definition
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: AniyomiApkRejectionException) {
            throw rejected
        } catch (error: Exception) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.DEX,
                AniyomiApkRejectionCode.DEX_MALFORMED,
                "APK DEX files could not be parsed statically",
                cause = error,
            )
        }

        if (allDefinedClasses.size > policy.maxDefinedClasses) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.DEX,
                AniyomiApkRejectionCode.DEX_LIMIT_EXCEEDED,
                "APK defines more classes than the policy permits",
            )
        }
        val factory = factoryDefinition
            ?: rejectAniyomiApk(
                AniyomiApkInspectionStage.DEX,
                AniyomiApkRejectionCode.ENTRYPOINT_CLASS_MISSING,
                "Manifest factory class is absent from every APK DEX file",
                policy.expectedFactoryClassName,
            )
        if (factory.accessFlags and ACC_PUBLIC == 0 ||
            factory.accessFlags and (ACC_INTERFACE or ACC_ABSTRACT) != 0 ||
            ANIME_SOURCE_FACTORY_DESCRIPTOR !in factory.interfaces
        ) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.DEX,
                AniyomiApkRejectionCode.ENTRYPOINT_CONTRACT_MISMATCH,
                "Manifest factory is not a public concrete AnimeSourceFactory",
                policy.expectedFactoryClassName,
            )
        }

        return AniyomiApkDexReport(
            dexFiles = dexEntryNames.size,
            definedClasses = allDefinedClasses.size,
            methodReferences = totalMethodReferences.toInt(),
            factoryClassName = policy.expectedFactoryClassName,
            factoryInterfaces = factory.interfaces.mapTo(linkedSetOf(), ::descriptorToClassName),
        )
    }

    private fun parseDex(
        bytes: ByteArray,
        entryName: String,
        policy: AniyomiApkPolicy,
    ): ParsedDex {
        if (bytes.size < DEX_HEADER_SIZE ||
            bytes[0] != 'd'.code.toByte() || bytes[1] != 'e'.code.toByte() ||
            bytes[2] != 'x'.code.toByte() || bytes[3] != '\n'.code.toByte() || bytes[7] != 0.toByte()
        ) {
            rejectDex(entryName, "DEX magic is invalid")
        }
        val version = bytes.copyOfRange(4, 7).toString(Charsets.US_ASCII)
        if (version !in supportedDexVersions || version.any { !it.isDigit() }) {
            rejectDex(entryName, "DEX version is unsupported")
        }
        if (u32(bytes, 32) != bytes.size.toLong() || u32(bytes, 36) != DEX_HEADER_SIZE.toLong() ||
            u32(bytes, 40) != DEX_ENDIAN_CONSTANT
        ) {
            rejectDex(entryName, "DEX header size, file size, or endian tag is invalid")
        }

        val expectedSignature = bytes.copyOfRange(12, 32)
        val actualSignature = MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(32, bytes.size))
        if (!expectedSignature.contentEquals(actualSignature)) {
            rejectDex(entryName, "DEX SHA-1 header signature is invalid")
        }
        val expectedChecksum = u32(bytes, 8)
        val adler = Adler32().apply { update(bytes, 12, bytes.size - 12) }
        if (expectedChecksum != adler.value) {
            rejectDex(entryName, "DEX Adler-32 header checksum is invalid")
        }

        val mapOffset = u32(bytes, 52)
        val stringIds = section(bytes, 56, 60, 4, "string_ids", entryName)
        val typeIds = section(bytes, 64, 68, 4, "type_ids", entryName)
        section(bytes, 72, 76, 12, "proto_ids", entryName)
        section(bytes, 80, 84, 8, "field_ids", entryName)
        val methodIds = section(bytes, 88, 92, 8, "method_ids", entryName)
        val classDefs = section(bytes, 96, 100, 32, "class_defs", entryName)
        val dataSize = u32(bytes, 104)
        val dataOffset = u32(bytes, 108)
        if (dataSize == 0L || dataOffset < DEX_HEADER_SIZE ||
            dataOffset % 4L != 0L || safeEnd(dataOffset, dataSize, bytes.size, entryName) != bytes.size.toLong() ||
            mapOffset !in dataOffset until bytes.size.toLong()
        ) {
            rejectDex(entryName, "DEX data or map section bounds are invalid")
        }
        if (methodIds.size > policy.maxMethodReferencesPerDex) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.DEX,
                AniyomiApkRejectionCode.DEX_LIMIT_EXCEEDED,
                "DEX method reference count exceeds the policy limit",
                entryName,
            )
        }
        validateMap(bytes, mapOffset.toInt(), entryName)

        val stringOffsets = IntArray(stringIds.size) { index ->
            val value = u32(bytes, stringIds.offset + index * 4)
            if (value !in dataOffset until bytes.size.toLong()) rejectDex(entryName, "DEX string offset is invalid")
            value.toInt()
        }
        val strings = arrayOfNulls<String>(stringIds.size)
        fun stringAt(index: Int): String {
            if (index !in strings.indices) rejectDex(entryName, "DEX string index is invalid")
            return strings[index] ?: readModifiedUtf8(bytes, stringOffsets[index], entryName).also {
                strings[index] = it
            }
        }

        val typeDescriptors = Array(typeIds.size) { index ->
            val stringIndex = u32(bytes, typeIds.offset + index * 4)
            if (stringIndex >= stringIds.size.toLong()) rejectDex(entryName, "DEX type descriptor index is invalid")
            stringAt(stringIndex.toInt())
        }

        val classes = ArrayList<DexClassDefinition>(classDefs.size)
        repeat(classDefs.size) { index ->
            ensureAniyomiInspectionActive()
            val offset = classDefs.offset + index * 32
            val classIndex = u32(bytes, offset)
            val accessFlags = u32(bytes, offset + 4).toInt()
            val superIndex = u32(bytes, offset + 8)
            val interfacesOffset = u32(bytes, offset + 12)
            if (classIndex >= typeDescriptors.size.toLong()) rejectDex(entryName, "DEX class index is invalid")
            if (superIndex != NO_INDEX && superIndex >= typeDescriptors.size.toLong()) {
                rejectDex(entryName, "DEX superclass index is invalid")
            }
            val descriptor = typeDescriptors[classIndex.toInt()]
            if (!isClassDescriptor(descriptor)) rejectDex(entryName, "DEX class descriptor is invalid")
            val interfaces = if (interfacesOffset == 0L) {
                emptySet()
            } else {
                readTypeList(bytes, interfacesOffset, dataOffset, typeDescriptors, entryName)
            }
            classes += DexClassDefinition(descriptor, accessFlags, interfaces)
        }
        return ParsedDex(methodIds.size, classes)
    }

    private fun validateMap(bytes: ByteArray, mapOffset: Int, entryName: String) {
        if (mapOffset % 4 != 0 || mapOffset > bytes.size - 4) rejectDex(entryName, "DEX map offset is invalid")
        val count = u32(bytes, mapOffset)
        if (count !in 1..256) rejectDex(entryName, "DEX map item count is invalid")
        val end = mapOffset.toLong() + 4L + count * 12L
        if (end > bytes.size) rejectDex(entryName, "DEX map list exceeds file bounds")
        val types = HashSet<Int>(count.toInt())
        var previousOffset = -1L
        repeat(count.toInt()) { index ->
            ensureAniyomiInspectionActive()
            val offset = mapOffset + 4 + index * 12
            val type = u16(bytes, offset)
            val unused = u16(bytes, offset + 2)
            val size = u32(bytes, offset + 4)
            val itemOffset = u32(bytes, offset + 8)
            if (unused != 0 || size == 0L || itemOffset >= bytes.size || !types.add(type) ||
                itemOffset < previousOffset
            ) {
                rejectDex(entryName, "DEX map item is malformed or out of order")
            }
            previousOffset = itemOffset
        }
    }

    private fun readTypeList(
        bytes: ByteArray,
        rawOffset: Long,
        dataOffset: Long,
        typeDescriptors: Array<String>,
        entryName: String,
    ): Set<String> {
        if (rawOffset < dataOffset || rawOffset % 4L != 0L || rawOffset > bytes.size - 4L) {
            rejectDex(entryName, "DEX interface type-list offset is invalid")
        }
        val offset = rawOffset.toInt()
        val count = u32(bytes, offset)
        if (count > typeDescriptors.size.toLong()) rejectDex(entryName, "DEX interface count is invalid")
        val end = offset.toLong() + 4L + count * 2L
        if (end > bytes.size) rejectDex(entryName, "DEX interface type-list exceeds file bounds")
        val result = linkedSetOf<String>()
        repeat(count.toInt()) { index ->
            ensureAniyomiInspectionActive()
            val typeIndex = u16(bytes, offset + 4 + index * 2)
            if (typeIndex !in typeDescriptors.indices) rejectDex(entryName, "DEX interface type index is invalid")
            if (!result.add(typeDescriptors[typeIndex])) rejectDex(entryName, "DEX interface list contains duplicates")
        }
        return result
    }

    private fun readModifiedUtf8(bytes: ByteArray, rawOffset: Int, entryName: String): String {
        val (utf16Length, contentOffset) = readUleb128(bytes, rawOffset, entryName)
        var end = contentOffset
        while (end < bytes.size && bytes[end] != 0.toByte()) {
            ensureAniyomiInspectionActive()
            end++
            if (end - contentOffset > 65_535) rejectDex(entryName, "DEX string exceeds modified UTF-8 limit")
        }
        if (end >= bytes.size) rejectDex(entryName, "DEX string is not NUL terminated")
        val byteLength = end - contentOffset
        val encoded = ByteArray(byteLength + 2)
        encoded[0] = (byteLength ushr 8).toByte()
        encoded[1] = byteLength.toByte()
        bytes.copyInto(encoded, 2, contentOffset, end)
        val value = try {
            DataInputStream(ByteArrayInputStream(encoded)).use(DataInputStream::readUTF)
        } catch (error: Exception) {
            rejectDex(entryName, "DEX string contains invalid modified UTF-8", cause = error)
        }
        if (value.length != utf16Length) rejectDex(entryName, "DEX string UTF-16 length is inconsistent")
        return value
    }

    private fun readUleb128(bytes: ByteArray, start: Int, entryName: String): Pair<Int, Int> {
        var value = 0
        var shift = 0
        var offset = start
        repeat(5) {
            if (offset !in bytes.indices) rejectDex(entryName, "DEX ULEB128 value exceeds file bounds")
            val current = bytes[offset++].toInt() and 0xFF
            value = value or ((current and 0x7F) shl shift)
            if (current and 0x80 == 0) return value to offset
            shift += 7
        }
        rejectDex(entryName, "DEX ULEB128 value is too long")
    }

    private fun section(
        bytes: ByteArray,
        sizeFieldOffset: Int,
        offsetFieldOffset: Int,
        itemSize: Int,
        name: String,
        entryName: String,
    ): DexSection {
        val size = u32(bytes, sizeFieldOffset)
        val offset = u32(bytes, offsetFieldOffset)
        if (size == 0L) {
            if (offset != 0L) rejectDex(entryName, "DEX $name has an offset without items")
            return DexSection(0, 0)
        }
        if (size > Int.MAX_VALUE || offset < DEX_HEADER_SIZE || offset % 4L != 0L) {
            rejectDex(entryName, "DEX $name section header is invalid")
        }
        safeEnd(offset, size * itemSize, bytes.size, entryName)
        return DexSection(size.toInt(), offset.toInt())
    }

    private fun safeEnd(offset: Long, length: Long, fileSize: Int, entryName: String): Long {
        if (offset < 0L || length < 0L || offset > Long.MAX_VALUE - length || offset + length > fileSize) {
            rejectDex(entryName, "DEX section exceeds file bounds")
        }
        return offset + length
    }

    private fun rejectDex(entryName: String, message: String, cause: Throwable? = null): Nothing =
        rejectAniyomiApk(
            AniyomiApkInspectionStage.DEX,
            AniyomiApkRejectionCode.DEX_MALFORMED,
            message,
            entryName,
            cause,
        )

    private fun isParentOwnedDefinition(
        descriptor: String,
        expectedChildDescriptorPrefix: String,
    ): Boolean {
        if (descriptor.startsWith(expectedChildDescriptorPrefix)) return false
        if (isExtensionGeneratedDefinition(descriptor)) return false
        return parentOwnedDescriptorPrefixes.any(descriptor::startsWith)
    }

    private fun isExtensionGeneratedDefinition(descriptor: String): Boolean {
        if (descriptor == "Leu/kanade/tachiyomi/animeextension/BuildConfig;") return true
        val resourceClassRoots = listOf(
            "Leu/kanade/tachiyomi/animeextension/R",
            "Leu/kanade/tachiyomi/lib/core/R",
        )
        return resourceClassRoots.any { root ->
            if (descriptor == "$root;") return@any true
            val nestedPrefix = "$root\$"
            if (!descriptor.startsWith(nestedPrefix) || !descriptor.endsWith(';')) return@any false
            val resourceType = descriptor.substring(nestedPrefix.length, descriptor.length - 1)
            resourceType.isNotEmpty() && resourceType.all { it.isLetterOrDigit() || it == '_' }
        }
    }

    private fun classNameToDescriptor(className: String): String =
        "L${className.replace('.', '/')};"

    private fun descriptorToClassName(descriptor: String): String =
        if (isClassDescriptor(descriptor)) descriptor.substring(1, descriptor.length - 1).replace('/', '.')
        else descriptor

    private fun isClassDescriptor(value: String): Boolean =
        value.length >= 3 && value.startsWith('L') && value.endsWith(';') &&
            value.substring(1, value.length - 1).split('/').all { segment ->
                segment.isNotBlank() && segment != "." && segment != ".."
            }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset > bytes.size - 2) throw IndexOutOfBoundsException()
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset > bytes.size - 4) throw IndexOutOfBoundsException()
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    private data class DexSection(val size: Int, val offset: Int)
    private data class ParsedDex(val methodReferences: Int, val classes: List<DexClassDefinition>)
    private data class DexClassDefinition(
        val descriptor: String,
        val accessFlags: Int,
        val interfaces: Set<String>,
    )
}
