package dev.shtev.quizacrowd.quizapi

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.shtev.quizacrowd.common.JoinTokenIssuer
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
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

fun main() {
    val config = Config.fromEnv()
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(Netty, port = port) { module(config) }.start(wait = true)
}

fun Application.module(config: Config) {
    val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.databaseUrl
        username = config.databaseUser
        password = config.databasePassword
        maximumPoolSize = 5
    })
    monitor.subscribe(ApplicationStopped) { dataSource.close() }
    Flyway.configure().dataSource(dataSource).load().migrate()
    val db = Database.connect(dataSource)
    val tokens = JoinTokenIssuer(config.joinTokenPrivateKeyPem)
    val valkey = Valkey(config.valkeyUrl)
    monitor.subscribe(ApplicationStopped) { valkey.close() }
    val rounds = RoundControl(db, valkey, config.questionSeconds)

    install(ContentNegotiation) { json(quizJson) }
    install(StatusPages) {
        // Malformed or missing JSON bodies.
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
    }

    routing {
        get("/healthz") { call.respondText("ok") }
        tenantRoutes(db, config)
        quizRoutes(db, config, tokens, valkey, rounds)
    }
}
