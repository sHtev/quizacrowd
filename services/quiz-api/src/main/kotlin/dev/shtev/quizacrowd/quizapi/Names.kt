package dev.shtev.quizacrowd.quizapi

fun cleanDisplayName(raw: String): String? {
    val name = raw.trim()
    if (
        name.isEmpty() ||
        name.codePointCount(0, name.length) > 24 ||
        name.any { it.isISOControl() }
    ) return null
    return name
}
