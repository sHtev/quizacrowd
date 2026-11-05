package dev.shtev.quizacrowd.quizapi

import dev.shtev.quizacrowd.common.JoinTokenVerifier
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class QuizApiTest : ApiTest() {
    @Test
    fun adminCreatesTenant() = api {
        val created = client.createTenant("redlion")
        assertEquals(HttpStatusCode.Created, created.status)
        val key = Json.parseToJsonElement(created.bodyAsText()).jsonObject["hostKey"]!!.jsonPrimitive.content
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(key), key)

        assertEquals(HttpStatusCode.Unauthorized, client.createTenant("crown", token = "wrong").status)
        assertEquals(HttpStatusCode.Unauthorized, client.createTenant("crown", token = null).status)
        assertEquals(HttpStatusCode.Conflict, client.createTenant("redlion").status)
        assertEquals(HttpStatusCode.BadRequest, client.createTenant("Red_Lion").status)
    }

    @Test
    fun hostImportsQuiz() = api {
        val response = client.importQuiz("redlion", client.hostKey("redlion"))
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(4, body["imported"]!!.jsonPrimitive.int)
        assertEquals(3, body["skipped"]!!.jsonPrimitive.int)

        val quizId = body["quizId"]!!.jsonPrimitive.content
        assertEquals(listOf(listOf("LOBBY", "PUB", "redlion")), sql("select state, mode, tenant_slug from quizzes where id = '$quizId'"))
        val questions = sql("select idx, text from questions where quiz_id = '$quizId' order by idx")
        assertEquals(listOf("0", "1", "2", "3"), questions.map { it[0] })
        assertEquals("Which of these companies does NOT manufacture automobiles?", questions[0][1])
    }

    @Test
    fun hostKeyIsScopedToTenant() = api {
        val redlionKey = client.hostKey("redlion")
        client.hostKey("crown")
        assertEquals(HttpStatusCode.Unauthorized, client.importQuiz("crown", redlionKey).status)
        assertEquals(HttpStatusCode.Unauthorized, client.importQuiz("redlion", "not-a-key").status)
    }

    @Test
    fun emptyQuizRejected() = api {
        val onlyPictures = """{"name":"Pictures","rounds":[{"questions":[
            {"description":"Name the film","freeText":true,"correctAnswers":["Rashomon"],"incorrectAnswers":[]}]}]}"""
        val key = client.hostKey("redlion")
        assertEquals(HttpStatusCode.BadRequest, client.importQuiz("redlion", key, onlyPictures).status)
        assertEquals(HttpStatusCode.BadRequest, client.importQuiz("redlion", key, "{not json").status)
    }

    @Test
    fun joinValidatesName() = api {
        val quizId = client.quizId("redlion", client.hostKey("redlion"))

        val joined = client.join(quizId, "  Ada  ")
        assertEquals(HttpStatusCode.Created, joined.status)
        val body = Json.parseToJsonElement(joined.bodyAsText()).jsonObject
        val playerId = body["playerId"]!!.jsonPrimitive.content
        assertEquals(listOf(listOf("Ada")), sql("select name from players where id = '$playerId'"))

        val claims = JoinTokenVerifier(publicPem).verify(body["token"]!!.jsonPrimitive.content)!!
        assertEquals(playerId, claims.playerId)
        assertEquals(quizId, claims.quizId)
        assertEquals("redlion", claims.tenant)
        assertEquals("Ada", claims.name)

        for (bad in listOf("", "   ", "a".repeat(25))) {
            assertEquals(HttpStatusCode.BadRequest, client.join(quizId, bad).status, "name '$bad'")
        }

        val second = Json.parseToJsonElement(client.join(quizId, "Ada").bodyAsText()).jsonObject["playerId"]!!.jsonPrimitive.content
        assertNotEquals(playerId, second)
    }

    @Test
    fun joinUnknownOrEndedQuiz() = api {
        val quizId = client.quizId("redlion", client.hostKey("redlion"))
        assertEquals(HttpStatusCode.NotFound, client.join("00000000-0000-0000-0000-000000000000", "Ada").status)
        assertEquals(HttpStatusCode.NotFound, client.join("not-a-uuid", "Ada").status)
        assertEquals(HttpStatusCode.NotFound, client.join(quizId, "Ada", tenant = "crown").status)
        sql("update quizzes set state = 'ENDED' where id = '$quizId' returning id") // test-only shortcut to the end state
        assertEquals(HttpStatusCode.Conflict, client.join(quizId, "Ada").status)
    }

    @Test
    fun qrEncodesPlayUrl() = api {
        assertEquals("http://redlion.localhost/play?quiz=q1", Qr.playUrl(tenant = "redlion", quizId = "q1", baseDomain = "localhost"))
        assertEquals("https://redlion.quizacrowd.app/play?quiz=q1", Qr.playUrl(tenant = "redlion", quizId = "q1", baseDomain = "quizacrowd.app"))

        val quizId = client.quizId("redlion", client.hostKey("redlion"))
        val response = client.get("/api/quizzes/$quizId/qr.svg") { header(HttpHeaders.Host, "redlion.localhost") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Image.SVG, response.contentType()?.withoutParameters())
        assertEquals(Qr.svg(Qr.playUrl("redlion", quizId, "localhost")), response.bodyAsText())
    }
}
