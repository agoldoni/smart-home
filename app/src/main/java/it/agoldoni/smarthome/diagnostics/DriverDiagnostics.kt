package it.agoldoni.smarthome.diagnostics

import it.agoldoni.smarthome.domain.model.ConnectionState

/**
 * Le interiora di un driver in un istante preciso.
 *
 * La password non c'e e non va aggiunta: questo oggetto finisce in chiaro in una
 * risposta HTTP sulla rete di casa. [passwordSet] dice l'unica cosa che serve
 * davvero sapere, cioe se ce n'e una.
 */
data class DriverSnapshot(
    val protocol: String,
    val connection: ConnectionState,
    val brokerConfigured: Boolean,
    val serverUri: String,
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val username: String,
    val passwordSet: Boolean,
    /** Quello scritto nelle impostazioni: vuoto significa "generane uno tu". */
    val clientIdSetting: String,
    /** Quello davvero passato al broker. */
    val clientIdInUse: String,
    /** C'e un client aperto, a prescindere da come stia. */
    val clientAlive: Boolean,
    /** Il client aperto si considera connesso. Puo smentire [connection], ed e il caso da vedere. */
    val clientConnected: Boolean,
    val trackedDeviceIds: List<Long>,
    /** Topic sottoscritti sul broker, con il QoS usato. */
    val subscriptions: Map<String, Int>,
)

/**
 * Un driver che sa raccontarsi.
 *
 * Interfaccia a parte e non metodo di [it.agoldoni.smarthome.domain.driver.DeviceDriver]:
 * saper parlare un protocollo e saper spiegare cosa si sta facendo sono due
 * mestieri diversi, e un driver nuovo deve poter nascere senza il secondo. Chi
 * legge le diagnostiche prova un cast e si arrangia se non c'e.
 */
interface DriverDiagnostics {
    fun diagnostics(): DriverSnapshot
}
