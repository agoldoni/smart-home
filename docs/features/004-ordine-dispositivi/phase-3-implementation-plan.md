# Ordine dei dispositivi — Implementation Plan

**Stato:** Implementato — verificato sul campo, restano tre prove che richiedono lo schermo sbloccato
**Autore:** Alberto Goldoni
**Data:** 13 settembre 2026
**Versione:** 1.0
**Feature:** `004-ordine-dispositivi` · [Fase 1](phase-1-requirements.md) · [Fase 2](phase-2-analysis.md)

---

## 0. Stato di avanzamento

**Aggiornato: 13 settembre 2026, sera.**

Task **T-01 → T-15 e T-17 chiusi**; T-16 quasi. `./gradlew testDebugUnitTest assembleDebug
lintDebug` passa: **87 test, 0 falliti** (erano 71; i sedici nuovi sono le posizioni nel
lettore e nel piano, e `DeviceListensLikeTest`), lint a **11 segnalazioni**, tutte
preesistenti — versioni di dipendenze e stringhe inutilizzate. Versione **1.3.0
(versionCode 11)**, installata sul telefono **sopra la 1.2.0, senza cancellare i dati**.

### L'ambiente di prova

Stack di sviluppo `devops/dev`: broker senza persistenza, sette dispositivi finti, nessun
ponte Tuya. Il registro di sviluppo ha un ordine **deliberatamente non alfabetico** — in
cima `golf-nome-lungo…`, in fondo `bravo` — perché se le posizioni non arrivassero l'elenco
tornerebbe in ordine di nome e la differenza si vedrebbe in un istante.

Il configuratore è stato provato **all'indirizzo vero**, `http://192.168.86.45:8080`, mai da
`localhost`: localhost è un contesto sicuro per definizione, e nasconde i difetti che si
vedono solo fuori.

### Verificato, e come

| # | Esito | Come |
|---|---|---|
| TC-01 | ✅ | Pubblicata la revisione con le posizioni, `GET /devices`: l'elenco esce `golf(0) echo(1) alfa(2) foxtrot(3) delta(4) charlie(5) bravo(6)`, cioè l'ordine dichiarato e non l'alfabeto |
| **TC-02** | ✅ | **`sottoscrizioni.aggiunte` ferma a 63 e `collegamento.tentativi` a 3 dopo tredici revisioni**, riordini compresi. Zero righe «sottoscrizioni rifatte» in tutto il log diagnostico. **R-1 chiuso** |
| TC-03 | ✅ | Gli id restano `1…7` prima e dopo ogni riordino; il log dice «revisione applicata: 0 aggiunti, 7 aggiornati, 0 rimossi» — aggiornati in loco, nessuno rinato |
| TC-04 | ✅ | Wifi del telefono spento, `am force-stop`, riapertura: elenco nell'ordine giusto con `connection: failed`. L'ordine viene dal database, non dal registro |
| TC-05 | ✅ | Registro senza posizioni (revisione più alta): torna l'ordine alfabetico e le posizioni si azzerano, sempre senza risottoscrizioni |
| TC-06 | ✅ | Posizioni su tre dispositivi su sette: i tre in testa nell'ordine dichiarato, gli altri quattro sotto per nome |
| TC-09 | ⚠️ | Trascinamento provato **con un puntatore sintetico** in Chromium: la prima riga arriva in fondo e il documento si pubblica da sé. Con un dito vero resta da fare |
| TC-10 | ✅ | Riordino superato da una pubblicazione altrui: non sovrascrive, e la pagina dice «il riordino non è partito» |
| TC-11…14 | ✅ | 87 test JVM |
| TC-15 | ✅ | Le frecce hanno `aria-label` per esteso («Sposta echo-regolabile più in alto»), la prima riga non può salire, e si raggiungono col tabulatore |
| Migrazione 5→6 | ✅ | 1.3.0 installata sopra la 1.2.0 con i dati veri: sette dispositivi, stessi id, `position: null`. L'app si è aperta senza storie |

### Il difetto che la prova ha trovato

Il trascinamento **si fermava dopo il primo scambio**. La cattura del puntatore stava sulla
maniglia, cioè dentro la riga trascinata — e spostare un nodo nel DOM **fa perdere la
cattura**: da lì in poi il puntatore si staccava dalla riga e non succedeva più niente. La
cattura è stata spostata sull'**elenco**, che non si muove mai: sono i suoi figli a muoversi.

Non era un difetto che si potesse vedere leggendo il codice, e nemmeno provando la sola
logica: è emerso alla prima prova in un browser vero.

### Da finire

1. **TC-09 con un dito**, su uno schermo touch: `touch-action: none` sulla maniglia è la riga
   che dovrebbe impedire alla pagina di scorrere invece di prendere la riga, e un puntatore
   sintetico non la mette alla prova
2. **TC-07 — un'app 1.2.0 davanti al registro nuovo.** Provato per costruzione dal test
   `un campo sconosciuto si ignora e il dispositivo si applica lo stesso`, ma non su un
   secondo telefono: quello che c'è qui è già alla 1.3.0, e non si torna indietro (§9)
3. **TC-08 — modificare un dispositivo dal telefono** con «segui il registro» spento, e
   vedere che la posizione resta. Richiede lo schermo sbloccato, che qui non lo era
4. **Guardare l'elenco sullo schermo**: che le schede scivolino invece di saltare (R-10) non
   è verificabile da `/devices`

### Due cose lasciate diverse da come si erano trovate

- **L'API di debug del telefono è accesa** (`shared_prefs/debug-api.xml`), perché era l'unico
  modo di leggere l'ordine senza sbloccare lo schermo. Era spenta
- **Il registro dello stack di sviluppo è alla revisione 14**, non alla 2 del file: le prove
  ne hanno consumate parecchie. Il broker di sviluppo non ha persistenza, quindi il prossimo
  `docker compose up` riparte dalla 2

---

## 1. Executive Summary

I dispositivi nell'app compaiono in ordine alfabetico, che è un ordine che nessuno ha scelto:
non ha rapporto con quanto spesso si usa una presa, e cambia da sé quando si rinomina un
dispositivo. Questa feature aggiunge a ogni dispositivo una **posizione**, decisa a mano
trascinando le righe nel configuratore web, che viaggia nel registro condiviso insieme ai
nomi e ai topic — quindi vale su tutti i telefoni, senza toccarne nessuno.

È una modifica **additiva su tutti i fronti**: il formato del registro resta alla versione 1,
un'app vecchia ignora il campo nuovo, e finché nessuno riordina l'elenco resta identico a
quello di oggi. Stima: **3,25 giorni/uomo**, di cui un terzo è il trascinamento nella pagina
web.

---

## 2. Obiettivo e motivazione

**Problema che risolve.** La vista principale è una colonna di interruttori pensata perché
«l'interruttore cada sotto il pollice» (feature 003). Quale interruttore ci cada, oggi, lo
decide l'iniziale del nome: `ORDER BY room COLLATE NOCASE, name COLLATE NOCASE`, e `room` è
vuoto per tutto ciò che arriva dal registro. Siccome il nome è anche una cosa che si cambia
dal configuratore in due secondi, **rinominare un dispositivo riordina la casa** e la memoria
muscolare formata su sette schede salta senza preavviso.

**Metriche di successo**

- [ ] L'ordine deciso nel configuratore è quello che si vede sul telefono, su **tutti** i
      telefoni, senza toccare niente su ciascuno
- [ ] L'ordine si vede anche a broker irraggiungibile, già al primo fotogramma
- [ ] Un riordino non fa rinascere nessun dispositivo: gli id locali restano, le schede
      conservano potenza e kWh, nessuna torna «in attesa di dati»
- [ ] Un riordino non fa cadere la connessione e non produce risottoscrizioni:
      `collegamento.tentativi` in `/state` non si muove, e nel log diagnostico non compare
      nessuna riga «sottoscrizioni rifatte»
- [ ] Un'app ferma alla 1.2.0 che legge il registro nuovo continua a funzionare
- [ ] Finché nessuno riordina, l'elenco è **esattamente** quello di oggi

**Legame con il resto del progetto.** È il terzo intervento sulla stessa vista dopo la 002
(il registro condiviso, che ha reso la configurazione una proprietà della casa invece che del
telefono) e la 003 (la sola lettura). Segue la stessa linea: quello che riguarda la casa sta
nel registro, quello che riguarda il telefono resta sul telefono.

---

## 3. Scope

### Incluso

**Il contratto**
- Campo `posizione` nel registro: intero ≥ 0, facoltativo, **additivo** — `schema` resta 1
- Una regola di ordinamento sola, scritta in `SCHEMA.md` e implementata identica nei due
  programmi: **prima chi ha una posizione, in ordine crescente; poi gli altri; a parità, per
  nome**
- L'ordine in cui le voci stanno nell'array **non si legge mai**

**Il configuratore web**
- Le righe si trascinano per riordinarle, e si spostano anche con due comandi su/giù
- Il riordino si pubblica **subito**, senza un pulsante da premere
- L'elenco del configuratore è l'elenco nell'ordine deciso: è lì che il riordino si vede per
  primo

**L'app Android**
- La vista principale rispetta l'ordine del registro
- La posizione è persistita in locale (colonna Room, migrazione additiva 5→6): vale a broker
  spento e prima che il registro arrivi
- Le schede **scivolano** al posto nuovo invece di saltarci

### Escluso (out of scope)

- **Ordinamenti per criterio** (nome, tipo, acceso/spento, consumo) — l'ordine è uno solo ed
  è quello deciso a mano; un menù di ordinamento è un'altra feature, non una variante di
  questa
- **Raggruppamento per stanza**, e il ritorno del campo `stanza` nel registro — lo scope
  resta un elenco piatto
- **Riordino dall'app Android** — l'app non scrive nel registro, e non è questa la feature
  che le insegna a farlo
- **Ordini diversi su telefoni diversi** — l'ordine è una proprietà della casa
- Favoriti, dispositivi nascosti, sezioni

### Decisioni aperte

Nessuna. Le sei emerse durante le Fasi 1 e 2 sono state chiuse il 13 settembre 2026.

| # | Decisione | Esito |
|---|---|---|
| 1 | Come si esprime l'ordine | **Manuale, a trascinamento.** Non criteri automatici |
| 2 | Dove vive l'ordine | **Nel registro condiviso**, quindi si riordina dal configuratore |
| 3 | Rientra la stanza | **No.** Elenco piatto |
| 4 | Posizione come indice dell'array o campo esplicito | **Campo esplicito**, `posizione`. L'array non si legge |
| 5 | Dispositivi senza posizione | **In fondo, per nome.** Cioè come oggi: l'app nuova davanti al registro vecchio si comporta come l'app vecchia |
| 6 | `room` nell'ordinamento dell'app | **Esce.** Non sposta nessun dispositivo esistente: la stanza è vuota per tutto ciò che viene dal registro |
| 7 | Il riordino si pubblica subito o a comando | **Subito**, con i tocchi ravvicinati accorpati |

---

## 4. User Stories e criteri di accettazione

### US-001 · I dispositivi che uso davvero, in cima
**Priorità:** Must Have

Come chi apre l'app dieci volte al giorno, voglio che i dispositivi che uso stiano in cima,
per trovarli sotto il pollice senza scorrere e senza cercarli.

**Criteri di accettazione:**
- [ ] Con un registro che dichiara l'ordine, la vista mostra le schede in quell'ordine esatto
- [ ] L'ordine non dipende dall'alfabeto: due nomi in ordine inverso rispetto alla posizione
      restano nell'ordine dichiarato
- [ ] Chiusa e riaperta l'app (`am force-stop` e riapertura), l'ordine è lo stesso

### US-002 · Decidere l'ordine trascinando
**Priorità:** Must Have

Come chi configura la casa, voglio decidere l'ordine trascinando le righe nel configuratore,
per non dover rinominare i dispositivi allo scopo di spostarli.

**Criteri di accettazione:**
- [ ] Le righe si trascinano e la nuova sequenza si vede subito nella pagina
- [ ] Il trascinamento funziona **col dito**, sulla pagina aperta all'indirizzo vero
- [ ] Il riordino pubblica **un solo** documento, con `revisione` incrementata di uno
- [ ] Il documento è identico al precedente tranne le posizioni: nessun altro campo cambia,
      nemmeno per i dispositivi che non si sono mossi
- [ ] Cinque spostamenti consecutivi con i comandi su/giù non pubblicano cinque documenti
- [ ] Ricaricata la pagina, l'ordine è quello salvato

### US-003 · Lo stesso ordine su ogni telefono
**Priorità:** Must Have

Come chi ha due telefoni, voglio che l'ordine sia lo stesso su entrambi, per non doverlo
rifare uno per uno.

**Criteri di accettazione:**
- [ ] Due telefoni che seguono lo stesso registro mostrano la stessa sequenza
- [ ] Un telefono acceso **dopo** il riordino prende l'ordine nuovo alla prima connessione

### US-004 · Riordinare non è un effetto collaterale
**Priorità:** Must Have

Come chi rinomina un dispositivo, voglio che resti dov'è, perché cambiare il nome di una
presa non è chiedere di riorganizzare l'elenco.

**Criteri di accettazione:**
- [ ] Cambiare il nome di un dispositivo non ne cambia la posizione
- [ ] Dopo un riordino gli id locali sono gli stessi: potenza, kWh e ultimo stato noto
      restano, nessuna scheda torna «in attesa di dati»
- [ ] Dopo quattro riordini, `collegamento.tentativi` non è cambiato
- [ ] Un riordino non provoca nessuna risottoscrizione
- [ ] Modificare un dispositivo **dal telefono** non ne azzera la posizione

### US-005 · L'ordine senza broker
**Priorità:** Should Have

Come chi apre l'app dove il broker non si raggiunge, voglio comunque l'elenco nell'ordine
giusto, perché l'ordine è configurazione, non stato.

**Criteri di accettazione:**
- [ ] Avvio a freddo senza rete: l'elenco compare nell'ordine giusto
- [ ] Nessun fotogramma in cui l'elenco appare alfabetico prima di riordinarsi

### US-006 · Spostare senza trascinare
**Priorità:** Should Have

Come chi usa una tastiera o una lettura dello schermo, voglio poter spostare una riga senza
trascinarla, perché il trascinamento è il gesto che una lettura dello schermo non sa fare.

**Criteri di accettazione:**
- [ ] Ogni riga si sposta su e giù senza gesto di trascinamento
- [ ] I comandi hanno un nome leggibile, e dopo lo spostamento si capisce dove la riga è
      finita
- [ ] I comandi si raggiungono con il solo tasto di tabulazione

### US-007 · L'app vecchia non si rompe
**Priorità:** Must Have

Come chi ha un secondo telefono che non aggiorna, voglio che continui a funzionare davanti a
un registro riordinato.

**Criteri di accettazione:**
- [ ] Un'app 1.2.0 legge il registro nuovo: sette dispositivi, nessuno scartato
- [ ] L'app nuova davanti a un registro senza posizioni mostra l'ordine alfabetico di oggi
- [ ] Un registro che colloca solo alcuni dispositivi mette quelli in cima nell'ordine
      dichiarato e gli altri sotto, per nome

---

## 5. Architettura tecnica

### Componenti coinvolti

```
  CONFIGURATORE WEB (nginx, 8080)
     elenco trascinabile ──┐
     comandi su/giù ───────┤
                           ▼
                  rinumerazione 0,1,2,…      ← B-4: mai buchi, mai a caso
                           │
                           ▼
                  serializza()  +  posizione ← senza questo il campo si perde: la
                           │                    lista delle chiavi è chiusa (R-9)
                           ▼
         documento ritenuto, revisione +1, schema 1
                           │
                    ┌──────┴──────┐
                    ▼             ▼
              broker MQTT    (chiunque altro legga: ignora ciò che non conosce)
                    │
   ─────────────────┼──────────────────────────────────────────────
                    ▼  APP ANDROID
              RegistrySync.onMessage
                    │
              readRegistry ── posizione, con le regole dei valori strani (B-5)
                    │
              planRegistry ── stesso uuid, stesso id locale: si aggiorna in loco
                    │
              Room  devices.position          [colonna nuova, annullabile]
                    │
                    ├──► DeviceDao.observeAll
                    │      ORDER BY (position IS NULL), position, name COLLATE NOCASE
                    │                │
                    │                ▼
                    │         LazyColumn(key = device.id) + animateItem
                    │         le schede scivolano al posto nuovo
                    │
                    └──► MqttDeviceDriver.track
                           cambiati = quelli che NON si ascoltano più allo stesso modo
                                      ╳ la posizione non è fra questi
                                      ▼
                           nessuna risottoscrizione, connessione intatta
```

Il punto dell'intero disegno è l'ultima biforcazione. La posizione attraversa il registro, il
piano, il database e la vista, e **si ferma prima del driver**: il driver continua ad
ascoltare esattamente come ascoltava un istante prima del trascinamento.

### Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| Room `devices` | **Modifica** | Colonna `position INTEGER` annullabile, senza `DEFAULT`. Migrazione **5→6** scritta a mano e additiva, come le quattro che la precedono. `schemas/6.json` generato e committato |
| `Device` (dominio) | Modifica | `val position: Int? = null` in coda alla data class, con predefinito: nessun punto di costruzione esistente va toccato |
| Registro condiviso | **Modifica additiva** | Campo `posizione`. `schema` resta **1**: un lettore vecchio ignora quello che non conosce |
| DataStore | **Nessuna** | L'ordine non è una preferenza del telefono |

### Nuove API o endpoint

| Metodo | Path | Descrizione | Auth |
|---|---|---|---|
| GET | `/devices` | Ogni dispositivo espone la sua `posizione`, e l'array esce **nell'ordine del database**. Additivo: nessun consumatore si rompe | Come oggi |

Non è un contorno: è lo strumento con cui si verificano TC-01 e TC-03 con `curl` invece che
con gli occhi sullo schermo.

### Breaking changes

Nessuno verso l'esterno. Due cose interne da sapere:

| Componente | Cosa cambia | Piano |
|---|---|---|
| `DeviceForm.toDevice(id, uuid)` | Prende un terzo parametro, la posizione da conservare | Un solo punto di chiamata, `DeviceEditViewModel.save()` |
| `smart-home.db` | Passa allo schema 6 | **Non reversibile**: la 1.2.0 non apre un database di schema 6. Vedi §9 |

---

## 6. Piano di implementazione

| ID | Task | Area | Stima (gg) | Dipende da |
|---|---|---|---|---|
| T-01 | `SCHEMA.md`: il campo `posizione`, la regola d'ordinamento, «l'array non si legge», le regole di assegnazione e i valori strani | Doc | 0,20 | — |
| T-02 | `Device.position` e il predicato «si ascolta allo stesso modo», scritto per **esclusione** (`id`, `uuid`, `name`, `room`, `position`) | Dominio | 0,15 | T-01 |
| T-03 | `DeviceEntity`, le due conversioni, `MIGRATION_5_6`, `version = 6`, `addMigrations`, `schemas/6.json` | Dati | 0,20 | T-02 |
| T-04 | L'`ORDER BY` del DAO | Dati | 0,05 | T-03 |
| T-05 | `leggiDispositivo` legge `posizione`: assente resta nulla, negativa o non intera vale come assente | Dominio | 0,15 | T-02 |
| T-06 | `track()` usa il predicato invece del confronto sull'oggetto intero | Driver | 0,10 | T-02 |
| T-07 | `DeviceEditViewModel` conserva la posizione al caricamento e la ripassa al salvataggio (R-8) | FE | 0,10 | T-02 |
| T-08 | `Modifier.animateItem()` sulle schede, e la posizione dentro `/devices` | FE | 0,10 | T-04 |
| T-09 | `serializza()` scrive `posizione`; `leggiRegistro` la normalizza con le stesse regole dell'app | Web | 0,15 | T-01 |
| T-10 | `disegnaElenco()` ordina per posizione e nome; `salva()` non riordina più per nome e assegna la posizione al dispositivo nuovo secondo B-4 | Web | 0,20 | T-09 |
| T-11 | Il trascinamento con i pointer events, i comandi su/giù, lo stile della riga che si muove | Web | 0,50 | T-10 |
| T-12 | Pubblicazione immediata con accorpamento dei tocchi ravvicinati; guardia della revisione estesa al riordino (R-5); nessun ridisegno mentre il dito è giù (R-11) | Web | 0,25 | T-11 |
| T-13 | `tools/genera-registro.mjs` assegna le posizioni; `registro-ponte.json` rigenerato | Test | 0,10 | T-09 |
| T-14 | `genera-registro-dev.mjs` e `registro-dev.json` | Infra | 0,10 | T-09 |
| T-15 | Test JVM: nuovi casi in `DeviceRegistryTest`, `DeviceWiringTest` nuovo, l'ordine asserito in `RegistryContractTest` | Test | 0,30 | T-05, T-06, T-13 |
| T-16 | Verifiche sul campo TC-01…TC-10 contro `devops/dev` | Test | 0,40 | tutti |
| T-17 | `README.md`, `versionCode = 11`, `versionName = "1.3.0"` | Doc | 0,20 | — |

**Stima totale:** 3,25 giorni/uomo
**Breakdown:** Doc 0,40gg · Dominio 0,30gg · Dati 0,25gg · Driver 0,10gg · FE 0,20gg ·
Web 1,10gg · Infra 0,10gg · Test 0,80gg

> La Fase 1 stimava 3,0. La differenza sono i tre difetti trovati in Fase 2 — R-8, R-9 e
> R-11 — più l'accorpamento dei tocchi, che discende dalla decisione di pubblicare subito.
> **Un terzo della feature è il trascinamento** (T-11 e T-12): è la parte che si scrive a
> mano perché il configuratore ha una dipendenza sola e deve continuare ad averne una.

**L'ordine dei lavori.** T-01 apre tutto: le due implementazioni della regola devono
discendere dallo stesso testo, o divergeranno nei casi limite. Da lì i due rami — app e
configuratore — sono indipendenti e si ricongiungono a T-15. Conviene fare prima il ramo
dell'app: con un documento scritto a mano si vede l'elenco riordinarsi prima ancora che il
configuratore sappia produrne uno.

---

## 7. Piano di test

**Strategia generale.** Test JVM per tutto ciò che è una regola — la lettura del campo, il
piano, il predicato delle risottoscrizioni — e verifica sul telefono per ciò che è SQL e
interfaccia, come per le tre feature precedenti. Le prove si fanno contro lo stack di
sviluppo `devops/dev`, **mai contro il Pi**: da lì nessun comando raggiunge una presa vera.

Il contratto fra i due programmi continua a passare da un documento vero:
`app/src/test/resources/registro-ponte.json` è **generato dal codice della pagina web** e
letto dal parser dell'app. È l'unico test che verifica che i due pezzi si capiscano davvero.

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Campo | Riordino nel configuratore, poi `GET /devices`: l'array esce nell'ordine giusto | Alta |
| TC-02 | Campo | Un riordino non risottoscrive niente: `collegamento.tentativi` fermo e nessuna riga «sottoscrizioni rifatte» nel log | **Alta** |
| TC-03 | Campo | Un riordino non azzera le schede: stessi id, potenza e kWh al loro posto | Alta |
| TC-04 | Campo | Avvio a freddo senza rete: l'ordine è giusto al primo fotogramma | Alta |
| TC-05 | Campo | Registro senza posizioni: ordine alfabetico, come la 1.2.0 | Alta |
| TC-06 | Campo | Posizioni parziali, tre su sette: i tre in cima, gli altri sotto per nome | Media |
| TC-07 | Compatibilità | Un'app 1.2.0 legge il registro nuovo: sette dispositivi, nessuno scartato | Alta |
| TC-08 | Campo | Con «segui il registro» spento, modificare un dispositivo dal telefono: la posizione resta (R-8) | Alta |
| TC-09 | Web | Trascinamento **col dito**, sulla pagina aperta all'indirizzo vero — mai da `localhost` | Alta |
| TC-10 | Web | Due schede aperte: riordino in una, salvataggio nell'altra. Chi perde se ne accorge (R-5) | Media |
| TC-11 | Unit | `posizione` letta; assente, negativa, decimale, stringa, duplicata | Alta |
| TC-12 | Unit | Cambia **solo** la posizione → `updated` con lo stesso id | Alta |
| TC-13 | Unit | Il predicato: posizione e nome diversi si ascoltano uguale; topic, chiave JSON, payload e livello massimo diversi no | Alta |
| TC-14 | Unit | Le sette prese si leggono **nell'ordine** del documento | Media |
| TC-15 | Web | I comandi su/giù si raggiungono col tabulatore e dicono dove la riga è finita | Media |

### Definition of Done

- [ ] `./gradlew testDebugUnitTest assembleDebug lintDebug` passa, nessuna segnalazione lint
      nuova rispetto alle 12 note
- [ ] I test JVM nuovi passano, e quelli esistenti non sono stati riscritti per farli passare
- [ ] `registro-ponte.json` e `registro-dev.json` sono **rigenerati**, non modificati a mano
- [ ] TC-01…TC-10 verificati sul telefono contro `devops/dev`, con scritto **come**
- [ ] `SCHEMA.md` e `README.md` aggiornati
- [ ] Rilettura a distanza di un giorno

---

## 8. Rischi e mitigazioni

| Rischio | Prob. | Impatto | Mitigazione |
|---|---|---|---|
| **R-1 · La risottoscrizione di massa.** `MqttDeviceDriver.kt:201` confronta l'oggetto intero: con la posizione dentro `Device`, un riordino di sette dispositivi sono sette risottoscrizioni a ogni salvataggio | Alta **se non si interviene** | Alto | T-06: il confronto guarda ciò che cambia il modo di ascoltare, e ignora identità e presentazione. Scritto per esclusione, così un campo di rete aggiunto domani ci entra da solo. Provato da TC-13 e TC-02. **Corregge anche un falso positivo che esiste già**: oggi pure un refuso corretto in un nome risottoscrive |
| **R-2 · Il trascinamento sul touch.** L'HTML5 drag-and-drop non emette eventi sul dito | Media | Alto | Pointer events, che coprono mouse, dito e penna con lo stesso codice. I comandi su/giù restano comunque, e non come ripiego: sono la strada per la tastiera. TC-09 e TC-15 |
| **R-4 · I NULL vengono primi.** In SQLite, senza `(position IS NULL)` in testa, i dispositivi senza posizione finirebbero in cima — il contrario della regola | Media | Medio | È dentro T-04, e TC-06 lo prova con un registro a posizioni parziali |
| **R-5 · Due scrittori.** La guardia della revisione (`app.js:152`) parte da `stato.modifica`: vale solo a modulo aperto, e un riordino non ne apre nessuno | Bassa | Alto | T-12 la estende al riordino. Un riordino tocca tutte le righe, quindi è la scrittura che ha più da perdere. TC-10 |
| **R-8 · Il modulo del telefono azzera la posizione.** `toDevice` ricostruisce il `Device` da zero, e la posizione non è un campo del modulo | Media | Medio | T-07, con lo stesso pattern che il file usa già per l'uuid. TC-08 |
| **R-9 · Il configuratore cancella ciò che non conosce.** `serializza()` pubblica solo le chiavi di una lista chiusa | Alta **se si dimentica** | Alto | T-09. È la condizione perché il campo sopravviva al primo salvataggio, non un dettaglio |
| **R-11 · L'elenco si ridisegna sotto il dito** se un registro arriva a metà trascinamento | Bassa | Basso | T-12 rinvia il ridisegno alla fine del gesto |
| **R-3 · Due ordini in disaccordo** fra le posizioni e l'ordine dell'array | Bassa | Medio | Contratto (T-01): vince il campo. Il configuratore rinumera comunque in blocco, così il documento non invita nessuno a interpretare l'array |
| **R-7 · Lo stack di sviluppo resta al formato vecchio**, perché il suo registro si rigenera a ogni `up` | Media | Basso | T-14. E il formato vecchio è comunque un caso da provare: è TC-05 |

Fuori tabella, e vale la pena dirlo: **il rischio che questa feature cambi qualcosa a chi non
la usa è nullo per costruzione.** Un registro senza posizioni produce l'ordine alfabetico di
oggi, riga per riga.

---

## 9. Rollout e rollback

**Strategia di rilascio:** deploy diretto, in due pezzi che **non hanno un ordine
obbligato**.

- Se arriva prima il configuratore, pubblica posizioni che i telefoni ignorano: ordine
  alfabetico, come adesso
- Se arriva prima l'app, aspetta posizioni che nessuno scrive: ordine alfabetico, come adesso

Nessuna finestra in cui il sistema è in uno stato strano — è la proprietà che si compra
rendendo il campo facoltativo. L'ordine consigliato resta **prima l'app**, perché così il
primo trascinamento si vede subito funzionare invece di essere una pubblicazione al buio.

**Percorso di consegna**

1. `./gradlew assembleDebug` e installazione sul telefono, verifica contro `devops/dev`
2. Immagine del configuratore costruita sul PC con `buildx --platform linux/arm64`, caricata
   sul Pi con `docker save | ssh docker load` — cioè `./devops/deploy.sh`. **Non** un `rsync`
3. Il registro di casa si riordina dal configuratore, che lo ripubblica alla revisione 9

**Niente feature flag**, per la stessa ragione della 003: il flag sarebbe la feature. Finché
nessuno trascina niente, non c'è niente da spegnere.

**Piano di rollback**

| Se va storto | Cosa fare | Effetto |
|---|---|---|
| L'ordine non convince | Ritrascinare, o pubblicare un registro **senza** `posizione` | Tutto torna alfabetico. Non serve toccare né l'app né le immagini: è la leva vera di questa feature |
| Il configuratore nuovo dà problemi | `IMAGE_TAG` precedente in `.env` sul Pi e `docker compose up -d` | Le posizioni già pubblicate restano nel registro e i telefoni continuano a rispettarle: il configuratore vecchio però le **cancellerebbe** al primo salvataggio di un dispositivo (R-9) |
| La 1.3.0 va rimessa alla 1.2.0 | **Non si può, non pulitamente** | La migrazione Room 5→6 non è reversibile e la 1.2.0 non apre un database di schema 6: servirebbe disinstallare, perdendo i dispositivi registrati a mano. È il contrario della 003, che si poteva downgradare, e discende dall'aver messo la posizione nel dominio invece che in una preferenza. Il registro però ricostruisce tutto: su un telefono che lo segue, la perdita è di fatto nulla |

---

## 10. Checklist di approvazione

Progetto di una persona sola: la revisione è una rilettura a distanza di un giorno, non il
passaggio a qualcun altro. Le righe restano perché le domande sono le stesse.

| Revisione | Cosa chiede | Stato | Data |
|---|---|---|---|
| Revisione tecnica | Il predicato scritto per esclusione (T-02) è la strada giusta, o conviene tenere la posizione fuori da `Device` e pagare il doppio percorso nel piano? | ⏳ In attesa | — |
| Revisione di prodotto | Sette storie sono quello che serve, e pubblicare **subito** a ogni riordino è la scelta giusta rispetto a un «Salva ordine» esplicito? | ⏳ In attesa | — |
| Stima approvata | 3,25 giorni sono accettabili, sapendo che 1,10 sono il solo trascinamento nella pagina web? | ⏳ In attesa | — |
| Rischi accettati | R-1 (si tocca il driver, che è il nervo della 003) e il downgrade impossibile di §9 si accettano? | ⏳ In attesa | — |
| Data di inizio confermata | — | ⏳ In attesa | — |

---

## Domande aperte

Nessuna. Le sette decisioni emerse durante le Fasi 1 e 2 sono state chiuse il 13 settembre
2026 e sono riportate, con il loro esito, nella sezione 3.

---

*Documento generato con la skill `claude-code-feature`.*
