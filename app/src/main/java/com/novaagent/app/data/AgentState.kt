package com.novaagent.app.data

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bus between the agent service and the UI — both live in the same process, the
 * service just outlives the Activity. Not persisted: on service death the state
 * resets, which is the desired behaviour.
 */
object AgentState {
    val running = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)

    /** Bumped when a turn finishes, so the UI can reload its transcript from Room. */
    val turnDone = MutableStateFlow(0)
}
