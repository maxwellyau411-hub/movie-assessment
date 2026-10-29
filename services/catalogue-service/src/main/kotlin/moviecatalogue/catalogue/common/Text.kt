package moviecatalogue.catalogue.common

import java.util.Locale

object Text {
    private val WHITESPACE = Regex("\\s+")

    /**
     * Duplicate-matching key: trimmed, internal whitespace collapsed, case-insensitive. Stored and
     * displayed values keep their spelling; this is only for comparison. The SQL side uses the same rule.
     */
    fun matchKey(value: String): String = value.trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT)

    /** Trimmed search text, or null when blank (blank means "no search"). */
    fun searchTerm(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    /** Escapes LIKE wildcards so `%` and `_` typed by a user match literally. Use with escape char `\`. */
    fun likeContains(term: String): String {
        val escaped =
            term
                .lowercase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
        return "%$escaped%"
    }

    const val LIKE_ESCAPE = '\\'
}
