package it.agoldoni.smarthome.driver.mqtt

import org.eclipse.paho.client.mqttv3.MqttException
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Dallo sbaglio che arriva da Paho alle due righe che servono: quella corta per
 * lo schermo, quella lunga per il registro di debug.
 *
 * Sono due lettori diversi. Sopra l'elenco dei dispositivi c'e chi vuole sapere
 * se conviene alzarsi e andare a vedere il broker, e un "failed to connect to
 * /192.168.86.45 (port 1883) from /192.168.86.7 (port 47882) after 10000ms:
 * isConnected failed: ECONNREFUSED (Connection refused)" la risposta ce l'ha
 * dentro, ma sepolta sotto due indirizzi, una porta d'origine che cambia a ogni
 * tentativo e tre modi di dire la stessa cosa. Nel registro invece quel testo si
 * tiene per intero: li il dettaglio e il motivo per cui si sta guardando.
 *
 * Oggetto a parte e non metodo privato del driver perche cosi resta una funzione
 * pura, e le funzioni pure di questo package hanno dei test (vedi [MqttTopics]).
 */
internal object MqttFailures {

    /** La riga da mettere in ConnectionState.Failed: poche parole, senza indirizzi. */
    fun describe(error: Throwable?): String {
        val chain = chain(error)
        // Prima il protocollo, che sa dire il perche vero (le credenziali non le
        // rifiuta un socket); poi la rete, dove sta il motivo di quasi tutte le
        // cadute; il codice MQTT nudo solo se non e rimasto altro.
        return chain.firstNotNullOfOrNull(::protocol)
            ?: chain.firstNotNullOfOrNull(::network)
            ?: chain.firstNotNullOfOrNull(::residual)
            ?: LOST
    }

    /** La riga per il registro: tutta la catena delle cause, com'e arrivata. */
    fun detail(error: Throwable?): String {
        val chain = chain(error)
        if (chain.isEmpty()) return "nessun dettaglio"
        return chain.joinToString(" <- ") { step ->
            val code = if (step is MqttException) " ${step.reasonCode}" else ""
            "${step::class.java.simpleName}$code: ${step.message?.trim() ?: "senza messaggio"}"
        }
    }

    /** Il broker ha risposto e ha detto di no: qui il codice MQTT e la verita. */
    private fun protocol(error: Throwable): String? {
        if (error !is MqttException) return null
        return when (error.reasonCode) {
            MqttException.REASON_CODE_INVALID_PROTOCOL_VERSION.toInt() -> "Versione MQTT non accettata"
            MqttException.REASON_CODE_INVALID_CLIENT_ID.toInt() -> "Client id rifiutato dal broker"
            MqttException.REASON_CODE_BROKER_UNAVAILABLE.toInt() -> "Broker non disponibile"
            MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() -> "Credenziali rifiutate"
            MqttException.REASON_CODE_NOT_AUTHORIZED.toInt() -> "Non autorizzato dal broker"
            MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt() -> "Il broker non risponde"
            else -> null
        }
    }

    /** Il broker non ha risposto affatto: il tipo dell'eccezione dice cosa manca. */
    private fun network(error: Throwable): String? = when (error) {
        is UnknownHostException -> "Indirizzo del broker sconosciuto"
        is SocketTimeoutException -> "Il broker non risponde"
        is NoRouteToHostException -> "Broker fuori dalla rete"
        is PortUnreachableException -> "Porta del broker chiusa"
        // ECONNREFUSED e ETIMEDOUT arrivano nella stessa classe e sono due guai
        // diversi: rifiutata vuol dire che l'indirizzo e giusto e il broker no.
        is ConnectException -> when {
            error.mentions("ECONNREFUSED") -> "Connessione rifiutata"
            error.mentions("ETIMEDOUT") -> "Il broker non risponde"
            else -> "Broker non raggiungibile"
        }

        is SSLException -> "Errore TLS con il broker"
        is EOFException -> "Il broker ha chiuso il collegamento"
        is SocketException -> "Collegamento interrotto"
        else -> null
    }

    /**
     * Quel che resta. Il testo che Paho tiene nel suo catalogo e sempre la
     * stessa frase generica per tutta una famiglia di guai ("Impossibile
     * effettuare la connessione al server"): il codice e piu corto e almeno si
     * puo cercare.
     */
    private fun residual(error: Throwable): String? = when {
        error is MqttException -> when (error.reasonCode) {
            MqttException.REASON_CODE_CONNECTION_LOST.toInt() -> LOST
            MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt() -> "Broker non raggiungibile"
            MqttException.REASON_CODE_CLIENT_NOT_CONNECTED.toInt() -> "Non collegato al broker"
            else -> "Errore MQTT ${error.reasonCode}"
        }

        else -> error.message?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }
            ?: error::class.java.simpleName
    }

    /** Causa dopo causa, con la guardia per gli anelli: un ciclo qui bloccherebbe l'app. */
    private fun chain(error: Throwable?): List<Throwable> {
        val steps = mutableListOf<Throwable>()
        var current = error
        while (current != null && steps.size < MAX_DEPTH && steps.none { it === current }) {
            steps += current
            current = current.cause
        }
        return steps
    }

    private fun Throwable.mentions(token: String) = message?.contains(token, ignoreCase = true) == true

    private const val LOST = "Connessione persa"

    /** Oltre questo, il messaggio di sistema non e piu una riga ma un paragrafo. */
    private const val MAX_LENGTH = 60

    private const val MAX_DEPTH = 5
}
