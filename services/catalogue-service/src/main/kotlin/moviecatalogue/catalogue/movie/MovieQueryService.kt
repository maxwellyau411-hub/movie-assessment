package moviecatalogue.catalogue.movie

import jakarta.persistence.EntityManager
import moviecatalogue.catalogue.common.Connection
import moviecatalogue.catalogue.common.Cursor
import moviecatalogue.catalogue.common.PageArgs
import moviecatalogue.catalogue.common.Text
import moviecatalogue.catalogue.common.TotalCount
import moviecatalogue.catalogue.common.Validator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * Title sorts are case-insensitive. Date sorts put unknown dates last in both directions. Every
 * sort ends with the id, so rows with equal keys still have one fixed order; descending sorts
 * order the id descending too, because they read the ascending index backwards.
 */
enum class MovieSort { TITLE_ASC, TITLE_DESC, RELEASE_DATE_NEWEST, RELEASE_DATE_OLDEST }

/** All present criteria are combined with AND. Several genres mean "any of these genres". */
data class MovieFilter(
    val search: String? = null,
    val genreCodes: List<String>? = null,
    val releaseYear: Int? = null,
)

/** A validated [MovieFilter]: search trimmed (null when blank), genres de-duplicated and sorted. */
data class MovieCriteria(
    val search: String?,
    val genreCodes: List<String>,
    val releaseYear: Int?,
)

/**
 * Search, filter, sort and keyset paging in one SQL query. The SQL is written by hand so that
 * each part matches an index from V3__listing_indexes.sql; change both together.
 */
@Service
@Transactional(readOnly = true)
class MovieQueryService(
    private val entityManager: EntityManager,
) {
    fun list(
        filter: MovieFilter?,
        sort: MovieSort,
        page: PageArgs,
    ): Connection<MovieView, MovieCriteria> {
        val criteria = validate(filter ?: MovieFilter())
        val scope = scope(sort, criteria)
        val where = Where(criteria)
        page.after?.let { after(sort, Cursor.decode(it, scope, keySize = 2), where) }

        val sql = "select m.* from movie m ${where.sql()} order by ${orderBy(sort)} limit :limit"
        val query = entityManager.createNativeQuery(sql, Movie::class.java)
        where.params.forEach(query::setParameter)
        query.setParameter("limit", page.first + 1)
        @Suppress("UNCHECKED_CAST")
        val rows = query.resultList as List<Movie>
        return Connection.of(rows, page, scope, criteria, key = { key(sort, it) }, view = { it.toView() })
    }

    /** Matching movies, counting at most [TotalCount.CAP] + 1 so a broad filter cannot cost a full count. */
    fun count(criteria: MovieCriteria): TotalCount {
        val where = Where(criteria)
        val sql = "select count(*) from (select 1 from movie m ${where.sql()} limit :limit) matches"
        val query = entityManager.createNativeQuery(sql)
        where.params.forEach(query::setParameter)
        query.setParameter("limit", TotalCount.CAP + 1)
        return TotalCount.of((query.singleResult as Number).toLong())
    }

    /** Collects SQL conditions with their named parameters. Only fixed SQL text is concatenated. */
    private class Where(
        criteria: MovieCriteria,
    ) {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()

        init {
            criteria.search?.let {
                // Served by the trigram index. Backslash is PostgreSQL's default LIKE escape character.
                conditions += "lower(m.title) like :search"
                params["search"] = Text.likeContains(it)
            }
            if (criteria.genreCodes.isNotEmpty()) {
                // EXISTS rather than a join: a movie matching two selected genres is still listed once.
                conditions += "exists (select 1 from movie_genre g where g.movie_id = m.id and g.genre_code in (:genres))"
                params["genres"] = criteria.genreCodes
            }
            criteria.releaseYear?.let { year ->
                // Written on the oldest-first index expression so that index serves the year range.
                // An unknown date becomes 'infinity', so it never matches a year.
                conditions += "$OLDEST_KEY between :yearStart and :yearEnd"
                params["yearStart"] = LocalDate.of(year, 1, 1)
                params["yearEnd"] = LocalDate.of(year, 12, 31)
            }
        }

        fun sql() = if (conditions.isEmpty()) "" else "where " + conditions.joinToString(" and ")
    }

    /** Rows strictly after the cursor row in sort order. A row comparison matches the index column order. */
    private fun after(
        sort: MovieSort,
        key: List<String?>,
        where: Where,
    ) {
        val id = Cursor.keyValue { UUID.fromString(key[1]) }
        where.params["afterId"] = id
        when (sort) {
            MovieSort.TITLE_ASC, MovieSort.TITLE_DESC -> {
                val title = Cursor.keyValue { checkNotNull(key[0]) }
                val op = if (sort == MovieSort.TITLE_ASC) ">" else "<"
                where.conditions += "(lower(m.title), m.id) $op (lower(:afterTitle), :afterId)"
                where.params["afterTitle"] = title
            }

            MovieSort.RELEASE_DATE_OLDEST, MovieSort.RELEASE_DATE_NEWEST -> {
                val date = key[0]?.let { Cursor.keyValue { LocalDate.parse(it) } }
                val (expression, unknown, op) =
                    if (sort == MovieSort.RELEASE_DATE_OLDEST) Triple(OLDEST_KEY, "infinity", ">") else Triple(NEWEST_KEY, "-infinity", "<")
                where.conditions += "($expression, m.id) $op (cast(:afterDate as date), :afterId)"
                // An unknown date in the cursor compares as the same infinity the index uses.
                where.params["afterDate"] = date?.toString() ?: unknown
            }
        }
    }

    private fun orderBy(sort: MovieSort): String =
        when (sort) {
            MovieSort.TITLE_ASC -> "lower(m.title), m.id"
            MovieSort.TITLE_DESC -> "lower(m.title) desc, m.id desc"
            MovieSort.RELEASE_DATE_OLDEST -> "$OLDEST_KEY, m.id"
            MovieSort.RELEASE_DATE_NEWEST -> "$NEWEST_KEY desc, m.id desc"
        }

    /** What a cursor must remember: the title or date of the row (not lower-cased; SQL does that), and its id. */
    private fun key(
        sort: MovieSort,
        movie: Movie,
    ): List<String?> =
        when (sort) {
            MovieSort.TITLE_ASC, MovieSort.TITLE_DESC -> listOf(movie.title, movie.id.toString())
            MovieSort.RELEASE_DATE_OLDEST, MovieSort.RELEASE_DATE_NEWEST -> listOf(movie.releaseDate?.toString(), movie.id.toString())
        }

    private fun scope(
        sort: MovieSort,
        criteria: MovieCriteria,
    ): List<Any?> = listOf("movies", sort.name, criteria.search?.lowercase(), criteria.genreCodes, criteria.releaseYear)

    private fun validate(filter: MovieFilter): MovieCriteria {
        val v = Validator()
        val search = Text.searchTerm(filter.search)
        v.check((search?.length ?: 0) <= MAX_SEARCH, "search", "must be at most $MAX_SEARCH characters")
        val genres =
            filter.genreCodes
                .orEmpty()
                .toSortedSet()
                .toList()
        v.check(genres.size <= MAX_GENRES, "genreCodes", "choose at most $MAX_GENRES genres")
        filter.releaseYear?.let { v.check(it in 1..9999, "releaseYear", "must be between 1 and 9999") }
        v.throwIfInvalid()
        return MovieCriteria(search, genres, filter.releaseYear)
    }

    companion object {
        const val MAX_SEARCH = 200
        const val MAX_GENRES = 20

        // Must match the index expressions in V3__listing_indexes.sql.
        private const val OLDEST_KEY = "coalesce(m.release_date, cast('infinity' as date))"
        private const val NEWEST_KEY = "coalesce(m.release_date, cast('-infinity' as date))"
    }
}
