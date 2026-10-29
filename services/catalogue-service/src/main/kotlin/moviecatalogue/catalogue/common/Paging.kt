package moviecatalogue.catalogue.common

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Base64

/**
 * Keyset ("cursor") paging arguments: [first] rows after the [after] cursor. There are no page
 * numbers, so a deep page costs the same as the first one: the database seeks straight to the
 * cursor position in an index instead of counting and skipping earlier rows.
 */
data class PageArgs(
    val first: Int,
    val after: String?,
) {
    companion object {
        const val DEFAULT_FIRST = 20
        const val MAX_FIRST = 50

        fun of(
            first: Int?,
            after: String?,
        ): PageArgs {
            val size = first ?: DEFAULT_FIRST
            val v = Validator()
            v.check(size in 1..MAX_FIRST, "first", "must be between 1 and $MAX_FIRST")
            v.throwIfInvalid()
            return PageArgs(size, after?.takeIf { it.isNotEmpty() })
        }
    }
}

data class Edge<T>(
    val cursor: String,
    val node: T,
)

/** [endCursor] is null only when the page is empty. */
data class PageInfo(
    val hasNextPage: Boolean,
    val endCursor: String?,
)

/**
 * One page of a list. [criteria] is the validated filter the page was read with; the totalCount
 * field resolver uses it to count only when a client asks for the total.
 */
data class Connection<T, C>(
    val edges: List<Edge<T>>,
    val pageInfo: PageInfo,
    val criteria: C,
) {
    val nodes: List<T> get() = edges.map { it.node }

    companion object {
        /**
         * [rows] were read with LIMIT first + 1. The extra row only tells us a next page exists; it
         * is not returned. [key] gives the sort key values a row's cursor must remember.
         */
        fun <R, T, C> of(
            rows: List<R>,
            args: PageArgs,
            scope: List<Any?>,
            criteria: C,
            key: (R) -> List<String?>,
            view: (R) -> T,
        ): Connection<T, C> {
            val page = rows.take(args.first)
            val edges = page.map { Edge(Cursor.encode(scope, key(it)), view(it)) }
            return Connection(edges, PageInfo(rows.size > args.first, edges.lastOrNull()?.cursor), criteria)
        }
    }
}

/** Counting stops at [CAP]: counting every match of a broad search would cost a full scan. */
data class TotalCount(
    val count: Int,
    val capped: Boolean,
) {
    companion object {
        const val CAP = 10_000

        /** [counted] comes from a count query limited to CAP + 1 rows. */
        fun of(counted: Long) = if (counted > CAP) TotalCount(CAP, capped = true) else TotalCount(counted.toInt(), capped = false)
    }
}

/**
 * A cursor is the sort key of a row plus the "scope" it was made for (which list, sort and
 * filters), as base64url-encoded JSON: `[scope, key]`. Clients treat it as an opaque token.
 * Reusing a cursor with another sort or filter would silently skip or repeat rows, so it is
 * rejected instead. The cursor is not signed: a hand-edited one can only move the start position.
 */
object Cursor {
    private val json = JsonMapper.builder().build()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun encode(
        scope: List<Any?>,
        key: List<String?>,
    ): String = encoder.encodeToString(json.writeValueAsBytes(listOf(scope, key)))

    /** Returns the sort key values stored in [cursor], or throws VALIDATION_ERROR on field `after`. */
    fun decode(
        cursor: String,
        scope: List<Any?>,
        keySize: Int,
    ): List<String?> {
        val node = parse(cursor)
        val key = node?.get(1)
        if (node == null || !node.isArray || node.size() != 2 || key == null || !key.isArray) invalid("is not a valid cursor")
        if (node[0] != json.valueToTree<JsonNode>(scope)) {
            invalid("belongs to a different list, sort or filter; start again from the first page")
        }
        if (key.size() != keySize || key.values().any { !it.isString && !it.isNull }) invalid("is not a valid cursor")
        return key.values().map { if (it.isNull) null else it.asString() }
    }

    /** Converts one stored key value, treating a value that does not parse as an invalid cursor. */
    fun <T> keyValue(parse: () -> T): T =
        try {
            parse()
        } catch (_: RuntimeException) {
            invalid("is not a valid cursor")
        }

    private fun parse(cursor: String): JsonNode? =
        try {
            json.readTree(Base64.getUrlDecoder().decode(cursor))
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: JacksonException) {
            null
        }

    private fun invalid(message: String): Nothing = throw ValidationException(listOf(FieldError("after", message)))
}
