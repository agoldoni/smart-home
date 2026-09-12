package it.agoldoni.smarthome.driver.mqtt

import it.agoldoni.smarthome.data.settings.BrokerSettings
import it.agoldoni.smarthome.diagnostics.DiagnosticsLog
import it.agoldoni.smarthome.diagnostics.DriverDiagnostics
import it.agoldoni.smarthome.diagnostics.DriverSnapshot
import it.agoldoni.smarthome.domain.driver.DeviceCommand
import it.agoldoni.smarthome.domain.driver.DeviceDriver
import it.agoldoni.smarthome.domain.model.ConnectionState
import it.agoldoni.smarthome.domain.model.Device
import it.agoldoni.smarthome.domain.model.DeviceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Driver MQTT: un solo collegamento al broker per tutta l'app, condiviso da
 * tutti i dispositivi registrati.
 *
 * Il collegamento vive quanto il processo, non quanto una schermata: aprirlo e
 * chiuderlo a ogni navigazione perderebbe gli stati ritenuti e farebbe lampeggiare
 * l'elenco a ogni ritorno indietro.
 */
class MqttDeviceDriver(
    private val scope: CoroutineScope,
    settings: Flow<BrokerSettings>,
) : DeviceDriver, DriverDiagnostics {

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.NotConfigured)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _states = MutableStateFlow<Map<Long, DeviceState>>(emptyMap())
    override val states: StateFlow<Map<Long, DeviceState>> = _states.asStateFlow()

    private val _commandsSent = MutableStateFlow(0)
    override val commandsSent: StateFlow<Int> = _commandsSent.asStateFlow()

    /** Serializza apertura, chiusura e sottoscrizioni: Paho non e rientrante su questi. */
    private val lock = Mutex()

    @Volatile
    private var client: MqttAsyncClient? = null

    @Volatile
    private var devices: List<Device> = emptyList()

    @Volatile
    private var settingsNow = BrokerSettings()

    /**
     * Topic gia sottoscritti con il QoS usato, per non rifare il lavoro a ogni
     * modifica. Volatile perche le diagnostiche la leggono da un altro thread
     * senza prendere [lock]: e una mappa immutabile riassegnata di netto, quindi
     * al massimo si legge quella di un istante fa.
     */
    @Volatile
    private var subscribed: Map<String, Int> = emptyMap()

    /** Usato quando l'utente non ne impone uno: stabile per l'intera vita del processo. */
    private val fallbackClientId: String = MqttAsyncClient.generateClientId()

    /**
     * Un callback per client, invece di uno solo condiviso da tutti.
     *
     * Ogni evento controlla di arrivare dal client che e ancora il nostro: uno
     * che abbiamo chiuso, se per qualche motivo sopravvive, non deve poter
     * scrivere lo stato del collegamento ne rifare le sottoscrizioni addosso a
     * quello vivo.
     */
    private fun callbackFor(owner: MqttAsyncClient) = object : MqttCallbackExtended {

        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            if (client !== owner) return
            _connection.value = ConnectionState.Connected
            DiagnosticsLog.count(DiagnosticsLog.COUNTER_CONNECT_OK)
            DiagnosticsLog.event(
                "collegamento",
                if (reconnect) "riconnesso a $serverURI" else "connesso a $serverURI",
            )
            // Dopo una riconnessione il broker non ricorda le sottoscrizioni
            // (sessione pulita): vanno rifatte tutte.
            scope.launch {
                lock.withLock {
                    if (client !== owner) return@withLock
                    subscribed = emptyMap()
                    syncSubscriptions()
                }
            }
        }

        override fun connectionLost(cause: Throwable?) {
            if (client !== owner) return
            val reason = MqttFailures.describe(cause)
            DiagnosticsLog.count(DiagnosticsLog.COUNTER_CONNECT_LOST)
            DiagnosticsLog.event(
                "collegamento",
                "caduto: $reason (${MqttFailures.detail(cause)})",
            )
            _connection.value = ConnectionState.Failed(reason)
        }

        override fun messageArrived(topic: String, message: MqttMessage) {
            if (client !== owner) return
            onMessage(topic, String(message.payload, Charsets.UTF_8))
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
    }

    init {
        scope.launch {
            settings.distinctUntilChanged().collect { applySettings(it) }
        }
        // Le riconnessioni sono di questo ciclo e di nessun altro: quella
        // automatica di Paho e spenta apposta, vedi openClient. Copre sia il
        // primo tentativo andato male — il telefono che rientra sotto la rete
        // di casa — sia il collegamento che cade dopo essere riuscito.
        //
        // L'attesa raddoppia a ogni tentativo fallito e riparte da capo appena
        // il collegamento regge: un broker spento non deve tenere il telefono a
        // bussare ogni tre secondi per ore.
        scope.launch {
            var wait = RETRY_MIN_MS
            while (isActive) {
                delay(wait)
                val down = _connection.value.let {
                    it is ConnectionState.Failed || it is ConnectionState.Disconnected
                }
                if (down && settingsNow.configured) {
                    applySettings(settingsNow)
                    wait = if (_connection.value is ConnectionState.Connected) {
                        RETRY_MIN_MS
                    } else {
                        (wait * 2).coerceAtMost(RETRY_MAX_MS)
                    }
                } else {
                    wait = RETRY_MIN_MS
                }
            }
        }
    }

    override fun track(devices: List<Device>) {
        scope.launch {
            lock.withLock {
                val precedenti = this@MqttDeviceDriver.devices.associateBy { it.id }
                this@MqttDeviceDriver.devices = devices
                DiagnosticsLog.event(
                    "dispositivi",
                    "seguiti ${devices.size}: ${devices.joinToString { it.name }}",
                )
                val alive = devices.mapTo(mutableSetOf()) { it.id }
                _states.update { current -> current.filterKeys { it in alive } }

                // Un dispositivo modificato si risottoscrive ai propri topic,
                // anche a quelli che aveva gia. I messaggi ritenuti il broker
                // li consegna al momento della sottoscrizione: senza questo,
                // correggere una chiave JSON lascerebbe la scheda vuota fino al
                // prossimo messaggio, che su un topic lento sono minuti — e
                // sembra che la correzione non abbia funzionato.
                val cambiati = devices.filter { precedenti[it.id]?.equals(it) == false }
                if (cambiati.isNotEmpty()) {
                    val topic = cambiati.flatMapTo(mutableSetOf()) { it.subscriptions }
                    subscribed = subscribed - topic
                    DiagnosticsLog.event(
                        "sottoscrizioni",
                        "rifatte per ${cambiati.joinToString { it.name }}: configurazione cambiata",
                    )
                }
                syncSubscriptions()
            }
        }
    }

    override suspend fun send(device: Device, command: DeviceCommand) {
        val active = client ?: error("Nessun broker configurato")
        when (command) {
            is DeviceCommand.Power -> {
                require(device.commandTopic.isNotBlank()) { "${device.name}: manca il topic di comando" }
                publish(active, device.commandTopic, if (command.on) device.payloadOn else device.payloadOff, device)
                updateState(device.id) { it.copy(power = command.on, pending = true) }
            }

            is DeviceCommand.Level -> {
                val topic = device.levelCommandTopic?.takeIf { it.isNotBlank() }
                    ?: error("${device.name}: manca il topic del livello")
                publish(active, topic, device.toDeviceLevel(command.percent).toString(), device)
                updateState(device.id) { it.copy(level = command.percent, pending = true) }
            }
        }
    }

    override fun reconnect() {
        DiagnosticsLog.event("collegamento", "riconnessione chiesta a mano")
        scope.launch { applySettings(settingsNow) }
    }

    /**
     * Le interiora cosi come stanno adesso, per l'API di debug.
     *
     * Legge campi volatili senza prendere [lock]: bloccare il driver per farsi
     * raccontare come sta sarebbe un buon modo per cambiare proprio la cosa che
     * si voleva guardare. Il prezzo e che due campi possono venire da istanti
     * diversi, il che per una fotografia diagnostica va benissimo.
     */
    override fun diagnostics(): DriverSnapshot {
        val settings = settingsNow
        val current = client
        return DriverSnapshot(
            protocol = "mqtt",
            connection = _connection.value,
            brokerConfigured = settings.configured,
            serverUri = settings.serverUri,
            host = settings.host,
            port = settings.port,
            useTls = settings.useTls,
            username = settings.username,
            passwordSet = settings.password.isNotBlank(),
            clientIdSetting = settings.clientId,
            clientIdInUse = current?.clientId ?: settings.clientId.ifBlank { fallbackClientId },
            clientAlive = current != null,
            // Su un client gia chiuso Paho puo sollevare invece di rispondere.
            clientConnected = runCatching { current?.isConnected == true }.getOrDefault(false),
            trackedDeviceIds = devices.map { it.id },
            subscriptions = subscribed,
        )
    }

    private suspend fun applySettings(next: BrokerSettings) = lock.withLock {
        settingsNow = next
        DiagnosticsLog.event(
            "impostazioni",
            if (next.configured) {
                "broker ${next.serverUri}, utente '${next.username}', client id '${next.clientId}'"
            } else {
                "nessun broker configurato"
            },
        )
        closeClient()
        if (!next.configured) {
            _connection.value = ConnectionState.NotConfigured
            return@withLock
        }
        openClient(next)
    }

    private suspend fun openClient(settings: BrokerSettings) = withContext(Dispatchers.IO) {
        _connection.value = ConnectionState.Connecting
        subscribed = emptyMap()
        DiagnosticsLog.count(DiagnosticsLog.COUNTER_CONNECT_ATTEMPT)
        DiagnosticsLog.event("collegamento", "apertura verso ${settings.serverUri}")

        val created = try {
            MqttAsyncClient(
                settings.serverUri,
                settings.clientId.ifBlank { fallbackClientId },
                MemoryPersistence(),
            )
        } catch (e: Throwable) {
            val reason = MqttFailures.describe(e)
            DiagnosticsLog.count(DiagnosticsLog.COUNTER_CONNECT_FAILED)
            DiagnosticsLog.event(
                "collegamento",
                "client non creato: $reason (${MqttFailures.detail(e)})",
            )
            _connection.value = ConnectionState.Failed(reason)
            return@withContext
        }

        created.setCallback(callbackFor(created))
        // Assegnato prima della connessione: connectComplete puo scattare durante
        // la connect e ha bisogno del client per sottoscrivere.
        client = created

        val options = MqttConnectOptions().apply {
            isCleanSession = true
            // Spenta di proposito, e non e un dettaglio. La riconnessione
            // automatica di Paho vive su un timer interno che l'API pubblica
            // non sa fermare: `stopReconnectCycle` e privato e lo chiama solo
            // `reconnect()`, mentre ne `disconnect()` ne `close()` lo toccano.
            // Un client che credevamo chiuso tornava quindi a collegarsi da
            // solo, con lo stesso client id, rubando la sessione a quello nuovo
            // — che gliela riprendeva mezzo secondo dopo, all'infinito. Con un
            // client id scelto a mano i due contendenti hanno per forza lo
            // stesso nome e la giostra non finisce piu.
            isAutomaticReconnect = false
            connectionTimeout = CONNECT_TIMEOUT_SECONDS
            keepAliveInterval = KEEP_ALIVE_SECONDS
            if (settings.username.isNotBlank()) userName = settings.username
            if (settings.password.isNotBlank()) password = settings.password.toCharArray()
        }

        try {
            created.connect(options).waitForCompletion(CONNECT_TIMEOUT_SECONDS * 1000L)
        } catch (e: Throwable) {
            val reason = MqttFailures.describe(e)
            DiagnosticsLog.count(DiagnosticsLog.COUNTER_CONNECT_FAILED)
            DiagnosticsLog.event(
                "collegamento",
                "connessione fallita: $reason (${MqttFailures.detail(e)})",
            )
            _connection.value = ConnectionState.Failed(reason)
        }
    }

    /**
     * Smontaggio in ordine, dal gentile al brutale.
     *
     * `client = null` viene per primo: da quel momento i callback del vecchio
     * client si riconoscono come non nostri e tacciono. Poi la disconnessione
     * pulita, l'unica che chiuda la sessione anche dal lato del broker; se non
     * passa — capita quando la connect e ancora in corso — si forza, e in ogni
     * caso si chiude con `close(true)`, che non pretende uno stato particolare.
     * Ogni passo e protetto da solo: quello che conta e arrivare in fondo, non
     * che ognuno riesca.
     */
    private suspend fun closeClient() = withContext(Dispatchers.IO) {
        val current = client ?: return@withContext
        DiagnosticsLog.event("collegamento", "chiusura del client")
        client = null
        subscribed = emptyMap()
        runCatching { current.setCallback(null) }
        runCatching { current.disconnect(0L).waitForCompletion(DISCONNECT_TIMEOUT_MS) }
        runCatching { current.disconnectForcibly(0L, DISCONNECT_TIMEOUT_MS, false) }
        runCatching { current.close(true) }
        _connection.value = ConnectionState.Disconnected
    }

    /** Da chiamare con [lock] acquisito. */
    private suspend fun syncSubscriptions() = withContext(Dispatchers.IO) {
        val active = client ?: return@withContext
        if (!active.isConnected) return@withContext

        val wanted = mutableMapOf<String, Int>()
        devices.forEach { device ->
            device.subscriptions.forEach { topic ->
                wanted[topic] = maxOf(wanted[topic] ?: 0, device.qos)
            }
        }

        val obsolete = subscribed.keys - wanted.keys
        val missing = wanted.filter { (topic, qos) -> subscribed[topic] != qos }

        if (obsolete.isNotEmpty()) {
            runCatching { active.unsubscribe(obsolete.toTypedArray()).waitForCompletion(ACTION_TIMEOUT_MS) }
            DiagnosticsLog.count(DiagnosticsLog.COUNTER_UNSUBSCRIBE, obsolete.size.toLong())
            DiagnosticsLog.event("sottoscrizioni", "abbandonate ${obsolete.joinToString()}")
        }
        if (missing.isNotEmpty()) {
            val outcome = runCatching {
                active.subscribe(missing.keys.toTypedArray(), missing.values.toIntArray())
                    .waitForCompletion(ACTION_TIMEOUT_MS)
            }
            if (outcome.isFailure) {
                val error = outcome.exceptionOrNull()
                val reason = MqttFailures.describe(error)
                DiagnosticsLog.event(
                    "sottoscrizioni",
                    "fallite ${missing.keys.joinToString()}: $reason (${MqttFailures.detail(error)})",
                )
                _connection.value = ConnectionState.Failed(reason)
                return@withContext
            }
            DiagnosticsLog.count(DiagnosticsLog.COUNTER_SUBSCRIBE, missing.size.toLong())
            DiagnosticsLog.event(
                "sottoscrizioni",
                "aggiunte ${missing.entries.joinToString { "${it.key} (qos ${it.value})" }}",
            )
        }
        subscribed = wanted
    }

    private suspend fun publish(
        client: MqttAsyncClient,
        topic: String,
        payload: String,
        device: Device,
    ) = withContext(Dispatchers.IO) {
        if (!client.isConnected) {
            DiagnosticsLog.count(DiagnosticsLog.COUNTER_PUBLISH_FAILED)
            DiagnosticsLog.outgoing(topic, payload, note = "non inviato: broker non connesso")
            error("Broker non connesso")
        }
        val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
            this.qos = device.qos
            this.isRetained = device.retained
        }
        try {
            client.publish(topic, message).waitForCompletion(ACTION_TIMEOUT_MS)
        } catch (e: Throwable) {
            DiagnosticsLog.count(DiagnosticsLog.COUNTER_PUBLISH_FAILED)
            DiagnosticsLog.outgoing(topic, payload, note = "fallito: ${MqttFailures.detail(e)}")
            // Rilanciato con il motivo corto: da qui il testo arriva tale e
            // quale al messaggio a comparsa di chi ha appena toccato
            // l'interruttore, e li vale la stessa regola del banner. La causa
            // resta appesa, per chi la va a cercare.
            throw IOException(MqttFailures.describe(e), e)
        }
        // Qui e non in send(): questo e l'unico passaggio da cui esce un
        // messaggio, e ci si arriva solo quando il broker ha confermato.
        _commandsSent.update { it + 1 }
        DiagnosticsLog.count(DiagnosticsLog.COUNTER_PUBLISHED)
        DiagnosticsLog.outgoing(
            topic,
            payload,
            note = "${device.name}, qos ${device.qos}" + if (device.retained) ", ritenuto" else "",
        )
    }

    private fun onMessage(topic: String, payload: String) {
        val now = System.currentTimeMillis()
        val updates = mutableMapOf<Long, DeviceState>()
        var matched = 0

        devices.forEach { device ->
            // Quanti dispositivi riconoscono questo topic: zero e il caso che
            // l'API di debug deve poter mostrare, perche a occhio non si vede.
            if (DiagnosticsLog.enabled && device.subscriptions.any { MqttTopics.matches(it, topic) }) {
                matched++
            }
            val current = _states.value[device.id] ?: DeviceState()
            var next = current

            if (device.stateTopic.isNotBlank() && MqttTopics.matches(device.stateTopic, topic)) {
                val value = device.stateJsonKey
                    ?.let { extractJson(payload, it) }
                    ?: payload.trim()
                next = next.copy(
                    power = readPower(device, value) ?: next.power,
                    raw = value,
                    updatedAt = now,
                    pending = false,
                )
                // Le misure si leggono dal payload intero e non dal valore
                // gia estratto: `stato` e `potenza_w` sono due campi affiancati,
                // non uno dentro l'altro.
                device.powerJsonKey?.takeIf { it.isNotBlank() }?.let { key ->
                    readNumber(payload, key)?.let { next = next.copy(watts = it) }
                }
                // Dimmer che pubblica tutto su un unico topic (il caso Tasmota).
                if (device.dimmable && device.levelStateTopic.isNullOrBlank()) {
                    readLevel(device, payload)?.let { next = next.copy(level = it) }
                }
            }

            // La disponibilita si legge prima dello stato: se il dispositivo
            // dichiara di non esserci, un comando in attesa non arrivera' mai a
            // conferma e lasciarlo su "comando inviato" sarebbe una bugia.
            val availabilityTopic = device.availabilityTopic
            if (!availabilityTopic.isNullOrBlank() && MqttTopics.matches(availabilityTopic, topic)) {
                readAvailability(
                    payload,
                    device.payloadAvailable,
                    device.payloadUnavailable,
                )?.let { reachable ->
                    next = next.copy(
                        reachable = reachable,
                        pending = if (reachable) next.pending else false,
                    )
                }
            }

            // L'energia non tocca `updatedAt`: un totale che arriva non
            // significa che si sappia com'e adesso il dispositivo.
            val energyTopic = device.energyTopic
            if (!energyTopic.isNullOrBlank() && MqttTopics.matches(energyTopic, topic)) {
                device.energyTodayJsonKey?.takeIf { it.isNotBlank() }?.let { key ->
                    readNumber(payload, key)?.let { next = next.copy(kwhToday = it) }
                }
                device.energyMonthJsonKey?.takeIf { it.isNotBlank() }?.let { key ->
                    readNumber(payload, key)?.let { next = next.copy(kwhMonth = it) }
                }
            }

            val levelTopic = device.levelStateTopic
            if (!levelTopic.isNullOrBlank() && MqttTopics.matches(levelTopic, topic)) {
                readLevel(device, payload)?.let {
                    next = next.copy(level = it, updatedAt = now, pending = false)
                }
            }

            if (next !== current) updates[device.id] = next
        }

        DiagnosticsLog.incoming(topic, payload, matched)
        if (updates.isNotEmpty()) _states.update { it + updates }
    }

    private fun readPower(device: Device, value: String): Boolean? = when {
        value.equals(device.payloadOn, ignoreCase = true) -> true
        value.equals(device.payloadOff, ignoreCase = true) -> false
        else -> null
    }

    private fun readLevel(device: Device, payload: String): Int? {
        val value = device.levelJsonKey?.let { extractJson(payload, it) } ?: payload.trim()
        val raw = value.toDoubleOrNull() ?: return null
        val max = device.levelMax.takeIf { it > 0 } ?: 100
        return (raw * 100 / max).roundToInt().coerceIn(0, 100)
    }

    /** Percentuale dell'interfaccia -> unita attese dal dispositivo. */
    private fun Device.toDeviceLevel(percent: Int): Int {
        val max = levelMax.takeIf { it > 0 } ?: 100
        return (percent.coerceIn(0, 100) * max / 100.0).roundToInt()
    }

    private fun updateState(deviceId: Long, transform: (DeviceState) -> DeviceState) {
        _states.update { states ->
            states + (deviceId to transform(states[deviceId] ?: DeviceState()))
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10
        const val KEEP_ALIVE_SECONDS = 30
        const val ACTION_TIMEOUT_MS = 5_000L
        const val DISCONNECT_TIMEOUT_MS = 500L
        const val RETRY_MIN_MS = 3_000L
        const val RETRY_MAX_MS = 60_000L
    }
}
