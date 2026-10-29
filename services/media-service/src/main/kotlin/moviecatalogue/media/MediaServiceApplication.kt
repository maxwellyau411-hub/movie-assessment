package moviecatalogue.media

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class MediaServiceApplication

fun main(args: Array<String>) {
    runApplication<MediaServiceApplication>(*args)
}
