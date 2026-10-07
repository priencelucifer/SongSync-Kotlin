package com.songsync.app.data.source

/** Decodes the HTML entities JioSaavn leaves in titles (e.g. `&quot;`, `&amp;`, `&#039;`). */
object HtmlEntities {
    private val named = mapOf(
        "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to " ",
    )
    private val pattern = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});")

    fun decode(text: String): String {
        if ('&' !in text) return text
        return pattern.replace(text) { match ->
            val entity = match.groupValues[1]
            val codePoint = when {
                entity.startsWith("#x") || entity.startsWith("#X") -> entity.substring(2).toIntOrNull(16)
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()
                else -> null
            }
            when {
                codePoint != null && Character.isValidCodePoint(codePoint) -> String(Character.toChars(codePoint))
                codePoint == null -> named[entity] ?: match.value
                else -> match.value
            }
        }
    }
}
