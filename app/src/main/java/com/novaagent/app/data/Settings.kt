package com.novaagent.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Provider connection settings. Deliberately not persisted yet — M1 keeps it in memory. */
data class ProviderConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
) {
    /** OpenAI-compatible chat completions endpoint, tolerant of a bare host or a trailing /v1. */
    val chatEndpoint: String
        get() {
            val base = baseUrl.trim().trimEnd('/')
            return if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
        }

    val isUsable: Boolean get() = apiKey.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}

object Settings {
    private val _config = MutableStateFlow(ProviderConfig())
    val config: StateFlow<ProviderConfig> = _config.asStateFlow()

    fun update(config: ProviderConfig) {
        _config.value = config
    }
}

data class UiMessage(val role: String, val text: String)
