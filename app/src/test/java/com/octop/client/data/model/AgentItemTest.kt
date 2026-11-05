package com.octop.client.data.model

import com.octop.client.ui.ChatViewModel
import org.junit.Assert.*
import org.junit.Test

class AgentItemTest {

    @Test
    fun testIsAgentEnabled() {
        val running = AgentItem(agentId = "jiaoyuan", name = "教员", state = "running")
        assertTrue(running.isAgentEnabled)

        val stopped = AgentItem(agentId = "WTNWE4", name = "运维", state = "stopped")
        assertFalse(stopped.isAgentEnabled)

        val disabled = AgentItem(agentId = "test", name = "测试", enabled = false)
        assertFalse(disabled.isAgentEnabled)

        val active = AgentItem(agentId = "3MM121", name = "孔夫子", state = "active")
        assertTrue(active.isAgentEnabled)
    }

    @Test
    fun testDefaultExpertsContainsNineEnabled() {
        val defaults = ChatViewModel.defaultExperts
        val jiaoyuan = defaults.find { it.agentId == "jiaoyuan" }
        assertNotNull(jiaoyuan)
        assertEquals("教员 · 战略实践导师", jiaoyuan?.name)

        val confucius = defaults.find { it.agentId == "3MM121" }
        assertNotNull(confucius)
        assertEquals("孔夫子 · 思想导师", confucius?.name)

        val enabled = defaults.filter { it.isAgentEnabled }
        // 9 enabled experts (main, KGZ2RP, AZYKMJ, 70P5RA, 5K92B4, EY93GH, ZBJ5S3, 3MM121, jiaoyuan)
        assertEquals(9, enabled.size)
    }
}
