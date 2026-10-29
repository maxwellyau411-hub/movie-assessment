package moviecatalogue.media

import io.grpc.Status
import io.grpc.stub.StreamObserver
import moviecatalogue.contracts.media.v1.ArtworkMetadata
import moviecatalogue.contracts.media.v1.ArtworkServiceGrpc
import moviecatalogue.contracts.media.v1.DeleteArtworkRequest
import moviecatalogue.contracts.media.v1.DeleteArtworkResponse
import moviecatalogue.contracts.media.v1.UploadArtworkRequest
import moviecatalogue.contracts.media.v1.UploadArtworkResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import moviecatalogue.contracts.media.v1.Artwork as ArtworkMessage

/**
 * gRPC side of [ArtworkStore]. Rejected files fail with INVALID_ARGUMENT and a message for the
 * user; anything else fails with INTERNAL and a generic message (details stay in this log).
 */
@Service
class ArtworkGrpcService(
    private val store: ArtworkStore,
    private val files: MediaFiles,
) : ArtworkServiceGrpc.ArtworkServiceImplBase() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun uploadArtwork(response: StreamObserver<UploadArtworkResponse>): StreamObserver<UploadArtworkRequest> =
        UploadReceiver(response)

    override fun deleteArtwork(
        request: DeleteArtworkRequest,
        response: StreamObserver<DeleteArtworkResponse>,
    ) {
        val id =
            runCatching { UUID.fromString(request.id) }.getOrElse {
                return response.onError(Status.INVALID_ARGUMENT.withDescription("id must be a UUID").asRuntimeException())
            }
        try {
            val deleted = store.delete(id)
            response.onNext(DeleteArtworkResponse.newBuilder().setDeleted(deleted).build())
            response.onCompleted()
        } catch (ex: Exception) {
            log.error("Deleting artwork {} failed", id, ex)
            response.onError(Status.INTERNAL.withDescription("Could not delete the image").asRuntimeException())
        }
    }

    /**
     * One upload call. The first message must be the metadata; every later one is a chunk, written
     * straight to a staging file while the total is counted, so an oversize upload is refused as
     * soon as it passes the limit rather than after it has all arrived. gRPC delivers one call's
     * messages one at a time, so this needs no locking.
     */
    private inner class UploadReceiver(
        private val response: StreamObserver<UploadArtworkResponse>,
    ) : StreamObserver<UploadArtworkRequest> {
        private var metadata: UploadMetadata? = null
        private var staged: Path? = null
        private var output: OutputStream? = null
        private var received = 0L
        private var finished = false

        override fun onNext(request: UploadArtworkRequest) {
            if (finished) return
            try {
                when (request.partCase) {
                    UploadArtworkRequest.PartCase.METADATA -> start(request.metadata)
                    UploadArtworkRequest.PartCase.CHUNK -> append(request.chunk.toByteArray())
                    else -> throw InvalidImageException("An upload message carried neither metadata nor data")
                }
            } catch (ex: InvalidImageException) {
                fail(Status.INVALID_ARGUMENT.withDescription(ex.message))
            } catch (ex: Exception) {
                log.error("Receiving an upload failed", ex)
                fail(Status.INTERNAL.withDescription("Could not receive the image"))
            }
        }

        /** The client cancelled or the connection broke: keep nothing. */
        override fun onError(t: Throwable) {
            log.info("Upload interrupted: {}", Status.fromThrowable(t).code)
            finished = true
            cleanUp()
        }

        override fun onCompleted() {
            if (finished) return
            try {
                val metadata = metadata ?: throw InvalidImageException("The upload contained no file")
                output?.close()
                if (received != metadata.declaredSize) {
                    throw InvalidImageException("The upload ended after $received of ${metadata.declaredSize} bytes; try again")
                }
                val artwork = store.store(checkNotNull(staged), metadata)
                finished = true
                response.onNext(UploadArtworkResponse.newBuilder().setArtwork(artwork.toMessage()).build())
                response.onCompleted()
            } catch (ex: InvalidImageException) {
                fail(Status.INVALID_ARGUMENT.withDescription(ex.message))
            } catch (ex: Exception) {
                log.error("Storing an upload failed", ex)
                fail(Status.INTERNAL.withDescription("Could not store the image"))
            } finally {
                cleanUp()
            }
        }

        private fun start(message: ArtworkMetadata) {
            if (metadata != null) throw InvalidImageException("The upload sent its metadata twice")
            val format =
                ImageFormat.ofContentType(message.contentType)
                    ?: throw InvalidImageException("Unsupported image format; use JPEG, PNG or WebP")
            if (message.declaredSize <= 0) throw InvalidImageException("The file is empty")
            if (message.declaredSize > ImageProcessor.MAX_BYTES) throw InvalidImageException("The file is larger than 5 MB")
            metadata = UploadMetadata(displayName(message.fileName), format, message.declaredSize)
            staged = files.newStagingFile().also { output = Files.newOutputStream(it) }
        }

        private fun append(chunk: ByteArray) {
            val metadata = metadata ?: throw InvalidImageException("The upload sent data before its metadata")
            received += chunk.size
            if (received > ImageProcessor.MAX_BYTES) throw InvalidImageException("The file is larger than 5 MB")
            if (received > metadata.declaredSize) throw InvalidImageException("The file is larger than its declared size")
            checkNotNull(output).write(chunk)
        }

        private fun fail(status: Status) {
            finished = true
            cleanUp()
            response.onError(status.asRuntimeException())
        }

        private fun cleanUp() {
            runCatching { output?.close() }
            output = null
            staged?.let(Files::deleteIfExists) // already moved away after a successful store
            staged = null
        }
    }
}

/** Only the last path part, at most 255 characters: some browsers send "C:\fakepath\poster.png". */
fun displayName(fileName: String): String? =
    fileName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()
        .take(255)
        .ifEmpty { null }

fun Artwork.toMessage(): ArtworkMessage =
    ArtworkMessage
        .newBuilder()
        .setId(id.toString())
        .setContentType(contentType)
        .setWidth(width)
        .setHeight(height)
        .setByteSize(byteSize.toLong())
        .setFullUrl(fullUrl)
        .setThumbnailUrl(thumbnailUrl)
        .setThumbnailWidth(thumbnailWidth)
        .setThumbnailHeight(thumbnailHeight)
        .build()
