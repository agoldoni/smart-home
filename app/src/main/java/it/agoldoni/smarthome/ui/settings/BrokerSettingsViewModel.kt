package it.agoldoni.smarthome.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.agoldoni.smarthome.data.settings.BrokerSettings
import it.agoldoni.smarthome.data.settings.BrokerSettingsStore
import it.agoldoni.smarthome.data.settings.RegistrySettings
import it.agoldoni.smarthome.data.settings.RegistryStore
import it.agoldoni.smarthome.diagnostics.DebugBridge
import it.agoldoni.smarthome.diagnostics.DebugStatus
import it.agoldoni.smarthome.domain.driver.DeviceDriver
import it.agoldoni.smarthome.domain.model.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val PORT_PLAIN = 1883
const val PORT_TLS = 8883

/** La porta resta testo finche si digita: durante la modifica passa per stati non numerici. */
data class BrokerForm(
    val host: String = "",
    val portText: String = PORT_PLAIN.toString(),
    val useTls: Boolean = false,
    val username: String = "",
    val password: String = "",
    val clientId: String = "",
)

data class BrokerSettingsUiState(
    val form: BrokerForm = BrokerForm(),
    val loading: Boolean = true,
    val saved: Boolean = false,
    val portError: String? = null,
    val hostError: String? = null,
)

class BrokerSettingsViewModel(
    private val store: BrokerSettingsStore,
    private val driver: DeviceDriver,
    private val registryStore: RegistryStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrokerSettingsUiState())
    val uiState: StateFlow<BrokerSettingsUiState> = _uiState.asStateFlow()

    val connection: StateFlow<ConnectionState> = driver.connection

    val debugStatus: StateFlow<DebugStatus> = DebugBridge.status

    /**
     * Il registro ha un file di preferenze suo e non passa da [BrokerForm]: un
     * campo in piu' dentro le impostazioni del broker farebbe riaprire il
     * collegamento a ogni revisione ricevuta.
     */
    val registry: StateFlow<RegistrySettings> = registryStore.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = RegistrySettings(),
    )

    fun setFollowRegistry(follow: Boolean) {
        viewModelScope.launch { registryStore.setFollow(follow) }
    }

    /** Scritto mentre si digita: il prefisso passa per stati intermedi senza senso. */
    fun setRegistryPrefix(prefix: String) {
        viewModelScope.launch { registryStore.setPrefix(prefix) }
    }

    init {
        viewModelScope.launch {
            val stored = store.settings.first()
            _uiState.update { it.copy(form = stored.toForm(), loading = false) }
        }
    }

    fun edit(transform: (BrokerForm) -> BrokerForm) {
        _uiState.update {
            it.copy(form = transform(it.form), saved = false, portError = null, hostError = null)
        }
    }

    /**
     * Il cambio di protocollo sposta anche la porta, ma solo se era ancora quella
     * predefinita: una porta scelta a mano non va sovrascritta.
     */
    fun setTls(enabled: Boolean) = edit { form ->
        val port = when (form.portText) {
            PORT_PLAIN.toString() -> if (enabled) PORT_TLS else PORT_PLAIN
            PORT_TLS.toString() -> if (enabled) PORT_TLS else PORT_PLAIN
            else -> return@edit form.copy(useTls = enabled)
        }
        form.copy(useTls = enabled, portText = port.toString())
    }

    fun save() {
        val form = _uiState.value.form
        val port = form.portText.toIntOrNull()
        val hostError = if (form.host.isBlank()) "Indica l'indirizzo del broker" else null
        val portError = if (port == null || port !in 1..65535) "Porta non valida" else null
        if (hostError != null || portError != null) {
            _uiState.update { it.copy(hostError = hostError, portError = portError) }
            return
        }
        viewModelScope.launch {
            store.save(form.toSettings(port!!))
            _uiState.update { it.copy(saved = true) }
        }
    }

    fun reconnect() = driver.reconnect()

    fun setDebugApi(enabled: Boolean) = DebugBridge.setEnabled(enabled)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

private fun BrokerSettings.toForm() = BrokerForm(
    host = host,
    portText = port.toString(),
    useTls = useTls,
    username = username,
    password = password,
    clientId = clientId,
)

private fun BrokerForm.toSettings(port: Int) = BrokerSettings(
    host = host.trim(),
    port = port,
    useTls = useTls,
    username = username.trim(),
    password = password,
    clientId = clientId.trim(),
)
