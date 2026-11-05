package dev.shtev.quizacrowd.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON used for every event and message: a "type" field names the event. */
val quizJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** Events sent to phones and screens over the WebSocket. */
@Serializable
sealed class QuizEvent

@Serializable
@SerialName("question")
data class QuestionStarted(
    val questionId: String,
    val index: Int,
    val total: Int,
    val text: String,
    val options: List<String>,
    val startedAtEpochMs: Long,
    val deadlineEpochMs: Long,
) : QuizEvent()

@Serializable
@SerialName("closed")
data class QuestionClosed(val questionId: String, val correctIndex: Int) : QuizEvent()

@Serializable
@SerialName("leaderboard")
data class LeaderboardUpdated(val top: List<Standing>, val answered: Int) : QuizEvent()

@Serializable
@SerialName("ended")
data class QuizEnded(val top: List<Standing>) : QuizEvent()

/** Sent to one player's connection only; rank is 1-based, null before they've scored. */
@Serializable
@SerialName("you")
data class YourStanding(val rank: Int?, val score: Long) : QuizEvent()

@Serializable
data class Standing(val name: String, val score: Long)

/** An answer as it travels through Redpanda; not sent to clients. */
@Serializable
data class AnswerSubmitted(
    val quizId: String,
    val questionId: String,
    val playerId: String,
    val choice: Int,
    val receivedAtEpochMs: Long,
)
