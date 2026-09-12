package it.agoldoni.smarthome.diagnostics

/** Come sta il servizio di debug, per la riga nelle impostazioni. */
data class DebugStatus(
    /** L'interruttore e su acceso. */
    val enabled: Boolean = false,
    /** Le porte sono davvero aperte: puo essere falso con [enabled] vero, se il bind e fallito. */
    val running: Boolean = false,
    /** Indirizzo da aprire dal PC, gia completo di porta. */
    val endpoint: String? = null,
    val error: String? = null,
)
