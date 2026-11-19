package com.novaagent.app.data

import kotlinx.coroutines.flow.MutableStateFlow

/** In-process view of whether the agent service is alive. Replaced by a bound state in M3. */
object AgentState {
    val running = MutableStateFlow(false)
}
