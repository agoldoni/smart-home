package it.agoldoni.smarthome.diagnostics

/** Un fatto accaduto dentro l'app, con il momento in cui e accaduto. */
data class DiagnosticEvent(
    val at: Long,
    val category: String,
    val message: String,
)

/** Un messaggio MQTT passato di qui, in entrata o in uscita. */
data class DiagnosticMessage(
    val at: Long,
    val incoming: Boolean,
    val topic: String,
    val payload: String,
    /** Quanti dispositivi registrati hanno riconosciuto il topic. Zero e il caso interessante. */
    val matched: Int = 0,
    val note: String? = null,
)

/** Quanto e arrivato su un topic e quando, anche se l'anello dei messaggi e gia girato. */
data class TopicStat(
    val count: Long,
    val lastAt: Long,
    val lastPayload: String,
)

/**
 * Registro circolare di quello che succede dentro l'app, letto dall'API di debug.
 *
 * Due anelli separati e non uno solo: le sette prese pubblicano corrente, potenza
 * e tensione ogni paio di secondi, e un anello unico sarebbe lavato dal traffico
 * MQTT prima che qualcuno faccia in tempo a leggere perche il collegamento e
 * caduto. Gli eventi rari stanno al riparo da quelli fitti.
 *
 * [enabled] lo accende il ponte di debug, che esiste solo nella build debug: in
 * release ogni chiamata qui dentro e un confronto booleano e niente altro.
 */
object DiagnosticsLog {

    @Volatile
    var enabled: Boolean = false

    /** Momento in cui il registro e stato acceso, cioe l'avvio del processo. */
    @Volatile
    var startedAt: Long = 0L
        private set

    private val lock = Any()
    private val events = ArrayDeque<DiagnosticEvent>()
    private val messages = ArrayDeque<DiagnosticMessage>()
    private val topics = LinkedHashMap<String, TopicStat>()
    private val counters = LinkedHashMap<String, Long>()

    fun start() {
        synchronized(lock) {
            startedAt = System.currentTimeMillis()
            enabled = true
        }
    }

    fun event(category: String, message: String) {
        if (!enabled) return
        synchronized(lock) {
            events.addLast(DiagnosticEvent(System.currentTimeMillis(), category, message))
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    fun incoming(topic: String, payload: String, matched: Int) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val short = truncate(payload)
        synchronized(lock) {
            messages.addLast(DiagnosticMessage(now, incoming = true, topic = topic, payload = short, matched = matched))
            while (messages.size > MAX_MESSAGES) messages.removeFirst()
            val seen = topics[topic]
            // Con le wildcard i topic distinti possono essere tanti: oltre il tetto
            // si butta il piu vecchio inserito, che e comunque piu di quanto
            // direbbe un contatore unico.
            if (seen == null && topics.size >= MAX_TOPICS) {
                topics.remove(topics.keys.first())
            }
            topics[topic] = TopicStat(
                count = (seen?.count ?: 0L) + 1L,
                lastAt = now,
                lastPayload = short,
            )
            bump(COUNTER_RECEIVED, 1L)
        }
    }

    fun outgoing(topic: String, payload: String, note: String? = null) {
        if (!enabled) return
        synchronized(lock) {
            messages.addLast(
                DiagnosticMessage(
                    at = System.currentTimeMillis(),
                    incoming = false,
                    topic = topic,
                    payload = truncate(payload),
                    note = note,
                ),
            )
            while (messages.size > MAX_MESSAGES) messages.removeFirst()
        }
    }

    fun count(name: String, delta: Long = 1L) {
        if (!enabled) return
        synchronized(lock) { bump(name, delta) }
    }

    /** Gli ultimi [limit] eventi piu recenti di [since], dal piu vecchio al piu nuovo. */
    fun events(limit: Int = MAX_EVENTS, since: Long = 0L): List<DiagnosticEvent> =
        synchronized(lock) { events.filter { it.at > since }.takeLast(limit) }

    fun messages(limit: Int = MAX_MESSAGES, since: Long = 0L): List<DiagnosticMessage> =
        synchronized(lock) { messages.filter { it.at > since }.takeLast(limit) }

    fun topics(): Map<String, TopicStat> = synchronized(lock) { LinkedHashMap(topics) }

    fun counters(): Map<String, Long> = synchronized(lock) { LinkedHashMap(counters) }

    /** Da chiamare con [lock] gia preso. */
    private fun bump(name: String, delta: Long) {
        counters[name] = (counters[name] ?: 0L) + delta
    }

    private fun truncate(payload: String): String =
        if (payload.length <= MAX_PAYLOAD) payload else payload.take(MAX_PAYLOAD) + "... (${payload.length} caratteri)"

    const val COUNTER_RECEIVED = "messaggi.ricevuti"
    const val COUNTER_PUBLISHED = "messaggi.pubblicati"
    const val COUNTER_PUBLISH_FAILED = "messaggi.pubblicazioni_fallite"
    const val COUNTER_CONNECT_ATTEMPT = "collegamento.tentativi"
    const val COUNTER_CONNECT_OK = "collegamento.riusciti"
    const val COUNTER_CONNECT_FAILED = "collegamento.falliti"
    const val COUNTER_CONNECT_LOST = "collegamento.caduti"
    const val COUNTER_SUBSCRIBE = "sottoscrizioni.aggiunte"
    const val COUNTER_UNSUBSCRIBE = "sottoscrizioni.tolte"

    private const val MAX_EVENTS = 200
    private const val MAX_MESSAGES = 200
    private const val MAX_TOPICS = 64
    private const val MAX_PAYLOAD = 512
}
