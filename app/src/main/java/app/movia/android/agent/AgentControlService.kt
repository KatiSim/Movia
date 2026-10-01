package app.movia.android.agent

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

class AgentControlService(
    private val context: Context,
    private val state: AgentStateRepository,
    private val events: AgentEventBus,
    private val actionHandler: (JSONObject) -> JSONObject,
) {
    private val running = AtomicBoolean(false)
    private val worker: ExecutorService = ThreadPoolExecutor(4, 8, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(16))
    private val sseSlots = Semaphore(2)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    val tokenFile: java.io.File = createTokenFile(context)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Log.i(TAG, "agent bridge start requested on 127.0.0.1:$MOVIA_AGENT_PORT")
        acceptThread = Thread {
            try {
                serverSocket = ServerSocket(
                    MOVIA_AGENT_PORT,
                    20,
                    InetAddress.getByName("127.0.0.1"),
                )
                while (running.get()) {
                    val socket = serverSocket?.accept() ?: break
                    try {
                        worker.execute { runCatching { handle(socket) }.onFailure { runCatching { socket.close() } } }
                    } catch (_: RejectedExecutionException) {
                        runCatching { respond(socket, 503, error("BUSY", "Bridge capacity reached")) }
                        socket.close()
                    }
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "agent bridge stopped", throwable)
                if (running.get()) events.publish(
                    "AGENT_BRIDGE_ERROR",
                    details = mapOf("code" to "SERVER_STOPPED"),
                )
            }
        }.also { thread ->
            thread.name = "movia-agent-bridge"
            thread.isDaemon = true
            thread.start()
        }
    }

    @Synchronized
    fun replaceToken(token: String): Boolean {
        val normalized = normalizeToken(token) ?: return false
        return writeTokenAtomically(tokenFile, normalized)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        worker.shutdownNow()
        acceptThread?.interrupt()
        acceptThread = null
        serverSocket = null
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15_000
            // Content-Length is measured in UTF-8 bytes, not Kotlin/Java characters.
            // Read the HTTP frame as bytes first so Cyrillic action payloads cannot
            // leave the handler waiting for characters that will never arrive.
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readHttpLine(input) ?: return
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readHttpLine(input) ?: return
                if (line.isEmpty()) break
                if (headers.size >= 64) { respond(client, 400, error("INVALID_REQUEST", "Too many headers")); return }
                val separator = line.indexOf(':')
                if (separator > 0) {
                    val key = line.substring(0, separator).trim().lowercase()
                    if (headers.containsKey(key)) { respond(client, 400, error("INVALID_REQUEST", "Duplicate header")); return }
                    headers[key] = line.substring(separator + 1).trim()
                }
            }
            if (!isAuthorized(headers)) { respond(client, 401, error("UNAUTHORIZED", "Valid local bridge token required")); return }
            if (headers.containsKey("transfer-encoding")) { respond(client, 400, error("INVALID_REQUEST", "Transfer encoding is unsupported")); return }
            val rawLength = headers["content-length"]
            val parsedLength = rawLength?.toLongOrNull() ?: if (rawLength == null) 0L else -1L
            if (parsedLength < 0L) { respond(client, 400, error("INVALID_REQUEST", "Invalid Content-Length")); return }
            if (parsedLength > 1_000_000L) { respond(client, 413, error("PAYLOAD_TOO_LARGE", "Body limit exceeded")); return }
            val length = parsedLength.toInt()
            val bodyBytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(bodyBytes, read, length - read)
                if (count <= 0) break
                read += count
            }
            if (read != length) { respond(client, 400, error("INVALID_REQUEST", "Truncated body")); return }

            val firstSpace = requestLine.indexOf(' ')
            val secondSpace = requestLine.indexOf(' ', firstSpace + 1)
            if (firstSpace <= 0 || secondSpace <= firstSpace) {
                respond(client, 400, error("INVALID_REQUEST", "Malformed HTTP request"))
                return
            }
            val method = requestLine.substring(0, firstSpace)
            val target = requestLine.substring(firstSpace + 1, secondSpace)
            val queryIndex = target.indexOf('?')
            val rawPath = if (queryIndex >= 0) target.substring(0, queryIndex) else target
            val query = if (queryIndex >= 0) target.substring(queryIndex + 1) else ""
            val path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name())
            if (!isAuthorized(headers)) {
                respond(client, 401, error("UNAUTHORIZED", "Valid local bridge token required"))
                return
            }

            if (method == "GET" && path == "/agent/v1/events/stream") {
                if (!sseSlots.tryAcquire()) { respond(client, 503, error("BUSY", "SSE capacity reached")); return }
                try { streamEvents(client) } finally { sseSlots.release() }
                return
            }
            val payload = runCatching {
                when {
                    method == "GET" && path == "/agent/v1/health" -> AgentControlRuntime.healthJson(context)
                    method == "GET" && path == "/agent/v1/snapshot" -> state.snapshotJson()
                    method == "GET" && path == "/agent/v1/actions" -> JSONObject()
                        .put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION)
                        .put("actions", state.actionsJson())
                    method == "GET" && path == "/agent/v1/ui" -> state.uiTreeJson()
                    method == "GET" && path == "/agent/v1/ui/controls" -> state.controlsManifestJson()
                    method == "GET" && path == "/agent/v1/events" -> eventsJson(query)
                    method == "GET" && path == "/agent/v1/settings" -> JSONObject()
                        .put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION)
                        .put("settings", state.currentSettingsJson())
                    method == "GET" && path == "/agent/v1/streams" -> AgentControlRuntime.streamsJson()
                    method == "GET" && path == "/agent/v1/diagnostics" -> AgentControlRuntime.diagnosticsJson()
                    method == "GET" && path == "/agent/v1/capabilities" -> AgentControlRuntime.capabilitiesJson()
                    method == "GET" && path == "/agent/v1/manifest" -> AgentControlRuntime.manifestJson()
                    method == "GET" && path == "/agent/v1/operations" -> {
                        val operationId = queryParam(query, "operationId")
                        if (operationId.isNullOrBlank()) {
                            error("INVALID_ARGUMENT", "operationId query parameter is required")
                        } else {
                            AgentControlRuntime.operationJson(operationId)
                                ?.let { JSONObject().put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION).put("operation", it) }
                                ?: error("OPERATION_NOT_FOUND", "Unknown operationId")
                        }
                    }
                    method == "POST" && path == "/agent/v1/action" -> actionHandler(
                        if (read == 0) JSONObject() else JSONObject(String(bodyBytes, 0, read, StandardCharsets.UTF_8)),
                    )
                    else -> error("NOT_FOUND", "Unknown Movia agent endpoint")
                }
            }.getOrElse { throwable ->
                error("BAD_REQUEST", throwable.message ?: "Invalid request")
            }
            val status = when {
                payload.optString("status") != "failed" -> 200
                payload.optString("code") == "NOT_FOUND" || payload.optString("code") == "OPERATION_NOT_FOUND" -> 404
                payload.optString("code") == "UNAUTHORIZED" -> 401
                else -> 400
            }
            respond(client, status, payload)
        }
    }

    private fun readHttpLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                return if (bytes.size() == 0) null
                else String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
            }
            if (value == '\n'.code) {
                val raw = bytes.toByteArray()
                val length = if (raw.isNotEmpty() && raw.last().toInt() == '\r'.code) {
                    raw.size - 1
                } else {
                    raw.size
                }
                return String(raw, 0, length, StandardCharsets.ISO_8859_1)
            }
            bytes.write(value)
            if (bytes.size() > 16_384) {
                throw IllegalArgumentException("HTTP header line too long")
            }
        }
    }

    private fun streamEvents(socket: Socket) {
        val output = socket.getOutputStream()
        output.write(("HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: keep-alive\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
        output.flush()
        kotlinx.coroutines.runBlocking {
            try {
                kotlinx.coroutines.flow.merge(
                    events.events.map { event -> "event: " + event.event + "\ndata: " + event.toJson() + "\n\n" },
                    kotlinx.coroutines.flow.flow { while (true) { kotlinx.coroutines.delay(15_000L); emit(": keep-alive\n\n") } },
                ).collect { message ->
                    output.write(message.toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
            } catch (_: Throwable) {
                // The client closing an SSE connection is a normal lifecycle event.
            }
        }
    }

    private fun queryParam(query: String, key: String): String? =
        query.split('&')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator < 0) return@mapNotNull null
                val name = URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8.name())
                val value = URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8.name())
                name to value
            }
            .firstOrNull { it.first == key }
            ?.second

    private fun eventsJson(query: String): JSONObject {
        val limit = query.split('&')
            .firstOrNull { it.startsWith("limit=") }
            ?.substringAfter('=')
            ?.toIntOrNull()
            ?.coerceIn(1, 1000)
            ?: 100
        return JSONObject().apply {
            put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION)
            put("events", org.json.JSONArray().apply {
                events.snapshot(limit).forEach { put(it.toJson()) }
            })
        }
    }

    private fun respond(socket: Socket, status: Int, payload: JSONObject) {
        val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val output = socket.getOutputStream()
        output.write(
            (
                "HTTP/1.1 " + status + " " + reason + "\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: " + bytes.size + "\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(StandardCharsets.UTF_8),
        )
        output.write(bytes)
        output.flush()
    }

    private fun error(code: String, message: String): JSONObject =
        JSONObject()
            .put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION)
            .put("status", "failed")
            .put("code", code)
            .put("message", message)
            .put("retryable", code != "NOT_FOUND" && code != "UNAUTHORIZED")

    private companion object {
        const val TAG = "MoviaAgent"
        const val TOKEN_BYTE_COUNT = 32
        private val TOKEN_PATTERN = Regex("^[0-9a-fA-F]{64}$")

        fun createTokenFile(context: Context): File {
            val directory = File(context.filesDir, "agent")
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
                error("Unable to create private agent token directory")
            }
            check(directory.isDirectory) { "Agent token path is not a directory" }
            check(setPrivateDirectoryPermissions(directory)) { "Unable to secure agent token directory" }

            val file = File(directory, "movia-agent.token")
            if (readToken(file) == null) {
                check(writeTokenAtomically(file, generateToken())) { "Unable to create agent token" }
            }
            check(setOwnerOnlyPermissions(file)) { "Unable to secure agent token" }
            return file
        }

        private fun generateToken(): String {
            val bytes = ByteArray(TOKEN_BYTE_COUNT)
            SecureRandom().nextBytes(bytes)
            val hex = "0123456789abcdef"
            return buildString(TOKEN_BYTE_COUNT * 2) {
                bytes.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(hex[value ushr 4])
                    append(hex[value and 0x0f])
                }
            }
        }

        private fun normalizeToken(token: String): String? =
            token.takeIf { TOKEN_PATTERN.matches(it) }?.lowercase()

        private fun readToken(file: File): String? = runCatching {
            normalizeToken(file.readText(StandardCharsets.US_ASCII).trim())
        }.getOrNull()

        private fun setOwnerOnlyPermissions(file: File): Boolean {
            val cleared = listOf(
                file.setReadable(false, false),
                file.setWritable(false, false),
                file.setExecutable(false, false),
            ).all { it }
            val ownerOnly = listOf(
                file.setReadable(true, true),
                file.setWritable(true, true),
                file.setExecutable(false, true),
            ).all { it }
            return cleared && ownerOnly
        }

        private fun setPrivateDirectoryPermissions(directory: File): Boolean {
            val cleared = listOf(
                directory.setReadable(false, false),
                directory.setWritable(false, false),
                directory.setExecutable(false, false),
            ).all { it }
            val ownerOnly = listOf(
                directory.setReadable(true, true),
                directory.setWritable(true, true),
                directory.setExecutable(true, true),
            ).all { it }
            return cleared && ownerOnly
        }

        private fun writeTokenAtomically(target: File, token: String): Boolean {
            val normalized = normalizeToken(token) ?: return false
            val directory = target.parentFile ?: return false
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) return false
            if (!directory.isDirectory || !setPrivateDirectoryPermissions(directory)) return false

            val temporary = runCatching {
                File.createTempFile(".movia-agent-token-", ".tmp", directory)
            }.getOrNull() ?: return false
            return try {
                if (!setOwnerOnlyPermissions(temporary)) return false
                FileOutputStream(temporary).use { output ->
                    output.write(normalized.toByteArray(StandardCharsets.US_ASCII))
                    output.fd.sync()
                }
                if (!setOwnerOnlyPermissions(temporary)) return false
                // Both paths are in the app-private directory: rename is the atomic replacement.
                temporary.renameTo(target)
            } catch (_: Throwable) {
                false
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }

    private fun isAuthorized(headers: Map<String, String>): Boolean {
        val authorization = headers["authorization"] ?: return false
        val prefix = "Bearer "
        if (!authorization.startsWith(prefix)) return false
        val supplied = normalizeToken(authorization.substring(prefix.length)) ?: return false
        val stored = runCatching {
            normalizeToken(tokenFile.readText(StandardCharsets.US_ASCII).trim())
        }.getOrNull() ?: return false
        return MessageDigest.isEqual(
            supplied.toByteArray(StandardCharsets.US_ASCII),
            stored.toByteArray(StandardCharsets.US_ASCII),
        )
    }
}
