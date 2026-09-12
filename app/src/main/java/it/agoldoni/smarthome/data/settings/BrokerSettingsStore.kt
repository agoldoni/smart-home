package it.agoldoni.smarthome.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    val settings: Flow<BrokerSettings> = context.brokerDataStore.data.map { prefs ->
        BrokerSettings(
            host = prefs[KEY_HOST].orEmpty(),
            port = prefs[KEY_PORT] ?: DEFAULT_PORT,
            useTls = prefs[KEY_TLS] ?: false,
            username = prefs[KEY_USERNAME].orEmpty(),
            password = prefs[KEY_PASSWORD].orEmpty(),
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
