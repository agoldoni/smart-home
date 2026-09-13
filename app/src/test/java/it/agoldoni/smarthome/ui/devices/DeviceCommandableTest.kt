package it.agoldoni.smarthome.ui.devices

import it.agoldoni.smarthome.domain.model.Device
import it.agoldoni.smarthome.domain.model.DeviceState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La regola del lucchetto.
 *
 * E' l'unica parte di questa feature che si possa provare senza un telefono, ed
 * e' anche l'unica che decide se un comando parte: due motivi indipendenti che
 * spengono lo stesso controllo. Le quattro combinazioni ci sono tutte perche'
 * quella che conta davvero e' la quarta — bloccato *e* irraggiungibile, dove un
 * `||` scritto al posto di un `&&` passerebbe inosservato nelle altre tre.
 */
class DeviceCommandableTest {

    @Test
    fun `sbloccato e raggiungibile comanda`() {
        assertTrue(item(reachable = true).commandable(locked = false))
    }

    @Test
    fun `bloccato non comanda nemmeno se raggiungibile`() {
        assertFalse(item(reachable = true).commandable(locked = true))
    }

    @Test
    fun `irraggiungibile non comanda nemmeno se sbloccato`() {
        assertFalse(item(reachable = false).commandable(locked = false))
    }

    @Test
    fun `bloccato e irraggiungibile non comanda`() {
        assertFalse(item(reachable = false).commandable(locked = true))
    }

    /**
     * Raggiungibilita' sconosciuta non e' irraggiungibilita': un dispositivo
     * senza topic di disponibilita' si comanda, ed e' il caso di quasi tutte le
     * prese del ponte.
     */
    @Test
    fun `raggiungibilita sconosciuta comanda se sbloccato`() {
        assertTrue(item(reachable = null).commandable(locked = false))
        assertFalse(item(reachable = null).commandable(locked = true))
    }

    private fun item(reachable: Boolean?) = DeviceUi(
        device = Device(name = "boiler", stateTopic = "casa/boiler/stato"),
        state = DeviceState(reachable = reachable),
    )
}
