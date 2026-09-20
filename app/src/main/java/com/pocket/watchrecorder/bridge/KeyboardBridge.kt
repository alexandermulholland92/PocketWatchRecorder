package com.pocket.watchrecorder.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import kotlin.random.Random

/**
 * Lets a phone's keyboard type into the watch.
 *
 * The watch briefly serves one page on the local network; you open it in the
 * phone's browser, type, and the text arrives here. That is all.
 *
 * Why not the obvious alternatives:
 *
 *  - A companion phone app over the Wearable Data Layer is the "proper" route,
 *    but it needs a second module sharing an applicationId *and* a signing
 *    certificate with this one — painful, and brittle the moment a CI-built
 *    APK and a locally built one disagree about signing.
 *  - Wear's own RemoteInput already offers the phone keyboard on some watches,
 *    but whether it does is up to the OEM. This does not depend on that.
 *
 * Deliberate limits, because this opens a socket on a watch:
 *
 *  - It runs only while the screen that started it is open.
 *  - It listens on an ephemeral port, not a predictable one.
 *  - A PIN shown on the watch must be echoed back.
 *  - It stops the instant one valid submission arrives.
 *
 * The text still crosses the LAN as plaintext HTTP, protected only by the
 * Wi-Fi link itself. That is a reasonable trade on a home network for a
 * one-shot secret you are about to store anyway; it is not something to leave
 * running, and the design above makes sure you cannot.
 */
class KeyboardBridge(private val context: Context) {

    private companion object {
        const val TAG = "KeyboardBridge"
        const val SOCKET_TIMEOUT_MS = 15_000
    }

    /** Where to point the phone, and the PIN that page will ask for. */
    data class Endpoint(val host: String, val port: Int, val pin: String) {
        val url: String get() = "http://$host:$port"
    }

    /** Why the bridge could not start. */
    sealed interface Failure {
        /** The watch has no usable Wi-Fi address — a phone cannot reach it. */
        data object NoWifi : Failure

        data class Unavailable(val reason: String) : Failure
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    /**
     * Starts listening. Returns the endpoint to display, or a [Failure].
     *
     * [onText] is called on the caller's scope when a valid submission lands,
     * after which the bridge stops itself.
     */
    suspend fun start(
        scope: CoroutineScope,
        onText: (String) -> Unit
    ): Result<Endpoint> = withContext(Dispatchers.IO) {
        stop()

        val host = wifiAddress()
            ?: return@withContext Result.failure(BridgeException(Failure.NoWifi))

        val socket = runCatching { ServerSocket(0) }.getOrElse {
            Log.e(TAG, "Could not open a socket", it)
            return@withContext Result.failure(
                BridgeException(Failure.Unavailable(it.message ?: "socket refused"))
            )
        }
        serverSocket = socket

        val pin = Random.nextInt(1000, 10000).toString()
        Log.i(TAG, "Listening on ${host}:${socket.localPort}")

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                val delivered = runCatching { serve(client, pin, onText) }
                    .onFailure { Log.w(TAG, "Request failed", it) }
                    .getOrDefault(false)
                if (delivered) break
            }
            stop()
        }

        Result.success(Endpoint(host, socket.localPort, pin))
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    /** Handles one connection. Returns true once text has been accepted. */
    private fun serve(client: Socket, pin: String, onText: (String) -> Unit): Boolean {
        client.use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return false
            val parsed = parseRequestLine(requestLine) ?: return false

            val headers = buildList {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    add(line)
                }
            }

            val request = BridgeRequest(
                method = parsed.first,
                path = parsed.second,
                body = readBody(reader, contentLengthOf(headers))
            )

            val (response, accepted) = respondTo(request, pin, onText)
            socket.getOutputStream().apply {
                write(response.toByteArray(Charsets.UTF_8))
                flush()
            }
            return accepted
        }
    }

    private fun readBody(reader: BufferedReader, length: Int): String {
        if (length <= 0) return ""
        val buffer = CharArray(length)
        var read = 0
        while (read < length) {
            val count = reader.read(buffer, read, length - read)
            if (count < 0) break
            read += count
        }
        return String(buffer, 0, read)
    }

    private fun respondTo(
        request: BridgeRequest,
        pin: String,
        onText: (String) -> Unit
    ): Pair<String, Boolean> {
        if (request.method == "POST" && request.path.startsWith("/submit")) {
            val form = parseFormBody(request.body)
            val text = form["text"].orEmpty().trim()

            if (form["pin"]?.trim() != pin) {
                return httpResponse(
                    "403 Forbidden",
                    "text/html",
                    formPage("Type on the watch", "That PIN does not match the watch.")
                ) to false
            }
            if (text.isEmpty()) {
                return httpResponse(
                    "400 Bad Request",
                    "text/html",
                    formPage("Type on the watch", "Nothing to send.")
                ) to false
            }

            onText(text)
            return httpResponse("200 OK", "text/html", donePage()) to true
        }

        return httpResponse("200 OK", "text/html", formPage("Type on the watch")) to false
    }

    /**
     * The watch's IPv4 address on Wi-Fi.
     *
     * Null when there is no Wi-Fi: on the Bluetooth proxy the phone has no
     * route to the watch at all, so the bridge cannot work and should say so
     * rather than display an address nothing can reach.
     */
    private fun wifiAddress(): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null

        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        return manager.getLinkProperties(network)
            ?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }
}

/** Carries a [KeyboardBridge.Failure] through a [Result]. */
class BridgeException(val failure: KeyboardBridge.Failure) :
    IllegalStateException(failure.toString())
