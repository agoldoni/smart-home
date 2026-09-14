# Consumi compatti — Implementation Plan

**Stato:** Implementato e verificato sul telefono — resta il dispiegamento sul Pi
**Autore:** Alberto Goldoni
**Data:** 14 settembre 2026
**Versione:** 1.2 (14 settembre 2026: verifiche sul telefono, TC-05…TC-09 e TC-12)
**Feature:** `005-consumi-compatti`
**Fasi precedenti:** [requisiti](phase-1-requirements.md) · [analisi](phase-2-analysis.md)

---

## 0. Stato di avanzamento

**T-01…T-18 e T-20 sono chiusi**, e **T-19 è quasi chiuso**: la 1.4.0 debug gira sul telefono
contro `devops/dev` e le verifiche che si potevano fare lì sono passate. Resta solo quello
che richiede il **ponte vero sul Pi** e un **telefono fermo alla 1.3.0**.

### Verificato, e come

| Cosa | Come |
|---|---|
| Il ponte | **53 test** (erano 31), nell'immagine `smart-home/tuya-mqtt:latest` come dice `bridge/README.md`. I 22 nuovi sono TC-01…TC-04 più i periodi in locale e la somma sull'intervallo |
| L'app | **111 test**, `./gradlew testDebugUnitTest`. `EnergiaCompattaTest` è nuovo e copre TC-13 e TC-14; `ReadSnapshotNumberTest` copre le due metà di D-7 |
| La compilazione | `./gradlew assembleDebug lintDebug`: **11 segnalazioni lint**, nessuna nuova. Le due `UnusedResources` sono `registry_rejected` e `registry_skipped`, che c'erano già prima |
| Lo schema 7 | `app/schemas/…/7.json` generato e presente: due colonne `TEXT` annullabili, che è esattamente quello che la migrazione scrive |
| **R-10**, il campo che sparisce in silenzio | Il documento è stato serializzato con il codice vero della pagina e riletto: i quattro `campo_kwh_*` ci sono tutti. È il controllo che il rischio chiedeva, fatto sul JSON e non sul sorgente |
| I due registri generati | Rigenerati, non modificati a mano: `registro-ponte.json` con `tools/genera-registro.mjs` e `registro-dev.json` con `genera-registro-dev.mjs` |
| Le prese finte | La scelta del payload provata a parte: `delta` ne pubblica due (i trattini), `golf-…` il caso peggiore `9,99/99,9/999/9999`, gli altri quattro valori |

### Verificato sul telefono, contro `devops/dev`

App **1.4.0-debug** installata il 14/09/2026, registro di sviluppo alla revisione 17.

| TC | Cosa | Esito |
|---|---|---|
| **TC-06** | La riga sulla scheda | `alfa` mostra `0,42/1,87/6,30/12,7 kWh`, nell'ordine e col formato previsti |
| **TC-07** | I trattini | `delta` dichiara tutte e quattro le chiavi ma il payload ne porta due: sulla scheda `0,40/−/−/12,7 kWh`, **caselle al loro posto** |
| **TC-09** | La larghezza | `golf-nome-lungo-…` col caso peggiore `9,99/99,9/999/9999 kWh`: **nessuna ellissi**, sulla scheda col nome più lungo dell'elenco — il nome sì che viene troncato, la riga dei consumi no |
| **TC-12** | Le risottoscrizioni | All'applicazione della revisione 17: «rifatte per golf…, echo-regolabile, alfa, delta, charlie, bravo: configurazione cambiata», **una volta sola**, e `foxtrot-sensore` non c'è perché non ha un topic dei consumi. `collegamento.tentativi` fermo a **1**: la connessione non è mai caduta |
| **TC-14** | Zero non è un trattino | `echo-regolabile` ha `kwh_ieri: 0` e mostra `0,10/0,00/0,80/2,30 kWh` |
| **D-7**, prima metà | Chiave che sparisce | Ripubblicato `alfa` senza `kwh_ieri` e `kwh_settimana`: i due valori tornano a **null** e la scheda mostra i trattini, invece di tenere i numeri vecchi |
| **D-7**, seconda metà | Chiave illeggibile | Ripubblicato `alfa` con `kwh_ieri: "boh"` partendo da 1,87: gli altri tre si aggiornano e **1,87 resta**. È la distinzione che i test unitari coprivano e il campo no |

Le prime due righe si sono lette dall'API di debug (`tools/debug-api.py --adb`) e poi
confermate a occhio su uno screenshot: entrambe le cose, perché l'API dice i valori e lo
schermo dice se ci stanno.

### Quattro cose decise scrivendo, che il piano non diceva

1. **La riga compatta ha un `contentDescription`.** Le barre non si sentono, e un lettore di
   schermo avrebbe letto «zero virgola quarantadue barra uno virgola ottantasette barra…».
   Il piano non lo prevedeva, ma senza sarebbe stata una **regressione** introdotta da
   questa feature: prima la riga diceva «0,84 kWh oggi · 27,3 questo mese», che si sentiva
   benissimo. Sullo schermo non cambia niente e D-1 resta intatta — le etichette non costano
   una riga se non si vedono.
2. **`Contatore.oggi()` è stata rimossa.** `periodi()` la sostituisce del tutto e, dopo T-04,
   non la chiamava più nessuno: lasciarla sarebbe stato un doppione che confonde chi legge.
3. **Il registro di sviluppo sale alla revisione 3** — e **non è bastato**, vedi qui sotto.
4. **La revisione del generatore di sviluppo è diventata un parametro.** Il numero fisso
   nel file era una mezza verità: sul broker di sviluppo scrive anche il **configuratore
   web**, che a ogni salvataggio incrementa per conto suo, e il 13/09 sera l'aveva portato a
   **16** mentre il generatore diceva ancora 2. Pubblicare la 3 non ha fatto **niente** — il
   telefono l'ha ignorata senza rumore, che è esattamente il sintomo per cui la revisione
   esiste — e la feature sembrava non funzionare. Adesso si passa
   `--revisione N`, e `devops/dev/README.md` dice come leggere quella applicata.

### Da finire

Nell'ordine, e **il ponte prima dell'app** come dice §9:

1. **Costruire e dispiegare le immagini** con `./devops/deploy.sh` (`buildx --platform
   linux/arm64`, poi `docker save | ssh docker load`)
2. **TC-05** — `mosquitto_sub` sul topic vero dopo il deploy: quattro valori, tre date, e
   `kwh_ieri` confrontato con la query fatta a mano su `energia.db`. È l'unico modo di
   provare il ponte contro dati veri: **lo stack di sviluppo il ponte non ce l'ha**
3. **Ripubblicare il registro di casa dal configuratore**, che è ciò che porta i due campi
   nuovi alle sette prese vere. Senza questo passo il ponte pubblica quattro valori e le
   schede ne mostrano due, con due trattini
4. **TC-10 e TC-11** — la compatibilità nelle due direzioni, che richiede un telefono fermo
   alla 1.3.0

E resta una cosa che non si verifica guardando: **se dopo una settimana d'uso i quattro
numeri senza etichette si leggano davvero**. È la domanda di §10 a cui si risponde usandola.

---

## 1. Executive Summary

La scheda di una presa mostra oggi due consumi — oggi e questo mese — e nessuno dei due
risponde alla domanda che ci si fa davvero guardandola: *sta consumando più del solito?* Per
rispondere serve **ieri**, e per sapere se ieri era un caso serve **la settimana**. Il dato
c'è già per intero nell'archivio orario che il ponte tiene dal 12 settembre: non è mai stato
pubblicato perché nessuno lo aveva chiesto.

La feature aggiunge i due valori al ponte e li mostra sulla scheda in una riga sola, senza
etichette: `0,42/1,87/6,30/12,7 kWh`. Grazie al formato a tre scalini quella riga è di **22
caratteri contro i 32 di oggi**: si aggiungono due numeri e la riga si accorcia. Tutto è
additivo — un'app vecchia non se ne accorge, un ponte vecchio non fa sbagliare l'app nuova.

**Stima: 3,5 giorni/uomo.** App alla 1.4.0, database allo schema 7.

---

## 2. Obiettivo e motivazione

**Problema che risolve.** `0,84 kWh oggi · 27,3 questo mese` dice due cose che da sole non
servono: «oggi» alle otto di mattina vale 0,08 e alle undici di sera 2,4 — senza un termine
di paragone non si sa se sia tanto o poco — e il mese è la bolletta, che cresce comunque. Il
numero mancante è ieri, che rende leggibile oggi **a qualunque ora**, e la settimana, che
dice se ieri era un'eccezione.

Il secondo problema è lo spazio. Le schede sono sette in una lista che si scorre col pollice:
passare da due numeri a quattro non poteva costare una riga in più per scheda.

**Metriche di successo:**

- [ ] Guardando una scheda si capisce in un colpo d'occhio se oggi si sta consumando più di
      ieri, a qualunque ora del giorno
- [ ] I consumi restano su **una riga sola**: l'elenco dei sette dispositivi occupa lo stesso
      spazio verticale di oggi
- [ ] La riga è più **corta** di quella che sostituisce: 22 caratteri nel caso peggiore
      contro i 32 attuali
- [ ] Un valore che non c'è si vede che non c'è: mai un numero nella casella del vicino
- [ ] Un'app **1.3.0** che riceve il payload nuovo continua a mostrare i suoi due numeri
- [ ] Un ponte **fermo alla versione di oggi** non fa sbagliare l'app nuova: due trattini
- [ ] La prima applicazione del registro nuovo produce **una sola** risottoscrizione

**Legame con il resto.** È la chiusura naturale della 001, che aveva costruito l'archivio
orario dichiarando fuori scope proprio le viste sui periodi. Qui non si costruisce niente di
nuovo: si pubblicano due somme.

---

## 3. Scope

### Incluso

**Il ponte** — `kwh_ieri` e `kwh_settimana` nel payload di `casa/<nome>/energia`, con le due
date a cui si riferiscono. La settimana è quella **di calendario**, da lunedì a adesso, fuso
`Europe/Rome`. `kwh_ieri` **manca** quando l'archivio non ha righe per ieri.

**Il contratto** — `campo_kwh_ieri` e `campo_kwh_settimana` nel registro, facoltativi,
additivi: `schema` resta **1**.

**Il configuratore** — i due campi nel modulo e nel modello della presa del ponte.

**L'app** — migrazione **6→7**, i due valori nello stato osservato, la riga compatta a
quattro caselle con i trattini, i due campi nel modulo di registrazione, i due valori
nell'API di debug.

**Lo stack di sviluppo** — prese finte a quattro valori, **e una a due**, perché il caso del
trattino si veda senza costruirlo a mano.

**La documentazione** — `SCHEMA.md`, `bridge/README.md`, `README.md`, quest'ultimo con la
**legenda dell'ordine**: è l'unico posto dove sarà scritto cosa siano quei quattro numeri.

### Escluso (out of scope)

- **L'anno** — c'è nell'archivio, ma sarebbe un quinto numero e quattro è il limite della riga
- **Il costo in euro** — servirebbe una tariffa, che è configurazione nuova e cambia da sola
- **Grafici e storico sfogliabile** — restano dove la 001 li ha lasciati: `sqlite3`
- **Etichette o legenda sulla scheda** — è D-1: l'ordine si impara, non si scrive
- **Ordine dei quattro numeri configurabile** — distruggerebbe l'unica cosa che rende
  leggibile una riga senza etichette
- **Cambiare lo schema di `energia.db`** — si aggiungono due `SELECT`, non una colonna
- **Ricostruire il passato** — ieri vale quello che l'archivio ha visto, e quando non l'ha
  visto si vede
- **La settimana come finestra mobile** sugli ultimi sette giorni — scartata in D-2

### Decisioni prese

Nessuna decisione aperta. Le sette emerse nelle Fasi 1 e 2 sono chiuse.

| # | Decisione | Esito | Dove è nata |
|---|---|---|---|
| **D-1** | Forma della riga | **Solo numeri, con le barre.** Scartate le etichette sopra (costa una riga per scheda) e quelle in linea (non sta su una riga). Ne discendono due vincoli non negoziabili: l'ordine non cambia mai, e le caselle sono **sempre quattro** | Fase 1 |
| **D-2** | Cosa conta «settimana» | **Settimana corrente, lunedì→adesso.** Coerente con «mese», che è già il mese in corso. Si accetta che il lunedì il terzo numero sia quasi uguale al primo: è lo stesso difetto che «mese» ha il primo del mese | Fase 1 |
| **D-3** | Processo | Documento prima del codice, come per le altre quattro | Fase 1 |
| **D-4** | Valore che l'archivio non ha | **Non si pubblica e non si finge.** Nel ponte la chiave manca, nell'app la casella è un trattino. Zero resta libero di dire l'unica cosa che significa: non ha consumato | Fase 1 |
| **D-5** | Formato dei numeri | **Tre scalini:** `<10` due decimali, `<100` uno, `≥100` nessuno. Non è estetica: tiene ogni casella in **quattro caratteri**, e chiude R-2 e R-3 per costruzione | Fase 1 |
| **D-6** | Nomi dei campi | `kwh_ieri` e `kwh_settimana`, `campo_kwh_ieri` e `campo_kwh_settimana` | Fase 1 |
| **D-7** | Chiave assente nel payload dell'energia | **Il topic si legge come una fotografia:** chiave assente → il valore torna a null (trattino); chiave presente ma illeggibile → si tiene l'ultimo, perché da un valore che non si è capito non si deduce niente. Senza questo, D-4 non funzionerebbe: `readNumber(...)?.let{}` non azzera mai, e un «ieri» vecchio resterebbe sulla scheda per sempre | **Fase 2 (R-11)** |

---

## 4. User Stories e criteri di accettazione

### US-001 · Oggi accanto a ieri
**Priorità:** Must Have

Come chi apre l'app la sera, voglio vedere oggi accanto a ieri, per capire in un istante se il
boiler sta consumando più del solito senza aprire niente.

- [ ] Il payload di `casa/<nome>/energia` contiene `kwh_ieri` e `kwh_settimana`
- [ ] `kwh_ieri` coincide con la somma delle righe d'archivio del giorno precedente, a meno
      dell'arrotondamento a tre decimali
- [ ] `kwh_ieri` **non cambia** durante la giornata: è fatto di sole ore chiuse
- [ ] I quattro numeri compaiono nell'ordine oggi, ieri, settimana, mese

### US-002 · Una riga sola
**Priorità:** Must Have

Come chi guarda l'elenco delle sette schede, voglio che i quattro numeri stiano su una riga
sola, perché lo spazio verticale decide quante schede vedo senza scorrere.

- [ ] La riga resta `maxLines = 1` e l'altezza della scheda non cambia
- [ ] Ogni numero occupa **al massimo quattro caratteri**: `9,99`, `99,9`, `999`, `9999`
- [ ] Il caso peggiore sta in 22 caratteri e non viene troncato sullo schermo più stretto
- [ ] Gli scalini si decidono **sul valore arrotondato**: `9,996` è `10,0` e non `10,00`

### US-003 · Il buco si vede
**Priorità:** Must Have

Come chi legge una riga in cui un numero manca, voglio vedere il buco, perché tre numeri su
quattro senza segnaposto li leggerei nella casella sbagliata.

- [ ] Un payload con `kwh_oggi` e `kwh_mese` soltanto produce `0,42/–/–/12,7 kWh`
- [ ] Le caselle **non si spostano**: il mese resta il quarto numero
- [ ] Un payload senza nessuno dei quattro non produce nessuna riga
- [ ] Zero si mostra `0,00` e **non** come trattino: zero e assente si vedono diversi
- [ ] Una chiave che sparisce dal payload riporta la casella al trattino (D-7)
- [ ] Una chiave presente ma con dentro qualcosa che non è un numero **non** cancella il
      valore precedente (D-7)

### US-004 · L'app vecchia non si accorge di niente
**Priorità:** Must Have

Come chi ha un secondo telefono fermo alla 1.3.0, voglio che continui a mostrare oggi e mese
come ha sempre fatto, perché non aggiorno due telefoni lo stesso giorno.

- [ ] Un'app 1.3.0 che riceve il payload a quattro valori mostra `0,42 kWh oggi · 12,7 questo
      mese`, senza errori
- [ ] Un'app 1.3.0 che legge il registro con i due campi nuovi mostra i sette dispositivi,
      nessuno saltato

### US-005 · I campi arrivano compilati
**Priorità:** Should Have

Come chi aggiunge una presa dal configuratore, voglio che i due campi nuovi arrivino già
compilati dal modello, perché sono sempre gli stessi due nomi.

- [ ] «Usa modello» compila `campo_kwh_ieri` e `campo_kwh_settimana`
- [ ] Il registro pubblicato **contiene** i due campi: non spariscono alla serializzazione
- [ ] Un registro senza i due campi non fa saltare nessun dispositivo

### US-006 · La settimana è di calendario
**Priorità:** Must Have

Come chi guarda la scheda di lunedì mattina, voglio che «settimana» si sia azzerata, perché è
un totale di calendario come il mese e deve comportarsi come il mese.

- [ ] Include lunedì e **non** include la domenica precedente
- [ ] Lunedì alle 00:00 **locali** riparte e vale quanto `kwh_oggi`
- [ ] La settimana che contiene un cambio d'ora coincide con la somma delle righe di quei
      sette giorni

### US-007 · Ponte vecchio, app nuova
**Priorità:** Must Have

Come chi ha aggiornato l'app ma non ancora il ponte, voglio vedere i due numeri che il ponte
manda e il trattino sugli altri due, invece di una riga sparita o di due zeri inventati.

- [ ] Col ritenuto vecchio ancora sul broker la scheda mostra `0,42/–/–/12,7 kWh`
- [ ] Al primo payload del ponte aggiornato la riga si completa senza riavviare l'app

---

## 5. Architettura tecnica

### Componenti coinvolti

```
  PONTE (bridge.py)
    energia.db  ┌── energia_grezza: una riga per presa e per ora, giorno LOCALE
                │   indice (giorno, presa) ← il BETWEEN della settimana lo usa (R-9)
                └── vista energia: grezzo × wh_per_unita / 1000
                        │
        Archivio.totale(presa, prefisso)      giorno e mese, COALESCE→0   [invariato]
        Archivio.somma(presa, dal, al)  ──►  (kWh, righe)                 [nuovo]
                        │                      └─ righe = 0 distingue
                        │                         "non ha consumato" da "nessuno contava"
                        ▼
        Contatore.periodi() ──► giorno, mese, ieri, lunedì
                        │       calcolati su date.date(), MAI su epoch-86400
                        │       ← due giorni l'anno hanno 23 e 25 ore (C.9)
                        ▼
        _rileggi_totali()   alla chiusura di un'ora e all'avvio, mai nel giro di polling
                        │   ← regge perché conta() è chiamata su TUTTI i rami del ciclo,
                        │     presa irraggiungibile compresa (R-4, verificato)
                        ▼
        _pubblica_energia()
          kwh_oggi + kwh_settimana + kwh_mese   ← archivio + ora in corso
          kwh_ieri                              ← solo ore chiuse, OMESSO se righe = 0
                        │
                        ▼
        casa/<nome>/energia   JSON ritenuto
                        │
   ─────────────────────┼──────────────────────────────────────────────
                        │        REGISTRO (configuratore web)
                        │   campi.js ──► il modulo
                        │   modelli.js ──► i due nomi precompilati
                        │   registro.js ──► NULLABILI **e** serializza()
                        │                   ╳ scordarne uno = il campo sparisce
                        │                     alla pubblicazione, in silenzio (R-10)
                        ▼
   ─────────────────────┼──────────────────────────────────────────────
                        ▼  APP ANDROID
        readRegistry ── campo_kwh_ieri, campo_kwh_settimana
                        │
        planRegistry ── stesso uuid, stesso id locale: aggiorna in loco
                        │
        Room devices ── energyYesterdayJsonKey, energyWeekJsonKey   [migrazione 6→7]
                        │
                        ├──► MqttDeviceDriver.track
                        │      listensLike: i due campi NE FANNO PARTE, ed è giusto
                        │      → una risottoscrizione sola, al primo registro nuovo (R-7)
                        │
                        ├──► onMessage, ramo del topic dell'energia
                        │      fotografia: chiave assente → null; illeggibile → si tiene (D-7)
                        │
                        └──► DeviceState  kwhToday/Yesterday/Week/Month
                                     │
                                     ▼
                          energiaCompatta()   ← funzione PURA, in un file suo (R-12)
                             quattro caselle sempre, trattino per chi manca
                             tre scalini sul valore arrotondato
                                     │
                                     ▼
                             0,42/1,87/6,30/12,7 kWh
```

Il punto del disegno è che **l'unica cosa nuova è una somma**. Nessun servizio, nessuna
tabella, nessun topic: due `SELECT` su un archivio che esiste da due giorni e quattro campi
che attraversano il sistema senza cambiare la forma di niente.

### Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| Room `devices` | **Modifica** | Due colonne `TEXT` annullabili, **senza `DEFAULT`**. Migrazione **6→7** scritta a mano e additiva, come le cinque che la precedono. `schemas/7.json` generato e committato |
| `Device` (dominio) | Modifica | `energyYesterdayJsonKey` e `energyWeekJsonKey`, `String? = null`, accanto ai due che ci sono. Entrano in `listensLike` **da soli**, perché è scritto per esclusione — ed è corretto: cambiano come si legge il payload |
| `DeviceState` | Modifica | `kwhYesterday` e `kwhWeek`, `Double? = null` |
| `energia.db` | **Nessuna** | `energia_grezza`, `fattori` e la vista `energia` restano identiche |
| Registro condiviso | **Modifica additiva** | Due campi. `schema` resta **1** |
| DataStore | **Nessuna** | Non è una preferenza del telefono |

### Nuove API o endpoint

| Metodo | Path | Descrizione | Auth |
|---|---|---|---|
| GET | `/devices` | `payloads` prende `energyYesterdayJsonKey` e `energyWeekJsonKey`; `state` prende `kwhYesterday` e `kwhWeek`. Additivo | Come oggi |

Non è un contorno: è come si verificano TC-06, TC-07 e TC-08 con `curl` invece che con gli
occhi sullo schermo.

### Breaking changes

Nessuno verso l'esterno: payload e registro sono additivi in tutte e quattro le direzioni
(§B.2 dell'analisi). Due cose interne da sapere:

| Componente | Cosa cambia | Piano |
|---|---|---|
| `formatKwh` | Da due scalini a tre, e la soglia si valuta sul valore arrotondato | Usata **solo** dalla riga dei consumi: nessun altro chiamante |
| `smart-home.db` | Passa allo schema 7 | **Non reversibile**: la 1.3.0 non apre un database di schema 7. Vedi §9 |

---

## 6. Piano di implementazione

| ID | Task | Area | Stima (gg) | Dipende da |
|---|---|---|---|---|
| T-01 | `SCHEMA.md`: i due campi nella tabella, e la nota che `schema` resta 1 | Doc | 0,10 | — |
| T-02 | `Archivio.somma(presa, dal, al)` → `(kWh, righe)`, con `BETWEEN` sui giorni. `totale()` **resta**: giorno e mese continuano a usarla | Ponte | 0,20 | — |
| T-03 | `Contatore.periodi()`: giorno, mese, ieri e lunedì calcolati sulla `date` locale con `timedelta` | Ponte | 0,15 | — |
| T-04 | `_rileggi_totali()` rilegge i due totali; `_pubblica_energia()` scrive i due valori e le due date, e **omette** `kwh_ieri` quando righe = 0 | Ponte | 0,20 | T-02, T-03 |
| T-05 | Test del ponte: `TotaliDiIeri`, `IeriSenzaRighe`, `SettimanaDiCalendario`, `SettimanaConCambioDOra` | Test | 0,25 | T-04 |
| T-06 | `bridge/README.md`: il payload nuovo, cosa vuol dire «settimana», perché `kwh_ieri` può mancare | Doc | 0,15 | T-04 |
| T-07 | Prese finte a quattro valori, **e una a due** | Infra | 0,10 | — |
| T-08 | `campi.js` (due voci), `modelli.js` (i due nomi), `registro.js` (**`NULLABILI` e `serializza()`**, R-10) | Web | 0,20 | T-01 |
| T-09 | `genera-registro-dev.mjs`; `registro-dev.json` e `registro-ponte.json` **rigenerati** | Infra | 0,15 | T-08 |
| T-10 | `Device` e `DeviceState`: i quattro campi nuovi | Dominio | 0,10 | T-01 |
| T-11 | `DeviceEntity` e le due conversioni, `MIGRATION_6_7`, `version = 7`, `addMigrations`, `schemas/7.json` | Dati | 0,20 | T-10 |
| T-12 | `leggiDispositivo()` legge i due campi | Dominio | 0,10 | T-10 |
| T-13 | Il ramo dell'energia nel driver con la semantica a fotografia (D-7): serve saper distinguere «chiave assente» da «valore illeggibile», che oggi `extractJson` confonde | Driver | 0,20 | T-10 |
| T-14 | `EnergiaCompatta.kt`: estrazione della riga in una funzione pura, **a comportamento invariato** | FE | 0,10 | T-10 |
| T-15 | Il formato nuovo: tre scalini sul valore arrotondato, quattro caselle, i trattini, le stringhe | FE | 0,20 | T-14 |
| T-16 | Il modulo: i due campi in form, ViewModel e schermata, e le due etichette | FE | 0,15 | T-11 |
| T-17 | `DebugReport`: le due chiavi JSON e i due valori | Driver | 0,10 | T-10 |
| T-18 | Test JVM: `EnergiaCompattaTest` nuovo, più i casi in `DeviceRegistryTest`, `DeviceListensLikeTest`, `RegistryContractTest`, `MqttPayloadsTest` | Test | 0,30 | T-09, T-13, T-15 |
| T-19 | Verifiche sul campo TC-05…TC-12 | Test | 0,35 | tutti |
| T-20 | `README.md` **con la legenda dell'ordine**, `versionCode = 12`, `versionName = "1.4.0"` | Doc | 0,20 | T-15 |

**Stima totale:** 3,5 giorni/uomo
**Breakdown:** Doc 0,45gg · Ponte 0,55gg · Web 0,20gg · Dominio 0,20gg · Dati 0,20gg ·
Driver 0,30gg · FE 0,45gg · Infra 0,25gg · Test 0,90gg

> La Fase 1 stimava 2,5. Il giorno di differenza sono tre cose trovate in Fase 2: **D-7**, che
> ha trasformato «due righe da copiare» in una decisione sul contratto di lettura (T-13);
> **l'estrazione** della riga in una funzione pura, senza la quale il formato non sarebbe
> provabile (T-14); e **`DebugReport`**, che nella Fase 1 non era stato contato (T-17). Il
> resto è nei test del ponte, che da uno sono diventati quattro.

**L'ordine dei lavori.** T-01 apre, come sempre: il contratto prima dei due programmi che lo
implementano. Poi i due rami sono indipendenti — il ponte (T-02…T-06) e l'app (T-08…T-17) —
e si ricongiungono a T-18. Conviene fare **prima il ponte**, per due motivi: è lì che sta
l'unica logica nuova, e le prese finte di T-07 devono imitare un payload che esiste già,
invece di inventarne uno che poi il ponte scriverà diverso.

Dentro il ramo dell'app, T-14 prima di T-15 non è pignoleria: estrarre e riscrivere in due
passi vuol dire che, se un test fallisce, si sa di chi è la colpa.

---

## 7. Piano di test

**Strategia generale.** Test unitari per tutto ciò che è una regola — le somme sui periodi,
il lunedì locale, il formato, la lettura del payload, la lettura del registro — e verifica
sul campo per ciò che è interfaccia e dispiegamento.

**Una cosa va detta chiara: lo stack di sviluppo non ha il ponte.** `devops/dev` pubblica
payload finti con `mosquitto_pub`; il ponte Tuya non c'è, ed è il motivo per cui da lì nessun
comando raggiunge una presa vera. Ne discende la divisione delle verifiche:

- **il ponte** si prova con i test unitari (TC-01…TC-04) e poi, dopo il dispiegamento, con
  `mosquitto_sub` sul topic vero (TC-05)
- **l'app** si prova contro `devops/dev`, con prese finte che imitano il payload nuovo

Il contratto fra configuratore e app continua a passare da un documento vero:
`registro-ponte.json` è **generato dal codice della pagina web** e letto dal parser dell'app.

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Unit ponte | `kwh_ieri` somma solo il giorno precedente e **non si muove** quando arrivano letture di oggi | Alta |
| TC-02 | Unit ponte | Archivio con le sole righe di oggi: la chiave `kwh_ieri` **non compare nel payload**. Da verificare sul payload, non sul metodo | **Alta** |
| TC-03 | Unit ponte | Righe da domenica a mercoledì: la somma parte dal lunedì, la domenica resta fuori. Più il caso «oggi è lunedì» (settimana = oggi) e uno a cavallo di due mesi | Alta |
| TC-04 | Unit ponte | La settimana del 25 ottobre 2026, quella da 169 ore: la somma pubblicata coincide con la somma delle righe. È il caso che C.9 esiste per non sbagliare | Alta |
| TC-05 | Campo (Pi) | `mosquitto_sub` sul topic vero dopo il deploy: quattro valori, tre date, e `kwh_ieri` coerente con la query fatta a mano su `energia.db` | Alta |
| TC-06 | Campo (dev) | La scheda mostra i quattro numeri nell'ordine; `GET /devices` riporta `kwhYesterday` e `kwhWeek` | Alta |
| TC-07 | Campo (dev) | La presa finta che pubblica due valori: due trattini, e **le caselle al loro posto** | Alta |
| TC-08 | Campo (dev) | Ripubblicato il payload **senza** una chiave, la casella torna al trattino; ripubblicato con dentro `"ciao"`, il valore precedente **resta** (D-7) | **Alta** |
| TC-09 | Campo (dev) | Payload costruito col caso peggiore (`9,99/99,9/999/9999`): nessuna ellissi sullo schermo più stretto | Alta |
| TC-10 | Compatibilità | Un'app **1.3.0** legge il registro nuovo e il payload nuovo: sette dispositivi, nessuno scartato, due numeri come sempre | Alta |
| TC-11 | Compatibilità | L'app **1.4.0** contro il registro vecchio e il ritenuto vecchio: due trattini, niente errori | Alta |
| TC-12 | Campo (dev) | Prima applicazione del registro nuovo: **una sola** riga «sottoscrizioni rifatte» nel log, e nessuna alle pubblicazioni successive (R-7) | Media |
| TC-13 | Unit | I tre scalini e le soglie: `9,99`, `9,996`→`10,0`, `99,9`, `99,96`→`100`, `439`. Ogni risultato al massimo quattro caratteri | Alta |
| TC-14 | Unit | Zero resta `0,00` e non diventa trattino; nessuno dei quattro → `null`, nessuna riga | Alta |
| TC-15 | Unit | Registro **senza** i due campi: valori null, dispositivo **non** saltato | Alta |
| TC-16 | Unit | `listensLike`: cambiare `energyYesterdayJsonKey` cambia l'ascolto, cambiare il nome no | Media |

### Definition of Done

- [ ] `cd bridge/tuya-mqtt && python -m unittest test_bridge -v` passa
- [ ] `./gradlew testDebugUnitTest assembleDebug lintDebug` passa, nessuna segnalazione lint
      nuova rispetto alle note
- [ ] I test esistenti non sono stati riscritti per farli passare
- [ ] `registro-ponte.json` e `registro-dev.json` sono **rigenerati**, non modificati a mano
- [ ] TC-05…TC-12 verificati, con scritto **come**
- [ ] `SCHEMA.md`, `bridge/README.md` e `README.md` aggiornati, la legenda dell'ordine
      compresa
- [ ] Rilettura a distanza di un giorno

---

## 8. Rischi e mitigazioni

| Rischio | Prob. | Impatto | Mitigazione |
|---|---|---|---|
| **R-10 · Il configuratore cancella ciò che non conosce.** `serializza()` (`registro.js:125`) costruisce il documento da una lista chiusa: un campo aggiunto solo in `campi.js` compare nel modulo, si compila, si salva — e sparisce alla pubblicazione, senza nessun errore | Alta **se si dimentica** | Alto | T-08 tocca i due punti insieme. T-09 rigenera il documento del contratto e T-18 lo asserisce: è il test che se ne accorge. Il commento su `posizione` (:158) è lì perché è già successo |
| **R-11 · `readNumber` non azzera mai.** Con D-4, una chiave che manca lascerebbe sulla scheda un «ieri» vecchio per sempre | Alta **se non si interviene** | Medio | **Chiuso da D-7** e implementato in T-13. Provato da TC-08, che è il test da non saltare: è l'unico che distingue le due metà della decisione |
| **R-12 · La riga non è testabile dov'è**, perché `energyLine()` è `private` e `@Composable` | Alta | Medio | T-14 la estrae a comportamento invariato, T-15 la riscrive. Due passi, non uno |
| **R-1 · La riga senza etichette non si spiega da sé** | Certa | Basso | È il costo accettato di D-1. Mitigato dai due vincoli che ne discendono — ordine fisso, caselle sempre quattro — e dalla legenda in `README.md` (T-20) |
| **R-4 · I totali si rileggono solo alla chiusura di un'ora** | Bassa | Medio | **Verificato in Fase 2 e regge:** `conta()` è chiamata su tutti e tre i rami del ciclo, presa irraggiungibile compresa. L'invariante è già dichiarata in un commento a `bridge.py:568`: non va toccata |
| **R-6 · Zero contro assente** | Media | Medio | T-02 restituisce anche il numero di righe. Serve a `kwh_ieri` e basta: gli altri tre contengono sempre l'ora in corso |
| **R-7 · I due campi cambiano `listensLike`** e la prima applicazione del registro nuovo risottoscrive le sette prese | Certa | Basso | È corretto che sia così, ed è **una volta sola**. TC-12 verifica che non si ripeta a ogni pubblicazione |
| **R-5 · Il ritenuto vecchio sul broker** finché il ponte non è aggiornato | Certa | Basso | È US-007, cioè un requisito e non un incidente. Mitigato dall'ordine di §9: il ponte prima dell'app |
| **R-2, R-3 · Formato e larghezza** | Bassa | Basso | **Chiusi da D-5** per costruzione: il caso peggiore è 22 caratteri contro i 32 di oggi. TC-09 e TC-13 lo verificano invece di fidarsi |
| **R-8 · Lo stack di sviluppo resta al formato vecchio**, perché il registro si rigenera a ogni `up` | Media | Basso | T-07 e T-09. E il formato vecchio resta comunque un caso da provare: è TC-11 |
| **R-9 · Costo delle query** | Bassa | Basso | **Chiuso:** l'indice `energia_per_giorno` ha `giorno` come prima colonna, quindi il `BETWEEN` lo usa. Sette prese, una volta l'ora |

Fuori tabella: **il rischio che questa feature cambi qualcosa a chi non la usa è nullo per
costruzione.** Un payload senza i due campi nuovi produce due trattini accanto ai due numeri
di sempre, e un registro senza i due campi non fa saltare niente.

---

## 9. Rollout e rollback

**Strategia di rilascio:** deploy diretto, in due pezzi con un ordine **consigliato ma non
obbligato**.

- Se arriva prima il **ponte**, pubblica due valori che nessuno legge: le schede restano
  come sono
- Se arriva prima l'**app**, mostra due trattini finché il ponte non si aggiorna: è US-007

Nessuna finestra in cui il sistema sta in uno stato strano — è la proprietà che si compra
rendendo tutto facoltativo. L'ordine consigliato è **prima il ponte**, perché non c'è motivo
di far vedere a nessuno una riga con due trattini.

**Percorso di consegna**

1. Test unitari del ponte e dell'app sul PC
2. Verifica dell'app contro `devops/dev`, con le prese finte aggiornate (TC-06…TC-09)
3. Immagini costruite sul PC con `buildx --platform linux/arm64`, caricate sul Pi con
   `docker save | ssh docker load` — cioè `./devops/deploy.sh`. **Non** un `rsync`
4. `mosquitto_sub` sul topic vero per TC-05, prima di toccare i telefoni
5. Il registro di casa si ripubblica **dal configuratore**, che sale alla revisione
   successiva
6. `./gradlew assembleDebug` e installazione sul telefono

**Niente feature flag.** Il flag sarebbe la feature: finché il ponte non pubblica i due
valori, non c'è niente da accendere.

**Piano di rollback**

| Se va storto | Cosa fare | Effetto |
|---|---|---|
| I quattro numeri non convincono | Niente da spegnere: basta **non pubblicare** i due campi nel registro | Le due caselle nuove diventano trattini e la scheda torna a dire quello che diceva, in forma compatta |
| Il ponte nuovo dà problemi | `IMAGE_TAG` precedente in `.env` sul Pi e `docker compose up -d` | Il ritenuto vecchio resta finché il ponte non ripubblica; le app nuove mostrano due trattini. **L'archivio non si tocca**: la feature non ci ha mai scritto dentro |
| Il configuratore nuovo dà problemi | Stesso rollback per tag | Le app nuove restano coi due campi già in database. Attenzione: un configuratore **vecchio** cancellerebbe i due campi al primo salvataggio, per R-10 |
| La 1.4.0 va rimessa alla 1.3.0 | **Non si può, non pulitamente** | La migrazione 6→7 non è reversibile e la 1.3.0 non apre un database di schema 7: servirebbe disinstallare, perdendo i dispositivi registrati a mano. Su un telefono che segue il registro la perdita è però di fatto nulla — il registro li ricostruisce tutti |

---

## 10. Checklist di approvazione

Progetto di una persona sola: la revisione è una rilettura a distanza di un giorno, non il
passaggio a qualcun altro. Le righe restano perché le domande sono le stesse.

| Revisione | Cosa chiede | Stato | Data |
|---|---|---|---|
| Revisione tecnica | D-7 — leggere il topic dell'energia come una fotografia — è la scelta giusta, sapendo che cambia (in meglio) anche il comportamento dei due campi che esistono già? | ⏳ In attesa | — |
| Revisione di prodotto | Quattro numeri senza etichette sono leggibili davvero, o dopo una settimana d'uso servirà la legenda sulla scheda? La risposta si dà **usandola**, non approvandola | ⏳ In attesa | — |
| Stima approvata | 3,5 giorni sono accettabili, sapendo che 0,9 sono test e che un giorno pieno è arrivato dall'analisi? | ⏳ In attesa | — |
| Rischi accettati | R-10 (il campo che sparisce in silenzio) e il downgrade impossibile di §9 si accettano? | ⏳ In attesa | — |
| Data di inizio confermata | — | ⏳ In attesa | — |

---

## Domande aperte

Nessuna. Le sette decisioni emerse nelle Fasi 1 e 2 sono chiuse e riportate, con il loro
esito e il punto in cui sono nate, nella sezione 3.

---

*Documento generato con la skill `claude-code-feature`.*
