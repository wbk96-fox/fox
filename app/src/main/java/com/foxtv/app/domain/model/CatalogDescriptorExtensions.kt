package com.foxtv.app.domain.model

private const val DEFAULT_SKIP_STEP = 100

fun CatalogDescriptor.skipStep(defaultStep: Int = DEFAULT_SKIP_STEP): Int {
    if (pageSize != null && pageSize > 0) return pageSize
    // Prefer step inferred from skip extra options when present (e.g. ["0","50","100"]).
    val skipExtra = extra.firstOrNull { it.name.equals("skip", ignoreCase = true) }
    val numericOptions = skipExtra?.options
        .orEmpty()
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it >= 0 }
        .distinct()
        .sorted()
    if (numericOptions.size >= 2) {
        val step = numericOptions
            .zipWithNext()
            .mapNotNull { (a, b) -> (b - a).takeIf { it > 0 } }
            .minOrNull()
        if (step != null && step > 0) return step
    }
    return defaultStep
}
