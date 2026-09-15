package uy.kohesive.injekt.api

interface InjektRegistrar : InjektRegistry, InjektFactory {
    fun importModule(module: InjektModule) {
        module.registerWith(this)
    }
}
