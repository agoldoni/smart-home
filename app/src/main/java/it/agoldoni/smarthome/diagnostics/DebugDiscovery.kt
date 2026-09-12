package it.agoldoni.smarthome.diagnostics

import android.content.Context
import android.net.wifi.WifiManager
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Come si fa a trovare il telefono.
 *
 * L'indirizzo del telefono cambia da solo — DHCP, rete diversa, riavvio del
 * Google Wifi — quindi scriverselo da qualche parte non funziona. Qui l'app dice
 * dov'e, in due modi che si coprono a vicenda:
 *
 * - **annuncio**: ogni [BEACON_INTERVAL_MS] spara un JSON in broadcast sulla
 *   porta [port]. Chi vuole trovarla si mette in ascolto e aspetta qualche
 *   secondo, senza dover mandare niente.
 * - **risposta a richiesta**: chi manda il datagramma [PROBE] si sente
 *   rispondere subito, senza aspettare il prossimo annuncio.
 *
 * mDNS avrebbe fatto lo stesso lavoro in modo piu ortodosso, ma dipende da un
 * risolutore installato sul PC e dal fatto che la mesh Wi-Fi inoltri il
 * multicast. Un broadcast UDP con dentro del JSON lo si legge con quattro righe
 * di Python e non ha niente in mezzo che possa perderlo.
 */
internal class DebugDiscovery(
    context: Context,
    private val port: Int,
    private val describe: (kind: String) -> String,
) {

    private val appContext = context.applicationContext

    private var socket: DatagramSocket? = null
    private var listener: Thread? = null
    private var beacon: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        val udp = DatagramSocket(null)
        udp.reuseAddress = true
        udp.broadcast = true
        udp.bind(InetSocketAddress(port))
        socket = udp
        // Senza questo lo stack Wi-Fi puo scartare i pacchetti non indirizzati
        // al telefono, cioe proprio le richieste in broadcast che ci interessano.
        multicastLock = runCatching {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.createMulticastLock("smart-home-debug").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        listener = Thread({ listenLoop(udp) }, "debug-discovery").apply {
            isDaemon = true
            start()
        }
        beacon = Thread({ beaconLoop(udp) }, "debug-beacon").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        beacon?.interrupt()
        runCatching { socket?.close() }
        runCatching { multicastLock?.release() }
        socket = null
        listener = null
        beacon = null
        multicastLock = null
    }

    private fun listenLoop(udp: DatagramSocket) {
        val buffer = ByteArray(BUFFER)
        while (!udp.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                udp.receive(packet)
            } catch (e: IOException) {
                return
            }
            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
            // Solo la parola d'ordine esatta: i nostri stessi annunci tornano su
            // questa porta, e senza il confronto l'app risponderebbe a se stessa.
            if (text != PROBE || !isPrivate(packet.address)) continue
            val reply = describe("reply").toByteArray(Charsets.UTF_8)
            runCatching { udp.send(DatagramPacket(reply, reply.size, packet.address, packet.port)) }
            // Un contatore e non un evento: una scoperta puo arrivare piu volte
            // di fila, una per indirizzo di broadcast di chi cerca, e in tre
            // tentativi laverebbe via dal registro proprio gli istanti
            // dell'avvio, che sono quelli per cui il registro esiste.
            DiagnosticsLog.count(COUNTER_DISCOVERY)
        }
    }

    private fun beaconLoop(udp: DatagramSocket) {
        while (!udp.isClosed && !Thread.currentThread().isInterrupted) {
            val payload = describe("beacon").toByteArray(Charsets.UTF_8)
            broadcastAddresses().forEach { target ->
                runCatching { udp.send(DatagramPacket(payload, payload.size, target, port)) }
            }
            try {
                Thread.sleep(BEACON_INTERVAL_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private companion object {
        const val BUFFER = 2048
        const val BEACON_INTERVAL_MS = 5_000L
        const val COUNTER_DISCOVERY = "scoperta.risposte"
    }
}

/** La parola d'ordine che fa rispondere l'app. Sta anche in tools/debug-api.py. */
internal const val PROBE = "SMART-HOME-DEBUG?"

/**
 * Gli indirizzi di broadcast delle reti a cui il telefono e attaccato, piu il
 * broadcast globale come rete di sicurezza: quello per interfaccia arriva anche
 * dove 255.255.255.255 viene scartato, che sulle mesh capita.
 */
internal fun broadcastAddresses(): List<InetAddress> {
    val targets = mutableListOf<InetAddress>()
    runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .forEach { nic ->
                nic.interfaceAddresses.forEach { entry ->
                    entry.broadcast?.let { targets += it }
                }
            }
    }
    runCatching { targets += InetAddress.getByName("255.255.255.255") }
    return targets.distinct()
}

/** Gli indirizzi IPv4 privati del telefono, il primo dei quali finisce nell'URL annunciato. */
internal fun localAddresses(): List<String> {
    val found = mutableListOf<String>()
    runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .forEach { nic ->
                nic.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { isPrivate(it) && !it.isLoopbackAddress }
                    .forEach { found += it.hostAddress.orEmpty() }
            }
    }
    return found.filter { it.isNotBlank() }.distinct()
}
