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
    /**
     * Potenza istantanea in watt, per chi la pubblica. Zero e null sono cose
     * diverse: zero e una presa accesa che non assorbe niente — l'elettrodomestico
     * attaccato e fermo — null e una presa che i watt non li dice.
     */
    val watts: Double? = null,
    /**
     * Energia accumulata in kWh: oggi, ieri, la settimana e il mese in corso.
     * Arrivano da chi conta, non dalla presa, e restano null per i dispositivi
     * che non hanno nessuno che conti per loro.
     *
     * Anche qui zero e null sono cose diverse, e sulla scheda si vedono diverse:
     * zero e un dispositivo che non ha consumato, null e un numero che nessuno
     * ha detto — una presa aggiunta stamattina un ieri non ce l'ha.
     */
    val kwhToday: Double? = null,
    val kwhYesterday: Double? = null,
    val kwhWeek: Double? = null,
    val kwhMonth: Double? = null,
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
