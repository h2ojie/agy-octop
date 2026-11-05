package com.octop.client.data.model

import org.junit.Assert.*
import org.junit.Test

class HistoryAnswerTest {
    private fun message(role: String?, content: Any?, thinking: String? = null) =
        HistoryMessageDto(role = role, content = content, thinking = thinking)

    @Test fun trailingMemoryToolCannotReplaceAssistantAnswer() {
        val answer = message("assistant", "这是用户可见回答")
        val tool = message("tool", """{"schema":"memory","entities":[],"confidence":0.9}""")
        assertSame(answer, MessageParser.selectHistoryAnswer(listOf(answer, tool)))
    }

    @Test fun toolOnlyTurnHasNoAnswer() {
        assertNull(MessageParser.selectHistoryAnswer(listOf(message("tool", "plain text tool output"))))
    }

    @Test fun legitimateAssistantJsonIsPreserved() {
        val json = """{"schema":"memory","entities":[],"confidence":0.9}"""
        val answer = message("assistant", json)
        assertSame(answer, MessageParser.selectHistoryAnswer(listOf(answer)))
        assertEquals(json, MessageParser.parse(answer.content).first)
    }

    @Test fun selectsLatestTextualAssistantAndSupportsAiAlias() {
        val last = message("AI", listOf(mapOf("type" to "text", "text" to "final")))
        assertSame(last, MessageParser.selectHistoryAnswer(listOf(
            message("assistant", "earlier"), last,
            message("assistant", "", "thinking only"), message("tool", "result")
        )))
    }

    @Test fun userSystemAndUnknownRolesCannotBecomeAnswer() {
        assertNull(MessageParser.selectHistoryAnswer(listOf(
            message("user", "prompt"), message("system", "internal"), message(null, "unknown")
        )))
    }

    @Test fun reasoningAndToolsDoNotDisplaceStructuredAnswer() {
        val answer = message("assistant", listOf(
            mapOf("type" to "thinking", "thinking" to "reasoning"),
            mapOf("type" to "text", "text" to "answer")
        ))
        val selected = MessageParser.selectHistoryAnswer(listOf(answer, message("tool", "internal")))!!
        assertEquals("answer" to "reasoning", MessageParser.parse(selected.content))
    }
}
