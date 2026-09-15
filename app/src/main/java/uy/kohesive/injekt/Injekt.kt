@file:Suppress("NOTHING_TO_INLINE")

package uy.kohesive.injekt

import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.InjektScopedMain
import uy.kohesive.injekt.api.fullType
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.api.logger
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import kotlin.reflect.KClass

/** Global scope ABI adapted from exact Injekt commit 65b04400 under MIT. */
@Volatile
var Injekt: InjektScope = InjektScope(DefaultRegistrar())

abstract class InjektMain : InjektScopedMain(Injekt)

inline fun <reified T : Any> injectLazy(): Lazy<T> = lazy { Injekt.get(fullType<T>()) }
inline fun <reified T : Any> injectValue(): Lazy<T> = lazyOf(Injekt.get(fullType<T>()))
inline fun <reified T : Any> injectLazy(key: Any): Lazy<T> = lazy { Injekt.get(fullType<T>(), key) }
inline fun <reified T : Any> injectValue(key: Any): Lazy<T> = lazyOf(Injekt.get(fullType<T>(), key))
inline fun <reified R : Any, reified T : Any> R.injectLogger(): Lazy<T> =
    lazy { Injekt.logger(fullType<T>(), R::class.java) }
inline fun <reified T : Any, O : Any> injectLogger(forClass: KClass<O>): Lazy<T> =
    lazy { Injekt.logger(fullType<T>(), forClass.java) }
inline fun <reified T : Any, O : Any> injectLogger(forClass: Class<O>): Lazy<T> =
    lazy { Injekt.logger(fullType<T>(), forClass) }
inline fun <reified T : Any> injectLogger(byName: String): Lazy<T> =
    lazy { Injekt.logger(fullType<T>(), byName) }
