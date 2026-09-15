package eu.kanade.tachiyomi.animesource.model

class AnimeFilterList(val list: List<AnimeFilter<*>>) : List<AnimeFilter<*>> by list {
    constructor(vararg filters: AnimeFilter<*>) : this(filters.asList())

    override fun equals(other: Any?): Boolean = other is AnimeFilterList && list == other.list
    override fun hashCode(): Int = list.hashCode()
}
