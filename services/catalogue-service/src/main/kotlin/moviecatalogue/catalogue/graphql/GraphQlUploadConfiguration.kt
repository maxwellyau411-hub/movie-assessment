package moviecatalogue.catalogue.graphql

import graphql.schema.GraphQLScalarType
import name.nkonev.multipart.spring.graphql.coercing.webmvc.UploadCoercing
import name.nkonev.multipart.spring.graphql.server.webmvc.MultipartGraphQlHttpHandler
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.graphql.autoconfigure.GraphQlProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.graphql.execution.RuntimeWiringConfigurer
import org.springframework.graphql.server.WebGraphQlHandler
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.web.servlet.function.RequestPredicates
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.json.JsonMapper

/**
 * File uploads on /graphql (graphql-multipart-request-spec, D11): the request has an `operations`
 * part (the normal GraphQL JSON with the file variable set to null), a `map` part saying which
 * variable each file fills, and the file parts themselves.
 *
 * This is the library's own set-up with one change: its handler throws a plain RuntimeException
 * for a malformed `map` or `operations` part, which Spring turns into HTTP 500. Here that becomes
 * HTTP 400 with a VALIDATION_ERROR body, because the client sent a bad request.
 */
@Configuration(proxyBeanMethods = false)
class GraphQlUploadConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Turns a file part into the `Upload` scalar, which reaches controllers as a MultipartFile. */
    @Bean
    fun uploadScalar(): RuntimeWiringConfigurer {
        val upload =
            GraphQLScalarType
                .newScalar()
                .name("Upload")
                .coercing(UploadCoercing())
                .build()
        return RuntimeWiringConfigurer { it.scalar(upload) }
    }

    /**
     * Ordered before Spring GraphQL's own /graphql route, which would answer 415 to multipart.
     * Only in a web application; service-level tests run without one.
     */
    @Bean
    @Order(-10)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    fun multipartGraphQlRoute(
        properties: GraphQlProperties,
        graphQlHandler: WebGraphQlHandler,
        jsonMapper: JsonMapper,
    ): RouterFunction<ServerResponse> {
        val handler = MultipartGraphQlHttpHandler(graphQlHandler, JacksonJsonHttpMessageConverter(jsonMapper))
        val multipart =
            RequestPredicates
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .and(RequestPredicates.accept(*MultipartGraphQlHttpHandler.SUPPORTED_MEDIA_TYPES.toTypedArray()))
        return RouterFunctions
            .route()
            .POST(properties.http.path, multipart) { request ->
                try {
                    handler.handleMultipartRequest(request)
                } catch (ex: RuntimeException) {
                    log.info("Malformed multipart GraphQL request: {}", ex.message)
                    ServerResponse
                        .status(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(
                            mapOf(
                                "errors" to
                                    listOf(
                                        mapOf(
                                            "message" to "Malformed multipart request: check the operations and map parts",
                                            "extensions" to mapOf("code" to "VALIDATION_ERROR"),
                                        ),
                                    ),
                            ),
                        )
                }
            }.build()
    }
}
