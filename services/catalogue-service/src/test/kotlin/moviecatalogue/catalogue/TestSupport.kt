package moviecatalogue.catalogue

import moviecatalogue.catalogue.common.SaveResult
import moviecatalogue.catalogue.movie.MovieInput
import moviecatalogue.catalogue.movie.MovieService
import moviecatalogue.catalogue.person.PersonInput
import moviecatalogue.catalogue.person.PersonService
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO

fun <T> SaveResult<T, *>.saved(): T = (this as? SaveResult.Saved)?.value ?: error("Expected a save, got $this")

// Tests share one database, so similar names recur across tests. These helpers confirm the
// duplicate warning up front; the warning itself is tested explicitly in DuplicateDetectionTest.

fun MovieService.createConfirmed(input: MovieInput) = create(input, confirmDuplicate = true).saved()

fun MovieService.updateConfirmed(
    id: UUID,
    expectedVersion: Long,
    input: MovieInput,
) = update(id, expectedVersion, input, confirmDuplicate = true).saved()

fun PersonService.createConfirmed(input: PersonInput) = create(input, confirmDuplicate = true).saved()

fun PersonService.updateConfirmed(
    id: UUID,
    expectedVersion: Long,
    input: PersonInput,
) = update(id, expectedVersion, input, confirmDuplicate = true).saved()

/** A small real PNG, so the fake media-service can read its dimensions like the real one. */
object TestPng {
    fun bytes(
        width: Int = 60,
        height: Int = 90,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
