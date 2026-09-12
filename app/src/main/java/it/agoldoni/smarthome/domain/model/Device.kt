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
) {
    val controllable: Boolean get() = kind != DeviceKind.SENSOR

    val dimmable: Boolean get() = kind == DeviceKind.DIMMER

    /** Topic a cui iscriversi per questo dispositivo. */
    val subscriptions: List<String>
        get() = listOfNotNull(
            stateTopic.takeIf { it.isNotBlank() },
            availabilityTopic?.takeIf { it.isNotBlank() },
            levelStateTopic?.takeIf { it.isNotBlank() },
        )
}
