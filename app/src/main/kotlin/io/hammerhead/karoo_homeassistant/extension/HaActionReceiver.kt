package io.hammerhead.karoo_homeassistant.extension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

class HaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "io.hammerhead.karoo_homeassistant.HA_ACTION") {
            Log.d("HaActionReceiver", "HA_ACTION received!")
            
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val sharedPrefs = context.getSharedPreferences("ha_prefs", Context.MODE_PRIVATE)
                    val urlString = sharedPrefs.getString("ha_url", "") ?: ""
                    val token = sharedPrefs.getString("ha_token", "") ?: ""
                    val entityId = sharedPrefs.getString("ha_entity_id", "") ?: ""

                    if (urlString.isNotEmpty() && token.isNotEmpty() && entityId.isNotEmpty()) {
                        val url = URL(urlString)
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Authorization", "Bearer $token")
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true
                        conn.connectTimeout = 5000

                        val jsonInputString = "{\"entity_id\": \"$entityId\"}"
                        conn.outputStream.use { it.write(jsonInputString.toByteArray()) }
                        
                        val responseCode = conn.responseCode
                        Log.d("HaActionReceiver", "HA Response: $responseCode")
                    } else {
                        Log.e("HaActionReceiver", "Missing config: URL, token or entity ID")
                    }
                } catch (e: Exception) {
                    Log.e("HaActionReceiver", "Error triggering HA: ${e.message}")
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
