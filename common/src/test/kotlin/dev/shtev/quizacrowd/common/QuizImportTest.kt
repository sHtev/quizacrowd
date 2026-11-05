package dev.shtev.quizacrowd.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuizImportTest {
    private val sample = javaClass.getResource("/quizzer-sample.json")!!.readText()
    private val keepOrder: (List<String>) -> List<String> = { it }

    @Test
    fun importsTextMultipleChoice() {
        val result = importQuizzer(sample, keepOrder)
        assertEquals("The Gilroy Quiz Number 1", result.name)
        val first = result.questions.first()
        assertEquals("Which of these companies does NOT manufacture automobiles?", first.text)
        assertEquals(listOf("Ducati", "Nissan", "GMC", "Fiat"), first.options)
        assertEquals(0, first.correctIndex)
    }

    @Test
    fun importSkipsUnsupported() {
        val result = importQuizzer(sample, keepOrder)
        assertEquals(4, result.questions.size)
        assertEquals(3, result.skipped)
    }

    @Test
    fun optionsAreShuffled() {
        val result = importQuizzer(sample) { it.reversed() }
        val first = result.questions.first()
        assertEquals("Ducati", first.options[first.correctIndex])
        assertEquals(first.options.lastIndex, first.correctIndex)
    }

    @Test
    fun decodesHtmlEntities() {
        val texts = importQuizzer(sample, keepOrder).questions.map { it.text }
        assertEquals(true, texts.any { "\"A Song of Ice And Fire\"" in it }, texts.toString())
        assertEquals(false, texts.any { "&quot;" in it })
    }

    @Test
    fun optionsCappedAtFour() {
        val json = """{"name":"Q","rounds":[{"type":"multiple","questions":[
            {"description":"Pick","freeText":false,"correctAnswers":["a"],"incorrectAnswers":["b","c","d","e","f"]}]}]}"""
        assertEquals(4, importQuizzer(json, keepOrder).questions.single().options.size)
    }

    @Test
    fun malformedJsonRejected() {
        assertFailsWith<IllegalArgumentException> { importQuizzer("{not json", keepOrder) }
    }
}
