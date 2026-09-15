package uy.kohesive.injekt.api

abstract class InjektScopedMain(val scope: InjektScope) : InjektModule {
    init {
        with(this) { scope.registrar.registerInjectables() }
    }
}

interface InjektModule {
    fun registerWith(registrar: InjektRegistrar) {
        with(this) { registrar.registerInjectables() }
    }

    fun InjektRegistrar.registerInjectables()
}
