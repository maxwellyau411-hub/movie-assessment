package moviecatalogue.catalogue.movie

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table
import jakarta.persistence.Version
import moviecatalogue.catalogue.genre.Genre
import org.hibernate.annotations.BatchSize
import org.hibernate.annotations.OptimisticLock
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Field values are validated by [MovieService] before they reach the entity; the entity guards the
 * one structural invariant (at least one genre). Changing any field, genre or credit bumps [version].
 */
@Entity
@Table(name = "movie")
class Movie(
    @Id
    val id: UUID,
    title: String,
    synopsis: String?,
    releaseDate: LocalDate?,
    runtimeMinutes: Int?,
    genres: Set<Genre>,
    now: Instant,
) {
    @Column(nullable = false, length = 200)
    var title: String = title
        protected set

    @Column(length = 5000)
    var synopsis: String? = synopsis
        protected set

    var releaseDate: LocalDate? = releaseDate
        protected set

    var runtimeMinutes: Int? = runtimeMinutes
        protected set

    // Loaded for up to 50 movies per query when a page of movies is mapped (avoids N+1).
    @ManyToMany
    @BatchSize(size = 50)
    @JoinTable(
        name = "movie_genre",
        joinColumns = [JoinColumn(name = "movie_id")],
        inverseJoinColumns = [JoinColumn(name = "genre_code")],
    )
    var genres: MutableSet<Genre> = genres.toMutableSet()
        protected set

    /** Null until first persisted; Spring Data uses that to choose persist over merge. */
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
        require(genres.isNotEmpty()) { "A movie needs at least one genre" }
    }

    fun update(
        title: String,
        synopsis: String?,
        releaseDate: LocalDate?,
        runtimeMinutes: Int?,
        genres: Set<Genre>,
        now: Instant,
    ) {
        require(genres.isNotEmpty()) { "A movie needs at least one genre" }
        this.title = title
        this.synopsis = synopsis
        this.releaseDate = releaseDate
        this.runtimeMinutes = runtimeMinutes
        this.genres.retainAll(genres)
        this.genres.addAll(genres)
        touch(now)
    }

    /**
     * Records the time of a change to something the movie owns (credits, poster). The caller also
     * force-increments the version, because those changes are not fields of this entity.
     */
    fun touch(now: Instant) {
        updatedAt = now
    }

    override fun equals(other: Any?) = other is Movie && other.id == id

    override fun hashCode() = id.hashCode()
}

private const val CREDITED_FOR_PERSON =
    "from Movie m where m.id in (select c.movie.id from CastCredit c where c.person.id = :personId) " +
        "or m.id in (select c.movie.id from CreatorCredit c where c.person.id = :personId)"

interface MovieRepository : JpaRepository<Movie, UUID> {
    /** Movies in which the person has any cast or creator credit, each movie once. */
    @Query(
        value = "select m $CREDITED_FOR_PERSON order by lower(m.title), m.id",
        countQuery = "select count(m) $CREDITED_FOR_PERSON",
    )
    fun findCreditedMovies(
        personId: UUID,
        pageable: Pageable,
    ): Page<Movie>

    /** Same normalisation as Text.matchKey: trim, collapse whitespace, lower-case. Capped at 5 candidates. */
    @Query(
        nativeQuery = true,
        value = """
            select * from movie m
            where lower(regexp_replace(m.title, '\s+', ' ', 'g')) = :titleKey
              and (cast(:releaseDate as date) is null or m.release_date is null or m.release_date = cast(:releaseDate as date))
              and (cast(:excludeId as uuid) is null or m.id <> cast(:excludeId as uuid))
            order by m.release_date nulls last, m.id
            limit 5
        """,
    )
    fun findPossibleDuplicates(
        titleKey: String,
        releaseDate: LocalDate?,
        excludeId: UUID?,
    ): List<Movie>
}
