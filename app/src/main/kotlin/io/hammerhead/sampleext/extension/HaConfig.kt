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

package io.hammerhead.sampleext.extension

object HaConfig {
    const val BASE_URL = "http://homeassistant.local:8123" // TODO: change
    const val ACCESS_TOKEN = "YOUR_LONG_LIVED_ACCESS_TOKEN" // TODO: change

    val buttons = listOf(
        HaButton("ha-btn-1", "Garage", "cover", "toggle", "cover.garage"),
        HaButton("ha-btn-2", "Lights", "light", "toggle", "light.outdoor"),
        HaButton("ha-btn-3", "Fan", "switch", "toggle", "switch.fan"),
    )
}
