package moviecatalogue.media

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ImageProcessorTest {
    @TempDir
    lateinit var dir: Path

    private fun process(
        bytes: ByteArray,
        declared: ImageFormat,
    ) = ImageProcessor.process(Files.write(dir.resolve("upload"), bytes), declared)

    private fun rejection(
        bytes: ByteArray,
        declared: ImageFormat = ImageFormat.PNG,
    ) = assertThrows<InvalidImageException> { process(bytes, declared) }.message!!

    @Test
    fun `accepts PNG, JPEG and both WebP encodings by content`() {
        for ((bytes, format) in listOf(
            TestImages.png() to ImageFormat.PNG,
            TestImages.jpeg() to ImageFormat.JPEG,
            TestImages.webpLossy to ImageFormat.WEBP,
            TestImages.webpLossless to ImageFormat.WEBP,
        )) {
            val image = process(bytes, format)
            assertEquals(format, image.format)
            assertEquals(if (format == ImageFormat.WEBP) 1 to 1 else 64 to 48, image.width to image.height)
        }
    }

    @Test
    fun `thumbnail is 320 px wide with the whole image kept, never enlarged, and at most 960 px tall`() {
        val poster = process(TestImages.png(600, 900), ImageFormat.PNG).thumbnail
        assertEquals(320 to 480, poster.width to poster.height)

        val wide = process(TestImages.jpeg(2000, 500), ImageFormat.JPEG).thumbnail
        assertEquals(320 to 80, wide.width to wide.height)

        val small = process(TestImages.png(100, 150), ImageFormat.PNG).thumbnail
        assertEquals(100 to 150, small.width to small.height)

        assertEquals(96 to 960, ImageProcessor.thumbnailSize(400, 4000))
        assertEquals(320 to 1, ImageProcessor.thumbnailSize(10_000, 10))
    }

    @Test
    fun `rejects empty files, SVG, GIF and arbitrary bytes`() {
        assertContains(rejection(ByteArray(0)), "empty")
        assertContains(rejection(TestImages.svg), "Unsupported")
        assertContains(rejection(TestImages.gif), "Unsupported")
        assertContains(rejection("hello".toByteArray()), "Unsupported")
    }

    @Test
    fun `rejects a file whose bytes are not the declared type`() {
        assertContains(rejection(TestImages.jpeg(), declared = ImageFormat.PNG), "content is JPEG, but it was sent as PNG")
        assertContains(rejection(TestImages.png(), declared = ImageFormat.WEBP), "content is PNG")
    }

    @Test
    fun `rejects too many pixels from the header alone, before decoding`() {
        assertContains(rejection(TestImages.pngHeaderClaiming(30_000, 30_000)), "too large")
        assertContains(rejection(TestImages.pngHeaderClaiming(10_001, 10)), "too large")
        assertContains(rejection(TestImages.pngHeaderClaiming(5_001, 5_000)), "too large") // 25.005 MP
        // Exactly 25 MP passes the header check; the fake body then fails the strict decode.
        assertContains(rejection(TestImages.pngHeaderClaiming(5_000, 5_000)), "not a valid")
    }

    @Test
    fun `rejects truncated and corrupted images that have a valid signature`() {
        val png = TestImages.png(200, 200)
        val jpeg = TestImages.jpeg(200, 200)
        assertContains(rejection(png.copyOf(png.size / 2)), "not a valid")
        assertContains(rejection(jpeg.copyOf(jpeg.size / 2), ImageFormat.JPEG), "not a valid")
        assertContains(rejection(TestImages.webpLossy.copyOf(20), ImageFormat.WEBP), "not a valid")
        assertContains(rejection(TestImages.png().copyOf(8) + ByteArray(64) { 7 }), "not a valid")
    }

    @Test
    fun `rejects files over 5 MB even if the start is a valid image`() {
        assertContains(rejection(TestImages.png() + ByteArray(ImageProcessor.MAX_BYTES.toInt())), "5 MB")
    }
}
