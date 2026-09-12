package it.agoldoni.smarthome.driver.mqtt

import org.json.JSONException
import org.json.JSONObject

/** Confronto fra un filtro di sottoscrizione e un topic, secondo le regole MQTT. */
internal object MqttTopics {

    fun matches(filter: String, topic: String): Boolean {
        val levels = filter.split('/')
        val actual = topic.split('/')
        var i = 0
        while (i < levels.size) {
            when {
                // `#` vale per tutto il resto, ma solo se e l'ultimo livello.
                levels[i] == "#" -> return i == levels.lastIndex
                i >= actual.size -> return false
                levels[i] == "+" -> Unit
                levels[i] != actual[i] -> return false
            }
            i++
        }
        return i == actual.size
    }
}

/**
 * Legge un campo da un payload JSON piatto o annidato (`stato.livello`).
 * Restituisce null se il payload non e JSON o il percorso non esiste: chi chiama
 * ripiega sul payload grezzo.
 */
internal fun extractJson(payload: String, path: String): String? {
    val trimmed = payload.trim()
    if (!trimmed.startsWith("{")) return null
    return try {
        var node = JSONObject(trimmed)
        val keys = path.split('.')
        keys.forEachIndexed { index, key ->
            if (index == keys.lastIndex) {
                val value = node.opt(key) ?: return null
                return if (value == JSONObject.NULL) null else value.toString()
            }
            node = node.optJSONObject(key) ?: return null
        }
        null
    } catch (e: JSONException) {
        null
    }
}

/**
 * Legge un numero da un campo del payload, per le misure che accompagnano lo
 * stato (la potenza istantanea di una presa).
 *
 * Null quando il campo non c'e o non e un numero: un valore che non si e capito
 * non deve cancellare l'ultimo buono, e nemmeno diventare uno zero che si
 * leggerebbe come "non assorbe niente".
 */
internal fun readNumber(payload: String, path: String): Double? =
    extractJson(payload, path)?.trim()?.toDoubleOrNull()

/**
 * Decide se un payload di disponibilita dica "ci sono" o "non ci sono".
 *
 * Restituisce null quando non dice ne l'una ne l'altra cosa: da un valore che
 * non si e capito non si deduce che il dispositivo sia sparito, altrimenti un
 * payload inatteso spegnerebbe l'intera scheda.
 *
 * Accetta anche la forma JSON `{"state":"online"}`, che e come pubblica
 * Zigbee2MQTT: senza, chi usa quello resterebbe per sempre senza risposta.
 */
internal fun readAvailability(payload: String, available: String, unavailable: String): Boolean? {
    val value = (extractJson(payload, "state") ?: payload).trim()
    return when {
        value.equals(available, ignoreCase = true) -> true
        value.equals(unavailable, ignoreCase = true) -> false
        else -> null
    }
}
