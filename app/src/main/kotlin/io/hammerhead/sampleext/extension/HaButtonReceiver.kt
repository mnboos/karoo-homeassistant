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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.internal.ViewEmitter
import timber.log.Timber

fun interface HaClickable {
    fun handleClick(isConfirmation: Boolean, emitter: ViewEmitter)
}

class HaButtonReceiver : BroadcastReceiver() {
    companion object {
        // Static reference to provide the KarooSystemService
        var karooSystemServiceProvider: (() -> KarooSystemService)? = null

        // Store references to data types for handling state
        val dataTypeInstances = mutableMapOf<String, Pair<HaClickable, ViewEmitterProvider>>()

        fun registerDataType(
            buttonId: String,
            dataType: HaClickable,
            emitterProvider: ViewEmitterProvider,
        ) {
            dataTypeInstances[buttonId] = Pair(dataType, emitterProvider)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_HA_BUTTON_CLICKED) {
            val buttonId = intent.getStringExtra(EXTRA_BUTTON_ID) ?: return
            val isConfirmation = intent.getBooleanExtra("confirmation", false)
            Timber.d("Button pressed: $buttonId (confirmation=$isConfirmation)")

            val (dataType, emitterProvider) = dataTypeInstances[buttonId]
                ?: run {
                    Timber.w("Data type not found for button: $buttonId")
                    return
                }

            val emitter = emitterProvider.get()
            if (emitter != null) {
                dataType.handleClick(isConfirmation, emitter)
            } else {
                Timber.w("Emitter not available for button: $buttonId")
            }
        }
    }
}

fun interface ViewEmitterProvider {
    fun get(): ViewEmitter?
}
