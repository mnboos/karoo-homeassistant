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

import dagger.hilt.android.AndroidEntryPoint
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.sampleext.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class HaExtension : KarooExtension("homeassistant", "1.0") {
    @Inject
    lateinit var karooSystem: KarooSystemService

    private var serviceJob: Job? = null

    override val types: List<DataTypeImpl> = emptyList()

    override fun onBonusAction(actionId: String) {
        val button = HaConfig.buttons.find { it.actionId == actionId }
        if (button != null) {
            callHa(button)
        } else {
            Timber.w("Unknown action $actionId")
        }
    }

    private fun callHa(button: HaButton) {
        val url = "${HaConfig.BASE_URL}/api/services/${button.domain}/${button.service}"
        val headers = mapOf(
            "Authorization" to "Bearer ${HaConfig.ACCESS_TOKEN}",
            "Content-Type" to "application/json",
        )
        val bodyJson = if (button.entityId != null) {
            """{"entity_id":"${button.entityId}"}"""
        } else {
            "{}"
        }

        val listenerRef = object {
            var id: String = ""
        }

        listenerRef.id = karooSystem.addConsumer(
            OnHttpResponse.MakeHttpRequest(
                method = "POST",
                url = url,
                headers = headers,
                body = bodyJson.toByteArray(),
            ),
        ) { response: OnHttpResponse ->
            when (val state = response.state) {
                is HttpResponseState.Complete -> {
                    Timber.d("HA response for ${button.displayName}: ${state.statusCode}")
                    if (state.statusCode in 200..299) {
                        karooSystem.dispatch(
                            InRideAlert(
                                id = "ha-action-${button.actionId}",
                                icon = R.drawable.ic_sample,
                                title = button.displayName,
                                detail = "Command sent to Home Assistant",
                                autoDismissMs = 3_000,
                                backgroundColor = R.color.colorPrimary,
                                textColor = R.color.white,
                            ),
                        )
                    } else {
                        val errorMsg = state.error ?: "HTTP ${state.statusCode}"
                        Timber.e("HA error for ${button.displayName}: $errorMsg")
                    }
                    // Remove the consumer to avoid repeated calls
                    karooSystem.removeConsumer(listenerRef.id)
                }

                else -> {
                    Timber.d("HA request for ${button.displayName} in progress...")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            karooSystem.connect { connected ->
                if (connected) {
                    Timber.d("Home Assistant extension started")
                }
            }
        }
    }

    override fun onDestroy() {
        serviceJob?.cancel()
        serviceJob = null
        karooSystem.disconnect()
        super.onDestroy()
    }
}
