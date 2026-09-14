package it.agoldoni.smarthome.driver.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import it.agoldoni.smarthome.domain.model.Device
import it.agoldoni.smarthome.domain.model.DeviceState

/**
 * Le due funzioni pure del driver. Sono anche le piu facili da sbagliare: un
 * topic che non combacia si manifesta come un dispositivo perennemente "in
 * attesa di dati", senza nessun errore visibile.
 */
class MqttTopicsTest {

    @Test
    fun `topic identico combacia`() {
        assertTrue(MqttTopics.matches("stat/luce/POWER", "stat/luce/POWER"))
    }

    @Test
    fun `piu combacia con un livello solo`() {
        assertTrue(MqttTopics.matches("stat/+/POWER", "stat/luce/POWER"))
        assertFalse(MqttTopics.matches("stat/+/POWER", "stat/piano/luce/POWER"))
    }

    @Test
    fun `cancelletto copre il resto compreso il livello padre`() {
        assertTrue(MqttTopics.matches("stat/#", "stat/luce/POWER"))
        assertTrue(MqttTopics.matches("stat/#", "stat"))
        assertFalse(MqttTopics.matches("stat/#", "tele/luce"))
    }

    @Test
    fun `un topic piu corto del filtro non combacia`() {
        assertFalse(MqttTopics.matches("stat/luce/POWER", "stat/luce"))
        assertFalse(MqttTopics.matches("stat/luce", "stat/luce/POWER"))
    }
}

class ExtractJsonTest {

    @Test
    fun `legge un campo di primo livello`() {
        assertEquals("ON", extractJson("""{"POWER":"ON"}""", "POWER"))
    }

    @Test
    fun `legge un campo annidato`() {
        assertEquals("42", extractJson("""{"stato":{"livello":42}}""", "stato.livello"))
    }

    @Test
    fun `payload non JSON restituisce null`() {
        assertNull(extractJson("ON", "POWER"))
    }

    @Test
    fun `campo assente restituisce null`() {
        assertNull(extractJson("""{"POWER":"ON"}""", "DIMMER"))
        assertNull(extractJson("""{"POWER":"ON"}""", "stato.livello"))
    }
}

/**
 * La disponibilita e la terza cosa facile da sbagliare in silenzio: un payload
 * letto male non da nessun errore, spegne solo una scheda che invece e viva, o
 * peggio ne lascia accesa una che non c'e piu.
 */
class ReadAvailabilityTest {

    @Test
    fun `riconosce i due payload attesi`() {
        assertEquals(true, readAvailability("online", "online", "offline"))
        assertEquals(false, readAvailability("offline", "online", "offline"))
    }

    @Test
    fun `ignora maiuscole e spazi, che Tasmota usa a modo suo`() {
        assertEquals(true, readAvailability(" Online\n", "online", "offline"))
        assertEquals(false, readAvailability("OFFLINE", "online", "offline"))
    }

    @Test
    fun `legge la forma JSON di Zigbee2MQTT`() {
        assertEquals(true, readAvailability("""{"state":"online"}""", "online", "offline"))
        assertEquals(false, readAvailability("""{"state":"offline"}""", "online", "offline"))
    }

    @Test
    fun `un payload incomprensibile non significa assente`() {
        // Il punto di tutta la funzione: davanti a qualcosa che non si capisce
        // si resta su quel che si sapeva, non si dichiara sparito il dispositivo.
        assertNull(readAvailability("boh", "online", "offline"))
        assertNull(readAvailability("", "online", "offline"))
        assertNull(readAvailability("""{"altro":"online"}""", "online", "offline"))
    }

    @Test
    fun `rispetta i payload scelti dall'utente`() {
        assertEquals(true, readAvailability("1", "1", "0"))
        assertEquals(false, readAvailability("0", "1", "0"))
        assertNull(readAvailability("online", "1", "0"))
    }
}

/**
 * Un topic dichiarato ma non sottoscritto e' il modo piu' silenzioso di non
 * funzionare: nessun errore, solo un dispositivo che non dice mai di esserci.
 */
class SubscriptionsTest {

    @Test
    fun `il topic di disponibilita finisce fra le sottoscrizioni`() {
        val device = Device(
            name = "pompa",
            stateTopic = "casa/pompa/stato",
            availabilityTopic = "casa/pompa/disponibilita",
        )
        assertTrue(device.subscriptions.contains("casa/pompa/disponibilita"))
    }

    @Test
    fun `senza topic di disponibilita non si sottoscrive niente in piu`() {
        val device = Device(name = "pompa", stateTopic = "casa/pompa/stato")
        assertEquals(listOf("casa/pompa/stato"), device.subscriptions)
    }

    @Test
    fun `il topic dei consumi si sottoscrive come gli altri`() {
        val device = Device(
            name = "boiler",
            stateTopic = "casa/boiler/stato",
            energyTopic = "casa/boiler/energia",
        )
        assertTrue(device.subscriptions.contains("casa/boiler/energia"))
    }

    @Test
    fun `senza topic dei consumi non si sottoscrive niente in piu`() {
        val device = Device(name = "boiler", stateTopic = "casa/boiler/stato")
        assertEquals(listOf("casa/boiler/stato"), device.subscriptions)
    }

    @Test
    fun `non conoscere la raggiungibilita non significa irraggiungibile`() {
        assertFalse(DeviceState().unreachable)
        assertFalse(DeviceState(reachable = true).unreachable)
        assertTrue(DeviceState(reachable = false).unreachable)
    }
}

/**
 * La potenza istantanea: un numero che si legge accanto allo stato, non dentro.
 * Zero e null qui non si equivalgono, e confonderli si vedrebbe sulla scheda —
 * una presa che dice "0 W" sta lavorando ma non assorbe, una che non dice
 * niente non deve mostrare nessun numero.
 */
class ReadNumberTest {

    @Test
    fun `legge un numero con la virgola`() {
        assertEquals(35.2, readNumber("""{"stato":"ON","potenza_w":35.2}""", "potenza_w")!!, 0.001)
    }

    @Test
    fun `legge uno zero, che e un valore come un altro`() {
        assertEquals(0.0, readNumber("""{"stato":"ON","potenza_w":0}""", "potenza_w")!!, 0.001)
    }

    @Test
    fun `campo assente o non numerico non produce un valore`() {
        assertNull(readNumber("""{"stato":"ON"}""", "potenza_w"))
        assertNull(readNumber("""{"potenza_w":"parecchia"}""", "potenza_w"))
        assertNull(readNumber("ON", "potenza_w"))
    }

    @Test
    fun `legge anche un campo annidato`() {
        assertEquals(233.4, readNumber("""{"misure":{"potenza_w":233.4}}""", "misure.potenza_w")!!, 0.001)
    }
}

/**
 * Il payload dei consumi, che e una **fotografia** e non una toppa.
 *
 * La differenza con [readNumber] e tutta in cosa voglia dire una chiave che non
 * c'e. Sullo stato non vuol dire niente — le prese mandano anche aggiornamenti
 * parziali, col solo campo cambiato — ma il topic dell'energia lo pubblica uno
 * solo, per intero e ritenuto: `kwh_ieri` che manca vuol dire che ieri non lo sa
 * nessuno, e tenere l'ultimo letto lascerebbe sulla scheda un giorno vecchio per
 * sempre, senza che niente lo dica.
 *
 * Resta fuori il payload incomprensibile, che non cancella niente: da un valore
 * che non si e capito non si deduce nulla, come per la disponibilita.
 */
class ReadSnapshotNumberTest {

    private val completo = """{"kwh_oggi":0.42,"kwh_ieri":1.87,"kwh_mese":12.7}"""

    @Test
    fun `legge il valore quando c'e`() {
        assertEquals(1.87, readSnapshotNumber(completo, "kwh_ieri", null)!!, 0.001)
    }

    @Test
    fun `il valore nuovo sostituisce quello vecchio`() {
        assertEquals(1.87, readSnapshotNumber(completo, "kwh_ieri", 99.0)!!, 0.001)
    }

    @Test
    fun `legge lo zero, che e un valore come un altro`() {
        assertEquals(0.0, readSnapshotNumber("""{"kwh_ieri":0}""", "kwh_ieri", 5.0)!!, 0.001)
    }

    @Test
    fun `una chiave che non c'e riporta il valore a non saputo`() {
        // E' la meta' che fa funzionare il trattino sulla scheda: il ponte omette
        // kwh_ieri quando l'archivio non ha righe di ieri, e quell'assenza deve
        // arrivare fino alla casella.
        assertNull(readSnapshotNumber("""{"kwh_oggi":0.42}""", "kwh_ieri", 1.87))
    }

    @Test
    fun `una chiave a null vale come una chiave che non c'e`() {
        assertNull(readSnapshotNumber("""{"kwh_ieri":null}""", "kwh_ieri", 1.87))
    }

    @Test
    fun `una chiave con dentro qualcosa che non e un numero non cancella niente`() {
        assertEquals(1.87, readSnapshotNumber("""{"kwh_ieri":"boh"}""", "kwh_ieri", 1.87)!!, 0.001)
    }

    @Test
    fun `un payload che non e JSON non cancella niente`() {
        assertEquals(1.87, readSnapshotNumber("qualcosa e andato storto", "kwh_ieri", 1.87)!!, 0.001)
        assertEquals(1.87, readSnapshotNumber("", "kwh_ieri", 1.87)!!, 0.001)
    }

    @Test
    fun `un campo non dichiarato non tocca il valore`() {
        // Nessuno ha mai detto dove leggere: non e questo payload a cambiarlo.
        assertEquals(1.87, readSnapshotNumber(completo, null, 1.87)!!, 0.001)
        assertEquals(1.87, readSnapshotNumber(completo, "  ", 1.87)!!, 0.001)
    }

    @Test
    fun `legge un campo annidato, e ne sente anche l'assenza`() {
        val annidato = """{"consumi":{"kwh_ieri":1.87}}"""
        assertEquals(1.87, readSnapshotNumber(annidato, "consumi.kwh_ieri", null)!!, 0.001)
        assertNull(readSnapshotNumber(annidato, "consumi.kwh_settimana", 6.3))
        // Se il ramo intermedio non c'e non si conclude niente: e' un payload
        // che non somiglia a quello che ci si aspettava, non un valore sparito.
        assertEquals(6.3, readSnapshotNumber(annidato, "altro.kwh_settimana", 6.3)!!, 0.001)
    }
}
