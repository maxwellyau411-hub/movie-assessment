package moviecatalogue.catalogue

import io.grpc.Status
import io.grpc.stub.StreamObserver
import moviecatalogue.contracts.media.v1.Artwork
import moviecatalogue.contracts.media.v1.ArtworkMetadata
import moviecatalogue.contracts.media.v1.ArtworkServiceGrpc
import moviecatalogue.contracts.media.v1.DeleteArtworkRequest
import moviecatalogue.contracts.media.v1.DeleteArtworkResponse
import moviecatalogue.contracts.media.v1.UploadArtworkRequest
import moviecatalogue.contracts.media.v1.UploadArtworkResponse
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO

/**
 * In-memory stand-in for media-service, served over in-process gRPC. It does not validate files
 * (media-service's own tests cover that); tests make it fail on purpose instead.
 */
class FakeMediaService : ArtworkServiceGrpc.ArtworkServiceImplBase() {
    class Received(
        val metadata: ArtworkMetadata,
        val bytes: ByteArray,
    )

    /** Stored uploads by artwork ID; deleting removes them. */
    val stored = ConcurrentHashMap<String, Received>()
    val deleteRequests = CopyOnWriteArrayList<String>()

    /** When set, every upload or delete fails with this status. */
    @Volatile var uploadFailure: Status? = null

    @Volatile var deleteFailure: Status? = null

    /** Runs after the bytes arrive and before the answer, e.g. to change the database meanwhile. */
    @Volatile var beforeUploadAnswer: (() -> Unit)? = null

    fun reset() {
        stored.clear()
        deleteRequests.clear()
        uploadFailure = null
        deleteFailure = null
        beforeUploadAnswer = null
    }

    override fun uploadArtwork(response: StreamObserver<UploadArtworkResponse>): StreamObserver<UploadArtworkRequest> =
        object : StreamObserver<UploadArtworkRequest> {
            private var metadata: ArtworkMetadata? = null
            private val bytes = ByteArrayOutputStream()

            override fun onNext(request: UploadArtworkRequest) {
                if (request.hasMetadata()) metadata = request.metadata else request.chunk.writeTo(bytes)
            }

            override fun onError(t: Throwable) {}

            override fun onCompleted() {
                uploadFailure?.let { return response.onError(it.asRuntimeException()) }
                beforeUploadAnswer?.invoke()
                val id = UUID.randomUUID().toString()
                val image = ImageIO.read(ByteArrayInputStream(bytes.toByteArray()))
                stored[id] = Received(checkNotNull(metadata), bytes.toByteArray())
                val artwork =
                    Artwork
                        .newBuilder()
                        .setId(id)
                        .setContentType(metadata!!.contentType)
                        .setWidth(image.width)
                        .setHeight(image.height)
                        .setByteSize(bytes.size().toLong())
                        .setFullUrl("/media/artwork/$id.png")
                        .setThumbnailUrl("/media/thumbnails/$id.jpg")
                        .build()
                response.onNext(UploadArtworkResponse.newBuilder().setArtwork(artwork).build())
                response.onCompleted()
            }
        }

    override fun deleteArtwork(
        request: DeleteArtworkRequest,
        response: StreamObserver<DeleteArtworkResponse>,
    ) {
        deleteFailure?.let { return response.onError(it.asRuntimeException()) }
        deleteRequests += request.id
        val deleted = stored.remove(request.id) != null
        response.onNext(DeleteArtworkResponse.newBuilder().setDeleted(deleted).build())
        response.onCompleted()
    }
}

@TestConfiguration
class FakeMediaConfig {
    @Bean
    fun fakeMediaService() = FakeMediaService()
}
