package moviecatalogue.catalogue.graphql

import moviecatalogue.catalogue.artwork.PosterService
import moviecatalogue.catalogue.common.Connection
import moviecatalogue.catalogue.common.PageArgs
import moviecatalogue.catalogue.common.SaveResult
import moviecatalogue.catalogue.common.TotalCount
import moviecatalogue.catalogue.credit.CastCreditView
import moviecatalogue.catalogue.credit.CreatorCreditView
import moviecatalogue.catalogue.credit.CreatorRole
import moviecatalogue.catalogue.credit.CreditService
import moviecatalogue.catalogue.genre.GenreRepository
import moviecatalogue.catalogue.movie.GenreView
import moviecatalogue.catalogue.movie.MovieCriteria
import moviecatalogue.catalogue.movie.MovieFilter
import moviecatalogue.catalogue.movie.MovieInput
import moviecatalogue.catalogue.movie.MovieQueryService
import moviecatalogue.catalogue.movie.MovieRef
import moviecatalogue.catalogue.movie.MovieSaveResult
import moviecatalogue.catalogue.movie.MovieService
import moviecatalogue.catalogue.movie.MovieSort
import moviecatalogue.catalogue.movie.MovieView
import moviecatalogue.catalogue.person.PersonCriteria
import moviecatalogue.catalogue.person.PersonInput
import moviecatalogue.catalogue.person.PersonQueryService
import moviecatalogue.catalogue.person.PersonRef
import moviecatalogue.catalogue.person.PersonSaveResult
import moviecatalogue.catalogue.person.PersonService
import moviecatalogue.catalogue.person.PersonSort
import moviecatalogue.catalogue.person.PersonView
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

// GraphQL union members; the class simple name selects the GraphQL type.
data class MovieSaved(
    val movie: MovieView,
)

data class DuplicateMovieWarning(
    val message: String,
    val candidates: List<MovieRef>,
)

data class PersonSaved(
    val person: PersonView,
)

data class DuplicatePersonWarning(
    val message: String,
    val candidates: List<PersonRef>,
)

private const val DUPLICATE_MESSAGE = "Possible duplicate. Nothing was saved: review the existing records, then cancel or save anyway."

private fun MovieSaveResult.toMovieResult(): Any =
    when (this) {
        is SaveResult.Saved -> MovieSaved(value)
        is SaveResult.DuplicateWarning -> DuplicateMovieWarning(DUPLICATE_MESSAGE, candidates)
    }

private fun PersonSaveResult.toPersonResult(): Any =
    when (this) {
        is SaveResult.Saved -> PersonSaved(value)
        is SaveResult.DuplicateWarning -> DuplicatePersonWarning(DUPLICATE_MESSAGE, candidates)
    }

/** Transport mapping only: argument mapping and delegation. Rules live in the services. */
@Controller
class CatalogueController(
    private val genres: GenreRepository,
    private val movies: MovieService,
    private val movieQueries: MovieQueryService,
    private val people: PersonService,
    private val personQueries: PersonQueryService,
    private val credits: CreditService,
    private val posters: PosterService,
) {
    @QueryMapping
    fun genres(): List<GenreView> = genres.findAllByOrderByNameAsc().map { GenreView(it.code, it.name) }

    @QueryMapping
    fun movie(
        @Argument id: UUID,
    ): MovieView? = movies.find(id)

    @QueryMapping
    fun movies(
        @Argument first: Int?,
        @Argument after: String?,
        @Argument sort: MovieSort?,
        @Argument filter: MovieFilter?,
    ): Connection<MovieView, MovieCriteria> = movieQueries.list(filter, sort ?: MovieSort.TITLE_ASC, PageArgs.of(first, after))

    @QueryMapping
    fun person(
        @Argument id: UUID,
    ): PersonView? = people.find(id)

    @QueryMapping
    fun castMembers(
        @Argument first: Int?,
        @Argument after: String?,
        @Argument sort: PersonSort?,
        @Argument search: String?,
    ): Connection<PersonView, PersonCriteria> = personQueries.cast(search, sort ?: PersonSort.NAME_ASC, PageArgs.of(first, after))

    @QueryMapping
    fun creators(
        @Argument first: Int?,
        @Argument after: String?,
        @Argument sort: PersonSort?,
        @Argument search: String?,
        @Argument role: CreatorRole?,
    ): Connection<PersonView, PersonCriteria> = personQueries.creators(search, role, sort ?: PersonSort.NAME_ASC, PageArgs.of(first, after))

    /** Runs only when a client selects totalCount, so plain paging never pays for a count. */
    @SchemaMapping(typeName = "MovieConnection", field = "totalCount")
    fun movieTotal(connection: Connection<MovieView, MovieCriteria>): TotalCount = movieQueries.count(connection.criteria)

    @SchemaMapping(typeName = "PersonConnection", field = "totalCount")
    fun personTotal(connection: Connection<PersonView, PersonCriteria>): TotalCount = personQueries.count(connection.criteria)

    @MutationMapping
    fun createMovie(
        @Argument input: MovieInput,
        @Argument confirmDuplicate: Boolean?,
    ): Any = movies.create(input, confirmDuplicate == true).toMovieResult()

    @MutationMapping
    fun updateMovie(
        @Argument id: UUID,
        @Argument expectedVersion: Long,
        @Argument input: MovieInput,
        @Argument confirmDuplicate: Boolean?,
    ): Any = movies.update(id, expectedVersion, input, confirmDuplicate == true).toMovieResult()

    @MutationMapping
    fun deleteMovie(
        @Argument id: UUID,
        @Argument expectedVersion: Long,
    ): UUID {
        movies.delete(id, expectedVersion)
        return id
    }

    /** The browser's declared type is passed on; media-service checks it against the actual bytes. */
    @MutationMapping
    fun uploadMoviePoster(
        @Argument movieId: UUID,
        @Argument file: MultipartFile,
    ): MovieView = posters.upload(movieId, file.originalFilename, file.contentType, file.size, file.inputStream)

    @MutationMapping
    fun createPerson(
        @Argument input: PersonInput,
        @Argument confirmDuplicate: Boolean?,
    ): Any = people.create(input, confirmDuplicate == true).toPersonResult()

    @MutationMapping
    fun updatePerson(
        @Argument id: UUID,
        @Argument expectedVersion: Long,
        @Argument input: PersonInput,
        @Argument confirmDuplicate: Boolean?,
    ): Any = people.update(id, expectedVersion, input, confirmDuplicate == true).toPersonResult()

    @MutationMapping
    fun deletePerson(
        @Argument id: UUID,
        @Argument expectedVersion: Long,
    ): UUID {
        people.delete(id, expectedVersion)
        return id
    }

    @MutationMapping
    fun addCastCredit(
        @Argument movieId: UUID,
        @Argument personId: UUID,
        @Argument characterName: String?,
    ): CastCreditView = credits.addCast(movieId, personId, characterName)

    @MutationMapping
    fun removeCastCredit(
        @Argument creditId: UUID,
    ): UUID {
        credits.removeCast(creditId)
        return creditId
    }

    @MutationMapping
    fun addCreatorCredit(
        @Argument movieId: UUID,
        @Argument personId: UUID,
        @Argument role: CreatorRole?,
    ): CreatorCreditView = credits.addCreator(movieId, personId, role)

    @MutationMapping
    fun removeCreatorCredit(
        @Argument creditId: UUID,
    ): UUID {
        credits.removeCreator(creditId)
        return creditId
    }
}
