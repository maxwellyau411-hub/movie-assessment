package moviecatalogue.catalogue.person

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.OptimisticLock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Which catalogue list(s) a person appears in. Separate from a creator credit's job role. */
enum class PersonCategory { CAST, CREATOR }

/**
 * One shared record per person: someone who both acts and directs has a single biography.
 * Categories are stored as two flags with a database CHECK that at least one is set.
 */
@Entity
@Table(name = "person")
class Person(
    @Id
    val id: UUID,
    name: String,
    dateOfBirth: LocalDate?,
    biography: String?,
    categories: Set<PersonCategory>,
    now: Instant,
) {
    @Column(nullable = false, length = 200)
    var name: String = name
        protected set

    var dateOfBirth: LocalDate? = dateOfBirth
        protected set

    @Column(length = 5000)
    var biography: String? = biography
        protected set

    @Column(name = "is_cast", nullable = false)
    var isCast: Boolean = PersonCategory.CAST in categories
        protected set

    @Column(name = "is_creator", nullable = false)
    var isCreator: Boolean = PersonCategory.CREATOR in categories
        protected set

    @Version
    var version: Long? = null
        protected set

    @Column(nullable = false)
    var createdAt: Instant = now
        protected set

    /** Excluded from versioning: changing only the timestamp must not count as an extra edit. */
    @Column(nullable = false)
    @OptimisticLock(excluded = true)
    var updatedAt: Instant = now
        protected set

    init {
        require(categories.isNotEmpty()) { "A person needs at least one category" }
    }

    val categories: Set<PersonCategory>
        get() =
            buildSet {
                if (isCast) add(PersonCategory.CAST)
                if (isCreator) add(PersonCategory.CREATOR)
            }

    fun update(
        name: String,
        dateOfBirth: LocalDate?,
        biography: String?,
        categories: Set<PersonCategory>,
        now: Instant,
    ) {
        require(categories.isNotEmpty()) { "A person needs at least one category" }
        this.name = name
        this.dateOfBirth = dateOfBirth
        this.biography = biography
        this.isCast = PersonCategory.CAST in categories
        this.isCreator = PersonCategory.CREATOR in categories
        touch(now)
    }

    /**
     * Records the time of a credit change. The caller also force-increments the version, so a
     * pending deletion request for this person becomes stale.
     */
    fun touch(now: Instant) {
        updatedAt = now
    }

    override fun equals(other: Any?) = other is Person && other.id == id

    override fun hashCode() = id.hashCode()
}

interface PersonRepository : JpaRepository<Person, UUID> {
    /** Same normalisation as Text.matchKey. Capped at 5 candidates. */
    @Query(
        nativeQuery = true,
        value = """
            select * from person p
            where lower(regexp_replace(p.name, '\s+', ' ', 'g')) = :nameKey
              and (cast(:dateOfBirth as date) is null or p.date_of_birth is null or p.date_of_birth = cast(:dateOfBirth as date))
              and (cast(:excludeId as uuid) is null or p.id <> cast(:excludeId as uuid))
            order by p.date_of_birth nulls last, p.id
            limit 5
        """,
    )
    fun findPossibleDuplicates(
        nameKey: String,
        dateOfBirth: LocalDate?,
        excludeId: UUID?,
    ): List<Person>
}
