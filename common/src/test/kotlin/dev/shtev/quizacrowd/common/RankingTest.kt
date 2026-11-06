package dev.shtev.quizacrowd.common

import kotlin.test.Test
import kotlin.test.assertEquals

class RankingTest {
    @Test
    fun noScoresNoStandings() {
        assertEquals(emptyList(), ranked(emptyList()))
    }

    @Test
    fun highestScoreFirst() {
        assertEquals(
            listOf(Standing("Bob", 900, rank = 1), Standing("Ada", 500, rank = 2)),
            ranked(listOf("Ada" to 500L, "Bob" to 900L)),
        )
    }

    @Test
    fun tiesShareARankAndAreListedByName() {
        assertEquals(
            listOf(Standing("Ada", 875, rank = 1), Standing("Zoe", 875, rank = 1), Standing("Bob", 0, rank = 3)),
            ranked(listOf("Zoe" to 875L, "Bob" to 0L, "Ada" to 875L)),
        )
    }

    @Test
    fun rankSkipsPastATie() {
        val scores = listOf("A" to 100L, "B" to 90L, "C" to 90L, "D" to 90L, "E" to 80L)
        assertEquals(listOf(1, 2, 2, 2, 5), ranked(scores).map { it.rank })
    }

    @Test
    fun everyoneOnZeroIsJointFirst() {
        assertEquals(listOf(1, 1, 1), ranked(listOf("C" to 0L, "A" to 0L, "B" to 0L)).map { it.rank })
    }
}
