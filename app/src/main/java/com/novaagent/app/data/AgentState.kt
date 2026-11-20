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

    /** Session folder the agent created on its last turn; blank until it writes something. */
    val sessionDir = MutableStateFlow("")

    /** Bumped when the header's workdir label should be recomputed. */
    val workdirVersion = MutableStateFlow(0)

    /**
     * Soft interrupt. The loop checks it between tool calls and stops keeping
     * what it produced, instead of killing the service mid-command.
     */
    val interrupt = MutableStateFlow(false)

    /**
     * Session the running turn belongs to, or 0.
     *
     * The service outlives the Activity, but this object does not survive process
     * death; after the app is killed and reopened, the UI has to ask the service
     * what is actually running instead of trusting its own (empty) state. That is
     * what [session] plus [running] answer.
     */
    val session = MutableStateFlow(0L)

    /** Answer to the last [session] probe; bumped so the UI can react once. */
    val reportedSession = MutableStateFlow(-1L)
}
