package com.foxtv.app.core.plugin.aniyomi

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException

internal data class AniyomiApkArchiveInspection(
    val report: AniyomiApkArchiveReport,
    val dexEntries: List<String>,
)

/** Strict, extraction-free APK ZIP validator. */
internal object AniyomiApkArchiveInspector {
    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50L
    private const val LOCAL_FILE_SIGNATURE = 0x04034b50L
    private const val EOCD_MIN_SIZE = 22
    private const val MAX_ZIP_COMMENT = 65_535
    private const val CENTRAL_DIRECTORY_FIXED_SIZE = 46
    private const val LOCAL_FILE_FIXED_SIZE = 30
    private const val UTF8_FLAG = 0x0800
    private const val ENCRYPTED_FLAG = 0x0001
    private const val STRONG_ENCRYPTION_FLAG = 0x0040
    private const val UNIX_PLATFORM = 3
    private const val UNIX_FILE_TYPE_MASK = 0xF000
    private const val UNIX_REGULAR_FILE = 0x8000
    private const val UNIX_DIRECTORY = 0x4000
    private const val UNIX_SYMBOLIC_LINK = 0xA000

    private val dexNamePattern = Regex("^classes(?:([2-9][0-9]*))?\\.dex$")
    private val nativeLibraryNamePattern = Regex("^[A-Za-z0-9_.+-]+\\.so$")
    private val drivePathPattern = Regex("^[A-Za-z]:[/\\\\]")

    fun inspect(file: File, policy: AniyomiApkPolicy): AniyomiApkArchiveInspection {
        ensureAniyomiInspectionActive()
        val centralEntries = try {
            readCentralDirectory(file, policy)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: AniyomiApkRejectionException) {
            throw rejected
        } catch (error: Exception) {
            rejectAniyomiApk(
                stage = AniyomiApkInspectionStage.ARCHIVE,
                code = AniyomiApkRejectionCode.MALFORMED_ARCHIVE,
                message = "APK central directory could not be parsed",
                cause = error,
            )
        }

        val names = centralEntries.map { it.name }.toSet()
        requireEntry(names, "AndroidManifest.xml")
        requireEntry(names, "resources.arsc")
        requireEntry(names, "classes.dex")

        val dexEntries = centralEntries
            .filterNot(CentralEntry::directory)
            .map(CentralEntry::name)
            .filter { it.endsWith(".dex", ignoreCase = true) }
            .map { name ->
                val match = dexNamePattern.matchEntire(name)
                    ?: rejectAniyomiApk(
                        AniyomiApkInspectionStage.DEX,
                        AniyomiApkRejectionCode.DEX_LAYOUT_INVALID,
                        "APK contains an unexpected DEX path",
                        name,
                    )
                val index = match.groupValues[1].takeIf(String::isNotEmpty)?.toIntOrNull() ?: 1
                index to name
            }
            .sortedBy(Pair<Int, String>::first)

        if (dexEntries.size !in policy.allowedDexCount) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.DEX,
                AniyomiApkRejectionCode.DEX_LAYOUT_INVALID,
                "APK DEX count is outside the accepted range",
            )
        }
        dexEntries.forEachIndexed { offset, (index, name) ->
            if (index != offset + 1) {
                rejectAniyomiApk(
                    AniyomiApkInspectionStage.DEX,
                    AniyomiApkRejectionCode.DEX_LAYOUT_INVALID,
                    "APK DEX sequence is not contiguous",
                    name,
                )
            }
        }

        val nativeAbis = linkedSetOf<String>()
        centralEntries.filterNot(CentralEntry::directory).forEach { entry ->
            if (!entry.name.startsWith("lib/")) return@forEach
            val segments = entry.name.split('/')
            if (segments.size != 3 ||
                segments[1].isBlank() ||
                !nativeLibraryNamePattern.matches(segments[2])
            ) {
                rejectAniyomiApk(
                    AniyomiApkInspectionStage.ABI,
                    AniyomiApkRejectionCode.NATIVE_LIBRARY_PATH_INVALID,
                    "APK native library path does not match lib/<abi>/<name>.so",
                    entry.name,
                )
            }
            nativeAbis += segments[1]
        }
        if (nativeAbis.isNotEmpty() && nativeAbis.none(policy.supportedDeviceAbis::contains)) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.ABI,
                AniyomiApkRejectionCode.ABI_INCOMPATIBLE,
                "APK has native libraries but none match the device ABI set",
                nativeAbis.sorted().joinToString(","),
            )
        }

        val actualUncompressed = verifyEveryEntry(file, centralEntries, policy)
        val compressedBytes = centralEntries.sumOf(CentralEntry::compressedSize)
        return AniyomiApkArchiveInspection(
            report = AniyomiApkArchiveReport(
                entryCount = centralEntries.size,
                compressedBytes = compressedBytes,
                uncompressedBytes = actualUncompressed,
                dexEntries = dexEntries.map(Pair<Int, String>::second),
                nativeAbis = nativeAbis,
            ),
            dexEntries = dexEntries.map(Pair<Int, String>::second),
        )
    }

    private fun requireEntry(names: Set<String>, required: String) {
        if (required !in names) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.ARCHIVE,
                AniyomiApkRejectionCode.REQUIRED_ENTRY_MISSING,
                "APK is missing a required entry",
                required,
            )
        }
    }

    private fun readCentralDirectory(file: File, policy: AniyomiApkPolicy): List<CentralEntry> =
        RandomAccessFile(file, "r").use { input ->
            val eocd = findEocd(input)
            if (eocd.diskNumber != 0 || eocd.centralDirectoryDisk != 0 ||
                eocd.entriesOnDisk != eocd.totalEntries
            ) {
                rejectAniyomiApk(
                    AniyomiApkInspectionStage.ARCHIVE,
                    AniyomiApkRejectionCode.UNSUPPORTED_ZIP_LAYOUT,
                    "Multi-disk APK archives are rejected",
                )
            }
            if (eocd.totalEntries == 0 || eocd.totalEntries > policy.maxArchiveEntries) {
                rejectAniyomiApk(
                    AniyomiApkInspectionStage.ARCHIVE,
                    AniyomiApkRejectionCode.ARCHIVE_LIMIT_EXCEEDED,
                    "APK entry count is outside the safety budget",
                )
            }
            if (eocd.zip64Sentinel) {
                rejectAniyomiApk(
                    AniyomiApkInspectionStage.ARCHIVE,
                    AniyomiApkRejectionCode.UNSUPPORTED_ZIP_LAYOUT,
                    "ZIP64 APK archives are rejected by this policy",
                )
            }

            val centralEnd = safeAdd(eocd.centralDirectoryOffset, eocd.centralDirectorySize)
            if (centralEnd != eocd.offset || centralEnd > input.length()) {
                rejectAniyomiApk(
                    AniyomiApkInspectionStage.ARCHIVE,
                    AniyomiApkRejectionCode.MALFORMED_ARCHIVE,
                    "APK central directory bounds are inconsistent",
                )
            }

            val entries = ArrayList<CentralEntry>(eocd.totalEntries)
            val rawNames = HashSet<String>(eocd.totalEntries)
            val normalizedNames = HashSet<String>(eocd.totalEntries)
            var cursor = eocd.centralDirectoryOffset
            var totalCompressed = 0L
            var totalUncompressed = 0L

            repeat(eocd.totalEntries) {
                ensureAniyomiInspectionActive()
                if (readU32(input, cursor) != CENTRAL_DIRECTORY_SIGNATURE) {
                    rejectAniyomiApk(
                        AniyomiApkInspectionStage.ARCHIVE,
                        AniyomiApkRejectionCode.MALFORMED_ARCHIVE,
                        "APK central directory entry signature is invalid",
                    )
                }
                val madeBy = readU16(input, cursor + 4)
                val flags = readU16(input, cursor + 8)
                val method = readU16(input, cursor + 10)
                val crc32 = readU32(input, cursor + 16)
                val compressedSize = readU32(input, cursor + 20)
                val uncompressedSize = readU32(input, cursor + 24)
                val nameLength = readU16(input, cursor + 28)
                val extraLength = readU16(input, cursor + 30)
                val commentLength = readU16(input, cursor + 32)
                val diskStart = readU16(input, cursor + 34)
                val externalAttributes = readU32(input, cursor + 38)
                val localHeaderOffset = readU32(input, cursor + 42)
                val variableLength = nameLength.toLong() + extraLength + commentLength
                val nextCursor = safeAdd(cursor, CENTRAL_DIRECTORY_FIXED_SIZE + variableLength)
                if (nameLength == 0 || nextCursor > centralEnd) {
                    rejectAniyomiApk(
                        AniyomiApkInspectionStage.ARCHIVE,
                        AniyomiApkRejectionCode.MALFORMED_ARCHIVE,
                        "APK central directory entry has invalid bounds",
                    )
                }
                if (diskStart != 0) {
                    rejectAniyomiApk(
                        AniyomiApkInspectionStage.ARCHIVE,
                        AniyomiApkRejectionCode.UNSUPPORTED_ZIP_LAYOUT,
                        "APK entry points to another ZIP disk",
                    )
                }
                if (flags and (ENCRYPTED_FLAG or STRONG_ENCRYPTION_FLAG) != 0) {
                    rejectAniyomiApk(
                        AniyomiApkInspectionStage.ARCHIVE,
                        AniyomiApkRejectionCode.ENCRYPTED_ENTRY,
                        "Encrypted APK entries are rejected",
                    )
                }
                if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
                    rejectAniyomiApk(
                        AniyomiApkInspectionStage.ARCHIVE,
                        AniyomiApkRejectionCode.UNSUPPORTED_COMPRESSION,
                        "APK entry uses an unsupported compression method",
                    )
                }

                val nameBytes = readBytes(input, cursor + CENTRAL_DIRECTORY_FIXED_SIZE, nameLength)
                val name = decodeName(nameBytes, flags)
                val normalizedName = validateEntryName(name)
                if (!rawNames.add(name) || !normalizedNames.add(normalizedName)) {
                    rejectAniyomiApk(
                        AniyomiApkInspectionStage.ARCHIVE,
                        AniyomiApkRejectionCode.DUPLICATE_ENTRY,
                        "APK contains duplicate or colliding entry names",
                        name,
                    )
                }

                val directory = name.endsWith('/')
                validateUnixFileType(madeBy ushr 8, externalAttributes, directory, name)
                if (uncompressedSize > policy.maxEntryBytes) {
                    rejectAniyomiApk(
                        AniyomiApkInspectionStage.ARCHIVE,
                        AniyomiApkRejectionCode.ARCHIVE_LIMIT_EXCEEDED,
                        "APK entry exceeds the per-entry byte budget",
                        name,
                    )
                }
                if (uncompressedSize > 0L && compressedSize == 0L) {
                    rejectAniyomiApk(
                        AniyomiApkInspectionStage.ARCHIVE,
                        AniyomiApkRejectionCode.ARCHIVE_LIMIT_EXCEEDED,
                        "APK entry declares an unbounded compression ratio",
                        name,
                    )
                }
                if (compressedSize > 0L &&
                    uncompressedSize > compressedSize * policy.maxCompressionRatio
                ) {
                    rejectAniyomiApk(
                        AniyomiApkInspectionStage.ARCHIVE,
                        AniyomiApkRejectionCode.ARCHIVE_LIMIT_EXCEEDED,
                        "APK entry exceeds the compression-ratio budget",
                        name,
                    )
                }

                totalCompressed = safeAdd(totalCompressed, compressedSize)
                totalUncompressed = safeAdd(totalUncompressed, uncompressedSize)
                if (totalCompressed > policy.maxArchiveBytes || totalUncompressed > policy.maxArchiveBytes) {
                    rejectAniyomiApk(
                        AniyomiApkInspectionStage.ARCHIVE,
                        AniyomiApkRejectionCode.ARCHIVE_LIMIT_EXCEEDED,
                        "APK archive exceeds the aggregate byte budget",
                    )
                }

                val localDataEnd = validateLocalHeader(
                    input = input,
                    centralDirectoryOffset = eocd.centralDirectoryOffset,
                    localHeaderOffset = localHeaderOffset,
                    expectedName = name,
                    expectedFlags = flags,
                    expectedMethod = method,
                    compressedSize = compressedSize,
                )

                entries += CentralEntry(
                    name = name,
                    flags = flags,
                    method = method,
                    crc32 = crc32,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    localHeaderOffset = localHeaderOffset,
                    directory = directory,
                    localDataEnd = localDataEnd,
                )
                cursor = nextCursor
            }

            if (cursor != centralEnd) {
                rejectAniyomiApk(
                    AniyomiApkInspectionStage.ARCHIVE,
                    AniyomiApkRejectionCode.MALFORMED_ARCHIVE,
                    "APK central directory contains trailing structures",
                )
            }

            entries.sortedBy(CentralEntry::localHeaderOffset)
                .zipWithNext()
                .forEach { (left, right) ->
                    if (left.localDataEnd > right.localHeaderOffset) {
                        rejectAniyomiApk(
                            AniyomiApkInspectionStage.ARCHIVE,
                            AniyomiApkRejectionCode.MALFORMED_ARCHIVE,
                            "APK local file records overlap",
                            right.name,
                        )
                    }
                }
            entries
        }

    private fun findEocd(input: RandomAccessFile): Eocd {
        val length = input.length()
        if (length < EOCD_MIN_SIZE) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.ARCHIVE,
                AniyomiApkRejectionCode.MALFORMED_ARCHIVE,
                "APK is shorter than a ZIP end record",
            )
        }
        val searchLength = minOf(length, (EOCD_MIN_SIZE + MAX_ZIP_COMMENT).toLong()).toInt()
        val searchOffset = length - searchLength
        val tail = readBytes(input, searchOffset, searchLength)
        for (index in tail.size - EOCD_MIN_SIZE downTo 0) {
            ensureAniyomiInspectionActive()
            if (u32(tail, index) != EOCD_SIGNATURE) continue
            val commentLength = u16(tail, index + 20)
            if (index + EOCD_MIN_SIZE + commentLength != tail.size) continue
            val entriesOnDisk = u16(tail, index + 8)
            val totalEntries = u16(tail, index + 10)
            val centralSize = u32(tail, index + 12)
            val centralOffset = u32(tail, index + 16)
            return Eocd(
                offset = searchOffset + index,
                diskNumber = u16(tail, index + 4),
                centralDirectoryDisk = u16(tail, index + 6),
                entriesOnDisk = entriesOnDisk,
                totalEntries = totalEntries,
                centralDirectorySize = centralSize,
                centralDirectoryOffset = centralOffset,
                zip64Sentinel = entriesOnDisk == 0xFFFF || totalEntries == 0xFFFF ||
                    centralSize == 0xFFFF_FFFFL || centralOffset == 0xFFFF_FFFFL,
            )
        }
        rejectAniyomiApk(
            AniyomiApkInspectionStage.ARCHIVE,
            AniyomiApkRejectionCode.MALFORMED_ARCHIVE,
            "APK ZIP end record was not found",
        )
    }

    private fun validateLocalHeader(
        input: RandomAccessFile,
        centralDirectoryOffset: Long,
        localHeaderOffset: Long,
        expectedName: String,
        expectedFlags: Int,
        expectedMethod: Int,
        compressedSize: Long,
    ): Long {
        if (localHeaderOffset < 0L ||
            safeAdd(localHeaderOffset, LOCAL_FILE_FIXED_SIZE.toLong()) > centralDirectoryOffset ||
            readU32(input, localHeaderOffset) != LOCAL_FILE_SIGNATURE
        ) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.ARCHIVE,
                AniyomiApkRejectionCode.MALFORMED_ARCHIVE,
                "APK local file header is invalid",
                expectedName,
            )
        }
        val localFlags = readU16(input, localHeaderOffset + 6)
        val localMethod = readU16(input, localHeaderOffset + 8)
        val localNameLength = readU16(input, localHeaderOffset + 26)
        val localExtraLength = readU16(input, localHeaderOffset + 28)
        val dataStart = safeAdd(
            localHeaderOffset,
            LOCAL_FILE_FIXED_SIZE.toLong() + localNameLength + localExtraLength,
        )
        val dataEnd = safeAdd(dataStart, compressedSize)
        if (dataEnd > centralDirectoryOffset || localFlags != expectedFlags || localMethod != expectedMethod) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.ARCHIVE,
                AniyomiApkRejectionCode.MALFORMED_ARCHIVE,
                "APK local and central file headers disagree",
                expectedName,
            )
        }
        val localName = decodeName(
            readBytes(input, localHeaderOffset + LOCAL_FILE_FIXED_SIZE, localNameLength),
            localFlags,
        )
        if (localName != expectedName) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.ARCHIVE,
                AniyomiApkRejectionCode.MALFORMED_ARCHIVE,
                "APK local and central entry names disagree",
                expectedName,
            )
        }
        return dataEnd
    }

    private fun validateUnixFileType(
        platform: Int,
        externalAttributes: Long,
        directory: Boolean,
        name: String,
    ) {
        if (platform != UNIX_PLATFORM) return
        val unixMode = ((externalAttributes ushr 16) and 0xFFFF).toInt()
        val fileType = unixMode and UNIX_FILE_TYPE_MASK
        if (fileType == UNIX_SYMBOLIC_LINK) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.ARCHIVE,
                AniyomiApkRejectionCode.UNSAFE_ENTRY_PATH,
                "APK symbolic-link entries are rejected",
                name,
            )
        }
        val expectedType = if (directory) UNIX_DIRECTORY else UNIX_REGULAR_FILE
        if (fileType != 0 && fileType != expectedType) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.ARCHIVE,
                AniyomiApkRejectionCode.UNSAFE_ENTRY_PATH,
                "APK entry has an unsupported Unix file type",
                name,
            )
        }
    }

    private fun validateEntryName(rawName: String): String {
        if (rawName.isBlank() || rawName.length > 1_024 || rawName.indexOf('\u0000') >= 0 ||
            rawName.startsWith('/') || rawName.startsWith('\\') || drivePathPattern.containsMatchIn(rawName)
        ) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.ARCHIVE,
                AniyomiApkRejectionCode.UNSAFE_ENTRY_PATH,
                "APK contains an unsafe entry path",
                rawName,
            )
        }
        if ('\\' in rawName) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.ARCHIVE,
                AniyomiApkRejectionCode.UNSAFE_ENTRY_PATH,
                "APK entry paths must use forward slashes",
                rawName,
            )
        }
        val body = rawName.removeSuffix("/")
        val segments = body.split('/')
        if (body.isBlank() || segments.any { it.isBlank() || it == "." || it == ".." }) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.ARCHIVE,
                AniyomiApkRejectionCode.UNSAFE_ENTRY_PATH,
                "APK entry path contains an unsafe segment",
                rawName,
            )
        }
        return if (rawName.endsWith('/')) "$body/" else body
    }

    private fun verifyEveryEntry(
        file: File,
        expectedEntries: List<CentralEntry>,
        policy: AniyomiApkPolicy,
    ): Long {
        try {
            ZipFile(file, StandardCharsets.UTF_8).use { zip ->
                val actualEntries = mutableListOf<ZipEntry>()
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    ensureAniyomiInspectionActive()
                    actualEntries += enumeration.nextElement()
                }
                if (actualEntries.size != expectedEntries.size) {
                    rejectAniyomiApk(
                        AniyomiApkInspectionStage.ARCHIVE,
                        AniyomiApkRejectionCode.ARCHIVE_INTEGRITY_MISMATCH,
                        "ZIP reader and central directory disagree on APK entry count",
                    )
                }
                val byName = expectedEntries.associateBy(CentralEntry::name)
                var aggregate = 0L
                actualEntries.forEach { entry ->
                    ensureAniyomiInspectionActive()
                    val expected = byName[entry.name]
                        ?: rejectAniyomiApk(
                            AniyomiApkInspectionStage.ARCHIVE,
                            AniyomiApkRejectionCode.ARCHIVE_INTEGRITY_MISMATCH,
                            "ZIP reader exposed an unexpected APK entry",
                            entry.name,
                        )
                    if (entry.method != expected.method || entry.size != expected.uncompressedSize ||
                        entry.compressedSize != expected.compressedSize || entry.crc != expected.crc32
                    ) {
                        rejectAniyomiApk(
                            AniyomiApkInspectionStage.ARCHIVE,
                            AniyomiApkRejectionCode.ARCHIVE_INTEGRITY_MISMATCH,
                            "APK entry metadata is inconsistent",
                            entry.name,
                        )
                    }
                    if (expected.directory) return@forEach
                    val crc = CRC32()
                    var entryBytes = 0L
                    zip.getInputStream(entry).use { stream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            ensureAniyomiInspectionActive()
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            entryBytes = safeAdd(entryBytes, count.toLong())
                            aggregate = safeAdd(aggregate, count.toLong())
                            if (entryBytes > expected.uncompressedSize ||
                                entryBytes > policy.maxEntryBytes || aggregate > policy.maxArchiveBytes
                            ) {
                                rejectAniyomiApk(
                                    AniyomiApkInspectionStage.ARCHIVE,
                                    AniyomiApkRejectionCode.ARCHIVE_LIMIT_EXCEEDED,
                                    "APK entry exceeded its declared or configured byte budget",
                                    entry.name,
                                )
                            }
                            crc.update(buffer, 0, count)
                        }
                    }
                    if (entryBytes != expected.uncompressedSize || crc.value != expected.crc32) {
                        rejectAniyomiApk(
                            AniyomiApkInspectionStage.ARCHIVE,
                            AniyomiApkRejectionCode.ARCHIVE_INTEGRITY_MISMATCH,
                            "APK entry content does not match its size or CRC",
                            entry.name,
                        )
                    }
                }
                return aggregate
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: AniyomiApkRejectionException) {
            throw rejected
        } catch (error: ZipException) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.ARCHIVE,
                AniyomiApkRejectionCode.MALFORMED_ARCHIVE,
                "APK ZIP structure is malformed",
                cause = error,
            )
        } catch (error: Exception) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.ARCHIVE,
                AniyomiApkRejectionCode.ARCHIVE_INTEGRITY_MISMATCH,
                "APK entries could not be read safely",
                cause = error,
            )
        }
    }

    private fun decodeName(bytes: ByteArray, flags: Int): String {
        val charset = if (flags and UTF8_FLAG != 0) StandardCharsets.UTF_8 else Charset.forName("CP437")
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.ARCHIVE,
                AniyomiApkRejectionCode.UNSAFE_ENTRY_PATH,
                "APK entry name is not valid in its declared ZIP charset",
                cause = error,
            )
        }
    }

    private fun safeAdd(left: Long, right: Long): Long {
        if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.ARCHIVE,
                AniyomiApkRejectionCode.ARCHIVE_LIMIT_EXCEEDED,
                "APK archive size arithmetic overflowed",
            )
        }
        return left + right
    }

    private fun readU16(input: RandomAccessFile, offset: Long): Int =
        u16(readBytes(input, offset, 2), 0)

    private fun readU32(input: RandomAccessFile, offset: Long): Long =
        u32(readBytes(input, offset, 4), 0)

    private fun readBytes(input: RandomAccessFile, offset: Long, count: Int): ByteArray {
        if (offset < 0L || count < 0 || offset > input.length() - count) throw EOFException()
        val bytes = ByteArray(count)
        input.seek(offset)
        input.readFully(bytes)
        return bytes
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL

    private data class Eocd(
        val offset: Long,
        val diskNumber: Int,
        val centralDirectoryDisk: Int,
        val entriesOnDisk: Int,
        val totalEntries: Int,
        val centralDirectorySize: Long,
        val centralDirectoryOffset: Long,
        val zip64Sentinel: Boolean,
    )

    private data class CentralEntry(
        val name: String,
        val flags: Int,
        val method: Int,
        val crc32: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val directory: Boolean,
        val localDataEnd: Long,
    )
}
