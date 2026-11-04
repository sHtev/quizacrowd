package dev.shtev.quizacrowd.common

import kotlin.math.roundToInt

fun points(correct: Boolean, receivedAtMs: Long, startedAtMs: Long, deadlineMs: Long): Int {
    if (!correct || receivedAtMs > deadlineMs) return 0
    // Fraction of time remaining between 0.0 and 1.0
    val timeLeft = (deadlineMs - receivedAtMs).toDouble() / (deadlineMs - startedAtMs)
    return (500 + 500 * timeLeft).roundToInt().coerceIn(500, 1000)
}
