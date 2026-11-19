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

    /** Session id whose turn hit the tool-round cap; UI shows continue / new prompt. */
    val limitHit = MutableStateFlow<Long?>(null)

    /** Bumped when the user picks "prompt baru" from the limit notification. */
    val requestPrompt = MutableStateFlow(0)

    /** Bumped when the agent queued a memory write under the confirm policy. */
    val pendingMemory = MutableStateFlow(0)
}
