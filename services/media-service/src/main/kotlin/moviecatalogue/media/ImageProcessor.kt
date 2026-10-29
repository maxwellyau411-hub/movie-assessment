package moviecatalogue.media

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.event.IIOReadWarningListener
import kotlin.math.roundToInt

enum class ImageFormat(
    val contentType: String,
    val extension: String,
    val imageIoName: String,
) {
    JPEG("image/jpeg", "jpg", "jpeg"),
    PNG("image/png", "png", "png"),
    WEBP("image/webp", "webp", "webp"),
    ;

    companion object {
        fun ofContentType(contentType: String): ImageFormat? = entries.firstOrNull { it.contentType == contentType }
    }
}

/** The uploaded file is not acceptable. The message is written for the user and shown as is. */
class InvalidImageException(
    message: String,
) : RuntimeException(message)

class ProcessedImage(
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val thumbnail: BufferedImage,
)

/**
 * Checks an uploaded file by its content, never by its name or declared type alone (D20):
 * size, magic bytes, header-only dimensions (so a decompression bomb is refused before any pixels
 * are decoded), then one full decode in which any reader warning counts as a failure.
 *
 * The decode is subsampled, so memory stays small even for a 25-megapixel image, and the decoded
 * pixels are reused for the thumbnail: one read both validates the file and produces the thumbnail.
 */
object ImageProcessor {
    const val MAX_BYTES = 5L * 1024 * 1024
    const val MAX_PIXELS = 25_000_000L
    const val MAX_SIDE = 10_000

    /** Thumbnails are 320 px wide, never enlarged, and never taller than 960 px (3:1). */
    const val THUMBNAIL_WIDTH = 320
    const val THUMBNAIL_MAX_HEIGHT = 960

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    init {
        ImageIO.scanForPlugins() // registers the TwelveMonkeys WebP reader when running from a Boot jar
    }

    fun sniff(header: ByteArray): ImageFormat? =
        when {
            header.size >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() -> {
                ImageFormat.JPEG
            }

            header.size >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> {
                ImageFormat.PNG
            }

            header.size >= 12 &&
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> {
                ImageFormat.WEBP
            }

            else -> {
                null
            }
        }

    fun process(
        file: Path,
        declared: ImageFormat,
    ): ProcessedImage {
        val size = Files.size(file)
        if (size == 0L) throw InvalidImageException("The file is empty")
        if (size > MAX_BYTES) throw InvalidImageException("The file is larger than 5 MB")
        val header = Files.newInputStream(file).use { it.readNBytes(12) }
        val format = sniff(header) ?: throw InvalidImageException("Unsupported image format; use JPEG, PNG or WebP")
        if (format != declared) {
            throw InvalidImageException("The file content is ${format.name}, but it was sent as ${declared.name}")
        }
        val reader =
            ImageIO.getImageReadersByFormatName(format.imageIoName).asSequence().firstOrNull()
                ?: error("No ImageIO reader for ${format.name}")
        try {
            ImageIO.createImageInputStream(file.toFile()).use { input ->
                reader.setInput(input, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0) throw InvalidImageException("The image has no pixels")
                if (width > MAX_SIDE || height > MAX_SIDE || width.toLong() * height > MAX_PIXELS) {
                    throw InvalidImageException("The image is too large (at most 25 megapixels and $MAX_SIDE px per side)")
                }
                val (thumbWidth, thumbHeight) = thumbnailSize(width, height)
                val decoded = decodeStrictly(reader, subsampling(width, height, thumbWidth, thumbHeight))
                return ProcessedImage(format, width, height, scaleDown(decoded, thumbWidth, thumbHeight))
            }
        } catch (ex: InvalidImageException) {
            throw ex
        } catch (_: IOException) {
            // includes IIOException from malformed data
            throw InvalidImageException("The file is not a valid ${format.name} image")
        } catch (_: RuntimeException) {
            // some decoders signal corrupt input with unchecked exceptions
            throw InvalidImageException("The file is not a valid ${format.name} image")
        } finally {
            reader.dispose()
        }
    }

    /** Keeps the whole image (no cropping): scaled to fit 320 px wide and at most 960 px tall. */
    fun thumbnailSize(
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        val scale = minOf(1.0, THUMBNAIL_WIDTH.toDouble() / width, THUMBNAIL_MAX_HEIGHT.toDouble() / height)
        return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
    }

    /** Reads every n-th pixel, choosing n so the decoded image is still at least twice the thumbnail size. */
    private fun subsampling(
        width: Int,
        height: Int,
        thumbWidth: Int,
        thumbHeight: Int,
    ): Int = maxOf(1, minOf(width / (2 * thumbWidth), height / (2 * thumbHeight)))

    private fun decodeStrictly(
        reader: ImageReader,
        step: Int,
    ): BufferedImage {
        var warning: String? = null
        // e.g. the JDK JPEG reader reports truncated data as a warning, not an exception
        reader.addIIOReadWarningListener(IIOReadWarningListener { _, message -> warning = message })
        val param = reader.defaultReadParam
        param.setSourceSubsampling(step, step, 0, 0)
        val image = reader.read(0, param)
        if (warning != null) throw InvalidImageException("The file is not a valid image")
        return image
    }

    /** Halves the image until it is close to the target, then draws it at the exact size. */
    private fun scaleDown(
        source: BufferedImage,
        width: Int,
        height: Int,
    ): BufferedImage {
        var image = source
        // One big bilinear jump skips pixels and looks jagged; halving in steps keeps it smooth.
        while (image.width / 2 >= width && image.height / 2 >= height) {
            image = draw(image, image.width / 2, image.height / 2)
        }
        return draw(image, width, height)
    }

    private fun draw(
        source: BufferedImage,
        width: Int,
        height: Int,
    ): BufferedImage {
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = target.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            // Thumbnails are JPEG, which has no transparency: transparent areas become white.
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)
            g.drawImage(source, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }
        return target
    }
}
