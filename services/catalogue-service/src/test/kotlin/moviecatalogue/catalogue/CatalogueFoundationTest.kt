package moviecatalogue.catalogue

import io.grpc.Status
import io.grpc.health.v1.HealthCheckRequest
import io.grpc.health.v1.HealthCheckResponse
import io.grpc.health.v1.HealthCheckResponse.ServingStatus
import io.grpc.health.v1.HealthGrpc
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-process stand-in for media-service's grpc.health.v1 endpoint. */
class FakeMediaServiceHealth : HealthGrpc.HealthImplBase() {
    @Volatile var reply: ServingStatus? = ServingStatus.SERVING // null = fail the call with UNAVAILABLE

    override fun check(
        request: HealthCheckRequest,
        responseObserver: StreamObserver<HealthCheckResponse>,
    ) {
        val status = reply ?: return responseObserver.onError(Status.UNAVAILABLE.asRuntimeException())
        responseObserver.onNext(HealthCheckResponse.newBuilder().setStatus(status).build())
        responseObserver.onCompleted()
    }
}

// The fake replaces the server-side health service that the gRPC test starter would otherwise add.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.grpc.server.health.enabled=false"],
)
@Testcontainers
@AutoConfigureTestGrpcTransport
@Import(CatalogueFoundationTest.FakeMediaServiceHealthConfig::class)
class CatalogueFoundationTest {
    @TestConfiguration
    class FakeMediaServiceHealthConfig {
        @Bean
        fun fakeMediaServiceHealth() = FakeMediaServiceHealth()
    }

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18.6-alpine").withDatabaseName("catalogue_db")

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var fakeMediaService: FakeMediaServiceHealth

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()

    @BeforeEach
    fun reset() {
        fakeMediaService.reply = ServingStatus.SERVING
    }

    private fun serviceStatus(): JsonNode {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/graphql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"query":"{ serviceStatus { catalogue mediaService } }"}"""))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        val body = json.readTree(response.body())
        assertTrue(body.path("errors").isMissingNode, response.body())
        return body.path("data").path("serviceStatus")
    }

    @Test
    fun `flyway applies every migration in order to a fresh database and seeds the genre list`() {
        val applied =
            jdbc.queryForList(
                "select version from flyway_schema_history where success order by installed_rank",
                String::class.java,
            )

        assertEquals(listOf("1", "2", "3", "4"), applied)
        assertEquals(1, jdbc.queryForObject("select count(*) from pg_extension where extname = 'pg_trgm'", Int::class.java))
        assertEquals(18, jdbc.queryForObject("select count(*) from genre", Int::class.java))
    }

    @Test
    fun `serviceStatus reports UP when media-service answers SERVING over gRPC`() {
        val status = serviceStatus()

        assertEquals("UP", status.path("catalogue").asString())
        assertEquals("UP", status.path("mediaService").asString())
    }

    @Test
    fun `serviceStatus reports DOWN when media-service is not serving`() {
        fakeMediaService.reply = ServingStatus.NOT_SERVING

        assertEquals("DOWN", serviceStatus().path("mediaService").asString())
    }

    @Test
    fun `serviceStatus reports DOWN, not an error, when the gRPC call fails`() {
        fakeMediaService.reply = null

        assertEquals("DOWN", serviceStatus().path("mediaService").asString())
    }

    @Test
    fun `readiness probe is exposed for Compose health checks`() {
        val response =
            http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/actuator/health/readiness")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        assertEquals(200, response.statusCode())
        assertEquals("UP", json.readTree(response.body()).path("status").asString())
    }
}
