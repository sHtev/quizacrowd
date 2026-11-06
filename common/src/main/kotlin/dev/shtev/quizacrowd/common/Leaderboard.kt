package dev.shtev.quizacrowd.common

import io.lettuce.core.ZRange
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.future.await

/**
 * The top [count] players with display names from names:{quizId}, ranked by [ranked].
 * If players tie for the last place, which of them make the cut is arbitrary.
 */
suspend fun RedisAsyncCommands<String, String>.topStandings(quizId: String, count: Int = 10): List<Standing> {
    val scores = zrangeWithScores(ValkeyKeys.leaderboard(quizId), ZRange.byIndex(0, count - 1L).rev()).await()
    if (scores.isEmpty()) return emptyList()
    val names = hmget(ValkeyKeys.names(quizId), *scores.map { it.value }.toTypedArray()).await()
        .associate { it.key to it.getValueOrElse("?") }
    return ranked(scores.map { names.getValue(it.value) to it.score.toLong() })
}
