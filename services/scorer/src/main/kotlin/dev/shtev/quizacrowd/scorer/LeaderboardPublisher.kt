package dev.shtev.quizacrowd.scorer

import dev.shtev.quizacrowd.common.LeaderboardUpdated
import dev.shtev.quizacrowd.common.QuizEvent
import dev.shtev.quizacrowd.common.ValkeyKeys
import dev.shtev.quizacrowd.common.quizJson
import dev.shtev.quizacrowd.common.topStandings
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.UUID

/**
 * Sends LeaderboardUpdated for a quiz at most once per [intervalMs] (see [decide]),
 * so a burst of answers becomes one or two events instead of thousands.
 */
class LeaderboardPublisher(
    private val valkey: RedisAsyncCommands<String, String>,
    private val answered: (questionId: UUID) -> Int,
    private val scope: CoroutineScope,
    private val intervalMs: Long,
    private val clock: Clock = Clock.systemUTC(),
) {
    private class QuizState {
        var lastPublishedAtMs: Long? = null
        var scheduledAtMs: Long? = null
        var questionId: UUID? = null
        var questionIndex = -1
    }

    // Touched by the consumer thread and by publishing coroutines, so every access holds this lock.
    private val states = HashMap<String, QuizState>()

    /** Called after the leaderboard of [quizId] has changed because of answers to [questionId]. */
    fun changed(quizId: String, questionId: UUID, questionIndex: Int) {
        val now = clock.millis()
        val decision = synchronized(states) {
            val state = states.getOrPut(quizId) { QuizState() }
            // A late answer to an earlier question shouldn't move the "answered" count back.
            if (questionIndex >= state.questionIndex) {
                state.questionIndex = questionIndex
                state.questionId = questionId
            }
            val decision = decide(now, state.lastPublishedAtMs, state.scheduledAtMs, intervalMs)
            when (decision) {
                ThrottleDecision.PublishNow -> state.lastPublishedAtMs = now
                is ThrottleDecision.PublishAt -> state.scheduledAtMs = decision.atMs
                ThrottleDecision.AlreadyScheduled -> Unit
            }
            decision
        }

        when (decision) {
            ThrottleDecision.PublishNow -> scope.launch { publish(quizId) }
            is ThrottleDecision.PublishAt -> scope.launch {
                delay(decision.atMs - now)
                // Clear the schedule before reading, so any change from here on schedules another publish.
                synchronized(states) {
                    val state = states.getValue(quizId)
                    state.scheduledAtMs = null
                    state.lastPublishedAtMs = clock.millis()
                }
                publish(quizId)
            }
            ThrottleDecision.AlreadyScheduled -> Unit
        }
    }

    private suspend fun publish(quizId: String) {
        val questionId = synchronized(states) { states.getValue(quizId).questionId } ?: return
        val answered = withContext(Dispatchers.IO) { answered(questionId) }
        val event: QuizEvent = LeaderboardUpdated(valkey.topStandings(quizId), answered)
        valkey.publish(ValkeyKeys.events(quizId), quizJson.encodeToString(event)).await()
    }
}
