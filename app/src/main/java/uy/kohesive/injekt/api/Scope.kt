@file:Suppress("NOTHING_TO_INLINE")

package uy.kohesive.injekt.api

import kotlin.reflect.KClass

open class InjektScope(val registrar: InjektRegistrar) : InjektRegistrar by registrar {
    inline fun <reified T : Any> injectLazy(): Lazy<T> = lazy { get(fullType<T>()) }
    inline fun <reified T : Any> injectValue(): Lazy<T> = lazyOf(get(fullType<T>()))
    inline fun <reified T : Any> injectLazy(key: Any): Lazy<T> = lazy { get(fullType<T>(), key) }
    inline fun <reified T : Any> injectValue(key: Any): Lazy<T> = lazyOf(get(fullType<T>(), key))
    inline fun <reified T : Any, O : Any> injectLogger(forClass: Class<O>): Lazy<T> =
        lazy { logger(fullType<T>(), forClass) }
    inline fun <reified T : Any, O : Any> injectLogger(forClass: KClass<O>): Lazy<T> =
        lazy { logger(fullType<T>(), forClass.java) }
    inline fun <reified T : Any> injectLogger(byName: String): Lazy<T> =
        lazy { logger(fullType<T>(), byName) }

    inline fun <reified R : Any> addScopedSingletonFactory(
        noinline factory: InjektScope.() -> R,
    ) = addSingletonFactory(fullType<R>()) { factory() }
    inline fun <reified R : Any> addScopedFactory(
        noinline factory: InjektScope.() -> R,
    ) = addFactory(fullType<R>()) { factory() }
    inline fun <reified R : Any> addScopedPerThreadFactory(
        noinline factory: InjektScope.() -> R,
    ) = addPerThreadFactory(fullType<R>()) { factory() }
    inline fun <reified R : Any, K : Any> addScopedPerKeyFactory(
        noinline factory: InjektScope.(K) -> R,
    ) = addPerKeyFactory(fullType<R>()) { key: K -> factory(key) }
    inline fun <reified R : Any, K : Any> addScopedPerThreadPerKeyFactory(
        noinline factory: InjektScope.(K) -> R,
    ) = addPerThreadPerKeyFactory(fullType<R>()) { key: K -> factory(key) }
}

abstract class LocalScoped(protected val localScope: InjektScope) {
    @PublishedApi
    internal fun localScopeForInlineAccess(): InjektScope = localScope

    inline fun <reified T : Any> injectLazy(): Lazy<T> = localScopeForInlineAccess().injectLazy()
    inline fun <reified T : Any> injectValue(): Lazy<T> = localScopeForInlineAccess().injectValue()
    inline fun <reified T : Any> injectLazy(key: Any): Lazy<T> = localScopeForInlineAccess().injectLazy(key)
    inline fun <reified T : Any> injectValue(key: Any): Lazy<T> = localScopeForInlineAccess().injectValue(key)
}
