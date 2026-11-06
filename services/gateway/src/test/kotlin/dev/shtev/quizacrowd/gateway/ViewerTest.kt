package dev.shtev.quizacrowd.gateway

import dev.shtev.quizacrowd.common.JoinClaims
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewerTest {
    private val quizId = "6f1c2a3e-0b7d-4c55-9a8e-1d2f3a4b5c6d"
    private val ada = JoinClaims(playerId = "p-ada", quizId = quizId, tenant = "redlion", name = "Ada")
    private val verify = { token: String -> if (token == "good") ada else null }

    @Test
    fun validTokenIsAPlayer() =
        assertEquals(Viewer.Player("p-ada", quizId), viewerFor("good", null, "redlion", verify))

    @Test
    fun invalidTokenRefused() = assertNull(viewerFor("forged", null, "redlion", verify))

    @Test
    fun tokenForAnotherTenantRefused() = assertNull(viewerFor("good", null, "crown", verify))

    @Test
    fun badTokenDoesNotFallBackToScreen() = assertNull(viewerFor("forged", quizId, "redlion", verify))

    @Test
    fun quizIdAloneIsAScreen() = assertEquals(Viewer.Screen(quizId), viewerFor(null, quizId, "redlion", verify))

    @Test
    fun screenQuizIdMustBeAUuid() = assertNull(viewerFor(null, "events:*", "redlion", verify))

    @Test
    fun unknownHostRefused() {
        assertNull(viewerFor("good", null, null, verify), "player")
        assertNull(viewerFor(null, quizId, null, verify), "screen")
    }

    @Test
    fun nothingGivenRefused() = assertNull(viewerFor(null, null, "redlion", verify))
}
