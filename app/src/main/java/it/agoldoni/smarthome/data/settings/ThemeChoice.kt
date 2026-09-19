package it.agoldoni.smarthome.data.settings

/**
 * Il tema dell'app, come lo sceglie chi la usa.
 *
 * Sta qui accanto a [BrokerSettings] e non dentro `ui/theme`: le tre voci sono
 * un dato salvato, e il verso delle dipendenze e' quello di tutto il resto
 * dell'app — l'interfaccia legge le impostazioni, non il contrario.
 *
 * Non importa niente di Compose ne' di Android, ed e' voluto: [scuro] e' l'unica
 * cosa di questa feature che si possa provare per casi, e i test di questo
 * progetto girano sulla JVM, senza Robolectric ne' androidTest. Stessa ragione e
 * stessa forma di `ui/devices/EnergiaCompatta.kt`.
 */
enum class ThemeChoice {
    SISTEMA,
    CHIARO,
    SCURO;

    /**
     * Siamo in scuro?
     *
     * L'unico punto in cui la scelta diventa un booleano, e ha due chiamanti
     * veri: la schermata, che il tema di sistema lo sa da Compose, e l'API di
     * debug, che gira fuori dalla composizione e se lo legge dalla
     * configurazione. Non e' una funzione nata per i test.
     */
    fun scuro(sistemaScuro: Boolean): Boolean = when (this) {
        SISTEMA -> sistemaScuro
        CHIARO -> false
        SCURO -> true
    }

    companion object {

        /**
         * La scelta salvata, o [SISTEMA].
         *
         * Tollerante di proposito. `valueOf` solleverebbe su un valore che non
         * conosce, e il valore che non conosce e' un caso reale: una versione
         * futura scrive una quarta voce, si reinstalla sopra l'APK di oggi, e
         * l'eccezione salterebbe **dentro il flusso che disegna l'interfaccia**.
         * Meglio tornare al predefinito, che e' anche il comportamento di prima
         * che la scelta esistesse.
         */
        fun da(salvato: String?): ThemeChoice =
            entries.firstOrNull { it.name == salvato } ?: SISTEMA
    }
}
