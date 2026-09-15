package com.foxtv.app.core.plugin.aniyomi

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile

internal data class AniyomiContractReference(
    val kind: Kind,
    val owner: String,
    val name: String,
    val descriptor: String,
    val dispatch: Dispatch,
) {
    enum class Kind(val token: String) {
        CLASS("C"),
        METHOD("M"),
        FIELD("F"),
    }

    enum class Dispatch {
        TYPE,
        STATIC,
        INSTANCE,
        CONSTRUCTOR,
    }

    fun canonical(): String = listOf(kind.token, owner, name, descriptor, dispatch.name).joinToString("|")

    companion object {
        fun parse(canonical: String): AniyomiContractReference {
            val parts = canonical.split('|')
            require(parts.size == 5) { "Malformed contract record: $canonical" }
            return AniyomiContractReference(
                kind = Kind.entries.single { it.token == parts[0] },
                owner = parts[1],
                name = parts[2],
                descriptor = parts[3],
                dispatch = Dispatch.valueOf(parts[4]),
            )
        }
    }
}

internal data class AniyomiDexCallSite(
    val callerOwner: String,
    val callerName: String,
    val callerDescriptor: String,
    val codeUnitOffset: Int,
    val calleeOwner: String,
    val calleeName: String,
    val calleeDescriptor: String,
    val mask: Int,
)

/** Byte-only DEX contract reader. It never creates a DexFile or a ClassLoader. */
internal object AniyomiDexContractReader {
    fun references(apk: File, scopePrefixes: List<String>): Set<AniyomiContractReference> {
        val records = linkedSetOf<AniyomiContractReference>()
        readDexFiles(apk).forEach { (_, bytes) -> records += Dex(bytes).references(scopePrefixes) }
        return records
    }

    fun callSites(apk: File, targets: Set<MethodKey>): Set<AniyomiDexCallSite> {
        val records = linkedSetOf<AniyomiDexCallSite>()
        readDexFiles(apk).forEach { (_, bytes) -> records += Dex(bytes).callSites(targets) }
        return records
    }

    data class MethodKey(
        val ownerDescriptor: String,
        val name: String,
        val descriptor: String,
    )

    private fun readDexFiles(apk: File): List<Pair<String, ByteArray>> = ZipFile(apk).use { zip ->
        val entries = zip.entries().asSequence()
            .filter { !it.isDirectory && DEX_NAME.matches(it.name) }
            .map { entry ->
                val ordinal = DEX_NAME.matchEntire(entry.name)?.groupValues?.get(1)
                    ?.takeIf(String::isNotEmpty)?.toInt() ?: 1
                Triple(ordinal, entry.name, entry)
            }
            .sortedBy { it.first }
            .toList()
        require(entries.isNotEmpty()) { "APK contains no classes*.dex entries" }
        require(entries.map { it.first } == (1..entries.size).toList()) {
            "APK DEX entries are not contiguous"
        }
        entries.map { (_, name, entry) -> name to zip.getInputStream(entry).use { it.readBytes() } }
    }

    private class Dex(private val bytes: ByteArray) {
        private val strings: List<String>
        private val types: List<String>
        private val protos: List<String>
        private val fields: List<FieldId>
        private val methods: List<MethodId>
        private val classDefsOffset: Int
        private val classDefsSize: Int

        init {
            require(bytes.size >= HEADER_SIZE && bytes.copyOfRange(0, 4).contentEquals(DEX_MAGIC)) {
                "Malformed DEX header"
            }
            require(u32(32) == bytes.size.toLong() && u32(36) == HEADER_SIZE.toLong()) {
                "Inconsistent DEX size"
            }
            strings = readStrings()
            types = readTypes(strings)
            protos = readProtos(types)
            fields = readFields(types, strings)
            methods = readMethods(types, strings, protos)
            classDefsSize = countAt(96)
            classDefsOffset = offsetAt(100, classDefsSize)
        }

        fun references(scopePrefixes: List<String>): Set<AniyomiContractReference> {
            val definitions = definitions()
            val scan = scanCode(definitions.codeMethods, emptySet())
            val prefixDescriptors = scopePrefixes.map { "L${it.replace('.', '/')}" }
            fun ownerMatches(owner: String): Boolean = prefixDescriptors.any(owner::startsWith)
            fun memberMatches(owner: String, descriptor: String): Boolean =
                ownerMatches(owner) || prefixDescriptors.any(descriptor::contains)

            val result = linkedSetOf<AniyomiContractReference>()
            methods.forEachIndexed { index, method ->
                if (index in definitions.methodIndices || !memberMatches(method.owner, method.descriptor)) return@forEachIndexed
                val dispatches = scan.methodDispatches[index].orEmpty()
                require(dispatches.size == 1) {
                    "Method dispatch is absent or ambiguous for ${method.owner}->${method.name}${method.descriptor}: $dispatches"
                }
                result += AniyomiContractReference(
                    kind = AniyomiContractReference.Kind.METHOD,
                    owner = className(method.owner),
                    name = method.name,
                    descriptor = method.descriptor,
                    dispatch = dispatches.single(),
                )
            }
            fields.forEachIndexed { index, field ->
                if (index in definitions.fieldIndices || !memberMatches(field.owner, field.descriptor)) return@forEachIndexed
                val dispatches = scan.fieldDispatches[index].orEmpty()
                require(dispatches.size == 1) {
                    "Field dispatch is absent or ambiguous for ${field.owner}->${field.name}:${field.descriptor}: $dispatches"
                }
                result += AniyomiContractReference(
                    kind = AniyomiContractReference.Kind.FIELD,
                    owner = className(field.owner),
                    name = field.name,
                    descriptor = field.descriptor,
                    dispatch = dispatches.single(),
                )
            }
            (methods.indices.asSequence()
                .filterNot(definitions.methodIndices::contains)
                .map { methods[it].owner } +
                fields.indices.asSequence()
                    .filterNot(definitions.fieldIndices::contains)
                    .map { fields[it].owner })
                .filter(::ownerMatches)
                .filterNot(definitions.classDescriptors::contains)
                .distinct()
                .forEach { owner ->
                    result += AniyomiContractReference(
                        kind = AniyomiContractReference.Kind.CLASS,
                        owner = className(owner),
                        name = "",
                        descriptor = owner,
                        dispatch = AniyomiContractReference.Dispatch.TYPE,
                    )
                }
            return result
        }

        fun callSites(targets: Set<MethodKey>): Set<AniyomiDexCallSite> {
            val definitions = definitions()
            return scanCode(definitions.codeMethods, targets).callSites
        }

        private fun definitions(): Definitions {
            val classDescriptors = linkedSetOf<String>()
            val fieldIndices = linkedSetOf<Int>()
            val methodIndices = linkedSetOf<Int>()
            val codeMethods = mutableListOf<CodeMethod>()

            repeat(classDefsSize) { classIndex ->
                val definitionOffset = classDefsOffset + classIndex * CLASS_DEF_SIZE
                val typeIndex = intIndex(u32(definitionOffset), types.size, "class type")
                classDescriptors += types[typeIndex]
                val classDataOffset = u32(definitionOffset + 24).toInt()
                if (classDataOffset == 0) return@repeat
                var cursor = classDataOffset
                val staticFields = readUleb128(cursor).also { cursor = it.next }.value
                val instanceFields = readUleb128(cursor).also { cursor = it.next }.value
                val directMethods = readUleb128(cursor).also { cursor = it.next }.value
                val virtualMethods = readUleb128(cursor).also { cursor = it.next }.value

                listOf(staticFields, instanceFields).forEach { fieldCount ->
                    var fieldId = 0
                    repeat(fieldCount) {
                        val delta = readUleb128(cursor).also { cursor = it.next }.value
                        fieldId += delta
                        require(fieldId in fields.indices) { "Invalid defined field index" }
                        fieldIndices += fieldId
                        cursor = readUleb128(cursor).next
                    }
                }
                listOf(directMethods, virtualMethods).forEach { methodCount ->
                    var methodId = 0
                    repeat(methodCount) {
                        val delta = readUleb128(cursor).also { cursor = it.next }.value
                        methodId += delta
                        require(methodId in methods.indices) { "Invalid defined method index" }
                        methodIndices += methodId
                        cursor = readUleb128(cursor).next
                        val codeOffset = readUleb128(cursor).also { cursor = it.next }.value
                        if (codeOffset != 0) codeMethods += CodeMethod(methodId, codeOffset)
                    }
                }
            }
            return Definitions(classDescriptors, fieldIndices, methodIndices, codeMethods)
        }

        private fun scanCode(
            codeMethods: List<CodeMethod>,
            callSiteTargets: Set<MethodKey>,
        ): CodeScan {
            val methodDispatches = mutableMapOf<Int, MutableSet<AniyomiContractReference.Dispatch>>()
            val fieldDispatches = mutableMapOf<Int, MutableSet<AniyomiContractReference.Dispatch>>()
            val callSites = linkedSetOf<AniyomiDexCallSite>()

            annotationEnumFieldIndices().forEach { fieldIndex ->
                addDispatch(
                    fieldDispatches,
                    fieldIndex,
                    AniyomiContractReference.Dispatch.STATIC,
                    fields.size,
                )
            }

            codeMethods.forEach { codeMethod ->
                require(codeMethod.codeOffset in 0..bytes.size - CODE_ITEM_HEADER_SIZE) { "Invalid code_item offset" }
                val instructionCount = u32(codeMethod.codeOffset + 12).toInt()
                val instructionsOffset = codeMethod.codeOffset + CODE_ITEM_HEADER_SIZE
                require(instructionsOffset.toLong() + instructionCount.toLong() * 2L <= bytes.size) {
                    "code_item instructions exceed DEX bounds"
                }
                val instructions = IntArray(instructionCount) { u16(instructionsOffset + it * 2) }
                val constants = mutableMapOf<Int, Int?>()
                var offset = 0
                while (offset < instructions.size) {
                    val first = instructions[offset]
                    val opcode = first and 0xFF
                    trackConstant(opcode, first, instructions, offset, constants)

                    when (opcode) {
                        in 0x52..0x5F -> addDispatch(
                            fieldDispatches,
                            instructions.requireUnit(offset + 1, "instance field index"),
                            AniyomiContractReference.Dispatch.INSTANCE,
                            fields.size,
                        )
                        in 0x60..0x6D -> addDispatch(
                            fieldDispatches,
                            instructions.requireUnit(offset + 1, "static field index"),
                            AniyomiContractReference.Dispatch.STATIC,
                            fields.size,
                        )
                        in 0x6E..0x72, in 0x74..0x78 -> {
                            val methodIndex = instructions.requireUnit(offset + 1, "method index")
                            require(methodIndex in methods.indices) { "Invalid invoked method index" }
                            val method = methods[methodIndex]
                            val dispatch = when (opcode) {
                                0x71, 0x77 -> AniyomiContractReference.Dispatch.STATIC
                                0x70, 0x76 -> if (method.name == "<init>") {
                                    AniyomiContractReference.Dispatch.CONSTRUCTOR
                                } else {
                                    AniyomiContractReference.Dispatch.INSTANCE
                                }
                                else -> AniyomiContractReference.Dispatch.INSTANCE
                            }
                            methodDispatches.getOrPut(methodIndex, ::linkedSetOf) += dispatch

                            val target = MethodKey(method.owner, method.name, method.descriptor)
                            if (target in callSiteTargets) {
                                val registers = invokeRegisters(opcode, first, instructions, offset)
                                val parameterIndex = maskParameterIndex(target)
                                val receiverOffset = if (dispatch == AniyomiContractReference.Dispatch.STATIC) 0 else 1
                                val registerPosition = receiverOffset + parameterIndex
                                require(registerPosition in registers.indices) {
                                    "Mask register is absent at ${methods[codeMethod.methodIndex]}+$offset"
                                }
                                val maskRegister = registers[registerPosition]
                                val mask = requireNotNull(constants[maskRegister]) {
                                    "Mask is not a tracked integer constant at ${methods[codeMethod.methodIndex]}+$offset"
                                }
                                val caller = methods[codeMethod.methodIndex]
                                callSites += AniyomiDexCallSite(
                                    callerOwner = caller.owner,
                                    callerName = caller.name,
                                    callerDescriptor = caller.descriptor,
                                    codeUnitOffset = offset,
                                    calleeOwner = method.owner,
                                    calleeName = method.name,
                                    calleeDescriptor = method.descriptor,
                                    mask = mask,
                                )
                            }
                        }
                    }
                    offset += instructionWidth(instructions, offset)
                }
                require(offset == instructions.size) { "Instruction decoding did not end at code_item boundary" }
            }
            return CodeScan(methodDispatches, fieldDispatches, callSites)
        }

        private fun trackConstant(
            opcode: Int,
            first: Int,
            instructions: IntArray,
            offset: Int,
            constants: MutableMap<Int, Int?>,
        ) {
            when (opcode) {
                0x01, 0x04, 0x07 -> {
                    val destination = first ushr 8 and 0xF
                    val source = first ushr 12 and 0xF
                    constants[destination] = constants[source]
                }
                0x02, 0x05, 0x08 -> {
                    val destination = first ushr 8 and 0xFF
                    constants[destination] = constants[instructions.requireUnit(offset + 1, "move source")]
                }
                0x03, 0x06, 0x09 -> {
                    val destination = instructions.requireUnit(offset + 1, "move destination")
                    val source = instructions.requireUnit(offset + 2, "move source")
                    constants[destination] = constants[source]
                }
                0x12 -> {
                    val destination = first ushr 8 and 0xF
                    constants[destination] = signExtend(first ushr 12 and 0xF, 4)
                }
                0x13 -> {
                    val destination = first ushr 8 and 0xFF
                    constants[destination] = signExtend(instructions.requireUnit(offset + 1, "const/16"), 16)
                }
                0x14 -> {
                    val destination = first ushr 8 and 0xFF
                    val value = instructions.requireUnit(offset + 1, "const low") or
                        (instructions.requireUnit(offset + 2, "const high") shl 16)
                    constants[destination] = value
                }
                0x15 -> {
                    val destination = first ushr 8 and 0xFF
                    constants[destination] = signExtend(
                        instructions.requireUnit(offset + 1, "const/high16"),
                        16,
                    ) shl 16
                }
            }
        }

        private fun invokeRegisters(
            opcode: Int,
            first: Int,
            instructions: IntArray,
            offset: Int,
        ): List<Int> = if (opcode in 0x6E..0x72) {
            val count = first ushr 12 and 0xF
            require(count <= 5) { "Invalid invoke register count" }
            val fifth = first ushr 8 and 0xF
            val packed = instructions.requireUnit(offset + 2, "invoke registers")
            listOf(
                packed and 0xF,
                packed ushr 4 and 0xF,
                packed ushr 8 and 0xF,
                packed ushr 12 and 0xF,
                fifth,
            ).take(count)
        } else {
            val count = first ushr 8 and 0xFF
            val start = instructions.requireUnit(offset + 2, "invoke/range start")
            List(count) { start + it }
        }

        private fun maskParameterIndex(method: MethodKey): Int = when {
            method.ownerDescriptor == REQUESTS_DESCRIPTOR && method.name == "GET\$default" -> 3
            method.ownerDescriptor == REQUESTS_DESCRIPTOR && method.name == "POST\$default" -> 4
            method.ownerDescriptor == VIDEO_DESCRIPTOR && method.name == "<init>" -> 6
            else -> error("No mask parameter contract for $method")
        }

        private fun instructionWidth(instructions: IntArray, offset: Int): Int {
            val first = instructions.requireUnit(offset, "opcode")
            val opcode = first and 0xFF
            if (opcode == 0x00) {
                return when (first) {
                    0x0100 -> 4 + instructions.requireUnit(offset + 1, "packed-switch size") * 2
                    0x0200 -> 2 + instructions.requireUnit(offset + 1, "sparse-switch size") * 4
                    0x0300 -> {
                        val elementWidth = instructions.requireUnit(offset + 1, "array element width")
                        val size = instructions.requireUnit(offset + 2, "array size low").toLong() or
                            (instructions.requireUnit(offset + 3, "array size high").toLong() shl 16)
                        val dataUnits = (elementWidth.toLong() * size + 1L) / 2L
                        require(dataUnits <= Int.MAX_VALUE - 4L) { "fill-array-data payload is too large" }
                        4 + dataUnits.toInt()
                    }
                    else -> 1
                }
            }
            return when {
                opcode == 0x18 -> 5
                opcode == 0xFA || opcode == 0xFB -> 4
                opcode in setOf(
                    0x03, 0x06, 0x09, 0x14, 0x17, 0x1B, 0x24, 0x25, 0x26,
                    0x2A, 0x2B, 0x2C,
                ) || opcode in 0x6E..0x72 || opcode in 0x74..0x78 || opcode in 0xFC..0xFD -> 3
                opcode in setOf(
                    0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1A, 0x1C,
                    0x1F, 0x20, 0x22, 0x23, 0x29, 0xFE, 0xFF,
                ) || opcode in 0x2D..0x3D || opcode in 0x44..0x6D || opcode in 0x90..0xAF ||
                    opcode in 0xD0..0xE2 -> 2
                else -> 1
            }
        }

        private fun annotationEnumFieldIndices(): Set<Int> {
            val mapOffset = u32(52).also {
                require(it in HEADER_SIZE.toLong()..bytes.size.toLong() - 4L) { "Invalid DEX map offset" }
            }.toInt()
            val mapSize = u32(mapOffset).also {
                require(it <= Int.MAX_VALUE) { "DEX map is too large" }
                require(mapOffset.toLong() + 4L + it * 12L <= bytes.size) { "DEX map exceeds file bounds" }
            }.toInt()
            var annotationCount = 0
            var annotationsOffset = 0
            repeat(mapSize) { index ->
                val itemOffset = mapOffset + 4 + index * 12
                if (u16(itemOffset) != TYPE_ANNOTATION_ITEM) return@repeat
                require(annotationCount == 0) { "DEX map contains duplicate annotation_item sections" }
                annotationCount = u32(itemOffset + 4).also {
                    require(it <= Int.MAX_VALUE) { "annotation_item count is too large" }
                }.toInt()
                annotationsOffset = u32(itemOffset + 8).also {
                    require(it < bytes.size.toLong()) { "Invalid annotation_item offset" }
                }.toInt()
            }
            if (annotationCount == 0) return emptySet()

            val enumFields = linkedSetOf<Int>()
            var cursor = annotationsOffset
            repeat(annotationCount) {
                require(cursor in bytes.indices) { "annotation_item exceeds DEX" }
                val visibility = bytes[cursor].toInt() and 0xFF
                require(visibility in 0..2) { "Invalid annotation visibility" }
                cursor = scanEncodedAnnotation(cursor + 1, enumFields, depth = 0)
            }
            return enumFields
        }

        private fun scanEncodedAnnotation(
            start: Int,
            enumFields: MutableSet<Int>,
            depth: Int,
        ): Int {
            require(depth <= MAX_ENCODED_VALUE_DEPTH) { "DEX encoded annotation nesting is too deep" }
            var cursor = start
            val type = readUleb128(cursor).also { cursor = it.next }
            require(type.value in types.indices) { "Invalid encoded annotation type" }
            val size = readUleb128(cursor).also { cursor = it.next }
            require(size.value >= 0 && size.value <= bytes.size) { "Invalid encoded annotation size" }
            repeat(size.value) {
                val name = readUleb128(cursor).also { cursor = it.next }
                require(name.value in strings.indices) { "Invalid encoded annotation name" }
                cursor = scanEncodedValue(cursor, enumFields, depth + 1)
            }
            return cursor
        }

        private fun scanEncodedValue(
            start: Int,
            enumFields: MutableSet<Int>,
            depth: Int,
        ): Int {
            require(depth <= MAX_ENCODED_VALUE_DEPTH) { "DEX encoded value nesting is too deep" }
            require(start in bytes.indices) { "encoded_value exceeds DEX" }
            val header = bytes[start].toInt() and 0xFF
            val valueType = header and 0x1F
            val valueArgument = header ushr 5
            var cursor = start + 1

            fun skipScalar(maxArgument: Int): Int {
                require(valueArgument <= maxArgument) { "Invalid encoded_value argument" }
                val byteCount = valueArgument + 1
                require(cursor <= bytes.size - byteCount) { "encoded_value scalar exceeds DEX" }
                return cursor + byteCount
            }

            return when (valueType) {
                0x00 -> skipScalar(0)
                0x02, 0x03 -> skipScalar(1)
                0x04, 0x10 -> skipScalar(3)
                0x06, 0x11 -> skipScalar(7)
                in 0x15..0x1A -> skipScalar(3)
                VALUE_ENUM -> {
                    require(valueArgument <= 3) { "Invalid enum field index width" }
                    val byteCount = valueArgument + 1
                    val fieldIndex = readEncodedUnsigned(cursor, byteCount)
                    require(fieldIndex in fields.indices) { "Invalid enum field index" }
                    enumFields += fieldIndex
                    cursor + byteCount
                }
                VALUE_ARRAY -> {
                    require(valueArgument == 0) { "Invalid encoded array argument" }
                    val size = readUleb128(cursor).also { cursor = it.next }
                    require(size.value >= 0 && size.value <= bytes.size) { "Invalid encoded array size" }
                    repeat(size.value) { cursor = scanEncodedValue(cursor, enumFields, depth + 1) }
                    cursor
                }
                VALUE_ANNOTATION -> {
                    require(valueArgument == 0) { "Invalid nested annotation argument" }
                    scanEncodedAnnotation(cursor, enumFields, depth + 1)
                }
                VALUE_NULL -> {
                    require(valueArgument == 0) { "Invalid encoded null argument" }
                    cursor
                }
                VALUE_BOOLEAN -> {
                    require(valueArgument <= 1) { "Invalid encoded boolean argument" }
                    cursor
                }
                else -> error("Unsupported encoded_value type: $valueType")
            }
        }

        private fun readEncodedUnsigned(start: Int, byteCount: Int): Int {
            require(byteCount in 1..4 && start in 0..bytes.size - byteCount) {
                "Encoded index exceeds DEX"
            }
            var value = 0L
            repeat(byteCount) { index ->
                value = value or ((bytes[start + index].toLong() and 0xFFL) shl (index * 8))
            }
            require(value <= Int.MAX_VALUE) { "Encoded index exceeds JVM limits" }
            return value.toInt()
        }

        private fun readStrings(): List<String> {
            val count = countAt(56)
            val offset = offsetAt(60, count)
            return List(count) { index ->
                val dataOffset = u32(offset + index * 4).toInt()
                readModifiedUtf8(dataOffset)
            }
        }

        private fun readTypes(strings: List<String>): List<String> {
            val count = countAt(64)
            val offset = offsetAt(68, count)
            return List(count) { index -> strings[intIndex(u32(offset + index * 4), strings.size, "type string")] }
        }

        private fun readProtos(types: List<String>): List<String> {
            val count = countAt(72)
            val offset = offsetAt(76, count)
            return List(count) { index ->
                val itemOffset = offset + index * 12
                val returnType = types[intIndex(u32(itemOffset + 4), types.size, "return type")]
                val parameters = readTypeList(u32(itemOffset + 8).toInt(), types)
                "(${parameters.joinToString("")})$returnType"
            }
        }

        private fun readFields(types: List<String>, strings: List<String>): List<FieldId> {
            val count = countAt(80)
            val offset = offsetAt(84, count)
            return List(count) { index ->
                val itemOffset = offset + index * 8
                FieldId(
                    owner = types[u16(itemOffset).also { require(it in types.indices) }],
                    descriptor = types[u16(itemOffset + 2).also { require(it in types.indices) }],
                    name = strings[intIndex(u32(itemOffset + 4), strings.size, "field name")],
                )
            }
        }

        private fun readMethods(
            types: List<String>,
            strings: List<String>,
            protos: List<String>,
        ): List<MethodId> {
            val count = countAt(88)
            val offset = offsetAt(92, count)
            return List(count) { index ->
                val itemOffset = offset + index * 8
                MethodId(
                    owner = types[u16(itemOffset).also { require(it in types.indices) }],
                    descriptor = protos[u16(itemOffset + 2).also { require(it in protos.indices) }],
                    name = strings[intIndex(u32(itemOffset + 4), strings.size, "method name")],
                )
            }
        }

        private fun readTypeList(offset: Int, types: List<String>): List<String> {
            if (offset == 0) return emptyList()
            require(offset in 0..bytes.size - 4) { "Invalid type_list offset" }
            val count = u32(offset).toInt()
            require(offset.toLong() + 4L + count.toLong() * 2L <= bytes.size) { "type_list exceeds DEX" }
            return List(count) { index ->
                types[u16(offset + 4 + index * 2).also { require(it in types.indices) }]
            }
        }

        private fun readModifiedUtf8(offset: Int): String {
            val length = readUleb128(offset)
            var end = length.next
            while (end < bytes.size && bytes[end] != 0.toByte()) end++
            require(end < bytes.size) { "Unterminated DEX string" }
            val byteLength = end - length.next
            val encoded = ByteArray(byteLength + 2)
            encoded[0] = (byteLength ushr 8).toByte()
            encoded[1] = byteLength.toByte()
            bytes.copyInto(encoded, 2, length.next, end)
            return DataInputStream(ByteArrayInputStream(encoded)).use(DataInputStream::readUTF).also {
                require(it.length == length.value) { "Inconsistent DEX string length" }
            }
        }

        private fun readUleb128(start: Int): Uleb128 {
            var value = 0
            var shift = 0
            var offset = start
            repeat(5) {
                require(offset in bytes.indices) { "ULEB128 exceeds DEX" }
                val current = bytes[offset++].toInt() and 0xFF
                value = value or ((current and 0x7F) shl shift)
                if (current and 0x80 == 0) return Uleb128(value, offset)
                shift += 7
            }
            error("ULEB128 is too long")
        }

        private fun countAt(offset: Int): Int = u32(offset).also {
            require(it <= Int.MAX_VALUE) { "DEX count exceeds JVM limits" }
        }.toInt()

        private fun offsetAt(offset: Int, count: Int): Int {
            val value = u32(offset)
            if (count == 0) {
                require(value == 0L) { "Empty DEX section has non-zero offset" }
                return 0
            }
            require(value in HEADER_SIZE.toLong() until bytes.size.toLong()) { "Invalid DEX section offset" }
            return value.toInt()
        }

        private fun intIndex(value: Long, size: Int, label: String): Int {
            require(value < size.toLong()) { "Invalid $label index" }
            return value.toInt()
        }

        private fun u16(offset: Int): Int {
            require(offset in 0..bytes.size - 2) { "u16 exceeds DEX" }
            return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        }

        private fun u32(offset: Int): Long {
            require(offset in 0..bytes.size - 4) { "u32 exceeds DEX" }
            return (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)
        }

        private fun className(descriptor: String): String {
            require(descriptor.startsWith('L') && descriptor.endsWith(';')) { "Not a class descriptor: $descriptor" }
            return descriptor.substring(1, descriptor.length - 1).replace('/', '.')
        }

        private fun addDispatch(
            destination: MutableMap<Int, MutableSet<AniyomiContractReference.Dispatch>>,
            index: Int,
            dispatch: AniyomiContractReference.Dispatch,
            limit: Int,
        ) {
            require(index in 0 until limit) { "Referenced member index exceeds DEX table" }
            destination.getOrPut(index, ::linkedSetOf) += dispatch
        }

        private fun IntArray.requireUnit(index: Int, label: String): Int {
            require(index in indices) { "$label exceeds code_item" }
            return this[index]
        }

        private fun signExtend(value: Int, bits: Int): Int {
            val shift = Int.SIZE_BITS - bits
            return value shl shift shr shift
        }
    }

    private data class FieldId(val owner: String, val name: String, val descriptor: String)
    private data class MethodId(val owner: String, val name: String, val descriptor: String)
    private data class CodeMethod(val methodIndex: Int, val codeOffset: Int)
    private data class Definitions(
        val classDescriptors: Set<String>,
        val fieldIndices: Set<Int>,
        val methodIndices: Set<Int>,
        val codeMethods: List<CodeMethod>,
    )
    private data class CodeScan(
        val methodDispatches: Map<Int, Set<AniyomiContractReference.Dispatch>>,
        val fieldDispatches: Map<Int, Set<AniyomiContractReference.Dispatch>>,
        val callSites: Set<AniyomiDexCallSite>,
    )
    private data class Uleb128(val value: Int, val next: Int)

    private const val HEADER_SIZE = 0x70
    private const val CLASS_DEF_SIZE = 32
    private const val CODE_ITEM_HEADER_SIZE = 16
    private const val TYPE_ANNOTATION_ITEM = 0x2004
    private const val VALUE_ENUM = 0x1B
    private const val VALUE_ARRAY = 0x1C
    private const val VALUE_ANNOTATION = 0x1D
    private const val VALUE_NULL = 0x1E
    private const val VALUE_BOOLEAN = 0x1F
    private const val MAX_ENCODED_VALUE_DEPTH = 64
    private const val REQUESTS_DESCRIPTOR = "Leu/kanade/tachiyomi/network/RequestsKt;"
    private const val VIDEO_DESCRIPTOR = "Leu/kanade/tachiyomi/animesource/model/Video;"
    private val DEX_MAGIC = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte())
    private val DEX_NAME = Regex("classes(\\d*)\\.dex")
}
