package it.agoldoni.smarthome.ui.devices

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import it.agoldoni.smarthome.BuildConfig
import it.agoldoni.smarthome.R
import it.agoldoni.smarthome.domain.model.ConnectionState
import it.agoldoni.smarthome.domain.model.Device
import it.agoldoni.smarthome.domain.model.DeviceKind
import it.agoldoni.smarthome.ui.AppViewModelFactory
import it.agoldoni.smarthome.ui.common.iconRes
import it.agoldoni.smarthome.ui.common.labelRes
import it.agoldoni.smarthome.ui.theme.debugRed
import it.agoldoni.smarthome.ui.theme.poweredColors
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    onAddDevice: () -> Unit,
    onEditDevice: (Device) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: DeviceListViewModel = viewModel(factory = AppViewModelFactory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val debugStatus by viewModel.debugStatus.collectAsStateWithLifecycle()
    val registry by viewModel.registryStatus.collectAsStateWithLifecycle()
    val proposal by viewModel.proposal.collectAsStateWithLifecycle()
    val commandsSentLabel = stringResource(R.string.commands_sent_desc)
    // Lo stato letto da TalkBack e' separato dall'etichetta del tocco: la
    // descrizione dice cosa succede se premi, questa dice come stai adesso.
    val lockStateLabel = stringResource(
        if (state.locked) R.string.view_locked_state_locked else R.string.view_locked_state_unlocked,
    )
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    // Il primo registro che toglie qualcosa si fa annunciare, e dice cosa.
    // Fonte di verita' unica vuol dire che quello che non c'e' sparisce, e la
    // prima volta chi usa l'app non se lo aspetta. Succede una volta sola.
    proposal?.let { proposta ->
        AlertDialog(
            onDismissRequest = viewModel::refuseRegistry,
            title = { Text(stringResource(R.string.registry_confirm_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.registry_confirm_body,
                            proposta.incoming,
                            proposta.removing.size,
                            proposta.removing.joinToString("\n") { "  · $it" },
                        ),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.registry_confirm_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::acceptRegistry) {
                    Text(stringResource(R.string.action_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::refuseRegistry) {
                    Text(stringResource(R.string.action_keep))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // Sotto al nome una riga di servizio in piccolo: la versione
                // dice quale delle due installazioni (debug o release) si sta
                // guardando, il contatore risponde alla domanda che ci si fa
                // davanti all'elenco - quel dispositivo che si e mosso, l'ho
                // mosso io? Se il numero non cambia, la risposta e no.
                title = {
                    Column {
                        Row {
                            Text(text = stringResource(R.string.app_name))
                            // Finche l'API di debug e accesa, un insetto rosso
                            // qui sopra. L'app espone il proprio stato a
                            // chiunque sia in rete locale: e una cosa che si
                            // deve vedere dalla schermata che si guarda sempre,
                            // non solo da dentro le impostazioni dove
                            // l'interruttore e stato acceso.
                            if (debugStatus.enabled) {
                                // Fermo, l'insetto si confonde con le altre
                                // icone della barra e dopo due giorni non lo si
                                // vede piu: il battito lento lo tiene addosso
                                // all'occhio senza farne un allarme.
                                val pulse = rememberInfiniteTransition(label = "debug")
                                val blink by pulse.animateFloat(
                                    initialValue = 1f,
                                    targetValue = 0.2f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(
                                            durationMillis = 700,
                                            easing = FastOutSlowInEasing,
                                        ),
                                        repeatMode = RepeatMode.Reverse,
                                    ),
                                    label = "debug-alpha",
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    painter = painterResource(R.drawable.ic_debug),
                                    contentDescription = stringResource(R.string.debug_indicator),
                                    tint = debugRed().copy(alpha = blink),
                                    modifier = Modifier
                                        .align(Alignment.CenterVertically)
                                        .size(18.dp),
                                )
                            }
                        }
                        Row {
                            Text(
                                text = stringResource(
                                    R.string.app_version,
                                    BuildConfig.VERSION_NAME,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.commands_sent, state.commandsSent),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics {
                                    contentDescription = commandsSentLabel
                                },
                            )
                        }
                    }
                },
                actions = {
                    // Il tocco resta di 48.dp, ma la cornice disegnata e piu
                    // stretta: tre icone in fila su una barra che ha gia due
                    // righe di titolo vogliono stare strette.
                    // Seguendo il registro, da qui non si aggiunge: un "+"
                    // che apre un modulo in sola lettura sarebbe una promessa
                    // che non viene mantenuta.
                    if (!registry.following) {
                        IconButton(
                            onClick = onAddDevice,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Default.Add, stringResource(R.string.action_add_device))
                        }
                    }
                    // Il lucchetto sta fra il "+" e l'ingranaggio: e l'unico
                    // comando della barra che cambia cosa fanno le schede
                    // sotto, e sta addosso a loro invece che in fondo.
                    // Aperto e chiuso sono due disegni diversi e non due tinte
                    // dello stesso: la barra si legge anche senza colore.
                    IconButton(
                        onClick = viewModel::toggleLock,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painter = painterResource(
                                if (state.locked) R.drawable.ic_lock_closed else R.drawable.ic_lock_open,
                            ),
                            contentDescription = stringResource(
                                if (state.locked) R.string.view_locked_unlock else R.string.view_locked_lock,
                            ),
                            modifier = Modifier.semantics {
                                stateDescription = lockStateLabel
                            },
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, stringResource(R.string.action_settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            ConnectionBanner(
                state = state.connection,
                onConfigure = onOpenSettings,
                onRetry = viewModel::reconnect,
            )

            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.devices.isEmpty() -> EmptyDevices(
                    onAddDevice = onAddDevice,
                    following = registry.following,
                )

                // Una scheda per riga, a tutta larghezza: il nome sta per
                // esteso e l'interruttore cade sotto il pollice invece che in
                // un angolo di una cella stretta.
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.devices, key = { it.device.id }) { item ->
                        DeviceCard(
                            // Un riordino arriva da fuori, mentre si sta
                            // guardando l'elenco: la chiave e' l'id, quindi
                            // Compose sa gia' quale scheda si e' spostata, e
                            // questa riga le fa scivolare invece di saltare.
                            modifier = Modifier.animateItem(),
                            item = item,
                            locked = state.locked,
                            onPower = { on -> viewModel.setPower(item.device, on) },
                            onLevel = { level -> viewModel.setLevel(item.device, level) },
                            onEdit = { onEditDevice(item.device) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Il banner compare solo quando c'e qualcosa da dire: a collegamento funzionante
 * una riga fissa "connesso" sarebbe solo rumore sopra i dispositivi.
 */
@Composable
private fun ConnectionBanner(
    state: ConnectionState,
    onConfigure: () -> Unit,
    onRetry: () -> Unit,
) {
    if (state is ConnectionState.Connected) return

    val failed = state is ConnectionState.Failed
    val background = if (failed) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val foreground = if (failed) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    val message = when (state) {
        ConnectionState.NotConfigured -> stringResource(R.string.connection_not_configured)
        ConnectionState.Connecting -> stringResource(R.string.connection_connecting)
        ConnectionState.Disconnected -> stringResource(R.string.connection_disconnected)
        is ConnectionState.Failed -> state.reason
        ConnectionState.Connected -> return
    }

    Surface(color = background, contentColor = foreground, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state is ConnectionState.Connecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = foreground,
                )
                Spacer(Modifier.size(12.dp))
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            when (state) {
                ConnectionState.NotConfigured -> TextButton(onClick = onConfigure) {
                    Text(stringResource(R.string.action_configure), color = foreground)
                }

                is ConnectionState.Failed, ConnectionState.Disconnected -> TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry), color = foreground)
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun EmptyDevices(onAddDevice: () -> Unit, following: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_device_switch),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(
                if (following) R.string.devices_empty_registry_title else R.string.devices_empty_title,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (following) R.string.devices_empty_registry_body else R.string.devices_empty_body,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Niente pulsante quando comanda il registro: non porterebbe da
        // nessuna parte.
        if (!following) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onAddDevice) { Text(stringResource(R.string.action_add_device)) }
        }
    }
}

/**
 * Quanto va tenuta premuta una scheda per aprire la configurazione. La pressione
 * prolungata di sistema (mezzo secondo) qui sarebbe troppo poco: un elenco di
 * schede si scorre tenendole sotto il dito, e mezzo secondo fermi prima di
 * partire capita per caso. Tre secondi no.
 */
private const val HOLD_TO_CONFIGURE_MS = 3_000L

@Composable
private fun DeviceCard(
    item: DeviceUi,
    locked: Boolean,
    onPower: (Boolean) -> Unit,
    onLevel: (Int) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = item.device
    // Due motivi, una risposta sola: la regola sta nel ViewModel perche' e'
    // l'unica cosa di questa feature che si possa provare senza un telefono.
    val commandable = item.commandable(locked)
    val lastKnownOn = item.state.power == true
    // Il verde dice "sta funzionando adesso", quindi non lo merita un
    // dispositivo che risulta acceso ma non risponde piu: li' l'ultimo stato e
    // un ricordo, e la scheda lo scrive a parole invece di colorarsi.
    val powered = lastKnownOn && !item.state.unreachable
    val green = poweredColors()
    val haptics = LocalHapticFeedback.current
    val configureLabel = stringResource(R.string.device_configure)
    // Il rilevatore del gesto non si riavvia a ogni ricomposizione, altrimenti
    // uno stato che arriva dal broker mentre si tiene premuto azzererebbe il
    // conteggio dei tre secondi: la chiave e' fissa e la lambda si aggiorna qui.
    val openConfig by rememberUpdatedState(onEdit)

    val content = if (powered) green.content else MaterialTheme.colorScheme.onSurface
    val secondary = if (powered) green.content.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
    val faded = if (powered) green.content.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline

    Card(
        modifier = modifier
            .fillMaxWidth()
            // La configurazione si apre tenendo premuta la scheda. Un tocco non
            // porta piu' da nessuna parte: era lo stesso gesto con cui si manca
            // l'interruttore, e finiva nel modulo di modifica invece che da
            // nessuna parte.
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Il tocco che parte dall'interruttore o dal cursore
                    // l'ha gia' consumato quel controllo, e qui non arriva:
                    // tenere premuto l'interruttore non deve aprire la
                    // configurazione e per giunta mandare un comando quando il
                    // dito si alza. Si guarda solo il corpo della scheda.
                    awaitFirstDown()
                    val held = try {
                        // Il dito che si alza chiude il blocco prima della
                        // scadenza; lo scorrimento dell'elenco si prende gli
                        // eventi e lo fa chiudere lo stesso. Si apre solo se a
                        // arrivare per prima e' la scadenza.
                        withTimeout(HOLD_TO_CONFIGURE_MS) { waitForUpOrCancellation() }
                        false
                    } catch (_: PointerEventTimeoutCancellationException) {
                        true
                    }
                    if (held) {
                        // Tre secondi senza un segnale sembrano un'app ferma:
                        // la vibrazione dice che il gesto e' arrivato, e arriva
                        // mentre il dito e' ancora giu'.
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        openConfig()
                    }
                }
            }
            // Il gesto vive dentro pointerInput, dove TalkBack non lo vede:
            // questa riga lo dichiara come azione di pressione prolungata, che
            // il lettore esegue subito senza contare tre secondi.
            .semantics {
                onLongClick(label = configureLabel) {
                    openConfig()
                    true
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = if (powered) {
                green.container
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = content,
        ),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(device.kind.iconRes),
                    contentDescription = stringResource(device.kind.labelRes),
                    tint = if (powered) green.content else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (device.room.isNotBlank()) {
                        Text(
                            text = device.room,
                            style = MaterialTheme.typography.bodyMedium,
                            color = secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = statusLine(item),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (item.state.known && !item.state.unreachable) secondary else faded,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // I consumi su una riga loro: non sono lo stato di adesso, e
                    // appiccicarli alla riga dello stato la rende illeggibile
                    // proprio sulle schede che hanno piu da dire.
                    energyLine(item)?.let { consumi ->
                        // Quello che si legge e quello che si sente sono due cose
                        // diverse, ed e la sola concessione che la riga compatta
                        // si puo permettere. Sullo schermo l'ordine delle caselle
                        // basta, perche si vede; in un lettore di schermo le
                        // barre non si sentono, e "zero virgola quarantadue
                        // barra uno virgola ottantasette" non lo capirebbe
                        // nessuno. Le etichette qui non costano una riga.
                        val parlato = energySpoken(item)
                        Text(
                            text = consumi,
                            style = MaterialTheme.typography.bodySmall,
                            color = faded,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (parlato == null) {
                                Modifier
                            } else {
                                Modifier.semantics { contentDescription = parlato }
                            },
                        )
                    }
                }
                if (device.controllable) {
                    Spacer(Modifier.width(12.dp))
                    // Su un dispositivo che ha dichiarato di non esserci
                    // l'interruttore non si muove: il comando non arriverebbe a
                    // nessuno, e vederlo scattare racconterebbe un'accensione
                    // che non e avvenuta. La posizione mostrata resta l'ultima
                    // saputa, come il testo qui accanto.
                    // A vista bloccata vale lo stesso ragionamento con un
                    // secondo motivo: il comando non lo vogliamo mandare, e
                    // un interruttore che scatta e torna indietro sembrerebbe
                    // un guasto invece di un blocco.
                    Switch(
                        checked = lastKnownOn,
                        onCheckedChange = onPower,
                        enabled = commandable,
                    )
                }
            }

            if (device.dimmable) {
                // La chiave su level fa ripartire il cursore quando arriva un
                // valore nuovo dal dispositivo, senza combattere con il dito
                // dell'utente mentre trascina.
                var slider by remember(item.state.level) {
                    mutableFloatStateOf((item.state.level ?: 0).toFloat())
                }
                Slider(
                    value = slider,
                    onValueChange = { slider = it },
                    onValueChangeFinished = { onLevel(slider.roundToInt()) },
                    valueRange = 0f..100f,
                    enabled = commandable,
                )
            }
        }
    }
}

@Composable
private fun statusLine(item: DeviceUi): String {
    val state = item.state
    // Chi dichiara di non esserci viene prima di tutto il resto: una presa
    // irraggiungibile che mostra "Acceso" e' esattamente la bugia che l'app si
    // e' sempre rifiutata di dire, e "Comando inviato" su un dispositivo che
    // non c'e' e' un'attesa che non finira' mai.
    if (state.unreachable) {
        val last = lastKnown(item)
        return if (last.isNullOrBlank()) {
            stringResource(R.string.state_unreachable)
        } else {
            stringResource(R.string.state_unreachable_last, last)
        }
    }
    if (state.pending) return stringResource(R.string.state_pending)
    if (!state.known) return stringResource(R.string.state_unknown)

    if (item.device.kind == DeviceKind.SENSOR) return state.raw.orEmpty()

    val power = powerLabel(item)
    val level = state.level?.let { stringResource(R.string.state_level, it) }
    // I watt di un dispositivo che si sa spento non si mostrano: a relay aperto
    // sono zero per forza, e "Spento · 0 W" non aggiunge niente a "Spento". Su
    // uno acceso invece dicono la cosa che l'interruttore da solo non dice —
    // la lavastoviglie e alimentata, ma sta lavorando?
    val watts = state.watts
        ?.takeIf { state.power != false }
        ?.let { stringResource(R.string.state_power, formatWatts(it)) }
    return listOfNotNull(power.takeIf { it.isNotBlank() }, level, watts).joinToString(" · ")
}

/**
 * I kWh accumulati, quando qualcuno li conta: `0,42/1,87/6,30/12,7 kWh`.
 *
 * Quattro caselle in ordine fisso — oggi, ieri, settimana, mese — e nessuna
 * etichetta. L'ordine si impara una volta e poi non si legge piu: si guarda se
 * il primo numero e piu grande del secondo, che e la domanda vera. Una casella
 * di cui non si sa niente porta un trattino e **resta al suo posto**.
 *
 * La regola vive in [energiaCompatta], che non e una `@Composable` apposta: e
 * la parte che si prova per casi.
 */
@Composable
private fun energyLine(item: DeviceUi): String? {
    val consumi = energiaCompatta(item.state, stringResource(R.string.state_energy_missing))
        ?: return null
    return stringResource(R.string.state_energy, consumi)
}

/** Gli stessi quattro numeri, con le etichette, per chi la scheda se la fa leggere. */
@Composable
private fun energySpoken(item: DeviceUi): String? {
    val valori = consumiFormattati(item.state) ?: return null
    val ignoto = stringResource(R.string.state_energy_missing_spoken)
    return stringResource(
        R.string.state_energy_spoken,
        valori[0] ?: ignoto,
        valori[1] ?: ignoto,
        valori[2] ?: ignoto,
        valori[3] ?: ignoto,
    )
}

/**
 * Sotto i dieci watt il decimale conta: fra 0,0 e 3,0 W passa la differenza fra
 * un elettrodomestico spento davvero e uno in attesa, ed e proprio quella che si
 * va a guardare. Sopra, sarebbe una cifra che balla a ogni lettura.
 */
private fun formatWatts(watts: Double): String =
    String.format(Locale.getDefault(), if (watts < 10) "%.1f" else "%.0f", watts)

/** L'ultimo stato ricevuto, da mostrare come ricordo e non come fatto presente. */
@Composable
private fun lastKnown(item: DeviceUi): String? {
    if (!item.state.known) return null
    if (item.device.kind == DeviceKind.SENSOR) return item.state.raw
    return powerLabel(item)
}

@Composable
private fun powerLabel(item: DeviceUi): String = when (item.state.power) {
    true -> stringResource(R.string.device_state_on)
    false -> stringResource(R.string.device_state_off)
    null -> item.state.raw.orEmpty()
}
