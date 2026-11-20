package com.novaagent.app.data

import org.json.JSONObject

/** Executable, dependency-free checks for provider config endpoints and serialization. */
fun main() {
    val c = ProviderConfig("https://host.test/v1/", "key", "primary", "backup", "custom")
    check(c.chatEndpoint == "https://host.test/v1/chat/completions")
    check(c.modelsEndpoint == "https://host.test/v1/models")
    val roundTrip = ProviderConfig.fromJson(c.toJson())
    check(roundTrip == c)
    check(ProviderConfig("https://host.test", "", "m").chatEndpoint == "https://host.test/v1/chat/completions")
}
