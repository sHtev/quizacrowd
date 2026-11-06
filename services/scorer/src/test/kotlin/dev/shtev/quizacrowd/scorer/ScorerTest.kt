package dev.shtev.quizacrowd.scorer

import dev.shtev.quizacrowd.common.AnswerSubmitted
import dev.shtev.quizacrowd.common.LeaderboardUpdated
import dev.shtev.quizacrowd.common.QuizEvent
import dev.shtev.quizacrowd.common.Standing
import dev.shtev.quizacrowd.common.ValkeyKeys
import dev.shtev.quizacrowd.common.points
import dev.shtev.quizacrowd.common.quizJson
import io.ktor.server.testing.testApplication
import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.RedisPubSubAdapter
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringSerializer
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.redpanda.RedpandaContainer
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScorerTest {
    companion object {
        const val GROUP = "scorer"
        const val TOPIC = "answers"
        val partitions = (0 until 6).map { TopicPartition(TOPIC, it) }

        val postgres = PostgreSQLContainer("postgres:17-alpine").apply { start() }
        val valkey = GenericContainer("valkey/valkey:8-alpine").withExposedPorts(6379).apply { start() }
        val redpanda = RedpandaContainer("redpandadata/redpanda:v26.2.6").apply { start() }
        val valkeyUrl = "redis://${valkey.host}:${valkey.getMappedPort(6379)}"
        private val redisClient = RedisClient.create(valkeyUrl)
        val redis = redisClient.connect().sync()
        val admin: Admin = Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to redpanda.bootstrapServers))
        val producer = KafkaProducer<String, String>(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to redpanda.bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            )
        )

        init {
            // quiz-api owns the schema; its migrations are on the test classpath.
            Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).load().migrate()
            // ingest creates this topic in a real deployment.
            admin.createTopics(listOf(NewTopic(TOPIC, 6, 1))).all().get()
        }

        private val T0: Instant = Instant.parse("2026-11-05T20:00:00Z")
        private fun at(offsetMs: Long) = T0.toEpochMilli() + offsetMs
    }

    private fun config() = Config(
        databaseUrl = postgres.jdbcUrl,
        databaseUser = postgres.username,
        databasePassword = postgres.password,
        valkeyUrl = valkeyUrl,
        kafkaBootstrap = redpanda.bootstrapServers,
    )

    private val quizId = UUID.randomUUID().toString()
    /** Question 0: correct answer 1, open T0..T0+20s. Question 1: correct answer 2, open T0+25s..T0+45s. */
    private val q0 = UUID.randomUUID().toString()
    private val q1 = UUID.randomUUID().toString()

    @BeforeEach
    fun seedQuiz() {
        sql("truncate tenants, quizzes, questions, players, answers cascade")
        redis.flushall()
        sql("insert into tenants (slug, name, host_key_hash) values ('redlion', 'The Red Lion', 'x')")
        sql("insert into quizzes (id, tenant_slug, name, mode, state, current_index) values ('$quizId', 'redlion', 'Quiz', 'PUB', 'QUESTION_OPEN', 1)")
        question(q0, idx = 0, correct = 1, startMs = 0)
        question(q1, idx = 1, correct = 2, startMs = 25_000)
    }

    private fun question(id: String, idx: Int, correct: Int, startMs: Long) = sql(
        "insert into questions (id, quiz_id, idx, text, options, correct_index, started_at, deadline) values " +
            "('$id', '$quizId', $idx, 'Q$idx', '[\"a\",\"b\",\"c\",\"d\"]', $correct, '${ts(startMs)}', '${ts(startMs + 20_000)}')"
    )

    private fun ts(offsetMs: Long) = Instant.ofEpochMilli(at(offsetMs)).atOffset(ZoneOffset.UTC)

    /** A player who has joined the quiz, as quiz-api records them. */
    private fun player(name: String, quiz: String = quizId): String {
        val id = UUID.randomUUID().toString()
        sql("insert into players (id, quiz_id, name) values ('$id', '$quiz', '$name')")
        redis.hset(ValkeyKeys.names(quiz), id, name)
        return id
    }

    private fun answer(player: String, question: String, choice: Int, receivedAfterMs: Long) =
        AnswerSubmitted(quizId, question, player, choice, at(receivedAfterMs))

    private fun send(vararg answers: AnswerSubmitted) {
        for (a in answers) producer.send(ProducerRecord(TOPIC, a.quizId, quizJson.encodeToString(a)))
        producer.flush()
    }

    private fun sendRaw(value: String) {
        producer.send(ProducerRecord(TOPIC, quizId, value))
        producer.flush()
    }

    private fun scorer(block: () -> Unit) = testApplication {
        application { module(config()) }
        startApplication()
        block()
    }

    private fun sql(query: String): List<List<String?>> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { s ->
                if (!s.execute(query)) return emptyList()
                val rs = s.resultSet
                buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) }
            }
        }

    private fun leaderboard(): Map<String, Long> =
        redis.zrangeWithScores(ValkeyKeys.leaderboard(quizId), 0, -1).associate { it.value to it.score.toLong() }

    private fun rows() = sql("select player_id, question_id, choice, points from answers order by points desc, player_id")

    /** Retries [check] until it stops throwing an assertion error, or the time runs out. */
    private fun <T> eventually(timeoutMs: Long = 15_000, check: () -> T): T {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                return check()
            } catch (e: AssertionError) {
                if (System.currentTimeMillis() > until) throw e
                Thread.sleep(100)
            }
        }
    }

    /** Waits until the scorer has committed everything on the topic. */
    private fun awaitCaughtUp() = eventually {
        val ends = admin.listOffsets(partitions.associateWith { OffsetSpec.latest() }).all().get()
        val committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get()
        for (p in partitions) assertEquals(ends.getValue(p).offset(), committed[p]?.offset() ?: 0L, "partition $p")
    }

    private class Events(channel: String) : AutoCloseable {
        private val queue = LinkedBlockingQueue<QuizEvent>()
        private val connection = redisClient.connectPubSub().apply {
            addListener(object : RedisPubSubAdapter<String, String>() {
                override fun message(channel: String, message: String) {
                    queue.add(quizJson.decodeFromString(message))
                }
            })
            sync().subscribe(channel)
        }

        /** Every event received until one matches [done]; fails after 15 s. */
        fun until(done: (QuizEvent) -> Boolean): List<QuizEvent> = buildList {
            val until = System.currentTimeMillis() + 15_000
            while (true) {
                val left = until - System.currentTimeMillis()
                val event = queue.poll(left.coerceAtLeast(0), TimeUnit.MILLISECONDS) ?: error("no matching event; got $this")
                add(event)
                if (done(event)) break
            }
        }

        fun drain(waitMs: Long): List<QuizEvent> = buildList {
            Thread.sleep(waitMs)
            queue.drainTo(this)
        }

        override fun close() = connection.close()
    }

    @Test
    fun correctAnswerScored() = scorer {
        val ada = player("Ada")
        val bob = player("Bob")
        Events(ValkeyKeys.events(quizId)).use { events ->
            send(answer(ada, q0, choice = 1, receivedAfterMs = 5_000), answer(bob, q0, choice = 0, receivedAfterMs = 4_000))

            val adaPoints = points(true, at(5_000), at(0), at(20_000))
            assertEquals(875, adaPoints)
            val last = events.until { it is LeaderboardUpdated && it.answered == 2 }.last() as LeaderboardUpdated
            assertEquals(listOf(Standing("Ada", 875, rank = 1), Standing("Bob", 0, rank = 2)), last.top)
            assertEquals(listOf(listOf(ada, q0, "1", "875"), listOf(bob, q0, "0", "0")), rows())
            assertEquals(mapOf(ada to 875L, bob to 0L), leaderboard())
        }
    }

    @Test
    fun playersOnTheSameScoreShareARank() = scorer {
        // Answers 3 ms apart round to the same points; neither player should be ranked above the other.
        val zoe = player("Zoe")
        val ada = player("Ada")
        val bob = player("Bob")
        Events(ValkeyKeys.events(quizId)).use { events ->
            send(
                answer(zoe, q0, choice = 1, receivedAfterMs = 5_000),
                answer(ada, q0, choice = 1, receivedAfterMs = 5_003),
                answer(bob, q0, choice = 0, receivedAfterMs = 4_000),
            )
            val last = events.until { it is LeaderboardUpdated && it.answered == 3 }.last() as LeaderboardUpdated
            assertEquals(
                listOf(Standing("Ada", 875, rank = 1), Standing("Zoe", 875, rank = 1), Standing("Bob", 0, rank = 3)),
                last.top,
            )
        }
    }

    @Test
    fun duplicateAnswerCountedOnce() = scorer {
        val ada = player("Ada")
        send(answer(ada, q0, choice = 1, receivedAfterMs = 5_000), answer(ada, q0, choice = 0, receivedAfterMs = 6_000))
        awaitCaughtUp()
        assertEquals(listOf(listOf(ada, q0, "1", "875")), rows(), "first answer wins")
        assertEquals(mapOf(ada to 875L), leaderboard())
    }

    @Test
    fun redeliveryDoesNotDoubleCount() {
        val ada = player("Ada")
        val bob = player("Bob")
        scorer {
            send(
                answer(ada, q0, choice = 1, receivedAfterMs = 5_000),
                answer(bob, q0, choice = 1, receivedAfterMs = 10_000),
                answer(ada, q1, choice = 2, receivedAfterMs = 30_000),
            )
            awaitCaughtUp()
        }
        val rowsBefore = rows()
        val leaderboardBefore = leaderboard()
        assertEquals(3, rowsBefore.size)

        // Rewind the group as if the scorer had crashed before committing.
        admin.alterConsumerGroupOffsets(GROUP, partitions.associateWith { OffsetAndMetadata(0) }).all().get()
        scorer { awaitCaughtUp() }

        assertEquals(rowsBefore, rows())
        assertEquals(leaderboardBefore, leaderboard())
    }

    @Test
    fun leaderboardRepairedIfValkeyMissedAnUpdate() = scorer {
        // As if a scorer stored the answer, then died before updating Valkey.
        val ada = player("Ada")
        sql("insert into answers (question_id, player_id, choice, received_at, points) values ('$q0', '$ada', 1, '${ts(5_000)}', 875)")
        send(answer(ada, q0, choice = 1, receivedAfterMs = 5_000))
        awaitCaughtUp()
        assertEquals(mapOf(ada to 875L), leaderboard())
        assertEquals(1, rows().size)
    }

    @Test
    fun laggedAnswerScoredAgainstItsOwnQuestion() = scorer {
        // Question 1 is already open when these answers to question 0 are scored.
        val ada = player("Ada")
        val bob = player("Bob")
        send(
            answer(ada, q0, choice = 1, receivedAfterMs = 19_000),
            answer(bob, q0, choice = 1, receivedAfterMs = 26_000),
        )
        awaitCaughtUp()
        assertEquals(mapOf(ada to points(true, at(19_000), at(0), at(20_000)).toLong(), bob to 0L), leaderboard())
        assertEquals(525L, leaderboard()[ada])
    }

    @Test
    fun unusableAnswersSkipped() = scorer {
        val ada = player("Ada")
        val otherQuiz = UUID.randomUUID().toString()
        sql("insert into quizzes (id, tenant_slug, name, mode, state) values ('$otherQuiz', 'redlion', 'Other', 'PUB', 'LOBBY')")
        val stranger = player("Eve", quiz = otherQuiz)

        sendRaw("not json")
        send(
            answer(UUID.randomUUID().toString(), q0, choice = 1, receivedAfterMs = 1_000), // never joined
            answer(ada, UUID.randomUUID().toString(), choice = 1, receivedAfterMs = 1_000), // no such question
            answer(stranger, q0, choice = 1, receivedAfterMs = 1_000), // joined a different quiz
            answer(ada, q0, choice = 1, receivedAfterMs = 5_000),
        )
        awaitCaughtUp()
        assertEquals(listOf(listOf(ada, q0, "1", "875")), rows())
        assertEquals(mapOf(ada to 875L), leaderboard())
    }

    @Test
    fun leaderboardPublishesAreThrottled() = scorer {
        val players = List(200) { player("Player $it") }
        // 50 ms apart is at least one point apart, so there are no ties to order arbitrarily.
        Events(ValkeyKeys.events(quizId)).use { events ->
            val start = System.currentTimeMillis()
            send(*players.mapIndexed { i, p -> answer(p, q0, choice = 1, receivedAfterMs = 1_000L + i * 50) }.toTypedArray())
            assertTrue(System.currentTimeMillis() - start < 1_000, "answers should go out in a burst")

            val published = events.until { it is LeaderboardUpdated && it.answered == 200 } + events.drain(waitMs = 1_000)
            assertTrue(published.size <= 2, "expected at most 2 leaderboard events, got ${published.size}")
            val last = published.last() as LeaderboardUpdated
            assertEquals(200, last.answered)
            assertEquals(Standing("Player 0", points(true, at(1_000), at(0), at(20_000)).toLong(), rank = 1), last.top.first())
            assertEquals(10, last.top.size)
        }
    }
}
