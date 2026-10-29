package moviecatalogue.catalogue.person

import jakarta.persistence.EntityManager
import moviecatalogue.catalogue.common.Connection
import moviecatalogue.catalogue.common.Cursor
import moviecatalogue.catalogue.common.PageArgs
import moviecatalogue.catalogue.common.Text
import moviecatalogue.catalogue.common.TotalCount
import moviecatalogue.catalogue.common.Validator
import moviecatalogue.catalogue.credit.CreatorRole
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Case-insensitive name order, then id. NAME_DESC reads the same index backwards, so its id order is descending too. */
enum class PersonSort { NAME_ASC, NAME_DESC }

/** A validated people filter. [role] is only used for the Creators list. */
data class PersonCriteria(
    val category: PersonCategory,
    val search: String?,
    val role: CreatorRole?,
)

/**
 * The Cast and Creators lists. Membership comes from the person's category, so people without
 * credits (and creators whose role is unspecified) are listed. A role filter uses EXISTS, so a
 * person with several matching credits is listed and counted once. The SQL matches the indexes
 * in V3__listing_indexes.sql; change both together.
 */
@Service
@Transactional(readOnly = true)
class PersonQueryService(
    private val entityManager: EntityManager,
) {
    fun cast(
        search: String?,
        sort: PersonSort,
        page: PageArgs,
    ): Connection<PersonView, PersonCriteria> = list(criteria(PersonCategory.CAST, search, role = null), sort, page)

    fun creators(
        search: String?,
        role: CreatorRole?,
        sort: PersonSort,
        page: PageArgs,
    ): Connection<PersonView, PersonCriteria> = list(criteria(PersonCategory.CREATOR, search, role), sort, page)

    /** Matching people, counting at most [TotalCount.CAP] + 1. */
    fun count(criteria: PersonCriteria): TotalCount {
        val where = Where(criteria)
        val sql = "select count(*) from (select 1 from person p ${where.sql()} limit :limit) matches"
        val query = entityManager.createNativeQuery(sql)
        where.params.forEach(query::setParameter)
        query.setParameter("limit", TotalCount.CAP + 1)
        return TotalCount.of((query.singleResult as Number).toLong())
    }

    private fun list(
        criteria: PersonCriteria,
        sort: PersonSort,
        page: PageArgs,
    ): Connection<PersonView, PersonCriteria> {
        val scope = listOf(criteria.category.name, sort.name, criteria.search?.lowercase(), criteria.role?.name)
        val where = Where(criteria)
        page.after?.let {
            val key = Cursor.decode(it, scope, keySize = 2)
            val op = if (sort == PersonSort.NAME_ASC) ">" else "<"
            where.conditions += "(lower(p.name), p.id) $op (lower(:afterName), :afterId)"
            where.params["afterName"] = Cursor.keyValue { checkNotNull(key[0]) }
            where.params["afterId"] = Cursor.keyValue { UUID.fromString(key[1]) }
        }
        val order = if (sort == PersonSort.NAME_ASC) "lower(p.name), p.id" else "lower(p.name) desc, p.id desc"

        val query =
            entityManager.createNativeQuery(
                "select p.* from person p ${where.sql()} order by $order limit :limit",
                Person::class.java,
            )
        where.params.forEach(query::setParameter)
        query.setParameter("limit", page.first + 1)
        @Suppress("UNCHECKED_CAST")
        val rows = query.resultList as List<Person>
        return Connection.of(rows, page, scope, criteria, key = { listOf(it.name, it.id.toString()) }, view = { it.toView() })
    }

    /** Collects SQL conditions with their named parameters. Only fixed SQL text is concatenated. */
    private class Where(
        criteria: PersonCriteria,
    ) {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()

        init {
            // Plain column test, so PostgreSQL can use the matching partial index.
            conditions += if (criteria.category == PersonCategory.CAST) "p.is_cast" else "p.is_creator"
            criteria.search?.let {
                conditions += "lower(p.name) like :search"
                params["search"] = Text.likeContains(it)
            }
            criteria.role?.let {
                conditions += "exists (select 1 from creator_credit c where c.person_id = p.id and c.role = :role)"
                params["role"] = it.name
            }
        }

        fun sql() = "where " + conditions.joinToString(" and ")
    }

    private fun criteria(
        category: PersonCategory,
        search: String?,
        role: CreatorRole?,
    ): PersonCriteria {
        val term = Text.searchTerm(search)
        val v = Validator()
        v.check((term?.length ?: 0) <= MAX_SEARCH, "search", "must be at most $MAX_SEARCH characters")
        v.throwIfInvalid()
        return PersonCriteria(category, term, role)
    }

    companion object {
        const val MAX_SEARCH = 200
    }
}
