package io.hammerhead.karoo_homeassistant.screens

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("ha_prefs", Context.MODE_PRIVATE) }

    var url by remember { mutableStateOf(sharedPrefs.getString("ha_url", "https://your-ha-instance/api/services/input_button/press") ?: "") }
    var token by remember { mutableStateOf(sharedPrefs.getString("ha_token", "") ?: "") }
    var entityId by remember { mutableStateOf(sharedPrefs.getString("ha_entity_id", "input_button.garage_door") ?: "") }
    var statusText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home Assistant Configuration") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Service URL") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Long-Lived Access Token") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1
            )

            OutlinedTextField(
                value = entityId,
                onValueChange = { entityId = it },
                label = { Text("Entity ID") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    sharedPrefs.edit()
                        .putString("ha_url", url)
                        .putString("ha_token", token)
                        .putString("ha_entity_id", entityId)
                        .apply()
                    statusText = "Configuration Saved!"
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Configuration")
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = {
                    statusText = "Sending test request..."
                    scope.launch {
                        val success = triggerHomeAssistant(url, token, entityId)
                        statusText = if (success) "Success!" else "Failed. Check logs."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Test Configuration")
            }

            if (statusText.isNotEmpty()) {
                Text(
                    text = statusText,
                    color = if (statusText.contains("Failed")) Color.Red else Color.Green,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

suspend fun triggerHomeAssistant(urlString: String, token: String, entityId: String): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000

            val jsonInputString = "{\"entity_id\": \"$entityId\"}"
            conn.outputStream.use { it.write(jsonInputString.toByteArray()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            Log.e("HA_APP", "Error: ${e.message}")
            false
        }
    }
}
