package dev.shtev.quizacrowd.scorer

import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/** What scoring needs to know about a question that has been asked. */
data class AskedQuestion(
    val quizId: String,
    val index: Int,
    val correctIndex: Int,
    val startedAtMs: Long,
    val deadlineMs: Long,
)

/**
 * Question timings, loaded from Postgres once and kept: a question's timing is fixed once it's been asked.
 * Only used from the consumer thread, so it isn't thread-safe.
 */
class QuestionCache(private val dataSource: DataSource, private val capacity: Int = 10_000) {
    // A LinkedHashMap in access order drops the least recently used question when full.
    private val cache = object : LinkedHashMap<UUID, AskedQuestion>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, AskedQuestion>) = size > capacity
    }

    /** Null if there's no such question or it hasn't been asked yet. */
    fun get(questionId: UUID): AskedQuestion? {
        cache[questionId]?.let { return it }
        val question = load(questionId) ?: return null
        cache[questionId] = question
        return question
    }

    private fun load(questionId: UUID): AskedQuestion? = dataSource.connection.use { c ->
        c.prepareStatement("select quiz_id, idx, correct_index, started_at, deadline from questions where id = ?").use { st ->
            st.setObject(1, questionId)
            val rs = st.executeQuery()
            if (!rs.next()) return null
            val startedAt = rs.getObject("started_at", OffsetDateTime::class.java) ?: return null
            val deadline = rs.getObject("deadline", OffsetDateTime::class.java) ?: return null
            AskedQuestion(
                quizId = rs.getString("quiz_id"),
                index = rs.getInt("idx"),
                correctIndex = rs.getInt("correct_index"),
                startedAtMs = startedAt.toInstant().toEpochMilli(),
                deadlineMs = deadline.toInstant().toEpochMilli(),
            )
        }
    }
}
