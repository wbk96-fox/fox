package com.foxtv.app.core.plugin.aniyomi

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest

internal object AniyomiResearchContracts {
    const val ARTIFACT_SHA256 = "54e76891daf987e53f671fd86a4ce2a409666e38b5b81b30dee2973f7520085e"
    const val ARTIFACT_SIZE_BYTES = 418_755L

    val globalInjektLock = Any()

    val directory: File by lazy {
        val workingDirectory = requireNotNull(System.getProperty("user.dir")) {
            "JVM test working directory is unavailable"
        }
        val relative = ".kiro/research/aniyomi/f8150feba27664976e77cdb9fe021bf80ffab782"
        generateSequence(File(workingDirectory).canonicalFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull(File::isDirectory)
            ?.canonicalFile
            ?: error("Pinned Aniyomi research directory is missing")
    }

    val apk: File by lazy { requiredFile("aniyomi-all.jellyfin-v14.17.apk") }

    fun referenceLedger(name: String): ReferenceLedger {
        val root = json(name)
        require(root.requiredInt("schemaVersion") == 1) { "$name has an unsupported schema" }
        require(root.requiredBoolean("immutable")) { "$name is not immutable" }
        require(root.requiredString("artifactSha256") == ARTIFACT_SHA256) { "$name has the wrong artifact hash" }
        require(root.requiredLong("artifactSizeBytes") == ARTIFACT_SIZE_BYTES) { "$name has the wrong artifact size" }
        require(root.requiredString("recordFormat") == "kind|owner|name|jvmDescriptor|dispatch") {
            "$name has an unsupported record format"
        }
        val prefixes = root.requiredArray("scopePrefixes").map { it.asString }
        val countObject = root.requiredObject("expectedCounts")
        val expectedCounts = mapOf(
            AniyomiContractReference.Kind.CLASS to countObject.requiredInt("class"),
            AniyomiContractReference.Kind.METHOD to countObject.requiredInt("method"),
            AniyomiContractReference.Kind.FIELD to countObject.requiredInt("field"),
        )
        val records = root.requiredArray("records").map { AniyomiContractReference.parse(it.asString) }
        require(records.size == records.toSet().size) { "$name contains duplicate records" }
        require(records.size == countObject.requiredInt("total")) { "$name total count is inconsistent" }
        expectedCounts.forEach { (kind, expected) ->
            require(records.count { it.kind == kind } == expected) { "$name $kind count is inconsistent" }
        }
        return ReferenceLedger(prefixes, expectedCounts, records.toSet())
    }

    fun callSiteLedger(): CallSiteLedger {
        val name = "CALL_SITE_MASKS.json"
        val root = json(name)
        require(root.requiredInt("schemaVersion") == 1) { "$name has an unsupported schema" }
        require(root.requiredBoolean("immutable")) { "$name is not immutable" }
        require(root.requiredString("artifactSha256") == ARTIFACT_SHA256) { "$name has the wrong artifact hash" }
        require(root.requiredLong("artifactSizeBytes") == ARTIFACT_SIZE_BYTES) { "$name has the wrong artifact size" }
        require(root.requiredString("offsetUnit") == "DEX code unit") { "$name has the wrong offset unit" }
        val records = root.requiredArray("records").map { element ->
            val record = element.asJsonObject
            AniyomiDexCallSite(
                callerOwner = record.requiredString("callerOwner"),
                callerName = record.requiredString("callerName"),
                callerDescriptor = record.requiredString("callerDescriptor"),
                codeUnitOffset = record.requiredInt("codeUnitOffset"),
                calleeOwner = record.requiredString("calleeOwner"),
                calleeName = record.requiredString("calleeName"),
                calleeDescriptor = record.requiredString("calleeDescriptor"),
                mask = record.requiredInt("mask"),
            )
        }
        require(records.size == records.toSet().size) { "$name contains duplicate records" }
        require(records.size == root.requiredInt("expectedCount")) { "$name count is inconsistent" }
        return CallSiteLedger(records.toSet())
    }

    fun verifyPinnedArtifact() {
        check(apk.isFile) { "Pinned Aniyomi APK fixture is missing" }
        check(apk.length() == ARTIFACT_SIZE_BYTES) { "Pinned Aniyomi APK size changed" }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == ARTIFACT_SHA256) { "Pinned Aniyomi APK hash changed: $actual" }
    }

    private fun json(name: String): JsonObject = JsonParser.parseString(requiredFile(name).readText()).asJsonObject

    private fun requiredFile(name: String): File = File(directory, name).canonicalFile.also {
        require(it.isFile && it.parentFile == directory) { "Research artifact is missing: $name" }
    }

    data class ReferenceLedger(
        val scopePrefixes: List<String>,
        val expectedCounts: Map<AniyomiContractReference.Kind, Int>,
        val records: Set<AniyomiContractReference>,
    )

    data class CallSiteLedger(val records: Set<AniyomiDexCallSite>)

    private fun JsonObject.requiredArray(name: String) = requireNotNull(getAsJsonArray(name)) { "Missing $name" }
    private fun JsonObject.requiredObject(name: String) = requireNotNull(getAsJsonObject(name)) { "Missing $name" }
    private fun JsonObject.requiredString(name: String): String = requireNotNull(get(name)) { "Missing $name" }.asString
    private fun JsonObject.requiredBoolean(name: String): Boolean = requireNotNull(get(name)) { "Missing $name" }.asBoolean
    private fun JsonObject.requiredInt(name: String): Int = requireNotNull(get(name)) { "Missing $name" }.asInt
    private fun JsonObject.requiredLong(name: String): Long = requireNotNull(get(name)) { "Missing $name" }.asLong
}
