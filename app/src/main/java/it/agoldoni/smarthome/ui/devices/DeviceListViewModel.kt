package it.agoldoni.smarthome.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.agoldoni.smarthome.data.DeviceRepository
import it.agoldoni.smarthome.data.registry.RegistryProposal
import it.agoldoni.smarthome.data.registry.RegistryStatus
import it.agoldoni.smarthome.data.registry.RegistrySync
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

data class DeviceListUiState(
    val devices: List<DeviceUi> = emptyList(),
    val connection: ConnectionState = ConnectionState.NotConfigured,
    val loading: Boolean = true,
    /** Comandi partiti da questa app da quando e aperta. */
    val commandsSent: Int = 0,
)

class DeviceListViewModel(
    repository: DeviceRepository,
    private val driver: DeviceDriver,
    private val registry: RegistrySync,
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

    val uiState: StateFlow<DeviceListUiState> = combine(
        repository.devices,
        driver.states,
        driver.connection,
        driver.commandsSent,
    ) { devices, states, connection, commandsSent ->
        DeviceListUiState(
            devices = devices.map { DeviceUi(it, states[it.id] ?: DeviceState()) },
            connection = connection,
            loading = false,
            commandsSent = commandsSent,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = DeviceListUiState(),
    )

    fun reconnect() = driver.reconnect()

    fun setPower(device: Device, on: Boolean) = send(device, DeviceCommand.Power(on))

    fun setLevel(device: Device, percent: Int) = send(device, DeviceCommand.Level(percent))

    private fun send(device: Device, command: DeviceCommand) {
        viewModelScope.launch {
            runCatching { driver.send(device, command) }
                .onFailure { _messages.tryEmit(it.message ?: "Comando non riuscito") }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
