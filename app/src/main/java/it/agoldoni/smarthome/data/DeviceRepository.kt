package it.agoldoni.smarthome.data

import it.agoldoni.smarthome.data.local.DeviceDao
import it.agoldoni.smarthome.data.local.toDomain
import it.agoldoni.smarthome.data.local.toEntity
import it.agoldoni.smarthome.domain.model.Device
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** L'elenco dei dispositivi registrati, unica fonte di verita per app e driver. */
class DeviceRepository(private val dao: DeviceDao) {

    val devices: Flow<List<Device>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun find(id: Long): Device? = dao.findById(id)?.toDomain()

    /** Salva un dispositivo nuovo (id 0) o aggiorna quello esistente. */
    suspend fun save(device: Device): Long =
        if (device.id == 0L) {
            dao.insert(device.toEntity())
        } else {
            dao.update(device.toEntity())
            device.id
        }

    suspend fun delete(id: Long) = dao.delete(id)
}
