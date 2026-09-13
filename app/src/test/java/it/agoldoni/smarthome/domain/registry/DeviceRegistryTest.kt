package it.agoldoni.smarthome.domain.registry

import it.agoldoni.smarthome.domain.model.Device
import it.agoldoni.smarthome.domain.model.DeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun ok(payload: String): DeviceRegistry =
    (readRegistry(payload) as RegistryRead.Ok).registry

private fun rifiuto(payload: String): RegistryRejection =
    (readRegistry(payload) as RegistryRead.Rejected).reason

/** Il documento dell'esempio in `bridge/configuratore/SCHEMA.md`. */
private const val COMPLETO = """
{"schema":1,"revisione":12,"aggiornato":"2026-09-12T21:04:33+02:00","dispositivi":[
 {"uuid":"8f1c","nome":"frigorifero","tipo":"SWITCH",
  "topic_stato":"casa/frigorifero/stato","campo_stato":"stato",
  "topic_comando":"casa/frigorifero/comando","payload_on":"ON","payload_off":"OFF",
  "campo_potenza":"potenza_w","topic_energia":"casa/frigorifero/energia",
  "campo_kwh_oggi":"kwh_oggi","campo_kwh_mese":"kwh_mese",
  "topic_disponibilita":"casa/frigorifero/disponibilita",
  "payload_disponibile":"online","payload_non_disponibile":"offline",
  "topic_stato_livello":null,"topic_comando_livello":null,"campo_livello":null,
  "livello_max":100,"qos":1,"ritenuto":false}]}
"""

class ParseRegistryTest {

    @Test
    fun `legge il documento completo`() {
        val registro = ok(COMPLETO)
        assertEquals(1, registro.schema)
        assertEquals(12, registro.revision)
        assertEquals("2026-09-12T21:04:33+02:00", registro.updatedAt)
        val d = registro.devices.single()
        assertEquals("8f1c", d.uuid)
        assertEquals("frigorifero", d.name)
        assertEquals(DeviceKind.SWITCH, d.kind)
        assertEquals("casa/frigorifero/stato", d.stateTopic)
        assertEquals("stato", d.stateJsonKey)
        assertEquals("potenza_w", d.powerJsonKey)
        assertEquals("casa/frigorifero/energia", d.energyTopic)
        assertEquals("kwh_oggi", d.energyTodayJsonKey)
        assertEquals("casa/frigorifero/disponibilita", d.availabilityTopic)
        assertEquals(1, d.qos)
        assertTrue(registro.skipped.isEmpty())
    }

    @Test
    fun `bastano i campi obbligatori, il resto ha un predefinito`() {
        val d = ok(
            """{"schema":1,"revisione":1,"dispositivi":[
               {"uuid":"a","nome":"luce","tipo":"LIGHT",
                "topic_stato":"s","topic_comando":"c"}]}""",
        ).devices.single()
        assertEquals("ON", d.payloadOn)
        assertEquals("OFF", d.payloadOff)
        assertEquals("online", d.payloadAvailable)
        assertEquals(100, d.levelMax)
        assertEquals(0, d.qos)
        assertEquals(false, d.retained)
        assertNull(d.stateJsonKey)
        assertNull(d.availabilityTopic)
    }

    @Test
    fun `un null JSON non diventa la stringa null`() {
        // optString su JSONObject.NULL restituirebbe "null", che finirebbe
        // dentro un topic e ci resterebbe.
        val d = ok(
            """{"schema":1,"revisione":1,"dispositivi":[
               {"uuid":"a","nome":"x","tipo":"SWITCH","topic_stato":"s","topic_comando":"c",
                "topic_disponibilita":null,"campo_stato":null}]}""",
        ).devices.single()
        assertNull(d.availabilityTopic)
        assertNull(d.stateJsonKey)
    }

    @Test
    fun `un payload che non e JSON si rifiuta in blocco`() {
        assertEquals(RegistryRejection.NOT_JSON, rifiuto("non sono JSON"))
        assertEquals(RegistryRejection.NOT_JSON, rifiuto("[1,2,3]"))
    }

    @Test
    fun `un payload vuoto e il registro cancellato, non un errore`() {
        assertEquals(RegistryRejection.EMPTY, rifiuto(""))
        assertEquals(RegistryRejection.EMPTY, rifiuto("   "))
    }

    @Test
    fun `senza l'elenco dei dispositivi si rifiuta in blocco`() {
        assertEquals(
            RegistryRejection.MISSING_DEVICES,
            rifiuto("""{"schema":1,"revisione":3}"""),
        )
    }

    @Test
    fun `uno schema piu nuovo si rifiuta invece di interpretarlo a meta`() {
        assertEquals(
            RegistryRejection.SCHEMA_TOO_NEW,
            rifiuto("""{"schema":2,"revisione":1,"dispositivi":[]}"""),
        )
    }

    @Test
    fun `un campo sconosciuto si ignora e il dispositivo si applica lo stesso`() {
        val registro = ok(
            """{"schema":1,"revisione":1,"dispositivi":[
               {"uuid":"a","nome":"x","tipo":"SWITCH","topic_stato":"s","topic_comando":"c",
                "stanza":"cucina","colore_scheda":"verde"}]}""",
        )
        assertEquals(1, registro.devices.size)
        assertTrue(registro.skipped.isEmpty())
    }

    @Test
    fun `la posizione si legge, e senza vale nulla`() {
        val registro = ok(
            """{"schema":1,"revisione":1,"dispositivi":[
               {"uuid":"a","nome":"boiler","tipo":"SWITCH","topic_stato":"s","topic_comando":"c",
                "posizione":3},
               {"uuid":"b","nome":"pompa","tipo":"SWITCH","topic_stato":"s2","topic_comando":"c2"}]}""",
        )
        assertEquals(3, registro.devices.first().position)
        assertNull(registro.devices.last().position)
    }

    @Test
    fun `lo zero e un posto vero, e non va confuso con l'assenza`() {
        // La distinzione su cui poggia tutta la feature: zero e' il primo posto,
        // nullo e' nessun posto — e nessun posto finisce in fondo.
        val d = ok(
            """{"schema":1,"revisione":1,"dispositivi":[
               {"uuid":"a","nome":"boiler","tipo":"SWITCH","topic_stato":"s","topic_comando":"c",
                "posizione":0}]}""",
        ).devices.single()
        assertEquals(0, d.position)
    }

    @Test
    fun `una posizione che non si capisce vale come assente, mai come zero`() {
        // Se ripiegasse su zero, un errore di battitura porterebbe una presa in
        // cima alla casa invece che in fondo all'elenco.
        val strane = listOf("-1", "1.5", "\"2\"", "true", "null")
        strane.forEach { valore ->
            val d = ok(
                """{"schema":1,"revisione":1,"dispositivi":[
                   {"uuid":"a","nome":"x","tipo":"SWITCH","topic_stato":"s","topic_comando":"c",
                    "posizione":$valore}]}""",
            ).devices.single()
            assertNull("posizione $valore doveva valere come assente", d.position)
        }
    }

    @Test
    fun `una posizione ripetuta non fa saltare nessuno`() {
        // Chi scrive non le produce, ma un documento scritto a mano puo'. Due
        // posti uguali sono un ordine ambiguo, non un documento illeggibile.
        val registro = ok(
            """{"schema":1,"revisione":1,"dispositivi":[
               {"uuid":"a","nome":"boiler","tipo":"SWITCH","topic_stato":"s","topic_comando":"c",
                "posizione":1},
               {"uuid":"b","nome":"pompa","tipo":"SWITCH","topic_stato":"s2","topic_comando":"c2",
                "posizione":1}]}""",
        )
        assertEquals(2, registro.devices.size)
        assertTrue(registro.skipped.isEmpty())
    }

    @Test
    fun `un tipo sconosciuto salta quel dispositivo, non gli altri`() {
        val registro = ok(
            """{"schema":1,"revisione":1,"dispositivi":[
               {"uuid":"a","nome":"tenda","tipo":"COVER","topic_stato":"s","topic_comando":"c"},
               {"uuid":"b","nome":"luce","tipo":"LIGHT","topic_stato":"s2","topic_comando":"c2"}]}""",
        )
        assertEquals(listOf("luce"), registro.devices.map { it.name })
        assertEquals(1, registro.skipped.size)
        assertTrue(registro.skipped.single().contains("COVER"))
    }

    @Test
    fun `senza uuid il dispositivo si salta`() {
        val registro = ok(
            """{"schema":1,"revisione":1,"dispositivi":[
               {"nome":"x","tipo":"SWITCH","topic_stato":"s","topic_comando":"c"}]}""",
        )
        assertTrue(registro.devices.isEmpty())
        assertEquals(1, registro.skipped.size)
    }

    @Test
    fun `un uuid duplicato entra una volta sola`() {
        val registro = ok(
            """{"schema":1,"revisione":1,"dispositivi":[
               {"uuid":"a","nome":"primo","tipo":"SWITCH","topic_stato":"s","topic_comando":"c"},
               {"uuid":"a","nome":"secondo","tipo":"SWITCH","topic_stato":"s2","topic_comando":"c2"}]}""",
        )
        assertEquals(listOf("primo"), registro.devices.map { it.name })
        assertTrue(registro.skipped.single().contains("duplicato"))
    }

    @Test
    fun `un topic di comando con wildcard salta il dispositivo`() {
        // Il broker rifiuterebbe il messaggio: meglio non registrarlo affatto
        // che avere una scheda con un interruttore che non puo' funzionare.
        val registro = ok(
            """{"schema":1,"revisione":1,"dispositivi":[
               {"uuid":"a","nome":"x","tipo":"SWITCH","topic_stato":"s","topic_comando":"casa/+/set"}]}""",
        )
        assertTrue(registro.devices.isEmpty())
    }

    @Test
    fun `un sensore non ha bisogno del topic di comando`() {
        val d = ok(
            """{"schema":1,"revisione":1,"dispositivi":[
               {"uuid":"a","nome":"termometro","tipo":"SENSOR","topic_stato":"casa/temp"}]}""",
        ).devices.single()
        assertEquals("", d.commandTopic)
        assertEquals(DeviceKind.SENSOR, d.kind)
    }

    @Test
    fun `un dimmer senza topic del livello si salta`() {
        val registro = ok(
            """{"schema":1,"revisione":1,"dispositivi":[
               {"uuid":"a","nome":"lampada","tipo":"DIMMER","topic_stato":"s","topic_comando":"c"}]}""",
        )
        assertTrue(registro.devices.isEmpty())
        assertTrue(registro.skipped.single().contains("dimmer"))
    }

    @Test
    fun `qos e livello massimo fuori scala si riportano dentro`() {
        val d = ok(
            """{"schema":1,"revisione":1,"dispositivi":[
               {"uuid":"a","nome":"x","tipo":"SWITCH","topic_stato":"s","topic_comando":"c",
                "qos":9,"livello_max":0}]}""",
        ).devices.single()
        assertEquals(2, d.qos)
        assertEquals(1, d.levelMax)
    }
}

class RegistryRevisionTest {

    private fun conRevisione(n: Int) = DeviceRegistry(1, n, null, emptyList())

    @Test
    fun `una revisione piu alta si applica`() {
        assertTrue(conRevisione(13).isNewerThan(12))
    }

    @Test
    fun `la stessa revisione non si riapplica`() {
        assertTrue(!conRevisione(12).isNewerThan(12))
    }

    @Test
    fun `una revisione piu vecchia si ignora`() {
        assertTrue(!conRevisione(11).isNewerThan(12))
    }

    @Test
    fun `la revisione zero si applica se non se n'e mai applicata nessuna`() {
        // Il sentinella e' -1 e non 0 apposta: zero e' una revisione valida.
        assertTrue(conRevisione(0).isNewerThan(NO_REVISION))
    }
}

class RegistryPlanTest {

    private fun dalRegistro(uuid: String, nome: String, topic: String = "casa/$nome/stato") =
        Device(uuid = uuid, name = nome, stateTopic = topic, commandTopic = "casa/$nome/comando")

    private fun registro(vararg devices: Device) =
        DeviceRegistry(1, 1, null, devices.toList())

    @Test
    fun `un registro identico non produce nessuna modifica`() {
        // Il test che conta piu' di tutti. Il registro si ripubblica per intero
        // a ogni salvataggio: se un documento invariato producesse aggiornamenti,
        // ogni virgola cambiata in un nome rifarebbe le sottoscrizioni di casa e
        // azzererebbe tutte le schede.
        val locale = dalRegistro("a", "boiler").copy(id = 7L)
        val piano = planRegistry(registro(dalRegistro("a", "boiler")), listOf(locale))
        assertTrue(piano.isEmpty)
        assertEquals(0, piano.touched)
    }

    @Test
    fun `un dispositivo in piu si inserisce`() {
        val piano = planRegistry(
            registro(dalRegistro("a", "boiler"), dalRegistro("b", "pompa")),
            listOf(dalRegistro("a", "boiler").copy(id = 7L)),
        )
        assertEquals(listOf("pompa"), piano.inserted.map { it.name })
        assertTrue(piano.updated.isEmpty())
        assertTrue(piano.deletedIds.isEmpty())
    }

    @Test
    fun `un dispositivo in meno si cancella`() {
        val piano = planRegistry(
            registro(dalRegistro("a", "boiler")),
            listOf(dalRegistro("a", "boiler").copy(id = 7L), dalRegistro("b", "pompa").copy(id = 9L)),
        )
        assertEquals(listOf(9L), piano.deletedIds)
        assertTrue(piano.inserted.isEmpty())
    }

    @Test
    fun `un dispositivo cambiato conserva il proprio id`() {
        val locale = dalRegistro("a", "boiler").copy(id = 7L)
        val piano = planRegistry(
            registro(dalRegistro("a", "boiler").copy(name = "boiler bagno")),
            listOf(locale),
        )
        val aggiornato = piano.updated.single()
        assertEquals(7L, aggiornato.id)
        assertEquals("boiler bagno", aggiornato.name)
        assertTrue(piano.inserted.isEmpty())
        assertTrue(piano.deletedIds.isEmpty())
    }

    @Test
    fun `un dispositivo solo spostato si aggiorna, e conserva il proprio id`() {
        // Il riordino visto dal piano: cambia un campo solo, e quel campo deve
        // arrivare al database. Ma **aggiornando in loco**, perche' un id nuovo
        // vorrebbe dire scheda azzerata e sottoscrizioni rifatte a ogni
        // trascinamento.
        val locale = dalRegistro("a", "boiler").copy(id = 7L, position = 0)
        val piano = planRegistry(
            registro(dalRegistro("a", "boiler").copy(position = 3)),
            listOf(locale),
        )
        val aggiornato = piano.updated.single()
        assertEquals(7L, aggiornato.id)
        assertEquals(3, aggiornato.position)
        assertTrue(piano.inserted.isEmpty())
        assertTrue(piano.deletedIds.isEmpty())
    }

    @Test
    fun `la stanza resta quella locale, il registro non la porta`() {
        val locale = dalRegistro("a", "boiler").copy(id = 7L, room = "bagno")
        val piano = planRegistry(
            registro(dalRegistro("a", "boiler").copy(name = "scaldabagno")),
            listOf(locale),
        )
        assertEquals("bagno", piano.updated.single().room)
    }

    @Test
    fun `su un telefono vuoto si inserisce tutto e non si cancella niente`() {
        val piano = planRegistry(
            registro(dalRegistro("a", "boiler"), dalRegistro("b", "pompa")),
            emptyList(),
        )
        assertEquals(2, piano.inserted.size)
        assertTrue(piano.deletedIds.isEmpty())
        assertTrue(piano.adoptedIds.isEmpty())
    }
}

class RegistryAdoptionTest {

    private fun dalRegistro(uuid: String, nome: String, topic: String) =
        Device(uuid = uuid, name = nome, stateTopic = topic, commandTopic = "casa/$nome/comando")

    @Test
    fun `un locale senza uuid con lo stesso topic di stato viene adottato`() {
        // Senza adozione verrebbe cancellato e riaggiunto con un id nuovo, e la
        // scheda tornerebbe "in attesa di dati" davanti a chi la sta guardando.
        val locale = Device(id = 7L, name = "boiler", stateTopic = "casa/boiler/stato")
        val piano = planRegistry(
            DeviceRegistry(1, 1, null, listOf(dalRegistro("a", "boiler", "casa/boiler/stato"))),
            listOf(locale),
        )
        assertEquals(listOf(7L), piano.adoptedIds)
        assertEquals(7L, piano.updated.single().id)
        assertEquals("a", piano.updated.single().uuid)
        assertTrue(piano.inserted.isEmpty())
        assertTrue(piano.deletedIds.isEmpty())
    }

    @Test
    fun `un topic diverso non e lo stesso dispositivo`() {
        val locale = Device(id = 7L, name = "boiler", stateTopic = "casa/altro/stato")
        val piano = planRegistry(
            DeviceRegistry(1, 1, null, listOf(dalRegistro("a", "boiler", "casa/boiler/stato"))),
            listOf(locale),
        )
        assertTrue(piano.adoptedIds.isEmpty())
        assertEquals(1, piano.inserted.size)
        assertEquals(listOf(7L), piano.deletedIds)
    }

    @Test
    fun `due locali sullo stesso topic ne fanno adottare uno solo, il primo registrato`() {
        val piano = planRegistry(
            DeviceRegistry(1, 1, null, listOf(dalRegistro("a", "boiler", "casa/boiler/stato"))),
            listOf(
                Device(id = 9L, name = "doppione", stateTopic = "casa/boiler/stato"),
                Device(id = 4L, name = "boiler", stateTopic = "casa/boiler/stato"),
            ),
        )
        assertEquals(listOf(4L), piano.adoptedIds)
        assertEquals(listOf(9L), piano.deletedIds)
    }

    @Test
    fun `l'uuid vince sul topic quando ci sono entrambi`() {
        val conUuid = Device(id = 7L, uuid = "a", name = "boiler", stateTopic = "casa/boiler/stato")
        val senzaUuid = Device(id = 9L, name = "vecchio", stateTopic = "casa/boiler/stato")
        val piano = planRegistry(
            DeviceRegistry(1, 1, null, listOf(dalRegistro("a", "boiler", "casa/boiler/stato"))),
            listOf(conUuid, senzaUuid),
        )
        assertTrue(piano.adoptedIds.isEmpty())
        assertEquals(listOf(9L), piano.deletedIds)
    }
}
