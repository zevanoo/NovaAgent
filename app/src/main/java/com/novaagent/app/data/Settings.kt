package com.novaagent.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Provider connection settings. Deliberately not persisted yet — M1 keeps it in memory. */
data class ProviderConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val fallbackModel: String = "",
    val name: String = "OpenAI",
) {
    val chatEndpoint: String get() = endpoint("chat/completions")
    val modelsEndpoint: String get() = endpoint("models")
    val isUsable: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

    private fun endpoint(path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/v1")) "$base/$path" else "$base/v1/$path"
    }

    fun toJson() = org.json.JSONObject().put("name", name).put("baseUrl", baseUrl)
        .put("apiKey", apiKey).put("model", model).put("fallbackModel", fallbackModel)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        fun fromJson(o: org.json.JSONObject) = ProviderConfig(
            baseUrl = o.optString("baseUrl", DEFAULT_BASE_URL),
            apiKey = o.optString("apiKey"),
            model = o.optString("model", DEFAULT_MODEL),
            fallbackModel = o.optString("fallbackModel"),
            name = o.optString("name", "Provider"),
        )
    }
}

object Settings {
    private val _providers = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val providers: StateFlow<List<ProviderConfig>> = _providers.asStateFlow()
    private val _config = MutableStateFlow(ProviderConfig())
    val config: StateFlow<ProviderConfig> = _config.asStateFlow()
    private var initialized = false

    @Synchronized
    fun load(context: android.content.Context) {
        if (initialized) return
        val prefs = context.getSharedPreferences("novaagent", 0)
        val array = runCatching { org.json.JSONArray(prefs.getString("providers", "[]")) }
            .getOrDefault(org.json.JSONArray())
        val list = (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let(ProviderConfig::fromJson)
        }.toMutableList()
        if (list.isEmpty()) list += ProviderConfig(
            baseUrl = prefs.getString("baseUrl", ProviderConfig.DEFAULT_BASE_URL) ?: ProviderConfig.DEFAULT_BASE_URL,
            apiKey = prefs.getString("apiKey", "") ?: "",
            model = prefs.getString("model", ProviderConfig.DEFAULT_MODEL) ?: ProviderConfig.DEFAULT_MODEL,
        )
        val index = prefs.getInt("activeProvider", 0).coerceIn(0, list.lastIndex)
        _providers.value = list
        _config.value = list[index]
        initialized = true
    }

    fun save(context: android.content.Context, providers: List<ProviderConfig>, activeIndex: Int) {
        if (providers.isEmpty()) return
        val index = activeIndex.coerceIn(0, providers.lastIndex)
        context.getSharedPreferences("novaagent", 0).edit()
            .putString("providers", org.json.JSONArray().apply { providers.forEach { put(it.toJson()) } }.toString())
            .putInt("activeProvider", index).apply()
        _providers.value = providers
        _config.value = providers[index]
    }

    fun update(config: ProviderConfig) {
        val list = _providers.value.toMutableList()
        val index = list.indexOfFirst { it.name == _config.value.name }
        if (index >= 0) list[index] = config else list += config
        _providers.value = list
        _config.value = config
    }

    fun select(context: android.content.Context, index: Int) = save(context, _providers.value, index)
}

data class UiMessage(
    val id: Long,
    val role: String,
    val text: String,
    val rounds: List<UiRound> = emptyList(),
    /** Git commit of the workdir before this turn; blank when there was none. */
    val snapshot: String = "",
)

/** One tool call rendered under the user turn; tap toggles result, tap thought toggles detail. */
data class UiRound(
    val name: String,
    val detail: String,
    val thought: String,
    val result: String,
)
