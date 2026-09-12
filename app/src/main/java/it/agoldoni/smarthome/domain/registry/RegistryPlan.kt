package it.agoldoni.smarthome.domain.registry

import it.agoldoni.smarthome.domain.model.Device

/**
 * Cosa fare al database per farlo somigliare al registro.
 *
 * Si ragiona per **uuid e non per id**: l'id e' l'autoincrement locale, diverso
 * su ogni telefono, e usarlo come identita' condivisa non funzionerebbe. Ma
 * soprattutto il piano **aggiorna in loco invece di cancellare e reinserire**, e
 * qui non c'e' niente di stilistico: lo stato osservato dei dispositivi e' una
 * mappa con chiave l'id, e il driver butta via gli stati la cui chiave non e'
 * piu' fra i dispositivi vivi. Reinserire vorrebbe dire id nuovi, quindi tutte
 * le schede di casa che tornano a "in attesa di dati" a ogni virgola cambiata in
 * un nome — e tutte le sottoscrizioni rifatte insieme.
 */
data class RegistryPlan(
    /** Dispositivi che nel database non ci sono. Hanno id 0. */
    val inserted: List<Device> = emptyList(),
    /** Dispositivi gia' presenti e davvero cambiati, con l'id locale conservato. */
    val updated: List<Device> = emptyList(),
    /** Id locali dei dispositivi che il registro non nomina piu'. */
    val deletedIds: List<Long> = emptyList(),
    /**
     * Id locali riconosciuti dal topic di stato e non dall'uuid: gia' registrati
     * a mano su questo telefono prima che esistesse un registro.
     */
    val adoptedIds: List<Long> = emptyList(),
) {
    /** Niente da fare. Il caso piu' frequente, e quello che non deve costare niente. */
    val isEmpty: Boolean
        get() = inserted.isEmpty() && updated.isEmpty() && deletedIds.isEmpty()

    val touched: Int get() = inserted.size + updated.size + deletedIds.size
}

/**
 * Confronta il registro con quello che c'e' nel database.
 *
 * L'**adozione** e' il passaggio che rende sopportabile la prima
 * sincronizzazione: un dispositivo registrato a mano prima che esistesse un
 * registro non ha uuid, e senza questo verrebbe cancellato e riaggiunto — con
 * id nuovo, quindi con la scheda azzerata. Riconoscerlo dal topic di stato gli
 * fa prendere l'uuid del registro restando lo stesso oggetto.
 *
 * Quando due dispositivi locali senza uuid hanno lo stesso topic di stato ne
 * viene adottato uno solo, e in modo deterministico: quello con l'id piu' basso,
 * cioe' il primo che era stato registrato.
 */
fun planRegistry(registry: DeviceRegistry, local: List<Device>): RegistryPlan {
    val perUuid = local.filter { it.uuid.isNotBlank() }.associateBy { it.uuid }
    val adottabili = local.filter { it.uuid.isBlank() && it.stateTopic.isNotBlank() }
        .sortedBy { it.id }
        .groupBy { it.stateTopic }
        .mapValues { (_, candidati) -> candidati.first() }

    val inseriti = mutableListOf<Device>()
    val aggiornati = mutableListOf<Device>()
    val adottati = mutableListOf<Long>()
    val consumati = mutableSetOf<Long>()

    registry.devices.forEach { dalRegistro ->
        val perIdentita = perUuid[dalRegistro.uuid]?.takeIf { it.id !in consumati }
        val perTopic = perIdentita ?: adottabili[dalRegistro.stateTopic]?.takeIf { it.id !in consumati }

        if (perTopic == null) {
            inseriti += dalRegistro
            return@forEach
        }

        consumati += perTopic.id
        if (perIdentita == null) adottati += perTopic.id

        // room non viaggia nel registro: resta quello che c'e' in locale.
        val fuso = dalRegistro.copy(id = perTopic.id, room = perTopic.room)
        if (fuso != perTopic) aggiornati += fuso
    }

    val cancellati = local.filter { it.id !in consumati }.map { it.id }

    return RegistryPlan(
        inserted = inseriti,
        updated = aggiornati,
        deletedIds = cancellati,
        adoptedIds = adottati,
    )
}
