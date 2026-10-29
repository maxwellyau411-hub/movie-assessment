package moviecatalogue.catalogue.common

/**
 * Outcome of a create/update that may first need the user to confirm a possible duplicate.
 * A warning means nothing was written.
 */
sealed interface SaveResult<out T, out C> {
    data class Saved<T>(
        val value: T,
    ) : SaveResult<T, Nothing>

    data class DuplicateWarning<C>(
        val candidates: List<C>,
    ) : SaveResult<Nothing, C>
}
