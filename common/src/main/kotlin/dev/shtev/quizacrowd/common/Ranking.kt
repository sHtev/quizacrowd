package dev.shtev.quizacrowd.common

/**
 * Standings from (name, score) pairs: highest score first. Equal scores share a rank and the
 * next rank skips past them (1, 2, 2, 4); players on the same score are listed by name.
 */
fun ranked(scores: List<Pair<String, Long>>): List<Standing> {
    return scores.sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
        .asSequence()
        .mapIndexed { index, (name, score) -> Standing(name, score, index + 1) }
        .runningReduce { previous, standing ->
            if (standing.score == previous.score) standing.copy(rank = previous.rank) else standing
        }
        .toList()
}
