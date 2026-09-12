package it.agoldoni.smarthome.diagnostics

import android.os.Build
import it.agoldoni.smarthome.BuildConfig
import it.agoldoni.smarthome.di.AppContainer
import it.agoldoni.smarthome.domain.model.ConnectionState
import it.agoldoni.smarthome.domain.model.Device
import it.agoldoni.smarthome.domain.model.DeviceState
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Il nome con cui l'app si presenta, in tutte le risposte e negli annunci. */
internal const val SERVICE = "smart-home-debug"

/**
 * Trasforma lo stato interno dell'app nel JSON che l'API restituisce.
 *
 * Ogni tempo compare due volte, come millisecondi e come ora leggibile: il primo
 * serve a filtrare con `?since=`, il secondo a capire cosa e successo senza
 * convertire niente a mano.
 *
 * La password del broker non passa di qui. Le coordinate del broker si, perche
 * meta dei guai stanno li, ma della password si dice solo se c'e.
 */
internal class DebugReport(
    private val container: AppContainer,
    private val port: Int,
) {

    private val clock = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ITALY)
    }

    fun handle(request: DebugHttpServer.Request): DebugHttpServer.Response = when (request.path) {
        "/" -> ok(index())
        "/health" -> ok(health())
        "/info" -> ok(info())
        "/broker" -> ok(broker())
        "/devices" -> ok(devices())
        "/mqtt" -> ok(mqtt(request))
        "/log" -> ok(log(request))
        "/state" -> ok(state(request))
        else -> DebugHttpServer.Response(
            status = 404,
            reason = "Not Found",
            body = JSONObject().apply {
                put("error", "Endpoint sconosciuto: ${request.path}")
                put("endpoints", endpoints())
            }.toString(2),
        )
    }

    /** Il biglietto da visita che viaggia negli annunci UDP: una riga sola. */
    fun descriptor(kind: String): String {
        val addresses = localAddresses()
        return JSONObject().apply {
            put("service", SERVICE)
            put("kind", kind)
            put("package", BuildConfig.APPLICATION_ID)
            put("version", BuildConfig.VERSION_NAME)
            put("model", Build.MODEL)
            put("port", port)
            put("addresses", JSONArray(addresses))
            putOrNull("url", addresses.firstOrNull()?.let { "http://$it:$port" })
            put("at", System.currentTimeMillis())
        }.toString()
    }

    private fun ok(body: JSONObject) = DebugHttpServer.Response(200, "OK", body.toString(2))

    private fun index() = JSONObject().apply {
        put("app", identity())
        put("endpoints", endpoints())
        put("filters", JSONObject().apply {
            put("limit", "quante voci restituire, 1-500")
            put("since", "solo le voci successive a questo istante, in millisecondi")
        })
        put("note", "Sola lettura: solo GET, e solo da indirizzi privati. Non comanda nessun dispositivo.")
    }

    private fun endpoints() = JSONObject().apply {
        put("/health", "vivo o no, in due righe")
        put("/state", "tutto insieme: e quello da leggere per primo")
        put("/info", "build, telefono, interfacce di rete")
        put("/broker", "coordinate del broker, stato del collegamento, client Paho")
        put("/devices", "dispositivi registrati con la loro configurazione e il loro stato")
        put("/mqtt", "sottoscrizioni attive, contatori, traffico recente")
        put("/log", "eventi interni recenti")
    }

    private fun identity() = JSONObject().apply {
        put("service", SERVICE)
        put("package", BuildConfig.APPLICATION_ID)
        put("version", BuildConfig.VERSION_NAME)
        put("versionCode", BuildConfig.VERSION_CODE)
        put("buildType", BuildConfig.BUILD_TYPE)
    }

    private fun health() = JSONObject().apply {
        val snapshot = snapshot()
        put("ok", true)
        stamp("at", System.currentTimeMillis())
        put("uptimeMs", uptime())
        put("connection", snapshot?.connection?.let { token(it) } ?: "unknown")
        put("devices", container.devices.value.size)
        put("subscriptions", snapshot?.subscriptions?.size ?: 0)
        put("received", DiagnosticsLog.counters()[DiagnosticsLog.COUNTER_RECEIVED] ?: 0L)
    }

    private fun info() = JSONObject().apply {
        put("app", identity())
        put("android", JSONObject().apply {
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("device", Build.DEVICE)
            put("release", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
        })
        put("process", JSONObject().apply {
            stamp("startedAt", DiagnosticsLog.startedAt)
            put("uptimeMs", uptime())
        })
        put("network", network())
    }

    private fun broker() = JSONObject().apply {
        val snapshot = snapshot()
        if (snapshot == null) {
            put("error", "Il driver in uso non espone diagnostiche")
            return@apply
        }
        put("protocol", snapshot.protocol)
        put("connection", connection(snapshot.connection))
        put("broker", JSONObject().apply {
            put("configured", snapshot.brokerConfigured)
            put("host", snapshot.host)
            put("port", snapshot.port)
            put("tls", snapshot.useTls)
            put("uri", snapshot.serverUri)
            put("username", snapshot.username)
            // La password non esce da qui: di lei si dice solo se esiste.
            put("passwordSet", snapshot.passwordSet)
            put("clientIdSetting", snapshot.clientIdSetting)
            put("clientIdInUse", snapshot.clientIdInUse)
        })
        put("client", JSONObject().apply {
            put("alive", snapshot.clientAlive)
            put("connected", snapshot.clientConnected)
        })
        put("counters", counters())
    }

    private fun devices() = JSONObject().apply {
        val devices = container.devices.value
        val states = container.driver.states.value
        val subscriptions = snapshot()?.subscriptions.orEmpty()
        put("count", devices.size)
        put("devices", JSONArray().apply {
            devices.forEach { put(device(it, states[it.id], subscriptions)) }
        })
    }

    private fun mqtt(request: DebugHttpServer.Request) = JSONObject().apply {
        val snapshot = snapshot()
        put("connection", snapshot?.let { connection(it.connection) } ?: JSONObject.NULL)
        put("trackedDeviceIds", JSONArray(snapshot?.trackedDeviceIds.orEmpty()))
        put("subscriptions", JSONObject().apply {
            snapshot?.subscriptions?.forEach { (topic, qos) -> put(topic, qos) }
        })
        put("counters", counters())
        put("topics", topics())
        put("messages", messages(request.int("limit", DEFAULT_LIMIT), request.since()))
    }

    private fun log(request: DebugHttpServer.Request) = JSONObject().apply {
        put("counters", counters())
        put("events", events(request.int("limit", DEFAULT_LIMIT), request.since()))
    }

    private fun state(request: DebugHttpServer.Request) = JSONObject().apply {
        val limit = request.int("limit", COMBINED_LIMIT)
        val since = request.since()
        val snapshot = snapshot()
        val devices = container.devices.value
        val states = container.driver.states.value

        put("app", identity())
        stamp("at", System.currentTimeMillis())
        put("uptimeMs", uptime())
        put("warnings", warnings(snapshot, devices, states))
        put("broker", broker())
        put("devices", devices())
        put("mqtt", JSONObject().apply {
            put("subscriptions", JSONObject().apply {
                snapshot?.subscriptions?.forEach { (topic, qos) -> put(topic, qos) }
            })
            put("topics", topics())
            put("messages", messages(limit, since))
        })
        put("events", events(limit, since))
        put("network", network())
    }

    /**
     * Le cose che, guardando lo stato, non tornano.
     *
     * Sono tutte deduzioni da quello che c'e negli altri campi: il valore sta nel
     * fatto che qualcuno le faccia sempre, invece di accorgersene confrontando a
     * occhio un elenco di topic con un altro.
     */
    private fun warnings(
        snapshot: DriverSnapshot?,
        devices: List<Device>,
        states: Map<Long, DeviceState>,
    ) = JSONArray().apply {
        if (snapshot == null) {
            put("Il driver in uso non espone diagnostiche: mqtt e broker restano vuoti")
            return@apply
        }
        if (!snapshot.brokerConfigured) put("Nessun broker configurato nelle impostazioni")
        val connected = snapshot.connection is ConnectionState.Connected
        if (!connected) put("Collegamento al broker: ${token(snapshot.connection)}")
        if (connected != snapshot.clientConnected) {
            put(
                "Il client Paho dice connesso=${snapshot.clientConnected} ma lo stato mostrato " +
                    "e ${token(snapshot.connection)}: uno dei due mente",
            )
        }
        if (devices.isEmpty()) put("Nessun dispositivo registrato nell'app")

        devices.forEach { device ->
            if (device.stateTopic.isBlank()) {
                put("${device.name}: nessun topic di stato, non potra mai sapere come sta")
            }
            if (connected) {
                device.subscriptions.filterNot { it in snapshot.subscriptions }.forEach { topic ->
                    put("${device.name}: il topic $topic non risulta sottoscritto sul broker")
                }
            }
            if (device.controllable && device.commandTopic.isBlank()) {
                put("${device.name}: nessun topic di comando, l'interruttore non puo pubblicare")
            }
            val state = states[device.id]
            if (state == null || !state.known) {
                put("${device.name}: non e mai arrivato niente sul topic ${device.stateTopic}")
            }
            if (state?.reachable == false) {
                put("${device.name}: dichiarato non raggiungibile sul topic di disponibilita")
            }
        }
    }

    private fun device(device: Device, state: DeviceState?, subscriptions: Map<String, Int>) =
        JSONObject().apply {
            put("id", device.id)
            put("name", device.name)
            put("room", device.room)
            put("kind", device.kind.name)
            put("qos", device.qos)
            put("retained", device.retained)
            put("topics", JSONObject().apply {
                put("state", device.stateTopic)
                put("command", device.commandTopic)
                putOrNull("availability", device.availabilityTopic)
                putOrNull("levelState", device.levelStateTopic)
                putOrNull("levelCommand", device.levelCommandTopic)
            })
            put("payloads", JSONObject().apply {
                put("on", device.payloadOn)
                put("off", device.payloadOff)
                put("available", device.payloadAvailable)
                put("unavailable", device.payloadUnavailable)
                putOrNull("stateJsonKey", device.stateJsonKey)
                putOrNull("levelJsonKey", device.levelJsonKey)
                put("levelMax", device.levelMax)
            })
            // Quello a cui il dispositivo vorrebbe essere iscritto, e se lo e davvero.
            put("subscribed", JSONObject().apply {
                device.subscriptions.forEach { put(it, it in subscriptions) }
            })
            put("state", deviceState(state))
        }

    private fun deviceState(state: DeviceState?) = JSONObject().apply {
        if (state == null) {
            put("known", false)
            return@apply
        }
        put("known", state.known)
        putOrNull("power", state.power)
        putOrNull("level", state.level)
        putOrNull("raw", state.raw)
        putOrNull("reachable", state.reachable)
        put("pending", state.pending)
        stamp("updatedAt", state.updatedAt)
    }

    private fun connection(state: ConnectionState) = JSONObject().apply {
        put("state", token(state))
        putOrNull("reason", (state as? ConnectionState.Failed)?.reason)
    }

    private fun token(state: ConnectionState): String = when (state) {
        ConnectionState.NotConfigured -> "not_configured"
        ConnectionState.Connecting -> "connecting"
        ConnectionState.Connected -> "connected"
        ConnectionState.Disconnected -> "disconnected"
        is ConnectionState.Failed -> "failed"
    }

    private fun counters() = JSONObject().apply {
        DiagnosticsLog.counters().forEach { (name, value) -> put(name, value) }
    }

    private fun topics() = JSONObject().apply {
        DiagnosticsLog.topics().forEach { (topic, stat) ->
            put(topic, JSONObject().apply {
                put("count", stat.count)
                stamp("lastAt", stat.lastAt)
                put("lastPayload", stat.lastPayload)
            })
        }
    }

    private fun messages(limit: Int, since: Long) = JSONArray().apply {
        DiagnosticsLog.messages(limit, since).forEach { message ->
            put(JSONObject().apply {
                stamp("at", message.at)
                put("direction", if (message.incoming) "in" else "out")
                put("topic", message.topic)
                put("payload", message.payload)
                if (message.incoming) put("matchedDevices", message.matched)
                putOrNull("note", message.note)
            })
        }
    }

    private fun events(limit: Int, since: Long) = JSONArray().apply {
        DiagnosticsLog.events(limit, since).forEach { event ->
            put(JSONObject().apply {
                stamp("at", event.at)
                put("category", event.category)
                put("message", event.message)
            })
        }
    }

    private fun network() = JSONObject().apply {
        val addresses = localAddresses()
        put("interfaces", JSONArray().apply {
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .filter { it.isUp && !it.isLoopback }
                    .forEach { nic ->
                        put(JSONObject().apply {
                            put("name", nic.name)
                            put(
                                "addresses",
                                JSONArray(nic.inetAddresses.toList().map { it.hostAddress.orEmpty() }),
                            )
                        })
                    }
            }
        })
        put("addresses", JSONArray(addresses))
        putOrNull("endpoint", addresses.firstOrNull()?.let { "http://$it:$port" })
        put("discoveryPort", port)
    }

    private fun snapshot(): DriverSnapshot? = (container.driver as? DriverDiagnostics)?.diagnostics()

    private fun uptime(): Long =
        if (DiagnosticsLog.startedAt == 0L) 0L else System.currentTimeMillis() - DiagnosticsLog.startedAt

    /** Mette `chiave` in millisecondi e `chiaveTime` in ora leggibile. */
    private fun JSONObject.stamp(key: String, millis: Long?) {
        put(key, millis ?: JSONObject.NULL)
        put(key + "Time", millis?.let { clock.get()!!.format(Date(it)) } ?: JSONObject.NULL)
    }

    private fun JSONObject.putOrNull(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private companion object {
        const val DEFAULT_LIMIT = 60
        const val COMBINED_LIMIT = 30
    }
}
