package moviecatalogue.catalogue.common

/**
 * Collects field errors and normalises text. Lengths are counted in Unicode code points, matching
 * PostgreSQL varchar(n), so an emoji counts as one character in both places.
 */
class Validator {
    private val errors = mutableListOf<FieldError>()

    fun requiredText(
        field: String,
        value: String,
        maxLength: Int,
    ): String {
        val trimmed = value.trim()
        when {
            trimmed.isEmpty() -> reject(field, "is required")
            trimmed.lengthInCodePoints() > maxLength -> reject(field, "must be at most $maxLength characters")
        }
        return trimmed
    }

    /** Blank becomes null, so "no synopsis" has a single representation. */
    fun optionalText(
        field: String,
        value: String?,
        maxLength: Int,
    ): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.lengthInCodePoints() > maxLength) reject(field, "must be at most $maxLength characters")
        return trimmed
    }

    fun check(
        condition: Boolean,
        field: String,
        message: String,
    ) {
        if (!condition) reject(field, message)
    }

    fun reject(
        field: String,
        message: String,
    ) {
        errors += FieldError(field, message)
    }

    fun throwIfInvalid() {
        if (errors.isNotEmpty()) throw ValidationException(errors.toList())
    }

    private fun String.lengthInCodePoints() = codePointCount(0, length)
}
