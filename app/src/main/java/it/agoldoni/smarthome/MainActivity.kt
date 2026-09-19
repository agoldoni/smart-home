package it.agoldoni.smarthome

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.agoldoni.smarthome.ui.SmartHomeApp
import it.agoldoni.smarthome.ui.theme.SmartHomeTheme

/**
 * L'unica Activity, e l'unico posto in cui la scelta del tema diventa
 * l'apparenza dell'app.
 *
 * Le superfici da convincere sono tre e non una, perche' tre cose in Android
 * guardano la configurazione di sistema per conto proprio: i colori della
 * composizione (ci pensa [SmartHomeTheme]), lo sfondo della finestra prima che
 * Compose disegni, e il contrasto delle icone nelle barre di sistema. Saltarne
 * una non rompe niente — lascia solo un pezzo di app dell'altro colore.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as SmartHomeApplication).container

        // Prima di qualunque disegno: il tema XML sceglie lo sfondo della
        // finestra dalla configurazione di sistema, quindi con "Scuro" su un
        // telefono chiaro il primo fotogramma sarebbe bianco. Si vede, ed e'
        // esattamente il lampeggio che questa feature non deve avere.
        applicaSfondo(container.scuroOra())
        preparaAvvio(container.scuroOra())

        setContent {
            val scelta by container.themeChoice.collectAsStateWithLifecycle()
            val scuro = scelta.scuro(isSystemInDarkTheme())

            // `enableEdgeToEdge()` senza argomenti decide il contrasto delle
            // icone dalla configurazione: con un tema forzato finirebbero nere
            // su fondo nero. Il lambda di `auto` e' il punto esatto in cui la
            // scelta prende il posto della configurazione. Si rifa' a ogni
            // cambio, perche' le barre non si ricompongono da sole.
            DisposableEffect(scuro) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT,
                        Color.TRANSPARENT,
                    ) { scuro },
                    navigationBarStyle = SystemBarStyle.auto(
                        VELO_CHIARO,
                        VELO_SCURO,
                    ) { scuro },
                )
                applicaSfondo(scuro)
                preparaAvvio(scuro)
                onDispose { }
            }

            SmartHomeTheme(darkTheme = scuro) {
                SmartHomeApp()
            }
        }
    }

    /**
     * Il colore della finestra che si vede **prima** che il processo esista.
     *
     * Non e' l'Activity a disegnarla: la mette il sistema, dal tema dichiarato
     * nel manifest, e quel tema segue la configurazione del telefono. Con
     * "Scuro" su un telefono chiaro si vede un rettangolo bianco per tutto
     * l'avvio, che e' proprio il lampeggio che si vuole togliere, e nessuna
     * riga dentro `onCreate` puo' cambiarlo: quando `onCreate` gira, quella
     * finestra e' gia' sullo schermo da un pezzo.
     *
     * Da Android 12 esiste l'unico modo per intervenire, ed e' dichiarare oggi
     * il tema del **prossimo** avvio. Una scelta appena cambiata vale quindi
     * dalla volta dopo; sotto Android 12 non c'e' rimedio, e li' restano i
     * pochi decimi della finestra d'avvio col colore del telefono.
     */
    private fun preparaAvvio(scuro: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        splashScreen.setSplashScreenTheme(
            if (scuro) R.style.Theme_SmartHome_Avvio_Scuro else R.style.Theme_SmartHome_Avvio_Chiaro,
        )
    }

    private fun applicaSfondo(scuro: Boolean) {
        val colore = if (scuro) {
            android.R.color.background_dark
        } else {
            android.R.color.background_light
        }
        window.setBackgroundDrawable(getColor(colore).toDrawable())
    }

    private companion object {
        // I veli che `enableEdgeToEdge()` mette da se' sotto la barra di
        // navigazione dove il sistema non sa fare il contrasto da solo (prima
        // di Android 10). Nella libreria sono privati: si ricopiano tali e
        // quali perche' cambiarli cambierebbe l'aspetto della barra sui
        // telefoni piu' vecchi, che e' l'opposto di quello che si sta facendo.
        val VELO_CHIARO = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val VELO_SCURO = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
