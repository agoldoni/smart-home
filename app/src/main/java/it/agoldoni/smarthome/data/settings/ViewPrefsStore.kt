package it.agoldoni.smarthome.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
 *
 * Dalla 1.5.0 gli inquilini sono due: il lucchetto e il tema. Sono cose diverse
 * ma della stessa categoria — decisioni prese su **questo** telefono, che non
 * viaggiano nel registro e non interessano a nessun altro — ed e' per questo che
 * il tema non ha aperto un quarto file.
 */
private val Context.viewDataStore: DataStore<Preferences> by preferencesDataStore(name = "vista")

class ViewPrefsStore(private val context: Context) {

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

    /**
     * Il tema scelto, o [ThemeChoice.SISTEMA].
     *
     * Predefinito **come il sistema**, cioe' quello che l'app ha sempre fatto:
     * chi aggiorna e non tocca niente non deve accorgersi che la scelta esiste.
     */
    val theme: Flow<ThemeChoice> = context.viewDataStore.data.map { prefs ->
        ThemeChoice.da(prefs[KEY_THEME])
    }

    suspend fun setTheme(choice: ThemeChoice) = context.viewDataStore.edit { prefs ->
        prefs[KEY_THEME] = choice.name
    }

    private companion object {
        val KEY_LOCKED = booleanPreferencesKey("bloccata")

        // Il nome e non l'ordinale: un indice cambia significato se domani si
        // infila una voce in mezzo, e questo dato sopravvive agli aggiornamenti.
        val KEY_THEME = stringPreferencesKey("tema")
    }
}
