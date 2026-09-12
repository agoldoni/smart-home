package it.agoldoni.smarthome.data.settings

/** Coordinate del broker MQTT. */
data class BrokerSettings(
    val host: String = "",
    val port: Int = 1883,
    val useTls: Boolean = false,
    val username: String = "",
    val password: String = "",
    /** Vuoto: ne viene generato uno stabile per installazione. */
    val clientId: String = "",
) {
    val configured: Boolean get() = host.isNotBlank()

    val serverUri: String get() = "${if (useTls) "ssl" else "tcp"}://$host:$port"
}
