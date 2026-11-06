package dev.shtev.quizacrowd.gateway

import dev.shtev.quizacrowd.common.JoinClaims
import dev.shtev.quizacrowd.common.JoinTokenIssuer
import dev.shtev.quizacrowd.common.LeaderboardUpdated
import dev.shtev.quizacrowd.common.QuestionStarted
import dev.shtev.quizacrowd.common.QuizEvent
import dev.shtev.quizacrowd.common.Standing
import dev.shtev.quizacrowd.common.ValkeyKeys
import dev.shtev.quizacrowd.common.YourStanding
import dev.shtev.quizacrowd.common.quizJson
import dev.shtev.quizacrowd.common.toCurrentQuestionHash
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.lettuce.core.RedisClient
import io.lettuce.core.ScoredValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.GenericContainer
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GatewayTest {
    companion object {
        val valkey = GenericContainer("valkey/valkey:8-alpine").withExposedPorts(6379).apply { start() }
        val valkeyUrl = "redis://${valkey.host}:${valkey.getMappedPort(6379)}"
        val redis = RedisClient.create(valkeyUrl).connect().sync()

        private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        private fun pem(type: String, der: ByteArray) =
            "-----BEGIN $type-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END $type-----\n"
        val publicPem = pem("PUBLIC KEY", keys.public.encoded)
        val tokens = JoinTokenIssuer(pem("PRIVATE KEY", keys.private.encoded))
    }

    private val quizId = UUID.randomUUID().toString()
    private val channel = ValkeyKeys.events(quizId)

    @BeforeEach
    fun cleanValkey() {
        redis.flushall()
    }

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(Config(baseDomain = "localhost", valkeyUrl = valkeyUrl, joinTokenPublicKeyPem = publicPem)) }
        block()
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(WebSockets) }

    private fun tokenFor(name: String, playerId: String = UUID.randomUUID().toString()) =
        tokens.issue(JoinClaims(playerId = playerId, quizId = quizId, tenant = "redlion", name = name))

    private suspend fun HttpClient.connect(query: String, tenant: String = "redlion"): DefaultClientWebSocketSession =
        webSocketSession("/ws?$query") { header(HttpHeaders.Host, "$tenant.localhost") }

    /** A player connection, once the gateway has registered it: players are always sent their standing on connect. */
    private suspend fun HttpClient.player(token: String): DefaultClientWebSocketSession =
        connect("token=$token").also { assertIs<YourStanding>(it.next()) }

    /** A screen connection; screens get nothing on connect, so wait until the gateway has subscribed for it. */
    private suspend fun HttpClient.screen(): DefaultClientWebSocketSession =
        connect("screen=$quizId").also { awaitSubscribers(1) }

    private suspend fun DefaultClientWebSocketSession.next(): QuizEvent = withTimeout(5.seconds) {
        quizJson.decodeFromString<QuizEvent>((incoming.receive() as Frame.Text).readText())
    }

    private suspend fun DefaultClientWebSocketSession.nothingMore() =
        assertNull(withTimeoutOrNull(300.milliseconds) { incoming.receive() }, "unexpected frame")

    private suspend fun awaitSubscribers(count: Long) = withTimeout(5.seconds) {
        while (redis.pubsubNumsub(channel)[channel] != count) delay(20)
    }

    private fun publish(event: QuizEvent) {
        redis.publish(channel, quizJson.encodeToString<QuizEvent>(event))
    }

    private val question = QuestionStarted(
        questionId = UUID.randomUUID().toString(),
        index = 0,
        total = 4,
        text = "Which of these companies does NOT manufacture automobiles?",
        options = listOf("Nissan", "Ducati", "GMC", "Fiat"),
        startedAtEpochMs = 1_000,
        deadlineEpochMs = 21_000,
    )

    @Test
    fun eventsFanOutToAllConnections() = api {
        val client = wsClient()
        val screen = client.screen()
        val players = listOf("Ada", "Bob", "Cy").map { client.player(tokenFor(it)) }

        publish(question)

        for (connection in players + screen) assertEquals(question, connection.next())
    }

    @Test
    fun playersGetTheirOwnStanding() = api {
        val client = wsClient()
        val screen = client.screen()
        val ada = client.player(tokenFor("Ada", playerId = "p-ada"))
        val bob = client.player(tokenFor("Bob", playerId = "p-bob"))
        val cy = client.player(tokenFor("Cy", playerId = "p-cy"))
        redis.zadd(ValkeyKeys.leaderboard(quizId), ScoredValue.just(900.0, "p-ada"), ScoredValue.just(900.0, "p-bob"), ScoredValue.just(100.0, "p-cy"))

        val update = LeaderboardUpdated(listOf(Standing("Ada", 900, 1), Standing("Bob", 900, 1), Standing("Cy", 100, 3)), answered = 3)
        publish(update)

        // Each player gets the leaderboard and then, separately, their own standing.
        for ((player, standing) in listOf(ada to YourStanding(1, 900), bob to YourStanding(1, 900), cy to YourStanding(3, 100))) {
            assertEquals(update, player.next())
            assertEquals(standing, player.next())
        }
        assertEquals(update, screen.next())
        screen.nothingMore()
    }

    @Test
    fun reconnectGetsCurrentState() = api {
        redis.hset(ValkeyKeys.currentQuestion(quizId), question.toCurrentQuestionHash())
        redis.zadd(ValkeyKeys.leaderboard(quizId), 450.0, "p-ada")

        val ada = wsClient().connect("token=${tokenFor("Ada", playerId = "p-ada")}")

        assertEquals(question, ada.next())
        assertEquals(YourStanding(1, 450), ada.next())
    }

    @Test
    fun playerWithNoScoreYetHasNoRank() = api {
        val ada = wsClient().connect("token=${tokenFor("Ada")}")
        assertEquals(YourStanding(rank = null, score = 0), ada.next())
    }

    @Test
    fun invalidTokenClosed() = api {
        val session = wsClient().connect("token=not-a-token")
        val reason = withTimeout(5.seconds) { session.closeReason.await() }
        assertEquals(4401.toShort(), reason?.code)
        assertEquals("unauthorised", reason?.message)
    }

    @Test
    fun singleSubscriptionPerQuiz() = api {
        val client = wsClient()
        val players = (1..50).map { client.player(tokenFor("Player $it")) }

        assertEquals(1L, redis.pubsubNumsub(channel)[channel])
        players.forEach { it.close() }
    }

    @Test
    fun lastDisconnectUnsubscribes() = api {
        val client = wsClient()
        val players = (1..3).map { client.player(tokenFor("Player $it")) }
        awaitSubscribers(1)

        players.forEach { it.close() }

        awaitSubscribers(0)
    }
}
