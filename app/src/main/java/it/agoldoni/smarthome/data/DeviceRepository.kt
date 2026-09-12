package it.agoldoni.smarthome.data

import androidx.room.withTransaction
import it.agoldoni.smarthome.data.local.DeviceDao
import it.agoldoni.smarthome.data.local.SmartHomeDatabase
import it.agoldoni.smarthome.data.local.toDomain
import it.agoldoni.smarthome.data.local.toEntity
import it.agoldoni.smarthome.domain.model.Device
import it.agoldoni.smarthome.domain.registry.RegistryPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** L'elenco dei dispositivi registrati, unica fonte di verita per app e driver. */
class DeviceRepository(
    private val database: SmartHomeDatabase,
    private val dao: DeviceDao,
) {

    val devices: Flow<List<Device>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun find(id: Long): Device? = dao.findById(id)?.toDomain()

    /** Tutti, in un colpo solo: serve a calcolare il piano di un registro. */
    suspend fun snapshot(): List<Device> = dao.findAll().map { it.toDomain() }

    /** Salva un dispositivo nuovo (id 0) o aggiorna quello esistente. */
    suspend fun save(device: Device): Long =
        if (device.id == 0L) {
            dao.insert(device.toEntity())
        } else {
            dao.update(device.toEntity())
            device.id
        }

    suspend fun delete(id: Long) = dao.delete(id)

    /**
     * Applica un piano calcolato da un registro.
     *
     * Tutto dentro una transazione sola, e **aggiornando in loco** invece di
     * cancellare e reinserire: gli id restano quelli, quindi restano anche gli
     * stati osservati, che sono indicizzati per id. Un piano vuoto non tocca il
     * database e non fa riemettere niente — ed e' il caso normale, perche' il
     * registro viene ripubblicato per intero anche quando cambia una virgola.
     */
    suspend fun apply(plan: RegistryPlan) {
        if (plan.isEmpty) return
        database.withTransaction {
            if (plan.deletedIds.isNotEmpty()) dao.deleteAll(plan.deletedIds)
            if (plan.updated.isNotEmpty()) dao.updateAll(plan.updated.map { it.toEntity() })
            if (plan.inserted.isNotEmpty()) dao.insertAll(plan.inserted.map { it.toEntity() })
        }
    }
}
