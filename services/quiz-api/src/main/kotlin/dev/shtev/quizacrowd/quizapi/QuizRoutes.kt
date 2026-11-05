package dev.shtev.quizacrowd.quizapi

import dev.shtev.quizacrowd.common.JoinClaims
import dev.shtev.quizacrowd.common.JoinTokenIssuer
import dev.shtev.quizacrowd.common.importQuizzer
import dev.shtev.quizacrowd.quizapi.db.Players
import dev.shtev.quizacrowd.quizapi.db.Questions
import dev.shtev.quizacrowd.quizapi.db.Quizzes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

enum class QuizMode { PUB, STADIUM, TELLY }

@Serializable
data class QuizImported(val quizId: String, val imported: Int, val skipped: Int)

@Serializable
data class JoinRequest(val name: String)

@Serializable
data class Joined(val playerId: String, val token: String)

fun Route.quizRoutes(db: Database, config: Config, tokens: JoinTokenIssuer) {
    post("/api/quizzes") {
        val tenant = call.authorisedHost(db, config) ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val mode = call.request.queryParameters["mode"]?.let { m -> QuizMode.entries.find { it.name == m } }
            ?: return@post call.respond(HttpStatusCode.BadRequest)

        val result = try {
            importQuizzer(call.receiveText())
        } catch (e: IllegalArgumentException) {
            return@post call.respond(HttpStatusCode.BadRequest)
        }
        if (result.questions.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest)

        val quizId = UUID.randomUUID()
        db.query {
            Quizzes.insert {
                it[id] = quizId
                it[tenantSlug] = tenant
                it[name] = result.name
                it[Quizzes.mode] = mode.name
                it[state] = "LOBBY"
            }
            Questions.batchInsert(result.questions.withIndex()) { (index, q) ->
                this[Questions.id] = UUID.randomUUID()
                this[Questions.quizId] = quizId
                this[Questions.idx] = index
                this[Questions.text] = q.text
                this[Questions.answerOptions] = q.options
                this[Questions.correctIndex] = q.correctIndex
            }
        }
        call.respond(HttpStatusCode.Created, QuizImported(quizId.toString(), result.questions.size, result.skipped))
    }

    route("/api/quizzes/{id}") {
        get("/qr.svg") {
            val (tenant, quiz) = call.quizForTenant(db, config) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondText(Qr.svg(Qr.playUrl(tenant, quiz.id.toString(), config.baseDomain)), ContentType.Image.SVG)
        }

        post("/join") {
            val (tenant, quiz) = call.quizForTenant(db, config) ?: return@post call.respond(HttpStatusCode.NotFound)
            if (quiz.state == "ENDED") return@post call.respond(HttpStatusCode.Conflict)
            val name = cleanDisplayName(call.receive<JoinRequest>().name) ?: return@post call.respond(HttpStatusCode.BadRequest)

            val playerId = UUID.randomUUID()
            db.query {
                Players.insert {
                    it[id] = playerId
                    it[quizId] = quiz.id
                    it[Players.name] = name
                }
            }
            val token = tokens.issue(JoinClaims(playerId.toString(), quiz.id.toString(), tenant, name))
            call.respond(HttpStatusCode.Created, Joined(playerId.toString(), token))
        }
    }
}

data class QuizRow(val id: UUID, val state: String)

/** The quiz named in the path, if it exists and belongs to the tenant in the Host header. */
private suspend fun ApplicationCall.quizForTenant(db: Database, config: Config): Pair<String, QuizRow>? {
    val tenant = tenant(config) ?: return null
    val id = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
    val quiz = db.query {
        Quizzes.selectAll().where { (Quizzes.id eq id) and (Quizzes.tenantSlug eq tenant) }
            .singleOrNull()?.let { QuizRow(it[Quizzes.id], it[Quizzes.state]) }
    } ?: return null
    return tenant to quiz
}
