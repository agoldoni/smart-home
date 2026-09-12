# Fase 2 — Analisi tecnica: configuratore web e registro condiviso

**Feature:** `002-configuratore-web`
**Data:** 12 settembre 2026
**Base:** [phase-1-requirements.md](phase-1-requirements.md)

Tutti i percorsi e i numeri di riga qui sotto sono stati letti, non ricordati. Dove una
cosa non esiste è scritto che non esiste.

---

## A. File coinvolti

### Stack e broker

| File | Modifica | Perché |
|---|---|---|
| `bridge/mosquitto/config/mosquitto.conf` | modifica | Oggi ha un solo `listener 1883`. Servono `listener 9001` e `protocol websockets`: senza, nessun browser può collegarsi. `allow_anonymous false` e `password_file` sono globali e valgono già per entrambi |
| `bridge/compose.yml` | modifica | Il servizio `broker` espone solo `1883:1883`. Va aggiunto `9001:9001` e va aggiunto il servizio `configuratore` |
| `bridge/configuratore/Dockerfile` | **nuovo** | `nginx:alpine` + i file statici + il client MQTT JS. Un `FROM` e due `COPY` |
| `bridge/configuratore/nginx.conf` | **nuovo** | Serve la cartella, niente altro. Nessun proxy: il browser parla col broker da sé |
| `bridge/.env.example` | modifica | `CONFIGURATORE_PORT=8080`. ✅ **Deciso il 12/09/2026:** la porta è una variabile d'ambiente, non una costante in compose — stessa forma di `WG_PORT` |

### La web app (tutto nuovo)

| File | Perché |
|---|---|
| `bridge/configuratore/web/index.html` | La pagina: collegamento, elenco, modulo |
| `bridge/configuratore/web/app.js` | Collegamento MQTT, lettura e scrittura del registro, revisione e conflitto |
| `bridge/configuratore/web/modulo.js` | I 18 campi del modulo e la validazione, la stessa di `DeviceEditViewModel` |
| `bridge/configuratore/web/modelli.js` | Ponte, Tasmota, Zigbee2MQTT |
| `bridge/configuratore/web/stile.css` | |
| `bridge/configuratore/web/vendor/mqtt.min.js` | Il client, dentro l'immagine e non da CDN |

### L'app Android

| File | Modifica | Perché |
|---|---|---|
| `app/src/main/java/it/agoldoni/smarthome/domain/model/Device.kt` | modifica | `uuid: String` nuovo. È un `data class` con default ovunque ([Device.kt:28](../../../app/src/main/java/it/agoldoni/smarthome/domain/model/Device.kt#L28)), quindi un parametro con default non rompe nessuna chiamata esistente |
| `app/src/main/java/it/agoldoni/smarthome/domain/registry/DeviceRegistry.kt` | **nuovo** | Il documento del registro come tipo, e le funzioni pure: parsing, rifiuto, diff, adozione |
| `app/src/main/java/it/agoldoni/smarthome/data/local/DeviceEntity.kt` | modifica | Colonna `uuid` e i due mapper |
| `app/src/main/java/it/agoldoni/smarthome/data/local/SmartHomeDatabase.kt` | modifica | `version = 5` e `MIGRATION_4_5`, scritta a mano come le tre che ci sono |
| `app/schemas/…/5.json` | **nuovo (generato)** | `exportSchema = true` e `room.schemaLocation` sono già configurati ([build.gradle.kts:79](../../../app/build.gradle.kts#L79)): il file nasce da solo e va committato |
| `app/src/main/java/it/agoldoni/smarthome/data/local/DeviceDao.kt` | modifica | Oggi ha `insert`/`update`/`delete(id)` singoli. Serve un `@Transaction` che applichi un registro intero |
| `app/src/main/java/it/agoldoni/smarthome/data/DeviceRepository.kt` | modifica | Un `applyRegistry()` accanto a `save()`/`delete()` |
| `app/src/main/java/it/agoldoni/smarthome/data/settings/RegistryStore.kt` | **nuovo** | Segui/non seguire, ultima revisione applicata, quando. **Non** dentro `BrokerSettings`: vedi il rischio E-2 |
| `app/src/main/java/it/agoldoni/smarthome/domain/driver/DeviceDriver.kt` | modifica | Un modo di iscriversi a un topic che non appartiene a nessun dispositivo |
| `app/src/main/java/it/agoldoni/smarthome/driver/mqtt/MqttDeviceDriver.kt` | modifica | Sottoscrizione di sistema in `syncSubscriptions()` e intercettazione in `onMessage()` |
| `app/src/main/java/it/agoldoni/smarthome/di/AppContainer.kt` | modifica | `MIGRATION_4_5` nell'elenco ([AppContainer.kt:39](../../../app/src/main/java/it/agoldoni/smarthome/di/AppContainer.kt#L39)) e il collegamento fra registro ricevuto e repository, accanto a quello che già passa i dispositivi al driver ([AppContainer.kt:63](../../../app/src/main/java/it/agoldoni/smarthome/di/AppContainer.kt#L63)) |
| `app/src/main/java/it/agoldoni/smarthome/ui/devices/DeviceEditScreen.kt` | modifica | Sola lettura quando l'app segue il registro: campi disabilitati, niente spunta di salvataggio ([riga 90](../../../app/src/main/java/it/agoldoni/smarthome/ui/devices/DeviceEditScreen.kt#L90)), niente cestino ([riga 86](../../../app/src/main/java/it/agoldoni/smarthome/ui/devices/DeviceEditScreen.kt#L86)) |
| `app/src/main/java/it/agoldoni/smarthome/ui/devices/DeviceEditViewModel.kt` | modifica | Un `readOnly` nello stato; `save()` e `delete()` non devono poter partire |
| `app/src/main/java/it/agoldoni/smarthome/ui/devices/DeviceListScreen.kt` | modifica | Il pulsante Aggiungi sparisce quando il registro comanda |
| `app/src/main/java/it/agoldoni/smarthome/ui/devices/DeviceListViewModel.kt` | modifica | Lo stato del registro arriva nell'UI state |
| `app/src/main/java/it/agoldoni/smarthome/ui/settings/BrokerSettingsScreen.kt` | modifica | Sezione "Registro": interruttore, revisione, ora. Il posto c'è già, è come `DebugApiSection` ([riga 181](../../../app/src/main/java/it/agoldoni/smarthome/ui/settings/BrokerSettingsScreen.kt#L181)) |
| `app/src/main/java/it/agoldoni/smarthome/ui/settings/BrokerSettingsViewModel.kt` | modifica | Legge e scrive il `RegistryStore` |
| `app/src/main/res/values/strings.xml` | modifica | Nessun testo è scritto nel codice: 94 stringhe già lì, le nuove vanno qui |
| `app/src/main/java/it/agoldoni/smarthome/diagnostics/DriverDiagnostics.kt` | modifica | `DriverSnapshot` con lo stato del registro |
| `app/src/main/java/it/agoldoni/smarthome/diagnostics/DebugReport.kt` | modifica | `/state`, `/devices` e le anomalie ([riga 215](../../../app/src/main/java/it/agoldoni/smarthome/diagnostics/DebugReport.kt#L215)) |
| `app/src/test/java/it/agoldoni/smarthome/domain/registry/DeviceRegistryTest.kt` | **nuovo** | Vedi sezione D |

### Documentazione

`README.md`, `bridge/README.md`, e lo schema del documento dentro `bridge/configuratore/`.

**Nessuna eliminazione.** Nessun file sparisce: il modulo di registrazione resta e diventa
condizionale, perché finché un registro non è mai arrivato l'app deve funzionare come oggi.

---

## B. Contratti e interfacce da modificare

### B.1 — Il documento del registro (contratto nuovo)

Topic `<prefisso>/registro/dispositivi`, ritenuto, QoS 1. Il prefisso è configurabile e
vale `casa` di norma — lo stesso dei dispositivi che il registro descrive:

```json
{
  "schema": 1,
  "revisione": 12,
  "aggiornato": "2026-09-12T21:04:33+02:00",
  "dispositivi": [
    {
      "uuid": "8f1c…", "nome": "frigorifero", "tipo": "SWITCH",
      "topic_stato": "casa/frigorifero/stato",     "campo_stato": "stato",
      "topic_comando": "casa/frigorifero/comando", "payload_on": "ON", "payload_off": "OFF",
      "campo_potenza": "potenza_w",
      "topic_energia": "casa/frigorifero/energia", "campo_kwh_oggi": "kwh_oggi", "campo_kwh_mese": "kwh_mese",
      "topic_disponibilita": "casa/frigorifero/disponibilita",
      "payload_disponibile": "online", "payload_non_disponibile": "offline",
      "topic_stato_livello": null, "topic_comando_livello": null, "campo_livello": null,
      "livello_max": 100, "qos": 1, "ritenuto": false
    }
  ]
}
```

Regole, che sono la parte che conta più dei nomi dei campi:

| Situazione | Comportamento |
|---|---|
| `schema` maggiore di quello conosciuto | **rifiuto in blocco**, si tiene il registro precedente |
| Payload non JSON, o senza `dispositivi` | rifiuto in blocco |
| Campo sconosciuto dentro un dispositivo noto | ignorato |
| `tipo` sconosciuto | quel dispositivo saltato, gli altri applicati |
| `uuid` mancante o duplicato | quel dispositivo saltato |
| `revisione` minore o uguale a quella già applicata | ignorato senza rumore |
| Campo `stanza` | non c'è. ✅ **Deciso il 12/09/2026:** non serve ora, e `room` resta un campo locale che il registro non tocca |

La regola generale non è nuova: è la stessa di `readAvailability`, che restituisce `null`
per un payload che non ha capito invece di dedurne un'assenza
([MqttPayloads.kt:72](../../../app/src/main/java/it/agoldoni/smarthome/driver/mqtt/MqttPayloads.kt#L72)),
ed è coperta da un test che si chiama *«un payload incomprensibile non significa assente»*.

**Nomi italiani in snake_case**, non i nomi Kotlin: il documento è un contratto fra una
pagina web e un'app, non la serializzazione di una classe. Ed è la convenzione che il ponte
usa già in ogni payload che l'app legge — `potenza_w`, `kwh_oggi`, `kwh_mese`, `tensione_v`,
`corrente_ma` ([bridge.py](../../../bridge/tuya-mqtt/bridge.py)) — quindi `campo_kwh_oggi`
che vale `kwh_oggi` resta in una convenzione sola, mentre `campoKwhOggi` ne avrebbe messe
due nella stessa riga.

> **Deciso il 12 settembre 2026.** In prima stesura questa sezione proponeva camelCase
> italiano; snake_case è coerente con i payload del ponte. Vale la regola di F-8: dopo la
> prima pubblicazione cambiare questi nomi costa uno `schema: 2` e un lettore che sappia
> leggere entrambi.

### B.2 — `Device`: un campo nuovo, nessuna rottura

`id: Long` ([Device.kt:29](../../../app/src/main/java/it/agoldoni/smarthome/domain/model/Device.kt#L29)) è
un autoincrement locale e **non può** essere l'identità nel registro: due telefoni
assegnerebbero numeri diversi alle stesse prese. Serve `uuid: String`.

`Device` è un `data class` con un default su ogni parametro tranne `name` e `stateTopic`:
aggiungere `val uuid: String = ""` non tocca nessuna chiamata esistente, compresi i quattro
test che costruiscono `Device(name = …, stateTopic = …)`
([MqttPayloadsTest.kt:113](../../../app/src/test/java/it/agoldoni/smarthome/driver/mqtt/MqttPayloadsTest.kt#L113)).

`id` resta la chiave primaria e resta la chiave di `states`. Questo non è un dettaglio: vedi E-1.

### B.3 — `DeviceDao` e `DeviceRepository`: manca l'operazione che serve

Oggi il DAO ha `insert`, `update`, `delete(id)`, tutti su un dispositivo alla volta, e il
repository decide fra insert e update guardando `device.id == 0L`
([DeviceRepository.kt](../../../app/src/main/java/it/agoldoni/smarthome/data/DeviceRepository.kt)).
Applicare un registro **non può** passare da lì: sono N operazioni separate, ognuna delle
quali fa emettere il `Flow` di `observeAll()`, e ogni emissione arriva fino a
`driver.track()` ([AppContainer.kt:66](../../../app/src/main/java/it/agoldoni/smarthome/di/AppContainer.kt#L66)).
Sette prese applicate una per una farebbero sette giri di risottoscrizione.

Serve:

```kotlin
@Query("SELECT * FROM devices WHERE uuid = :uuid")  suspend fun findByUuid(uuid: String): DeviceEntity?
@Query("SELECT * FROM devices WHERE uuid = ''")     suspend fun findWithoutUuid(): List<DeviceEntity>
@Transaction suspend fun applyRegistry(…)           // inserimenti, aggiornamenti e rimozioni in un colpo solo
```

### B.4 — `DeviceDriver`: il buco da riempire

L'interfaccia ha `track(devices)`, `send(...)`, `reconnect()`
([DeviceDriver.kt](../../../app/src/main/java/it/agoldoni/smarthome/domain/driver/DeviceDriver.kt)),
e tutte le sottoscrizioni nascono dai dispositivi. Il registro è un topic che non appartiene
a nessun dispositivo e deve esistere **anche quando i dispositivi sono zero** — che è
esattamente il caso del telefono appena installato, cioè lo scenario della user story 3.

Due strade, e la scelta va fatta prima di scrivere codice:

| | Come | Costo | Cosa si perde |
|---|---|---|---|
| **a** | `fun watch(topic: String, qos: Int)` + `val incoming: Flow<Pair<String,String>>` sull'interfaccia | Generico, il driver resta ignaro del registro | L'interfaccia si allarga e diventa un po' meno "cosa sa fare un canale verso i dispositivi" |
| **b** | Il driver conosce il registro: `val registry: StateFlow<String?>` | Meno API | Il registro entra dentro il protocollo, e un driver futuro non-MQTT dovrebbe reimplementarlo |

**(a)**, e il motivo è scritto nel javadoc dell'interfaccia stessa: *«il protocollo è l'unica
parte dell'app destinata a cambiare… il resto parla solo di `Device` e `DeviceCommand` e non
va toccato quando si aggiunge un driver»*. Un topic e un payload restano concetti di
trasporto; il *registro* no. Chi interpreta il JSON sta in `domain/registry/`, non nel driver.

### B.5 — `BrokerSettings`: da **non** toccare

Vedi E-2. È il punto in cui la scelta ovvia è quella sbagliata.

### B.6 — `DriverSnapshot` e la API di diagnostica

`DriverSnapshot` ([DriverDiagnostics.kt](../../../app/src/main/java/it/agoldoni/smarthome/diagnostics/DriverDiagnostics.kt))
porta già `subscriptions: Map<String, Int>`, quindi la sottoscrizione al registro comparirà
in `/mqtt` e in `/state` senza fare niente. Da aggiungere: se l'app segue il registro, quale
revisione ha applicato, quando, e se ne ha rifiutato uno.

Il javadoc della classe dice una cosa che vale anche qui: *«La password non c'è e non va
aggiunta: questo oggetto finisce in chiaro in una risposta HTTP»*. Il registro non contiene
segreti, quindi può uscire per intero.

**Breaking changes verso l'esterno: nessuno.** I topic dei dispositivi non cambiano, il
ponte non viene toccato, l'app vecchia continua a funzionare contro un broker che ha il
registro (ignora un topic a cui non si iscrive).

---

## C. Pattern da rispettare

Letti nel codice, non dedotti.

**Migrazioni Room scritte a mano, mai `fallbackToDestructiveMigration`.** Le tre esistenti
sono `ALTER TABLE ADD COLUMN` e basta, e il commento di `MIGRATION_1_2` spiega perché:
*«qui dentro ci sono i dispositivi che l'utente ha registrato uno per uno, e perderli per
una colonna in più sarebbe un pessimo scambio»*. `MIGRATION_4_5` aggiunge `uuid TEXT NOT
NULL DEFAULT ''` — la stringa vuota è il segno di "mai visto un registro", che è la verità
per ogni riga esistente, e da cui parte l'adozione.

**Nullo vuol dire "non si sa", e non si inventa.** `stateJsonKey`, `powerJsonKey`,
`availabilityTopic` sono nullable e ogni javadoc lo dice a parole. `readNumber` restituisce
`null` invece di `0.0` perché *«una presa che dice "0 W" sta lavorando ma non assorbe, una
che non dice niente non deve mostrare nessun numero»*. Il registro segue: un campo assente
non diventa un default silenzioso.

**`DeviceKind` salvato come stringa e non come ordinale**
([DeviceEntity.kt:16](../../../app/src/main/java/it/agoldoni/smarthome/data/local/DeviceEntity.kt#L16)),
con `runCatching { valueOf(kind) }.getOrDefault(SWITCH)` in lettura. Il `tipo` nel JSON del
registro è una stringa per la stessa ragione — ma lì un tipo sconosciuto **salta il
dispositivo** invece di ripiegare su `SWITCH`: fra due app che non hanno la stessa versione,
mostrare un interruttore dove il registro diceva altro sarebbe peggio che non mostrare niente.

**Niente testo scritto nel codice.** 94 stringhe in `strings.xml`, e gli helper dei campi
sono già scritti nella lingua del progetto (*«Dove il dispositivo pubblica il proprio stato.
Ammette le wildcard + e #.»*). La sezione Registro in Impostazioni segue.

**Le funzioni pure stanno in un file a parte e sono testate da sole.**
`MqttPayloads.kt` contiene `MqttTopics.matches`, `extractJson`, `readNumber`,
`readAvailability`: tutte `internal`, tutte senza Android, tutte con un test. Il parsing del
registro, il diff e l'adozione vanno nello stesso stampo — è ciò che rende la milestone 3
della fase 1 (il lettore prima dello scrittore) realizzabile senza interfaccia.

**Nel driver: `lock` per le operazioni Paho, `@Volatile` per ciò che leggono le
diagnostiche, e ogni callback verifica di essere ancora quello buono** (`if (client !==
owner) return`). Il codice nuovo dentro `MqttDeviceDriver` rispetta le stesse tre regole, e
`syncSubscriptions()` va chiamata con `lock` acquisito — c'è scritto sopra la firma.

**`DiagnosticsLog.event` e `.count` su ogni cosa che succede.** Il registro ricevuto,
applicato o rifiutato è esattamente il genere di evento che poi si va a cercare in `/log`.

**Validazione nel ViewModel, errori azzerati a ogni modifica**
([DeviceEditViewModel.kt](../../../app/src/main/java/it/agoldoni/smarthome/ui/devices/DeviceEditViewModel.kt)):
*«segnalarli mentre si corregge è solo rumore»*. La web app copia le stesse cinque
validazioni, con gli stessi messaggi.

**Compose:** `bridge/compose.yml` ha `name: smart-home`, `restart: unless-stopped`,
`user: "${PUID:-1000}:${PGID:-1000}"` sui servizi che scrivono su bind mount, healthcheck
sul broker. Il servizio `configuratore` serve file in sola lettura, quindi `user:` non gli
serve, ma `restart` e il nome del container (`sh-configuratore`) seguono la convenzione.

**Italiano ovunque**, nomi compresi (`Presa`, `prefisso`, `dispositivi.yaml`,
`topic_energia`). La web app non fa eccezione.

---

## D. Test da creare o aggiornare

### Cosa c'è oggi

Due file, entrambi unit JVM: `MqttPayloadsTest.kt` (184 righe, quattro classi:
`MqttTopicsTest`, `ExtractJsonTest`, `ReadAvailabilityTest`, `SubscriptionsTest`,
`ReadNumberTest`) e `MqttFailuresTest.kt`. Si lanciano con `./gradlew testDebugUnitTest`.
I nomi dei test sono frasi italiane fra backtick.

**`app/src/androidTest` non esiste**, e in `app/build.gradle.kts` non c'è nessuna
dipendenza `androidTestImplementation`: le tre migrazioni Room già in produzione sono state
scritte senza test strumentati.

### Nuovo: `app/src/test/java/it/agoldoni/smarthome/domain/registry/DeviceRegistryTest.kt`

Unit JVM, `org.json` reale già in `testImplementation` ([build.gradle.kts](../../../app/build.gradle.kts)) —
la nota nel `libs.versions.toml` lo dice perché: *«nei test JVM la android.jar ne offre solo
uno stub che solleva "not mocked"»*.

| Classe | Test |
|---|---|
| `ParseRegistryTest` | documento valido con tutti i campi · solo i campi obbligatori · payload non JSON → `null` · senza `dispositivi` → `null` · `schema` 2 su lettore 1 → `null` · campo sconosciuto ignorato · `tipo` sconosciuto → dispositivo saltato, gli altri no · `uuid` mancante → saltato · `uuid` duplicato → uno solo |
| `RegistryRevisionTest` | revisione maggiore → si applica · uguale → si ignora · minore → si ignora · registro rifiutato non abbassa la revisione applicata |
| `RegistryDiffTest` | un dispositivo in più → aggiunto · uno in meno → rimosso · uno cambiato → aggiornato **conservando l'`id`** · registro identico → diff vuoto (nessuna risottoscrizione: vedi E-1) |
| `RegistryAdoptionTest` | locale senza uuid con lo stesso `topicStato` → adottato, `id` conservato · topic diverso → non adottato · due locali con lo stesso topic → adottato uno solo, deterministicamente |

Il test *«registro identico → diff vuoto»* è il più importante di tutti e il meno ovvio: è
quello che impedisce a una ripubblicazione senza modifiche di far lampeggiare tutte le schede.

### Da aggiornare

`SubscriptionsTest` in `MqttPayloadsTest.kt`: i quattro test costruiscono `Device(name =
…, stateTopic = …)` e continuano a compilare con `uuid` a default. Vanno aggiunti due casi
sulla sottoscrizione di sistema — c'è anche a zero dispositivi, e non viene abbandonata da
`syncSubscriptions()`.

### Non coperto, e dichiarato

- **Migrazione 4→5**: servirebbe `androidTest` con `MigrationTestHelper` e
  `androidTestImplementation(libs.androidx.room.testing)`, che oggi non c'è. Decisione da
  prendere in fase 3 — vedi F-7
- **La web app**: nessun framework di test nel progetto, e introdurne uno (node, jest) per
  un modulo statico è sproporzionato. Al suo posto una lista di verifica manuale nel README
  e il controllo che conta, che si fa da riga di comando:
  ```bash
  mosquitto_sub -h localhost -u casa -P … -t casa/registro/dispositivi -C 1
  ```
- **Il giro completo** (browser → broker → app): verifica sul campo, milestone 10

---

## E. Rischi tecnici aggiornati

Quelli della fase 1 restano. Questi sono nuovi, e vengono dal codice.

### E-1 — Applicare il registro cancellando e reinserendo svuota tutte le schede

**Il rischio più serio, e il meno visibile.**

`states` è una mappa con chiave `device.id`, il Long autoincrement
([DeviceDriver.kt](../../../app/src/main/java/it/agoldoni/smarthome/domain/driver/DeviceDriver.kt)).
E in `track()`:

```kotlin
// MqttDeviceDriver.kt:171-172
val alive = devices.mapTo(mutableSetOf()) { it.id }
_states.update { current -> current.filterKeys { it in alive } }
```

Uno stato la cui chiave non è più fra i dispositivi vivi **viene buttato**. Un'applicazione
del registro fatta con `DELETE` + `INSERT` assegnerebbe id nuovi a tutto, e il risultato
sarebbe che a ogni pubblicazione del registro — anche per una virgola in un nome di stanza —
tutte le schede tornerebbero a *«in attesa di dati»* finché non ripassa un messaggio, che
sul topic dell'energia sono minuti.

E non finirebbe lì: dieci righe sopra, in `track()`, i dispositivi «cambiati» si
risottoscrivono ai propri topic, con il commento che spiega perché. Id nuovi significa tutti
cambiati, quindi unsubscribe e subscribe di tutto a ogni registro.

**Mitigazione, non negoziabile:** l'applicazione aggiorna **in loco per `uuid`**,
conservando l'`id`. Solo i dispositivi davvero spariti dal registro vengono cancellati. Il
test *«registro identico → diff vuoto»* è la sentinella di questa regola.

### E-2 — Mettere lo stato del registro in `BrokerSettings` riapre la connessione a ogni revisione

La scelta ovvia — c'è già un DataStore, ci sono già le impostazioni — è quella sbagliata. Nel driver:

```kotlin
// MqttDeviceDriver.kt:131
settings.distinctUntilChanged().collect { applySettings(it) }
```

e `applySettings` ([riga 248](../../../app/src/main/java/it/agoldoni/smarthome/driver/mqtt/MqttDeviceDriver.kt#L248))
chiama `closeClient()` e poi `openClient()`. `BrokerSettings` è un `data class`: aggiungerci
`revisioneRegistro` farebbe cambiare l'`equals` a ogni registro ricevuto, `distinctUntilChanged`
lascerebbe passare, e **il collegamento al broker si chiuderebbe e riaprirebbe** — perdendo le
sottoscrizioni, riprendendo i ritenuti, e con `isCleanSession = true` ([riga 295](../../../app/src/main/java/it/agoldoni/smarthome/driver/mqtt/MqttDeviceDriver.kt#L295))
ripartendo da zero. Con una revisione che cambia a ogni salvataggio sul configuratore, si
otterrebbe un'app che si riconnette mentre qualcuno sta configurando.

**Mitigazione:** `RegistryStore` separato, con un suo `preferencesDataStore(name = "registro")`.
Il driver non lo guarda.

### E-3 — La sottoscrizione al registro viene abbandonata al primo `syncSubscriptions()`

```kotlin
// MqttDeviceDriver.kt:353-360
val wanted = mutableMapOf<String, Int>()
devices.forEach { device -> device.subscriptions.forEach { topic -> … } }
…
val obsolete = subscribed.keys - wanted.keys
```

`wanted` nasce **solo** dai dispositivi. Un topic sottoscritto fuori da quel giro finisce in
`obsolete` alla prima risincronizzazione e viene disiscritto — e succederebbe subito, perché
`connectComplete` ([riga 91](../../../app/src/main/java/it/agoldoni/smarthome/driver/mqtt/MqttDeviceDriver.kt#L91))
azzera `subscribed` e richiama `syncSubscriptions()` a ogni connessione.

**Mitigazione:** i topic di sistema entrano in `wanted` insieme a quelli dei dispositivi, e
l'insieme vive in un campo `@Volatile` come `devices`.

### E-4 — Il messaggio del registro finisce dentro il ciclo dei dispositivi

`onMessage` ([riga 429](../../../app/src/main/java/it/agoldoni/smarthome/driver/mqtt/MqttDeviceDriver.kt#L429))
scorre tutti i dispositivi e confronta il topic con ognuno. Un dispositivo registrato con
`stateTopic = casa/#` — legittimo, le wildcard sono ammesse nel topic di stato —
**intercetterebbe il registro** e ne userebbe i 5 KB di JSON come proprio stato.

**Mitigazione:** il topic di sistema si intercetta **prima** del ciclo, con un `return`. In
più, un'anomalia in `/state` per un dispositivo il cui filtro combacia con
`<prefisso>/registro/#`.

### E-5 — `registro` diventa un nome di dispositivo riservato dentro il prefisso

Il ponte costruisce i topic come `f"{prefisso}/{self.nome}"`
([bridge.py:402](../../../bridge/tuya-mqtt/bridge.py#L402)) con `prefisso: casa`. Una presa
chiamata `registro` in `dispositivi.yaml` pubblicherebbe su `casa/registro/stato` — nessuna
collisione con `casa/registro/dispositivi`, ma una gran confusione.

Che il registro viva **sotto lo stesso prefisso** è una scelta e non un ripiego: il prefisso
diventa il confine di un'istanza, e sullo stesso broker possono convivere `casa/` e
`ufficio/`, ciascuno con i suoi dispositivi e il suo registro. Per questo il prefisso è
configurabile da entrambe le parti. ✅ **Deciso il 12/09/2026.**

Il ponte non sottoscrive wildcard (`client.subscribe(p.topic_comando)` e `p.topic_dps`,
[bridge.py:827-828](../../../bridge/tuya-mqtt/bridge.py#L827)): il registro **non lo
disturba**. Da documentare come nome da non usare, niente di più.

### E-6 — La minificazione non è un problema, se non si cambia libreria

`isMinifyEnabled = true` e `isShrinkResources = true` sulla release
([build.gradle.kts](../../../app/build.gradle.kts)). Il progetto parsa JSON a mano con
`org.json`, senza riflessione (`extractJson` in `MqttPayloads.kt`): R8 non ha niente da
rompere e `proguard-rules.pro` resta vuoto. Introdurre Gson o Moshi per il registro
costringerebbe a scrivere regole `-keep` per non ritrovarsi un parsing che funziona in debug
e fallisce in release.

**Mitigazione:** parsing a mano con `org.json`, come già si fa. Nessuna dipendenza nuova.

### E-7 — Il broker espone solo il 1883

`ports: - "1883:1883"` nel servizio `broker`. Il listener websockets senza la porta
pubblicata non è raggiungibile dal browser, e il sintomo è un collegamento che non si apre
senza dire perché. L'immagine `eclipse-mosquitto:2` ha il supporto websockets compilato: non
serve cambiare immagine.

### E-8 — Nessun limite di dimensione, e va bene

`mosquitto.conf` non imposta `message_size_limit`, quindi vale il default (nessun limite), e
`max_queued_messages 1000` riguarda la coda per client, non i ritenuti alla sottoscrizione.
Un documento da 5 KB non incontra nessun tetto. Confermato: il rischio "documento troppo
grande" della fase 1 non esiste a queste dimensioni.

### E-9 — Il ponte non sa niente del registro, ed è un bene

`dispositivi.yaml` resta l'unica fonte per il ponte. Il rischio vero è **umano**: rinominare
una presa nel registro senza rinominarla nel ponte dà un dispositivo con un bel nome che
punta a topic che non esistono più. La web app non può accorgersene senza iscriversi a
`casa/#`, che è la scoperta dei topic attivi, che è fuori scope.

**Mitigazione per ora:** l'anomalia c'è già e si legge dall'app — `/state` dice *«non è mai
arrivato niente sul topic …»* ([DebugReport.kt:215](../../../app/src/main/java/it/agoldoni/smarthome/diagnostics/DebugReport.kt#L215)).
Da citare nel README.

---

## F. Prerequisiti e task bloccanti

Nessun refactoring grosso. Le cinque cose qui sotto vanno però decise o fatte **prima**, e
le prime tre bloccano davvero.

1. **Listener websockets e porta 9001.** Due righe in `mosquitto.conf`, una in `compose.yml`,
   un riavvio del broker. Blocca tutto il resto: finché un browser non si collega non c'è
   niente da provare. Si verifica in mezz'ora con una pagina di quattro righe.

2. **Come il driver espone un topic non legato a un dispositivo** (B.4). Blocca sia il
   lettore lato app sia i test. ✅ **Deciso il 12/09/2026:** `watch(topic, qos)` + un `Flow`
   dei messaggi in arrivo sull'interfaccia `DeviceDriver`, con l'interpretazione del registro
   in `domain/registry/`.

3. **Decidere dove vive lo stato del registro** (E-2). `RegistryStore` separato. Se si
   sbaglia qui, il sintomo — l'app che si riconnette mentre si configura — è difficile da
   ricondurre alla causa.

4. **`uuid` e migrazione 4→5**, con `schemas/5.json` committato. Non blocca la web app, che
   può essere scritta e provata contro `mosquitto_sub` senza che l'app esista.

5. **Applicazione in loco per uuid, mai delete+insert** (E-1). È una regola di
   implementazione, non un prerequisito, ma va scritta prima di scrivere il DAO perché è la
   forma che il DAO deve avere.

6. **Scegliere e vendorizzare il client MQTT JavaScript.** MQTT.js è quello mantenuto e
   parla WebSocket nel browser. Va messo in `bridge/configuratore/web/vendor/` e copiato
   nell'immagine: da CDN, lo stack smetterebbe di funzionare proprio quando la linea è giù.

7. **`androidTest` per la migrazione Room.** Oggi non esiste e le tre migrazioni precedenti
   ne hanno fatto a meno; introdurlo significa una dipendenza, una cartella e un emulatore nel
   giro. La migrazione 4→5 è un `ADD COLUMN` con default, cioè la stessa forma delle altre
   tre. ✅ **Deciso il 12/09/2026: no**, per coerenza con quello che c'è. La verifica è
   aggiornare una build installata invece di disinstallarla.

8. **I nomi dei campi del JSON.** ✅ **Deciso il 12/09/2026: snake_case italiano**, la stessa
   convenzione dei payload del ponte (vedi B.1). Cambiarli dopo la prima pubblicazione
   significa un `schema: 2` e un lettore che sa leggere entrambi.
