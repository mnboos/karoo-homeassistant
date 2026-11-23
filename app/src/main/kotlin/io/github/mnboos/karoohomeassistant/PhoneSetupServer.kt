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

package io.github.mnboos.karoohomeassistant

import io.github.mnboos.karoohomeassistant.extension.AppConfig
import io.github.mnboos.karoohomeassistant.extension.ButtonConfig
import io.github.mnboos.karoohomeassistant.extension.DOMAIN_SERVICES
import io.github.mnboos.karoohomeassistant.extension.EntityListResult
import io.github.mnboos.karoohomeassistant.extension.slot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import timber.log.Timber
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.BindException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tiny HTTP server that lets the user type long values (URL, access token, proxy header) and pick
 * the entities of the button slots on their phone instead of on the Karoo.
 *
 * The server only runs while the "Set up from phone" dialog is open, every submission (including
 * loading the entity list) must carry a one-time PIN shown on the Karoo, and it shuts itself down
 * after [MAX_FAILED_ATTEMPTS] wrong PINs. Secrets in [current] are never sent back to the browser.
 *
 * @param current settings currently shown on the Karoo; used to prefill the page and as the
 *   connection for loading entities when the phone leaves a field empty.
 * @param loadEntities blocking call that lists the Home Assistant entities; runs on a request thread.
 */
class PhoneSetupServer(
    private val current: AppConfig,
    private val loadEntities: (AppConfig) -> EntityListResult,
    private val onReceived: (SetupValues) -> Unit,
    private val onLockedOut: () -> Unit,
) {
    /** Values submitted from the phone. `null` means "leave this unchanged". */
    data class SetupValues(
        val baseUrl: String?,
        val accessToken: String?,
        val headerKey: String?,
        val headerValue: String?,
        val buttons: List<ButtonConfig>? = null,
    ) {
        /** [config] with the submitted connection values applied. */
        fun applyTo(config: AppConfig) = config.copy(
            baseUrl = baseUrl ?: config.baseUrl,
            accessToken = accessToken ?: config.accessToken,
            customHeaderKey = headerKey ?: config.customHeaderKey,
            customHeaderValue = headerValue ?: config.customHeaderValue,
            buttons = buttons ?: config.buttons,
        )
    }

    val pin: String = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')

    @Volatile
    private var serverSocket: ServerSocket? = null
    private val failedAttempts = AtomicInteger(0)

    /** Starts listening and returns the bound port. */
    fun start(): Int {
        val socket = try {
            ServerSocket(DEFAULT_PORT)
        } catch (e: BindException) {
            Timber.w(e, "Port $DEFAULT_PORT busy, using a random port")
            ServerSocket(0)
        }
        serverSocket = socket
        Thread({ acceptLoop(socket) }, "phone-setup-server").apply {
            isDaemon = true
            start()
        }
        return socket.localPort
    }

    fun stop() {
        val socket = serverSocket ?: return
        serverSocket = null
        try {
            socket.close()
        } catch (ignored: IOException) {
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (ignored: IOException) {
                break // socket closed by stop()
            }
            // One thread per connection: browsers open idle pre-connect sockets that would otherwise
            // block the real request until the socket timeout fires.
            Thread({
                try {
                    client.use { handle(it) }
                } catch (e: Exception) {
                    Timber.w(e, "Phone setup request failed")
                }
            }, "phone-setup-request").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        // ISO-8859-1 maps bytes 1:1 to chars, so Content-Length (bytes) == chars to read.
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return respond(client, 400, page("Bad request"))
        val method = parts[0]
        val path = parts[1].substringBefore('?')

        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
            }
        }

        when {
            path != "/" && path != "/entities" -> respond(client, 404, page("Not found"))
            path == "/" && method == "GET" -> respond(client, 200, formPage(error = null))
            method == "POST" -> {
                if (contentLength !in 0..MAX_BODY_BYTES) {
                    return respond(client, 413, page("Request too large"))
                }
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                val form = try {
                    parseForm(String(buf, 0, read))
                } catch (e: IllegalArgumentException) {
                    return respond(client, 400, page("Malformed form data"))
                }
                if (path == "/entities") handleEntities(client, form) else handleSubmit(client, form)
            }
            else -> respond(client, 405, page("Method not allowed"))
        }
    }

    /** `null` if the PIN in [form] is right; otherwise counts the failure and returns the error text. */
    private fun pinError(form: Map<String, String>): String? {
        if (form["pin"]?.trim() == pin) return null
        return if (failedAttempts.incrementAndGet() >= MAX_FAILED_ATTEMPTS) LOCKED_OUT_MESSAGE else "Wrong PIN"
    }

    private fun isLockedOut() = failedAttempts.get() >= MAX_FAILED_ATTEMPTS

    private fun lockOut() {
        stop()
        onLockedOut()
    }

    private fun handleSubmit(client: Socket, form: Map<String, String>) {
        pinError(form)?.let { error ->
            if (isLockedOut()) {
                respond(client, 403, page(error))
                lockOut()
            } else {
                respond(client, 403, formPage(error))
            }
            return
        }
        onReceived(connectionValues(form).copy(buttons = buttonsFrom(form)))
        respond(client, 200, page("Values sent ✔<br>Check them on the Karoo and tap <b>Save</b>."))
    }

    /** Answers the page's "Load entities" request with `{"entities":[{"id","name"}]}` or `{"error"}`. */
    private fun handleEntities(client: Socket, form: Map<String, String>) {
        pinError(form)?.let { error ->
            respondJson(client, 403, buildJsonObject { put("error", error) })
            if (isLockedOut()) lockOut()
            return
        }
        val result = loadEntities(connectionValues(form).applyTo(current))
        val json = if (result.error != null) {
            buildJsonObject { put("error", result.error) }
        } else {
            buildJsonObject {
                putJsonArray("entities") {
                    result.entities.forEach { entity ->
                        addJsonObject {
                            put("id", entity.entityId)
                            put("name", entity.name)
                        }
                    }
                }
            }
        }
        respondJson(client, 200, json)
    }

    private fun connectionValues(form: Map<String, String>): SetupValues {
        fun field(name: String) = form[name]?.trim()?.takeIf { it.isNotEmpty() }
        return SetupValues(
            baseUrl = field("baseUrl"),
            accessToken = field("accessToken"),
            headerKey = field("headerKey"),
            headerValue = field("headerValue"),
        )
    }

    /** Slot configs from the form, or `null` if the form has no slot fields. */
    private fun buttonsFrom(form: Map<String, String>): List<ButtonConfig>? {
        if (AppConfig.SLOT_IDS.indices.none { form.containsKey("slot${it + 1}_entity") }) return null
        return AppConfig.SLOT_IDS.mapIndexed { index, slotId ->
            val n = index + 1
            val existing = current.slot(slotId)
            val name = form["slot${n}_name"]?.trim().orEmpty().take(MAX_NAME_LENGTH)
            val entity = form["slot${n}_entity"]?.trim()
            when {
                entity == null -> existing
                entity == KEEP -> existing.copy(displayName = name.ifEmpty { existing.displayName })
                entity.isEmpty() -> AppConfig.unconfiguredSlot(slotId).let { it.copy(displayName = name.ifEmpty { it.displayName }) }
                !ENTITY_ID.matches(entity) -> existing
                else -> {
                    val domain = entity.substringBefore('.')
                    val services = DOMAIN_SERVICES[domain].orEmpty()
                    val service = form["slot${n}_service"]?.trim()?.takeIf { it in services } ?: services.firstOrNull()
                    if (service == null) {
                        existing
                    } else {
                        ButtonConfig(slotId, name.ifEmpty { entity }, domain, service, entity)
                    }
                }
            }
        }
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }

    private fun respondJson(client: Socket, status: Int, json: JsonObject) =
        respond(client, status, json.toString(), "application/json; charset=utf-8")

    private fun respond(client: Socket, status: Int, content: String, contentType: String = "text/html; charset=utf-8") {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val body = content.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(header.toByteArray(Charsets.ISO_8859_1))
            write(body)
            flush()
        }
    }

    private fun formPage(error: String?): String = page(
        """
        ${error?.let { "<p class=\"err\">${escape(it)}</p>" } ?: ""}
        <form id="setup" method="post" action="/" autocomplete="off">
          <label>PIN shown on the Karoo
            <input name="pin" inputmode="numeric" required></label>
          <h3>Connection</h3>
          <label>HA Base URL
            <input name="baseUrl" type="url" value="${escape(current.baseUrl)}"
                   placeholder="https://homeassistant.example.com"></label>
          <label>Home Assistant Token <small>(HA profile → Security → Long-lived access tokens)</small>
            <textarea name="accessToken" rows="4" placeholder="Leave empty to keep current"></textarea></label>
          <label>Proxy Header Name <small>(optional, e.g. for Cloudflare)</small>
            <input name="headerKey" value="${escape(current.customHeaderKey)}" placeholder="CF-Access-Client-Secret"></label>
          <label>Proxy Header Value
            <textarea name="headerValue" rows="3" placeholder="Leave empty to keep current"></textarea></label>
          <h3>Buttons</h3>
          <p class="hint">Enter the PIN (and the connection, if it is new), then load the entities.
            Listing entities needs the token of a Home Assistant admin user.</p>
          <button type="button" id="load" class="secondary">Load entities from Home Assistant</button>
          <p id="status" class="hint"></p>
          <input id="filter" type="search" placeholder="Filter entities…" disabled>
          ${AppConfig.SLOT_IDS.mapIndexed { index, slotId -> slotFields(index + 1, current.slot(slotId)) }.joinToString("\n")}
          <button type="submit">Send to Karoo</button>
        </form>
        <script>${script()}</script>
        """.trimIndent(),
    )

    private fun slotFields(n: Int, button: ButtonConfig): String {
        val entityOptions = buildString {
            if (button.isConfigured) {
                append("<option value=\"$KEEP\" selected>Keep current: ${escape(button.summary)}</option>")
            }
            append("<option value=\"\">(not set)</option>")
        }
        val serviceOption = if (button.isConfigured) escape(button.service) else ""
        return """
            <div class="slot">
              <h4>Slot $n</h4>
              <label>Entity
                <select name="slot${n}_entity" data-slot="$n">$entityOptions</select></label>
              <label>Action
                <select name="slot${n}_service" data-current="$serviceOption"><option value="$serviceOption">${serviceOption.ifEmpty { "—" }}</option></select></label>
              <label>Label on the Karoo
                <input name="slot${n}_name" value="${escape(button.displayName)}" maxlength="$MAX_NAME_LENGTH"></label>
            </div>
        """.trimIndent()
    }

    /**
     * Loads the entity list (POST /entities with the whole form, so the PIN and any new connection
     * values are used), fills the entity selects grouped by domain and keeps action and label in sync
     * with the chosen entity. All entity text goes through textContent, never innerHTML.
     */
    private fun script(): String {
        val services = buildJsonObject {
            DOMAIN_SERVICES.forEach { (domain, list) -> putJsonArray(domain) { list.forEach { add(it) } } }
        }
        // Wrapped in a function: a global `var status` would hit the legacy window.status string.
        return """
            (function () {
            var SERVICES = $services;
            var KEEP = ${JsonPrimitive(KEEP)};
            var entities = [];
            var form = document.getElementById('setup');
            var status = document.getElementById('status');
            var filter = document.getElementById('filter');
            var selects = Array.prototype.slice.call(document.querySelectorAll('select[data-slot]'));
            function el(n) { return form.elements[n]; }
            function setStatus(text, isError) { status.textContent = text; status.className = isError ? 'err' : 'hint'; }
            function fillEntities(sel, q) {
              var chosen = sel.value;
              Array.prototype.slice.call(sel.querySelectorAll('optgroup')).forEach(function (g) { g.remove(); });
              var group = null, domain = null;
              entities.forEach(function (e) {
                var hit = !q || e.id.toLowerCase().indexOf(q) >= 0 || e.name.toLowerCase().indexOf(q) >= 0;
                if (!hit && e.id !== chosen) return;
                var d = e.id.split('.')[0];
                if (d !== domain) {
                  domain = d;
                  group = document.createElement('optgroup');
                  group.label = d;
                  sel.appendChild(group);
                }
                var o = document.createElement('option');
                o.value = e.id;
                o.textContent = e.name + ' (' + e.id + ')';
                group.appendChild(o);
              });
              sel.value = chosen;
            }
            function onEntityChange(sel) {
              var n = sel.getAttribute('data-slot');
              var svc = el('slot' + n + '_service');
              var name = el('slot' + n + '_name');
              var v = sel.value;
              var list = v === KEEP ? [svc.getAttribute('data-current')] : (v ? (SERVICES[v.split('.')[0]] || []) : ['']);
              while (svc.firstChild) svc.removeChild(svc.firstChild);
              list.forEach(function (s) {
                var o = document.createElement('option');
                o.value = s;
                o.textContent = s || '—';
                svc.appendChild(o);
              });
              if (!name.getAttribute('data-edited')) {
                var e = entities.filter(function (x) { return x.id === v; })[0];
                name.value = e ? e.name.slice(0, $MAX_NAME_LENGTH) : name.defaultValue;
              }
            }
            selects.forEach(function (sel) {
              sel.addEventListener('change', function () { onEntityChange(sel); });
              el('slot' + sel.getAttribute('data-slot') + '_name').addEventListener('input', function () {
                this.setAttribute('data-edited', '1');
              });
            });
            filter.addEventListener('input', function () {
              var q = filter.value.trim().toLowerCase();
              selects.forEach(function (sel) { fillEntities(sel, q); });
            });
            document.getElementById('load').addEventListener('click', function () {
              if (!el('pin').value.trim()) { setStatus('Enter the PIN first', true); return; }
              setStatus('Loading… (up to 20 s)', false);
              fetch('/entities', { method: 'POST', body: new URLSearchParams(new FormData(form)) })
                .then(function (r) { return r.json(); })
                .then(function (data) {
                  if (data.error) { setStatus(data.error, true); return; }
                  entities = data.entities;
                  filter.disabled = false;
                  selects.forEach(function (sel) { fillEntities(sel, filter.value.trim().toLowerCase()); });
                  setStatus(entities.length + ' entities loaded — pick one per slot', false);
                })
                .catch(function (err) { setStatus('Loading failed: ' + err, true); });
            });
            })();
        """.trimIndent()
    }

    private fun page(content: String): String = """
        <!doctype html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Karoo HA setup</title>
        <style>
          body{font-family:system-ui,sans-serif;margin:0 auto;max-width:480px;padding:16px;}
          label{display:block;margin:12px 0;font-weight:600;}
          input,textarea,select{display:block;width:100%;box-sizing:border-box;margin-top:4px;padding:10px;
            font:inherit;font-weight:normal;border:1px solid #999;border-radius:6px;background:#fff;}
          button{width:100%;padding:12px;font:inherit;font-weight:600;border:0;border-radius:6px;
            background:#03a9f4;color:#fff;margin-top:8px;}
          button.secondary{background:#607d8b;}
          h3{margin:24px 0 4px;}
          h4{margin:0;}
          .slot{border-top:1px solid #ccc;margin-top:16px;padding-top:12px;}
          .hint{color:#555;font-size:.9em;}
          .err{color:#c62828;font-weight:600;}
        </style></head>
        <body><h2>Home Assistant → Karoo</h2>$content</body></html>
    """.trimIndent()

    private fun escape(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {
        private const val DEFAULT_PORT = 8080
        private const val MAX_BODY_BYTES = 16 * 1024
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val MAX_NAME_LENGTH = 40
        private const val LOCKED_OUT_MESSAGE = "Too many wrong PINs. Setup stopped — reopen it on the Karoo."

        /** Entity select value meaning "leave this slot's action as it is". */
        private const val KEEP = "__keep__"
        private val ENTITY_ID = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")

        /** First site-local IPv4 address of an active interface (normally the WiFi address). */
        fun localIpv4(): String? = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            Timber.w(e, "Could not determine local IP")
            null
        }
    }
}
