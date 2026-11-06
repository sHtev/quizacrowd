package dev.shtev.quizacrowd.ingest

import dev.shtev.quizacrowd.common.ANSWERS_TOPIC
import dev.shtev.quizacrowd.common.AnswerSubmitted
import dev.shtev.quizacrowd.common.quizJson
import kotlinx.coroutines.suspendCancellableCoroutine
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.serialization.StringSerializer
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val ANSWERS_PARTITIONS = 6

/** Creates the answers topic if it isn't there yet. One broker in Stage 1, so one replica. */
fun ensureTopic(bootstrap: String) {
    Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)).use { admin ->
        try {
            admin.createTopics(listOf(NewTopic(ANSWERS_TOPIC, ANSWERS_PARTITIONS, 1))).all().get()
        } catch (e: ExecutionException) {
            if (e.cause !is TopicExistsException) throw e
        }
    }
}

/** Puts answers on the queue, keyed by quiz so each quiz's answers stay in order on one partition. */
class AnswerProducer(bootstrap: String) : AutoCloseable {
    private val producer = KafkaProducer<String, String>(
        mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "all", // 202 only once the broker has it
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true, // retries can't duplicate a message
            ProducerConfig.LINGER_MS_CONFIG to 5, // wait up to 5 ms to batch a burst of answers
        )
    )

    /** Suspends until the broker acknowledges the answer. */
    suspend fun send(answer: AnswerSubmitted) {
        val record = ProducerRecord(ANSWERS_TOPIC, answer.quizId, quizJson.encodeToString(answer))
        // Kafka reports completion through a callback; this turns it into a suspend call.
        suspendCancellableCoroutine { continuation ->
            producer.send(record) { _, error ->
                if (error == null) continuation.resume(Unit) else continuation.resumeWithException(error)
            }
        }
    }

    override fun close() = producer.close()
}
