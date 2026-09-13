package it.agoldoni.smarthome.domain.model

/**
 * Cosa sa fare un dispositivo. Determina i controlli mostrati nella scheda e
 * quali campi sono obbligatori in fase di registrazione.
 */
enum class DeviceKind {
    /** Rele, presa comandata: acceso o spento. */
    SWITCH,

    /** Luce on/off. Si comporta come SWITCH, cambia solo l'icona. */
    LIGHT,

    /** Luce regolabile: oltre all'on/off ha un livello 0-100. */
    DIMMER,

    /** Sola lettura: si guarda il valore pubblicato, non si comanda niente. */
    SENSOR,
}

/**
 * Un dispositivo registrato nell'app.
 *
 * I campi che descrivono i topic sono deliberatamente espliciti invece di essere
 * derivati da una convenzione: i dispositivi MQTT reali (Tasmota, Zigbee2MQTT,
 * ESPHome, un ESP fatto in casa) non ne condividono nessuna.
 */
data class Device(
    val id: Long = 0L,
    /**
     * Identita' del dispositivo dentro il registro condiviso, stabile per
     * sempre. Vuota per chi e' stato registrato a mano su questo telefono prima
     * che un registro esistesse: e' da quel vuoto che parte l'adozione.
     *
     * Non e' [id] e non puo' esserlo: quello e' un autoincrement locale, e due
     * telefoni darebbero numeri diversi alle stesse prese.
     */
    val uuid: String = "",
    val name: String,
    val room: String = "",
    val kind: DeviceKind = DeviceKind.SWITCH,
    /** Topic su cui il dispositivo pubblica il proprio stato. */
    val stateTopic: String,
    /** Topic su cui l'app pubblica i comandi. Vuoto per i sensori. */
    val commandTopic: String = "",
    val payloadOn: String = "ON",
    val payloadOff: String = "OFF",
    /**
     * Se lo stato arriva come JSON, il percorso del campo da leggere
     * (es. `POWER` per Tasmota, `state` per Zigbee2MQTT). I livelli si separano
     * con il punto. Null quando il payload e gia il valore.
     */
    val stateJsonKey: String? = null,
    /**
     * Campo JSON dello stato che porta la potenza istantanea in watt. Null
     * quando il dispositivo non la misura: senza un campo da cui leggerla la
     * scheda non mostra nessun numero, invece di inventarne uno.
     */
    val powerJsonKey: String? = null,
    /**
     * Topic su cui arrivano i consumi accumulati, per chi li tiene. Sta a parte
     * dallo stato perche' e' una grandezza con un altro tempo: lo stato e una
     * fotografia dell'istante, l'energia un totale che non si azzera quando la
     * presa si spegne.
     */
    val energyTopic: String? = null,
    /** Campo JSON con i kWh di oggi, dentro il payload dell'energia. */
    val energyTodayJsonKey: String? = null,
    /** Campo JSON con i kWh del mese in corso. */
    val energyMonthJsonKey: String? = null,
    /**
     * Topic su cui il dispositivo, o chi lo rappresenta, dichiara di essere
     * raggiungibile. Vuoto quando non ce n'e uno: in quel caso l'app non sa se
     * il dispositivo sia vivo, e non finge di saperlo.
     */
    val availabilityTopic: String? = null,
    val payloadAvailable: String = "online",
    val payloadUnavailable: String = "offline",
    val levelStateTopic: String? = null,
    val levelCommandTopic: String? = null,
    val levelJsonKey: String? = null,
    /**
     * Fondo scala del livello sul dispositivo. L'interfaccia ragiona sempre in
     * percentuale; qui si dice a cosa corrisponde il 100%: 100 per Tasmota,
     * 254 per Zigbee2MQTT, 255 per parecchi firmware fatti in casa.
     */
    val levelMax: Int = 100,
    val qos: Int = 0,
    /** Comandi ritenuti dal broker. Di norma no: un comando e un evento, non uno stato. */
    val retained: Boolean = false,
    /**
     * Dove sta nell'elenco, per chi lo ha collocato. Nullo vuol dire che nessun
     * registro gli ha mai dato un posto, e quelli vanno **in fondo**, in ordine
     * di nome.
     *
     * Nullo non e' zero, e la differenza e' tutta la sicurezza di questo campo:
     * zero e' il primo posto, nullo e' l'ultimo. Un valore che non si capisce
     * diventa nullo e non zero, altrimenti un errore di battitura porterebbe un
     * dispositivo in cima alla casa.
     *
     * E' l'unico campo che non dice ne' cosa sia il dispositivo ne' come gli si
     * parli: dice come lo si guarda.
     */
    val position: Int? = null,
) {
    val controllable: Boolean get() = kind != DeviceKind.SENSOR

    val dimmable: Boolean get() = kind == DeviceKind.DIMMER

    /**
     * Le stesse orecchie: stessi topic, e stesso modo di leggere quel che arriva.
     *
     * Serve a chi deve decidere se un dispositivo cambiato vada risottoscritto.
     * E' scritto come **esclusione** e non come elenco dei campi che contano, e
     * non e' un vezzo: un campo di rete aggiunto domani entra qui da se', mentre
     * dimenticarsi di aggiungerlo a un elenco di campi buoni non darebbe nessun
     * errore e lascerebbe una scheda vuota fino al messaggio dopo.
     *
     * Fuori restano identita' e presentazione: id, uuid, nome, stanza e
     * posizione non cambiano ne' dove si ascolta ne' come si interpreta quello
     * che si sente.
     */
    fun listensLike(other: Device): Boolean = anonimo() == other.anonimo()

    private fun anonimo(): Device = copy(id = 0L, uuid = "", name = "", room = "", position = null)

    /** Topic a cui iscriversi per questo dispositivo. */
    val subscriptions: List<String>
        get() = listOfNotNull(
            stateTopic.takeIf { it.isNotBlank() },
            availabilityTopic?.takeIf { it.isNotBlank() },
            levelStateTopic?.takeIf { it.isNotBlank() },
            energyTopic?.takeIf { it.isNotBlank() },
        )
}
