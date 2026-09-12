package it.agoldoni.smarthome.domain.model

/**
 * Lo stato di un dispositivo per come lo conosce l'app.
 *
 * Tutto e nullabile perche prima del primo messaggio non si sa niente: mostrare
 * "spento" a un dispositivo di cui non e ancora arrivato lo stato sarebbe una
 * bugia, e su un interruttore le bugie si pagano.
 */
data class DeviceState(
    val power: Boolean? = null,
    /** Livello 0-100 per i dispositivi regolabili. */
    val level: Int? = null,
    /** Ultimo payload ricevuto, cosi com'e. E il valore mostrato dai sensori. */
    val raw: String? = null,
    val updatedAt: Long? = null,
    /**
     * Comando inviato e conferma non ancora arrivata: l'interfaccia si muove
     * subito ma segnala che il dispositivo non ha ancora risposto.
     */
    val pending: Boolean = false,
    /**
     * Raggiungibilita dichiarata sul topic di disponibilita. Null quando non se
     * ne sa niente: il dispositivo non ne ha uno, oppure non e ancora arrivato
     * niente. Null e false sono cose diverse e non vanno confuse.
     */
    val reachable: Boolean? = null,
) {
    val known: Boolean get() = updatedAt != null

    /** Il dispositivo ha dichiarato di non esserci. */
    val unreachable: Boolean get() = reachable == false
}
