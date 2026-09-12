package it.agoldoni.smarthome.driver.mqtt

import org.eclipse.paho.client.mqttv3.MqttException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException

/**
 * La riga che finisce sopra l'elenco dei dispositivi. Il caso che ha fatto
 * nascere questa classe e il primo: il messaggio di sistema per ECONNREFUSED e
 * lungo una riga e mezza di terminale.
 */
class MqttFailuresTest {

    /** Testuale da un telefono vero, letto dall'API di debug. */
    private val refused = ConnectException(
        "failed to connect to /192.168.86.45 (port 1883) from /192.168.86.7 (port 47882) " +
            "after 10000ms: isConnected failed: ECONNREFUSED (Connection refused)",
    )

    @Test
    fun `il broker spento diventa una riga corta`() {
        val error = MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt(), refused)
        assertEquals("Connessione rifiutata", MqttFailures.describe(error))
    }

    @Test
    fun `nessun messaggio mostrato porta indirizzi o porte`() {
        val error = MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt(), refused)
        val shown = MqttFailures.describe(error)
        assertTrue(shown.length <= 40)
        assertTrue(shown.none { it.isDigit() })
    }

    @Test
    fun `il rifiuto del broker vince sulla rete`() {
        val error = MqttException(MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt())
        assertEquals("Credenziali rifiutate", MqttFailures.describe(error))
    }

    @Test
    fun `l'host che non si risolve si distingue dal broker spento`() {
        val error = MqttException(
            MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt(),
            UnknownHostException("Unable to resolve host \"casa.local\""),
        )
        assertEquals("Indirizzo del broker sconosciuto", MqttFailures.describe(error))
    }

    @Test
    fun `senza causa resta il codice, non la frase generica di Paho`() {
        val error = MqttException(32105)
        assertEquals("Errore MQTT 32105", MqttFailures.describe(error))
    }

    @Test
    fun `il collegamento caduto senza causa ha il suo messaggio`() {
        assertEquals("Connessione persa", MqttFailures.describe(null))
        assertEquals(
            "Connessione persa",
            MqttFailures.describe(MqttException(MqttException.REASON_CODE_CONNECTION_LOST.toInt())),
        )
    }

    @Test
    fun `il dettaglio per il registro tiene tutto`() {
        val error = MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt(), refused)
        val detail = MqttFailures.detail(error)
        assertTrue(detail.contains("32103"))
        assertTrue(detail.contains("ECONNREFUSED"))
        assertTrue(detail.contains("192.168.86.45"))
    }

    @Test
    fun `una catena che gira su se stessa non blocca`() {
        val first = MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION.toInt())
        val second = RuntimeException("giro", first)
        first.initCause(second)
        assertTrue(MqttFailures.detail(first).isNotEmpty())
    }
}
