package it.agoldoni.smarthome.domain.model

/** Stato del collegamento al broker, mostrato in cima all'elenco dispositivi. */
sealed interface ConnectionState {
    /** Nessun broker configurato: non c'e niente da connettere. */
    data object NotConfigured : ConnectionState

    data object Connecting : ConnectionState

    data object Connected : ConnectionState

    data object Disconnected : ConnectionState

    data class Failed(val reason: String) : ConnectionState
}
