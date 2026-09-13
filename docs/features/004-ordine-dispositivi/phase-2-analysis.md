# Ordine dei dispositivi — Analisi tecnica

**Stato:** Fase 2 — decisioni sciolte, pronta per la Fase 3
**Autore:** Alberto Goldoni
**Data:** 13 settembre 2026
**Versione:** 1.1 (13 settembre 2026: sciolte le tre domande finali)
**Feature:** `004-ordine-dispositivi` · [Fase 1](phase-1-requirements.md)

---

## Dove sta l'ordine, oggi

In tre righe, e nessuna delle tre lo ha mai deciso davvero.

| Dove | Riga | Cosa fa |
|---|---|---|
| App, database | `data/local/DeviceDao.kt:12` | `ORDER BY room COLLATE NOCASE, name COLLATE NOCASE` |
| Configuratore, disegno | `web/app.js:278` | `[...stato.dispositivi].sort(… localeCompare(nome))` |
| Configuratore, pubblicazione | `web/app.js:173` | `[...altri, d].sort(… localeCompare(nome))` |

Le ultime due sono **la stessa regola scritta due volte**, e la terza — quella che riordina
le voci prima di pubblicarle — è già oggi un ordinamento del documento che nessun lettore
usa: l'app rilegge e riordina per conto proprio. È il posto dove la posizione andrà a
sostituire il nome.

`room` partecipa all'ordinamento dell'app ma **non viaggia nel registro** (è confermato in
`RegistryPlan.kt:74`, «room non viaggia nel registro: resta quello che c'e' in locale») e
resta vuoto per tutti i dispositivi che vengono da lì. È però un campo vero del modulo
dell'app (`DeviceEditScreen.kt:127`, etichetta «Stanza»), quindi per i dispositivi registrati
a mano oggi ordina davvero — ed **esce dall'ordinamento** con questa feature (§8, D-2).

---

## A. File coinvolti

### Il contratto

| File | Modifica | Perché |
|---|---|---|
| `bridge/configuratore/SCHEMA.md` | modifica | Il campo `posizione`, la regola d'ordinamento, e la frase che vieta di leggere l'ordine dell'array. Va scritto **prima** del codice: è ciò che i due programmi devono condividere |

### L'app Android

| File | Modifica | Perché |
|---|---|---|
| `domain/model/Device.kt` | modifica | `val position: Int? = null`, in fondo alla data class e con un predefinito: così ogni chiamata esistente continua a compilare |
| `domain/model/Device.kt` | modifica | Il predicato che dice se due versioni dello stesso dispositivo **si ascoltano allo stesso modo** (vedi E/R-1). Sta qui perché è una proprietà del dispositivo, non del driver |
| `data/local/DeviceEntity.kt` | modifica | La colonna e le due funzioni di conversione `toDomain` / `toEntity` |
| `data/local/SmartHomeDatabase.kt` | modifica | `version = 6` (riga 8) e `MIGRATION_5_6` accanto alle altre quattro (riga 70) |
| `di/AppContainer.kt:45` | modifica | `addMigrations(…, MIGRATION_5_6)`. Dimenticarlo non rompe la compilazione: rompe l'apertura del database sul telefono di chi aggiorna |
| `data/local/DeviceDao.kt:12` | modifica | L'`ORDER BY`, unico punto da cui esce l'ordine dell'elenco |
| `domain/registry/DeviceRegistry.kt:128` | modifica | `leggiDispositivo` legge `posizione` con la stessa prudenza degli altri campi |
| `driver/mqtt/MqttDeviceDriver.kt:201` | modifica | Il confronto che decide le risottoscrizioni. **È il punto delicato di tutta la feature** |
| `ui/devices/DeviceEditViewModel.kt:205` | modifica | `DeviceForm.toDevice` ricostruisce un `Device` da zero: senza intervento **azzera la posizione a ogni salvataggio dal telefono** (vedi E/R-8) |
| `app/schemas/…/6.json` | nuovo | Lo genera Room, va committato: `exportSchema = true` |
| `app/build.gradle.kts:30-31` | modifica | `versionCode = 11`, `versionName = "1.3.0"` |

Non toccati, ed è la notizia buona: `DeviceRepository`, `RegistryPlan`, `RegistrySync`,
`DeviceListViewModel`, `DeviceListScreen`. L'ordine entra dal database ed esce dalla
`LazyColumn` senza che nessuno dei livelli in mezzo debba saperne niente — `items(…, key =
{ it.device.id })` (`DeviceListScreen.kt:276`) è già indicizzata per id, quindi un riordino
sposta le schede invece di ricrearle.

### Il configuratore web

| File | Modifica | Perché |
|---|---|---|
| `web/registro.js:62` | modifica | `serializza()` costruisce l'oggetto da pubblicare **da una lista chiusa di chiavi**: senza aggiungerci `posizione`, il campo verrebbe cancellato al primo salvataggio |
| `web/app.js:278` | modifica | `disegnaElenco()` ordina per posizione, poi per nome: la stessa regola dell'app |
| `web/app.js:266` | modifica | Le righe diventano trascinabili, e prendono i due comandi su/giù |
| `web/app.js:150` | modifica | `pubblica()`: la guardia contro la scrittura concorrente oggi vale solo a modulo aperto (vedi E/R-5) |
| `web/app.js:167` | modifica | `salva()` non riordina più per nome, e assegna una posizione al dispositivo nuovo secondo la regola di B-4 |
| `web/app.js:11` | modifica | Nello stato della pagina compare l'ordine in sospeso, quello trascinato e non ancora pubblicato |
| `web/stile.css` | modifica | Lo stato «riga che si sta trascinando» e il punto in cui cadrà |
| `web/campi.js` | **non** toccato | Dichiara i campi **del modulo**. La posizione non si digita in una casella: se finisse lì comparirebbe come campo di testo nel modulo di ogni dispositivo |

### Gli strumenti e l'ambiente di prova

| File | Modifica | Perché |
|---|---|---|
| `tools/genera-registro.mjs` | modifica | Assegna le posizioni alle sette prese |
| `app/src/test/resources/registro-ponte.json` | rigenerato | È la prova che i due lati si capiscono: va rifatto col codice nuovo, non a mano |
| `devops/dev/genera-registro-dev.mjs` | modifica | Idem per i sette finti. Il broker di sviluppo non ha persistenza: il registro si ripubblica a ogni `up`, e se resta senza posizioni le prove girano sul formato vecchio |
| `devops/dev/registro-dev.json` | rigenerato | — |

---

## B. Contratti e interfacce da modificare

### B-1 — Il campo

```json
{ "uuid": "…", "nome": "boiler", "posizione": 0, … }
```

| Campo | Tipo | Obbl. | Predefinito | Significato |
|---|---|---|---|---|
| `posizione` | intero ≥ 0 | no | assente | Dove sta il dispositivo nell'elenco. Assente = nessuno lo ha collocato |

**Additivo: `schema` resta 1.** Lo dice già `SCHEMA.md` — «aggiungere un campo è additivo e
non richiede di alzare `schema`» — ed è verificato dal test
`un campo sconosciuto si ignora e il dispositivo si applica lo stesso`
(`DeviceRegistryTest.kt:111`): un'app 1.2.0 davanti al registro nuovo ignora `posizione` e
applica il dispositivo.

### B-2 — La regola di ordinamento, una sola per tutti e due

1. Prima chi ha una posizione, in ordine crescente
2. Poi chi non ce l'ha
3. A parità — stessa posizione, o entrambi senza — **per nome**

Il terzo punto non è pignoleria: senza un criterio di spareggio dichiarato, due dispositivi
con la stessa posizione si ordinerebbero come capita, e capiterebbe **diversamente** nei due
programmi (SQLite e `Array.prototype.sort` non hanno la stessa idea di stabilità).

Nell'app è una riga sola:

```sql
ORDER BY (position IS NULL), position, name COLLATE NOCASE
```

`(position IS NULL)` serve perché in SQLite i NULL vengono **primi** in ordine crescente, e
qui devono venire ultimi. `room` **esce dall'ordinamento** (§8, D-2): l'elenco è piatto come
dice lo scope, e la promessa «chi non riordina non si accorge di niente» regge lo stesso, di
fatto, perché la stanza è vuota per tutto ciò che viene dal registro. Si sposterebbe solo un
dispositivo registrato a mano **e** con una stanza scritta dentro: non ne esiste nessuno.

### B-3 — Vince il campo, l'array non si legge mai

Il documento porta due ordini: le posizioni e l'ordine in cui le voci stanno nell'array.
`SCHEMA.md` deve dire, a lettere chiare, che **il secondo non è un'informazione**. Il
configuratore pubblicherà comunque le voci già ordinate, per riguardo verso chi legge il
JSON con gli occhi, ma nessun lettore ci si appoggia.

### B-4 — Chi assegna le posizioni, e quando

Il configuratore è l'unico scrittore. Le regole:

- **Riordino:** tutte le voci vengono rinumerate `0, 1, 2, …` in blocco. Posizioni diritte e
  senza buchi, sempre
- **Dispositivo nuovo:** prende `max + 1` **solo se almeno un altro ha già una posizione**.
  Altrimenti non ne prende nessuna
- **Modifica o duplicazione:** la posizione non si tocca

La seconda regola è quella che evita la trappola: in un registro dove nessuno ha ancora
riordinato — come quello di casa adesso — dare `posizione: 0` al primo dispositivo aggiunto
lo farebbe schizzare **in cima** a tutti gli altri, che sono senza posizione e quindi in
fondo per definizione. Un campo invisibile che riordina la casa a sorpresa è esattamente
quello che questa feature deve non fare.

### B-5 — Valori che non si capiscono

`SCHEMA.md` ha già la regola: «campo noto di tipo sbagliato → si usa il predefinito». Qui il
predefinito è **assente**, non zero. Una `posizione` negativa, con la virgola, o scritta come
stringa vale come se non ci fosse — e il dispositivo finisce in fondo, per nome. Zero
sarebbe la risposta sbagliata nel modo peggiore: metterebbe in cima quello che non si è
capito.

### B-6 — Kotlin e SQL

- `Device`: `val position: Int? = null` in coda alla data class, con predefinito → nessun
  punto di costruzione esistente va toccato
- `DeviceEntity`: idem, più le due conversioni
- `MIGRATION_5_6`: `ALTER TABLE devices ADD COLUMN position INTEGER` — annullabile, senza
  `DEFAULT`. Additiva come le quattro che la precedono, e per la stessa ragione scritta lì:
  in quella tabella ci sono dispositivi registrati a mano
- `DeviceForm.toDevice(id, uuid)` diventa `toDevice(id, uuid, position)`

---

## C. Pattern da rispettare

**C-1 — Da un valore che non si è capito non si deduce niente.** È la regola che il progetto
applica ai payload di disponibilità, ai registri illeggibili e ai tipi sconosciuti. Qui
diventa B-5.

**C-2 — Nullo vuol dire «di questo non si sa».** Le quattro migrazioni esistenti lo dicono
tutte, con parole loro: la colonna nuova nasce nulla e il commento spiega *che cosa* significa
quel nulla per le righe che c'erano già. `MIGRATION_5_6` seguirà la stessa forma.

**C-3 — Il contratto è in italiano, il codice in inglese.** `SCHEMA.md` dice `posizione`,
`nome`, `topic_stato`; `Device` dice `position`, `name`, `stateTopic`. La traduzione sta in
un posto solo per lato: `leggiDispositivo` (`DeviceRegistry.kt:128`) e `serializza`
(`registro.js:62`).

**C-4 — Una regola che si può provare in JVM si mette fuori dai Composable.** Lo dice la 003
del suo `DeviceUi.commandable`. Vale per il predicato delle risottoscrizioni.

**C-5 — Il configuratore ha una dipendenza sola** (`mqtt.min.js`). Il trascinamento si scrive
a mano con i pointer events: sono un centinaio di righe, contro una libreria da versionare,
aggiornare e far entrare in un'immagine.

**C-6 — Le prove del contratto passano da un documento vero.** `RegistryContractTest` legge
un file **generato dal codice della pagina web**. È l'unico test che verifica che i due pezzi
si capiscano; la posizione ci deve entrare.

---

## D. Test da creare o aggiornare

### Unitari, JVM (`app/src/test`)

| File | Tipo | Casi |
|---|---|---|
| `domain/registry/DeviceRegistryTest.kt` | modifica | `posizione` letta; assente resta nulla; negativa, decimale o stringa valgono come assente; duplicata non fa saltare nessuno |
| `domain/registry/DeviceRegistryTest.kt` | modifica | Piano: **cambia solo la posizione** → il dispositivo finisce in `updated` con **lo stesso id**. È il test che protegge le schede dal ritorno a «in attesa di dati» |
| `domain/model/DeviceWiringTest.kt` | **nuovo** | Il predicato di R-1: posizione diversa → si ascolta uguale; nome diverso → uguale; topic di stato, chiave JSON, payload, livello massimo diversi → **non** uguale. Sul modello di `DeviceCommandableTest` |
| `domain/registry/RegistryContractTest.kt` | modifica | Le sette prese si leggono **nell'ordine** dichiarato dal documento, non ordinate per nome (oggi la prova è su `.map { it.name }.sorted()`, riga 56: con le posizioni si può finalmente asserire la sequenza vera) |

L'`ORDER BY` **non** è coperto da questi: è SQL, e gira su SQLite vero. Si verifica sul
telefono, e c'è una strada comoda — vedi F-3.

### Il documento condiviso

`app/src/test/resources/registro-ponte.json` va rigenerato con
`node tools/genera-registro.mjs app/src/test/resources/registro-ponte.json`, come dice il
commento in testa a `RegistryContractTest`. Il file finisce in git con una diff pulita
perché gli uuid sono deterministici e `aggiornato` è fisso.

### Sul campo, contro `devops/dev`

| # | Caso | Come si verifica |
|---|---|---|
| TC-01 | L'ordine del registro è quello dell'elenco | Riordino nel configuratore, poi `GET /devices` sull'API di diagnostica: l'array `devices` esce nell'ordine del database, senza dover guardare lo schermo |
| TC-02 | Un riordino non fa risottoscrivere niente | `collegamento.tentativi` fermo in `/state`, e nessuna riga «sottoscrizioni rifatte» nel log diagnostico |
| TC-03 | Un riordino non azzera le schede | Gli id in `/devices` sono gli stessi di prima, e potenza e kWh non spariscono |
| TC-04 | L'ordine vale a broker spento | Avvio a freddo senza rete: l'elenco è già giusto al primo fotogramma |
| TC-05 | Registro senza posizioni | Il `registro-dev.json` vecchio: i sette finti escono in ordine alfabetico, come nella 1.2.0 |
| TC-06 | Posizioni parziali | Documento fatto a mano con tre posizioni su sette: i tre in cima nell'ordine, gli altri quattro sotto per nome |
| TC-07 | Un'app 1.2.0 legge il registro nuovo | L'APK precedente installato su un secondo telefono, o `adb install -r` a ritroso: sette dispositivi, nessuno scartato |
| TC-08 | Modifica dal telefono | Con «segui il registro» spento, modificare un dispositivo e verificare che la posizione **resti** (è R-8) |
| TC-09 | Trascinamento sul telefono | La pagina aperta da un browser touch all'indirizzo vero, non da `localhost` |
| TC-10 | Due schede aperte | Riordino in una e salvataggio nell'altra: chi perde se ne accorge (R-5) |

---

## E. Rischi tecnici aggiornati

### R-1 — La risottoscrizione di massa · **confermato, con la riga sotto gli occhi**

`MqttDeviceDriver.kt:201`:

```kotlin
val cambiati = devices.filter { precedenti[it.id]?.equals(it) == false }
```

Il confronto è sull'**oggetto intero**. Con `position` dentro `Device`, un riordino di sette
dispositivi produce sette «cambiati», quindi sette risottoscrizioni e sette consegne di
ritenuti, a ogni salvataggio dell'ordine. Da notare che il difetto **esiste già**: anche
correggere un refuso in un nome risottoscrive tutto quel dispositivo. Oggi è un falso
positivo raro; la posizione lo renderebbe sistematico.

Due strade.

**(a) `position` dentro `Device`, e il confronto si affina.** Il predicato guarda quello che
davvero cambia il modo di ascoltare — i topic e l'interpretazione dei payload — e ignora
l'identità e la presentazione: `id`, `uuid`, `name`, `room`, `position`. Scritto come
esclusione e non come elenco di campi buoni, così un campo di rete aggiunto domani ci entra
da solo:

```kotlin
/** Le stesse orecchie: stessi topic, e stesso modo di leggere quel che arriva. */
fun Device.listensLike(other: Device): Boolean =
    anonimo() == other.anonimo()

private fun Device.anonimo(): Device =
    copy(id = 0L, uuid = "", name = "", room = "", position = null)
```

**(b) `position` fuori da `Device`, solo nella riga del database.** Il driver non la vede mai
e R-1 sparisce per costruzione. Ma `planRegistry` ragiona su `Device`: un riordino puro
produrrebbe un piano vuoto e non verrebbe applicato, quindi servirebbe una seconda strada —
il registro che restituisce anche una mappa `uuid → posizione`, il repository che la scrive a
parte, la transazione che deve comprendere tutte e due.

**Raccomandazione: (a).** Un concetto solo e un percorso solo, il meccanismo dell'adozione e
della conservazione degli id riusato com'è, e in più **corregge** un falso positivo che c'è
già. Il prezzo è toccare il driver, che è il nervo scoperto della 003: si paga con un test
JVM dedicato e con TC-02, che è la stessa prova che chiuse R-6.

### R-2 — Il trascinamento sul touch · **confermato**

Nessuna libreria disponibile né desiderabile (C-5). L'HTML5 drag-and-drop non emette eventi
sul touch: si usano i **pointer events** (`pointerdown`, `pointermove`, `setPointerCapture`),
che coprono mouse, dito e penna con lo stesso codice. I comandi su/giù di US-6 restano
comunque, e non come ripiego: sono la strada per la tastiera e per TalkBack.

### R-3 — Due ordini in disaccordo · **sciolto dal contratto** (B-3)

Rimane il dovere del configuratore di rinumerare in blocco (B-4): posizioni con buchi non
rompono niente, ma un documento in cui le posizioni sono `0, 3, 7` è un documento che invita
qualcuno a interpretarle.

### R-4 — Dispositivi senza posizione · **sciolto** (B-2)

Con un'insidia tecnica reale: in SQLite i NULL vengono primi. Senza `(position IS NULL)` in
testa all'`ORDER BY`, i dispositivi senza posizione finirebbero **in cima** — l'esatto
contrario della regola.

### R-5 — Due scrittori · **confermato, e la guardia oggi non copre il riordino**

`app.js:152`:

```js
if (stato.modifica && stato.revisione !== stato.revisioneBase) { … }
```

La condizione parte da `stato.modifica`, cioè **solo a modulo aperto**. Un riordino non apre
nessun modulo: così com'è, pubblicherebbe sempre, sovrascrivendo qualunque cosa sia arrivata
nel frattempo — e un riordino tocca *tutte* le righe, quindi ci perde tutto. La guardia va
estesa all'ordine in sospeso, con lo stesso `revisioneBase` che già esiste.

### R-6 — Il registro di casa · **confermato, nessuna migrazione**

Revisione 8, sette prese, ritenuto dentro `mosquitto.db`. Il documento nuovo è un
sovrainsieme: si ripubblica dal configuratore riordinando, non a mano.

### R-7 — Lo stack di sviluppo · **confermato**

`devops/dev/genera-registro-dev.mjs` importa il codice vero della pagina
(`registro.js`, `modelli.js`): appena `serializza()` conosce `posizione`, il generatore la
può assegnare. Senza, le prove girerebbero sul formato vecchio — che è comunque un caso da
provare, ed è TC-05.

### R-8 — Il modulo del telefono azzera la posizione · **nuovo**

`DeviceEditViewModel.kt:205`, `DeviceForm.toDevice(id, uuid)` **ricostruisce il `Device` da
zero** dai campi del modulo. La posizione non è un campo del modulo — e non deve esserlo —
quindi ogni salvataggio dal telefono la riporterebbe a nulla, spedendo il dispositivo in
fondo all'elenco. E non tornerebbe da sé: il registro è ritenuto e la revisione è già stata
applicata, quindi non si riapplica.

La cura è il pattern che il file già usa per l'uuid (`private var uuid` alla riga 81): si
conserva la posizione al caricamento e la si ripassa al salvataggio. È anche la stessa cosa
che `planRegistry` fa con `room` — «resta quello che c'e' in locale».

Il caso è stretto — a registro seguito il modulo è in sola lettura — ma non impossibile:
basta spegnere «segui il registro» e correggere un topic.

### R-9 — Il configuratore cancella quello che non conosce · **nuovo**

`serializza()` (`registro.js:62`) costruisce l'oggetto da pubblicare da una lista chiusa di
chiavi: **quello che non è in quella lista non viene ripubblicato.** È in contrasto con il
commento di `leggiRegistro` («quello che non capisce lo tiene com'e', cosi' non lo cancella
ripubblicando»): la lettura lo tiene, la scrittura lo butta.

Per la feature significa che aggiungere `posizione` a `serializza` non è un dettaglio ma la
condizione perché il campo sopravviva al primo salvataggio. In generale è un difetto a sé, da
segnalare e non da correggere qui: lo scrittore è uno solo e il campo che perderebbe non
esiste ancora.

### R-10 — Le schede saltano · **nuovo, cosmetico**

`DeviceListScreen.kt:276` ha già `key = { it.device.id }`, quindi Compose sa *quale* scheda
si è spostata, ma senza `Modifier.animateItem()` il riordino appare come un salto secco. Un
riordino arriva da fuori, mentre si sta guardando l'elenco: vale la riga che serve a farlo
scivolare.

### R-11 — L'elenco che si ridisegna sotto il dito · **nuovo, ridimensionato da D-3**

`arrivato()` (`app.js:105`) rimpiazza `stato.dispositivi` con quello che è appena arrivato
dal broker, e ridisegna. Con la pubblicazione immediata (§8, D-3) **non esiste più un
riordino in sospeso da perdere**: quello che resta è la finestra in cui il dito è ancora
giù. Se un registro arriva a metà trascinamento, la riga trascinata viene ricreata sotto il
puntatore e il gesto si rompe. La cura è rinviare il ridisegno alla fine del trascinamento —
poche righe, ma vanno messe, perché è proprio durante un riordino che qualcun altro ha
motivo di star scrivendo.

---

## F. Prerequisiti e task bloccanti

**F-1 — `SCHEMA.md` prima del codice.** Bloccante per tutto il resto: le due implementazioni
della regola B-2 devono discendere dallo stesso testo, o divergeranno nei casi limite
(posizioni uguali, valori strani, `room` nella coda).

**F-2 — Decidere la strada di R-1** fra (a) e (b). Bloccante per l'app: cambia dove va il
campo e quanto driver si tocca.

**F-3 — Niente `androidTest` nel progetto.** Non c'è infrastruttura strumentata: le
migrazioni 1→2, 2→3, 3→4 e 4→5 sono state verificate sul telefono, non da un
`MigrationTestHelper`. Si continua così — e per l'`ORDER BY` c'è una strada migliore dello
schermo: l'API di diagnostica espone `/devices` (`DebugHttpServer.kt:45`) nell'ordine esatto
del database, quindi TC-01 e TC-03 si leggono con `curl`, non con gli occhi. Non è
bloccante, ma va detto prima invece di scoprirlo a metà.

**F-4 — Rigenerare i due documenti** (`registro-ponte.json`, `registro-dev.json`) subito dopo
aver toccato `serializza()`: sono generati, e una modifica a mano si perderebbe alla
rigenerazione successiva.

**F-5 — Il percorso di consegna.** Il configuratore vive in un'immagine costruita sul PC con
`buildx --platform linux/arm64` e caricata sul Pi via `docker save | ssh docker load`: la
pagina modificata arriva a casa con `./devops/deploy.sh`, non con un `rsync`. L'app sale a
1.3.0 e va installata sopra la 1.2.0 con i dati veri, che è anche TC-07 al contrario.

---

## Decisioni prese

Le tre domande della 1.0, sciolte il **13 settembre 2026**.

**D-1 — Il campo si chiama `posizione`.** In italiano come tutto il resto del contratto.

**D-2 — `room` esce dall'ordinamento.** L'elenco è piatto davvero: posizione, poi nome. La
stanza resta un campo del modulo dell'app — non si tocca, non si toglie — ma non ordina più
niente. Nella pratica non sposta nessun dispositivo esistente, perché nessuno ha una stanza
scritta dentro.

**D-3 — Il riordino si pubblica subito**, senza un «Salva ordine» da premere. Ne discendono
tre cose, tutte da tenere presenti nel piano:

1. **Una revisione per riordino.** Un trascinamento completato pubblica; cinque tocchi sulla
   freccia «su» pubblicherebbero cinque volte. Il piano prevede di **accorpare i tocchi
   ravvicinati** con una breve attesa prima di pubblicare — resta «subito» per chi guarda, e
   non fa attraversare il broker a cinque documenti per uno spostamento solo
2. **R-11 si ridimensiona**: non c'è più un ordine in sospeso che un registro in arrivo possa
   cancellare. Resta da non ridisegnare l'elenco mentre il dito è giù
3. **La guardia di R-5 conta meno, ma non zero.** Pubblicando subito, il riordino parte
   sempre dallo stato più fresco che la pagina conosce; chi perde è chi ha trascinato mentre
   un altro salvava, e deve potersene accorgere