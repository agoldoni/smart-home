package it.agoldoni.smarthome.ui.devices

import it.agoldoni.smarthome.domain.model.DeviceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * La riga dei consumi: quattro caselle, tre scalini, un trattino.
 *
 * E' una riga senza etichette, e questo sposta tutto il peso su due proprieta'
 * che qui si provano per casi: **l'ordine non cambia mai** e **le caselle sono
 * sempre quattro**. Se una delle due salta, la riga non diventa meno bella —
 * diventa illeggibile in modo silenzioso, perche' il numero del mese si legge
 * come se fosse quello della settimana e nessuno se ne accorge.
 */
class EnergiaCompattaTest {

    private val italia = Locale.ITALY

    private fun consumi(oggi: Double?, ieri: Double?, settimana: Double?, mese: Double?) =
        DeviceState(kwhToday = oggi, kwhYesterday = ieri, kwhWeek = settimana, kwhMonth = mese)

    private fun riga(oggi: Double?, ieri: Double?, settimana: Double?, mese: Double?) =
        energiaCompatta(consumi(oggi, ieri, settimana, mese), "–", italia)

    // -- i quattro numeri, nell'ordine ---------------------------------------

    @Test
    fun `i quattro valori vanno in fila separati dalla barra`() {
        assertEquals("0,42/1,87/6,30/12,7", riga(0.42, 1.87, 6.3, 12.7))
    }

    @Test
    fun `l'ordine e oggi ieri settimana mese`() {
        // Quattro numeri distinguibili a colpo d'occhio: se l'ordine cambiasse,
        // questo e' il test che se ne accorgerebbe invece dell'utente.
        assertEquals("1,00/2,00/3,00/4,00", riga(1.0, 2.0, 3.0, 4.0))
    }

    // -- i tre scalini -------------------------------------------------------

    @Test
    fun `sotto i dieci due decimali`() {
        assertEquals("0,42", formatKwh(0.42, italia))
        assertEquals("9,99", formatKwh(9.99, italia))
    }

    @Test
    fun `sotto i cento un decimale solo`() {
        assertEquals("12,7", formatKwh(12.7, italia))
        assertEquals("99,9", formatKwh(99.9, italia))
    }

    @Test
    fun `da cento in su nessun decimale`() {
        assertEquals("104", formatKwh(104.2, italia))
        assertEquals("439", formatKwh(438.7, italia))
    }

    @Test
    fun `lo scalino si decide su come il numero verra scritto`() {
        // 9,996 con due decimali uscirebbe "10,00": cinque caratteri, e il limite
        // che fa stare la riga salterebbe proprio sulla soglia. La soglia e'
        // 9,995 apposta.
        assertEquals("10,0", formatKwh(9.996, italia))
        assertEquals("100", formatKwh(99.96, italia))
    }

    @Test
    fun `nessun numero supera i quattro caratteri`() {
        val estremi = listOf(0.0, 0.004, 9.99, 9.996, 10.0, 99.9, 99.96, 100.0, 438.7, 9999.4)
        for (kwh in estremi) {
            val scritto = formatKwh(kwh, italia)
            assertTrue("$kwh -> $scritto", scritto.length <= 4)
        }
    }

    @Test
    fun `la riga peggiore sta in ventidue caratteri`() {
        val peggiore = riga(9.99, 99.9, 999.0, 9999.0)
        assertEquals("9,99/99,9/999/9999", peggiore)
        // Piu' l'unita', che la mette chi chiama: ventidue contro i trentadue
        // della riga che questa sostituisce.
        assertEquals(22, "$peggiore kWh".length)
    }

    // -- quello che manca ----------------------------------------------------

    @Test
    fun `un valore che non si sa e un trattino, e la casella resta al suo posto`() {
        assertEquals("0,42/–/–/12,7", riga(0.42, null, null, 12.7))
    }

    @Test
    fun `il buco puo stare anche in fondo o in testa`() {
        assertEquals("–/1,87/6,30/12,7", riga(null, 1.87, 6.3, 12.7))
        assertEquals("0,42/1,87/6,30/–", riga(0.42, 1.87, 6.3, null))
    }

    @Test
    fun `zero non e un trattino`() {
        // Zero e' un dispositivo che non ha consumato, ed e' una risposta. Il
        // trattino e' l'assenza di risposta, e le due cose si vedono diverse.
        assertEquals("0,00/0,00/0,00/0,00", riga(0.0, 0.0, 0.0, 0.0))
    }

    @Test
    fun `se non si sa niente non c'e nessuna riga`() {
        assertNull(riga(null, null, null, null))
        assertNull(consumiFormattati(DeviceState(), italia))
    }

    @Test
    fun `basta un valore solo perche la riga esista`() {
        assertEquals("–/–/–/12,7", riga(null, null, null, 12.7))
    }

    // -- i pezzi per chi la scheda se la fa leggere ---------------------------

    @Test
    fun `i valori formattati escono in quattro caselle, nulle dove non si sa`() {
        val valori = consumiFormattati(consumi(0.42, null, 6.3, 12.7), italia)
        assertEquals(listOf("0,42", null, "6,30", "12,7"), valori)
    }
}
