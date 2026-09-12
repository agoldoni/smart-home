package it.agoldoni.smarthome.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import it.agoldoni.smarthome.domain.model.Device
import it.agoldoni.smarthome.domain.model.DeviceKind

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** Identita' nel registro condiviso. Vuota finche' nessun registro l'ha nominato. */
    val uuid: String,
    val name: String,
    val room: String,
    /**
     * Il tipo e salvato come stringa e non come ordinale: cosi riordinare o
     * inserire un valore in [DeviceKind] non reinterpreta i dispositivi gia
     * registrati.
     */
    val kind: String,
    val stateTopic: String,
    val commandTopic: String,
    val payloadOn: String,
    val payloadOff: String,
    val stateJsonKey: String?,
    val powerJsonKey: String?,
    val energyTopic: String?,
    val energyTodayJsonKey: String?,
    val energyMonthJsonKey: String?,
    val availabilityTopic: String?,
    val payloadAvailable: String,
    val payloadUnavailable: String,
    val levelStateTopic: String?,
    val levelCommandTopic: String?,
    val levelJsonKey: String?,
    val levelMax: Int,
    val qos: Int,
    val retained: Boolean,
)

fun DeviceEntity.toDomain(): Device = Device(
    id = id,
    uuid = uuid,
    name = name,
    room = room,
    kind = runCatching { DeviceKind.valueOf(kind) }.getOrDefault(DeviceKind.SWITCH),
    stateTopic = stateTopic,
    commandTopic = commandTopic,
    payloadOn = payloadOn,
    payloadOff = payloadOff,
    stateJsonKey = stateJsonKey,
    powerJsonKey = powerJsonKey,
    energyTopic = energyTopic,
    energyTodayJsonKey = energyTodayJsonKey,
    energyMonthJsonKey = energyMonthJsonKey,
    availabilityTopic = availabilityTopic,
    payloadAvailable = payloadAvailable,
    payloadUnavailable = payloadUnavailable,
    levelStateTopic = levelStateTopic,
    levelCommandTopic = levelCommandTopic,
    levelJsonKey = levelJsonKey,
    levelMax = levelMax,
    qos = qos,
    retained = retained,
)

fun Device.toEntity(): DeviceEntity = DeviceEntity(
    id = id,
    uuid = uuid,
    name = name,
    room = room,
    kind = kind.name,
    stateTopic = stateTopic,
    commandTopic = commandTopic,
    payloadOn = payloadOn,
    payloadOff = payloadOff,
    stateJsonKey = stateJsonKey,
    powerJsonKey = powerJsonKey,
    energyTopic = energyTopic,
    energyTodayJsonKey = energyTodayJsonKey,
    energyMonthJsonKey = energyMonthJsonKey,
    availabilityTopic = availabilityTopic,
    payloadAvailable = payloadAvailable,
    payloadUnavailable = payloadUnavailable,
    levelStateTopic = levelStateTopic,
    levelCommandTopic = levelCommandTopic,
    levelJsonKey = levelJsonKey,
    levelMax = levelMax,
    qos = qos,
    retained = retained,
)
