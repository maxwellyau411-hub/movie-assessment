package moviecatalogue.catalogue.credit

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import moviecatalogue.catalogue.movie.Movie
import moviecatalogue.catalogue.person.Person
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

/** Controlled creator job roles. A creator credit's role may also be null ("Unspecified"). */
enum class CreatorRole { DIRECTOR, WRITER, PRODUCER, COMPOSER }

/** Person appears in the movie's cast. One entry per person per movie; several roles share one string. */
@Entity
@Table(name = "cast_credit")
class CastCredit(
    @Id
    val id: UUID,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "movie_id", nullable = false)
    val movie: Movie,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "person_id", nullable = false)
    val person: Person,
    @Column(name = "character_name", length = 200)
    val characterName: String?,
) {
    override fun equals(other: Any?) = other is CastCredit && other.id == id

    override fun hashCode() = id.hashCode()
}

/** Person worked on the movie as a creator. Distinct roles for the same person and movie are allowed. */
@Entity
@Table(name = "creator_credit")
class CreatorCredit(
    @Id
    val id: UUID,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "movie_id", nullable = false)
    val movie: Movie,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "person_id", nullable = false)
    val person: Person,
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    val role: CreatorRole?,
) {
    override fun equals(other: Any?) = other is CreatorCredit && other.id == id

    override fun hashCode() = id.hashCode()
}

interface CastCreditRepository : JpaRepository<CastCredit, UUID> {
    fun existsByMovieIdAndPersonId(
        movieId: UUID,
        personId: UUID,
    ): Boolean

    fun existsByPersonId(personId: UUID): Boolean

    @Query("select c from CastCredit c join fetch c.person p join fetch c.movie where c.movie.id = :movieId order by p.name, p.id")
    fun findForMovie(movieId: UUID): List<CastCredit>

    /** One query for a whole page of movies (GraphQL batch loading). */
    @Query("select c from CastCredit c join fetch c.person p join fetch c.movie m where m.id in :movieIds order by p.name, p.id")
    fun findForMovies(movieIds: Collection<UUID>): List<CastCredit>

    @Query(
        "select c from CastCredit c join fetch c.movie m join fetch c.person p where p.id in :personIds " +
            "order by m.releaseDate desc nulls last, m.title, m.id",
    )
    fun findForPeople(personIds: Collection<UUID>): List<CastCredit>
}

interface CreatorCreditRepository : JpaRepository<CreatorCredit, UUID> {
    /** `role = null` matches a role-less credit (Spring Data derives IS NULL). */
    fun existsByMovieIdAndPersonIdAndRole(
        movieId: UUID,
        personId: UUID,
        role: CreatorRole?,
    ): Boolean

    fun existsByPersonId(personId: UUID): Boolean

    @Query(
        "select c from CreatorCredit c join fetch c.person p join fetch c.movie where c.movie.id = :movieId " +
            "order by p.name, p.id, c.role nulls last",
    )
    fun findForMovie(movieId: UUID): List<CreatorCredit>

    @Query(
        "select c from CreatorCredit c join fetch c.person p join fetch c.movie m where m.id in :movieIds " +
            "order by p.name, p.id, c.role nulls last",
    )
    fun findForMovies(movieIds: Collection<UUID>): List<CreatorCredit>

    @Query(
        "select c from CreatorCredit c join fetch c.movie m join fetch c.person p where p.id in :personIds " +
            "order by m.releaseDate desc nulls last, m.title, m.id, c.role nulls last",
    )
    fun findForPeople(personIds: Collection<UUID>): List<CreatorCredit>
}
