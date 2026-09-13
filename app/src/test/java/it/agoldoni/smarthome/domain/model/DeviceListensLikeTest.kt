package it.agoldoni.smarthome.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quando due versioni dello stesso dispositivo si ascoltano allo stesso modo.
 *
 * E' la regola che tiene ferme le sottoscrizioni durante un riordino: il driver
 * rifa' le iscrizioni dei dispositivi "cambiati", e senza questa distinzione un
 * trascinamento — che cambia un numero su ogni riga — le rifarebbe tutte, con la
 * raffica di ritenuti che ne segue.
 *
 * Il predicato e' scritto per **esclusione**, quindi la meta' importante di
 * questi test e' quella che verifica che i campi di rete continuino a contare:
 * un'esclusione scritta troppo larga non da' nessun errore, lascia solo schede
 * che non si aggiornano piu'.
 */
class DeviceListensLikeTest {

    private val boiler = Device(
        id = 7L,
        uuid = "a",
        name = "boiler",
        stateTopic = "casa/boiler/stato",
        commandTopic = "casa/boiler/comando",
        stateJsonKey = "stato",
        availabilityTopic = "casa/boiler/disponibilita",
        energyTopic = "casa/boiler/energia",
        position = 0,
    )

    // -- quello che non cambia le orecchie -----------------------------------

    @Test
    fun `spostato nell'elenco si ascolta allo stesso modo`() {
        assertTrue(boiler.listensLike(boiler.copy(position = 6)))
    }

    @Test
    fun `tolto dall'elenco ordinato si ascolta allo stesso modo`() {
        assertTrue(boiler.listensLike(boiler.copy(position = null)))
    }

    @Test
    fun `rinominato si ascolta allo stesso modo`() {
        // Falso positivo che c'era gia' prima di questa feature: correggere un
        // refuso in un nome rifaceva le sottoscrizioni di quel dispositivo.
        assertTrue(boiler.listensLike(boiler.copy(name = "scaldabagno")))
    }

    @Test
    fun `stanza, id e uuid non cambiano niente`() {
        assertTrue(boiler.listensLike(boiler.copy(room = "bagno")))
        assertTrue(boiler.listensLike(boiler.copy(id = 99L)))
        // L'adozione da' un uuid a un dispositivo registrato a mano: i topic
        // restano quelli, non c'e' niente da risottoscrivere.
        assertTrue(boiler.listensLike(boiler.copy(uuid = "b")))
    }

    @Test
    fun `identico a se stesso`() {
        assertTrue(boiler.listensLike(boiler))
    }

    // -- quello che le cambia ------------------------------------------------

    @Test
    fun `un topic diverso non si ascolta allo stesso modo`() {
        assertFalse(boiler.listensLike(boiler.copy(stateTopic = "casa/altro/stato")))
        assertFalse(boiler.listensLike(boiler.copy(availabilityTopic = null)))
        assertFalse(boiler.listensLike(boiler.copy(energyTopic = "casa/boiler/kwh")))
        assertFalse(boiler.listensLike(boiler.copy(levelStateTopic = "casa/boiler/livello")))
    }

    @Test
    fun `una chiave JSON diversa non si ascolta allo stesso modo`() {
        // I topic sono gli stessi, ma il ritenuto va riletto con l'occhio nuovo:
        // e' esattamente il caso per cui la risottoscrizione esiste.
        assertFalse(boiler.listensLike(boiler.copy(stateJsonKey = "POWER")))
        assertFalse(boiler.listensLike(boiler.copy(powerJsonKey = "potenza_w")))
        assertFalse(boiler.listensLike(boiler.copy(energyTodayJsonKey = "kwh_oggi")))
    }

    @Test
    fun `un payload diverso non si ascolta allo stesso modo`() {
        assertFalse(boiler.listensLike(boiler.copy(payloadOn = "1")))
        assertFalse(boiler.listensLike(boiler.copy(payloadUnavailable = "lost")))
    }

    @Test
    fun `il fondo scala del livello non si ascolta allo stesso modo`() {
        // 100 o 254 cambia il numero che si legge dallo stesso payload.
        assertFalse(boiler.listensLike(boiler.copy(levelMax = 254)))
    }

    @Test
    fun `il tipo non si ascolta allo stesso modo`() {
        assertFalse(boiler.listensLike(boiler.copy(kind = DeviceKind.DIMMER)))
    }
}
