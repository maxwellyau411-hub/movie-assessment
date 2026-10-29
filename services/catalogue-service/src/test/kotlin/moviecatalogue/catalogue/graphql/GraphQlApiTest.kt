package moviecatalogue.catalogue.graphql

import moviecatalogue.catalogue.CatalogueIntegrationTest
import moviecatalogue.catalogue.FakeMediaConfig
import moviecatalogue.catalogue.FakeMediaService
import moviecatalogue.catalogue.MutableClock
import moviecatalogue.catalogue.TestClockConfig
import moviecatalogue.catalogue.TestDatabase
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

class GqlResponse(
    val status: Int,
    val json: JsonNode,
) {
    fun data(path: String): JsonNode =
        path.split('.').fold(json.path("data")) { node, key ->
            key.toIntOrNull()?.let(node::path)
                ?: node.path(key)
        }

    val errors: JsonNode get() = json.path("errors")

    fun errorCode(index: Int = 0): String? =
        errors
            .path(index)
            .path("extensions")
            .path("code")
            .takeIf { it.isString }
            ?.asString()

    override fun toString() = json.toString()
}

/** GraphQL over real HTTP against the running test server (Tomcat, Spring GraphQL); media-service is faked. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestGrpcTransport
@Import(TestClockConfig::class, FakeMediaConfig::class)
abstract class GraphQlApiTest {
    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) = TestDatabase.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    protected lateinit var clock: MutableClock

    @Autowired
    protected lateinit var media: FakeMediaService

    private val http = HttpClient.newHttpClient()
    protected val mapper: JsonMapper = JsonMapper.builder().build()

    @BeforeEach
    fun resetClockAndMedia() {
        clock.now = CatalogueIntegrationTest.FIXED_NOW
        media.reset()
    }

    protected fun gql(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
    ): GqlResponse {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/graphql"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("query" to query, "variables" to variables))))
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return GqlResponse(response.statusCode(), mapper.readTree(response.body()))
    }

    /**
     * A graphql-multipart-request-spec request, as a browser sends it: `operations` (the query, with
     * the file variable null), `map` (which variable the file part fills), then the file part "0".
     */
    protected fun multipart(
        operations: String,
        map: String,
        file: ByteArray,
        fileName: String = "poster.png",
        contentType: String = "image/png",
    ): HttpResponse<String> {
        val boundary = "----test" + UUID.randomUUID()

        fun text(
            name: String,
            value: String,
        ) = "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()
        val body =
            text("operations", operations) + text("map", map) +
                "--$boundary\r\nContent-Disposition: form-data; name=\"0\"; filename=\"$fileName\"\r\nContent-Type: $contentType\r\n\r\n"
                    .toByteArray() + file + "\r\n--$boundary--\r\n".toByteArray()
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/graphql"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    protected fun uploadPoster(
        movieId: String,
        file: ByteArray,
        contentType: String = "image/png",
        selection: String = "id version artwork { thumbnailUrl fullUrl width height }",
    ): GqlResponse {
        val query = "mutation(\$movieId: ID!, \$file: Upload!) { uploadMoviePoster(movieId: \$movieId, file: \$file) { $selection } }"
        val operations = mapper.writeValueAsString(mapOf("query" to query, "variables" to mapOf("movieId" to movieId, "file" to null)))
        val response = multipart(operations, """{"0": ["variables.file"]}""", file, contentType = contentType)
        return GqlResponse(response.statusCode(), mapper.readTree(response.body()))
    }

    /** Creates a movie with confirmDuplicate=true and returns its id. */
    protected fun createMovie(
        title: String,
        genres: List<String> = listOf("DRAMA"),
        releaseDate: String? = null,
    ): String {
        val result =
            gql(
                """mutation(${'$'}input: MovieInput!) { createMovie(input: ${'$'}input, confirmDuplicate: true) { ... on MovieSaved { movie { id } } } }""",
                mapOf("input" to mapOf("title" to title, "genreCodes" to genres, "releaseDate" to releaseDate)),
            )
        return result.data("createMovie.movie.id").asString().ifEmpty { error("createMovie failed: $result") }
    }

    protected fun createPerson(
        name: String,
        categories: List<String>,
        dateOfBirth: String? = null,
    ): String {
        val result =
            gql(
                """mutation(${'$'}input: PersonInput!) { createPerson(input: ${'$'}input, confirmDuplicate: true) { ... on PersonSaved { person { id } } } }""",
                mapOf("input" to mapOf("name" to name, "categories" to categories, "dateOfBirth" to dateOfBirth)),
            )
        return result.data("createPerson.person.id").asString().ifEmpty { error("createPerson failed: $result") }
    }
}
