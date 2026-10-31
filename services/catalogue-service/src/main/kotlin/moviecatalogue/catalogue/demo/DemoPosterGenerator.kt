package moviecatalogue.catalogue.demo

import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO

/**
 * Draws placeholder posters in code: a gradient derived from the movie ID plus the title text.
 * No third-party images or fonts are bundled (the JRE's DejaVu fonts are used), so the artwork is
 * freely redistributable.
 */
object DemoPosterGenerator {
    const val WIDTH = 600
    const val HEIGHT = 900

    fun png(
        movieId: UUID,
        title: String,
    ): ByteArray {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val hue = (movieId.mostSignificantBits ushr 40).toFloat() / (1L shl 24)
            g.paint = GradientPaint(0f, 0f, Color.getHSBColor(hue, 0.55f, 0.55f), 0f, HEIGHT.toFloat(), Color.getHSBColor(hue, 0.7f, 0.12f))
            g.fillRect(0, 0, WIDTH, HEIGHT)

            g.color = Color.WHITE
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 56)
            var y = 360
            for (line in wrap(title, g.fontMetrics::stringWidth, WIDTH - 96)) {
                g.drawString(line, 48, y)
                y += 68
            }
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 22)
            g.color = Color(255, 255, 255, 170)
            g.drawString("Demo poster · generated placeholder", 48, HEIGHT - 48)
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun wrap(
        text: String,
        width: (String) -> Int,
        maxWidth: Int,
    ): List<String> =
        text.split(' ').fold(mutableListOf<String>()) { lines, word ->
            val candidate = if (lines.isEmpty()) word else "${lines.last()} $word"
            if (lines.isNotEmpty() && width(candidate) <= maxWidth) lines[lines.lastIndex] = candidate else lines += word
            lines
        }
}
