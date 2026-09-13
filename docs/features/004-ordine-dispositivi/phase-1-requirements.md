# Ordine dei dispositivi — Requisiti

**Stato:** Fase 1 — decisioni sciolte, pronta per la Fase 2
**Autore:** Alberto Goldoni
**Data:** 13 settembre 2026
**Versione:** 1.1 (13 settembre 2026: sciolte le tre domande aperte)
**Feature:** `004-ordine-dispositivi`

---

## 1. Obiettivo e motivazione

L'elenco della vista principale è ordinato da una riga di SQL che nessuno ha mai deciso
davvero: `ORDER BY room COLLATE NOCASE, name COLLATE NOCASE`. E siccome `stanza` non viaggia
nel registro — SCHEMA.md lo dice a chiare lettere, «l'app non ordina né raggruppa per
stanza» — per i sette dispositivi di casa `room` è la stringa vuota per tutti. Resta
l'alfabeto.

L'alfabeto è un ordine, ma è l'ordine sbagliato: non ha nessun rapporto con quanto spesso si
tocca un dispositivo. La feature 003 ha stabilito che quella vista è fatta perché
«l'interruttore cada sotto il pollice»; oggi *quale* interruttore cada sotto il pollice lo
decide l'iniziale del nome. E siccome l'iniziale del nome è anche una cosa che si cambia dal
configuratore in due secondi, rinominare un dispositivo **riordina la casa**: la memoria
muscolare che si era formata su sette schede salta senza preavviso.

Questa feature dà all'elenco un ordine **scelto**, deciso a mano trascinando le righe nel
configuratore web, e lo fa viaggiare nel registro insieme a tutto il resto — così è lo
stesso su ogni telefono, come già lo sono i nomi e i topic.

**Metriche di successo:**

- L'ordine deciso nel configuratore è quello che si vede sul telefono, **su tutti i
  telefoni**, senza toccare niente su ciascuno
- L'ordine si vede **anche prima che il registro arrivi**: app aperta fuori casa, broker
  irraggiungibile, l'elenco è comunque nell'ordine giusto
- Un riordino **non tocca nessun altro campo** e non fa rinascere nessun dispositivo: gli id
  locali restano, quindi restano gli stati osservati e le schede non tornano «in attesa di
  dati»
- Un riordino **non fa cadere la connessione** e non produce una raffica di
  risottoscrizioni: `collegamento.tentativi` in `/state` non si muove (è il controllo che ha
  chiuso R-6 della 003)
- Un'app ferma alla 1.2.0 che legge il registro nuovo **continua a funzionare**: ignora il
  campo che non conosce e mostra l'elenco come ha sempre fatto

---

## 2. Scope

### Incluso

**Il modello**
- Ogni dispositivo ha una **posizione** nell'elenco, decisa a mano
- La posizione **viaggia nel registro**, additivamente: niente `schema` da alzare, i lettori
  vecchi non se ne accorgono
- La posizione è **persistita in locale** (colonna nel database dell'app, migrazione
  additiva 5→6), così vale anche a broker spento e al primo fotogramma dopo l'avvio
- Elenco **piatto**: una sequenza sola, nessun raggruppamento

**Il configuratore web**
- L'elenco del configuratore **è** l'elenco nell'ordine deciso: è lì che il riordino si vede
  per primo, prima ancora che qualcuno pubblichi
- Le righe dell'elenco si **trascinano** per riordinarle
- Il riordino si salva come tutte le altre modifiche: una pubblicazione del registro, una
  revisione in più
- Un'alternativa al trascinamento per chi non può trascinare (tastiera, TalkBack, schermo
  piccolo): due comandi «su» e «giù» per riga, o equivalente

**L'app Android**
- La vista principale rispetta l'ordine del registro
- Il modulo di registrazione a mano **non** mostra né chiede la posizione

**Dispositivi senza posizione**
- Un dispositivo che il registro non colloca — registrato a mano sul telefono, oppure
  descritto da un registro scritto prima di questa feature — va **in fondo e in ordine
  alfabetico**: chi ha una posizione viene prima, gli altri dopo, come oggi
- Ne discende la proprietà che conta più di tutte: **finché nessuno riordina, l'elenco è
  esattamente quello di adesso**. La feature non cambia niente a chi non la usa

### Escluso (out of scope)

- **Ordinamenti per criterio** — per nome, tipo, acceso/spento, consumo. Niente menù di
  ordinamento: l'ordine è uno solo ed è quello deciso a mano
- **Raggruppamento per stanza**, e con esso il ritorno del campo `stanza` nel registro
- **Riordino dall'app Android.** L'app non scrive nel registro, e non è questa la feature
  che le insegna a farlo
- **Ordini diversi su telefoni diversi.** L'ordine è una proprietà della casa, non del
  telefono: sta nel registro apposta
- Favoriti, dispositivi nascosti, sezioni, ordinamento della vista Impostazioni

---

## 3. User Stories

**US-1** — Come chi apre l'app dieci volte al giorno, voglio che i dispositivi che uso
davvero stiano in cima, per trovarli sotto il pollice senza scorrere e senza cercarli.

**US-2** — Come chi configura la casa, voglio decidere l'ordine **trascinando le righe** nel
configuratore web, per non dovere rinominare i dispositivi allo scopo di spostarli.

**US-3** — Come chi ha due telefoni, voglio che l'ordine sia lo stesso su entrambi, per non
doverlo rifare uno per uno — e per non doverlo rifare di nuovo al telefono successivo.

**US-4** — Come chi rinomina un dispositivo, voglio che **resti dov'è**, perché cambiare il
nome di una presa non è chiedere di riorganizzare l'elenco.

**US-5** — Come chi apre l'app dove il broker non si raggiunge, voglio comunque l'elenco
nell'ordine giusto, perché l'ordine è configurazione, non stato.

**US-6** — Come chi usa TalkBack, voglio poter spostare una riga **senza trascinarla**,
perché il trascinamento è il gesto che una lettura dello schermo non sa fare.

---

## 4. Criteri di accettazione

### US-1 — l'ordine scelto si vede

- [ ] Con un registro che dichiara l'ordine `boiler, lavastoviglie, pompa, …`, la vista
      principale mostra le schede in quell'ordine esatto
- [ ] L'ordine non dipende dall'alfabeto: due dispositivi i cui nomi sono in ordine inverso
      rispetto alla posizione restano nell'ordine dichiarato
- [ ] Chiusa e riaperta l'app (`am force-stop` e riapertura), l'ordine è lo stesso

### US-2 — il riordino nel configuratore

- [ ] Le righe dell'elenco si trascinano e la nuova sequenza si vede subito nella pagina
- [ ] Il salvataggio pubblica **un solo** documento, con `revisione` incrementata di uno
- [ ] Il documento pubblicato è identico al precedente **tranne** la posizione: nessun altro
      campo cambia, nemmeno per i dispositivi che non si sono mossi
- [ ] Ricaricata la pagina, l'ordine è quello salvato e non quello di prima

### US-3 — stesso ordine su ogni telefono

- [ ] Due telefoni che seguono lo stesso registro mostrano la stessa sequenza
- [ ] Un telefono acceso **dopo** il riordino prende l'ordine nuovo alla prima connessione,
      senza interventi

### US-4 — riordinare non è un effetto collaterale

- [ ] Cambiare il nome di un dispositivo **non** ne cambia la posizione
- [ ] Dopo un riordino, gli id locali sono gli stessi: le schede conservano potenza, kWh e
      ultimo stato noto, nessuna torna «in attesa di dati»
- [ ] Dopo quattro riordini consecutivi, `collegamento.tentativi` in `/state` non è
      cambiato: la connessione non è mai caduta
- [ ] Un riordino **non** provoca la risottoscrizione dei topic dei dispositivi spostati

### US-5 — l'ordine senza broker

- [ ] Avvio a freddo con broker irraggiungibile: l'elenco compare nell'ordine giusto
- [ ] Nessun fotogramma in cui l'elenco appare in ordine alfabetico prima di riordinarsi

### US-6 — spostare senza trascinare

- [ ] Ogni riga si può spostare su e giù senza gesto di trascinamento
- [ ] I comandi di spostamento hanno un nome leggibile da una lettura dello schermo, e dopo
      lo spostamento si capisce dove la riga è finita

### Compatibilità

- [ ] Un'app **1.2.0** che legge il registro nuovo mostra i sette dispositivi come sempre,
      senza errori e senza dispositivi saltati
- [ ] L'app nuova che legge un registro **senza** posizioni (quello oggi ritenuto sul broker
      di casa, revisione 8) mostra i sette dispositivi **in ordine alfabetico**: cioè
      esattamente come li mostra oggi la 1.2.0
- [ ] Un registro che colloca **solo alcuni** dispositivi mette quelli in cima nell'ordine
      dichiarato e gli altri sotto, per nome
- [ ] Il registro cancellato (payload vuoto) non tocca l'ordine: l'app tiene quello che ha

---

## 5. Rischi e dipendenze

| # | Rischio | Impatto | Mitigazione |
|---|---|---|---|
| **R-1** | **La risottoscrizione di massa.** `MqttDeviceDriver.track()` risottoscrive ogni dispositivo il cui oggetto `Device` risulta diverso dal precedente. Se la posizione diventa un campo di `Device`, **un riordino li fa cambiare tutti**: sette risottoscrizioni e una raffica di ritenuti riconsegnati, a ogni trascinamento salvato | Alto — è lo stesso nervo che la 003 ha chiuso con R-6 | Decidere in Fase 2 se la posizione entra in `Device` o resta nella sola riga del database; in ogni caso il confronto che decide la risottoscrizione deve guardare **i topic e l'interpretazione dei payload**, non un campo cosmetico. Oggi anche un semplice cambio di nome risottoscrive: è un falso positivo che questa feature può correggere |
| **R-2** | **Il trascinamento sul touch.** Il configuratore è JavaScript senza librerie e si apre anche dal telefono. L'HTML5 drag-and-drop **non funziona sul touch** | Medio — la storia US-2 non si realizza dove serve di più | Pointer events invece di drag events, più i comandi su/giù di US-6 come strada sempre percorribile. Nessuna libreria nuova: il configuratore ha una sola dipendenza (`mqtt.min.js`) ed è un bene che resti così |
| **R-3** | **Due ordini in disaccordo.** Deciso il campo esplicito (§8), il documento ne porta due: la posizione dichiarata e l'ordine in cui le voci stanno nell'array. Un lettore che guardasse l'uno e uno che guardasse l'altro vedrebbero due case diverse | Medio — ricade sul contratto, che è la cosa più costosa da cambiare dopo | Una regola sola in `SCHEMA.md`: **vince il campo, l'ordine dell'array non si legge mai**. Il configuratore pubblica comunque le voci già ordinate, ma per riguardo verso chi legge il JSON a occhio, non perché qualcuno ci si appoggi. Posizioni duplicate, negative o mancanti hanno una regola dichiarata, non un caso |
| **R-4** | **Dispositivi senza posizione.** Registrati a mano, o registro vecchio. Se finiscono «da qualche parte», l'elenco si riordina da solo a ogni avvio | Medio | Regola dichiarata (§8): in fondo, e fra loro **per nome**. È il comportamento di oggi, quindi chi non riordina non si accorge di niente — e non dipende mai dall'ordine di lettura |
| **R-5** | **Due scrittori sul registro.** Riordinare è un'operazione che tocca tutte le righe: se qualcuno pubblica nel frattempo, il riordino le sovrascrive tutte | Basso | Il meccanismo della `revisione` c'è già e il configuratore lo usa; qui basta non introdurre una via che lo scavalchi |
| **R-6** | **Il registro di casa è ritenuto alla revisione 8** e ha attraversato il trasloco dentro `mosquitto.db`. La prima pubblicazione con le posizioni lo sostituisce | Basso | Nessuna migrazione: il documento nuovo è un sovrainsieme. Va però ripubblicato dal configuratore, non a mano |
| **R-7** | **Lo stack di sviluppo va rigenerato.** Il broker di `devops/dev` non ha persistenza e il registro si ripubblica a ogni `up` con `genera-registro-dev.mjs`: quello script deve imparare le posizioni, o le prove girerebbero sul formato vecchio | Basso | Fa parte del lavoro, non è un contorno: è l'ambiente dove la feature si prova |

**Dipendenze**

- Il configuratore viaggia **dentro un'immagine** costruita sul PC e caricata sul Pi: una
  modifica alla pagina richiede build e `./devops/deploy.sh`, non un `rsync`
- L'app sale a **1.3.0** e prende una migrazione di database 5→6
- Le prove si fanno contro `devops/dev`, mai contro il Pi: da lì nessun comando raggiunge
  una presa vera

---

## 6. Stima effort

Un solo sviluppatore; le giornate sono giornate di lavoro effettivo, non di calendario.

| Area | Giorni | Cosa |
|---|---:|---|
| Contratto e documentazione | 0,25 | La regola della posizione in `SCHEMA.md`, la nota in `README.md` |
| Configuratore web | 1,0 | Riordino a trascinamento con pointer events, comandi su/giù, pubblicazione, `genera-registro-dev.mjs` |
| App Android | 0,75 | Colonna e migrazione 5→6, lettura della posizione dal registro, `ORDER BY` nel DAO, e il punto delicato: che il riordino non risottoscriva |
| Test | 0,5 | Unitari sul lettore del registro e sul piano; i casi limite dei dispositivi senza posizione |
| Verifica sul campo | 0,5 | Riordino vero contro `devops/dev`, due client, il controllo su `collegamento.tentativi` |
| **Totale** | **~3,0** | |

La voce che può crescere è l'app, ed è tutta dentro R-1: se il confronto che decide le
risottoscrizioni va rifatto, è un pezzo di driver che va toccato con cautela e provato per
davvero.

---

## 7. Milestones

1. **M-1 — Il contratto.** Decidere indice-dell'array *o* campo esplicito (R-3) e scriverlo
   in `SCHEMA.md`, insieme alla regola per i dispositivi senza posizione (R-4). Prima di
   qualunque riga di codice: è ciò che i due programmi devono condividere
2. **M-2 — L'app legge.** Colonna, migrazione 5→6, lettura della posizione, `ORDER BY`.
   Provata con un registro scritto a mano: l'app onora l'ordine prima ancora che il
   configuratore sappia produrlo
3. **M-3 — Il riordino non risottoscrive.** Il punto di R-1, isolato e provato a parte
4. **M-4 — Il configuratore riordina.** Trascinamento, comandi su/giù, pubblicazione
5. **M-5 — L'ambiente di sviluppo.** `genera-registro-dev.mjs` produce le posizioni
6. **M-6 — Verifica sul campo** contro `devops/dev`: riordino, secondo client, connessione
   che non cade, app 1.2.0 che regge il documento nuovo
7. **M-7 — In casa.** Build delle immagini, `deploy.sh`, ripubblicazione del registro di
   casa dal configuratore

---

## 8. Decisioni prese

Le tre domande aperte della 1.0, sciolte il **13 settembre 2026**.

**D-1 — La posizione è un campo esplicito, non l'indice dell'array.**
Il campo si legge senza dipendere da come un intermediario ha trattato la lista, si può
lasciare vuoto per un dispositivo solo, e soprattutto **si vede**: un registro letto a occhio
dice dove va ogni dispositivo, invece di dirlo implicitamente con l'ordine delle righe. Il
prezzo è R-3, e si paga con una riga di contratto: l'ordine dell'array non si legge mai.

**D-2 — I dispositivi senza posizione si ordinano per nome.**
Cioè come oggi. Era l'alternativa conservativa fra le due, e ha una conseguenza che vale più
della coerenza interna: l'app nuova davanti al registro vecchio si comporta **esattamente**
come l'app vecchia. Nessun cambio di comportamento sotto i piedi di chi usa l'app a mano,
nessun riordino a sorpresa al primo avvio dopo l'aggiornamento.

> La Fase 2 ha aggiunto un corollario: `room`, che oggi partecipa all'ordinamento dell'app,
> **esce** dall'`ORDER BY`. Non sposta nessun dispositivo esistente — la stanza è vuota per
> tutto ciò che viene dal registro — e rende l'elenco piatto davvero, come dice lo scope.
> Vedi [Fase 2, §8](phase-2-analysis.md).

**D-3 — L'ordine vale prima di tutto nel configuratore.**
L'elenco del configuratore non è una lista di righe da cui si pubblica un documento: è la
casa vista dall'alto, ed è lì che il riordino dev'essere immediatamente leggibile. Da questo
discende che il configuratore ordina il proprio elenco con la stessa regola dell'app —
posizione, poi nome — e che le due implementazioni della regola vanno provate sullo stesso
insieme di casi.