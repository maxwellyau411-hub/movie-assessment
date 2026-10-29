package moviecatalogue.catalogue

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClock(
    var now: Instant,
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}

@TestConfiguration
class TestClockConfig {
    @Bean
    @Primary
    fun testClock() = MutableClock(CatalogueIntegrationTest.FIXED_NOW)
}

/**
 * Service-level tests on real PostgreSQL, migrated by Flyway. Tests create their own rows with
 * fresh IDs instead of relying on an empty database. media-service is [FakeMediaService], over
 * in-process gRPC.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureTestGrpcTransport
@Import(TestClockConfig::class, FakeMediaConfig::class)
abstract class CatalogueIntegrationTest {
    companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-10-25T00:00:00Z")

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) = TestDatabase.register(registry)
    }

    @Autowired
    protected lateinit var clock: MutableClock

    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun resetClock() {
        clock.now = FIXED_NOW
    }

    protected fun count(
        sql: String,
        vararg args: Any,
    ): Int = jdbc.queryForObject(sql, Int::class.java, *args)!!
}
