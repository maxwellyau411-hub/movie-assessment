package moviecatalogue.media

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.nio.file.Path
import java.time.Duration

/**
 * Serves stored images as plain files: /media/artwork/<id>.<ext> and /media/thumbnails/<id>.jpg.
 * A stored file never changes (a new upload gets a new ID), so browsers may cache it for a year
 * without asking again. Spring's resource handler also refuses paths outside these folders.
 */
@Configuration
class MediaWebConfiguration(
    private val files: MediaFiles,
) : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val cache = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()
        registry.addResourceHandler("/media/artwork/**").addResourceLocations(location(files.originals)).setCacheControl(cache)
        registry.addResourceHandler("/media/thumbnails/**").addResourceLocations(location(files.thumbnails)).setCacheControl(cache)
    }

    /** Browsers must trust the Content-Type (from the file extension) and never guess another. */
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry
            .addInterceptor(
                object : HandlerInterceptor {
                    override fun preHandle(
                        request: HttpServletRequest,
                        response: HttpServletResponse,
                        handler: Any,
                    ): Boolean {
                        response.setHeader("X-Content-Type-Options", "nosniff")
                        return true
                    }
                },
            ).addPathPatterns("/media/**")
    }

    private fun location(folder: Path) = "file:${folder.toAbsolutePath()}/"
}
