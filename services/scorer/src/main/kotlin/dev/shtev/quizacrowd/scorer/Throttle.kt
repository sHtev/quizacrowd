package dev.shtev.quizacrowd.scorer

/** What to do when a quiz's leaderboard changes. */
sealed interface ThrottleDecision {
    /** Publish straight away. */
    data object PublishNow : ThrottleDecision

    /** Publish once at [atMs]; changes before then ride along with it. */
    data class PublishAt(val atMs: Long) : ThrottleDecision

    /** A publish is already scheduled and will include this change. */
    data object AlreadyScheduled : ThrottleDecision
}

/**
 * Publishes at most once per [intervalMs], and never leaves a change unpublished.
 * [lastPublishedAtMs] is null before the first publish; [scheduledAtMs] is null when nothing is scheduled.
 */
fun decide(nowMs: Long, lastPublishedAtMs: Long?, scheduledAtMs: Long?, intervalMs: Long): ThrottleDecision {
    if (lastPublishedAtMs == null) return ThrottleDecision.PublishNow
    if (scheduledAtMs == null) {
        val nextScheduledMs = lastPublishedAtMs + intervalMs
        if (nowMs < nextScheduledMs) return ThrottleDecision.PublishAt(nextScheduledMs)
        return ThrottleDecision.PublishNow
    }
    return ThrottleDecision.AlreadyScheduled
}
