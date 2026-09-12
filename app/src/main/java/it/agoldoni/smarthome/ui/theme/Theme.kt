package it.agoldoni.smarthome.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF006874),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF97F0FF),
    onPrimaryContainer = Color(0xFF001F24),
    secondary = Color(0xFF4A6267),
    secondaryContainer = Color(0xFFCDE7EC),
    tertiary = Color(0xFF9A4521),
    surfaceVariant = Color(0xFFDBE4E6),
    onSurfaceVariant = Color(0xFF3F484A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FD8EB),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF97F0FF),
    secondary = Color(0xFFB1CBD0),
    secondaryContainer = Color(0xFF334B4F),
    tertiary = Color(0xFFFFB597),
    surfaceVariant = Color(0xFF3F484A),
    onSurfaceVariant = Color(0xFFBFC8CA),
)

@Composable
fun SmartHomeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // I colori di sistema di Android 12+ fanno sembrare l'app parte del telefono;
    // sotto quella versione si ripiega sulla palette dell'app.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}

/** Sfondo e contenuto di una scheda accesa. */
@Immutable
data class PoweredColors(val container: Color, val content: Color)

/**
 * Il verde dell'acceso non viene dallo schema di colori.
 *
 * Con i colori dinamici di Android 12+ lo schema segue lo sfondo del telefono:
 * `primaryContainer` puo essere rosa, ocra o viola a seconda della fotografia
 * che l'utente ha messo in home. Per dire "questa presa e accesa" serve un
 * colore che voglia dire sempre la stessa cosa, quindi e fissato qui.
 *
 * I due valori non sono lo stesso verde schiarito: su fondo scuro un verde
 * chiaro abbaglia, su fondo chiaro un verde saturo mangia il testo.
 */
@Composable
fun poweredColors(): PoweredColors = if (isSystemInDarkTheme()) {
    PoweredColors(container = Color(0xFF1F5B28), content = Color(0xFFD8F3D9))
} else {
    PoweredColors(container = Color(0xFFB9E6BC), content = Color(0xFF0D3B14))
}

/**
 * Il rosso del segno di debug, fissato qui per la stessa ragione del verde qui
 * sopra: `colorScheme.error` con i colori dinamici segue lo sfondo del telefono
 * e sul tema scuro diventa un rosa slavato, che e esattamente il contrario di
 * quello che questo segno deve fare. Due tonalita perche su fondo scuro un rosso
 * cupo sparisce e su fondo chiaro uno acceso vibra.
 */
@Composable
fun debugRed(): Color = if (isSystemInDarkTheme()) Color(0xFFFF5A5A) else Color(0xFFD32F2F)
