package dev.shtev.quizacrowd.quizapi

import io.ktor.http.ContentType
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// Everything the pages load comes from this origin; the WebSocket is same-origin too.
private const val CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; " +
    "connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"

/** The player, host and big-screen pages (src/main/resources/static), and their scripts and styles under /assets. */
fun Route.pageRoutes() {
    for (page in listOf("play", "host", "screen")) {
        val html = PageRoutes::class.java.getResource("/static/$page.html")!!.readText()
        get("/$page") {
            call.response.header("Content-Security-Policy", CSP)
            call.respondText(html, ContentType.Text.Html)
        }
    }
    staticResources("/assets", "static") {
        exclude { it.path.endsWith(".html") }
    }
}

private object PageRoutes
