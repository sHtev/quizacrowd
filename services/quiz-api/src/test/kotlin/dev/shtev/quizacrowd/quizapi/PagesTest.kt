package dev.shtev.quizacrowd.quizapi

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PagesTest : ApiTest() {
    @Test
    fun pagesServedWithContentSecurityPolicy() = api {
        for (page in listOf("/play", "/host", "/screen")) {
            val response = client.get(page)
            assertEquals(HttpStatusCode.OK, response.status, page)
            assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters(), page)
            assertTrue("<!doctype html>" in response.bodyAsText().lowercase(), page)
            // Scripts only from our own origin: a name that did slip into the page as HTML couldn't run anything.
            assertTrue("script-src 'self'" in response.headers["Content-Security-Policy"].orEmpty(), page)
        }
    }

    @Test
    fun assetsServed() = api {
        assertEquals(ContentType.Text.CSS, client.get("/assets/app.css").contentType()?.withoutParameters())
        assertEquals(ContentType.Text.JavaScript, client.get("/assets/play.js").contentType()?.withoutParameters())
        assertEquals(HttpStatusCode.NotFound, client.get("/assets/nope.js").status)
        // Pages are only served at their own paths, where they get the CSP header.
        assertFalse(client.get("/assets/play.html").status.isSuccess())
    }
}
