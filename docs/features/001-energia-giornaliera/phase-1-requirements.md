# Fase 1 — Requisiti: energia storicizzata per presa

**Feature:** `001-energia-giornaliera`
**Data:** 12 settembre 2026
**Stack:** ponte Python (`bridge/tuya-mqtt/bridge.py`), broker Mosquitto, app Android Compose
**Tipo di progetto:** due componenti in casa — un servizio di traduzione Tuya→MQTT e un'app che legge solo topic MQTT
**Team:** una persona

---

## 1. Obiettivo e motivazione

Le sette prese misurano i consumi e il ponte pubblica già potenza, corrente e tensione
istantanee. Quello che nessuno tiene è il **tempo**: quanto ha consumato il boiler oggi,
questo mese, quest'anno. La potenza istantanea risponde a "sta lavorando adesso"; non
risponde a "quanto mi costa".

Il dato non si può prendere dalle prese, e non è una supposizione — è misurato in questa
sessione:

| Evidenza | Misura |
|---|---|
| Nessuna presa pubblica un'energia giornaliera | nei `dps` non c'è nessun campo con quella semantica |
| Il dp 17 (`add_ele` dello schema Tuya) **non è monotono** | frigorifero: 30 alle 17:28 → 6 alle 19:47 → 18 alle 20:03 → 42 alle 20:35 |
| Il dp 17 avanza a **tacche intere**, ~1 Wh | depuratore a ~3 W: una sola tacca in 20 minuti (1→2 alle 20:11) |
| Il dp 17 si aggiorna **a strappi** | frigorifero fermo su 18 per oltre 20 minuti, poi +24 in blocco |
| La **pompa non espone il dp 17** | il suo payload non contiene la chiave `17` |
| Le misure istantanee **restano congelate** | frigorifero su 246 mA / 35,2 W / **232,9 V esatti** per venti minuti: non è la rete, è la presa che non rinfresca |

Da qui discendono i due vincoli che danno forma alla feature: **l'energia va accumulata da
chi guarda**, e **va accumulata dai delta del dp 17**, che è l'integrazione fatta dalla
presa con le sue misure vere — non dal nostro campionamento di valori che stanno fermi
mezz'ora.

La risoluzione scelta è **oraria**: una riga per presa per ora. Giorno, mese e anno si
ottengono sommando, e in più resta la domanda che le sole righe giornaliere perderebbero
per sempre — a che ora della giornata si consuma. È la scelta non correggibile a posteriori.

## 2. Scope

### Incluso

- Accumulo continuo dell'energia per presa dentro il ponte, dai delta del dp 17, con
  ripiego sull'integrale di `potenza_w` per le prese che il dp 17 non ce l'hanno (oggi: la
  pompa)
- **Copertura**: per ogni ora, quanti minuti il ponte ha davvero visto la presa. Un'ora con
  un buco non deve leggersi come un'ora di consumo basso
- Chiusura dell'ora e del giorno con fuso `Europe/Rome`, DST compreso (i due giorni l'anno
  da 23 e 25 ore)
- Archivio locale in **SQLite**, una riga per presa/ora, dentro un volume scrivibile del
  container. Nessun servizio nuovo
- Recupero dopo un riavvio del ponte: l'accumulo dell'ora in corso non si perde
- Pubblicazione MQTT dei valori correnti: `kwh_oggi` e `kwh_mese` per presa, ritenuti
- Visualizzazione in app: sotto la potenza istantanea, i due numeri (oggi e mese)
- Documentazione: `bridge/README.md` (nuovi topic, schema dell'archivio, query di esempio) e
  `README.md` (il campo nuovo nel modulo di registrazione)

### Escluso (out of scope)

- **Grafici**: niente Grafana, niente InfluxDB/VictoriaMetrics, nessun container in più.
  Se serviranno, l'archivio orario è già la sorgente da cui partire
- **Storico dentro l'app**: l'app mostra due numeri correnti, non serie temporali. Niente
  schermata di dettaglio, niente grafici a barre
- **Costi in euro**: nessuna tariffa, nessuna fascia oraria. Solo kWh
- **Ricostruzione del passato**: l'archivio parte dal giorno in cui entra in funzione. I
  consumi di ieri non esistono da nessuna parte e non si possono inventare
- **Energia di dispositivi non Tuya** (Sonoff, Bosch, BroadLink): fuori dal ponte, fuori da qui
- Backup automatico dell'archivio

## 3. User stories

1. **Come chi paga la bolletta** voglio vedere quanto ha consumato oggi ciascuna presa
   **per** sapere quale elettrodomestico pesa davvero, invece di indovinarlo.

2. **Come chi paga la bolletta** voglio il totale del mese in corso accanto a quello di
   oggi **per** accorgermi a metà mese che qualcosa sta consumando più del solito, non
   quando arriva la bolletta.

3. **Come curioso dei miei consumi** voglio interrogare l'archivio per giorno, mese e anno
   **per** confrontare periodi ("ottobre contro novembre", "quest'inverno contro l'anno
   scorso") senza dipendere da un servizio esterno.

4. **Come curioso dei miei consumi** voglio sapere a che ora della giornata si consuma
   **per** capire se conviene spostare la lavatrice, e perché è un dato che se non lo
   registro adesso non lo avrò mai.

5. **Come chi tiene in piedi il sistema** voglio che un riavvio del ponte o un'ora di
   presa irraggiungibile non falsifichino i totali **per** potermi fidare di una statistica
   fatta su tre anni di righe.

## 4. Criteri di accettazione

### Story 1 — consumo di oggi

- [ ] Ogni presa pubblica `kwh_oggi` su topic ritenuto, con il `giorno` a cui si riferisce
- [ ] Il valore riparte da zero a mezzanotte ora italiana, non a mezzanotte UTC
- [ ] Riavviando il ponte a metà giornata il totale di oggi non torna a zero
- [ ] Una presa senza dp 17 (pompa) pubblica comunque un valore, dichiarato come stimato

### Story 2 — totale del mese

- [ ] Ogni presa pubblica `kwh_mese`, coerente con la somma delle righe del mese in archivio
- [ ] Il valore riparte al primo del mese
- [ ] L'app mostra i due numeri sotto la potenza, e non mostra niente dove il dato manca

### Story 3 — interrogazione per periodo

- [ ] L'archivio risponde a `SUM(kwh) GROUP BY giorno`, `... GROUP BY mese`, `... GROUP BY anno`
      con una sola query SQL, senza post-elaborazione
- [ ] L'archivio sopravvive a `docker compose down && up`
- [ ] Lo schema è documentato in `bridge/README.md` con almeno tre query pronte

### Story 4 — profilo orario

- [ ] Una riga per presa e per ora locale, con la data locale e l'istante UTC di inizio
- [ ] I due giorni di cambio ora producono 23 e 25 righe, non 24 con un'ora doppia o persa

### Story 5 — affidabilità del dato

- [ ] Ogni riga porta la **copertura** (minuti visti sui minuti dell'ora) e la **sorgente**
      (`dp17` o `integrale`)
- [ ] Un azzeramento del dp 17 fra due letture non produce un delta negativo né un salto:
      il contatore riparte e si somma il nuovo valore
- [ ] Un'ora in cui la presa è stata irraggiungibile per 20 minuti risulta con copertura 67%,
      non con un consumo basso e nessuna traccia del buco
- [ ] Uccidendo il container a metà ora (`docker kill`) si perde al massimo un minuto di accumulo

## 5. Rischi e dipendenze

### Rischi tecnici

| Rischio | Impatto | Mitigazione |
|---|---|---|
| **L'unità del dp 17 non è confermata** — le misure dicono 0,4–0,8 Wh a tacca, lo schema Tuya standard dice 1 Wh (`add_ele`, 0,001 kWh) | Tutti i numeri sbagliati di un fattore costante, per sempre | Taratura misurata: un carico noto (phon, scaldabagno) acceso per un tempo misurato, confronto con il contatore di casa. Da fare **prima** di accumulare mesi di dati |
| **Azzeramenti del dp 17 non visti** fra due letture | Energia persa, sottostima | Polling a 2 s: si perde al massimo la frazione sotto la tacca. Da registrare nel log quando accade, per sapere quanto spesso |
| **Aggiornamento a strappi** del dp 17 | L'energia di un'ora può arrivare nell'ora dopo, spostando qualche Wh fra due righe | Accettato e documentato: sul giorno e sul mese si compensa. L'alternativa (attribuire per interpolazione) fingerebbe una precisione che non c'è |
| **La pompa non ha il dp 17** e le sue misure istantanee restano congelate | Il suo dato vale meno degli altri | Sorgente dichiarata per riga (`integrale`), così una statistica sa di cosa si sta fidando |
| **Perdita dello stato in corso** a crash del container | Fino a un'ora di accumulo persa | Stato dell'ora in corso scritto su file ogni minuto |
| **DST** | Un'ora doppia o mancante l'anno, con chiave primaria duplicata | Chiave sull'istante UTC di inizio ora; data e ora locali come colonne derivate |
| Crescita dell'archivio | Trascurabile: 7 × 24 × 365 ≈ 61.000 righe l'anno, pochi MB in dieci anni | Nessuna, se non l'attenzione a non metterlo in git |

### Dipendenze

- **Nessun componente nuovo.** Il ponte c'è, il broker c'è, l'app c'è
- `compose.yml`: un volume scrivibile per il ponte, e `user:` come già fa il broker — altrimenti i file di stato nascono di root nella cartella del progetto
- `.gitignore`: l'archivio è un dato, non codice
- App: un campo nuovo nel modulo di registrazione (o due), come si è appena fatto per la
  potenza istantanea, con la relativa migrazione Room. **Da decidere in fase 2**: se i due
  numeri viaggiano dentro il payload di stato già esistente (app più semplice, contratto del
  topic `stato` un po' meno puro) o su un topic `energia` a sé (contratto pulito, tre campi
  in più da riempire per ciascuna delle sette prese)
- Taratura del dp 17: serve un carico noto e mezz'ora di pazienza. **Blocca la fiducia nei
  numeri, non l'implementazione**

## 6. Stima effort

Giorni/uomo per una persona sola, su una codebase che si conosce.

| Area | Giorni | Cosa |
|---|---|---|
| Ponte (BE) | 1,5 | Accumulatore con gestione azzeramenti, copertura, rollover ora/giorno/mese con DST, SQLite, stato su file, nuovi topic |
| App (FE) | 0,5 | Campi nuovi + migrazione Room 3→4, lettura, formato sulla scheda |
| Test | 0,5 | Unit sull'accumulatore (azzeramento, buchi, DST, riavvio) e sul parsing lato app |
| Documentazione | 0,25 | `bridge/README.md` con schema e query, `README.md` per il campo nuovo |
| **Totale** | **~2,75** | |

Fuori stima, da fare a parte: la **taratura del dp 17** (~0,5 giorni, in gran parte attesa).

## 7. Milestones

1. **Taratura del dp 17** — carico noto, tempo misurato, fattore Wh/tacca confermato o corretto.
   Conviene presto, ma **non blocca**: l'archivio tiene il conteggio grezzo e il fattore usato
   in ogni riga, quindi ritarare dopo è un `UPDATE` e non una perdita
2. **Accumulatore in memoria** — delta del dp 17 con azzeramenti, ripiego sull'integrale,
   conteggio della copertura. Testabile da solo, senza database e senza MQTT
3. **Rollover ora / giorno / mese** in `Europe/Rome`, DST compreso. Anche questo puro, anche
   questo testabile da solo
4. **Persistenza**: schema SQLite, scrittura a fine ora, stato dell'ora in corso su file,
   ripresa all'avvio. Volume e `user:` in `compose.yml`, riga in `.gitignore`
5. **Topic MQTT**: `casa/<nome>/energia` ritenuto con i totali, e i due numeri che servono
   all'app dove l'app li sa leggere (vedi decisione in fase 2)
6. **App**: campi, migrazione Room, formato sulla scheda (`0,84 kWh oggi · 27,3 questo mese`)
7. **Documentazione**: schema dell'archivio e query di esempio nel README del ponte
8. **Verifica sul campo**: 24 ore di funzionamento, confronto della somma oraria con il
   contatore di casa su un carico grosso e noto (il boiler)
