package dev.shtev.quizacrowd.ingest

import dev.shtev.quizacrowd.common.AnswerSubmitted
import dev.shtev.quizacrowd.common.JoinClaims
import dev.shtev.quizacrowd.common.JoinTokenIssuer
import dev.shtev.quizacrowd.common.ValkeyKeys
import dev.shtev.quizacrowd.common.quizJson
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.lettuce.core.RedisClient
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.GenericContainer
import org.testcontainers.redpanda.RedpandaContainer
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class IngestTest {
    companion object {
        val redpanda = RedpandaContainer("redpandadata/redpanda:v26.2.6").apply { start() }
        val valkey = GenericContainer("valkey/valkey:8-alpine").withExposedPorts(6379).apply { start() }
        val valkeyUrl = "redis://${valkey.host}:${valkey.getMappedPort(6379)}"
        val redis = RedisClient.create(valkeyUrl).connect().sync()

        private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        private fun pem(type: String, der: ByteArray) =
            "-----BEGIN $type-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END $type-----\n"
        val privatePem = pem("PRIVATE KEY", keys.private.encoded)
        val publicPem = pem("PUBLIC KEY", keys.public.encoded)
    }

    private val tokens = JoinTokenIssuer(privatePem)
    private val quizId = UUID.randomUUID().toString()
    private val questionId = UUID.randomUUID().toString()
    private val claims = JoinClaims(playerId = UUID.randomUUID().toString(), quizId = quizId, tenant = "redlion", name = "Ada")
    private lateinit var consumer: KafkaConsumer<String, String>

    private fun config() = Config(
        baseDomain = "localhost",
        valkeyUrl = valkeyUrl,
        kafkaBootstrap = redpanda.bootstrapServers,
        joinTokenPublicKeyPem = publicPem,
    )

    @BeforeEach
    fun startAtTheEndOfTheTopic() {
        redis.flushall()
        ensureTopic(redpanda.bootstrapServers)
        consumer = KafkaConsumer(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to redpanda.bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            )
        )
        val partitions = (0 until 6).map { TopicPartition(ANSWERS_TOPIC, it) }
        consumer.assign(partitions)
        consumer.seekToEnd(partitions)
        partitions.forEach { consumer.position(it) } // seekToEnd is lazy; this fixes the positions now
    }

    @AfterEach
    fun closeConsumer() = consumer.close()

    /** Everything queued since the test started, waiting up to [wait] for at least [atLeast] records. */
    private fun queued(atLeast: Int = 1, wait: Duration = Duration.ofSeconds(5)): List<ConsumerRecord<String, String>> {
        val records = mutableListOf<ConsumerRecord<String, String>>()
        val until = Instant.now() + wait
        while (Instant.now() < until && records.size < atLeast) records += consumer.poll(Duration.ofMillis(200))
        records += consumer.poll(Duration.ofMillis(300)) // catch anything extra
        return records
    }

    private fun openQuestion(deadline: Instant = Instant.now().plusSeconds(20), optionCount: Int = 4) {
        redis.hset(
            ValkeyKeys.currentQuestion(quizId),
            mapOf(
                "questionId" to questionId,
                "index" to "0",
                "total" to "4",
                "startedAt" to deadline.minusSeconds(20).toEpochMilli().toString(),
                "deadline" to deadline.toEpochMilli().toString(),
                "optionCount" to optionCount.toString(),
                "text" to "Which of these companies does NOT manufacture automobiles?",
                "options" to """["Nissan","Ducati","GMC","Fiat"]""",
            ),
        )
    }

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(config()) }
        block()
    }

    private suspend fun HttpClient.answer(
        token: String?,
        choice: Int = 2,
        question: String = questionId,
        tenant: String = "redlion",
    ): HttpResponse = post("/answers") {
        header(HttpHeaders.Host, "$tenant.localhost")
        token?.let { bearerAuth(it) }
        contentType(ContentType.Application.Json)
        setBody("""{"questionId":"$question","choice":$choice}""")
    }

    @Test
    fun acceptedAnswerIsQueued() = api {
        openQuestion()
        val before = System.currentTimeMillis()
        assertEquals(HttpStatusCode.Accepted, client.answer(tokens.issue(claims), choice = 2).status)
        val after = System.currentTimeMillis()

        val record = queued().single()
        assertEquals(quizId, record.key())
        val answer = quizJson.decodeFromString<AnswerSubmitted>(record.value())
        assertEquals(AnswerSubmitted(quizId, questionId, claims.playerId, 2, answer.receivedAtEpochMs), answer)
        assertTrue(answer.receivedAtEpochMs in before..after, "received at ${answer.receivedAtEpochMs}, request $before..$after")
    }

    @Test
    fun badTokenRejected() = api {
        openQuestion()
        val expired = JoinTokenIssuer(privatePem, Clock.fixed(Instant.now().minusSeconds(13 * 3600), ZoneOffset.UTC))
            .issue(claims, ttl = 12.hours)
        val otherKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val forged = JoinTokenIssuer(pem("PRIVATE KEY", otherKey.private.encoded)).issue(claims)

        assertEquals(HttpStatusCode.Unauthorized, client.answer(token = null).status, "missing")
        assertEquals(HttpStatusCode.Unauthorized, client.answer("not-a-token").status, "garbage")
        assertEquals(HttpStatusCode.Unauthorized, client.answer(expired).status, "expired")
        assertEquals(HttpStatusCode.Unauthorized, client.answer(forged).status, "signed by another key")
        assertEquals(HttpStatusCode.Unauthorized, client.answer(tokens.issue(claims), tenant = "crown").status, "other tenant's host")
        assertEquals(emptyList(), queued(wait = Duration.ofMillis(500)))
    }

    @Test
    fun lateAnswerRejected() = api {
        val token = tokens.issue(claims)
        assertEquals(HttpStatusCode.Conflict, client.answer(token).status, "no question open")

        openQuestion()
        assertEquals(HttpStatusCode.Conflict, client.answer(token, question = UUID.randomUUID().toString()).status, "not the current question")

        openQuestion(deadline = Instant.now().minusMillis(1))
        assertEquals(HttpStatusCode.Conflict, client.answer(token).status, "after the deadline")
        assertEquals(emptyList(), queued(wait = Duration.ofMillis(500)))
    }

    @Test
    fun invalidChoiceRejected() = api {
        openQuestion(optionCount = 3)
        val token = tokens.issue(claims)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.answer(token, choice = 3).status)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.answer(token, choice = -1).status)
        assertEquals(emptyList(), queued(wait = Duration.ofMillis(500)))
    }

    @Test
    fun malformedBodyRejected() = api {
        openQuestion()
        val response = client.post("/answers") {
            header(HttpHeaders.Host, "redlion.localhost")
            bearerAuth(tokens.issue(claims))
            contentType(ContentType.Application.Json)
            setBody("""{"choice":"two"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun topicHasSixPartitions() {
        ensureTopic(redpanda.bootstrapServers) // a second call is a no-op
        Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to redpanda.bootstrapServers)).use { admin ->
            val topic = admin.describeTopics(listOf(ANSWERS_TOPIC)).allTopicNames().get().getValue(ANSWERS_TOPIC)
            assertEquals(6, topic.partitions().size)
        }
    }
}
