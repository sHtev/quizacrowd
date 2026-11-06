package dev.shtev.quizacrowd.scorer

import dev.shtev.quizacrowd.scorer.ThrottleDecision.AlreadyScheduled
import dev.shtev.quizacrowd.scorer.ThrottleDecision.PublishAt
import dev.shtev.quizacrowd.scorer.ThrottleDecision.PublishNow
import kotlin.test.Test
import kotlin.test.assertEquals

class ThrottleTest {
    private val interval = 500L

    @Test
    fun firstChangePublishesNow() {
        assertEquals(PublishNow, decide(nowMs = 1_000, lastPublishedAtMs = null, scheduledAtMs = null, intervalMs = interval))
    }

    @Test
    fun publishesNowOnceTheIntervalHasPassed() {
        assertEquals(PublishNow, decide(nowMs = 1_500, lastPublishedAtMs = 1_000, scheduledAtMs = null, intervalMs = interval))
        assertEquals(PublishNow, decide(nowMs = 9_000, lastPublishedAtMs = 1_000, scheduledAtMs = null, intervalMs = interval))
    }

    @Test
    fun changeWithinTheIntervalIsPublishedWhenItEnds() {
        assertEquals(PublishAt(1_500), decide(nowMs = 1_200, lastPublishedAtMs = 1_000, scheduledAtMs = null, intervalMs = interval))
        assertEquals(PublishAt(1_500), decide(nowMs = 1_000, lastPublishedAtMs = 1_000, scheduledAtMs = null, intervalMs = interval))
    }

    @Test
    fun changeRidesAlongWithAScheduledPublish() {
        assertEquals(AlreadyScheduled, decide(nowMs = 1_300, lastPublishedAtMs = 1_000, scheduledAtMs = 1_500, intervalMs = interval))
    }

    @Test
    fun scheduledPublishCoversChangesEvenIfItsRunningLate() {
        // The scheduled publish hasn't read the leaderboard yet, so it will include this change.
        assertEquals(AlreadyScheduled, decide(nowMs = 1_600, lastPublishedAtMs = 1_000, scheduledAtMs = 1_500, intervalMs = interval))
    }
}
