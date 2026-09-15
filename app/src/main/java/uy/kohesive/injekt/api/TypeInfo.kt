package uy.kohesive.injekt.api

import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

/** Adapted from Injekt commit 65b04400 under its distributed MIT license. */
@Suppress("UNCHECKED_CAST")
fun Type.erasedType(): Class<Any> = when (this) {
    is Class<*> -> this as Class<Any>
    is ParameterizedType -> rawType.erasedType()
    is GenericArrayType -> java.lang.reflect.Array.newInstance(genericComponentType.erasedType(), 0).javaClass
    is WildcardType -> upperBounds.first().erasedType()
    is TypeVariable<*> -> error("A type variable cannot be erased without a concrete binding")
    else -> error("Unsupported reflective type: $this")
}

interface TypeReference<T> {
    val type: Type
}

abstract class FullTypeReference<T> protected constructor() : TypeReference<T> {
    final override val type: Type = when (val genericParent = javaClass.genericSuperclass) {
        is ParameterizedType -> genericParent.actualTypeArguments.first()
        else -> throw IllegalArgumentException("FullTypeReference requires concrete generic type information")
    }
}

inline fun <reified T : Any> typeRef(): FullTypeReference<T> = object : FullTypeReference<T>() {}
inline fun <reified T : Any> fullType(): FullTypeReference<T> = object : FullTypeReference<T>() {}
