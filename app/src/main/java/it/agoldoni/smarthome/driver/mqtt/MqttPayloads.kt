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
 * Il valore di un campo numerico dentro un payload che e una **fotografia**.
 *
 * Tre esiti, e servono tutti e tre:
 *
 * - **il numero**, quando la chiave c'e e dentro c'e un numero
 * - **null**, quando il payload e un oggetto JSON che quella chiave non ce l'ha,
 *   o ce l'ha a `null`: vuol dire che quel valore non lo sa nessuno
 * - **[previous]**, quando non c'e niente da concludere: il campo non e
 *   dichiarato, il payload non e un oggetto JSON, oppure la chiave c'e ma dentro
 *   non c'e un numero
 *
 * La differenza fra il secondo e il terzo caso e tutto il senso di questa
 * funzione, e non e' un cavillo. Il topic dei consumi lo pubblica un produttore
 * solo, per intero e ritenuto: se `kwh_ieri` non c'e, e perche nessuno sa quanto
 * si sia consumato ieri — tenere l'ultimo numero letto mostrerebbe per sempre un
 * giorno sbagliato, e nessuno andrebbe a verificarlo. Ma un payload che non si e
 * capito non e una fotografia, e da quello non si deduce niente: e la stessa
 * regola che vale per i payload di disponibilita.
 *
 * Non va usata sui payload dello stato, che arrivano anche **parziali** — col
 * solo campo che e cambiato — e dove una chiave assente non significa niente.
 */
internal fun readSnapshotNumber(payload: String, path: String?, previous: Double?): Double? {
    val key = path?.takeIf { it.isNotBlank() } ?: return previous
    val parent = jsonParent(payload, key) ?: return previous
    val leaf = key.substringAfterLast('.')
    val value = parent.opt(leaf)
    if (value == null || value == JSONObject.NULL) return null
    return value.toString().trim().toDoubleOrNull() ?: previous
}

/** L'oggetto che contiene l'ultimo segmento del percorso, o null se non ci si arriva. */
private fun jsonParent(payload: String, path: String): JSONObject? {
    val trimmed = payload.trim()
    if (!trimmed.startsWith("{")) return null
    return try {
        var node = JSONObject(trimmed)
        path.split('.').dropLast(1).forEach { key ->
            node = node.optJSONObject(key) ?: return null
        }
        node
    } catch (e: JSONException) {
        null
    }
}

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
