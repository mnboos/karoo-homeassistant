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

package io.hammerhead.sampleext

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.hammerhead.sampleext.extension.AppConfig
import io.hammerhead.sampleext.extension.ButtonConfig

@Composable
fun ConfigScreen() {
    val context = LocalContext.current
    val initial = remember { AppConfig.load(context) }

    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var accessToken by remember { mutableStateOf(initial.accessToken) }
    var buttons by remember { mutableStateOf(initial.buttons.toMutableList()) }
    var tokenVisible by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Home Assistant Settings",
            style = MaterialTheme.typography.headlineSmall,
        )

        // Connection card
        SectionCard(title = "Connection") {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("HA Base URL") },
                placeholder = { Text("http://homeassistant.local:8123") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = accessToken,
                onValueChange = { accessToken = it },
                label = { Text("Access Token") },
                placeholder = { Text("Long-lived access token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = if (tokenVisible) "Hide token" else "Show token",
                            tint = if (tokenVisible) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
        }

        // Buttons card
        SectionCard(title = "Buttons") {
            buttons.forEachIndexed { index, btn ->
                if (index > 0) HorizontalDivider()
                ButtonRow(
                    slotNumber = index + 1,
                    button = btn,
                    onEditClick = { editingIndex = index },
                )
            }
        }

        // Save + Reset buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    val newConfig = AppConfig(baseUrl.trim(), accessToken.trim(), buttons.toList())
                    AppConfig.save(context, newConfig)
                    showToast(context, "Settings saved")
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Save")
            }
            TextButton(
                onClick = {
                    AppConfig.reset(context)
                    val defaults = AppConfig.load(context)
                    baseUrl = defaults.baseUrl
                    accessToken = defaults.accessToken
                    buttons = defaults.buttons.toMutableList()
                    showToast(context, "Reset to defaults")
                },
            ) {
                Text("Reset")
            }
        }
    }

    // Edit button dialog
    editingIndex?.let { idx ->
        ButtonEditDialog(
            slotNumber = idx + 1,
            initial = buttons[idx],
            onDismiss = { editingIndex = null },
            onConfirm = { updated ->
                buttons = buttons.toMutableList().also { it[idx] = updated }
                editingIndex = null
            },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ButtonRow(slotNumber: Int, button: ButtonConfig, onEditClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEditClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Slot $slotNumber: ${button.displayName}", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${button.domain}.${button.service}" + (button.entityId?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEditClick) {
            Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit slot $slotNumber")
        }
    }
}

@Composable
private fun ButtonEditDialog(
    slotNumber: Int,
    initial: ButtonConfig,
    onDismiss: () -> Unit,
    onConfirm: (ButtonConfig) -> Unit,
) {
    var displayName by remember { mutableStateOf(initial.displayName) }
    var domain by remember { mutableStateOf(initial.domain) }
    var service by remember { mutableStateOf(initial.service) }
    var entityId by remember { mutableStateOf(initial.entityId ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Slot $slotNumber") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text("Domain") },
                    placeholder = { Text("cover, light, switch, script…") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = service,
                    onValueChange = { service = it },
                    label = { Text("Service") },
                    placeholder = { Text("toggle, turn_on, turn_off…") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = entityId,
                    onValueChange = { entityId = it },
                    label = { Text("Entity ID (optional)") },
                    placeholder = { Text("cover.garage") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        initial.copy(
                            displayName = displayName.trim(),
                            domain = domain.trim(),
                            service = service.trim(),
                            entityId = entityId.trim().takeIf { it.isNotEmpty() },
                        ),
                    )
                },
                enabled = displayName.isNotBlank() && domain.isNotBlank() && service.isNotBlank(),
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun showToast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
