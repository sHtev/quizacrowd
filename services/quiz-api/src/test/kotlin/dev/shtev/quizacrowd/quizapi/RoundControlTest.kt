package dev.shtev.quizacrowd.quizapi

import dev.shtev.quizacrowd.common.QuestionClosed
import dev.shtev.quizacrowd.common.QuestionStarted
import dev.shtev.quizacrowd.common.QuizEnded
import dev.shtev.quizacrowd.common.QuizEvent
import dev.shtev.quizacrowd.common.Standing
import dev.shtev.quizacrowd.common.quizJson
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class RoundControlTest : ApiTest() {
    private suspend fun HttpClient.host(quizId: String, action: String, key: String, tenant: String = "redlion"): HttpResponse =
        post("/api/quizzes/$quizId/$action") {
            header(HttpHeaders.Host, "$tenant.localhost")
            bearerAuth(key)
        }

    private fun state(quizId: String) = sql("select state, current_index from quizzes where id = '$quizId'").single()

    @Test
    fun nextStartsQuestion() = api {
        val key = client.hostKey("redlion")
        val quizId = client.quizId("redlion", key)
        Subscription("events:$quizId").use { events ->
            assertEquals(HttpStatusCode.OK, client.host(quizId, "next", key).status)

            val raw = events.nextRaw()
            assertFalse("correctIndex" in raw, "the answer must not reach phones before close: $raw")
            val question = assertIs<QuestionStarted>(quizJson.decodeFromString<QuizEvent>(raw))
            assertEquals(0, question.index)
            assertEquals(4, question.total)
            assertEquals("Which of these companies does NOT manufacture automobiles?", question.text)
            val (id, options) = sql("select id, options from questions where quiz_id = '$quizId' and idx = 0").single()
            assertEquals(id, question.questionId)
            assertEquals(quizJson.decodeFromString<List<String>>(options!!), question.options)
            assertEquals(20_000L, question.deadlineEpochMs - question.startedAtEpochMs)

            assertEquals(listOf("QUESTION_OPEN", "0"), state(quizId))
            val timing = sql(
                "select (extract(epoch from started_at) * 1000)::bigint, (extract(epoch from deadline) * 1000)::bigint from questions where id = '$id'"
            ).single()
            assertEquals(listOf(question.startedAtEpochMs.toString(), question.deadlineEpochMs.toString()), timing)

            val current = redis.hgetall("quiz:$quizId:current")
            assertEquals(
                mapOf(
                    "questionId" to question.questionId,
                    "index" to "0",
                    "total" to "4",
                    "startedAt" to question.startedAtEpochMs.toString(),
                    "deadline" to question.deadlineEpochMs.toString(),
                    "optionCount" to question.options.size.toString(),
                    "text" to question.text,
                    "options" to quizJson.encodeToString(question.options),
                ),
                current,
            )
        }
    }

    @Test
    fun closeRevealsAnswer() = api {
        val key = client.hostKey("redlion")
        val quizId = client.quizId("redlion", key)
        Subscription("events:$quizId").use { events ->
            client.host(quizId, "next", key)
            val question = assertIs<QuestionStarted>(events.next())

            assertEquals(HttpStatusCode.OK, client.host(quizId, "close", key).status)
            val closed = assertIs<QuestionClosed>(events.next())
            val correctIndex = sql("select correct_index from questions where id = '${question.questionId}'").single().single()
            assertEquals(QuestionClosed(question.questionId, correctIndex!!.toInt()), closed)
            assertEquals(0L, redis.exists("quiz:$quizId:current"))
            assertEquals(listOf("QUESTION_CLOSED", "0"), state(quizId))

            client.host(quizId, "next", key)
            assertEquals(1, assertIs<QuestionStarted>(events.next()).index)
        }
    }

    @Test
    fun outOfOrderActionsRejected() = api {
        val key = client.hostKey("redlion")
        val quizId = client.quizId("redlion", key)
        Subscription("events:$quizId").use { events ->
            assertEquals(HttpStatusCode.Conflict, client.host(quizId, "close", key).status, "close in lobby")
            assertEquals(listOf("LOBBY", null), state(quizId))

            client.host(quizId, "next", key)
            assertIs<QuestionStarted>(events.next())
            assertEquals(HttpStatusCode.Conflict, client.host(quizId, "next", key).status, "next while open")
            assertEquals(listOf("QUESTION_OPEN", "0"), state(quizId))

            client.host(quizId, "close", key)
            assertIs<QuestionClosed>(events.next())
            assertEquals(HttpStatusCode.Conflict, client.host(quizId, "close", key).status, "close twice")

            client.host(quizId, "end", key)
            assertIs<QuizEnded>(events.next())
            for (action in listOf("next", "close", "end")) {
                assertEquals(HttpStatusCode.Conflict, client.host(quizId, action, key).status, "$action after end")
            }
            assertEquals(listOf("ENDED", "0"), state(quizId))
            events.assertNoMore()
        }
    }

    @Test
    fun doubleClickedNextOpensOneQuestion() = api {
        val key = client.hostKey("redlion")
        val quizId = client.quizId("redlion", key)
        Subscription("events:$quizId").use { events ->
            val statuses = coroutineScope {
                List(2) { async { client.host(quizId, "next", key).status } }.awaitAll()
            }
            assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.Conflict), statuses.sortedBy { it.value })
            assertEquals(0, assertIs<QuestionStarted>(events.next()).index)
            events.assertNoMore()
        }
    }

    @Test
    fun nextAfterLastQuestionRejected() = api {
        val key = client.hostKey("redlion")
        val quizId = client.quizId("redlion", key)
        repeat(4) {
            assertEquals(HttpStatusCode.OK, client.host(quizId, "next", key).status)
            assertEquals(HttpStatusCode.OK, client.host(quizId, "close", key).status)
        }
        assertEquals(HttpStatusCode.Conflict, client.host(quizId, "next", key).status)
        assertEquals(listOf("QUESTION_CLOSED", "3"), state(quizId))
        assertEquals(HttpStatusCode.OK, client.host(quizId, "end", key).status)
    }

    @Test
    fun endPublishesFinalStandings() = api {
        val key = client.hostKey("redlion")
        val quizId = client.quizId("redlion", key)
        // The scorer (Task 6) will fill these; seed them directly here.
        for (i in 1..12) {
            redis.zadd("lb:$quizId", i * 100.0, "p$i")
            redis.hset("names:$quizId", "p$i", "Player $i")
        }
        client.host(quizId, "next", key)

        Subscription("events:$quizId").use { events ->
            assertEquals(HttpStatusCode.OK, client.host(quizId, "end", key).status)
            val ended = assertIs<QuizEnded>(events.next())
            assertEquals((12 downTo 3).map { Standing("Player $it", it * 100L) }, ended.top)
        }
        assertEquals(0L, redis.exists("quiz:$quizId:current"), "ending mid-question clears it")
        assertEquals("ENDED", state(quizId)[0])
    }

    @Test
    fun hostActionsNeedTheTenantsHostKey() = api {
        val key = client.hostKey("redlion")
        val crownKey = client.hostKey("crown")
        val quizId = client.quizId("redlion", key)
        assertEquals(HttpStatusCode.Unauthorized, client.host(quizId, "next", "not-a-key").status)
        assertEquals(HttpStatusCode.Unauthorized, client.host(quizId, "next", key, tenant = "crown").status)
        assertEquals(HttpStatusCode.NotFound, client.host(quizId, "next", crownKey, tenant = "crown").status)
        assertEquals(HttpStatusCode.NotFound, client.host(quizId, "rewind", key).status)
        assertEquals(listOf("LOBBY", null), state(quizId))
    }

    @Test
    fun joinRecordsName() = api {
        val quizId = client.quizId("redlion", client.hostKey("redlion"))
        val joined = client.join(quizId, "Ada")
        val playerId = Json.parseToJsonElement(joined.bodyAsText()).jsonObject["playerId"]!!.jsonPrimitive.content
        assertEquals(mapOf(playerId to "Ada"), redis.hgetall("names:$quizId"))
    }
}
