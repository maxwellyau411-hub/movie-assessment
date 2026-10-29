package moviecatalogue.catalogue.artwork

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import moviecatalogue.catalogue.common.FieldError
import moviecatalogue.catalogue.common.ServiceUnavailableException
import moviecatalogue.catalogue.common.ValidationException
import moviecatalogue.contracts.media.v1.ArtworkMetadata
import moviecatalogue.contracts.media.v1.ArtworkServiceGrpc.ArtworkServiceBlockingStub
import moviecatalogue.contracts.media.v1.ArtworkServiceGrpc.ArtworkServiceStub
import moviecatalogue.contracts.media.v1.DeleteArtworkRequest
import moviecatalogue.contracts.media.v1.UploadArtworkRequest
import moviecatalogue.contracts.media.v1.UploadArtworkResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** catalogue-service's only way to media-service: upload and delete over gRPC. */
@Component
class MediaClient(
    private val uploads: ArtworkServiceStub,
    private val calls: ArtworkServiceBlockingStub,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Sends the metadata, then the file in 64 KiB chunks. media-service validates while the bytes
     * arrive, so if it refuses the file part way, sending stops there. A refused file becomes a
     * [ValidationException] on field `file` with media-service's message; no answer becomes a
     * [ServiceUnavailableException].
     */
    fun upload(
        fileName: String?,
        contentType: String?,
        size: Long,
        content: InputStream,
    ): StoredArtwork {
        val result = CompletableFuture<UploadArtworkResponse>()
        val requests =
            uploads.withDeadlineAfter(UPLOAD_DEADLINE_SECONDS, TimeUnit.SECONDS).uploadArtwork(
                object : StreamObserver<UploadArtworkResponse> {
                    override fun onNext(value: UploadArtworkResponse) {
                        result.complete(value)
                    }

                    override fun onError(t: Throwable) {
                        result.completeExceptionally(t)
                    }

                    override fun onCompleted() {}
                },
            )
        try {
            requests.onNext(
                UploadArtworkRequest
                    .newBuilder()
                    .setMetadata(
                        ArtworkMetadata
                            .newBuilder()
                            .setFileName(fileName.orEmpty())
                            .setContentType(contentType.orEmpty())
                            .setDeclaredSize(size),
                    ).build(),
            )
            content.use { input ->
                val buffer = ByteArray(CHUNK_BYTES)
                while (!result.isDone) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    requests.onNext(UploadArtworkRequest.newBuilder().setChunk(ByteString.copyFrom(buffer, 0, read)).build())
                }
            }
            requests.onCompleted()
        } catch (ex: Exception) {
            // Tells media-service to drop what it has received so far.
            requests.onError(Status.CANCELLED.withDescription("catalogue-service could not read the upload").asRuntimeException())
            throw ex
        }
        val response =
            try {
                result.get()
            } catch (ex: ExecutionException) {
                throw translate(ex.cause)
            }
        return response.artwork.let { StoredArtwork(UUID.fromString(it.id), it.width, it.height, it.fullUrl, it.thumbnailUrl) }
    }

    /**
     * Best effort, never throws: a failure only leaves an unused image in media-service, logged with
     * its ID, and must not undo the change that made it unused.
     */
    fun deleteQuietly(artworkId: UUID) {
        try {
            calls.deleteArtwork(DeleteArtworkRequest.newBuilder().setId(artworkId.toString()).build())
        } catch (ex: StatusRuntimeException) {
            log.warn("Artwork {} could not be deleted from media-service ({}); it is now unused", artworkId, ex.status.code)
        }
    }

    private fun translate(failure: Throwable?): RuntimeException {
        val status = Status.fromThrowable(failure)
        return when (status.code) {
            Status.Code.INVALID_ARGUMENT -> {
                ValidationException(listOf(FieldError("file", status.description ?: "The file was not accepted")))
            }

            Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED -> {
                ServiceUnavailableException("Artwork storage is unavailable; nothing was changed, try again later", failure)
            }

            else -> {
                IllegalStateException("media-service upload failed: ${status.code}", failure)
            }
        }
    }

    companion object {
        const val CHUNK_BYTES = 64 * 1024

        /** Long enough for a 5 MB file plus validation; the channel default (2 s) is for small calls. */
        const val UPLOAD_DEADLINE_SECONDS = 30L
    }
}
