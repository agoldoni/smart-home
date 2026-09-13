package it.agoldoni.smarthome.domain.registry

import it.agoldoni.smarthome.domain.model.Device
import it.agoldoni.smarthome.domain.model.DeviceKind
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Versione del formato che questo lettore conosce. Vedi `bridge/configuratore/SCHEMA.md`. */
const val REGISTRY_SCHEMA = 1

/** Il ramo sotto cui vive il registro, dentro il prefisso dell'istanza. */
fun registryTopic(prefix: String): String =
    "${prefix.trim().trim('/').ifBlank { DEFAULT_REGISTRY_PREFIX }}/registro/dispositivi"

const val DEFAULT_REGISTRY_PREFIX = "casa"

/**
 * Un registro letto e capito.
 *
 * [devices] e' l'insieme completo dichiarato da chi ha scritto: cio' che non c'e'
 * non esiste, ed e' da questa assenza che si legge una cancellazione avvenuta
 * mentre il telefono era spento.
 */
data class DeviceRegistry(
    val schema: Int,
    val revision: Int,
    val updatedAt: String?,
    val devices: List<Device>,
    /**
     * I dispositivi scartati, uno per riga, con il motivo. Non e' un dettaglio
     * da log: un dispositivo che sparisce dalla scheda senza che niente lo dica
     * e' esattamente il genere di cosa che poi si cerca per mezz'ora.
     */
    val skipped: List<String> = emptyList(),
)

/** Perche' un intero documento e' stato buttato. */
enum class RegistryRejection {
    /** Payload vuoto: il ritenuto e' stato cancellato. Non e' un errore. */
    EMPTY,
    NOT_JSON,
    MISSING_DEVICES,
    /** Scritto da qualcosa di piu' nuovo di noi. */
    SCHEMA_TOO_NEW,
}

sealed interface RegistryRead {
    data class Ok(val registry: DeviceRegistry) : RegistryRead

    data class Rejected(val reason: RegistryRejection, val detail: String) : RegistryRead
}

/**
 * Legge il documento del registro.
 *
 * Il rifiuto e' **in blocco** per la testata e **per dispositivo** per il
 * contenuto, e non e' una sfumatura: uno `schema` che non si conosce mette in
 * dubbio ogni campo del documento, mentre un `tipo` sconosciuto mette in dubbio
 * un dispositivo solo — buttare anche gli altri sei sarebbe una punizione senza
 * motivo.
 *
 * Sotto c'e' la regola che l'app applica gia' ai payload di disponibilita': da
 * un valore che non si e' capito non si deduce niente. Un registro
 * incomprensibile non e' un registro vuoto, ed e' la differenza fra un errore di
 * battitura e una casa senza dispositivi.
 */
fun readRegistry(payload: String): RegistryRead {
    val testo = payload.trim()
    if (testo.isEmpty()) {
        return RegistryRead.Rejected(RegistryRejection.EMPTY, "payload vuoto: il registro e' stato cancellato")
    }

    val radice = try {
        JSONObject(testo)
    } catch (e: JSONException) {
        return RegistryRead.Rejected(RegistryRejection.NOT_JSON, e.message ?: "payload non JSON")
    }

    val schema = radice.optInt("schema", 1)
    if (schema > REGISTRY_SCHEMA) {
        return RegistryRead.Rejected(
            RegistryRejection.SCHEMA_TOO_NEW,
            "schema $schema, questa app ne conosce $REGISTRY_SCHEMA",
        )
    }

    val elenco: JSONArray = radice.optJSONArray("dispositivi")
        ?: return RegistryRead.Rejected(RegistryRejection.MISSING_DEVICES, "manca l'elenco 'dispositivi'")

    val dispositivi = mutableListOf<Device>()
    val saltati = mutableListOf<String>()
    val visti = mutableSetOf<String>()

    for (i in 0 until elenco.length()) {
        val voce = elenco.optJSONObject(i)
        if (voce == null) {
            saltati += "voce ${i + 1}: non e' un oggetto"
            continue
        }
        when (val letto = leggiDispositivo(voce, i, visti)) {
            is LetturaVoce.Ok -> {
                visti += letto.device.uuid
                dispositivi += letto.device
            }

            is LetturaVoce.Saltato -> saltati += letto.motivo
        }
    }

    return RegistryRead.Ok(
        DeviceRegistry(
            schema = schema,
            revision = radice.optInt("revisione", 0),
            updatedAt = radice.testo("aggiornato"),
            devices = dispositivi,
            skipped = saltati,
        ),
    )
}

private sealed interface LetturaVoce {
    data class Ok(val device: Device) : LetturaVoce

    data class Saltato(val motivo: String) : LetturaVoce
}

private fun leggiDispositivo(voce: JSONObject, indice: Int, visti: Set<String>): LetturaVoce {
    val dove = "voce ${indice + 1}"

    val uuid = voce.testo("uuid")
        ?: return LetturaVoce.Saltato("$dove: manca l'uuid")
    if (uuid in visti) return LetturaVoce.Saltato("$dove ($uuid): uuid duplicato")

    val nome = voce.testo("nome")
        ?: return LetturaVoce.Saltato("$dove ($uuid): manca il nome")

    val tipoTesto = voce.testo("tipo")
        ?: return LetturaVoce.Saltato("$nome: manca il tipo")
    // Un tipo che non si conosce **salta il dispositivo** invece di ripiegare
    // su SWITCH: fra due app di versione diversa, mostrare un interruttore dove
    // il registro diceva altro e' peggio che non mostrare niente.
    val tipo = DeviceKind.entries.firstOrNull { it.name == tipoTesto }
        ?: return LetturaVoce.Saltato("$nome: tipo sconosciuto '$tipoTesto'")

    val topicStato = voce.testo("topic_stato")
        ?: return LetturaVoce.Saltato("$nome: manca il topic di stato")

    val topicComando = voce.testo("topic_comando").orEmpty()
    if (tipo != DeviceKind.SENSOR && topicComando.isEmpty()) {
        return LetturaVoce.Saltato("$nome: manca il topic di comando")
    }
    if (topicComando.hasWildcard()) {
        return LetturaVoce.Saltato("$nome: il topic di comando contiene + o #")
    }

    val topicComandoLivello = voce.testo("topic_comando_livello")
    if (tipo == DeviceKind.DIMMER && topicComandoLivello == null) {
        return LetturaVoce.Saltato("$nome: e' un dimmer senza topic per il livello")
    }
    if (topicComandoLivello?.hasWildcard() == true) {
        return LetturaVoce.Saltato("$nome: il topic del livello contiene + o #")
    }

    return LetturaVoce.Ok(
        Device(
            // id e room non vengono dal registro: il primo e' locale, il secondo
            // non e' nel documento. Chi applica il piano li conserva dal
            // dispositivo che sta gia' nel database.
            id = 0L,
            uuid = uuid,
            name = nome,
            room = "",
            kind = tipo,
            stateTopic = topicStato,
            commandTopic = if (tipo == DeviceKind.SENSOR) "" else topicComando,
            payloadOn = voce.testo("payload_on") ?: "ON",
            payloadOff = voce.testo("payload_off") ?: "OFF",
            stateJsonKey = voce.testo("campo_stato"),
            powerJsonKey = voce.testo("campo_potenza"),
            energyTopic = voce.testo("topic_energia"),
            energyTodayJsonKey = voce.testo("campo_kwh_oggi"),
            energyMonthJsonKey = voce.testo("campo_kwh_mese"),
            availabilityTopic = voce.testo("topic_disponibilita"),
            payloadAvailable = voce.testo("payload_disponibile") ?: "online",
            payloadUnavailable = voce.testo("payload_non_disponibile") ?: "offline",
            levelStateTopic = voce.testo("topic_stato_livello")?.takeIf { tipo == DeviceKind.DIMMER },
            levelCommandTopic = topicComandoLivello?.takeIf { tipo == DeviceKind.DIMMER },
            levelJsonKey = voce.testo("campo_livello")?.takeIf { tipo == DeviceKind.DIMMER },
            levelMax = voce.optInt("livello_max", 100).coerceIn(1, 65535),
            qos = voce.optInt("qos", 0).coerceIn(0, 2),
            retained = voce.optBoolean("ritenuto", false),
            position = voce.posizione(),
        ),
    )
}

/**
 * Una stringa non vuota, oppure null.
 *
 * `isNull` prima di tutto: `optString` su un `JSONObject.NULL` restituirebbe la
 * stringa "null", che finirebbe dritta dentro un topic.
 */
private fun JSONObject.testo(chiave: String): String? =
    if (isNull(chiave)) null else optString(chiave).trim().takeIf { it.isNotEmpty() }

/**
 * Il posto nell'elenco, oppure null.
 *
 * Non si ripiega mai su zero, ed e' la regola piu' importante di questo campo:
 * zero e' il **primo** posto, e un valore che non si e' capito non puo' portare
 * un dispositivo in cima alla casa. Assente, negativo, con la virgola o scritto
 * come stringa valgono tutti "nessuno lo ha collocato", che lo manda in fondo
 * insieme agli altri senza posto.
 *
 * E' la stessa regola che l'app applica ai payload di disponibilita': da un
 * valore che non si e' capito non si deduce niente.
 */
private fun JSONObject.posizione(): Int? {
    val valore = opt("posizione")
    if (valore !is Number) return null
    val intero = valore.toInt()
    // Un intero, non un numero qualsiasi: 1.5 non e' un posto in un elenco.
    if (intero.toDouble() != valore.toDouble()) return null
    return intero.takeIf { it >= 0 }
}

private fun String.hasWildcard(): Boolean = contains('+') || contains('#')

/** Nessun registro e' mai stato applicato. Non e' zero: una revisione zero e' valida. */
const val NO_REVISION = -1

/**
 * Un registro piu' vecchio, o gia' applicato, non si riapplica.
 *
 * Serve contro il riordino: i ritenuti arrivano alla sottoscrizione e una
 * riconnessione li riconsegna tutti, compreso quello che avevamo gia'.
 */
fun DeviceRegistry.isNewerThan(appliedRevision: Int): Boolean = revision > appliedRevision
