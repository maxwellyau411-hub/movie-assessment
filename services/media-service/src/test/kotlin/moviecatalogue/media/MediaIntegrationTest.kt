package moviecatalogue.media

import io.grpc.health.v1.HealthGrpc.HealthBlockingStub
import moviecatalogue.contracts.media.v1.ArtworkServiceGrpc.ArtworkServiceBlockingStub
import moviecatalogue.contracts.media.v1.ArtworkServiceGrpc.ArtworkServiceStub
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.grpc.client.ImportGrpcClients
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files

/**
 * The real service on PostgreSQL 18 (the Compose image) and a temporary media folder. gRPC runs
 * in-process; HTTP runs on a random port. Every subclass shares one application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestGrpcTransport
@ImportGrpcClients(types = [ArtworkServiceStub::class, ArtworkServiceBlockingStub::class, HealthBlockingStub::class])
abstract class MediaIntegrationTest {
    companion object {
        private val postgres: PostgreSQLContainer =
            PostgreSQLContainer("postgres:18.6-alpine").withDatabaseName("media_db").also { it.start() }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("media.root") { Files.createTempDirectory("media-test").toString() }
        }
    }

    @LocalServerPort
    protected var port: Int = 0
}
