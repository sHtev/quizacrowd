package dev.shtev.quizacrowd.scorer

import dev.shtev.quizacrowd.common.ANSWERS_TOPIC
import dev.shtev.quizacrowd.common.AnswerSubmitted
import dev.shtev.quizacrowd.common.ValkeyKeys
import dev.shtev.quizacrowd.common.points
import dev.shtev.quizacrowd.common.quizJson
import io.lettuce.core.ScoredValue
import io.lettuce.core.api.sync.RedisCommands
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

private val log = LoggerFactory.getLogger(ScoringConsumer::class.java)

private data class ScoredAnswer(val answer: AnswerSubmitted, val questionId: UUID, val playerId: UUID, val question: AskedQuestion, val points: Int)

// Stores the answer only if the player joined this quiz; a second answer to the same question is ignored.
private const val INSERT_ANSWER = """
    insert into answers (question_id, player_id, choice, received_at, points)
    select ?, ?, ?, ?, ?
    where exists (select 1 from players where id = ? and quiz_id = ?)
    on conflict (question_id, player_id) do nothing
"""

private const val PLAYER_TOTALS = """
    select a.player_id, sum(a.points) from answers a where a.player_id = any(?) group by a.player_id
"""

/**
 * Reads answers off the queue, scores them in Postgres and sets leaderboard totals in Valkey.
 * Offsets are committed only after a batch is fully handled, so a crash replays the batch;
 * replaying is harmless because Postgres keeps one answer per player per question and the
 * Valkey totals are recomputed from Postgres rather than incremented.
 */
class ScoringConsumer(
    config: Config,
    private val dataSource: DataSource,
    private val valkey: RedisCommands<String, String>,
    private val publisher: LeaderboardPublisher,
) {
    private val questions = QuestionCache(dataSource)
    private val consumer = KafkaConsumer<String, String>(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to config.kafkaBootstrap,
            ConsumerConfig.GROUP_ID_CONFIG to config.groupId,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
        )
    )

    /** Polls until [stop] is called. Blocks, so run it on its own thread; any other error ends the loop. */
    fun run() {
        try {
            consumer.subscribe(listOf(ANSWERS_TOPIC))
            while (true) {
                val records = consumer.poll(Duration.ofSeconds(1))
                if (records.isEmpty) continue
                process(records)
                consumer.commitSync()
            }
        } catch (_: WakeupException) {
            // stop() was called
        } finally {
            consumer.close()
        }
    }

    /** Safe from any thread: interrupts poll() so run() returns. */
    fun stop() = consumer.wakeup()

    private fun process(records: ConsumerRecords<String, String>) {
        val scored = records.mapNotNull { score(it.value()) }
        if (scored.isEmpty()) return

        val totals = store(scored)
        val quizOfPlayer = scored.associate { it.playerId to it.answer.quizId }
        for ((quizId, playerTotals) in totals.entries.groupBy { quizOfPlayer.getValue(it.key) }) {
            val values = playerTotals.map { ScoredValue.just(it.value.toDouble(), it.key.toString()) }
            valkey.zadd(ValkeyKeys.leaderboard(quizId), *values.toTypedArray())
        }
        for (s in scored.distinctBy { it.questionId }) {
            publisher.changed(s.answer.quizId, s.questionId, s.question.index)
        }
    }

    /** Null for anything that can't be scored: unreadable, unknown question, or a question from another quiz. */
    private fun score(json: String): ScoredAnswer? {
        val answer = runCatching { quizJson.decodeFromString<AnswerSubmitted>(json) }.getOrNull()
            ?: return skip("unreadable message")
        val questionId = answer.questionId.toUuidOrNull() ?: return skip("bad question id ${answer.questionId}")
        val playerId = answer.playerId.toUuidOrNull() ?: return skip("bad player id ${answer.playerId}")
        val question = questions.get(questionId) ?: return skip("unknown question $questionId")
        if (question.quizId != answer.quizId) return skip("question $questionId is not in quiz ${answer.quizId}")

        val correct = answer.choice == question.correctIndex
        val points = points(correct, answer.receivedAtEpochMs, question.startedAtMs, question.deadlineMs)
        return ScoredAnswer(answer, questionId, playerId, question, points)
    }

    /** Inserts the batch in one transaction and returns each answering player's total. */
    private fun store(scored: List<ScoredAnswer>): Map<UUID, Long> = dataSource.connection.use { c ->
        c.autoCommit = false
        c.prepareStatement(INSERT_ANSWER).use { st ->
            for (s in scored) {
                st.setObject(1, s.questionId)
                st.setObject(2, s.playerId)
                st.setInt(3, s.answer.choice)
                st.setObject(4, Instant.ofEpochMilli(s.answer.receivedAtEpochMs).atOffset(ZoneOffset.UTC))
                st.setInt(5, s.points)
                st.setObject(6, s.playerId)
                st.setObject(7, UUID.fromString(s.answer.quizId))
                st.addBatch()
            }
            st.executeBatch()
        }
        val totals = c.prepareStatement(PLAYER_TOTALS).use { st ->
            st.setArray(1, c.createArrayOf("uuid", scored.map { it.playerId }.distinct().toTypedArray()))
            val rs = st.executeQuery()
            buildMap { while (rs.next()) put(rs.getObject(1, UUID::class.java), rs.getLong(2)) }
        }
        c.commit()
        totals
    }

    private fun skip(reason: String): Nothing? {
        log.warn("skipping answer: {}", reason)
        return null
    }
}

/** Number of answers stored for a question, for the "answered" count on the big screen. */
fun answeredCount(dataSource: DataSource, questionId: UUID): Int = dataSource.connection.use { c ->
    c.prepareStatement("select count(*) from answers where question_id = ?").use { st ->
        st.setObject(1, questionId)
        st.executeQuery().run { next(); getInt(1) }
    }
}

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
