package moviecatalogue.catalogue

import io.grpc.StatusRuntimeException
import io.grpc.health.v1.HealthCheckRequest
import io.grpc.health.v1.HealthCheckResponse.ServingStatus
import io.grpc.health.v1.HealthGrpc.HealthBlockingStub
import org.slf4j.LoggerFactory
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service

enum class ComponentStatus { UP, DOWN }

data class ServiceStatus(
    val catalogue: ComponentStatus,
    val mediaService: ComponentStatus,
)

/** Asks media-service for its overall health over gRPC (bounded by the channel's default deadline). */
@Service
class MediaServiceHealth(
    private val health: HealthBlockingStub,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun check(): ComponentStatus =
        try {
            val status = health.check(HealthCheckRequest.getDefaultInstance()).status
            if (status == ServingStatus.SERVING) ComponentStatus.UP else ComponentStatus.DOWN
        } catch (ex: StatusRuntimeException) {
            log.warn("media-service health check failed: {}", ex.status.code)
            ComponentStatus.DOWN
        }
}

/** Foundation probe: proves browser -> GraphQL -> gRPC -> media-service end to end. */
@Controller
class ServiceStatusController(
    private val mediaServiceHealth: MediaServiceHealth,
) {
    @QueryMapping
    fun serviceStatus() = ServiceStatus(catalogue = ComponentStatus.UP, mediaService = mediaServiceHealth.check())
}
