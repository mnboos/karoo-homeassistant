/**
 * Copyright (c) 2025 SRAM LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.mnboos.karoohomeassistant.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber

data class HaEntity(val entityId: String, val name: String) {
    val domain: String get() = entityId.substringBefore('.')
}

data class EntityListResult(val entities: List<HaEntity>, val error: String?)

/**
 * Services offered per entity domain; the first one is the default. The keys are also the
 * domains listed by [fetchHaEntities] — entities of other domains can't be "pressed".
 */
val DOMAIN_SERVICES: Map<String, List<String>> = linkedMapOf(
    "button" to listOf("press"),
    "input_button" to listOf("press"),
    "light" to listOf("toggle", "turn_on", "turn_off"),
    "switch" to listOf("toggle", "turn_on", "turn_off"),
    "input_boolean" to listOf("toggle", "turn_on", "turn_off"),
    "fan" to listOf("toggle", "turn_on", "turn_off"),
    "siren" to listOf("toggle", "turn_on", "turn_off"),
    "cover" to listOf("toggle", "open_cover", "close_cover", "stop_cover"),
    "valve" to listOf("toggle", "open_valve", "close_valve"),
    "lock" to listOf("unlock", "lock", "open"),
    "script" to listOf("turn_on"),
    "scene" to listOf("turn_on"),
    "automation" to listOf("trigger", "toggle"),
)

/**
 * Renders a compact `[[entity_id, name], …]` list server side. `GET /api/states` would be simpler
 * but usually exceeds the 100 KB response limit of the Karoo HTTP route.
 */
private val ENTITY_LIST_TEMPLATE = """
    {%- set ns = namespace(items=[]) -%}
    {%- for s in states if s.domain in domains -%}
    {%- set ns.items = ns.items + [[s.entity_id, s.name]] -%}
    {%- endfor -%}
    {{ ns.items | tojson }}
""".trimIndent()

/**
 * Lists the entities a button can act on via `POST /api/template`, through the Karoo system
 * (the same route the buttons use). Home Assistant only allows this for admin users.
 *
 * [onResult] may be called on a binder thread. Returns the consumer id so the caller can cancel the
 * request with [KarooSystemService.removeConsumer], or `null` if the request was not started.
 */
fun fetchHaEntities(
    karooSystem: KarooSystemService,
    config: AppConfig,
    onResult: (EntityListResult) -> Unit,
): String? {
    if (config.baseUrl.isBlank()) {
        onResult(EntityListResult(emptyList(), "Enter the HA Base URL first"))
        return null
    }
    val body = buildJsonObject {
        put("template", ENTITY_LIST_TEMPLATE)
        putJsonObject("variables") {
            putJsonArray("domains") { DOMAIN_SERVICES.keys.forEach { add(it) } }
        }
    }.toString()
    val listenerRef = object {
        var id: String = ""
    }
    listenerRef.id = karooSystem.addConsumer(
        OnHttpResponse.MakeHttpRequest(
            method = "POST",
            url = config.apiUrl("template"),
            headers = config.requestHeaders(),
            body = body.toByteArray(),
            waitForConnection = false,
        ),
        onError = { error ->
            karooSystem.removeConsumer(listenerRef.id)
            onResult(EntityListResult(emptyList(), "Error: $error"))
        },
    ) { response: OnHttpResponse ->
        val state = response.state as? HttpResponseState.Complete ?: return@addConsumer
        karooSystem.removeConsumer(listenerRef.id)
        Timber.d("Entity list -> ${state.statusCode} ${state.error ?: ""} (${state.body?.size ?: 0} bytes)")
        onResult(interpretEntityList(state))
    }
    return listenerRef.id
}

private fun interpretEntityList(state: HttpResponseState.Complete): EntityListResult {
    val body = state.body?.toString(Charsets.UTF_8)?.trim().orEmpty()
    // HTML bodies (e.g. a Cloudflare block page) are noise.
    val snippet = body.takeIf { it.isNotEmpty() && !it.startsWith("<") }?.take(140)?.let { ": $it" } ?: ""
    fun fail(message: String) = EntityListResult(emptyList(), message)
    return when {
        state.error != null && state.statusCode !in 100..599 -> fail("Not reachable: ${state.error}")
        state.statusCode in 200..299 -> try {
            val entities = (Json.parseToJsonElement(body) as JsonArray).map { item ->
                val pair = item as JsonArray
                HaEntity(pair[0].jsonPrimitive.content, pair[1].jsonPrimitive.content)
            }
            EntityListResult(entities.sortedWith(compareBy({ it.domain }, { it.name.lowercase() })), null)
        } catch (e: Exception) {
            Timber.w(e, "Unexpected entity list response")
            if ((state.body?.size ?: 0) >= OnHttpResponse.MAX_REQUEST_SIZE - 1_000) {
                // Probably cut off at the Karoo HTTP size limit.
                fail("Entity list too large for the Karoo connection — set the slots by hand on the Karoo")
            } else {
                fail("Unexpected answer — check URL and proxy header$snippet")
            }
        }
        state.statusCode == 401 ->
            fail("401 Unauthorized — wrong token, or the token's HA user is not an admin (needed to list entities)")
        state.statusCode == 403 -> fail("403 Forbidden — check the proxy header$snippet")
        state.statusCode == 404 -> fail("404 Not found — check the base URL (without /api)")
        else -> fail("HTTP ${state.statusCode}${state.error?.let { " ($it)" } ?: ""}$snippet")
    }
}
