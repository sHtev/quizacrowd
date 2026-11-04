package dev.shtev.quizacrowd.common

import kotlinx.serialization.Serializable
import org.apache.commons.text.StringEscapeUtils

data class ImportedQuestion(val text: String, val options: List<String>, val correctIndex: Int)

data class ImportResult(val name: String, val questions: List<ImportedQuestion>, val skipped: Int)

private const val MAX_OPTIONS = 4

// The parts of a Quizzer quiz.json we use; other fields are ignored.
@Serializable
private data class QuizzerQuiz(val name: String, val rounds: List<QuizzerRound> = emptyList())

@Serializable
private data class QuizzerRound(val questions: List<QuizzerQuestion> = emptyList())

@Serializable
private data class QuizzerQuestion(
    val description: String,
    val freeText: Boolean = false,
    val correctAnswers: List<String> = emptyList(),
    val incorrectAnswers: List<String> = emptyList(),
    val mediaLink: String? = null,
)

/**
 * Imports the multiple-choice and true/false questions from a Quizzer quiz.json.
 * Free-text, picture and music questions can't be answered by tapping a button, so they're skipped.
 * Throws IllegalArgumentException if the JSON doesn't parse.
 */
fun importQuizzer(json: String, shuffle: (List<String>) -> List<String> = { it.shuffled() }): ImportResult {
    val quiz = quizJson.decodeFromString<QuizzerQuiz>(json)
    val all = quiz.rounds.flatMap { it.questions }
    val usable = all.filter { it.isTapToAnswer() }
    val questions = usable.map { q ->
        val correct = decodeEntities(q.correctAnswers.single())
        val options = shuffle((listOf(q.correctAnswers.single()) + q.incorrectAnswers.take(MAX_OPTIONS - 1)).map(::decodeEntities))
        ImportedQuestion(decodeEntities(q.description), options, options.indexOf(correct))
    }
    return ImportResult(decodeEntities(quiz.name), questions, skipped = all.size - usable.size)
}

private fun QuizzerQuestion.isTapToAnswer() =
    !freeText && mediaLink == null && correctAnswers.size == 1 && incorrectAnswers.isNotEmpty()

// Open Trivia DB questions arrive HTML-encoded (&quot;, &#039;, &eacute;, ...).
private fun decodeEntities(s: String): String = StringEscapeUtils.unescapeHtml4(s)
