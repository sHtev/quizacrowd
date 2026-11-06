package dev.shtev.quizacrowd.scorer

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.lettuce.core.RedisClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private val log = LoggerFactory.getLogger("dev.shtev.quizacrowd.scorer")

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val config = Config.fromEnv()
    embeddedServer(Netty, port = port) { module(config) }.start(wait = true)
}

fun Application.module(config: Config) {
    val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.databaseUrl
        username = config.databaseUser
        password = config.databasePassword
        maximumPoolSize = 4
    })
    val redis = RedisClient.create(config.valkeyUrl)
    val valkey = redis.connect()

    // Publishing runs in coroutines; one failed publish shouldn't stop the others.
    val publishing = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> log.error("leaderboard publish failed", e) }
    )
    val publisher = LeaderboardPublisher(
        valkey = valkey.async(),
        answered = { answeredCount(dataSource, it) },
        scope = publishing,
        intervalMs = config.publishIntervalMs,
    )
    val consumer = ScoringConsumer(config, dataSource, valkey.sync(), publisher)

    // KafkaConsumer is blocking and single-threaded, so it gets a plain thread of its own.
    // If the loop dies (e.g. Postgres is down), /healthz fails and Kubernetes restarts the pod,
    // which resumes from the last committed batch.
    val consuming = AtomicBoolean(true)
    val consumerThread = thread(name = "scoring-consumer") {
        try {
            consumer.run()
        } catch (e: Exception) {
            log.error("scoring stopped", e)
        } finally {
            consuming.set(false)
        }
    }

    monitor.subscribe(ApplicationStopped) {
        consumer.stop()
        consumerThread.join()
        publishing.cancel()
        valkey.close()
        redis.shutdown()
        dataSource.close()
    }

    routing {
        get("/healthz") {
            if (consuming.get()) call.respondText("ok") else call.respondText("not consuming", status = HttpStatusCode.ServiceUnavailable)
        }
    }
}
