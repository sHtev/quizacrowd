package dev.shtev.quizacrowd.ingest

import dev.shtev.quizacrowd.common.JoinTokenVerifier
import dev.shtev.quizacrowd.common.quizJson
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.lettuce.core.RedisClient

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val config = Config.fromEnv()
    embeddedServer(Netty, port = port) { module(config) }.start(wait = true)
}

fun Application.module(config: Config) {
    ensureTopic(config.kafkaBootstrap)
    val producer = AnswerProducer(config.kafkaBootstrap)
    val redis = RedisClient.create(config.valkeyUrl)
    val valkey = redis.connect()
    monitor.subscribe(ApplicationStopped) {
        producer.close()
        valkey.close()
        redis.shutdown()
    }

    install(ContentNegotiation) { json(quizJson) }
    install(StatusPages) {
        // Malformed or missing JSON bodies.
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
    }

    routing {
        get("/healthz") { call.respondText("ok") }
        answerRoutes(config, JoinTokenVerifier(config.joinTokenPublicKeyPem), valkey.async(), producer)
    }
}
