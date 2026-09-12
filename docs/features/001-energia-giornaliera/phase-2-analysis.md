# Fase 2 — Analisi tecnica: energia storicizzata per presa

**Feature:** `001-energia-giornaliera`
**Data:** 12 settembre 2026
**Riferimento:** [phase-1-requirements.md](phase-1-requirements.md)

Tutti i percorsi, i numeri di riga e i comportamenti citati qui sono stati verificati sulla
codebase e sul sistema in funzione. Dove una cosa non esiste, è detto esplicitamente.

---

## A. File coinvolti

### Ponte

| File | Modifica | Motivazione |
|---|---|---|
| `bridge/tuya-mqtt/bridge.py` | **modifica** | Il cuore. Due classi nuove (`Contatore` per presa, `Archivio` condiviso) e tre agganci nel ciclo esistente — vedi sotto |
| `bridge/tuya-mqtt/test_bridge.py` | **modifica** | Nuovi `TestCase` per l'accumulatore. Le finte (`FintoMqtt`, `FintaScoperta`, l'helper `presa(**extra)` alle righe 15-31) sono già lì e bastano |
| `bridge/compose.yml` | **modifica** | Volume scrivibile e `user:` per il servizio `ponte` |
| `bridge/.gitignore` | **modifica** | L'archivio è un dato, non codice |
| `bridge/stato/.gitkeep` | **nuovo** | Il bind mount vuole la cartella già lì e dell'utente giusto |
| `bridge/dispositivi.yaml` + `.esempio.yaml` | **modifica** | Il dp del contatore e il fattore Wh/tacca sono configurazione, come già lo sono i tre dp delle letture |
| `bridge/README.md` | **modifica** | Topic nuovi, schema dell'archivio, query di esempio |
| `bridge/tuya-mqtt/Dockerfile` | **nessuna** | Verificato nell'immagine: `sqlite3` 3.46.1 e `zoneinfo` con `Europe/Rome` ci sono già, sono libreria standard |
| `bridge/tuya-mqtt/requirements.txt` | **nessuna** | Nessuna dipendenza nuova |

**I tre agganci in `bridge.py`:**

1. `Presa.run()`, riga **306** (`self._dps.update(risposta["dps"])`) — è il punto in cui
   arriva una lettura fresca, con il suo istante. Da lì il `Contatore` riceve il dp 17 (o
   la potenza, per chi non ce l'ha) e aggiorna l'ora in corso.
2. `Presa._payload_stato()`, righe **186-199** — se i due numeri viaggiano nel payload di
   stato (decisione in sezione B), è qui che si aggiungono, accanto alle letture scalate.
3. `main()`, righe **389-415** — costruisce l'`Archivio` una volta sola e lo passa a ogni
   `Presa`, come già fa con `scoperta`, `mqttc`, `prefisso`, `qos`.

**Un dettaglio che decide la correttezza:** alla riga **260**, a ogni riconnessione,
`Presa.run()` fa `self._dps = {}`. L'ultimo valore del dp 17 **non può vivere lì**: dopo una
riconnessione sembrerebbe la prima lettura di un contatore appena azzerato, e ogni
riconnessione conterebbe due volte (o zero volte) l'energia. Il valore precedente sta nel
`Contatore`, che sopravvive alle riconnessioni.

**La regola del contatore**, buchi compresi:

| Caso | Cosa si fa |
|---|---|
| Valore ≥ ultimo visto | si somma il delta |
| Valore < ultimo visto | azzeramento: si somma il valore nuovo |
| Buco **fino a un'ora** | il delta copre il buco e si somma nell'ora in cui lo si legge: la presa ha continuato a contare per conto suo, quell'energia non è persa |
| Buco **oltre un'ora** | la lettura fa da nuovo riferimento e non si somma niente: meglio dichiarare persa un'ora che scaricare tre giorni di consumi dentro una riga sola |

Il taglio a un'ora è lì perché è il periodo della riga: dentro l'ora l'attribuzione è
comunque giusta, oltre non lo è più.

### App

| File | Modifica | Motivazione |
|---|---|---|
| `app/src/main/java/.../domain/model/Device.kt` | **modifica** | Campi nuovi per dire dove leggere i kWh. Se su topic a sé, va aggiornata anche `subscriptions` (righe 77-83) |
| `.../domain/model/DeviceState.kt` | **modifica** | `kwhToday`, `kwhMonth` nullabili, accanto a `watts` (riga 21) |
| `.../driver/mqtt/MqttDeviceDriver.kt` | **modifica** | Lettura. `readNumber` esiste già (`MqttPayloads.kt:51-59`), aggiunta un'ora fa per la potenza |
| `.../data/local/DeviceEntity.kt` | **modifica** | Colonne + i due mapper |
| `.../data/local/SmartHomeDatabase.kt` | **modifica** | Versione **3 → 4** e `MIGRATION_3_4`. La 3 è di stamattina (potenza istantanea) |
| `.../di/AppContainer.kt` | **modifica** | `addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)` |
| `.../ui/devices/DeviceEditViewModel.kt` | **modifica** | `DeviceForm` + `toForm`/`toDevice` |
| `.../ui/devices/DeviceEditScreen.kt` | **modifica** | Campi nel modulo, sezione *Stato* |
| `.../ui/devices/DeviceListScreen.kt` | **modifica** | `statusLine` (righe 429-442) e un formato per i kWh accanto a `formatWatts` (riga 447) |
| `.../diagnostics/DebugReport.kt` | **modifica** | I campi nuovi in `device()` e `deviceState()`, come si è fatto per `watts` |
| `app/src/main/res/values/strings.xml` | **modifica** | Etichette, helper, formato |
| `app/src/test/java/.../MqttPayloadsTest.kt` | **modifica** | Sottoscrizioni e lettura |
| `app/schemas/.../4.json` | **generato** | Lo produce KSP, va versionato come 1/2/3 |

## B. Contratti e interfacce da modificare

### B.1 Topic MQTT nuovo — `casa/<nome>/energia` (ritenuto)

```json
{
  "kwh_oggi": 0.842,
  "kwh_mese": 27.31,
  "kwh_ieri": 1.104,
  "giorno": "2026-09-12",
  "mese": "2026-09",
  "sorgente": "dp17",
  "copertura_oggi": 0.98
}
```

`sorgente` è `dp17` oppure `integrale`: chi legge deve poter sapere se quel numero viene
dal contatore della presa o da una stima nostra. `copertura_oggi` è la frazione di giornata
in cui il ponte ha davvero visto la presa.

### B.2 Il payload di stato — ✅ **deciso: topic a sé** (12/09/2026)

L'app oggi sa leggere campi JSON da un topic. I due numeri possono arrivarle in due modi:

| | **Dentro `casa/<nome>/stato`** | **Su `casa/<nome>/energia`** |
|---|---|---|
| App: campi nuovi da riempire | 2 (due chiavi JSON) | 3 (topic + due chiavi) |
| App: sottoscrizioni | nessuna nuova | una in più per presa, via `Device.subscriptions` |
| Contratto del topic `stato` | smette di essere "quel che dice la presa più le sue letture scalate" | resta quello che è |
| Duplicazione | i due numeri stanno in due posti | nessuna |
| Lavoro a mano sulle sette prese | 14 campi da digitare | 21 |

**Raccomandazione: topic a sé.** Costa sette campi in più da digitare una volta sola, e
tiene separate due cose che hanno tempi diversi — lo stato è una fotografia dell'istante,
l'energia è un accumulo che non si azzera quando la presa si spegne. La scorciatoia
dell'altra colonna si paga il giorno in cui qualcuno legge `stato` e trova dentro un numero
che la presa non ha mai detto. Il precedente nel codice è `levelStateTopic` (`Device.kt:59`):
un secondo topic per una grandezza che vive per conto suo esiste già come pattern.

### B.3 Schema dell'archivio SQLite

Due tabelle e una vista, perché **si archivia solo quello a cui manca un fattore k** — il
conteggio è un fatto, il fattore è un'interpretazione, e le due cose non vanno mescolate
nella stessa riga.

```sql
-- I FATTI: quello che la presa ha contato. Append-only, mai riscritto.
CREATE TABLE energia_grezza (
    presa       TEXT    NOT NULL,
    inizio_utc  TEXT    NOT NULL,  -- ISO 8601 in UTC: la chiave vera
    giorno      TEXT    NOT NULL,  -- '2026-09-12', data locale Europe/Rome
    ora         INTEGER NOT NULL,  -- 0-23, ora locale
    grezzo      REAL    NOT NULL,  -- tacche del dp 17, oppure Wh integrati
    sorgente    TEXT    NOT NULL,  -- 'dp17' | 'integrale'
    copertura   REAL    NOT NULL,  -- 0.0-1.0: minuti visti / minuti dell'ora
    PRIMARY KEY (presa, inizio_utc)
);
CREATE INDEX energia_per_giorno ON energia_grezza (giorno, presa);

-- L'INTERPRETAZIONE: una riga per presa. Il ponte la riscrive all'avvio da
-- dispositivi.yaml, che resta la fonte di verita'.
CREATE TABLE fattori (
    presa        TEXT NOT NULL,
    sorgente     TEXT NOT NULL,
    wh_per_unita REAL NOT NULL,
    PRIMARY KEY (presa, sorgente)
);

-- QUELLO CHE SI INTERROGA: ha il nome buono apposta, cosi' chi apre il file
-- con sqlite3 trova i kWh senza sapere niente di tutto questo.
CREATE VIEW energia AS
SELECT g.presa, g.inizio_utc, g.giorno, g.ora, g.copertura, g.sorgente, g.grezzo,
       g.grezzo * f.wh_per_unita / 1000.0 AS kwh
  FROM energia_grezza g
  JOIN fattori f ON f.presa = g.presa AND f.sorgente = g.sorgente;
```

`grezzo` non è omogeneo fra le righe, ed è `sorgente` a dire in che unità è: **tacche** per
il dp 17, **Wh già integrati** per il ripiego (che quindi ha `wh_per_unita = 1.0`, perché lì
un fattore da scoprire non c'è — l'interpretazione è già stata fatta al momento della lettura,
e non si può disfare).

**Ritarare non tocca l'archivio.** Si cambia il fattore in `dispositivi.yaml` e si riavvia il
ponte: una riga della tabella `fattori`, e dieci anni di righe cambiano valore insieme.

```sql
-- verifica al volo, prima di metterlo in configurazione
UPDATE fattori SET wh_per_unita = 0.92 WHERE presa = 'boiler' AND sorgente = 'dp17';
```

E vale anche se i due modelli di presa contassero in modo diverso: c'è una riga per presa.

> Se un giorno servisse sapere *quando* il fattore è cambiato, è un `taratura(data, presa,
> valore, nota)` da tre colonne. Non serve adesso: il fattore corrente è uno, e l'archivio
> non ne ha memoria perché non ne ha bisogno.

**Provato, non supposto.** Schema, vista e ritaratura girati su SQLite con righe finte:
somme per giorno, mese e anno corrette; le due ore delle 2:00 del 25 ottobre convivono e si
sommano (chiavi `inizio_utc` diverse, stessa `ora`); la ritaratura da 1,0 a 0,92 Wh/tacca ha
toccato **2 righe di `fattori` e nessuna riga dell'archivio**, e ha lasciato ferme le righe
`integrale` della pompa — che è esattamente quello che deve fare: tarare il contatore non
può spostare una stima che dal contatore non viene.

La chiave primaria sta sull'**istante UTC** e non su `(giorno, ora)`: il 25 ottobre 2026 le
2:30 italiane esistono due volte, e con la chiave locale la seconda sovrascriverebbe la
prima. `giorno` e `ora` restano come colonne perché sono quelle su cui si raggruppa, e un
indice le rende immediate.

Verificato nell'immagine del ponte: `datetime(2026,10,25,2,30, tzinfo=ZoneInfo('Europe/Rome'))`
risolve a `+02:00`, cioè il **primo** dei due passaggi. L'ambiguità è reale, non teorica.

### B.4 Room 3 → 4

Additiva: colonne `TEXT` nullabili, nessun `NOT NULL` senza default, nessuna riga toccata.
Stesso taglio di `MIGRATION_2_3` (`SmartHomeDatabase.kt:38-42`), scritta a mano per non far
ricreare la tabella: dentro ci sono i dispositivi registrati a uno a uno.

**Breaking changes: nessuno.** Chi legge i topic oggi continua a leggerli; l'app ignora i
campi JSON che non conosce, perché `extractJson` (`MqttPayloads.kt:32-49`) cerca la chiave
che le si chiede e basta.

## C. Pattern da rispettare

**Ponte**
- Nomi e commenti in italiano; i commenti dicono *perché*, non *cosa*. Il modello sono le
  righe 202-214 (`_leggi`) e 320-330 (`_chiedi`): spiegano il caso vero che ha prodotto quel codice
- Le mappature dp → grandezza stanno in `dispositivi.yaml`, una volta sola, in
  `letture_predefinite` (righe 36-41 del file). Il dp del contatore e il fattore Wh/tacca
  vanno lì, non costanti nel codice
- La logica che conta deve essere provabile **senza socket e senza database**: le prove
  esistenti costruiscono una `Presa` con finte (`test_bridge.py:15-31`) e chiamano metodi
  puri. Il `Contatore` va progettato così: gli si passano letture e istanti, restituisce righe
- Si pubblica solo quando il payload cambia (righe 310-313). Vale anche per `energia`

**App**
- I campi sono **espliciti, mai dedotti da una convenzione** — è scritto nel commento di
  `Device.kt:21-27` ed è la ragione per cui `powerJsonKey` è un campo e non un nome fisso
- `null` significa "non si sa", e non si finge il contrario (`DeviceState.kt:3-9`)
- Le migrazioni si scrivono a mano, con il commento che dice perché (`SmartHomeDatabase.kt:13-21`)
- I numeri si formattano in Kotlin con `Locale.getDefault()` e si compongono con
  `stringResource` (`DeviceListScreen.kt:445-448`): la virgola decimale italiana viene da lì
- Stringhe in `values/strings.xml`, nomi `field_*`, `helper_*`, `state_*`

## D. Test da creare o aggiornare

### Ponte — `bridge/tuya-mqtt/test_bridge.py`

Si lanciano con `python -m unittest test_bridge -v`, come documentato in `bridge/README.md:207`.

| Classe nuova | Prove |
|---|---|
| `ContatoreDaiDelta` | delta normale; **valore che scende = azzeramento**, si somma il nuovo valore e non un delta negativo; due letture identiche non aggiungono niente; prima lettura dopo la connessione = riferimento, non conta |
| `ContatoreSenzaDp17` | ripiego sull'integrale di `potenza_w`, con la sorgente dichiarata `integrale` |
| `Copertura` | ora piena = 1.0; ora con venti minuti di buco = 0,67; presa mai vista = riga assente, non riga a zero |
| `RolloverLocale` | chiusura all'ora, al giorno e al mese in `Europe/Rome`; **25 ottobre 2026 → 25 righe**, **29 marzo 2026 → 23 righe**; nessuna chiave duplicata |
| `RipresaDopoRiavvio` | stato scritto e riletto: l'accumulo dell'ora in corso non si perde; un file di stato corrotto non impedisce l'avvio |

### App

| File | Prove |
|---|---|
| `MqttPayloadsTest.kt` | `readNumber` è già coperto (`ReadNumberTest`, aggiunto stamattina). Da aggiungere: il topic dell'energia compare in `Device.subscriptions`; senza topic non compare niente in più |

Non copribile con prove automatiche, e va messo in conto: la **correttezza del fattore
Wh/tacca**. Quella si verifica solo contro un carico noto e il contatore di casa.

## E. Rischi tecnici aggiornati

| Rischio | Evidenza dalla codebase / dal sistema | Mitigazione |
|---|---|---|
| **Doppio conteggio a ogni riconnessione** | `bridge.py:260`: `self._dps = {}` a ogni riaggancio | L'ultimo dp 17 vive nel `Contatore`, non in `_dps`. Prima lettura dopo un buco = riferimento |
| **File di stato di root nella cartella del progetto** | `docker inspect sh-ponte` → `Config.User` **vuoto**: il ponte gira da root | `user: "${PUID:-1000}:${PGID:-1000}"` come già fa `broker` in `compose.yml:8-10`, e `stato/.gitkeep` versionato come si è fatto per `mosquitto/data` (il perché è già scritto in `bridge/.gitignore`) |
| **Il ponte non parte più da non-root** | La scoperta si lega a UDP **6666/6667** (`bridge.py:75-90`): porte alte, nessun privilegio richiesto; `network_mode: host` non cambia niente | Verifica al primo avvio: se la scoperta tace, si vede subito nel log ("non riesco ad ascoltare su UDP") |
| **SQLite e i thread** | Una `Presa` per thread (`bridge.py:128`), sette in parallelo | Una sola connessione dentro `Archivio`, con lock; si scrive a fine ora, sette righe: contesa nulla. `journal_mode=WAL` perché `sqlite3` da riga di comando possa leggere mentre il ponte scrive |
| **Orologio del sistema** | Il ponte usa l'ora dell'host; un salto (NTP, sospensione del PC) sposterebbe le righe | Durate e copertura con `time.monotonic()`, che non salta; l'orologio serve solo a decidere in quale ora finisce il valore |
| **Unità del dp 17 non confermata** | Misurato in sessione: 0,4–0,8 Wh/tacca; lo schema Tuya standard dice 1 Wh | Fattore in `dispositivi.yaml`, non nel codice: correggerlo dopo la taratura è una riga, e le righe vecchie si possono ricalcolare con un `UPDATE` |
| **L'energia di un'ora arriva nell'ora dopo** | Frigorifero fermo 20 minuti, poi +24 tacche in blocco | Accettato e documentato. Sul giorno e sul mese si compensa; fingere l'interpolazione sarebbe peggio |
| Crescita dell'archivio | 7 × 24 × 365 ≈ 61.000 righe l'anno | Nessuna. Pochi MB in dieci anni |

## F. Prerequisiti e task bloccanti

1. ~~**Decidere B.2**~~ — ✅ deciso: **topic `casa/<nome>/energia` a sé**. L'app avrà tre campi
   nuovi (topic + due chiavi JSON) e una sottoscrizione in più per presa.
2. **`bridge/stato/` creata e dell'utente giusto, più `user:` in `compose.yml`.** Blocca il
   primo avvio con persistenza. Non è un refactoring: sono tre righe, ma vanno prima
3. **Taratura del dp 17.** Non blocca il codice — blocca la fiducia nei numeri. Conviene
   farla prima di accumulare mesi di righe, anche se il fattore in configurazione rende
   l'errore correggibile a posteriori
4. Nessun altro refactoring necessario: il ciclo di `Presa` ha già il punto in cui agganciarsi
   (riga 306), `main()` ha già il modo di passare oggetti condivisi alle prese, l'app ha già
   il pattern del secondo topic (`levelStateTopic`) e quello dei campi JSON configurabili
