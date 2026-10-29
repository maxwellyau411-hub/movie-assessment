package moviecatalogue.catalogue

import io.grpc.health.v1.HealthGrpc.HealthBlockingStub
import moviecatalogue.contracts.media.v1.ArtworkServiceGrpc.ArtworkServiceBlockingStub
import moviecatalogue.contracts.media.v1.ArtworkServiceGrpc.ArtworkServiceStub
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.grpc.client.ImportGrpcClients
import java.time.Clock

@SpringBootApplication
@ConfigurationPropertiesScan
// The async stub is needed for the client-streaming upload; the blocking ones for everything else.
@ImportGrpcClients(
    target = "media-service",
    types = [HealthBlockingStub::class, ArtworkServiceStub::class, ArtworkServiceBlockingStub::class],
)
class CatalogueServiceApplication {
    /** Injected everywhere "now" matters, so tests can fix time. Timestamps are stored in UTC. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<CatalogueServiceApplication>(*args)
}
