package moviecatalogue.catalogue.common

import java.util.UUID

data class FieldError(
    val field: String,
    val message: String,
)

/** Input breaks a model rule. Carries every field problem so the UI can show them together. */
class ValidationException(
    val errors: List<FieldError>,
) : RuntimeException(errors.joinToString("; ") { "${it.field}: ${it.message}" })

class NotFoundException(
    val entity: String,
    val id: Any,
) : RuntimeException("$entity $id was not found")

enum class ConflictReason {
    /** The caller edited an older version; reload and retry. */
    STALE_VERSION,

    /** The exact association already exists. */
    DUPLICATE_ASSOCIATION,

    /** A credit needs the person to be in the matching category first. */
    CATEGORY_REQUIRED,

    /** A category cannot be removed while credits still depend on it. */
    CATEGORY_IN_USE,

    /** A person cannot be deleted while any cast or creator credit still names them. */
    PERSON_HAS_CREDITS,
}

/** [details] are extra facts the UI can show, such as the movies that still credit a person. */
class ConflictException(
    val reason: ConflictReason,
    message: String,
    val details: Map<String, Any> = emptyMap(),
) : RuntimeException(message)

fun staleVersion(
    entity: String,
    id: UUID,
) = ConflictException(ConflictReason.STALE_VERSION, "$entity $id was changed by someone else; reload and try again")

/** Another service this request needs did not answer in time. Nothing was changed; trying again may work. */
class ServiceUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
