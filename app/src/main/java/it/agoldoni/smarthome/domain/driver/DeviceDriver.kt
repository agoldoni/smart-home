package it.agoldoni.smarthome.domain.driver

import it.agoldoni.smarthome.domain.model.ConnectionState
import it.agoldoni.smarthome.domain.model.Device
import it.agoldoni.smarthome.domain.model.DeviceState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Un messaggio arrivato su un topic che il driver segue per conto di qualcun
 * altro, consegnato senza interpretarlo.
 */
data class IncomingMessage(val topic: String, val payload: String)

/** Un comando diretto a un dispositivo, indipendente dal protocollo. */
sealed interface DeviceCommand {
    data class Power(val on: Boolean) : DeviceCommand

    data class Level(val percent: Int) : DeviceCommand
}

/**
 * Il canale verso i dispositivi.
 *
 * Interfaccia e non classe perche il protocollo e l'unica parte dell'app
 * destinata a cambiare: oggi MQTT, domani magari Tuya in LAN o una REST. Il
 * resto (persistenza, ViewModel, schermate) parla solo di [Device] e
 * [DeviceCommand] e non va toccato quando si aggiunge un driver.
 */
interface DeviceDriver {

    val connection: StateFlow<ConnectionState>

    /** Stato corrente per id dispositivo. Contiene solo cio che e stato osservato. */
    val states: StateFlow<Map<Long, DeviceState>>

    /**
     * Quanti comandi sono davvero usciti da qui, da quando il processo e vivo.
     *
     * Serve a rispondere a una domanda sola, e non teorica: quando un
     * dispositivo si muove, e stata l'app? Se questo numero non si alza, no — e
     * si va a cercare altrove. Conta le pubblicazioni riuscite, non i tentativi:
     * un comando fallito lo si vede gia comparire come errore.
     */
    val commandsSent: StateFlow<Int>

    /**
     * Dichiara l'insieme dei dispositivi da seguire. Il driver si iscrive a quelli
     * nuovi e abbandona quelli spariti; va richiamata a ogni modifica dell'elenco.
     */
    fun track(devices: List<Device>)

    /** Invia un comando. Solleva un'eccezione se il canale non e disponibile. */
    suspend fun send(device: Device, command: DeviceCommand)

    /**
     * Dichiara i topic da seguire che **non appartengono a nessun dispositivo**,
     * con il QoS voluto. Dichiarativa come [track]: l'insieme passato e' quello
     * che si vuole, e il driver aggiunge e abbandona di conseguenza.
     *
     * Serve perche' le sottoscrizioni nascono tutte dai dispositivi, e ce n'e'
     * almeno una che deve esistere anche quando i dispositivi sono zero — il
     * registro condiviso, che su un telefono appena installato e' l'unica cosa
     * che puo' portarceli.
     *
     * Il driver non sa cosa ci sia dentro quei topic e non deve saperlo: un
     * topic e un payload restano concetti di trasporto, l'interpretazione sta
     * altrove. E' la stessa ragione per cui questa interfaccia esiste.
     */
    fun watch(topics: Map<String, Int>)

    /**
     * I messaggi arrivati sui topic di [watch], grezzi.
     *
     * Tiene in memoria l'ultimo: i messaggi ritenuti arrivano nell'istante in
     * cui ci si sottoscrive, e chi ascolta potrebbe essersi messo in ascolto un
     * momento dopo.
     */
    val incoming: SharedFlow<IncomingMessage>

    /** Forza una riconnessione, per il pulsante nelle impostazioni. */
    fun reconnect()
}
