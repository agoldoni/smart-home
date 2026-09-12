# Configuratore web e registro condiviso dei dispositivi — Implementation Plan

**Stato:** Completo — nessuna domanda aperta, in attesa di approvazione
**Autore:** Alberto Goldoni
**Data:** 12 settembre 2026
**Versione:** 1.0
**Feature:** `002-configuratore-web` · [Fase 1](phase-1-requirements.md) · [Fase 2](phase-2-analysis.md)

---

## 1. Executive Summary

Oggi i dispositivi di casa vanno registrati a mano su ogni telefono: una presa del ponte
vuole otto campi fra topic e chiavi JSON, le prese sono sette, e un topic sbagliato non dà
errore — dà una scheda ferma su "in attesa di dati".

Questa feature aggiunge una **pagina web** da cui si configurano i dispositivi una volta
sola. La configurazione viene pubblicata sul broker MQTT di casa come messaggio ritenuto, e
ogni app che si collega — adesso, o fra tre settimane su un telefono che ancora non esiste —
la riceve e si allinea. Nessun server applicativo e nessun database: il broker tiene già
l'ultimo valore e lo consegna a chi si iscrive, ed è lo stesso meccanismo per cui l'app trova
subito lo stato delle prese quando riapre.

**Stima: 6,5 giorni/uomo.** La pagina è statica, il broker impara a parlare WebSocket, e
l'app Android impara a leggere il registro e ad applicarlo.

---

## 2. Obiettivo e motivazione

- **Problema che risolve:** la configurazione dei dispositivi è duplicata su ogni telefono e
  si inserisce a mano. `Device` ha 21 campi, il modulo ne mostra 18, e le sette prese del
  ponte ne richiedono otto non banali ciascuna — circa 112 stringhe digitate su due telefoni,
  di cui metà topic che falliscono in silenzio.

- **Metriche di successo:**
  - [ ] **Campi digitati a mano per aggiungere una presa a tutta la casa: da ~16 (otto per
        telefono, due telefoni) a 1** — il nome, con il resto compilato dal modello del ponte
  - [ ] **Un telefono nuovo è operativo senza nessuna registrazione manuale**: si configura
        il broker e i dispositivi ci sono
  - [ ] **Una modifica raggiunge un telefono spento**: riacceso, si allinea da solo
  - [ ] **Zero regressioni sul giro dei comandi**: `/state` non segnala nessuna anomalia
        nuova dopo l'applicazione di un registro, e il contatore dei comandi inviati continua
        a salire come prima

- **Legame con gli obiettivi del progetto:** il README dichiara fra i limiti attuali
  *«nessuna scoperta automatica dei dispositivi: si registrano a mano»*. Questa feature non
  toglie la registrazione a mano — la fa fare **una volta sola, in un posto solo**, che è il
  passo che rende sopportabile il resto. Resta nello spirito dichiarato: niente cloud,
  niente account, tutto dentro casa.

---

## 3. Scope

### Incluso

**Broker e stack**
- Listener websockets su Mosquitto (porta 9001), autenticato come il 1883
- Servizio `configuratore` in `bridge/compose.yml`: `nginx:alpine` con i file statici, sulla
  porta `${CONFIGURATORE_PORT:-8080}` come già fa WireGuard con la sua
- Client MQTT JavaScript dentro l'immagine, non da CDN

**Il registro**
- Un solo messaggio ritenuto su `<prefisso>/registro/dispositivi`, QoS 1, con `schema`,
  `revisione`, `aggiornato` e l'elenco completo dei dispositivi
- **Il prefisso è configurabile** (predefinito `casa`), sulla pagina e nell'app: è quello che
  rende possibile una seconda istanza — stesso broker, `ufficio/registro/dispositivi`, un
  altro insieme di dispositivi — cambiando una stringa e niente altro
- Nomi dei campi in **snake_case italiano**, la convenzione dei payload del ponte
- Regole di rifiuto esplicite: quello che non si capisce non si applica

**Web app**
- Elenco, creazione, modifica, duplicazione, eliminazione
- I 17 campi del registro (quelli del modulo Android meno `stanza`) con le stesse cinque validazioni
- Modelli precompilati: presa del ponte, Tasmota, Zigbee2MQTT
- Login con le credenziali del broker, password solo in memoria
- Client id casuale a ogni apertura
- Avviso di conflitto sulla revisione

**App Android**
- Sottoscrizione al registro indipendente dai dispositivi seguiti
- Applicazione al database locale **in loco per `uuid`**, conservando l'`id`
- `uuid` su `Device`, migrazione Room 4→5, adozione per topic di stato dei dispositivi già registrati
- Conferma esplicita alla prima applicazione, con il conto di cosa verrà rimosso
- Modulo in sola lettura mentre l'app segue il registro; interruttore "segui il registro" in Impostazioni
- Stato del registro e anomalie in `/state`

**Documentazione**
- `README.md`, `bridge/README.md`, e lo schema del documento

### Escluso (out of scope)

- **La configurazione del ponte** (`dispositivi.yaml`: id Tuya, chiavi locali, dp) — il
  registro dice alle *app* cosa mostrare, il yaml dice al *ponte* con chi parlare, e contiene
  chiavi che non devono passare da una pagina web
- **Le impostazioni del broker** (indirizzo, credenziali, client id) — restano per telefono:
  sono ciò che serve per *ricevere* il registro, quindi non possono arrivarci dentro
- **Scoperta dei topic attivi** («qui pubblica qualcosa e nessuno lo mostra») — è il seguito
  naturale, non è necessario a far funzionare il registro
- **Proposte dal ponte** (il ponte che pubblica da sé la configurazione delle prese che traduce) — stessa ragione
- **Bidirezionale** — l'app non scrive sul registro: è la decisione presa, non una mancanza
- **Storico e rollback del registro** — una revisione sola, quella corrente
- **MQTT discovery di Home Assistant**
- **HTTPS e TLS** — la pagina è in chiaro sulla LAN, come il broker sul 1883
- **Utenti e permessi** — un solo utente del broker, come oggi
- **Il campo `stanza` nel registro** — l'app non ci fa niente (il README lo dice già:
  *«la stanza è solo un'etichetta: non ordina né raccoglie le schede»*), e un campo che
  nessuno legge invecchia male. La colonna `room` resta nel database — toglierla sarebbe una
  migrazione distruttiva per zero guadagno — e il registro semplicemente **non la tocca**
- **`androidTest` per la migrazione Room** — vedi la decisione 3 qui sotto

### Decisioni aperte

Nessuna bloccante. Le cinque che lo erano sono state chiuse; restano le domande di fondo
pagina, che non impediscono di cominciare.

| # | Decisione | Esito | Data |
|---|---|---|---|
| 1 | Stack della web app | Statica + MQTT su WebSocket | 12/09/2026 |
| 2 | Fonte di verità | La web app, sola | 12/09/2026 |
| 3 | Accesso | Credenziali del broker | 12/09/2026 |
| 4 | Come il driver espone un topic non legato a un dispositivo | `watch(topic, qos)` + `Flow` su `DeviceDriver` | 12/09/2026 |
| 5 | Nomi dei campi JSON | snake_case italiano | 12/09/2026 |
| 6 | `androidTest` per la migrazione 4→5 | No, coerenza con le tre precedenti | 12/09/2026 |
| 7 | Dove sta il topic del registro | Sotto il prefisso dei dispositivi, **derivato da un prefisso configurabile** | 12/09/2026 |
| 8 | Cosa significa un payload vuoto | Scelta prudente: smetti di seguire, tieni quello che hai | 12/09/2026 |
| 9 | Il campo `stanza` nel registro | Fuori: non serve ora | 12/09/2026 |
| 10 | La porta del configuratore | `CONFIGURATORE_PORT` in `.env`, predefinito 8080 | 12/09/2026 |
| 11 | Dove girerà lo stack | Raspberry, sempre acceso: il configuratore ci va insieme | 12/09/2026 |

---

## 4. User Stories e criteri di accettazione

### US-001 · Registrare un dispositivo da browser
**Priorità:** Must Have

Come chi ha appena montato una presa voglio registrarla una volta sola da un browser per non
ridigitare gli stessi otto topic su ogni telefono di casa.

**Criteri di accettazione:**
- [ ] La pagina si collega al broker via WebSocket con utente e password; con credenziali
      sbagliate mostra il rifiuto del broker, non una pagina vuota
- [ ] Un dispositivo creato compare nel messaggio ritenuto entro un secondo, con `revisione` incrementata
- [ ] `mosquitto_sub -t casa/registro/dispositivi -C 1` restituisce il documento completo anche a pagina chiusa
- [ ] Cambiando il prefisso in `ufficio`, la pagina scrive su `ufficio/registro/dispositivi` e non tocca più `casa/`
- [ ] Chiudendo e riaprendo la pagina l'elenco si ripopola dal broker: la pagina non tiene stato proprio
- [ ] La password non finisce in `localStorage` né in `sessionStorage`

### US-002 · Modelli che compilano i topic
**Priorità:** Must Have

Come chi registra le sette prese del ponte voglio un modello che compili i topic dal nome per
non scrivere a mano cinquantasei stringhe in cui un carattere sbagliato non dà errore ma un
dispositivo che non riceve niente.

**Criteri di accettazione:**
- [ ] Scelto *presa del ponte* e scritto `frigorifero`, i quattro topic e i quattro campi JSON
      risultano compilati e coincidono **carattere per carattere** con quelli pubblicati dal ponte
- [ ] I modelli Tasmota e Zigbee2MQTT producono le configurazioni degli esempi del README
- [ ] Un topic di comando con `+` o `#` è rifiutato dal modulo, con il motivo

### US-003 · Un telefono nuovo trova casa già configurata
**Priorità:** Must Have

Come chi installa l'app su un telefono nuovo voglio trovarci dentro i dispositivi di casa
appena collego il broker per non rifare la registrazione da capo.

**Criteri di accettazione:**
- [ ] App appena installata: configurato il broker, i dispositivi del registro compaiono senza toccare "Aggiungi"
- [ ] Le schede si popolano di stato, potenza ed energia: il registro porta una configurazione, non uno stato
- [ ] Il modulo di registrazione è in sola lettura e dice dove si modifica

### US-004 · Modifiche e cancellazioni raggiungono tutti
**Priorità:** Must Have

Come chi ha rinominato o tolto una presa voglio che la modifica arrivi a tutti i telefoni,
anche a quelli spenti in quel momento, per non ritrovarmi una scheda che comanda un topic che
non esiste più.

**Criteri di accettazione:**
- [ ] Un dispositivo rinominato cambia nome nell'app **senza perdere lo stato già ricevuto**
- [ ] Un dispositivo eliminato sparisce anche da un telefono che era **spento** al momento dell'eliminazione
- [ ] Cambiare un topic di stato produce l'annullamento della vecchia sottoscrizione e la nuova, verificabile da `/mqtt`
- [ ] Due finestre aperte: la seconda che pubblica su una revisione superata viene fermata con l'avviso e non sovrascrive
- [ ] **Una ripubblicazione senza modifiche non fa lampeggiare nessuna scheda** e non produce nessuna risottoscrizione

### US-005 · Un registro sbagliato non cancella casa
**Priorità:** Must Have

Come chi tiene in piedi il sistema voglio che un registro incomprensibile non cancelli i
dispositivi che funzionano, per non perdere la configurazione di casa per un errore di
battitura o una versione più nuova del formato.

**Criteri di accettazione:**
- [ ] Payload non JSON, o senza `dispositivi` → **rifiuto in blocco**: resta il registro precedente e l'app continua a funzionare
- [ ] `schema` maggiore di quello conosciuto → rifiutato, non interpretato a metà
- [ ] `tipo` sconosciuto → quel dispositivo saltato, gli altri applicati
- [ ] Campi sconosciuti dentro un dispositivo noto → ignorati senza errore
- [ ] Ogni rifiuto compare in `/state` fra le anomalie, con il motivo
- [ ] La **prima** applicazione su un'app che ha già dispositivi propri mostra il conto e aspetta conferma
- [ ] Un dispositivo locale con lo stesso topic di stato di uno del registro viene **adottato**, non duplicato

### US-006 · L'accesso passa dal broker
**Priorità:** Should Have

Come chi apre il configuratore da fuori casa voglio che chieda le credenziali del broker per
sapere che chi non le ha non può riscrivere la configurazione di casa.

**Criteri di accettazione:**
- [ ] Senza credenziali valide la pagina non mostra il registro e non pubblica
- [ ] Il client id è diverso a ogni apertura, e due schede aperte insieme non si buttano fuori a vicenda
- [ ] La pagina raggiunta attraverso il tunnel WireGuard funziona come in LAN

---

## 5. Architettura tecnica

### Componenti coinvolti

```
                      casa
┌──────────────────────────────────────────────────────┐
│                                                       │
│   browser ──ws://:9001── ┐                            │
│   (configuratore)        │                            │
│                          ▼                            │
│                    broker mosquitto                   │
│                     :1883   :9001                     │
│                          │                            │
│   ponte tinytuya ────────┤                            │
│   (invariato)            │                            │
└──────────────────────────┼────────────────────────────┘
                           │  <prefisso>/registro/dispositivi (ritenuto)
            ┌──────────────┼──────────────┐
            ▼              ▼              ▼
         app 1          app 2          app 3
     (Room = copia del registro, sola lettura)


  dentro l'app:

   MqttDeviceDriver ──incoming──► RegistrySync ──► DeviceRepository ──► Room
     watch(topic)                 (AppContainer)      applyRegistry()
                                       │
                                  domain/registry/
                                  parse · diff · adozione   (funzioni pure, testate)
```

Il driver **non sa cosa sia un registro**: sa seguire un topic e consegnare coppie
`(topic, payload)`. L'interpretazione sta in `domain/registry/`, coerentemente con il javadoc
di `DeviceDriver` — *«il resto parla solo di `Device` e `DeviceCommand` e non va toccato
quando si aggiunge un driver»*.

### Il contratto: `<prefisso>/registro/dispositivi`

Ritenuto, QoS 1. Il prefisso è configurabile e vale `casa` di norma — lo stesso che il ponte
ha in `dispositivi.yaml` sotto `mqtt.prefisso`, e lo stesso sotto cui pubblicano le prese.

**Perché lì dentro e non in un ramo suo.** Tenere il registro sotto lo stesso prefisso dei
dispositivi che descrive fa sì che il prefisso diventi il confine di un'**istanza**: cambiarlo
sposta insieme le prese e il registro che le elenca. Sullo stesso broker possono convivere
`casa/` e `ufficio/`, ciascuno con i suoi dispositivi e il suo registro, e un'app sceglie a
quale casa appartiene impostando una stringa. Un ramo separato — `config/dispositivi` —
avrebbe tenuto due cose che si muovono insieme in due posti che si muovono separatamente.

I due prefissi, quello del ponte e quello del registro, restano impostazioni indipendenti
che **per convenzione coincidono**: niente li obbliga, e sbagliarli dà un registro che
descrive topic su cui non pubblica nessuno — che è l'anomalia che `/state` segnala già come
*«non è mai arrivato niente sul topic …»*.

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
      "topic_energia": "casa/frigorifero/energia",
      "campo_kwh_oggi": "kwh_oggi", "campo_kwh_mese": "kwh_mese",
      "topic_disponibilita": "casa/frigorifero/disponibilita",
      "payload_disponibile": "online", "payload_non_disponibile": "offline",
      "topic_stato_livello": null, "topic_comando_livello": null, "campo_livello": null,
      "livello_max": 100, "qos": 1, "ritenuto": false
    }
  ]
}
```

| Situazione | Comportamento |
|---|---|
| `schema` maggiore di quello conosciuto | rifiuto in blocco, resta il precedente |
| Payload non JSON, o senza `dispositivi` | rifiuto in blocco |
| **Payload vuoto** (ritenuto cancellato) | l'app **smette di seguire** e tiene quello che ha. Non cancella niente (decisione 8) |
| Campo sconosciuto in un dispositivo noto | ignorato |
| `tipo` sconosciuto | dispositivo saltato, gli altri applicati |
| `uuid` mancante o duplicato | dispositivo saltato |
| `revisione` ≤ quella applicata | ignorato senza rumore |
| Campo `stanza` | non esiste nel documento. `room` resta quello che è in locale, il registro non lo sovrascrive; su un dispositivo nuovo nasce vuoto |

Rimetterci `stanza` il giorno che servisse un raggruppamento **non costa uno `schema: 2`**:
aggiungere un campo è additivo, e un lettore vecchio ignora quello che non conosce. La
regola dello schema vale per ciò che si rompe, non per ciò che si aggiunge.

La regola generale è quella che il progetto applica già a `readAvailability`: da un valore
che non si è capito non si deduce niente.

### Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| `devices` (Room) | Modifica | `MIGRATION_4_5`: `ALTER TABLE devices ADD COLUMN uuid TEXT NOT NULL DEFAULT ''`. Additiva come le tre precedenti, nessuna tabella ricreata. La stringa vuota significa "mai visto un registro" ed è il punto da cui parte l'adozione |
| `Device` (dominio) | Modifica | `val uuid: String = ""`. Default su un `data class`: nessuna chiamata esistente da toccare, test compresi |
| `DeviceEntity` | Modifica | Colonna `uuid` e i due mapper |
| `schemas/…/5.json` | Nuovo (generato) | `exportSchema` e `room.schemaLocation` già configurati: nasce da solo, va committato |
| `DataStore "registro"` | Nuovo | `prefisso: String` (predefinito `casa`), `segui: Boolean`, `revisione: Int`, `ricevutoIl: Long`, `primaApplicazioneFatta: Boolean`. **File separato da quello del broker** — vedi rischio R-2. Il prefisso sta qui e non in `BrokerSettings` per la stessa ragione: cambiarlo deve rifare la sottoscrizione, non riaprire il collegamento |

### Nuove API o endpoint

Non ci sono API HTTP nuove: il trasporto è MQTT. Per completezza, i canali che cambiano:

| Canale | Topic / path | Chi scrive | Chi legge | Auth |
|---|---|---|---|---|
| MQTT | `<prefisso>/registro/dispositivi` (ritenuto, QoS 1) | web app | app Android | Sì — credenziali broker |
| MQTT | listener `:9001` websockets | browser | — | Sì — stesso `password_file` del 1883 |
| HTTP | `http://<host>:${CONFIGURATORE_PORT}/` (predefinito 8080) | — | browser | No — solo LAN e tunnel WireGuard |
| HTTP | `/state` della API di diagnostica | — | `tools/debug-api.py` | No — invariata, solo GET, solo indirizzi privati |

### Breaking changes

Nessun breaking change verso l'esterno. I topic dei dispositivi non cambiano, il ponte non
viene toccato, e un'app alla versione precedente continua a funzionare contro un broker che
ha il registro: ignora un topic a cui non si iscrive.

L'unica irreversibilità è interna e va detta:

| Componente | Cosa | Conseguenza |
|---|---|---|
| Room `devices` | La migrazione 4→5 è additiva ma non reversibile | Dopo l'aggiornamento, installare un APK precedente (schema 4) su quel telefono richiede di **cancellare i dati dell'app**. È la stessa condizione delle tre migrazioni già fatte |

---

## 6. Piano di implementazione

| ID | Task | Area | Stima (gg) | Dipende da |
|---|---|---|---|---|
| T-01 | Listener websockets in `mosquitto.conf`, porta 9001 in `compose.yml`, verifica con una pagina di quattro righe | Infra | 0,25 | — |
| T-02 | Schema del documento scritto e versionato (`bridge/configuratore/SCHEMA.md`) | Doc | 0,10 | — |
| T-03 | `domain/registry/`: parsing e regole di rifiuto (funzioni pure) | BE | 0,40 | T-02 |
| T-04 | `domain/registry/`: diff per uuid e adozione per topic di stato | BE | 0,30 | T-03 |
| T-05 | Test unit su T-03 e T-04 | Test | 0,50 | T-04 |
| T-06 | `Device.uuid`, `DeviceEntity`, `MIGRATION_4_5`, `schemas/5.json` | BE | 0,30 | — |
| T-07 | `DeviceDao.applyRegistry` `@Transaction` e `DeviceRepository` | BE | 0,25 | T-06 |
| T-08 | `DeviceDriver.watch(topic, qos)` + `incoming: Flow`; nel driver MQTT: topic di sistema in `wanted`, intercettazione in `onMessage` | BE | 0,40 | — |
| T-09 | `RegistryStore` su DataStore separato, prefisso compreso | BE | 0,20 | — |
| T-10 | `AppContainer`: cablaggio registro → repository, conferma alla prima applicazione | BE | 0,30 | T-04, T-07, T-08, T-09 |
| T-11 | UI: modulo in sola lettura, "Aggiungi" nascosto, sezione Registro in Impostazioni (prefisso compreso), `strings.xml` | BE | 0,45 | T-09 |
| T-12 | Diagnostica: `DriverSnapshot`, `/state`, anomalie nuove | BE | 0,20 | T-10 |
| T-13 | Web app: scheletro, collegamento, login, prefisso, elenco in **sola lettura** | FE | 0,65 | T-01, T-02 |
| T-14 | Web app: modulo a 17 campi e le cinque validazioni | FE | 0,80 | T-13 |
| T-15 | Web app: pubblicazione ritenuta, revisione, rilevamento conflitto | FE | 0,40 | T-14 |
| T-16 | Web app: modelli ponte / Tasmota / Zigbee2MQTT | FE | 0,25 | T-14 |
| T-17 | `Dockerfile`, `nginx.conf`, servizio `configuratore` in compose, `CONFIGURATORE_PORT` in `.env.example`, `vendor/mqtt.min.js` | Infra | 0,25 | T-13 |
| T-18 | Documentazione: `README.md`, `bridge/README.md` | Doc | 0,25 | T-16, T-12 |
| T-19 | Verifica sul campo (vedi TC-10) | Test | 0,30 | tutti |

**Stima totale: 6,55 giorni/uomo** (arrotondati a **6,5**)
**Breakdown:** BE 2,80 · FE 2,10 · Infra 0,50 · Test 0,80 · Doc 0,35

> **La stima è salita da 4,5 a 6,5 rispetto alla Fase 1**, ed è tutta sul lato Android
> (1,5 → 2,7). Non è un ripensamento: sono i tre problemi che la lettura del codice ha
> trovato e che la Fase 1 non poteva vedere — l'applicazione transazionale in loco per uuid
> (R-1), lo store separato (R-2) e la sottoscrizione di sistema (R-3). Ognuno è mezza
> giornata che prima non c'era. **La stima da usare è questa**, non quella della Fase 1.

### Ordine di lavoro consigliato

Il lettore prima dello scrittore: T-01 → T-02 → T-03 → T-04 → T-05 → T-06 → T-07 → T-08 →
T-09 → T-10. A questo punto l'app riceve e applica un registro pubblicato a mano con
`mosquitto_pub`, e il pezzo difficile è finito e testato senza che esista un'interfaccia.
Poi T-11, T-12, e la web app (T-13 → T-14 → T-15 → T-16 → T-17), che è lavoro di modulo.

---

## 7. Piano di test

**Strategia generale:** unit test JVM sulle funzioni pure, come già si fa per il driver
(`MqttPayloads.kt` → `MqttPayloadsTest.kt`). Tutto ciò che decide qualcosa — parsing,
rifiuto, diff, adozione — va scritto senza Android e senza MQTT, in modo da essere provabile
con `./gradlew testDebugUnitTest`. Il resto si verifica sul campo con `mosquitto_pub` /
`mosquitto_sub` e con la API di diagnostica, che sono gli strumenti che il progetto ha già.

Nessun test strumentato: `app/src/androidTest` non esiste e non lo si introduce (decisione 6).

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Unit | **Registro identico → diff vuoto.** Riapplicare lo stesso registro non produce nessuna modifica, nessuna risottoscrizione, nessuna scheda azzerata. È la sentinella di R-1 | Alta |
| TC-02 | Unit | Documento valido completo e documento con i soli campi obbligatori: entrambi producono i `Device` attesi | Alta |
| TC-03 | Unit | Payload non JSON, senza `dispositivi`, con `schema: 2` → `null` in tutti e tre i casi (rifiuto in blocco) | Alta |
| TC-04 | Unit | `tipo` sconosciuto → quel dispositivo saltato, gli altri applicati. `uuid` mancante o duplicato → saltato | Alta |
| TC-05 | Unit | Campo sconosciuto dentro un dispositivo noto → ignorato, il dispositivo si applica lo stesso | Alta |
| TC-06 | Unit | Revisione maggiore si applica, uguale e minore si ignorano; un registro rifiutato non abbassa la revisione applicata | Alta |
| TC-07 | Unit | Diff: aggiunto, rimosso, modificato **con `id` conservato** | Alta |
| TC-08 | Unit | Adozione: locale senza uuid con lo stesso `topic_stato` → adottato con `id` conservato; topic diverso → non adottato; due locali sullo stesso topic → uno solo, deterministicamente | Alta |
| TC-09 | Unit | La sottoscrizione di sistema c'è anche a zero dispositivi e non viene abbandonata da `syncSubscriptions()` | Alta |
| TC-10 | Campo | **Il giro completo:** le sette prese registrate dal configuratore, due telefoni che le ricevono, uno dei due **spento durante una cancellazione** e riacceso dopo | Alta |
| TC-11 | Campo | Un dispositivo con `topic_stato = casa/#` non intercetta il registro (R-4) | Media |
| TC-12 | Campo | Due schede del configuratore aperte: la seconda che pubblica su revisione superata viene fermata | Media |
| TC-13 | Campo | Aggiornamento di una build già installata da versione 4 a versione 5: i dispositivi registrati a mano sopravvivono e vengono adottati | Alta |
| TC-14 | Campo | Build **release** (R8 attivo): il parsing del registro funziona come in debug | Alta |

### Definition of Done

- [ ] `./gradlew testDebugUnitTest` passa, TC-01…TC-09 compresi
- [ ] TC-10 eseguito e annotato: sette prese, due telefoni, una cancellazione a telefono spento
- [ ] TC-13 eseguito su un telefono che aveva davvero dispositivi registrati a mano — **non** su un'installazione pulita
- [ ] TC-14 eseguito sulla release firmata, non sulla debug
- [ ] `/state` non segnala anomalie nuove dopo l'applicazione di un registro
- [ ] `schemas/5.json` committato insieme alla migrazione
- [ ] `README.md` e `bridge/README.md` aggiornati
- [ ] `versionCode` e `versionName` alzati in `app/build.gradle.kts` (da 8 / 1.0.7 a 9 / **1.1.0**: cambia il modo in cui si gestiscono i dispositivi, non è una correzione)

> Nessuna soglia di coverage e nessuna CI: il progetto non ha né l'una né l'altra, e
> inventarne una qui sarebbe una riga di spunta che nessuno verifica. Il criterio è che i
> test elencati esistano e passino.

---

## 8. Rischi e mitigazioni

| # | Rischio | Prob. | Impatto | Mitigazione |
|---|---|---|---|---|
| R-1 | **Applicare il registro con delete+insert svuota tutte le schede.** `states` ha come chiave `device.id`, e `track()` butta gli stati la cui chiave non è più fra i vivi ([MqttDeviceDriver.kt:171-172](../../../app/src/main/java/it/agoldoni/smarthome/driver/mqtt/MqttDeviceDriver.kt#L171)) | Alta | Alto | Aggiornamento **in loco per uuid**, `id` conservato. Solo i dispositivi davvero spariti vengono cancellati. TC-01 è la sentinella |
| R-2 | **Lo stato del registro dentro `BrokerSettings` riconnette l'app a ogni revisione.** Il driver fa `settings.distinctUntilChanged().collect { applySettings(it) }`, e `applySettings` chiude e riapre il client | Alta | Alto | `RegistryStore` su un DataStore separato. Il driver non lo guarda |
| R-3 | **La sottoscrizione al registro viene abbandonata da sola.** In `syncSubscriptions()` `wanted` nasce solo dai dispositivi e `obsolete = subscribed.keys - wanted.keys` disiscrive il resto | Alta | Alto | I topic di sistema entrano in `wanted`. TC-09 |
| R-4 | **Un dispositivo con `casa/#` intercetta il registro** e ne usa 5 KB di JSON come proprio stato | Bassa | Medio | Intercettazione prima del ciclo dei dispositivi, con `return`. Anomalia in `/state` per un filtro che combacia con `<prefisso>/registro/#`. TC-11 |
| R-5 | **La prima sincronizzazione è distruttiva per definizione**: fonte di verità unica significa che ciò che non c'è viene rimosso | Media | Alto | Adozione per topic di stato + conferma esplicita con il conto di cosa verrà rimosso. TC-13 |
| R-6 | **Ultima scrittura vince**: MQTT non ha compare-and-swap, due finestre che pubblicano insieme si sovrascrivono | Media | Medio | Confronto della `revisione` prima di pubblicare. Non è atomico — due invii nello stesso istante passano entrambi — ma copre il caso vero, la scheda lasciata aperta ieri. TC-12 |
| R-7 | **Chiunque abbia le credenziali del broker può riscrivere il registro**: un utente solo, nessuna ACL | Media | Medio | Accettato e documentato. La strada, se servirà: secondo utente con `acl_file` che dà `write` su `+/registro/#` solo a lui |
| R-8 | **WebSocket in chiaro**: la password del broker passa leggibile sulla LAN | Alta | Basso | Coerente col 1883, già in chiaro. Da fuori si passa dal tunnel WireGuard. Da rivedere solo insieme al TLS del broker |
| R-9 | **Mixed content**: se un giorno la pagina va in HTTPS, il browser blocca `ws://` senza errore visibile | Bassa | Medio | O entrambi in chiaro o entrambi cifrati, documentato. Errore di connessione sempre mostrato, mai un'attesa muta |
| R-10 | **Una libreria JSON con riflessione romperebbe la release.** `isMinifyEnabled = true` e R8 attivo | Bassa | Alto | Parsing a mano con `org.json`, come già fa `MqttPayloads.kt`: nessuna regola `-keep`, nessuna dipendenza nuova. TC-14 |
| R-11 | **Il ponte non sa niente del registro**: rinominare una presa nel registro senza rinominarla in `dispositivi.yaml` dà un bel nome su topic che non esistono più | Media | Basso | L'anomalia c'è già e si legge: `/state` dice *«non è mai arrivato niente sul topic …»*. Da citare nel README |
| R-12 | ~~Il PC che ospita lo stack si spegne la sera~~ | — | — | **Non è più un rischio.** Lo stack si sposta su un Raspberry sempre acceso (decisione 11): il registro ritenuto è sempre lì, e un telefono nuovo lo trova a qualunque ora. Era il rischio che indeboliva di più US-003 |
| R-13 | `registro` diventa un nome di presa da non usare dentro un prefisso | Bassa | Basso | Il ponte sottoscrive solo topic espliciti per dispositivo, non wildcard: nessuna interferenza. Da documentare |
| R-14 | **Prefisso del registro e prefisso del ponte disallineati**: il registro descrive topic su cui non pubblica nessuno | Bassa | Medio | Predefinito uguale (`casa`) da entrambe le parti. Il sintomo si legge già: `/state` dice *«non è mai arrivato niente sul topic …»* per ogni dispositivo |

---

## 9. Rollout e rollback

**Bersaglio del deploy:** il Raspberry, sempre acceso. Vale la pena costruire già per lui —
non c'è niente da fare di diverso, ma due cose vanno verificate al primo `docker compose up`
là sopra, e nessuna delle due riguarda il configuratore, che è nginx con dentro dei file:

- **Raspberry Pi OS a 64 bit.** `eclipse-mosquitto:2`, `nginx:alpine` e `python:3.12-slim`
  sono multi-arch e non danno problemi, ma il ponte si ricostruisce sul posto e `cryptography`
  ha le ruote precompilate per `aarch64` e non per l'ARM a 32 bit, dove finirebbe a compilare
  Rust. Su un sistema a 32 bit è lì che ci si ferma, non sul configuratore
- **`PUID`/`PGID`**: su Raspberry Pi OS l'utente predefinito è di norma 1000, quindi i
  default di `compose.yml` reggono. Da confermare con un `id` prima di copiare i volumi

**Strategia di rilascio:** deploy diretto, in due tempi indipendenti.

1. **Lo stack** (T-01, T-17): il servizio `configuratore` e il listener 9001 si aggiungono
   senza toccare broker, ponte e app. Aggiungerli non cambia il comportamento di niente:
   finché nessuno pubblica su `<prefisso>/registro/dispositivi`, non esiste nessun registro
2. **L'app** (T-03…T-12): rilasciata come 1.1.0. Un'app aggiornata che non trova nessun
   registro **si comporta esattamente come prima**

Non serve un feature flag: ce ne sono già due, e sono di prodotto, non di infrastruttura.

- **L'esistenza del registro.** Nessun messaggio ritenuto = nessun registro = l'app gestisce
  i dispositivi da sé, come oggi. È l'interruttore vero, e non richiede codice apposta
- **"Segui il registro"** in Impostazioni, spegnibile a mano per telefono: restituisce il
  modulo scrivibile e interrompe la sottoscrizione

**Piano di rollback**

| Se va storto | Cosa fare | Effetto |
|---|---|---|
| Il registro contiene qualcosa di sbagliato | Correggerlo dal configuratore e ripubblicare | Le app si allineano da sole |
| Il configuratore fa danni | Cancellare il messaggio ritenuto: `mosquitto_pub -t casa/registro/dispositivi -r -n` | Le app **smettono di seguire e tengono quello che hanno**. Non cancellano niente: è la regola del payload vuoto, decisione 8 |
| Un telefono si comporta male | Spegnere "segui il registro" in Impostazioni su quel telefono | Torna a gestire i dispositivi da sé, con quelli che ha |
| Il servizio web dà noia | `docker compose stop configuratore` | Il registro ritenuto resta sul broker e le app continuano a funzionare |
| L'app 1.1.0 va rimessa alla 1.0.7 | Disinstallare, reinstallare, **riconfigurare** | La migrazione Room 4→5 non è reversibile: l'APK vecchio non apre un database di schema 5 |

**Una nota sul trasloco, che non è un rollback ma gli somiglia.** Quando lo stack passa sul
Raspberry cambia l'indirizzo del broker, e **ogni telefono va riaperto una volta** per
scriverci il nuovo host. Il registro non può aiutare: le coordinate del broker sono
esattamente ciò che serve per *riceverlo*, ed è il motivo per cui stanno fuori dal registro
(sezione 3, Escluso). Conviene fare le due cose insieme — trasloco e aggiornamento a 1.1.0 —
così i telefoni si toccano una volta sola invece di due.

L'ultima riga è l'unica operazione davvero costosa, ed è la ragione per cui TC-13 — un
aggiornamento su un telefono che aveva dispositivi registrati a mano — è nella Definition of
Done e non fra le verifiche opzionali.

---

## 10. Checklist di approvazione

Progetto di una persona sola: la revisione è una rilettura a distanza di un giorno, non il
passaggio a qualcun altro. Le righe restano perché le domande sono le stesse.

| Revisione | Cosa chiede | Stato | Data |
|---|---|---|---|
| Revisione tecnica | L'architettura regge? R-1, R-2, R-3 sono davvero coperti? | ⏳ In attesa | — |
| Revisione di prodotto | Le sei storie sono quello che serve davvero, e nient'altro? | ⏳ In attesa | — |
| Stima approvata | 6,5 giorni sono accettabili, sapendo che 2,7 sono sull'app e non sulla pagina? | ⏳ In attesa | — |
| Rischi accettati | R-7 (chiunque abbia le credenziali riscrive il registro) e R-8 (password in chiaro sulla LAN) si accettano per ora? | ⏳ In attesa | — |
| Data di inizio confermata | — | ⏳ In attesa | — |

---

## Domande aperte

Le cinque decise oggi sono qui sotto con il perché. Non ne restano.

### Chiuse il 12 settembre 2026

- **Il topic del registro sta sotto il prefisso dei dispositivi**, e il prefisso è
  configurabile. Non è una scelta di ordine ma di struttura: il prefisso diventa il confine
  di un'istanza, e cambiarlo sposta insieme i dispositivi e il registro che li elenca. Una
  seconda casa — o l'ufficio — nasce cambiando una stringa, sullo stesso broker. Un ramo
  separato avrebbe tenuto in due posti due cose che si muovono insieme. *(Decisione 7,
  sezione 5.)*

- **Il payload vuoto significa "smetti di seguire e tieni quello che hai".** Scelta
  prudente confermata: la lettura opposta — registro cancellato = casa senza dispositivi —
  sarebbe più coerente con "fonte di verità unica", ma renderebbe la cancellazione
  accidentale di un ritenuto un disastro, e un ritenuto si cancella con un comando solo.
  *(Decisione 8, sezione 5 e piano di rollback.)*

- **`stanza` non entra nel registro.** Non serve ora: l'app non ordina né raggruppa per
  stanza, quindi sarebbe un campo da compilare che nessuno legge. La colonna `room` resta
  nel database e il registro non la tocca. Rimetterla quando servirà è additivo e non
  rompe i lettori vecchi. *(Decisione 9.)*

- **La porta del configuratore non è una decisione, è una variabile.** `CONFIGURATORE_PORT`
  in `.env`, predefinita 8080, usata in compose come `${CONFIGURATORE_PORT:-8080}:80` —
  la forma che `WG_PORT` ha già. Se 8080 è occupata sulla macchina che ospita lo stack, si
  cambia una riga senza toccare né compose né il documento. *(Decisione 10.)*

- **Lo stack si sposta su un Raspberry sempre acceso**, e il configuratore ci va insieme:
  è un servizio in più nello stesso `compose.yml`, quindi viaggia da solo col resto. Due
  effetti su questo piano: **R-12 sparisce** — un telefono nuovo trova il registro a
  qualunque ora, che è la premessa di US-003 — e **R-7 pesa un po' di più**, perché una
  pagina sempre raggiungibile è sempre raggiungibile. Non abbastanza da cambiare la
  decisione 3, abbastanza da ricordarsi che la strada per le ACL è scritta lì accanto.
  *(Decisione 11.)*

### Nessuna domanda aperta

Il documento è completo e non aspetta risposte da nessuno.

---

*Documento generato con la skill `claude-code-feature`.*
