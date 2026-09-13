package it.agoldoni.smarthome.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import it.agoldoni.smarthome.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.brokerDataStore: DataStore<Preferences> by preferencesDataStore(name = "broker")

/**
 * Persistenza delle impostazioni del broker.
 *
 * La password finisce in chiaro nel DataStore. E lo stesso livello di protezione
 * che le danno le altre app del genere (i file dell'app sono leggibili solo
 * dall'app stessa, salvo root), ma per questo il manifest disattiva il backup:
 * altrimenti uscirebbe dal dispositivo.
 */
class BrokerSettingsStore(private val context: Context) {

    /**
     * Le impostazioni salvate, o i valori predefiniti della build.
     *
     * Il `?:` guarda se la **chiave esiste**, non se e' vuota, e la differenza e'
     * tutto: [save] scrive sempre tutti i campi, quindi appena qualcuno tocca le
     * impostazioni i predefiniti spariscono per sempre — anche se ha cancellato
     * l'indirizzo apposta. Valgono solo su un'installazione che non e' mai stata
     * configurata: nella build debug la mandano dritta allo stack di sviluppo,
     * nella release sono stringhe vuote e non cambiano niente.
     */
    val settings: Flow<BrokerSettings> = context.brokerDataStore.data.map { prefs ->
        BrokerSettings(
            host = prefs[KEY_HOST] ?: BuildConfig.DEV_BROKER_HOST,
            port = prefs[KEY_PORT] ?: BuildConfig.DEV_BROKER_PORT.toIntOrNull() ?: DEFAULT_PORT,
            useTls = prefs[KEY_TLS] ?: false,
            username = prefs[KEY_USERNAME] ?: BuildConfig.DEV_BROKER_USER,
            password = prefs[KEY_PASSWORD] ?: BuildConfig.DEV_BROKER_PASS,
            clientId = prefs[KEY_CLIENT_ID].orEmpty(),
        )
    }

    suspend fun save(settings: BrokerSettings) {
        context.brokerDataStore.edit { prefs ->
            prefs[KEY_HOST] = settings.host.trim()
            prefs[KEY_PORT] = settings.port
            prefs[KEY_TLS] = settings.useTls
            prefs[KEY_USERNAME] = settings.username.trim()
            prefs[KEY_PASSWORD] = settings.password
            prefs[KEY_CLIENT_ID] = settings.clientId.trim()
        }
    }

    private companion object {
        const val DEFAULT_PORT = 1883
        val KEY_HOST = stringPreferencesKey("host")
        val KEY_PORT = intPreferencesKey("port")
        val KEY_TLS = booleanPreferencesKey("tls")
        val KEY_USERNAME = stringPreferencesKey("username")
        val KEY_PASSWORD = stringPreferencesKey("password")
        val KEY_CLIENT_ID = stringPreferencesKey("client_id")
    }
}
