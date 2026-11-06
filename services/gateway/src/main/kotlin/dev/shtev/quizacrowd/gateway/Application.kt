package dev.shtev.quizacrowd.gateway

import dev.shtev.quizacrowd.common.JoinTokenVerifier
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.lettuce.core.RedisClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("dev.shtev.quizacrowd.gateway")

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val config = Config.fromEnv()
    embeddedServer(Netty, port = port) { module(config) }.start(wait = true)
}

fun Application.module(config: Config) {
    val redis = RedisClient.create(config.valkeyUrl)
    val valkey = redis.connect()
    // A subscribed connection can't run other commands, so pub/sub gets its own.
    val pubSub = redis.connectPubSub()
    val lookups = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> log.error("standing lookup failed", e) }
    )
    val subscriptions = QuizSubscriptions(pubSub, valkey.async(), lookups)
    val verifier = JoinTokenVerifier(config.joinTokenPublicKeyPem)

    monitor.subscribe(ApplicationStopped) {
        lookups.cancel()
        pubSub.close()
        valkey.close()
        redis.shutdown()
    }

    install(WebSockets) {
        pingPeriod = 15.seconds // keeps idle connections open through proxies, and finds dead ones
        timeout = 30.seconds
        maxFrameSize = 4096 // clients send nothing of their own
    }
    routing {
        get("/healthz") { call.respondText("ok") }
        connectionRoutes(config, verifier::verify, valkey.async(), subscriptions)
    }
}
