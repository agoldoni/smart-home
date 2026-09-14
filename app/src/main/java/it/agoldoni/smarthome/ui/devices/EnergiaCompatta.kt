package it.agoldoni.smarthome.ui.devices

import it.agoldoni.smarthome.domain.model.DeviceState
import java.util.Locale

/**
 * I consumi accumulati, per la riga della scheda.
 *
 * Sta fuori dalla schermata perche' e' una **regola** e non un disegno: quattro
 * caselle in ordine fisso, un formato con tre scalini, e un segnaposto per chi
 * manca. Tutte cose che si provano per casi, e che dentro una `@Composable`
 * privata non si potrebbero provare affatto.
 */

/**
 * I quattro valori formattati, sempre in quest'ordine: **oggi, ieri, settimana,
 * mese**. `null` nelle caselle di cui non si sa niente.
 *
 * Restituisce `null` — e non una lista di quattro `null` — quando nessuno dei
 * quattro si sa: e' il caso del dispositivo per cui non conta nessuno, e li' la
 * riga non va mostrata per niente.
 *
 * L'ordine e' fisso e non sara' mai configurabile. E' l'unica cosa che dice cosa
 * sia un numero, in una riga che le etichette non ce le ha.
 */
fun consumiFormattati(state: DeviceState, locale: Locale = Locale.getDefault()): List<String?>? {
    val valori = listOf(state.kwhToday, state.kwhYesterday, state.kwhWeek, state.kwhMonth)
    if (valori.all { it == null }) return null
    return valori.map { it?.let { kwh -> formatKwh(kwh, locale) } }
}

/**
 * La riga compatta: `0,42/1,87/6,30/12,7 kWh` — l'unita' la mette chi chiama.
 *
 * Le caselle sono **sempre quattro**, anche quando un valore non c'e: al suo
 * posto va [mancante]. Non e' un vezzo tipografico, e' la condizione perche' la
 * riga si possa leggere — tre numeri che scivolano a sinistra farebbero leggere
 * il mese al posto della settimana, e nessuno se ne accorgerebbe.
 */
fun energiaCompatta(
    state: DeviceState,
    mancante: String,
    locale: Locale = Locale.getDefault(),
): String? = consumiFormattati(state, locale)
    ?.joinToString("/") { it ?: mancante }

/**
 * Quanti kWh, in quattro caratteri.
 *
 * Tre scalini: due decimali sotto i dieci kWh, uno sotto i cento, nessuno sopra.
 * Un boiler non fa 47,83 kWh, fa 47,8 — e a 438 kWh in un mese il decimale e'
 * una cifra che balla senza dire niente.
 *
 * Il limite dei quattro caratteri e' quello che fa stare la riga: quattro
 * caselle, tre barre e l'unita' sono ventidue caratteri nel caso peggiore,
 * contro i trentadue della riga che questa sostituisce.
 *
 * Le soglie sono **9,995 e 99,95 e non 10 e 100**, ed e' il dettaglio che le fa
 * funzionare: confrontando con 10, un 9,996 cadrebbe fra i due decimali e
 * uscirebbe `10,00`, che di caratteri ne sono cinque. Lo scalino si decide su
 * come il numero verra' scritto, non su com'e' arrivato.
 */
internal fun formatKwh(kwh: Double, locale: Locale = Locale.getDefault()): String {
    val formato = when {
        kwh < 9.995 -> "%.2f"
        kwh < 99.95 -> "%.1f"
        else -> "%.0f"
    }
    return String.format(locale, formato, kwh)
}
