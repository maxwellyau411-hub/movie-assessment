package moviecatalogue.media

import io.grpc.health.v1.HealthCheckRequest
import io.grpc.health.v1.HealthCheckResponse.ServingStatus
import io.grpc.health.v1.HealthGrpc.HealthBlockingStub
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

/** Flyway migrates a fresh database, Hibernate validates, gRPC health reports SERVING, HTTP readiness is up. */
class MediaServiceFoundationTest : MediaIntegrationTest() {
    @Autowired
    private lateinit var health: HealthBlockingStub

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `flyway applies every migration in order to a fresh database`() {
        val applied =
            jdbc.queryForList(
                "select version from flyway_schema_history where success order by installed_rank",
                String::class.java,
            )

        assertEquals(listOf("1", "2"), applied)
    }

    @Test
    fun `overall gRPC health becomes SERVING once the application and database are healthy`() {
        val deadline = System.nanoTime() + 15_000_000_000
        var status: ServingStatus
        do {
            status = health.check(HealthCheckRequest.getDefaultInstance()).status
            if (status == ServingStatus.SERVING) break
            Thread.sleep(200)
        } while (System.nanoTime() < deadline)

        assertEquals(ServingStatus.SERVING, status)
    }

    @Test
    fun `readiness probe is exposed for Compose health checks`() {
        val response =
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/actuator/health/readiness")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        assertEquals(200, response.statusCode(), response.body())
    }
}
