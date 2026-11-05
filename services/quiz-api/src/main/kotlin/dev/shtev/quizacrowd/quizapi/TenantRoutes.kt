package dev.shtev.quizacrowd.quizapi

import dev.shtev.quizacrowd.common.tenantFromHost
import dev.shtev.quizacrowd.quizapi.db.Tenants
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

@Serializable
data class CreateTenant(val slug: String, val name: String)

@Serializable
data class TenantCreated(val slug: String, val hostKey: String)

fun Route.tenantRoutes(db: Database, config: Config) {
    post("/admin/tenants") {
        val token = call.bearerToken()
        if (token == null || !sameSecret(token, config.adminToken)) return@post call.respond(HttpStatusCode.Unauthorized)

        val request = call.receive<CreateTenant>()
        // A slug is valid if it would work as a subdomain.
        if (tenantFromHost("${request.slug}.${config.baseDomain}", config.baseDomain) != request.slug || request.name.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest)
        }

        val hostKey = newHostKey()
        val created = db.query {
            val exists = Tenants.selectAll().where { Tenants.slug eq request.slug }.any()
            if (!exists) {
                Tenants.insert {
                    it[slug] = request.slug
                    it[name] = request.name.trim()
                    it[hostKeyHash] = sha256(hostKey)
                }
            }
            !exists
        }
        if (!created) return@post call.respond(HttpStatusCode.Conflict)
        call.respond(HttpStatusCode.Created, TenantCreated(request.slug, hostKey))
    }
}
