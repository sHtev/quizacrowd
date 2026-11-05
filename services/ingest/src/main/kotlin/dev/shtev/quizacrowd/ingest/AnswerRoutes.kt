package dev.shtev.quizacrowd.ingest

import dev.shtev.quizacrowd.common.AnswerSubmitted
import dev.shtev.quizacrowd.common.JoinClaims
import dev.shtev.quizacrowd.common.JoinTokenVerifier
import dev.shtev.quizacrowd.common.ValkeyKeys
import dev.shtev.quizacrowd.common.tenantFromHost
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import java.time.Clock

@Serializable
data class AnswerRequest(val questionId: String, val choice: Int)

fun Route.answerRoutes(
    config: Config,
    verifier: JoinTokenVerifier,
    valkey: RedisAsyncCommands<String, String>,
    producer: AnswerProducer,
    clock: Clock = Clock.systemUTC(),
) {
    post("/answers") {
        // Stamp the time first, so time spent checking doesn't count against the player.
        val receivedAt = clock.millis()
        val player = call.player(config, verifier) ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val request = call.receive<AnswerRequest>()

        val open = valkey.hgetall(ValkeyKeys.currentQuestion(player.quizId)).await().toOpenQuestion()
        when (checkAnswer(open, request.questionId, request.choice, receivedAt)) {
            Verdict.CLOSED -> call.respond(HttpStatusCode.Conflict)
            Verdict.INVALID_CHOICE -> call.respond(HttpStatusCode.UnprocessableEntity)
            Verdict.ACCEPTED -> {
                producer.send(AnswerSubmitted(player.quizId, request.questionId, player.playerId, request.choice, receivedAt))
                call.respond(HttpStatusCode.Accepted)
            }
        }
    }
}

/** The player's join token claims, if the token is valid and was issued for this tenant's hostname. */
private fun ApplicationCall.player(config: Config, verifier: JoinTokenVerifier): JoinClaims? {
    val token = request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
        ?: return null
    val claims = verifier.verify(token) ?: return null
    val tenant = request.headers[HttpHeaders.Host]?.let { tenantFromHost(it, config.baseDomain) }
    return claims.takeIf { it.tenant == tenant }
}

/** HGETALL gives an empty map when the key doesn't exist, i.e. no question is open. */
private fun Map<String, String>.toOpenQuestion(): OpenQuestion? {
    if (isEmpty()) return null
    return OpenQuestion(
        questionId = getValue("questionId"),
        deadlineEpochMs = getValue("deadline").toLong(),
        optionCount = getValue("optionCount").toInt(),
    )
}
