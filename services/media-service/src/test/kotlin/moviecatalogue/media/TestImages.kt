package moviecatalogue.media

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32
import javax.imageio.ImageIO

object TestImages {
    fun png(
        width: Int = 64,
        height: Int = 48,
    ): ByteArray = encode("png", width, height)

    fun jpeg(
        width: Int = 64,
        height: Int = 48,
    ): ByteArray = encode("jpeg", width, height)

    /** 1x1 lossy and lossless WebP files (the well-known Modernizr feature-detection samples). */
    val webpLossy: ByteArray = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA")
    val webpLossless: ByteArray = Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==")

    val svg: ByteArray = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><script>alert(1)</script></svg>""".toByteArray()

    val gif: ByteArray = encode("gif", 8, 8)

    /** A valid PNG header that claims [width]x[height] but carries almost no pixel data. */
    fun pngHeaderClaiming(
        width: Int,
        height: Int,
    ): ByteArray {
        val ihdr =
            ByteBuffer
                .allocate(13)
                .putInt(width)
                .putInt(height)
                .put(8)
                .put(2)
                .put(0)
                .put(0)
                .put(0)
                .array()
        return ByteArrayOutputStream()
            .apply {
                write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
                write(chunk("IHDR", ihdr))
                write(chunk("IDAT", byteArrayOf(0x78, 0x9C.toByte(), 0x03, 0x00, 0x00, 0x00, 0x00, 0x01)))
                write(chunk("IEND", ByteArray(0)))
            }.toByteArray()
    }

    private fun chunk(
        type: String,
        data: ByteArray,
    ): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc =
            CRC32()
                .apply {
                    update(typeBytes)
                    update(data)
                }.value
                .toInt()
        return ByteBuffer
            .allocate(12 + data.size)
            .putInt(data.size)
            .put(typeBytes)
            .put(data)
            .putInt(crc)
            .array()
    }

    private fun encode(
        format: String,
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, ((x * 4 % 256) shl 16) or ((y * 5 % 256) shl 8) or 0x40)
        return ByteArrayOutputStream().also { check(ImageIO.write(image, format, it)) { "no writer for $format" } }.toByteArray()
    }
}
