package dev.shtev.quizacrowd.gateway

import dev.shtev.quizacrowd.common.JoinClaims
import dev.shtev.quizacrowd.common.QuizEvent
import dev.shtev.quizacrowd.common.ValkeyKeys
import dev.shtev.quizacrowd.common.questionFromHash
import dev.shtev.quizacrowd.common.quizJson
import dev.shtev.quizacrowd.common.standingOf
import dev.shtev.quizacrowd.common.tenantFromHost
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.send
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

/** Frames a connection can fall behind by before its oldest are dropped. */
private const val OUTBOUND_BUFFER = 64

/** Custom close code: 4000–4999 are free for applications; 4401 echoes HTTP 401. */
private val UNAUTHORISED = CloseReason(4401, "unauthorised")

/**
 * One WebSocket's queue of outgoing JSON frames. A client that can't keep up loses its oldest frames
 * rather than holding up delivery to everyone else.
 */
class Connection(val viewer: Viewer) {
    val outbound = Channel<String>(OUTBOUND_BUFFER, BufferOverflow.DROP_OLDEST)

    fun offer(json: String) {
        outbound.trySend(json)
    }
}

/**
 * GET /ws?token=… for a player's phone, /ws?screen={quizId} for the big screen. Browsers can't set
 * headers on a WebSocket, hence the query string.
 */
fun Route.connectionRoutes(
    config: Config,
    verify: (String) -> JoinClaims?,
    valkey: RedisAsyncCommands<String, String>,
    subscriptions: QuizSubscriptions,
) {
    webSocket("/ws") {
        val tenant = call.request.headers[HttpHeaders.Host]?.let { tenantFromHost(it, config.baseDomain) }
        val params = call.request.queryParameters
        val viewer = viewerFor(params["token"], params["screen"], tenant, verify) ?: return@webSocket close(UNAUTHORISED)

        val connection = Connection(viewer)
        subscriptions.join(connection)
        try {
            // Joined before reading the current state, so nothing published from now on is missed.
            // Anything published in between arrives twice; clients treat events as idempotent.
            for (json in currentState(valkey, viewer)) send(json)
            val pump = launch { for (json in connection.outbound) send(json) }
            try {
                for (frame in incoming) { } // clients send nothing; this waits for them to go
            } finally {
                pump.cancel()
            }
        } finally {
            subscriptions.leave(connection)
        }
    }
}

/** What a new or reconnecting viewer needs straight away: the open question, and a player's own standing. */
private suspend fun currentState(valkey: RedisAsyncCommands<String, String>, viewer: Viewer): List<String> = buildList {
    val question = questionFromHash(valkey.hgetall(ValkeyKeys.currentQuestion(viewer.quizId)).await())
    if (question != null) add(quizJson.encodeToString<QuizEvent>(question))
    if (viewer is Viewer.Player) add(quizJson.encodeToString<QuizEvent>(valkey.standingOf(viewer.quizId, viewer.playerId)))
}
