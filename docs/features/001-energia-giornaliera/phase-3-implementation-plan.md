# Energia storicizzata per presa — Implementation Plan

**Stato:** Bozza — in attesa di approvazione
**Autore:** Alberto Goldoni
**Data:** 12 settembre 2026
**Versione:** 1.0

---

## 1. Executive Summary

Le sette prese di casa misurano i consumi, ma nessuno li tiene nel tempo: si sa quanto sta
assorbendo il boiler adesso, non quanto ha consumato oggi, questo mese o l'inverno scorso.
Questa feature fa accumulare l'energia al ponte Tuya, una riga per presa e per ora, in un
archivio SQLite dentro il ponte stesso — **nessun servizio nuovo da tenere in piedi** — e
pubblica i due numeri che servono all'app: quanto oggi, quanto questo mese.

Da lì giorni, mesi e anni sono una somma. Stima: **~3 giorni/uomo**, più mezza giornata di
taratura che è quasi tutta attesa.

---

## 2. Obiettivo e motivazione

- **Problema che risolve:** la potenza istantanea risponde a "sta lavorando adesso"; non
  risponde a "quanto mi costa". Nessuna presa pubblica un'energia giornaliera, e il dato
  non è recuperabile a posteriori: quello che non si registra oggi non esiste domani.

- **Metriche di successo:**
  - [ ] Dopo 24 ore di funzionamento, la somma delle righe orarie del boiler coincide col
        contatore di casa entro il **±5%**, misurata su un ciclo di riscaldamento noto
  - [ ] Nessuna riga con `copertura < 0,95` in una giornata senza guasti di rete
  - [ ] Un `docker compose down && up` a metà giornata non sposta i totali di più di un minuto
        di consumo
  - [ ] L'archivio contiene 7 righe per ogni ora trascorsa (meno quelle in cui una presa era
        davvero irraggiungibile, che devono comunque risultare da qualche parte)

- **Legame con gli obiettivi del progetto:** il README dichiara che l'app non inventa lo
  stato — "finché non arriva un messaggio, la scheda dice in attesa di dati". Un archivio di
  consumi deve reggere lo stesso principio: un buco si dichiara, non si riempie con uno zero.

---

## 3. Scope

### Incluso

- Accumulo continuo per presa dai **delta del dp 17**, con gestione degli azzeramenti
- Ripiego sull'**integrale di `potenza_w`** per le prese senza dp 17 (oggi: la pompa),
  con la sorgente dichiarata in ogni riga
- **Copertura** per riga: minuti in cui il ponte ha davvero visto la presa
- Chiusura di ora, giorno e mese in `Europe/Rome`, DST compreso
- Archivio **SQLite**, una riga per presa/ora, in un volume del container
- Ripresa dopo riavvio: l'ora in corso non si perde
- Topic `casa/<nome>/energia` ritenuto con i totali correnti
- App: i due numeri sotto la potenza istantanea
- Documentazione: schema, topic e query di esempio in `bridge/README.md`

### Escluso (out of scope)

- **Grafici** (Grafana, InfluxDB, VictoriaMetrics) — aggiungerebbero un servizio da tenere in
  piedi per un bisogno che oggi non c'è; l'archivio orario resta la sorgente da cui partire
- **Storico dentro l'app** — l'app mostra due numeri correnti, non serie temporali: non ha
  schermate di dettaglio e non è questo il momento di dargliene una
- **Costi in euro** — tariffe e fasce orarie sono un altro dominio, con altre regole e altri dati
- **Ricostruzione del passato** — i consumi di ieri non esistono da nessuna parte
- **Dispositivi non Tuya** (Sonoff, Bosch, BroadLink) — fuori dal ponte
- **Backup dell'archivio** — da decidere quando i dati varranno qualcosa, cioè fra qualche mese

### Decisioni aperte

| # | Decisione | Esito | Data |
|---|---|---|---|
| 1 | I due numeri all'app: topic a sé o dentro il payload `stato` | ✅ **Topic separato** `casa/<nome>/energia` | 12/09/2026 |
| 2 | Fattore **Wh per tacca** del dp 17: misurato 0,4–0,8, standard Tuya 1,0 | ⏳ Da tarare (T-00). Il fattore vive in `dispositivi.yaml`, quindi non blocca il codice | — |
| 3 | Cosa fare delle righe a copertura bassa | ✅ Si registrano e si mostrano come sono; la copertura dice **cosa** significa il buco, che con il dp 17 e con l'integrale non è la stessa cosa (vedi §5) | 12/09/2026 |
| 4 | Quanto storico si tiene | ✅ **Per sempre.** L'eventuale compattazione delle ore vecchie in righe giornaliere si valuterà quando ci saranno anni da compattare | 12/09/2026 |

---

## 4. User Stories e criteri di accettazione

### US-001 · Quanto ha consumato oggi
**Priorità:** Must Have

Come chi paga la bolletta voglio vedere quanto ha consumato oggi ciascuna presa per sapere
quale elettrodomestico pesa davvero, invece di indovinarlo.

**Criteri di accettazione:**
- [ ] Ogni presa pubblica `kwh_oggi` su topic ritenuto, con il `giorno` a cui si riferisce
- [ ] Il valore riparte da zero a mezzanotte **italiana**, non UTC
- [ ] Riavviando il ponte a metà giornata il totale di oggi non torna a zero
- [ ] La pompa, che il dp 17 non ce l'ha, pubblica comunque un valore con `"sorgente": "integrale"`

### US-002 · Il mese in corso
**Priorità:** Must Have

Come chi paga la bolletta voglio il totale del mese accanto a quello di oggi per accorgermi
a metà mese che qualcosa consuma più del solito, non quando arriva la bolletta.

**Criteri di accettazione:**
- [ ] `kwh_mese` coerente con `SUM(kwh)` delle righe del mese in archivio
- [ ] Riparte il primo del mese
- [ ] L'app mostra i due numeri sotto la potenza e **non mostra niente** dove il dato manca

### US-003 · Interrogare per periodo
**Priorità:** Must Have

Come curioso dei miei consumi voglio interrogare l'archivio per giorno, mese e anno per
confrontare periodi senza dipendere da un servizio esterno.

**Criteri di accettazione:**
- [ ] `SELECT SUM(kwh) FROM energia GROUP BY giorno` / per mese / per anno rispondono con una
      sola query SQL, senza che chi interroga debba sapere niente di tacche e fattori
- [ ] L'archivio sopravvive a `docker compose down && up`
- [ ] Schema documentato in `bridge/README.md` con almeno tre query pronte

### US-004 · Il profilo orario
**Priorità:** Should Have

Come curioso dei miei consumi voglio sapere a che ora della giornata si consuma, perché è
un dato che se non lo registro adesso non lo avrò mai.

**Criteri di accettazione:**
- [ ] Una riga per presa e per ora locale, con data locale e istante UTC di inizio
- [ ] 25 ottobre 2026 → **25 righe**; 29 marzo 2026 → **23 righe**
- [ ] Nessuna chiave duplicata nei due giorni di cambio ora

### US-005 · Fidarsi del numero
**Priorità:** Must Have

Come chi tiene in piedi il sistema voglio che un riavvio o un'ora di presa irraggiungibile
non falsifichino i totali, per potermi fidare di una statistica fatta su tre anni.

**Criteri di accettazione:**
- [ ] Ogni riga porta `copertura` e `sorgente`
- [ ] Un azzeramento del dp 17 non produce delta negativi né salti: si somma il nuovo valore
- [ ] Un'ora con 20 minuti di buco risulta a copertura 0,67 e non come consumo basso
- [ ] Un buco **fino a un'ora** non perde energia: al ritorno il delta del dp 17 la recupera
- [ ] Un buco **oltre un'ora** non produce una riga gonfia: la lettura riparte da zero e le
      ore scoperte restano dichiarate tali
- [ ] `docker kill` a metà ora perde al massimo **un minuto** di accumulo

---

## 5. Architettura tecnica

### Componenti coinvolti

```
   presa Tuya ──UDP/TCP LAN──┐
                             │  polling 2 s        ┌──────────────┐
                      ┌──────▼──────┐  delta dp17  │   Contatore  │  uno per presa
                      │    Presa    ├─────────────►│  (in memoria)│  ora in corso
                      │  (thread)   │              └──────┬───────┘
                      └──────┬──────┘                     │ a fine ora
                             │ stato                      ▼
                             │                     ┌──────────────┐
                             │                     │   Archivio   │  una riga
                             │                     │  SQLite+lock │  per presa/ora
                             │                     └──────┬───────┘
                             │                            │ ogni minuto
                             ▼                            ▼
                    casa/<nome>/stato            stato/energia.json  (ripresa)
                    casa/<nome>/energia ◄─── totali oggi / mese
                             │
                             ▼
                       app Android
```

L'aggancio è alla riga **306** di `bridge.py`, dove arriva una lettura fresca. Il `Contatore`
tiene l'ultimo dp 17 visto — **non** `Presa._dps`, che alla riga 260 viene azzerato a ogni
riconnessione: metterlo lì farebbe contare due volte l'energia a ogni riaggancio.

### Cosa significa la copertura

Non la stessa cosa per le due sorgenti, ed è la ragione per cui la colonna `sorgente` sta
accanto a `copertura` e non altrove:

| Sorgente | Un buco di venti minuti significa |
|---|---|
| `dp17` | **L'energia non è persa**: la presa ha continuato a contare da sola e al ritorno il delta la restituisce. La copertura dice che l'attribuzione a quell'ora è approssimata, non che manchi qualcosa |
| `integrale` | **L'energia è persa davvero**: nessuno ha visto quei minuti e non c'è niente da recuperare. La copertura dice quanto manca |

Oltre l'ora la regola cambia: la lettura fa da nuovo riferimento e non si somma niente,
perché scaricare tre giorni di consumi dentro la riga dell'ora corrente sarebbe un numero
falso messo in un posto preciso — il modo peggiore di sbagliare.

### Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| `energia_grezza` (SQLite, ponte) | **Nuova** | I fatti: `presa`, `inizio_utc`, `giorno`, `ora`, `grezzo`, `sorgente`, `copertura`. PK `(presa, inizio_utc)`, indice su `(giorno, presa)`. WAL. Append-only |
| `fattori` (SQLite, ponte) | **Nuova** | L'interpretazione: una riga per presa, riscritta all'avvio da `dispositivi.yaml` |
| `energia` (vista) | **Nuova** | Il join che moltiplica: è il nome con cui si interroga l'archivio |
| `stato/energia.json` (ponte) | **Nuovo** | Accumulo dell'ora in corso, riscritto ogni minuto. Serve solo alla ripresa |
| `devices` (Room, app) | **Modifica** | Colonne `TEXT` nullabili per topic e chiavi JSON dell'energia. Versione **3 → 4** |
| `DeviceState` (app) | **Modifica** | `kwhToday`, `kwhMonth` nullabili, accanto a `watts` |

La chiave primaria sull'**istante UTC** e non su `(giorno, ora)` è la ragione per cui il 25
ottobre funziona: le 2:30 italiane esistono due volte, e con la chiave locale la seconda
sovrascriverebbe la prima. Verificato nell'immagine del ponte: quell'ora risolve al primo
dei due passaggi.

### Nuovi topic MQTT

*(Questo progetto non ha API HTTP: il contratto verso l'app sono i topic.)*

| Topic | Chi pubblica | Payload | Ritenuto |
|---|---|---|---|
| `casa/<nome>/energia` | ponte | `{kwh_oggi, kwh_mese, kwh_ieri, giorno, mese, sorgente, copertura_oggi}` | Sì |

### Breaking changes

Nessuno. I topic esistenti non cambiano, e l'app ignora i campi JSON che non conosce perché
`extractJson` cerca la chiave che le si chiede e basta. La migrazione Room è additiva e
scritta a mano, come le due precedenti: nessuna riga toccata.

---

## 6. Piano di implementazione

| ID | Task | Area | Stima (gg) | Dipende da | Responsabile |
|---|---|---|---|---|---|
| T-00 | Taratura del dp 17 con carico noto e tempo misurato (vedi §*Taratura*). **Non bloccante**: rifattibile dopo, finché l'archivio tiene il grezzo | Infra | 0,5 | — | Alberto |
| T-01 | `Contatore`: delta dp 17, azzeramenti, prima lettura come riferimento, ripiego sull'integrale, copertura. Puro, senza socket né DB | BE | 0,75 | — | Alberto |
| T-02 | Rollover ora/giorno/mese in `Europe/Rome` con DST. Puro | BE | 0,25 | T-01 | Alberto |
| T-03 | `Archivio`: schema SQLite, WAL, scrittura a fine ora, lock, stato su file e ripresa all'avvio | BE | 0,5 | T-02 | Alberto |
| T-04 | `compose.yml` (volume + `user:`), `bridge/stato/.gitkeep`, `.gitignore` | Infra | 0,1 | — | Alberto |
| T-05 | Aggancio in `Presa.run()` e `main()`; configurazione del dp e del fattore in `dispositivi.yaml` | BE | 0,25 | T-03, T-04 | Alberto |
| T-06 | Topic `casa/<nome>/energia` ritenuto, con dedupe come per lo stato | BE | 0,15 | T-05 | Alberto |
| T-07 | Prove del ponte: azzeramenti, copertura, DST, ripresa | Test | 0,5 | T-01…T-03 | Alberto |
| T-08 | App: campi, `MIGRATION_3_4`, lettura nel driver, diagnostica | FE | 0,3 | T-06 | Alberto |
| T-09 | App: formato sulla scheda (`0,84 kWh oggi · 27,3 questo mese`) e stringhe | FE | 0,2 | T-08 | Alberto |
| T-10 | Prove app: sottoscrizioni e lettura dei due campi | Test | 0,1 | T-08 | Alberto |
| T-11 | Documentazione: schema, topic, query in `bridge/README.md`; campo nuovo nel `README.md` | Doc | 0,25 | T-06 | Alberto |
| T-12 | Verifica sul campo: 24 ore, confronto col contatore di casa sul boiler | Test | 0,15 | tutti | Alberto |

**Stima totale:** 4,0 giorni/uomo, di cui 0,5 di sola attesa (T-00)
**Breakdown:** BE 1,9 gg · FE 0,5 gg · Test 0,75 gg · Doc 0,25 gg · Infra 0,6 gg

---

## 7. Piano di test

**Strategia generale:** la logica che conta va scritta pura e provata da sola — è il pattern
già in uso nel ponte, dove `test_bridge.py` costruisce una `Presa` con finte e chiama metodi
senza aprire socket. Contatore e rollover si provano senza database e senza MQTT; l'archivio
su un file temporaneo; l'app con prove JUnit sulle funzioni pure. La parte che nessuna prova
automatica può coprire — il fattore Wh/tacca — si verifica contro il contatore di casa.

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Unit | Il dp 17 che **scende** è un azzeramento: si somma il nuovo valore, mai un delta negativo | Alta |
| TC-02 | Unit | Buco **fino a un'ora**: al ritorno il delta del dp 17 si somma, l'energia del buco non si perde | Alta |
| TC-02b | Unit | Buco **oltre un'ora**: la lettura fa da nuovo riferimento, nessuna riga gonfia | Alta |
| TC-03 | Unit | Due letture identiche non aggiungono energia | Alta |
| TC-04 | Unit | Presa senza dp 17: integrale della potenza, `sorgente = integrale` | Alta |
| TC-05 | Unit | Copertura: ora piena 1,0; venti minuti di buco 0,67; presa mai vista nessuna riga | Alta |
| TC-06 | Unit | 25 ottobre 2026 → 25 righe, 29 marzo 2026 → 23 righe, nessuna chiave duplicata | Alta |
| TC-07 | Integration | Scrittura e rilettura dell'archivio su file temporaneo; `SUM` per giorno/mese/anno | Alta |
| TC-08 | Integration | Ripresa: stato salvato, processo riavviato, l'ora in corso continua | Alta |
| TC-09 | Unit (app) | Il topic dell'energia compare in `Device.subscriptions`; senza topic niente in più | Media |
| TC-10 | E2E | 24 ore sul campo: somma oraria del boiler contro il contatore di casa, entro ±5% | Alta |

### Definition of Done

- [ ] `python -m unittest test_bridge -v` verde, comprese le prove nuove
- [ ] `./gradlew :app:testDebugUnitTest` verde
- [ ] Nessun `WARNING` inatteso in `docker logs sh-ponte` dopo un'ora di funzionamento
- [ ] La migrazione Room 3→4 gira su un'installazione vera senza perdere i dispositivi registrati
- [ ] `bridge/README.md` aggiornato con schema e query
- [ ] Verifica sul campo (TC-10) fatta e annotata

> ⚠️ DA COMPLETARE: non esiste una CI in questo progetto, né un ambiente di staging. Le prove
> si lanciano a mano e l'ambiente di prova è casa. Se serve una CI, è un lavoro a parte.

---

## 8. Rischi e mitigazioni

| Rischio | Probabilità | Impatto | Mitigazione |
|---|---|---|---|
| Fattore Wh/tacca sbagliato: tutti i numeri scalati di una costante | Alta | **Basso**: l'archivio tiene il conteggio grezzo e non i kWh, quindi la taratura si rifà quando si vuole cambiando una riga della tabella `fattori`. Se l'archivio contenesse i kWh sarebbe stato Alto | Taratura (T-00, non bloccante), fattore in `dispositivi.yaml` |
| Doppio conteggio a ogni riconnessione | Media | Alto | L'ultimo dp 17 vive nel `Contatore`, non in `Presa._dps` (azzerato a riga 260). Coperto da TC-02 |
| File di stato di root nella cartella del progetto | Alta | Basso | `user:` in `compose.yml` e `stato/.gitkeep` versionato, come già fatto per `mosquitto/data` |
| Il ponte non parte da non-root | Bassa | Medio | La scoperta usa UDP 6666/6667, porte alte: nessun privilegio richiesto. Si vede subito nel log |
| Azzeramenti del dp 17 non visti fra due letture | Media | Basso | Polling a 2 s: si perde meno di una tacca. Da registrare nel log per sapere quanto spesso capita |
| Energia di un'ora attribuita all'ora dopo (aggiornamenti a strappi) | Alta | Basso | Accettato e documentato: su giorno e mese si compensa. Interpolare fingerebbe una precisione che non c'è |
| Salto dell'orologio di sistema | Bassa | Medio | Durate e copertura con `time.monotonic()`; l'orologio decide solo in quale ora finisce il valore |
| Contesa su SQLite fra i sette thread | Bassa | Basso | Una connessione sola dentro `Archivio`, con lock; sette righe per ora |

---

## 9. Rollout e rollback

**Strategia di rilascio:** deploy diretto. Niente feature flag: è un sistema di casa con un
utente, e un flag sarebbe più codice da mantenere della feature stessa. La gradualità c'è già
nell'ordine dei task — il ponte accumula e archivia (T-01…T-07) prima che l'app sappia
leggere qualcosa (T-08), quindi l'archivio si riempie e si controlla mentre l'app è ancora
com'è oggi.

```bash
cd bridge && docker compose up -d --build ponte    # ricostruisce solo il ponte
./install-all.sh --build                           # l'app, quando T-09 è pronto
```

**Piano di rollback:**
1. `git revert` delle modifiche al ponte e `docker compose up -d --build ponte`: si torna a
   pubblicare solo lo stato. **L'archivio resta sul disco** e non viene toccato
2. Se il problema è solo l'app, si reinstalla l'APK precedente: i topic in più vengono ignorati
3. **La migrazione Room non si annulla**: un'app con schema 4 non si riapre con la versione
   vecchia. Se serve tornare indietro sull'app, va disinstallata e riregistrati i dispositivi —
   sette moduli da riempire. È il motivo per cui T-08 viene dopo che il ponte è in piedi e verificato

---

## 10. Checklist di approvazione

| Revisione | Responsabile | Stato | Data |
|---|---|---|---|
| Revisione tecnica | Alberto | ⏳ In attesa | — |
| Decisione 1 (topic a sé vs payload di stato) | Alberto | ✅ Topic a sé | 12/09/2026 |
| Decisione 3 (righe a copertura bassa) | Alberto | ✅ Si registrano e si mostrano | 12/09/2026 |
| Decisione 4 (quanto storico si tiene) | Alberto | ✅ Per sempre | 12/09/2026 |
| Taratura del dp 17 | Alberto | ⏳ In attesa (T-00) | — |
| Stima approvata | Alberto | ⏳ In attesa | — |
| Data di inizio confermata | Alberto | ⏳ In attesa | — |

---

## Taratura del dp 17 (T-00)

**Perché serve.** Il dp 17 conta tacche, non watt, e quanto valga una tacca non lo dice
nessuno: lo schema Tuya standard (`add_ele`) dà 0,001 kWh, cioè 1 Wh, ma le misure di questa
sessione danno **0,4–0,8 Wh** — un fattore due di incertezza. Se il fattore è sbagliato lo
sono tutti i numeri allo stesso modo e per sempre, e nessuna prova automatica può
accorgersene: il codice funziona benissimo moltiplicando per la costante sbagliata.

**Perché le misure di oggi non bastano.** Per ricavare il fattore serve confrontare le tacche
con un'energia nota, e oggi il secondo termine non lo conosciamo: il frigorifero accende e
spegne il compressore per conto suo, e la presa rinfresca la potenza a strappi (venti minuti
ferma su 232,9 V esatti). Fra le 19:47 e le 20:03 ha fatto 12 tacche con una potenza
*dichiarata* di 35 W, ma quanto abbia consumato davvero in quei sedici minuti non lo sa nessuno.

**Cosa serve al carico.** Tre proprietà, e ognuna toglie un'incognita:

| Proprietà | Toglie |
|---|---|
| **Resistivo** (fattore di potenza 1) | Il dubbio sulla potenza: V × I = W si verifica da sé. Sul frigorifero 233,8 V × 0,251 A darebbero 58,7 VA contro i 34,7 W dichiarati — è induttivo, e i conti non tornano per costruzione |
| **Stabile** (non cicla) | Il dubbio sulla media: energia = potenza × tempo, senza integrare niente |
| **Grosso** (≥ 1 kW) | Il tempo: a 2 kW una tacca da 1 Wh arriva ogni 1,8 secondi, e in dieci minuti se ne contano trecento. A 3 W, come il depuratore, ne è arrivata **una sola in venti minuti**: un rapporto misurato su una tacca ha il 100% di errore |

**Il candidato migliore è il boiler**: è già una presa del ponte, la resistenza è un carico
resistivo puro, e sta sui 1–2 kW. Basta accenderlo a freddo per dieci minuti con il dp 17
sotto osservazione. In alternativa un phon al massimo su una presa qualunque.

### Si può fare dopo?

**Sì, e non costa nemmeno un `UPDATE` sull'archivio** — perché l'archivio i kWh non li
contiene. Contiene il conteggio grezzo, che è un fatto; il fattore sta in una riga della
tabella `fattori`, e una vista li moltiplica al momento della lettura. Ritarare a marzo un
archivio cominciato a settembre vuol dire cambiare quella riga: dieci anni di dati cambiano
valore insieme, senza riscrivere niente e senza poter sbagliare a metà.

Se invece l'archivio contenesse i kWh già moltiplicati, il fattore sarebbe inchiodato dentro
ogni riga e per correggerlo bisognerebbe sapere quale costante aveva in pancia ognuna.

Cosa si paga aspettando:

- i numeri pubblicati su MQTT e mostrati in app sono **indicativi** fino alla taratura — fino
  a un fattore due
- dopo la ritaratura i totali del giorno e del mese vanno ricalcolati dall'archivio, non
  corretti a mano
- una statistica guardata prima della taratura può far trarre conclusioni sbagliate, e quelle
  non le corregge nessun `UPDATE`

Quindi T-00 esce dal percorso critico: resta la prima cosa da fare **appena capita
l'occasione** — la prossima volta che il boiler parte da freddo — ma non tiene fermo niente.

---

## Domande aperte

Tutte risposte il 12/09/2026.

1. ~~Topic a sé o dentro `stato`?~~ → **Topic `casa/<nome>/energia` a sé.**
2. ~~Quale carico per la taratura?~~ → vedi la sezione qui sopra: **il boiler**, o un phon.
3. ~~Escludere le righe a copertura bassa?~~ → **No**: si registrano e si mostrano come sono,
   con la copertura accanto. Cosa significhi un buco dipende dalla sorgente (§5).
4. ~~Quanto storico si tiene?~~ → **Per sempre.** Se un giorno pesasse, si valuterà di
   compattare le ore vecchie in righe giornaliere — lo schema lo permette con una
   `SUM ... GROUP BY giorno` — e quella decisione si prenderà quando ci saranno anni da
   compattare, non adesso.

---

*Documento generato con la skill `claude-code-feature`.*
