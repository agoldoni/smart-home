package it.agoldoni.smarthome.di

import android.content.Context
import androidx.room.Room
import it.agoldoni.smarthome.data.DeviceRepository
import it.agoldoni.smarthome.data.registry.RegistrySync
import it.agoldoni.smarthome.data.local.MIGRATION_1_2
import it.agoldoni.smarthome.data.local.MIGRATION_2_3
import it.agoldoni.smarthome.data.local.MIGRATION_3_4
import it.agoldoni.smarthome.data.local.MIGRATION_4_5
import it.agoldoni.smarthome.data.local.SmartHomeDatabase
import it.agoldoni.smarthome.data.settings.BrokerSettingsStore
import it.agoldoni.smarthome.data.settings.RegistryStore
import it.agoldoni.smarthome.data.settings.ViewLockStore
import it.agoldoni.smarthome.diagnostics.DebugBridge
import it.agoldoni.smarthome.domain.driver.DeviceDriver
import it.agoldoni.smarthome.domain.model.Device
import it.agoldoni.smarthome.driver.mqtt.MqttDeviceDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Dipendenze dell'app, costruite a mano.
 *
 * Con una dozzina di oggetti un contenitore esplicito resta piu leggibile di un
 * framework a annotazioni, e soprattutto tiene la build senza processori di
 * annotazioni oltre a quello di Room.
 */
class AppContainer(context: Context) {

    private val applicationContext = context.applicationContext

    /** Vive quanto il processo: ci gira il collegamento al broker. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database: SmartHomeDatabase by lazy {
        Room.databaseBuilder(applicationContext, SmartHomeDatabase::class.java, "smart-home.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }

    val settingsStore: BrokerSettingsStore by lazy { BrokerSettingsStore(applicationContext) }

    val deviceRepository: DeviceRepository by lazy { DeviceRepository(database, database.deviceDao()) }

    val driver: DeviceDriver by lazy { MqttDeviceDriver(applicationScope, settingsStore.settings) }

    /**
     * Un file di preferenze separato da quello del broker, e non per ordine:
     * il driver riapre il collegamento a ogni [BrokerSettingsStore] diverso, e
     * la revisione del registro cambia a ogni salvataggio sul configuratore.
     */
    val registryStore: RegistryStore by lazy { RegistryStore(applicationContext) }

    /** Il lucchetto della vista principale. Un file suo, vedi [ViewLockStore]. */
    val viewLockStore: ViewLockStore by lazy { ViewLockStore(applicationContext) }

    /**
     * Il lucchetto leggibile senza aprire una coroutine, per la stessa ragione
     * di [devices]: l'API di debug racconta lo stato dell'app da fuori, e non
     * ha dove sospendere.
     */
    val viewLocked: StateFlow<Boolean> =
        viewLockStore.locked.stateIn(applicationScope, SharingStarted.Eagerly, false)

    val registrySync: RegistrySync by lazy {
        RegistrySync(applicationScope, driver, deviceRepository, registryStore)
    }

    private val _devices = MutableStateFlow<List<Device>>(emptyList())

    /**
     * L'ultimo elenco arrivato dal database. Il contenitore lo attraversa gia per
     * passarlo al driver: tenerne memoria costa un riferimento e da a chi guarda
     * l'app da fuori un modo di leggerlo senza aprire una coroutine.
     */
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    /**
     * Tiene allineato il driver all'elenco registrato. Sta qui e non in un
     * ViewModel perche le sottoscrizioni non devono cadere quando l'utente
     * cambia schermata.
     */
    fun start() {
        DebugBridge.install(applicationContext, this)
        // Prima del collettore dei dispositivi: e' questo che dichiara al driver
        // il topic da seguire, e il registro e' ritenuto — arriva nell'istante
        // della sottoscrizione.
        registrySync.start()
        applicationScope.launch {
            deviceRepository.devices.collect { list ->
                _devices.value = list
                driver.track(list)
            }
        }
    }
}
