package dev.shtev.quizacrowd.common

/** Valkey keys and channels shared by the services. */
object ValkeyKeys {
    /** Hash describing the open question (epoch-ms times); absent when no question is open. */
    fun currentQuestion(quizId: String) = "quiz:$quizId:current"

    /** Sorted set of playerId to score. */
    fun leaderboard(quizId: String) = "lb:$quizId"

    /** Hash of playerId to display name. */
    fun names(quizId: String) = "names:$quizId"

    /** Pub/sub channel carrying [QuizEvent] JSON. */
    fun events(quizId: String) = "events:$quizId"
}
