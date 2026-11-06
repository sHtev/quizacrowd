package dev.shtev.quizacrowd.common

/** The quiz:{id}:current hash for an open question. quiz-api writes it; ingest and the gateway read it. */
fun QuestionStarted.toCurrentQuestionHash(): Map<String, String> = mapOf(
    "questionId" to questionId,
    "index" to index.toString(),
    "total" to total.toString(),
    "startedAt" to startedAtEpochMs.toString(),
    "deadline" to deadlineEpochMs.toString(),
    "optionCount" to options.size.toString(), // ingest checks choices against this without parsing options
    "text" to text,
    "options" to quizJson.encodeToString(options),
)

/** The open question from its hash, or null if none is open (HGETALL gives an empty map for a missing key). */
fun questionFromHash(hash: Map<String, String>): QuestionStarted? {
    if (hash.isEmpty()) return null
    return QuestionStarted(
        questionId = hash.getValue("questionId"),
        index = hash.getValue("index").toInt(),
        total = hash.getValue("total").toInt(),
        text = hash.getValue("text"),
        options = quizJson.decodeFromString(hash.getValue("options")),
        startedAtEpochMs = hash.getValue("startedAt").toLong(),
        deadlineEpochMs = hash.getValue("deadline").toLong(),
    )
}
