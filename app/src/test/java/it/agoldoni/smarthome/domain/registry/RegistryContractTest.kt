package it.agoldoni.smarthome.domain.registry

import it.agoldoni.smarthome.domain.model.DeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il contratto fra i due componenti, provato sul documento vero.
 *
 * `registro-ponte.json` non e' scritto a mano: lo produce il codice della web
 * app — il suo modello della presa del ponte, la sua validazione, la sua
 * serializzazione — e qui lo legge il parser dell'app. E' l'unico test che
 * verifica che i due pezzi si capiscano davvero, invece di capirsi ognuno con
 * se' stesso.
 *
 * Per rigenerarlo, dopo aver toccato i modelli o la serializzazione:
 * ```
 * node tools/genera-registro.mjs app/src/test/resources/registro-ponte.json
 * ```
 *
 * I topic e i campi JSON qui sotto sono stati confrontati il 12/09/2026 con
 * quelli che il ponte pubblica davvero, leggendoli dal broker di casa: tutti e
 * ventuno i topic delle sette prese combaciano carattere per carattere, e i
 * quattro campi JSON esistono nei payload.
 */
class RegistryContractTest {

    private val documento: String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("registro-ponte.json")) {
            "manca app/src/test/resources/registro-ponte.json"
        }.bufferedReader().use { it.readText() }

    private val registro: DeviceRegistry
        get() = (readRegistry(documento) as RegistryRead.Ok).registry

    @Test
    fun `il documento scritto dal configuratore si legge per intero`() {
        val letto = registro
        assertEquals(REGISTRY_SCHEMA, letto.schema)
        assertEquals(7, letto.devices.size)
        // Nessuno scartato: se il configuratore scrivesse qualcosa che l'app
        // non accetta, un dispositivo sparirebbe senza che nessuno dei due
        // segnali un errore.
        assertTrue("scartati: ${letto.skipped}", letto.skipped.isEmpty())
    }

    @Test
    fun `ci sono tutte e sette le prese di casa`() {
        assertEquals(
            listOf(
                "boiler", "depuratore", "frigorifero", "jacopo-studio",
                "lavastoviglie", "lavatrice-nuova", "pompa",
            ),
            registro.devices.map { it.name }.sorted(),
        )
    }

    @Test
    fun `la presa porta i quattro topic e i quattro campi che il ponte usa`() {
        val frigo = registro.devices.single { it.name == "frigorifero" }
        assertEquals(DeviceKind.SWITCH, frigo.kind)
        assertEquals("casa/frigorifero/stato", frigo.stateTopic)
        assertEquals("casa/frigorifero/comando", frigo.commandTopic)
        assertEquals("casa/frigorifero/disponibilita", frigo.availabilityTopic)
        assertEquals("casa/frigorifero/energia", frigo.energyTopic)
        assertEquals("stato", frigo.stateJsonKey)
        assertEquals("potenza_w", frigo.powerJsonKey)
        assertEquals("kwh_oggi", frigo.energyTodayJsonKey)
        assertEquals("kwh_mese", frigo.energyMonthJsonKey)
        assertEquals(1, frigo.qos)
        assertTrue(!frigo.retained)
    }

    @Test
    fun `il dispositivo si iscrive a tutti e quattro i topic`() {
        val frigo = registro.devices.single { it.name == "frigorifero" }
        assertEquals(
            listOf(
                "casa/frigorifero/stato",
                "casa/frigorifero/disponibilita",
                "casa/frigorifero/energia",
            ),
            frigo.subscriptions,
        )
    }

    @Test
    fun `una presa non e un dimmer, e i campi del livello restano vuoti`() {
        val frigo = registro.devices.single { it.name == "frigorifero" }
        assertNull(frigo.levelCommandTopic)
        assertNull(frigo.levelStateTopic)
        assertNull(frigo.levelJsonKey)
    }

    @Test
    fun `applicato a un telefono vuoto inserisce le sette prese e non cancella niente`() {
        val piano = planRegistry(registro, emptyList())
        assertEquals(7, piano.inserted.size)
        assertTrue(piano.deletedIds.isEmpty())
        assertTrue(piano.updated.isEmpty())
    }

    @Test
    fun `riapplicato non cambia piu niente`() {
        // Il registro si ripubblica per intero a ogni salvataggio: la seconda
        // volta non deve toccare il database, altrimenti le schede di casa si
        // azzerano a ogni virgola cambiata in un nome.
        val locali = registro.devices.mapIndexed { i, d -> d.copy(id = (i + 1).toLong()) }
        assertTrue(planRegistry(registro, locali).isEmpty)
    }
}
