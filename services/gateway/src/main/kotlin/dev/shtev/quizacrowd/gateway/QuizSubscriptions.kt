package dev.shtev.quizacrowd.gateway

import dev.shtev.quizacrowd.common.LeaderboardUpdated
import dev.shtev.quizacrowd.common.QuizEvent
import dev.shtev.quizacrowd.common.ValkeyKeys
import dev.shtev.quizacrowd.common.quizJson
import dev.shtev.quizacrowd.common.standingOf
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger(QuizSubscriptions::class.java)

/**
 * This pod's WebSocket connections, grouped by quiz. Each quiz with at least one local connection has
 * exactly one Valkey subscription, however many connections share it: subscribed when the first
 * connects, unsubscribed when the last leaves.
 */
class QuizSubscriptions(
    private val pubSub: StatefulRedisPubSubConnection<String, String>,
    private val valkey: RedisAsyncCommands<String, String>,
    private val scope: CoroutineScope,
) {
    private class Quiz(val id: String) {
        val connections: MutableSet<Connection> = ConcurrentHashMap.newKeySet()
    }

    // Keyed by channel. Joins and leaves take the mutex so subscribing and unsubscribing can't interleave;
    // delivery reads without it, from Lettuce's I/O thread.
    private val quizzes = ConcurrentHashMap<String, Quiz>()
    private val mutex = Mutex()

    init {
        pubSub.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String, message: String) = deliver(channel, message)
        })
    }

    suspend fun join(connection: Connection) = mutex.withLock {
        val channel = ValkeyKeys.events(connection.viewer.quizId)
        val existing = quizzes[channel]
        if (existing != null) {
            existing.connections += connection
            return@withLock
        }
        quizzes[channel] = Quiz(connection.viewer.quizId).apply { connections += connection }
        try {
            pubSub.async().subscribe(channel).await()
        } catch (e: Exception) {
            quizzes.remove(channel)
            throw e
        }
    }

    suspend fun leave(connection: Connection) = mutex.withLock {
        val channel = ValkeyKeys.events(connection.viewer.quizId)
        val quiz = quizzes[channel] ?: return@withLock
        quiz.connections -= connection
        if (quiz.connections.isNotEmpty()) return@withLock
        quizzes.remove(channel)
        pubSub.async().unsubscribe(channel).await()
    }

    /** Runs on Lettuce's I/O thread, so it only queues frames; standings are looked up in [scope]. */
    private fun deliver(channel: String, json: String) {
        val quiz = quizzes[channel] ?: return
        quiz.connections.forEach { it.offer(json) }

        val event = runCatching { quizJson.decodeFromString<QuizEvent>(json) }.getOrNull()
        if (event == null) log.warn("unreadable event on {}", channel)
        if (event is LeaderboardUpdated) sendStandings(quiz)
    }

    /** Each player connection gets its own rank; Lettuce pipelines the lookups on one connection. */
    private fun sendStandings(quiz: Quiz) {
        for (connection in quiz.connections) {
            val player = connection.viewer as? Viewer.Player ?: continue
            scope.launch {
                val standing = valkey.standingOf(quiz.id, player.playerId)
                connection.offer(quizJson.encodeToString<QuizEvent>(standing))
            }
        }
    }
}

