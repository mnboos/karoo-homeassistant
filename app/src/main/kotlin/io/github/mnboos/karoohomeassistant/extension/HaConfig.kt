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

import android.content.Context
import io.github.mnboos.karoohomeassistant.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * One button slot. [actionId] matches a `typeId` in `extension_info.xml`; a slot without
 * [domain] / [service] is not set up yet and must not call Home Assistant.
 */
@Serializable
data class ButtonConfig(
    val actionId: String,
    val displayName: String,
    val domain: String = "",
    val service: String = "",
    val entityId: String? = null,
) {
    val isConfigured: Boolean get() = domain.isNotBlank() && service.isNotBlank()

    /** Short description of what the slot does, e.g. `light.toggle · light.outdoor`. */
    val summary: String
        get() = if (!isConfigured) "Not set" else "$domain.$service" + (entityId?.let { " · $it" } ?: "")
}

/** The saved config of slot [actionId], or an unconfigured placeholder if there is none. */
fun AppConfig.slot(actionId: String): ButtonConfig =
    buttons.firstOrNull { it.actionId == actionId } ?: AppConfig.unconfiguredSlot(actionId)

/** Full URL of a Home Assistant REST endpoint, e.g. `apiUrl("services/light/toggle")`. */
fun AppConfig.apiUrl(path: String) = "${baseUrl.trim().trimEnd('/')}/api/$path"

/** Headers sent with every Home Assistant request. */
fun AppConfig.requestHeaders(): Map<String, String> = buildMap {
    // No token: authentication may be injected by a proxy; an empty
    // "Bearer " header would make Home Assistant reject the request.
    if (accessToken.isNotBlank()) {
        put("Authorization", "Bearer ${accessToken.trim()}")
    }
    put("Content-Type", "application/json")
    // Optional extra header (e.g. for a reverse proxy / Cloudflare Access). Added last so it
    // can deliberately override a default header.
    if (customHeaderKey.isNotBlank()) {
        put(customHeaderKey.trim(), customHeaderValue.trim())
    }
}

@Serializable
data class AppConfig(
    val baseUrl: String = BuildConfig.HA_BASE_URL,
    val accessToken: String = BuildConfig.HA_ACCESS_TOKEN,
    val customHeaderKey: String = "",
    val customHeaderValue: String = "",
    val buttons: List<ButtonConfig> = defaultButtons,
) {
    companion object {
        /** Slot ids; must match the `typeId`s declared in `res/xml/extension_info.xml`. */
        val SLOT_IDS = listOf("ha-btn-1", "ha-btn-2", "ha-btn-3")

        fun unconfiguredSlot(actionId: String) =
            ButtonConfig(actionId, "Button ${SLOT_IDS.indexOf(actionId) + 1}")

        val defaultButtons = SLOT_IDS.map { unconfiguredSlot(it) }

        private const val PREFS_NAME = "ha_config"
        private const val KEY_CONFIG = "config"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun load(context: Context): AppConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_CONFIG, null) ?: return AppConfig()
            return try {
                json.decodeFromString(raw)
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse AppConfig, using defaults")
                AppConfig()
            }
        }

        fun save(context: Context, config: AppConfig) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_CONFIG, json.encodeToString(config)).apply()
        }

        fun reset(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }
}
