# Consumi compatti — Requisiti

**Stato:** Fase 1 — decisioni sciolte, pronta per la Fase 2
**Autore:** Alberto Goldoni
**Data:** 14 settembre 2026
**Versione:** 1.1 (14 settembre 2026: sciolte le due domande aperte)
**Feature:** `005-consumi-compatti`

---

## 1. Obiettivo e motivazione

La scheda di un dispositivo che espone l'energia mostra oggi una riga così:

```
0,84 kWh oggi · 27,3 questo mese
```

Due numeri, trentadue caratteri, e una domanda a cui non risponde. Perché «oggi» da solo
non dice niente: alle otto di mattina sono 0,08 kWh e alle undici di sera sono 2,4, e senza
un termine di paragone non si sa se sia tanto o poco. Il mese non aiuta — è la bolletta, non
il comportamento: cresce comunque, e a fine mese cresce per forza. Quello che manca è
**ieri**, che è l'unico numero che rende leggibile «oggi» in qualunque momento della
giornata, e **la settimana**, che dice se ieri era un caso o una tendenza.

Il dato per rispondere **c'è già, per intero, dal 12 settembre**: la feature 001 ha messo in
`stato/energia.db` una riga per presa e per ora, con giorno e ora locali. Ieri e la settimana
sono due `SUM` su quella tabella. Non si sono mai pubblicate perché nessuno le aveva chieste.

La seconda metà della feature è **lo spazio**. Le schede sono sette, stanno in una lista che
si scorre col pollice, e ciascuna porta già nome, stanza, stato, watt e — per chi ce li ha —
i consumi. Passare da due numeri a quattro non può costare una riga in più per scheda:
quattro etichette scritte per esteso occuperebbero due righe e mangerebbero una scheda di
elenco. Da qui la forma scelta, che è la più densa possibile:

```
0,42/1,87/6,30/12,7 kWh
```

Quattro numeri, un ordine fisso — **oggi, ieri, settimana, mese** — e nessuna etichetta.
L'ordine si impara una volta sola e poi non si legge più: si guarda se il primo numero è più
grande del secondo. È una scelta deliberata di densità contro autoesplicatività, presa
sapendo cosa costa (vedi R-1), e vale perché **chi legge questa riga è la stessa persona che
ha configurato la casa**, non un ospite.

**Metriche di successo:**

- Guardando una scheda si capisce **in un colpo d'occhio** se oggi quel dispositivo sta
  consumando più di ieri, a qualunque ora del giorno
- I consumi restano su **una riga sola**: nessuna scheda cresce in altezza, l'elenco dei
  sette dispositivi occupa lo stesso spazio di oggi
- Un valore che non c'è **si vede che non c'è**: mai un numero che scivola nella casella
  del vicino, mai un mese letto come se fosse una settimana
- Un'app ferma alla **1.3.0** che riceve il payload nuovo continua a mostrare i suoi due
  numeri come sempre: i campi che non conosce li ignora
- Un ponte fermo alla versione di oggi non fa sbagliare l'app nuova: i due valori che non
  arrivano restano vuoti, gli altri due si mostrano

---

## 2. Scope

### Incluso

**Il ponte**
- Due valori nuovi nel payload di `casa/<nome>/energia`: **`kwh_ieri`** e
  **`kwh_settimana`**, accanto a `kwh_oggi` e `kwh_mese`
- **`kwh_ieri`**: il totale del giorno solare precedente, chiuso e in archivio. Non contiene
  nessuna ora in corso, per definizione
- **`kwh_settimana`**: la **settimana corrente**, da lunedì a adesso, ora in corso compresa.
  Confine di calendario come il mese, fuso `Europe/Rome` come tutto il resto dell'archivio
- Due campi nuovi nella testata informativa del payload — `ieri` e `settimana` — con le date
  a cui i numeri si riferiscono, come già `giorno` e `mese`
- **Un valore che l'archivio non ha non si pubblica**: se per ieri non c'è nemmeno una riga,
  la chiave `kwh_ieri` **manca**, invece di valere zero. Zero vuol dire «non ha consumato»,
  ed è una cosa diversa da «non c'era nessuno a contare»
- I totali si rileggono quando si chiude un'ora, come già fanno giorno e mese: nessuna query
  in più nel giro di polling

**Il contratto del registro**
- Due campi nuovi per dispositivo: **`campo_kwh_ieri`** e **`campo_kwh_settimana`**, stringa
  o `null`, facoltativi
- **Additivi: `schema` resta `1`.** È la regola già scritta in `SCHEMA.md` — un lettore
  vecchio ignora quello che non conosce, uno nuovo trova il predefinito quando il campo non
  c'è

**Il configuratore web**
- I due campi nuovi nella sezione dei consumi, accanto ai due che ci sono
- Il modello delle prese del ponte li precompila con `kwh_ieri` e `kwh_settimana`, come già
  fa per gli altri due

**L'app Android**
- Due colonne nuove nel database locale (migrazione additiva **6→7**) e due valori nuovi
  nello stato osservato
- La riga dei consumi diventa `0,42/1,87/6,30/12,7 kWh`: quattro caselle in ordine fisso
- **Un valore mancante è un trattino**, e la casella resta al suo posto: `0,42/–/6,30/12,7`
- **Il formato a tre scalini** (D-5): sotto 10 kWh due decimali, sotto 100 un decimale solo,
  da 100 in su nessuno. La stessa regola per tutti e quattro i numeri, e non per eleganza:
  tiene ogni casella dentro i **quattro caratteri**, che è quello che fa stare la riga
- Se non si sa **nessuno** dei quattro, la riga non compare — come oggi
- Il modulo di registrazione a mano espone i due campi nuovi, come gli altri due

**Lo stack di sviluppo**
- Le prese finte pubblicano quattro valori, e almeno una ne pubblica solo due, perché il
  caso del trattino si veda senza costruirlo a mano
- `genera-registro-dev.mjs` conosce i due campi nuovi

**La documentazione**
- `bridge/configuratore/SCHEMA.md`: i due campi nel contratto
- `bridge/README.md`: il payload dell'energia nuovo, e cosa vuol dire la settimana
- `README.md`: la riga della scheda, **con la legenda dell'ordine** — è l'unico posto dove
  quell'ordine è scritto

### Escluso (out of scope)

- **L'anno.** C'è nell'archivio ed è una query come le altre, ma sulla scheda sarebbe un
  quinto numero, e quattro è già il limite di quanto una riga sola regge
- **Il costo in euro.** Servirebbe una tariffa, che è configurazione nuova e cambia da sola
  ogni tre mesi
- **Grafici, storico sfogliabile, dettaglio orario.** Restano dove la 001 li ha lasciati:
  nell'archivio, interrogabile con `sqlite3`. Questa feature non apre nessuna schermata
- **Etichette, legenda o intestazioni sulla scheda.** È la decisione D-1: l'ordine si impara,
  non si scrive
- **Ordine dei quattro numeri configurabile.** Un ordine che cambia da telefono a telefono
  distruggerebbe l'unica cosa che rende leggibile una riga senza etichette
- **Cambiare lo schema dell'archivio.** `energia_grezza` e la vista `energia` restano
  identiche: qui si aggiungono due `SELECT`, non una colonna
- **Ricostruire il passato.** Ieri e la settimana valgono quello che l'archivio ha visto; per
  una presa aggiunta stamattina «ieri» non esiste, e si vede che non esiste
- **La settimana come finestra mobile** sugli ultimi sette giorni. Scartata in D-2
- **Alzare `schema` nel registro.** L'aggiunta è additiva e la regola per non alzarlo è già
  scritta

---

## 3. User stories

**US-1** — Come chi apre l'app la sera, voglio vedere **oggi accanto a ieri**, per capire in
un istante se il boiler sta consumando più del solito senza aprire niente e senza ricordarmi
a memoria il numero di ieri.

**US-2** — Come chi guarda l'elenco delle sette schede, voglio che i quattro numeri stiano
su **una riga sola**, perché lo spazio verticale è quello che decide quante schede vedo
senza scorrere.

**US-3** — Come chi legge una riga in cui un numero manca, voglio **vedere il buco**, perché
tre numeri su quattro senza segnaposto li leggerei nella casella sbagliata e crederei che il
mese sia la settimana.

**US-4** — Come chi ha un secondo telefono fermo alla 1.3.0, voglio che continui a mostrare
oggi e mese come ha sempre fatto, perché non aggiorno due telefoni lo stesso giorno.

**US-5** — Come chi aggiunge una presa dal configuratore, voglio che i due campi nuovi
arrivino **già compilati** dal modello, perché sono sempre gli stessi due nomi e scriverli a
mano è solo un modo di sbagliarli.

**US-6** — Come chi guarda la scheda di lunedì mattina, voglio che «settimana» si sia
**azzerata**, perché è un totale di calendario come il mese e deve comportarsi come il mese.

**US-7** — Come chi ha aggiornato l'app ma non ancora il ponte, voglio vedere i due numeri
che il ponte manda e il trattino sugli altri due, invece di una riga sparita o di due zeri
inventati.

---

## 4. Criteri di accettazione

### US-1 — oggi accanto a ieri

- [ ] Il payload di `casa/<nome>/energia` contiene `kwh_ieri` e `kwh_settimana`
- [ ] `kwh_ieri` coincide con `SELECT SUM(kwh) FROM energia WHERE presa=? AND giorno=?` sul
      giorno precedente, a meno dell'arrotondamento a tre decimali
- [ ] `kwh_ieri` **non** cambia durante la giornata: è un giorno chiuso
- [ ] Sulla scheda i quattro numeri compaiono nell'ordine oggi, ieri, settimana, mese

### US-2 — una riga sola

- [ ] La riga dei consumi resta `maxLines = 1`
- [ ] Con quattro valori a tre cifre intere la riga non viene troncata sulla larghezza di
      uno schermo da telefono
- [ ] Ogni numero occupa **al massimo quattro caratteri**: `9,99`, `99,9`, `999`, `9999`
- [ ] La riga nel caso peggiore sta in **22 caratteri**, cioè è più corta dei 32 della riga
      di oggi: la feature aggiunge due numeri e **toglie** larghezza
- [ ] L'altezza della scheda è la stessa di prima della feature

### US-3 — il buco si vede

- [ ] Un payload con `kwh_oggi` e `kwh_mese` soltanto produce `0,42/–/–/12,7 kWh`
- [ ] Un payload senza nessuno dei quattro valori **non** produce nessuna riga
- [ ] Un valore pari a zero si mostra come `0,00`, **non** come trattino: zero e assente
      sono cose diverse e si vedono diverse

### Il formato dei numeri

- [ ] Sotto 10 kWh due decimali: `0,42`, `9,99`
- [ ] Da 10 a 99,9 un decimale solo: `12,7`, `99,9`
- [ ] Da 100 in su nessun decimale: `104`, `439`
- [ ] La regola è **la stessa per tutte e quattro le caselle**: non esiste un numero che si
      formatta diversamente perché sta nella casella del mese

### US-4 — l'app vecchia non si accorge di niente

- [ ] Un'app **1.3.0** che riceve il payload con quattro valori mostra
      `0,42 kWh oggi · 12,7 questo mese`, senza errori
- [ ] Un'app **1.3.0** che legge il registro con i due campi nuovi mostra i sette
      dispositivi, nessuno saltato

### US-5 — i campi arrivano compilati

- [ ] «Usa modello» in configuratore compila `campo_kwh_ieri` e `campo_kwh_settimana` con
      `kwh_ieri` e `kwh_settimana`
- [ ] Il registro pubblicato contiene i due campi nuovi e supera la lettura dell'app nuova
- [ ] Un registro **senza** i due campi non fa saltare nessun dispositivo: quei due valori
      restano vuoti e sulla scheda sono trattini

### US-6 — la settimana è di calendario

- [ ] `kwh_settimana` include lunedì e **non** include la domenica precedente
- [ ] Lunedì alle 00:00 locali `kwh_settimana` riparte e vale quanto `kwh_oggi`
- [ ] Il confine è nel fuso `Europe/Rome`, non in UTC: alle 00:30 italiane di lunedì il
      valore è già ripartito
- [ ] La settimana che contiene un cambio d'ora legale è coerente con l'archivio: la somma
      pubblicata coincide con la somma delle righe di quei giorni

### US-7 — ponte vecchio, app nuova

- [ ] Con il ritenuto vecchio ancora sul broker, la scheda mostra
      `0,42/–/–/12,7 kWh` e nient'altro cambia
- [ ] Al primo payload del ponte aggiornato la riga si completa senza riavviare l'app

### Regressioni da non introdurre

- [ ] La modifica del registro **non** provoca risottoscrizioni: i campi nuovi cambiano
      *cosa* si legge nel payload dell'energia, quindi entrano legittimamente in
      `listensLike` — va verificato che la prima applicazione del registro nuovo
      risottoscriva **una volta sola** e non a ogni pubblicazione successiva
- [ ] I 66+ test unitari esistenti continuano a passare
- [ ] La migrazione 6→7 conserva i dispositivi registrati a mano

---

## 5. Rischi e dipendenze

| # | Rischio | Impatto | Mitigazione |
|---|---|---|---|
| **R-1** | **Una riga senza etichette non si spiega.** `0,42/1,87/6,30/12,7 kWh` non dice a nessuno cosa siano quei quattro numeri: chi non ha scritto questa feature lo deve sapere da fuori | Medio — è il costo accettato della densità | Ordine **fisso e mai configurabile**, quattro caselle **sempre** presenti (trattino compreso) così le posizioni non ballano mai, e la legenda scritta in `README.md`. È la decisione D-1, presa a ragion veduta |
| **R-2** | **Numeri di ordini di grandezza diversi accostati.** `formatKwh` dava due decimali sotto 10 e uno sopra, senza più scalini: con un boiler si sarebbe arrivati a `2,31/18,4/104,2/438,7`, quattro larghezze diverse in fila | Basso | **Chiuso da D-5**: tre scalini, `2,31/18,4/104/439`. Una sola regola per tutti e quattro i numeri, mai un allineamento a colonne — che su una riga sola non esiste comunque |
| **R-3** | **La riga si allunga e viene troncata.** Quattro numeri più `kWh` contro `maxLines = 1` e uno schermo stretto: nel caso peggiore l'ellissi mangerebbe proprio il totale del mese | Medio — un numero troncato è peggio di un numero assente | **Chiuso da D-5, per costruzione e non per misura**: gli scalini limitano ogni casella a quattro caratteri, quindi la riga peggiore possibile è `9,99/99,9/999/9999 kWh`, 22 caratteri contro i 32 della riga di oggi. Resta da guardarla una volta sullo schermo vero, ma non è più un rischio aperto |
| **R-4** | **`_rileggi_totali()` gira solo alla chiusura di un'ora e all'avvio.** Va bene per ieri e per la settimana — mezzanotte e la mezzanotte di lunedì sono chiusure d'ora — ma solo finché il ciclo continua a chiamare `conta()` anche quando la presa non risponde | Medio | Il ciclo chiama già `conta(None)` sui fallimenti di lettura, quindi le ore si chiudono anche a presa irraggiungibile: **da verificare in Fase 2, non da dare per scontato**. Se l'invariante non regge, il numero di ieri resterebbe fermo a quello dell'altro ieri, che è esattamente il genere di errore che nessuno nota |
| **R-5** | **Il ritenuto vecchio sul broker di casa.** Fino al `deploy.sh` che porta su il ponte nuovo, il payload ritenuto ha due valori su quattro | Basso | È US-7, ed è un requisito invece che un incidente: due trattini e nessun danno. Nell'ordine delle milestone il ponte va **prima** dell'app |
| **R-6** | **Zero contro assente.** `SUM` su zero righe in SQLite con `COALESCE(...,0)` dà `0`, che pubblicato diventa «non ha consumato niente» — falso per una presa aggiunta stamattina | Medio — è una bugia, e questa è un'app che si è sempre rifiutata di dirne | La chiave si **omette** quando l'archivio non ha righe per quel periodo. Serve una query che distingua «somma zero» da «nessuna riga», quindi non basta riusare `totale()` così com'è |
| **R-7** | **Due campi nuovi in `Device` cambiano `listensLike`.** È scritto come esclusione, quindi i campi nuovi entrano da soli — ed è giusto, perché cambiano davvero come si legge il payload. Ma la prima applicazione del registro nuovo risottoscrive tutti i dispositivi dell'energia | Basso | È una volta sola e al primo registro nuovo. Va comunque verificato che sia **una** e non una per pubblicazione: è lo stesso nervo di R-1 della 004 |
| **R-8** | **Lo stack di sviluppo va rigenerato.** Il broker di `devops/dev` non ha persistenza, il registro si ripubblica a ogni `up`, e le prese finte pubblicano un payload a due valori scritto a mano | Basso | Fa parte del lavoro: è l'ambiente dove la feature si prova, e serve che ci sia anche il caso a due valori per vedere il trattino |
| **R-9** | **Il costo delle query.** Alla chiusura di un'ora si passa da due `SUM` a quattro per presa, e quella della settimana è su un intervallo invece che su un prefisso | Trascurabile | Sette prese, una volta l'ora, su un indice `(giorno, presa)` che esiste già. Da tenere d'occhio solo perché l'intervallo non usa il `LIKE` a prefisso e potrebbe non toccare lo stesso indice |

**Dipendenze**

- Il ponte e il configuratore viaggiano **dentro le immagini** costruite sul PC: una modifica
  richiede build `linux/arm64` e `./devops/deploy.sh`, non un `rsync`
- L'app sale a **1.4.0** e prende una migrazione di database **6→7**
- Le prove si fanno contro `devops/dev`, mai contro il Pi
- La settimana di calendario richiede di conoscere il lunedì nel fuso locale: `ZoneInfo` e
  `datetime` sono già importati nel ponte, nessuna dipendenza nuova

---

## 6. Stima effort

Un solo sviluppatore; giornate di lavoro effettivo, non di calendario.

| Area | Giorni | Cosa |
|---|---:|---|
| Ponte | 0,5 | Le due query nuove (con la distinzione «nessuna riga»), il lunedì nel fuso locale, i due campi nel payload, i test in `test_bridge.py` |
| Contratto e configuratore | 0,25 | I due campi in `SCHEMA.md`, in `campi.js` e nel modello di `modelli.js` |
| App | 0,75 | Migrazione 6→7, `Device` e `DeviceState`, lettura in `MqttDeviceDriver`, lettura dal registro, i due campi nel modulo, la riga compatta con i trattini |
| Stack di sviluppo | 0,25 | Prese finte a quattro valori (e una a due), `genera-registro-dev.mjs` |
| Documentazione | 0,25 | `bridge/README.md`, `README.md` con la legenda dell'ordine |
| Verifica | 0,5 | Prove contro `devops/dev`, il caso del trattino, il caso dell'app 1.3.0, la larghezza della riga sullo schermo stretto |
| **Totale** | **2,5** | |

---

## 7. Milestones

1. **Il contratto prima di tutto.** I due campi in `SCHEMA.md`, con la regola dell'assenza
   (`null` = nessuno lo legge) e la nota che `schema` resta `1`
2. **Il ponte.** Query di ieri e della settimana, con la distinzione fra somma zero e nessuna
   riga; il lunedì locale; i due campi nel payload; i test
3. **Lo stack di sviluppo.** Prese finte a quattro valori, una a due, registro rigenerato:
   da qui in poi la feature si può guardare
4. **L'app, dal basso.** Migrazione 6→7, i due campi in `Device`, la lettura del payload,
   la lettura dal registro — tutto senza toccare l'interfaccia. A questo punto i valori
   arrivano e nessuno li vede
5. **La riga compatta.** Il formato a quattro caselle con i trattini, i test sul formato
6. **Il modulo di registrazione** e il configuratore web: i due campi da compilare a mano e
   il modello che li precompila
7. **La documentazione,** compresa la legenda dell'ordine in `README.md` — che è l'unica
   cosa che rende leggibile la riga a chi non l'ha scritta
8. **La verifica** contro `devops/dev`, poi `./devops/deploy.sh` sul Pi: **il ponte prima
   dell'app**, così il caso a due trattini non capita a nessuno per caso

---

## 8. Decisioni prese

**D-1 — La riga è solo numeri, con le barre.** `0,42/1,87/6,30/12,7 kWh`, senza etichette,
senza intestazione, senza legenda sulla scheda. Le alternative erano una riga di etichette
minuscole sopra i numeri (costa una riga per scheda, su sette schede) e le etichette in
linea (`oggi 0,42 · ieri 1,87 · …`, che non sta su una riga sola). Si accetta che la riga
non si spieghi da sé: chi la legge è chi ha configurato la casa, e la legenda sta nel
`README.md`. In cambio, due vincoli diventano non negoziabili — **l'ordine non cambia mai** e
**le caselle sono sempre quattro**, perché senza etichette la posizione è l'unica cosa che
dice cosa sia un numero.

**D-2 — «Settimana» è la settimana corrente, da lunedì a adesso.** Non gli ultimi sette
giorni. Il motivo è la coerenza col numero che le sta accanto: «mese» è già il mese in corso,
e due finestre con criteri diversi appiccicate sulla stessa riga sarebbero una trappola. Si
accetta il difetto noto — **lunedì mattina il terzo numero è quasi uguale al primo** — perché
è lo stesso difetto che «mese» ha il primo del mese, e nessuno se n'è mai lamentato.

**D-3 — Prima il documento, poi il codice.** Come per le altre quattro feature: la modifica
attraversa ponte, contratto del registro, configuratore e app, e il contratto è la parte che
costa di più da cambiare dopo.

**D-4 — Un valore che non c'è non si pubblica e non si finge.** Nel ponte la chiave manca,
nell'app la casella è un trattino. Zero resta disponibile per dire l'unica cosa che
significa: quel dispositivo, in quel periodo, non ha consumato. È la stessa regola che l'app
applica già ai payload di disponibilità — da un valore che non si è capito non si deduce
niente — e qui si estende a un valore che non è mai arrivato.

**D-5 — Il formato ha tre scalini, non due.** Sotto 10 kWh due decimali (`0,42`), sotto 100
un decimale solo (`12,7`), da 100 in su nessuno (`439`). Sostituisce la regola a due scalini
di `formatKwh`, che sopra i 10 kWh teneva il decimale per sempre. La ragione non è estetica:
**ogni casella sta così in quattro caratteri**, e la riga peggiore possibile —
`9,99/99,9/999/9999 kWh` — è di 22 caratteri contro i 32 di `0,84 kWh oggi · 27,3 questo
mese`. La feature aggiunge due numeri e la riga si **accorcia**. È quello che chiude R-2 e
R-3 senza rimandarli a una misura.

**D-6 — I due campi nel payload si chiamano `kwh_ieri` e `kwh_settimana`.** Seguono
`kwh_oggi` e `kwh_mese` senza sorprese, e sul ramo `energia` non confliggono con niente di
già pubblicato.

---

## 9. Domande aperte

**Nessuna.** Le due della versione 1.0 sono sciolte in **D-5** (il formato a tre scalini) e
**D-6** (il nome dei due campi). Il documento è completo e la Fase 2 può partire.
