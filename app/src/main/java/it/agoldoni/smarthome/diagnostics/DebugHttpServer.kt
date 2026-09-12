package it.agoldoni.smarthome.diagnostics

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Il server dell'API di debug: HTTP/1.1 scritto a mano su un ServerSocket.
 *
 * Una libreria per questo sarebbe una dipendenza in piu nell'APK per servire
 * otto GET che restituiscono JSON, per giunta solo nella build debug. Qui basta
 * leggere la prima riga della richiesta e buttare via le intestazioni.
 *
 * Due paletti, entrambi voluti:
 * - **solo GET**. L'API racconta, non comanda. Nessuna richiesta che arrivi qui
 *   puo accendere una presa: per quello c'e il broker, che ha le sue regole.
 * - **solo indirizzi privati**. Il socket ascolta su 0.0.0.0 perche deve essere
 *   raggiungibile dal PC, ma chi bussa da un indirizzo pubblico si prende un 403
 *   e nemmeno viene letto.
 */
internal class DebugHttpServer(
    private val port: Int,
    private val respond: (Request) -> Response,
) {

    /** Cosa rispondere e con che codice. Serve perche un endpoint inesistente e un 404, non un 200 con dentro un errore. */
    data class Response(val status: Int, val reason: String, val body: String)

    data class Request(val path: String, val query: Map<String, String>) {
        fun int(name: String, fallback: Int): Int =
            query[name]?.toIntOrNull()?.coerceIn(1, MAX_LIMIT) ?: fallback

        fun since(): Long = query["since"]?.toLongOrNull() ?: 0L
    }

    private var server: ServerSocket? = null
    private var acceptor: Thread? = null
    private var workers: ExecutorService? = null

    /** Solleva se la porta non si apre: chi chiama lo riporta nell'interfaccia. */
    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port), BACKLOG)
        val pool = Executors.newFixedThreadPool(WORKERS)
        server = socket
        workers = pool
        acceptor = Thread({ acceptLoop(socket, pool) }, "debug-http").apply {
            isDaemon = true
            start()
        }
    }

    /** La chiusura del socket e cio che sblocca la accept: interrompere il thread non basta. */
    fun stop() {
        runCatching { server?.close() }
        workers?.shutdownNow()
        server = null
        acceptor = null
        workers = null
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                return
            }
            try {
                pool.execute { handle(client) }
            } catch (e: RejectedExecutionException) {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = READ_TIMEOUT_MS
                val output = BufferedOutputStream(socket.getOutputStream())
                val peer = socket.inetAddress

                if (!isPrivate(peer)) {
                    DiagnosticsLog.event(
                        "debug",
                        "richiesta respinta: ${peer.hostAddress} non e un indirizzo privato",
                    )
                    write(output, 403, "Forbidden", failure("Solo dalla rete locale"))
                    return
                }

                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine()
                if (requestLine.isNullOrBlank()) return
                // Le intestazioni non servono a niente, ma vanno consumate prima
                // di rispondere, altrimenti il client legge la risposta a meta.
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                }

                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    write(output, 400, "Bad Request", failure("Richiesta illeggibile"))
                    return
                }
                if (parts[0] != "GET") {
                    write(output, 405, "Method Not Allowed", failure("L'API di debug legge soltanto: usa GET"))
                    return
                }

                val response = try {
                    respond(parse(parts[1]))
                } catch (e: Throwable) {
                    write(output, 500, "Internal Server Error", failure(describe(e)))
                    return
                }
                write(output, response.status, response.reason, response.body)
            } catch (e: IOException) {
                // Client sparito a meta richiesta: capita, e non e una notizia.
            }
        }
    }

    private fun parse(target: String): Request {
        val mark = target.indexOf('?')
        val rawPath = if (mark < 0) target else target.substring(0, mark)
        val query = if (mark < 0) {
            emptyMap()
        } else {
            target.substring(mark + 1)
                .split('&')
                .filter { it.isNotBlank() }
                .associate { pair ->
                    val eq = pair.indexOf('=')
                    if (eq < 0) {
                        decode(pair) to ""
                    } else {
                        decode(pair.substring(0, eq)) to decode(pair.substring(eq + 1))
                    }
                }
        }
        return Request(rawPath.trimEnd('/').ifEmpty { "/" }, query)
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun write(out: OutputStream, status: Int, reason: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun failure(message: String): String =
        """{"error":${org.json.JSONObject.quote(message)}}"""

    private fun describe(error: Throwable): String =
        error.message ?: error::class.java.simpleName

    private companion object {
        const val BACKLOG = 8
        const val WORKERS = 2
        const val READ_TIMEOUT_MS = 5_000
        const val MAX_LIMIT = 500
    }
}

/**
 * Rete locale, nel senso largo: casa, il link-local di chi non ha preso un DHCP,
 * e il tunnel WireGuard, che e casa allungata e per entrarci bisogna gia essere
 * dentro. Fuori resta tutto il resto di internet.
 */
internal fun isPrivate(address: InetAddress): Boolean = when {
    address.isLoopbackAddress -> true
    address.isLinkLocalAddress -> true
    address.isSiteLocalAddress -> true
    // Le ULA IPv6 (fc00::/7) non rientrano in isSiteLocalAddress, che in Java
    // guarda ancora le fec0::/10 ritirate da vent'anni.
    address is Inet6Address -> (address.address[0].toInt() and 0xFE) == 0xFC
    else -> false
}
