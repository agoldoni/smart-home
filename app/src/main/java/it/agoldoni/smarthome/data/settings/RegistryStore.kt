package it.agoldoni.smarthome.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import it.agoldoni.smarthome.BuildConfig
import it.agoldoni.smarthome.domain.registry.DEFAULT_REGISTRY_PREFIX
import it.agoldoni.smarthome.domain.registry.NO_REVISION
import it.agoldoni.smarthome.domain.registry.registryTopic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Un file di preferenze suo, e non quello del broker.
 *
 * Non e' una scelta di ordine. Il driver osserva [BrokerSettingsStore] e a ogni
 * valore diverso chiude e riapre il collegamento: e' un `data class`, quindi
 * bastera' un campo in piu' che cambia perche' `distinctUntilChanged` lasci
 * passare. La revisione del registro cambia a ogni salvataggio fatto sul
 * configuratore — l'app si riconnetterebbe mentre qualcuno la sta configurando.
 */
private val Context.registryDataStore: DataStore<Preferences> by preferencesDataStore(name = "registro")

data class RegistrySettings(
    /** Il ramo dell'istanza. Cambiarlo sposta insieme dispositivi e registro. */
    val prefix: String = DEFAULT_REGISTRY_PREFIX,
    /** L'interruttore in Impostazioni. Spento, l'app torna a gestirsi i dispositivi da sola. */
    val follow: Boolean = true,
    /** L'ultima revisione applicata. [NO_REVISION] = nessun registro e' mai arrivato. */
    val appliedRevision: Int = NO_REVISION,
    val receivedAt: Long = 0L,
    /**
     * La prima applicazione e' stata confermata.
     *
     * Serve una volta sola nella vita di un'installazione: dopo, il registro si
     * applica da solo. Prima, se c'e' qualcosa da rimuovere, si chiede.
     */
    val bootstrapped: Boolean = false,
) {
    val topic: String get() = registryTopic(prefix)

    /** Segue davvero: l'interruttore e' acceso e un registro e' gia' arrivato. */
    val following: Boolean get() = follow && appliedRevision != NO_REVISION
}

class RegistryStore(private val context: Context) {

    val settings: Flow<RegistrySettings> = context.registryDataStore.data.map { prefs ->
        RegistrySettings(
            // Come per il broker: il predefinito della build vale solo finche'
            // nessuno ha salvato niente su questo telefono. Nella debug e' `dev`,
            // nella release e' vuoto e si ricade su `casa`.
            prefix = prefs[KEY_PREFIX]
                ?: BuildConfig.DEV_REGISTRY_PREFIX.ifBlank { DEFAULT_REGISTRY_PREFIX },
            follow = prefs[KEY_FOLLOW] ?: true,
            appliedRevision = prefs[KEY_REVISION] ?: NO_REVISION,
            receivedAt = prefs[KEY_RECEIVED_AT] ?: 0L,
            bootstrapped = prefs[KEY_BOOTSTRAPPED] ?: false,
        )
    }

    suspend fun setPrefix(prefix: String) = context.registryDataStore.edit { prefs ->
        prefs[KEY_PREFIX] = prefix.trim().trim('/').ifBlank { DEFAULT_REGISTRY_PREFIX }
    }

    suspend fun setFollow(follow: Boolean) = context.registryDataStore.edit { prefs ->
        prefs[KEY_FOLLOW] = follow
    }

    /** Un registro e' stato applicato. */
    suspend fun record(revision: Int, at: Long) = context.registryDataStore.edit { prefs ->
        prefs[KEY_REVISION] = revision
        prefs[KEY_RECEIVED_AT] = at
        prefs[KEY_BOOTSTRAPPED] = true
    }

    /**
     * Il registro ritenuto e' stato cancellato.
     *
     * Si smette di seguire e si tiene quello che si ha. La lettura opposta —
     * registro cancellato uguale casa senza dispositivi — farebbe di un comando
     * solo un disastro, e un ritenuto si cancella con un comando solo.
     */
    suspend fun forget() = context.registryDataStore.edit { prefs ->
        prefs[KEY_REVISION] = NO_REVISION
        prefs[KEY_RECEIVED_AT] = 0L
    }

    private companion object {
        val KEY_PREFIX = stringPreferencesKey("prefisso")
        val KEY_FOLLOW = booleanPreferencesKey("segui")
        val KEY_REVISION = intPreferencesKey("revisione")
        val KEY_RECEIVED_AT = longPreferencesKey("ricevuto_il")
        val KEY_BOOTSTRAPPED = booleanPreferencesKey("prima_applicazione")
    }
}
