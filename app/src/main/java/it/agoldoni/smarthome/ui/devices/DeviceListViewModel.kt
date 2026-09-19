package it.agoldoni.smarthome.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.agoldoni.smarthome.data.DeviceRepository
import it.agoldoni.smarthome.data.registry.RegistryProposal
import it.agoldoni.smarthome.data.registry.RegistryStatus
import it.agoldoni.smarthome.data.registry.RegistrySync
import it.agoldoni.smarthome.data.settings.ViewPrefsStore
import it.agoldoni.smarthome.diagnostics.DebugBridge
import it.agoldoni.smarthome.diagnostics.DebugStatus
import it.agoldoni.smarthome.domain.driver.DeviceCommand
import it.agoldoni.smarthome.domain.driver.DeviceDriver
import it.agoldoni.smarthome.domain.model.ConnectionState
import it.agoldoni.smarthome.domain.model.Device
import it.agoldoni.smarthome.domain.model.DeviceState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Un dispositivo insieme a cio che si sa del suo stato. */
data class DeviceUi(
    val device: Device,
    val state: DeviceState,
)

/**
 * Se il comando puo' partire, e quindi se il controllo va acceso.
 *
 * Due motivi diversi per la stessa risposta: un dispositivo che ha dichiarato
 * di non esserci non riceverebbe il comando, e a vista bloccata il comando non
 * lo vogliamo mandare. Sta fuori dai Composable perche' e' l'unica regola di
 * questa feature che si possa provare in JVM.
 */
fun DeviceUi.commandable(locked: Boolean): Boolean = !locked && !state.unreachable

data class DeviceListUiState(
    val devices: List<DeviceUi> = emptyList(),
    val connection: ConnectionState = ConnectionState.NotConfigured,
    val loading: Boolean = true,
    /** Comandi partiti da questa app da quando e aperta. */
    val commandsSent: Int = 0,
    /** La vista e' in sola lettura: i comandi non partono. */
    val locked: Boolean = false,
)

class DeviceListViewModel(
    repository: DeviceRepository,
    private val driver: DeviceDriver,
    private val registry: RegistrySync,
    private val viewPrefs: ViewPrefsStore,
) : ViewModel() {

    /** Segue un registro: da qui non si aggiungono dispositivi. */
    val registryStatus: StateFlow<RegistryStatus> = registry.status

    /** Il primo registro aspetta un si' prima di togliere qualcosa. */
    val proposal: StateFlow<RegistryProposal?> = registry.proposal

    fun acceptRegistry() = registry.confirm()

    fun refuseRegistry() = registry.dismiss()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Errori di invio, mostrati come snackbar. */
    val messages: SharedFlow<String> = _messages

    /** Serve solo al segno rosso in cima: l'elenco dei dispositivi non ne sa niente. */
    val debugStatus: StateFlow<DebugStatus> = DebugBridge.status

    /**
     * Il lucchetto sta **dentro** questo `combine`, e non e' un dettaglio.
     *
     * La preferenza arriva da DataStore, quindi asincrona. Finche' uno dei
     * flussi non ha emesso, `uiState` resta al valore iniziale con `loading` a
     * vero e la schermata mostra il progress invece dell'elenco: non c'e'
     * nessun interruttore da toccare. Letta fuori di qui, l'elenco esisterebbe
     * gia' armato per qualche fotogramma.
     */
    val uiState: StateFlow<DeviceListUiState> = combine(
        repository.devices,
        driver.states,
        driver.connection,
        driver.commandsSent,
        viewPrefs.locked,
    ) { devices, states, connection, commandsSent, locked ->
        DeviceListUiState(
            devices = devices.map { DeviceUi(it, states[it.id] ?: DeviceState()) },
            connection = connection,
            loading = false,
            commandsSent = commandsSent,
            locked = locked,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = DeviceListUiState(),
    )

    fun reconnect() = driver.reconnect()

    fun toggleLock() {
        viewModelScope.launch { viewPrefs.setLocked(!uiState.value.locked) }
    }

    fun setPower(device: Device, on: Boolean) = send(device, DeviceCommand.Power(on))

    fun setLevel(device: Device, percent: Int) = send(device, DeviceCommand.Level(percent))

    private fun send(device: Device, command: DeviceCommand) {
        // La rete, non l'interfaccia. Quello che l'utente vede e' il controllo
        // spento; questa riga esiste perche' una via di comando aggiunta domani
        // non scavalchi il lucchetto in silenzio. Non e' ridondanza: sono due
        // presidi con due lavori diversi. `loading` e' dentro la condizione per
        // lo stesso motivo: finche' la preferenza non e' arrivata non si sa se
        // la vista sia bloccata, e nel dubbio non si comanda.
        val stato = uiState.value
        if (stato.loading || stato.locked) return
        viewModelScope.launch {
            runCatching { driver.send(device, command) }
                .onFailure { _messages.tryEmit(it.message ?: "Comando non riuscito") }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
