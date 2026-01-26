package com.sbf.assistant

/**
 * Splits streaming tokens into thought vs answer content.
 */
class ThinkFilter {
    private var inThinkBlock = false

    data class Chunk(val thoughtDelta: String, val answerDelta: String)

    fun consume(token: String): Chunk {
        if (token.isEmpty()) return Chunk("", "")
        var text = token
        val thoughtOut = StringBuilder()
        val answerOut = StringBuilder()

        while (text.isNotEmpty()) {
            if (inThinkBlock) {
                val end = findEndTag(text)
                if (end == null) {
                    thoughtOut.append(text)
                    return Chunk(thoughtOut.toString(), answerOut.toString())
                }
                thoughtOut.append(text.substring(0, end.first))
                text = text.substring(end.first + end.second)
                inThinkBlock = false
            } else {
                val start = findStartTag(text)
                if (start == null) {
                    answerOut.append(text)
                    return Chunk(thoughtOut.toString(), answerOut.toString())
                }
                answerOut.append(text.substring(0, start.first))
                text = text.substring(start.first + start.second)
                inThinkBlock = true
            }
        }
        return Chunk(thoughtOut.toString(), answerOut.toString())
    }

    fun stripAll(text: String): String {
        if (text.isBlank()) return text
        var cleaned = text
        cleaned = cleaned.replace(THINK_BLOCK_REGEX, "")
        cleaned = cleaned.replace(THINKING_BLOCK_REGEX, "")
        cleaned = cleaned.replace(THINK_LINE_REGEX, "")
        return cleaned
    }

    private fun findStartTag(text: String): Pair<Int, Int>? {
        val candidates = listOf("<think>", "<thinking>")
        return findFirstTag(text, candidates)
    }

    private fun findEndTag(text: String): Pair<Int, Int>? {
        val candidates = listOf("</think>", "</thinking>", "/think")
        return findFirstTag(text, candidates)
    }

    private fun findFirstTag(text: String, tags: List<String>): Pair<Int, Int>? {
        var bestIndex = -1
        var bestLen = 0
        val lower = text.lowercase()
        for (tag in tags) {
            val idx = lower.indexOf(tag)
            if (idx >= 0 && (bestIndex == -1 || idx < bestIndex)) {
                bestIndex = idx
                bestLen = tag.length
            }
        }
        return if (bestIndex >= 0) bestIndex to bestLen else null
    }

    companion object {
        private val THINK_BLOCK_REGEX = Regex("(?is)<think>.*?</think>")
        private val THINKING_BLOCK_REGEX = Regex("(?is)<thinking>.*?</thinking>")
        private val THINK_LINE_REGEX = Regex("(?im)^\\s*/think\\s*$")
    }
}
