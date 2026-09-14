package it.agoldoni.smarthome.ui.devices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.agoldoni.smarthome.data.DeviceRepository
import it.agoldoni.smarthome.data.registry.RegistrySync
import it.agoldoni.smarthome.domain.model.Device
import it.agoldoni.smarthome.domain.model.DeviceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val ARG_DEVICE_ID = "deviceId"
const val NEW_DEVICE_ID = 0L

/** Lo stesso dispositivo, in forma modificabile: tutti i campi sono testo. */
data class DeviceForm(
    val name: String = "",
    val room: String = "",
    val kind: DeviceKind = DeviceKind.SWITCH,
    val stateTopic: String = "",
    val commandTopic: String = "",
    val payloadOn: String = "ON",
    val payloadOff: String = "OFF",
    val stateJsonKey: String = "",
    val powerJsonKey: String = "",
    val energyTopic: String = "",
    val energyTodayJsonKey: String = "",
    val energyYesterdayJsonKey: String = "",
    val energyWeekJsonKey: String = "",
    val energyMonthJsonKey: String = "",
    val availabilityTopic: String = "",
    val payloadAvailable: String = "online",
    val payloadUnavailable: String = "offline",
    val levelStateTopic: String = "",
    val levelCommandTopic: String = "",
    val levelJsonKey: String = "",
    val levelMaxText: String = "100",
    val qos: Int = 0,
    val retained: Boolean = false,
)

data class DeviceEditUiState(
    val form: DeviceForm = DeviceForm(),
    val isNew: Boolean = true,
    val loading: Boolean = true,
    val nameError: String? = null,
    val stateTopicError: String? = null,
    val commandTopicError: String? = null,
    val levelCommandTopicError: String? = null,
    val energyTodayJsonKeyError: String? = null,
    /** Salvataggio o cancellazione conclusi: la schermata si chiude. */
    val closed: Boolean = false,
    /**
     * L'app segue un registro: i dispositivi si modificano dal configuratore e
     * qui si guardano soltanto. Il modulo resta visibile — vedere com'e'
     * configurato un dispositivo serve anche quando non lo si puo' cambiare.
     */
    val readOnly: Boolean = false,
)

class DeviceEditViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: DeviceRepository,
    registry: RegistrySync,
) : ViewModel() {

    private val deviceId: Long = savedStateHandle.get<String>(ARG_DEVICE_ID)?.toLongOrNull() ?: NEW_DEVICE_ID

    private val _uiState = MutableStateFlow(DeviceEditUiState(isNew = deviceId == NEW_DEVICE_ID))
    val uiState: StateFlow<DeviceEditUiState> = _uiState.asStateFlow()

    /**
     * L'identita' nel registro, tenuta da parte perche' non e' un campo del
     * modulo: non si scrive a mano e non si vede. Ma va riportata nel
     * dispositivo salvato, altrimenti una modifica fatta a mano — possibile dopo
     * aver spento "segui il registro" — lo scollegherebbe dal registro, e al
     * ritorno si ritroverebbe adottato o duplicato.
     */
    private var uuid: String = ""

    /**
     * Il posto nell'elenco, tenuto da parte per la stessa ragione dell'uuid: non
     * e' un campo del modulo — l'ordine si decide dal configuratore — ma il
     * salvataggio ricostruisce il dispositivo da zero, e senza conservarlo qui
     * ogni correzione fatta dal telefono lo manderebbe in fondo all'elenco. Da
     * dove non tornerebbe: il registro e' ritenuto e quella revisione e' gia'
     * stata applicata, quindi non si riapplica.
     */
    private var position: Int? = null

    init {
        viewModelScope.launch {
            val existing = if (deviceId == NEW_DEVICE_ID) null else repository.find(deviceId)
            uuid = existing?.uuid.orEmpty()
            position = existing?.position
            _uiState.update { it.copy(form = existing?.toForm() ?: DeviceForm(), loading = false) }
        }
        viewModelScope.launch {
            registry.status.collect { stato ->
                _uiState.update { it.copy(readOnly = stato.following) }
            }
        }
    }

    /** Ogni modifica azzera gli errori: segnalarli mentre si corregge e solo rumore. */
    fun edit(transform: (DeviceForm) -> DeviceForm) {
        _uiState.update {
            it.copy(
                form = transform(it.form),
                nameError = null,
                stateTopicError = null,
                commandTopicError = null,
                levelCommandTopicError = null,
                energyTodayJsonKeyError = null,
            )
        }
    }

    fun save() {
        if (_uiState.value.readOnly) return
        val form = _uiState.value.form
        val validated = validate(form)
        if (validated != null) {
            _uiState.update { validated }
            return
        }
        viewModelScope.launch {
            repository.save(form.toDevice(deviceId, uuid, position))
            _uiState.update { it.copy(closed = true) }
        }
    }

    fun delete() {
        if (_uiState.value.readOnly) return
        if (deviceId == NEW_DEVICE_ID) return
        viewModelScope.launch {
            repository.delete(deviceId)
            _uiState.update { it.copy(closed = true) }
        }
    }

    /** Restituisce lo stato con gli errori, oppure null se il modulo e valido. */
    private fun validate(form: DeviceForm): DeviceEditUiState? {
        val nameError = if (form.name.isBlank()) "Il nome e obbligatorio" else null
        val stateTopicError = when {
            form.stateTopic.isBlank() -> "Il topic di stato e obbligatorio"
            else -> null
        }
        val commandTopicError = when {
            form.kind == DeviceKind.SENSOR -> null
            form.commandTopic.isBlank() -> "Il topic di comando e obbligatorio"
            // Le wildcard valgono per chi ascolta: pubblicare su `+` o `#` non
            // significa niente, il broker rifiuta il messaggio.
            form.commandTopic.hasWildcard() -> "Un topic di comando non puo contenere + o #"
            else -> null
        }
        val levelCommandTopicError = when {
            form.kind != DeviceKind.DIMMER -> null
            form.levelCommandTopic.isBlank() -> "Serve il topic per regolare il livello"
            form.levelCommandTopic.hasWildcard() -> "Un topic di comando non puo contenere + o #"
            else -> null
        }

        // Un topic dei consumi senza il campo da leggerci dentro e il modo piu
        // silenzioso di non funzionare: l'app si iscrive, i messaggi arrivano,
        // e la scheda resta vuota senza che niente lo dica.
        val energyTodayJsonKeyError = when {
            form.energyTopic.isBlank() -> null
            form.energyTodayJsonKey.isBlank() -> "Serve il campo da leggere nel payload dei consumi"
            else -> null
        }

        val errors = listOf(nameError, stateTopicError, commandTopicError,
                            levelCommandTopicError, energyTodayJsonKeyError)
        if (errors.all { it == null }) return null

        return _uiState.value.copy(
            nameError = nameError,
            stateTopicError = stateTopicError,
            commandTopicError = commandTopicError,
            levelCommandTopicError = levelCommandTopicError,
            energyTodayJsonKeyError = energyTodayJsonKeyError,
        )
    }
}

private fun String.hasWildcard(): Boolean = contains('+') || contains('#')

private fun Device.toForm() = DeviceForm(
    name = name,
    room = room,
    kind = kind,
    stateTopic = stateTopic,
    commandTopic = commandTopic,
    payloadOn = payloadOn,
    payloadOff = payloadOff,
    stateJsonKey = stateJsonKey.orEmpty(),
    powerJsonKey = powerJsonKey.orEmpty(),
    energyTopic = energyTopic.orEmpty(),
    energyTodayJsonKey = energyTodayJsonKey.orEmpty(),
    energyYesterdayJsonKey = energyYesterdayJsonKey.orEmpty(),
    energyWeekJsonKey = energyWeekJsonKey.orEmpty(),
    energyMonthJsonKey = energyMonthJsonKey.orEmpty(),
    availabilityTopic = availabilityTopic.orEmpty(),
    payloadAvailable = payloadAvailable,
    payloadUnavailable = payloadUnavailable,
    levelStateTopic = levelStateTopic.orEmpty(),
    levelCommandTopic = levelCommandTopic.orEmpty(),
    levelJsonKey = levelJsonKey.orEmpty(),
    levelMaxText = levelMax.toString(),
    qos = qos,
    retained = retained,
)

private fun DeviceForm.toDevice(id: Long, uuid: String, position: Int?) = Device(
    id = id,
    uuid = uuid,
    position = position,
    name = name.trim(),
    room = room.trim(),
    kind = kind,
    stateTopic = stateTopic.trim(),
    commandTopic = if (kind == DeviceKind.SENSOR) "" else commandTopic.trim(),
    payloadOn = payloadOn.ifBlank { "ON" },
    payloadOff = payloadOff.ifBlank { "OFF" },
    stateJsonKey = stateJsonKey.trim().takeIf { it.isNotEmpty() },
    powerJsonKey = powerJsonKey.trim().takeIf { it.isNotEmpty() },
    energyTopic = energyTopic.trim().takeIf { it.isNotEmpty() },
    energyTodayJsonKey = energyTodayJsonKey.trim().takeIf { it.isNotEmpty() },
    energyYesterdayJsonKey = energyYesterdayJsonKey.trim().takeIf { it.isNotEmpty() },
    energyWeekJsonKey = energyWeekJsonKey.trim().takeIf { it.isNotEmpty() },
    energyMonthJsonKey = energyMonthJsonKey.trim().takeIf { it.isNotEmpty() },
    availabilityTopic = availabilityTopic.trim().takeIf { it.isNotEmpty() },
    payloadAvailable = payloadAvailable.ifBlank { "online" },
    payloadUnavailable = payloadUnavailable.ifBlank { "offline" },
    levelStateTopic = levelStateTopic.trim().takeIf { it.isNotEmpty() && kind == DeviceKind.DIMMER },
    levelCommandTopic = levelCommandTopic.trim().takeIf { it.isNotEmpty() && kind == DeviceKind.DIMMER },
    levelJsonKey = levelJsonKey.trim().takeIf { it.isNotEmpty() && kind == DeviceKind.DIMMER },
    levelMax = levelMaxText.toIntOrNull()?.coerceIn(1, 65535) ?: 100,
    qos = qos,
    retained = retained,
)
