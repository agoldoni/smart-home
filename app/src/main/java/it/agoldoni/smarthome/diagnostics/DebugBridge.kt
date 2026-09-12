package it.agoldoni.smarthome.diagnostics

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import it.agoldoni.smarthome.BuildConfig
import it.agoldoni.smarthome.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/**
 * L'API di debug: scoperta in rete locale piu qualche GET che raccontano com'e
 * messa l'app dentro.
 *
 * C'e in tutte le build, release compresa: i guai che vale la pena guardare da
 * dentro capitano sull'app che si usa davvero, e tenere lo strumento solo nella
 * debug vuol dire non averlo mai quando serve.
 *
 * Quello che lo rende accettabile in release non e l'assenza ma i paletti, tutti
 * insieme: parte spento a ogni installazione, si accende a mano, risponde solo
 * in lettura e solo a indirizzi privati, non dice mai la password del broker, e
 * finche resta acceso la barra del titolo porta un segno rosso — una cosa del
 * genere lasciata accesa per distrazione deve darsi fastidio da sola.
 *
 * Il registro [DiagnosticsLog] invece parte con l'app e non con l'interruttore:
 * altrimenti, accendendo l'API dopo che qualcosa e andato storto, si troverebbe
 * un registro vuoto proprio degli istanti interessanti, quelli dell'avvio.
 */
object DebugBridge {

    /** Stessa porta per il TCP dell'API e per l'UDP della scoperta: protocolli diversi, un numero solo da ricordare. */
    const val PORT: Int = 8787

    private val _status = MutableStateFlow(DebugStatus())
    val status: StateFlow<DebugStatus> = _status.asStateFlow()

    /** Aprire e chiudere socket non si fa sul thread principale, nemmeno per scherzo. */
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "debug-bridge").apply { isDaemon = true }
    }

    private var appContext: Context? = null
    private var report: DebugReport? = null
    private var http: DebugHttpServer? = null
    private var discovery: DebugDiscovery? = null

    fun install(context: Context, container: AppContainer) {
        appContext = context.applicationContext
        report = DebugReport(container, PORT)
        DiagnosticsLog.start()
        DiagnosticsLog.event(
            "app",
            "avvio ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME}",
        )
        // SharedPreferences e non DataStore: qui serve leggere un booleano
        // adesso, in onCreate, e il DataStore risponde solo dentro una coroutine.
        val remembered = prefs().getBoolean(KEY_ENABLED, false)
        _status.update { it.copy(enabled = remembered) }
        if (remembered) worker.execute { open() }
    }

    fun setEnabled(enabled: Boolean) {
        prefs().edit { putBoolean(KEY_ENABLED, enabled) }
        _status.update { it.copy(enabled = enabled, error = null) }
        DiagnosticsLog.event("debug", if (enabled) "API di debug accesa" else "API di debug spenta")
        worker.execute { if (enabled) open() else close() }
    }

    private fun open() {
        close()
        val currentReport = report ?: return
        val context = appContext ?: return
        try {
            val server = DebugHttpServer(PORT) { currentReport.handle(it) }
            server.start()
            http = server

            val udp = DebugDiscovery(context, PORT) { kind ->
                // L'indirizzo cambia quando cambia la rete. L'annuncio e gia un
                // giro periodico: tanto vale tenerci allineata la riga mostrata
                // nelle impostazioni, invece di lasciarla su un IP di ieri.
                refreshEndpoint()
                currentReport.descriptor(kind)
            }
            udp.start()
            discovery = udp

            refreshEndpoint()
            _status.update { it.copy(running = true, error = null) }
            DiagnosticsLog.event("debug", "in ascolto su ${_status.value.endpoint ?: "porta $PORT"}")
        } catch (e: Throwable) {
            close()
            val reason = e.message ?: e::class.java.simpleName
            _status.update { it.copy(running = false, endpoint = null, error = reason) }
            DiagnosticsLog.event("debug", "avvio fallito: $reason")
        }
    }

    private fun close() {
        http?.stop()
        http = null
        discovery?.stop()
        discovery = null
        _status.update { it.copy(running = false, endpoint = null) }
    }

    private fun refreshEndpoint() {
        val endpoint = localAddresses().firstOrNull()?.let { "http://$it:$PORT" }
        _status.update { if (it.endpoint == endpoint) it else it.copy(endpoint = endpoint) }
    }

    private fun prefs(): SharedPreferences =
        requireNotNull(appContext).getSharedPreferences("debug-api", Context.MODE_PRIVATE)

    private const val KEY_ENABLED = "enabled"
}
