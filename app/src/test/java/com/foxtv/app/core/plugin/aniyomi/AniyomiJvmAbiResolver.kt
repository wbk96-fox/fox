package com.foxtv.app.core.plugin.aniyomi

import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import java.lang.reflect.Member
import java.lang.reflect.Modifier

/** Resolves only parent classpath symbols. The pinned extension namespace is never loaded. */
internal object AniyomiJvmAbiResolver {
    fun requireResolvable(reference: AniyomiContractReference) {
        when (reference.kind) {
            AniyomiContractReference.Kind.CLASS -> {
                require(!reference.owner.startsWith(EXTENSION_PREFIX)) {
                    "Extension classes must not be loaded during ABI resolution"
                }
                loadClass(reference.owner)
            }
            AniyomiContractReference.Kind.METHOD -> resolveMethod(reference)
            AniyomiContractReference.Kind.FIELD -> resolveField(reference)
        }
    }

    private fun resolveMethod(reference: AniyomiContractReference) {
        val owner = resolutionOwner(reference.owner)
        val signature = DescriptorParser.method(reference.descriptor)
        val member: Member = if (reference.name == "<init>") {
            owner.declaredConstructors.firstOrNull { constructor ->
                constructor.parameterTypes.contentEquals(signature.parameters)
            } ?: error("Missing constructor ${reference.owner}${reference.descriptor}")
        } else {
            hierarchy(owner)
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { method ->
                    method.name == reference.name &&
                        method.parameterTypes.contentEquals(signature.parameters) &&
                        method.returnType == signature.returnType
                } ?: error("Missing method ${reference.owner}.${reference.name}${reference.descriptor}")
        }
        val actualDispatch = when {
            reference.name == "<init>" -> AniyomiContractReference.Dispatch.CONSTRUCTOR
            Modifier.isStatic(member.modifiers) -> AniyomiContractReference.Dispatch.STATIC
            else -> AniyomiContractReference.Dispatch.INSTANCE
        }
        check(actualDispatch == reference.dispatch) {
            "Dispatch mismatch for ${reference.canonical()}: reflected $actualDispatch"
        }
    }

    private fun resolveField(reference: AniyomiContractReference) {
        val owner = resolutionOwner(reference.owner)
        val expectedType = DescriptorParser.type(reference.descriptor)
        val field = hierarchy(owner)
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { it.name == reference.name && it.type == expectedType }
            ?: error("Missing field ${reference.owner}.${reference.name}:${reference.descriptor}")
        val actualDispatch = if (Modifier.isStatic(field.modifiers)) {
            AniyomiContractReference.Dispatch.STATIC
        } else {
            AniyomiContractReference.Dispatch.INSTANCE
        }
        check(actualDispatch == reference.dispatch) {
            "Dispatch mismatch for ${reference.canonical()}: reflected $actualDispatch"
        }
    }

    private fun resolutionOwner(name: String): Class<*> = if (name.startsWith(EXTENSION_PREFIX)) {
        require(name == PINNED_JELLYFIN_CLASS) { "Unexpected extension owner in host ledger: $name" }
        AnimeHttpSource::class.java
    } else {
        loadClass(name)
    }

    private fun loadClass(name: String): Class<*> = try {
        Class.forName(name, false, AniyomiJvmAbiResolver::class.java.classLoader)
    } catch (error: ClassNotFoundException) {
        throw AssertionError("Missing class $name", error)
    }

    private fun hierarchy(root: Class<*>): Sequence<Class<*>> = sequence {
        val pending = ArrayDeque<Class<*>>()
        val visited = linkedSetOf<Class<*>>()
        pending += root
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            yield(current)
            current.superclass?.let(pending::addLast)
            current.interfaces.forEach(pending::addLast)
        }
    }

    private object DescriptorParser {
        fun method(descriptor: String): MethodSignature {
            require(descriptor.startsWith('(')) { "Not a method descriptor: $descriptor" }
            var offset = 1
            val parameters = mutableListOf<Class<*>>()
            while (descriptor[offset] != ')') {
                val parsed = parse(descriptor, offset)
                parameters += parsed.type
                offset = parsed.next
            }
            val result = parse(descriptor, offset + 1)
            require(result.next == descriptor.length) { "Trailing method descriptor data: $descriptor" }
            return MethodSignature(parameters.toTypedArray(), result.type)
        }

        fun type(descriptor: String): Class<*> {
            val parsed = parse(descriptor, 0)
            require(parsed.next == descriptor.length) { "Trailing type descriptor data: $descriptor" }
            return parsed.type
        }

        private fun parse(descriptor: String, start: Int): ParsedType {
            require(start in descriptor.indices) { "Truncated descriptor: $descriptor" }
            return when (descriptor[start]) {
                'V' -> ParsedType(Void.TYPE, start + 1)
                'Z' -> ParsedType(Boolean::class.javaPrimitiveType!!, start + 1)
                'B' -> ParsedType(Byte::class.javaPrimitiveType!!, start + 1)
                'C' -> ParsedType(Char::class.javaPrimitiveType!!, start + 1)
                'S' -> ParsedType(Short::class.javaPrimitiveType!!, start + 1)
                'I' -> ParsedType(Int::class.javaPrimitiveType!!, start + 1)
                'J' -> ParsedType(Long::class.javaPrimitiveType!!, start + 1)
                'F' -> ParsedType(Float::class.javaPrimitiveType!!, start + 1)
                'D' -> ParsedType(Double::class.javaPrimitiveType!!, start + 1)
                'L' -> {
                    val end = descriptor.indexOf(';', start)
                    require(end > start) { "Unterminated class descriptor: $descriptor" }
                    ParsedType(loadClass(descriptor.substring(start + 1, end).replace('/', '.')), end + 1)
                }
                '[' -> {
                    var end = start
                    while (descriptor[end] == '[') end++
                    if (descriptor[end] == 'L') {
                        end = descriptor.indexOf(';', end)
                        require(end > start) { "Unterminated array descriptor: $descriptor" }
                    }
                    val arrayDescriptor = descriptor.substring(start, end + 1).replace('/', '.')
                    ParsedType(loadClass(arrayDescriptor), end + 1)
                }
                else -> error("Unknown descriptor type in $descriptor at $start")
            }
        }

        private data class ParsedType(val type: Class<*>, val next: Int)
        data class MethodSignature(val parameters: Array<Class<*>>, val returnType: Class<*>)
    }

    private const val EXTENSION_PREFIX = "eu.kanade.tachiyomi.animeextension."
    private const val PINNED_JELLYFIN_CLASS = "eu.kanade.tachiyomi.animeextension.all.jellyfin.Jellyfin"
}
