package it.agoldoni.smarthome.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La scelta del tema: tre voci, e due sole regole.
 *
 * La prima e' che **"come il sistema" non e' una terza resa** ma un rinvio: e'
 * cio' che rende invisibile l'aggiornamento a chi non tocca niente. Se saltasse,
 * l'app non si romperebbe — semplicemente smetterebbe di seguire il telefono, e
 * nessun errore lo direbbe.
 *
 * La seconda e' che **un valore salvato che non si capisce non e' un incidente**.
 * Vale per il downgrade: la versione di domani scrive una quarta voce, si
 * reinstalla sopra l'APK di oggi, e senza questa regola l'eccezione salterebbe
 * dentro il flusso che disegna l'interfaccia.
 */
class ThemeChoiceTest {

    // -- "come il sistema" rinvia, nei due versi ------------------------------

    @Test
    fun `con sistema chiaro la scelta sistema resta chiara`() {
        assertFalse(ThemeChoice.SISTEMA.scuro(sistemaScuro = false))
    }

    @Test
    fun `con sistema scuro la scelta sistema diventa scura`() {
        assertTrue(ThemeChoice.SISTEMA.scuro(sistemaScuro = true))
    }

    // -- le due scelte esplicite vincono sul telefono -------------------------

    @Test
    fun `chiaro resta chiaro anche se il telefono e scuro`() {
        assertFalse(ThemeChoice.CHIARO.scuro(sistemaScuro = true))
    }

    @Test
    fun `scuro resta scuro anche se il telefono e chiaro`() {
        assertTrue(ThemeChoice.SCURO.scuro(sistemaScuro = false))
    }

    // -- cosa si legge da un file che non dice niente di utile ----------------

    @Test
    fun `senza niente salvato si segue il sistema`() {
        assertEquals(ThemeChoice.SISTEMA, ThemeChoice.da(null))
    }

    @Test
    fun `un valore che non si conosce non solleva e torna al sistema`() {
        assertEquals(ThemeChoice.SISTEMA, ThemeChoice.da("TRAMONTO"))
        assertEquals(ThemeChoice.SISTEMA, ThemeChoice.da(""))
        // Le tre vere si rileggono per quello che sono: il giro completo
        // salvataggio-rilettura e' quello che conta, non il caso limite da solo.
        ThemeChoice.entries.forEach { scelta ->
            assertEquals(scelta, ThemeChoice.da(scelta.name))
        }
    }
}
