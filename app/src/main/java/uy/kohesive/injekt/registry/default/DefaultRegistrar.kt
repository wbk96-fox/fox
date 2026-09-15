package uy.kohesive.injekt.registry.default

import uy.kohesive.injekt.api.InjektionException
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.TypeReference
import uy.kohesive.injekt.api.erasedType
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

/**
 * Concurrent registry adapted from Injekt commit 65b04400. The singleton slot stores a
 * synchronized Lazy, preserving the commit's one-publication fix under concurrent reads.
 */
open class DefaultRegistrar : InjektRegistrar {
    private object NoKey
    private data class InstanceKey(val type: Type, val key: Any)
    private data class LoggerFactory(
        val type: Type,
        val byName: (String) -> Any,
        val byClass: (Class<Any>) -> Any,
    )

    private val retainedValues = ConcurrentHashMap<InstanceKey, Any>()
    private val threadValues = object : ThreadLocal<MutableMap<InstanceKey, Any>>() {
        override fun initialValue(): MutableMap<InstanceKey, Any> = hashMapOf()
    }
    private val factories = ConcurrentHashMap<Type, () -> Any>()
    private val keyedFactories = ConcurrentHashMap<Type, (Any) -> Any>()

    @Volatile
    private var loggerFactory: LoggerFactory? = null

    override fun <T : Any> addSingleton(forType: TypeReference<T>, singleInstance: T) {
        addSingletonFactory(forType) { singleInstance }
        getInstance<T>(forType.type)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> addSingletonFactory(forType: TypeReference<R>, factoryCalledOnce: () -> R) {
        factories[forType.type] = {
            val key = InstanceKey(forType.type, NoKey)
            val candidate = lazy(LazyThreadSafetyMode.SYNCHRONIZED, factoryCalledOnce)
            val retained = retainedValues.putIfAbsent(key, candidate) ?: candidate
            (retained as Lazy<R>).value
        }
    }

    override fun <R : Any> addFactory(forType: TypeReference<R>, factoryCalledEveryTime: () -> R) {
        factories[forType.type] = factoryCalledEveryTime
    }

    override fun <R : Any> addPerThreadFactory(
        forType: TypeReference<R>,
        factoryCalledOncePerThread: () -> R,
    ) {
        factories[forType.type] = {
            val key = InstanceKey(forType.type, NoKey)
            threadValues.get().getOrPut(key, factoryCalledOncePerThread)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> addPerKeyFactory(
        forType: TypeReference<R>,
        factoryCalledPerKey: (K) -> R,
    ) {
        keyedFactories[forType.type] = { rawKey ->
            val key = InstanceKey(forType.type, rawKey)
            retainedValues[key] ?: run {
                val candidate = factoryCalledPerKey(rawKey as K)
                retainedValues.putIfAbsent(key, candidate) ?: candidate
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> addPerThreadPerKeyFactory(
        forType: TypeReference<R>,
        factoryCalledPerKeyPerThread: (K) -> R,
    ) {
        keyedFactories[forType.type] = { rawKey ->
            threadValues.get().getOrPut(InstanceKey(forType.type, rawKey)) {
                factoryCalledPerKeyPerThread(rawKey as K)
            }
        }
    }

    override fun <R : Any> addLoggerFactory(
        forLoggerType: TypeReference<R>,
        factoryByName: (String) -> R,
        factoryByClass: (Class<Any>) -> R,
    ) {
        loggerFactory = LoggerFactory(forLoggerType.type, factoryByName, factoryByClass)
    }

    override fun <O : Any, T : O> addAlias(
        existingRegisteredType: TypeReference<T>,
        otherAncestorOrInterface: TypeReference<O>,
    ) {
        factories[existingRegisteredType.type]?.let { factories[otherAncestorOrInterface.type] = it }
        keyedFactories[existingRegisteredType.type]?.let { keyedFactories[otherAncestorOrInterface.type] = it }
    }

    override fun <T : Any> hasFactory(forType: TypeReference<T>): Boolean =
        factories.containsKey(forType.type) || keyedFactories.containsKey(forType.type)

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstance(forType: Type): R =
        (factories[forType] ?: throw InjektionException("No registered instance or factory for type $forType"))() as R

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstanceOrElse(forType: Type, default: R): R =
        factories[forType]?.invoke() as? R ?: default

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstanceOrElse(forType: Type, default: () -> R): R =
        factories[forType]?.invoke() as? R ?: default()

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstanceOrNull(forType: Type): R? = factories[forType]?.invoke() as? R

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> getKeyedInstance(forType: Type, key: K): R =
        (keyedFactories[forType]
            ?: throw InjektionException("No registered keyed factory for type $forType"))(key) as R

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> getKeyedInstanceOrElse(forType: Type, key: K, default: R): R =
        keyedFactories[forType]?.invoke(key) as? R ?: default

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> getKeyedInstanceOrElse(
        forType: Type,
        key: K,
        default: () -> R,
    ): R = keyedFactories[forType]?.invoke(key) as? R ?: default()

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> getKeyedInstanceOrNull(forType: Type, key: K): R? =
        keyedFactories[forType]?.invoke(key) as? R

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getLogger(expectedLoggerType: Type, byName: String): R {
        val factory = checkedLoggerFactory(expectedLoggerType)
        return factory.byName(byName) as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, T : Any> getLogger(expectedLoggerType: Type, forClass: Class<T>): R {
        val factory = checkedLoggerFactory(expectedLoggerType)
        return factory.byClass(forClass as Class<Any>) as R
    }

    override fun importModule(module: InjektModule) {
        module.registerWith(this)
    }

    private fun checkedLoggerFactory(expectedType: Type): LoggerFactory {
        val factory = loggerFactory
            ?: throw InjektionException("No logger factory has been registered")
        if (!factory.type.erasedType().isAssignableFrom(expectedType.erasedType())) {
            throw InjektionException(
                "Registered logger type ${factory.type} is incompatible with requested type $expectedType",
            )
        }
        return factory
    }
}
