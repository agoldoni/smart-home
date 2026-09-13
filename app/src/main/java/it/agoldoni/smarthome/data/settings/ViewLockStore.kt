package it.agoldoni.smarthome.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Un terzo file di preferenze, e per la stessa ragione del secondo.
 *
 * Il lucchetto non puo' stare in [BrokerSettingsStore]: il driver osserva quel
 * flusso e a ogni valore diverso chiude e riapre il collegamento. E' una
 * `data class`, quindi basterebbe questo campo in piu' perche'
 * `distinctUntilChanged` lasci passare — e ogni tocco del lucchetto farebbe
 * cadere il broker. Non puo' stare nemmeno in [RegistryStore], che pure
 * andrebbe tecnicamente bene: li' dentro ci sono le cose del registro
 * condiviso, e questa e' una preferenza della vista di questo telefono.
 */
private val Context.viewDataStore: DataStore<Preferences> by preferencesDataStore(name = "vista")

class ViewLockStore(private val context: Context) {

    /**
     * La vista principale e' bloccata.
     *
     * Predefinito **aperto**: chi aggiorna dalla 1.1.0 deve ritrovare l'app che
     * aveva, non una che non risponde piu' ai comandi senza spiegare perche'.
     */
    val locked: Flow<Boolean> = context.viewDataStore.data.map { prefs ->
        prefs[KEY_LOCKED] ?: false
    }

    suspend fun setLocked(locked: Boolean) = context.viewDataStore.edit { prefs ->
        prefs[KEY_LOCKED] = locked
    }

    private companion object {
        val KEY_LOCKED = booleanPreferencesKey("bloccata")
    }
}
