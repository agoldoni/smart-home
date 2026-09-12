package it.agoldoni.smarthome.data.registry

import it.agoldoni.smarthome.data.DeviceRepository
import it.agoldoni.smarthome.data.settings.RegistrySettings
import it.agoldoni.smarthome.data.settings.RegistryStore
import it.agoldoni.smarthome.diagnostics.DiagnosticsLog
import it.agoldoni.smarthome.domain.driver.DeviceDriver
import it.agoldoni.smarthome.domain.driver.IncomingMessage
import it.agoldoni.smarthome.domain.registry.DEFAULT_REGISTRY_PREFIX
import it.agoldoni.smarthome.domain.registry.DeviceRegistry
import it.agoldoni.smarthome.domain.registry.NO_REVISION
import it.agoldoni.smarthome.domain.registry.RegistryPlan
import it.agoldoni.smarthome.domain.registry.RegistryRead
import it.agoldoni.smarthome.domain.registry.RegistryRejection
import it.agoldoni.smarthome.domain.registry.isNewerThan
import it.agoldoni.smarthome.domain.registry.planRegistry
import it.agoldoni.smarthome.domain.registry.readRegistry
import it.agoldoni.smarthome.domain.registry.registryTopic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Come sta il registro adesso, per le impostazioni e per la API di diagnostica. */
data class RegistryStatus(
    val followEnabled: Boolean = true,
    /** L'interruttore e' acceso **e** un registro e' davvero arrivato. */
    val following: Boolean = false,
    val prefix: String = DEFAULT_REGISTRY_PREFIX,
    val topic: String = registryTopic(DEFAULT_REGISTRY_PREFIX),
    val revision: Int = NO_REVISION,
    val receivedAt: Long = 0L,
    /** Quanti dispositivi portava l'ultimo registro letto. */
    val deviceCount: Int = 0,
    /** I dispositivi che l'ultimo registro conteneva ma che sono stati scartati. */
    val skipped: List<String> = emptyList(),
    /** L'ultimo documento buttato via per intero, col motivo. */
    val lastRejection: String? = null,
)

/** Un registro che aspetta un si' prima di togliere qualcosa. */
data class RegistryProposal(
    val revision: Int,
    val incoming: Int,
    /** I nomi di quello che sparirebbe. Si mostrano: sono dispositivi veri. */
    val removing: List<String>,
)

/**
 * Tiene il database allineato al registro pubblicato sul broker.
 *
 * Sta fuori dal driver di proposito: il driver segue un topic e consegna un
 * payload, e non deve sapere che quel payload sia un registro. Qui invece non
 * si sa niente di MQTT.
 */
class RegistrySync(
    private val scope: CoroutineScope,
    private val driver: DeviceDriver,
    private val repository: DeviceRepository,
    private val store: RegistryStore,
) {

    private val _status = MutableStateFlow(RegistryStatus())
    val status: StateFlow<RegistryStatus> = _status.asStateFlow()

    private val _proposal = MutableStateFlow<RegistryProposal?>(null)
    val proposal: StateFlow<RegistryProposal?> = _proposal.asStateFlow()

    private val lock = Mutex()

    @Volatile
    private var settingsNow = RegistrySettings()

    private var pending: Pair<DeviceRegistry, RegistryPlan>? = null

    fun start() {
        scope.launch {
            store.settings.distinctUntilChanged().collect { impostazioni ->
                settingsNow = impostazioni
                driver.watch(
                    if (impostazioni.follow) mapOf(impostazioni.topic to REGISTRY_QOS) else emptyMap(),
                )
                _status.update {
                    it.copy(
                        followEnabled = impostazioni.follow,
                        following = impostazioni.following,
                        prefix = impostazioni.prefix,
                        topic = impostazioni.topic,
                        revision = impostazioni.appliedRevision,
                        receivedAt = impostazioni.receivedAt,
                    )
                }
                if (!impostazioni.follow) scartaProposta()
            }
        }
        scope.launch {
            driver.incoming.collect { onMessage(it) }
        }
    }

    /** Si', applica: rimuovi quello che il registro non nomina. */
    fun confirm() {
        scope.launch {
            lock.withLock {
                val (registro, piano) = pending ?: return@withLock
                pending = null
                _proposal.value = null
                esegui(registro, piano)
            }
        }
    }

    /**
     * No.
     *
     * Spegne anche l'interruttore, invece di limitarsi a chiudere il messaggio:
     * altrimenti la stessa domanda tornerebbe a ogni riconnessione, e una
     * domanda che si ripresenta da sola si impara a chiuderla senza leggerla.
     * Si riaccende dalle impostazioni.
     */
    fun dismiss() {
        scope.launch {
            lock.withLock { scartaProposta() }
            store.setFollow(false)
            DiagnosticsLog.event("registro", "prima applicazione rifiutata: l'app smette di seguirlo")
        }
    }

    private fun scartaProposta() {
        pending = null
        _proposal.value = null
    }

    private suspend fun onMessage(message: IncomingMessage) = lock.withLock {
        val impostazioni = settingsNow
        if (!impostazioni.follow) return@withLock
        // Dopo un cambio di prefisso il replay puo' riconsegnare il messaggio
        // del ramo di prima: non e' piu' il nostro registro.
        if (message.topic != impostazioni.topic) return@withLock

        when (val letto = readRegistry(message.payload)) {
            is RegistryRead.Rejected -> rifiutato(letto)
            is RegistryRead.Ok -> applica(letto.registry)
        }
    }

    private suspend fun rifiutato(letto: RegistryRead.Rejected) {
        if (letto.reason == RegistryRejection.EMPTY) {
            DiagnosticsLog.event(
                "registro",
                "cancellato dal broker: l'app smette di seguirlo e tiene i dispositivi che ha",
            )
            scartaProposta()
            store.forget()
            _status.update { it.copy(lastRejection = null, deviceCount = 0, skipped = emptyList()) }
            return
        }
        val motivo = "${letto.reason.name.lowercase()}: ${letto.detail}"
        DiagnosticsLog.event("registro", "documento rifiutato in blocco — $motivo")
        // Il registro applicato in precedenza resta dov'e'. Un documento
        // incomprensibile non e' un documento vuoto.
        _status.update { it.copy(lastRejection = motivo) }
    }

    private suspend fun applica(registro: DeviceRegistry) {
        _status.update {
            it.copy(
                lastRejection = null,
                deviceCount = registro.devices.size,
                skipped = registro.skipped,
            )
        }
        if (registro.skipped.isNotEmpty()) {
            DiagnosticsLog.event(
                "registro",
                "scartati ${registro.skipped.size}: ${registro.skipped.joinToString("; ")}",
            )
        }
        if (!registro.isNewerThan(settingsNow.appliedRevision)) {
            DiagnosticsLog.event(
                "registro",
                "revisione ${registro.revision} gia' applicata, ignorata",
            )
            return
        }

        val locali = repository.snapshot()
        val piano = planRegistry(registro, locali)

        // Solo la prima volta, e solo se c'e' davvero qualcosa da togliere:
        // fonte di verita' unica vuol dire che quello che non c'e' sparisce, e
        // la prima volta chi usa l'app non se lo aspetta.
        if (!settingsNow.bootstrapped && piano.deletedIds.isNotEmpty()) {
            val daRimuovere = piano.deletedIds.toSet()
            pending = registro to piano
            _proposal.value = RegistryProposal(
                revision = registro.revision,
                incoming = registro.devices.size,
                removing = locali.filter { it.id in daRimuovere }.map { it.name },
            )
            DiagnosticsLog.event(
                "registro",
                "prima applicazione in attesa di conferma: ${registro.devices.size} dal registro, " +
                    "${piano.deletedIds.size} da rimuovere",
            )
            return
        }

        esegui(registro, piano)
    }

    private suspend fun esegui(registro: DeviceRegistry, piano: RegistryPlan) {
        repository.apply(piano)
        store.record(registro.revision, System.currentTimeMillis())
        DiagnosticsLog.event(
            "registro",
            if (piano.isEmpty) {
                "revisione ${registro.revision}: niente da cambiare"
            } else {
                "revisione ${registro.revision} applicata: ${piano.inserted.size} aggiunti, " +
                    "${piano.updated.size} aggiornati, ${piano.deletedIds.size} rimossi, " +
                    "${piano.adoptedIds.size} adottati"
            },
        )
    }

    private companion object {
        /**
         * QoS 1 sul registro: e' ritenuto, quindi arriva comunque alla
         * sottoscrizione, ma una consegna persa qui costa una configurazione di
         * casa che non si aggiorna.
         */
        const val REGISTRY_QOS = 1
    }
}
