package moviecatalogue.catalogue.graphql

import moviecatalogue.catalogue.artwork.MoviePosterRepository
import moviecatalogue.catalogue.credit.CastCreditRepository
import moviecatalogue.catalogue.credit.CastCreditView
import moviecatalogue.catalogue.credit.CreatorCreditRepository
import moviecatalogue.catalogue.credit.CreatorCreditView
import moviecatalogue.catalogue.credit.toView
import moviecatalogue.catalogue.movie.MovieView
import moviecatalogue.catalogue.person.PersonView
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller
import java.time.Clock
import java.time.LocalDate
import java.time.Period

data class ArtworkInfo(
    val thumbnailUrl: String,
    val fullUrl: String,
    val width: Int,
    val height: Int,
)

/**
 * Nested fields resolved once per *list*, not once per item (no N+1): GraphQL collects every movie
 * or person in the response and each @BatchMapping runs a single query for all of them. Access is
 * already checked on the parent query.
 */
@Controller
class MovieFieldsController(
    private val castCredits: CastCreditRepository,
    private val creatorCredits: CreatorCreditRepository,
    private val posters: MoviePosterRepository,
) {
    @BatchMapping(typeName = "Movie")
    fun cast(movies: List<MovieView>): Map<MovieView, List<CastCreditView>> {
        val byMovie = castCredits.findForMovies(movies.map { it.id }).map { it.toView() }.groupBy { it.movie.id }
        return movies.associateWith { byMovie[it.id].orEmpty() }
    }

    @BatchMapping(typeName = "Movie")
    fun creators(movies: List<MovieView>): Map<MovieView, List<CreatorCreditView>> {
        val byMovie = creatorCredits.findForMovies(movies.map { it.id }).map { it.toView() }.groupBy { it.movie.id }
        return movies.associateWith { byMovie[it.id].orEmpty() }
    }

    @BatchMapping(typeName = "Movie")
    fun artwork(movies: List<MovieView>): Map<MovieView, ArtworkInfo> {
        val byMovie = posters.findAllById(movies.map { it.id }).associateBy { it.movieId }
        return movies
            .mapNotNull { movie -> byMovie[movie.id]?.let { movie to ArtworkInfo(it.thumbnailUrl, it.fullUrl, it.width, it.height) } }
            .toMap()
    }
}

@Controller
class PersonFieldsController(
    private val castCredits: CastCreditRepository,
    private val creatorCredits: CreatorCreditRepository,
    private val clock: Clock,
) {
    @BatchMapping(typeName = "Person")
    fun castCredits(people: List<PersonView>): Map<PersonView, List<CastCreditView>> {
        val byPerson = castCredits.findForPeople(people.map { it.id }).map { it.toView() }.groupBy { it.person.id }
        return people.associateWith { byPerson[it.id].orEmpty() }
    }

    @BatchMapping(typeName = "Person")
    fun creatorCredits(people: List<PersonView>): Map<PersonView, List<CreatorCreditView>> {
        val byPerson = creatorCredits.findForPeople(people.map { it.id }).map { it.toView() }.groupBy { it.person.id }
        return people.associateWith { byPerson[it.id].orEmpty() }
    }

    /** Calculated, never stored. Date of death is deferred, so this is "years since birth". */
    @SchemaMapping(typeName = "Person")
    fun age(person: PersonView): Int? = person.dateOfBirth?.let { Period.between(it, LocalDate.now(clock)).years.coerceAtLeast(0) }
}
