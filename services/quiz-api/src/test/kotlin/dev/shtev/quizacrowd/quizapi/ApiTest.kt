package dev.shtev.quizacrowd.quizapi

import dev.shtev.quizacrowd.common.QuizEvent
import dev.shtev.quizacrowd.common.quizJson
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.lettuce.core.RedisClient
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.pubsub.RedisPubSubAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.sql.DriverManager
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Shared set-up for quiz-api tests: one Postgres and one Valkey container for the whole run
 * (Testcontainers' "singleton container" pattern), wiped before each test.
 */
abstract class ApiTest {
    companion object {
        val postgres = PostgreSQLContainer("postgres:17-alpine").apply { start() }
        val valkey = GenericContainer("valkey/valkey:8-alpine").withExposedPorts(6379).apply { start() }
        val valkeyUrl = "redis://${valkey.host}:${valkey.getMappedPort(6379)}"

        private val redisClient: RedisClient = RedisClient.create(valkeyUrl)
        /** Direct Valkey access for seeding and checking state. */
        val redis: RedisCommands<String, String> = redisClient.connect().sync()

        private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        private fun pem(type: String, der: ByteArray) =
            "-----BEGIN $type-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END $type-----\n"
        val privatePem = pem("PRIVATE KEY", keys.private.encoded)
        val publicPem = pem("PUBLIC KEY", keys.public.encoded)
        const val ADMIN = "admin-secret"
    }

    protected val sample = javaClass.getResource("/quizzer-sample.json")!!.readText()

    protected fun config() = Config(
        baseDomain = "localhost",
        databaseUrl = postgres.jdbcUrl,
        databaseUser = postgres.username,
        databasePassword = postgres.password,
        valkeyUrl = valkeyUrl,
        adminToken = ADMIN,
        joinTokenPrivateKeyPem = privatePem,
    )

    @BeforeEach
    fun cleanStores() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { s ->
                val hasSchema = s.executeQuery("select to_regclass('public.tenants') is not null").let { it.next(); it.getBoolean(1) }
                if (hasSchema) s.execute("truncate tenants, quizzes, questions, players, answers cascade")
            }
        }
        redis.flushall()
    }

    protected fun api(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(config()) }
        block()
    }

    protected suspend fun HttpClient.createTenant(slug: String, token: String? = ADMIN): HttpResponse =
        post("/admin/tenants") {
            header(HttpHeaders.Host, "admin.localhost")
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"$slug","name":"The ${slug.replaceFirstChar { it.uppercase() }}"}""")
        }

    protected suspend fun HttpClient.hostKey(slug: String): String =
        Json.parseToJsonElement(createTenant(slug).bodyAsText()).jsonObject["hostKey"]!!.jsonPrimitive.content

    protected suspend fun HttpClient.importQuiz(tenant: String, key: String, body: String = sample): HttpResponse =
        post("/api/quizzes?mode=PUB") {
            header(HttpHeaders.Host, "$tenant.localhost")
            bearerAuth(key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    protected suspend fun HttpClient.quizId(tenant: String, key: String): String =
        Json.parseToJsonElement(importQuiz(tenant, key).bodyAsText()).jsonObject["quizId"]!!.jsonPrimitive.content

    protected suspend fun HttpClient.join(quizId: String, name: String, tenant: String = "redlion"): HttpResponse =
        post("/api/quizzes/$quizId/join") {
            header(HttpHeaders.Host, "$tenant.localhost")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(mapOf("name" to name)))
        }

    protected fun sql(query: String): List<List<String?>> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { s ->
                val rs = s.executeQuery(query)
                buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) }
            }
        }

    /** Listens on a Valkey pub/sub channel; subscribed by the time this returns. */
    protected class Subscription(channel: String) : AutoCloseable {
        private val messages = LinkedBlockingQueue<String>()
        private val connection = redisClient.connectPubSub().apply {
            addListener(object : RedisPubSubAdapter<String, String>() {
                override fun message(channel: String, message: String) {
                    messages.add(message)
                }
            })
            sync().subscribe(channel)
        }

        fun nextRaw(): String = messages.poll(5, TimeUnit.SECONDS) ?: fail("no event within 5s")
        fun next(): QuizEvent = quizJson.decodeFromString(nextRaw())
        fun assertNoMore() = assertNull(messages.poll(300, TimeUnit.MILLISECONDS), "unexpected event")

        override fun close() = connection.close()
    }
}
