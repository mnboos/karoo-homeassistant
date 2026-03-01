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

data class HaButton(
    val actionId: String, // matches BonusAction actionId in XML
    val displayName: String, // label shown on Karoo button picker
    val domain: String, // e.g. "light", "cover", "switch", "script"
    val service: String, // e.g. "toggle", "turn_on", "turn_off"
    val entityId: String?, // null for script/scene calls that don't need it
)
