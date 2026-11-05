package dev.shtev.quizacrowd.quizapi

import dev.shtev.quizacrowd.common.tenantFromHost
import dev.shtev.quizacrowd.quizapi.db.Tenants
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Runs a database transaction on the IO thread pool: JDBC blocks, so keep it off Netty's event loop. */
suspend fun <T> Database.query(block: () -> T): T = withContext(Dispatchers.IO) { transaction(this@query) { block() } }

/** The tenant named by the request's Host header, e.g. "redlion" for redlion.quizacrowd.online. */
fun ApplicationCall.tenant(config: Config): String? =
    request.headers[HttpHeaders.Host]?.let { tenantFromHost(it, config.baseDomain) }

fun ApplicationCall.bearerToken(): String? =
    request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()

/** The tenant, if the request carries that tenant's host key. */
suspend fun ApplicationCall.authorisedHost(db: Database, config: Config): String? {
    val tenant = tenant(config) ?: return null
    val key = bearerToken() ?: return null
    val storedHash = db.query {
        Tenants.selectAll().where { Tenants.slug eq tenant }.singleOrNull()?.get(Tenants.hostKeyHash)
    } ?: return null
    return tenant.takeIf { sameSecret(sha256(key), storedHash) }
}
