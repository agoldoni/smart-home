# Vista principale in sola lettura — Implementation Plan

**Stato:** Implementato — 9 test su 10 verificati sul telefono, vedi *Stato di avanzamento*
**Autore:** Alberto Goldoni
**Data:** 13 settembre 2026
**Versione:** 1.0
**Feature:** `003-vista-sola-lettura` · [Fase 1](phase-1-requirements.md) · [Fase 2](phase-2-analysis.md)

---

## 0. Stato di avanzamento

**Aggiornato: 13 settembre 2026, sera.**

Task **T-01 → T-11 chiusi**. `./gradlew testDebugUnitTest assembleDebug lintDebug` passa:
**71 test, 0 falliti** (erano 66; i cinque nuovi sono `DeviceCommandableTest`), lint senza
segnalazioni nuove — le 12 che restano sono versioni di dipendenze e due stringhe
inutilizzate della 002. Versione **1.2.0 (versionCode 10)**, installata sul telefono sopra
la 1.1.0.

### L'ambiente di prova

Le verifiche **non** sono state fatte contro il broker di casa: sul PC è stato acceso un
**broker usa e getta** (stesse credenziali, nessun ponte Tuya attaccato) e sopra ci sono
state messe **sette prese finte** che rispondono ai comandi sui topic veri del registro.
Un tocco accendeva una variabile, non un boiler. Smontato tutto a fine prove.

### Verificato sul telefono, e come

| # | Esito | Come |
|---|---|---|
| TC-01 | ✅ | Vista bloccata, dieci tocchi sugli interruttori: contatore fermo a `0 inviati` **e zero messaggi sul broker**, letti da un `mosquitto_sub` in ascolto — una prova indipendente dal contatore |
| TC-02 | ✅ | Tutti e sette gli interruttori `checked="false"` dopo i dieci tocchi: nessuno si è mosso, e nel dump risultano `enabled="false"` |
| TC-03 | ✅ | `am force-stop`, processo confermato morto, riapertura: ancora bloccata |
| TC-04 | ✅ | Avvio a freddo con 15 tocchi sparati dal primo istante: contatore a 0, nessun messaggio, interruttori nati già disabilitati. **R-2 chiuso** |
| TC-05 | ✅ | `adb install -r` sopra la 1.1.0 con dati veri: i sette dispositivi ci sono tutti e il lucchetto parte **aperto** |
| TC-06 | ✅ | Sbloccato con un tocco, comando inviato: la presa finta ha risposto `boiler -> ON (772 W)`, la scheda è diventata verde, contatore a `1 inviati` |
| TC-07 | ✅ | `DeviceCommandableTest`, cinque casi: le quattro combinazioni più la raggiungibilità sconosciuta |
| TC-08 | ✅ | Guardata **nel caso peggiore**: "segui il registro" spento per far comparire il "+", quindi `+ 🔒 ⚙` più l'insetto rosso e il titolo su due righe. Ci stanno comodi. **R-4 chiuso** |
| **R-6** | ✅ | Quattro cambi di lucchetto e `collegamento.tentativi` in `/state` è rimasto **1**, connessione mai caduta. Era il rischio serio della feature |
| T-08 | ✅ | `/state` riporta `view: {"locked": true\|false}`, seguito dal vivo durante le prove |

### Da finire

1. **TC-09, TalkBack.** Non verificato: nel dump di uiautomator il lucchetto espone gli
   stessi attributi vuoti dell'**ingranaggio**, che è codice preesistente e funzionante —
   quindi non c'è regressione, ma nemmeno una verifica positiva. Serve accendere TalkBack
   sul telefono e ascoltare
2. **TC-10, release con R8.** A metà, e per un motivo esterno: `assembleRelease` arriva in
   fondo a **`minifyReleaseWithR8`** e si ferma solo su `packageRelease`, perché le
   variabili di firma non sono definite in questo ambiente. Quello che si poteva controllare
   senza firmare è controllato: nel dex minificato **sopravvivono sia la chiave `bloccata`
   sia il nome del file di preferenze `vista`**. Manca il giro sull'APK firmato

---

## 1. Executive Summary

La vista principale dell'app è una colonna di interruttori larghi quanto lo schermo, e ogni
tocco accende o spegne qualcosa di vero in casa. Oggi non c'è modo di **guardarla** senza
poterla usare: aperta, è sempre armata.

Questa feature aggiunge un **lucchetto nella barra in alto**. Chiuso, gli interruttori e i
cursori diventano inerti: si continua a vedere stato e consumi, ma nessun comando parte. Lo
stato del lucchetto sopravvive alla chiusura dell'app, e si apre e chiude con un tocco solo.

**Stima: 1,45 giorni/uomo.** Tutto dentro l'app: nessuna modifica al broker, al ponte, al
configuratore o al registro condiviso, e nessuna migrazione del database.

---

## 2. Obiettivo e motivazione

- **Problema che risolve:** gli interruttori sono messi apposta perché cadano sotto il
  pollice, e funziona — è anche il motivo per cui un tocco distratto accende il boiler.
  Che il problema sia reale lo dice il codice: in barra c'è un contatore dei comandi
  inviati, e il commento accanto spiega che serve a rispondere a *«quel dispositivo che si
  è mosso, l'ho mosso io?»*. Quel contatore è la diagnosi dopo il fatto. Questo è il modo
  di non arrivarci.

- **Metriche di successo:**
  - [ ] **Un tocco a lucchetto chiuso non produce nessun messaggio MQTT**: il contatore dei
        comandi inviati resta fermo
  - [ ] Lo stato sopravvive alla morte del processo: riaperta, la vista è com'era
  - [ ] Si capisce dal colpo d'occhio in quale modalità si è, senza toccare niente
  - [ ] **Zero regressioni a lucchetto aperto**: l'app si comporta esattamente come la 1.1.0

- **Legame con gli obiettivi del progetto:** il README descrive un'app che si rifiuta di
  dire bugie sullo stato di casa — un dispositivo irraggiungibile non mostra «Acceso», e il
  suo interruttore non si muove. Questa feature applica la stessa regola a un secondo
  motivo: se un comando non deve partire, il controllo non deve muoversi.

---

## 3. Scope

### Incluso

**Comportamento**
- Due modalità della sola vista principale: **aperta** (come oggi) e **chiusa**
- A lucchetto chiuso sono inerti i **comandi ai dispositivi**: interruttore di accensione e
  cursore del livello. Nient'altro
- Stato **persistito fra un lancio e l'altro**, su un file DataStore proprio
- Prima installazione e aggiornamento dalla 1.1.0: si parte **aperti**

**Interfaccia**
- Icona in barra, **fra il "+" e l'ingranaggio**
- Un tocco chiude, un tocco apre. Nessuna conferma, nessuna pressione lunga
- I controlli bloccati appaiono disabilitati **prima** del tocco
- I due stati si distinguono per **forma**, non per tinta
- Etichette per TalkBack

**Diagnostica**
- La modalità corrente in `/state`

### Escluso (out of scope)

- **Protezioni più forti di un tocco** — niente PIN, pressione lunga o biometria: è una
  difesa dal tocco distratto, non dalle persone. Per quelle c'è il blocco schermo
- **Blocco per singolo dispositivo** — il blocco è della vista, non della presa
- **Blocco della modifica** — la scheda si apre lo stesso, il "+" resta, le Impostazioni
  pure. Si blocca ciò che ha effetto fisico, e basta
- **Sincronizzazione fra telefoni** — è una preferenza locale e non entra nel registro
  condiviso: due telefoni possono stare in modalità diverse, ed è giusto così
- **Qualunque effetto sulle prese vere** — restano comandabili dagli altri telefoni, dal
  ponte e dall'app Tuya. Questa feature non tocca il broker

### Decisioni aperte

Nessuna. Le quattro emerse in Fase 1 e 2 sono state chiuse in revisione il 13 settembre 2026:

| # | Decisione | Esito |
|---|---|---|
| 1 | Il blocco vale anche fuori dalla vista principale? | **No.** E l'analisi ha mostrato che non lascia scoperto niente: `DeviceCommand` esiste in tre soli file e la schermata di modifica non manda comandi |
| 2 | L'interruttore va ripetuto in Impostazioni? | **No.** Vive solo in barra: una seconda copia sarebbe una seconda verità da tenere allineata |
| 3 | Come si chiama nel codice | **`locked`** — `ViewLockStore`, `locked: Boolean`, `toggleLock()`, stringhe `view_locked_*` |
| 4 | Le icone | **Due drawable disegnati**, aperto e chiuso. Niente `material-icons-extended` |

---

## 4. User Stories e criteri di accettazione

### US-001 · Guardare senza toccare
**Priorità:** Must Have

Come chi apre l'app con le mani occupate, voglio mettere la vista in sola lettura, per
leggere consumi e stato delle prese senza rischiare di spegnere il boiler con il pollice.

**Criteri di accettazione:**
- [ ] A lucchetto chiuso, toccare un interruttore **non invia nessun messaggio**: il
      contatore dei comandi inviati resta fermo
- [ ] L'interruttore **non cambia posizione** al tocco, nemmeno per un istante
- [ ] Il cursore del livello non si sposta e non invia
- [ ] Lo stato continua ad aggiornarsi: schede vive, consumi che si muovono, banner di
      connessione funzionante

### US-002 · Passare il telefono a qualcuno
**Priorità:** Must Have

Come chi fa vedere l'app a un ospite o a un figlio, voglio che gli interruttori siano inerti
finché non li riabilito io, per mostrare com'è fatta casa senza che succeda niente.

**Criteri di accettazione:**
- [ ] Il blocco vale per tutti i dispositivi, compresi quelli aggiunti dopo
- [ ] Nessun percorso alternativo comanda un dispositivo dalla vista principale mentre è
      bloccata

### US-003 · Ritrovarla come l'ho lasciata
**Priorità:** Must Have

Come chi tiene l'app normalmente bloccata, voglio che lo stato sopravviva alla chiusura, per
non dover rimettere il blocco a ogni apertura.

**Criteri di accettazione:**
- [ ] Chiusa bloccata e riaperta: ancora bloccata. Chiusa aperta: ancora aperta
- [ ] Sopravvive alla **morte del processo**, non solo al ritorno da background
- [ ] **Durante il caricamento della preferenza la vista non è comandabile**: un tocco nei
      primi frame non passa perché il valore non è ancora arrivato
- [ ] All'aggiornamento dalla 1.1.0 si parte **aperti**

### US-004 · Capire perché non risponde
**Priorità:** Must Have

Come chi tocca un interruttore che non si muove, voglio vedere dalla barra che la vista è
bloccata, per non pensare che sia caduto il collegamento al broker.

**Criteri di accettazione:**
- [ ] I due stati si distinguono **senza bisogno di colore**: forma diversa, non solo tinta
- [ ] I controlli bloccati appaiono disabilitati prima del tocco
- [ ] TalkBack annuncia lo stato corrente e cosa fa il tocco
- [ ] L'icona sta fra "+" e ingranaggio, con area di tocco di almeno 48dp

### US-005 · Tornare operativo subito
**Priorità:** Must Have

Come chi deve accendere davvero qualcosa, voglio sbloccare con un tocco solo e comandare
subito, per non pagare il blocco ogni volta che mi serve l'app per quello che fa.

**Criteri di accettazione:**
- [ ] Un tocco solo riapre, da qualunque punto dell'elenco
- [ ] Subito dopo, un comando parte e arriva: **nessuna riconnessione** nel mezzo —
      `clientIdInUse` in `/state` non cambia
- [ ] `/state` riporta la modalità corrente

---

## 5. Architettura tecnica

### Componenti coinvolti

```
  DataStore "vista"
        │
        ▼
  ViewLockStore.locked : Flow<Boolean>          [nuovo]
        │
        ▼
  DeviceListViewModel
     combine(devices, states, connection, commandsSent, locked)
        │                                    └── il quinto flusso chiude R-2:
        ▼                                        finché non emette, loading = true
     DeviceListUiState(locked, loading)          e l'elenco non esiste
        │
        ├──────────────► TopAppBar
        │                  [+] [🔒/🔓] [⚙]   ── tap ──► toggleLock()
        │
        └──────────────► DeviceCard(locked)
                           Switch(enabled = !unreachable && !locked)
                           Slider(enabled = !unreachable && !locked)
                                     │
                                     ╳  nessun DeviceCommand parte
                                     ▼
                           MqttDeviceDriver — non sa che la modalità esista
```

Il blocco sta **sopra** il driver. Il driver resta collegato, le sottoscrizioni restano
vive, lo stato continua ad arrivare: bloccata, l'app ascolta esattamente come prima e
smette solo di parlare.

### Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| Room (`smart-home.db`) | **Nessuna** | La preferenza non è un dato di dominio: niente campo su `Device`, niente migrazione 5→6, niente `schemas/6.json` |
| DataStore `vista` | Nuovo file | Una chiave booleana, `locked`, predefinita `false` |
| Registro condiviso | **Nessuna** | Il documento resta a `schema: 1`. Il configuratore web non cambia |

### Nuove API o endpoint

| Metodo | Path | Descrizione | Auth |
|---|---|---|---|
| GET | `/state` | Chiave nuova con la modalità corrente della vista. **Additiva**: nessun consumatore esistente si rompe | Come oggi |

### Breaking changes

Nessuno. La firma di `DeviceListViewModel` cambia, ma l'unico punto di costruzione è
`AppViewModelFactory` e nessun test lo istanzia.

---

## 6. Piano di implementazione

| ID | Task | Area | Stima (gg) | Dipende da |
|---|---|---|---|---|
| T-01 | `ViewLockStore`: DataStore `vista`, `Flow<Boolean>`, setter `suspend`. Ricalca `RegistryStore` | Dati | 0,20 | — |
| T-02 | `AppContainer` e `AppViewModelFactory`: lo store arriva a `DeviceListViewModel` | Dati | 0,05 | T-01 |
| T-03 | `locked` nel `combine` e in `DeviceListUiState`; `toggleLock()` | FE | 0,15 | T-02 |
| T-04 | La regola di comandabilità estratta in funzione pura, e il presidio in `send()` | FE | 0,10 | T-03 |
| T-05 | I due drawable: lucchetto aperto e chiuso, tratto coerente con `ic_device_*` | FE | 0,15 | — |
| T-06 | L'azione in barra fra "+" e ingranaggio, stringhe `view_locked_*`, TalkBack | FE | 0,20 | T-03, T-05 |
| T-07 | `enabled` esteso su `Switch` (riga 455) e `Slider` (riga 472) | FE | 0,05 | T-04 |
| T-08 | La modalità in `/state` | FE | 0,05 | T-03 |
| T-09 | Test JVM sulla regola pura, quattro combinazioni | Test | 0,10 | T-04 |
| T-10 | Verifiche sul telefono, TC-01…TC-09 | Test | 0,25 | tutti |
| T-11 | README, `versionCode` 10, `versionName` 1.2.0 | Doc | 0,15 | — |

**Stima totale:** 1,45 giorni/uomo
**Breakdown:** Dati 0,25gg · FE 0,70gg · Test 0,35gg · Doc 0,15gg

> La Fase 1 stimava 1,25. La differenza sono i due drawable (T-05) e l'estrazione della
> regola pura (T-04), che in Fase 1 non erano voci separate. Preferisco il numero più alto
> scritto adesso a uno più basso da spiegare dopo.

---

## 7. Piano di test

**Strategia generale.** Il progetto prova in JVM ciò che è logica pura — parser del
registro, payload MQTT, classificazione degli errori — e porta il resto sul telefono, con
test di campo annotati. Non c'è Robolectric, non c'è Compose UI test, non esiste
`androidTest`, e non vale la pena aggiungerli per una feature da un giorno.

Da qui una scelta di progettazione: la regola di comandabilità viene **estratta in una
funzione pura** così che l'unica cosa provabile in JVM lo sia davvero, e il resto sia
onestamente un test di campo invece di un test finto.

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Campo | **Il contatore fermo.** Bloccata la vista, dieci tocchi sugli interruttori: il contatore dei comandi in barra non si muove di uno. È la sentinella della feature | Alta |
| TC-02 | Campo | **L'interruttore non scatta** e non torna indietro. È R-1 | Alta |
| TC-03 | Campo | **Persistenza vera:** bloccata, `adb shell am force-stop`, riaperta → ancora bloccata | Alta |
| TC-04 | Campo | **La finestra all'avvio:** app bloccata, avvio a freddo, tocco nel primo istante utile → nessun comando parte. È R-2 | Alta |
| TC-05 | Campo | **Aggiornamento dalla 1.1.0** su un telefono con dati veri: si parte aperti, i dispositivi ci sono tutti | Alta |
| TC-06 | Campo | **Sblocco e comando:** un tocco, poi un comando che arriva alla presa, e `clientIdInUse` in `/state` **non cambia** (R-6) | Alta |
| TC-07 | Unit | La regola pura nelle quattro combinazioni `locked` × `unreachable`, compreso «bloccato **e** irraggiungibile» | Alta |
| TC-08 | Campo | **La barra guardata davvero**, coi nomi lunghi delle prese e l'insetto di debug acceso: tre icone più il badge ci stanno? (R-4) | Media |
| TC-09 | Campo | **TalkBack**: il lucchetto annuncia stato e azione; un interruttore bloccato si annuncia disabilitato | Media |
| TC-10 | Campo | **Build release con R8**: la preferenza si legge e si scrive anche offuscata | Media |

### Definition of Done

- [ ] `./gradlew testDebugUnitTest assembleDebug lintDebug` verde — la linea di partenza è
      **66 test, 0 falliti**, lint pulito
- [ ] TC-01 eseguito e annotato: è la metrica di successo numero uno
- [ ] TC-03 fatto con il processo **ucciso**, non con l'app mandata in background
- [ ] TC-04 eseguito: R-2 non si dà per risolto solo perché il `combine` dovrebbe bastare
- [ ] TC-05 eseguito su un telefono che aveva davvero dei dati, **non** su un'installazione
      pulita
- [ ] TC-08 eseguito guardando lo schermo: sulla 002 è esattamente il controllo che è
      saltato, ed è saltato perché sembrava il meno importante
- [ ] `/state` riporta la modalità e non segnala anomalie nuove
- [ ] `README.md` dice cosa blocca e **cosa non blocca**
- [ ] `versionCode` 10 e `versionName` 1.2.0 in `app/build.gradle.kts`

> Nessuna soglia di coverage e nessuna CI: il progetto non ha né l'una né l'altra, e
> inventarne una qui sarebbe una riga di spunta che nessuno verifica. Il criterio è che i
> test elencati esistano e passino.

---

## 8. Rischi e mitigazioni

| # | Rischio | Probabilità | Impatto | Mitigazione |
|---|---|---|---|---|
| R-1 | **L'interruttore scatta e torna indietro.** Filtrare il comando invece di disabilitare il controllo fa sembrare un guasto quello che è un blocco | Bassa | Medio | Il meccanismo esiste già: `enabled = !unreachable` su `Switch` e `Slider`, da estendere con `&& !locked`. TC-02 |
| R-2 | **La finestra fra il primo frame e il valore letto.** La preferenza arriva asincrona: per qualche frame la vista potrebbe essere armata quando doveva essere bloccata | Media | Alto | Il flusso entra nello **stesso `combine`** che produce `uiState`: finché non emette, `loading` resta vero e l'elenco non è disegnato. TC-04 lo verifica invece di darlo per fatto |
| R-3 | **Scambiare il blocco per sicurezza.** Non protegge niente: le prese restano comandabili da ogni altro telefono, dal ponte e dall'app Tuya | Media | Basso | Detto nello scope e nel README |
| R-4 | **Affollamento della barra:** tre icone più l'insetto di debug, su un titolo che ha già due righe di servizio | Media | Basso | TC-08, che si chiude solo guardando uno schermo vero |
| R-6 | **La preferenza nel posto sbagliato spegne il broker.** Dentro `BrokerSettings`, ogni tocco del lucchetto chiuderebbe e riaprirebbe il collegamento MQTT: il driver osserva quel flusso, è una `data class`, e basta un campo diverso perché `distinctUntilChanged` lasci passare | Bassa | **Alto** | File DataStore proprio (`vista`), mai `BrokerSettingsStore` — è la stessa ragione per cui `RegistryStore` esiste separato, e lo dice il commento in testa a quel file. TC-06 lo verifica su `clientIdInUse` |
| R-7 | **Il presidio doppio che mente.** Solo la guardia in `send()` lascia i controlli vivi (→ R-1); solo l'`enabled` lascia scoperta una via di comando aggiunta domani | Media | Medio | Entrambi, con ruoli dichiarati nel commento: l'`enabled` è **l'interfaccia**, la guardia in `send()` è **la rete**. Non è ridondanza |

---

## 9. Rollout e rollback

**Strategia di rilascio:** deploy diretto, un APK. Niente stack da toccare — broker, ponte e
configuratore non sanno che questa feature esiste, e un telefono aggiornato convive senza
attriti con uno alla 1.1.0.

**Nessun feature flag, perché il flag è la feature.** Il lucchetto nasce aperto: installata
la 1.2.0 e non toccato niente, l'app si comporta **esattamente** come la 1.1.0. Non serve un
interruttore per spegnere un interruttore.

**Piano di rollback**

| Se va storto | Cosa fare | Effetto |
|---|---|---|
| Il blocco si comporta male | Aprire il lucchetto | Si torna al comportamento della 1.1.0, subito e senza reinstallare |
| La 1.2.0 va rimessa alla 1.1.0 | Installare l'APK precedente **senza disinstallare** | **Nessuna perdita di dati: non c'è migrazione Room.** Il file DataStore `vista` resta sul disco, la 1.1.0 lo ignora, e una futura 1.2.0 lo ritrova com'era |

Vale la pena fermarsi un attimo sulla seconda riga, perché è il contrario di quanto valeva
per la feature precedente. La 1.1.0 **non** si poteva rimettere alla 1.0.7: la migrazione
Room 4→5 non è reversibile e l'APK vecchio non apre un database di schema 5. Qui il
downgrade è pulito, ed è una conseguenza diretta della scelta di tenere la preferenza fuori
dal dominio.

---

## 10. Checklist di approvazione

Progetto di una persona sola: la revisione è una rilettura a distanza di un giorno, non il
passaggio a qualcun altro. Le righe restano perché le domande sono le stesse.

| Revisione | Cosa chiede | Stato | Data |
|---|---|---|---|
| Revisione tecnica | Il `combine` basta davvero a chiudere R-2, o serve uno stato esplicito «non ancora letto»? | ⏳ In attesa | — |
| Revisione di prodotto | Cinque storie sono quello che serve, e il blocco che **non** blocca la modifica è la scelta giusta? | ⏳ In attesa | — |
| Stima approvata | 1,45 giorni sono accettabili, sapendo che 0,35 sono di sole verifiche sul telefono? | ⏳ In attesa | — |
| Rischi accettati | R-3 (non è una misura di sicurezza) e R-4 (barra affollata) si accettano? | ⏳ In attesa | — |
| Data di inizio confermata | — | ⏳ In attesa | — |

---

## Domande aperte

Nessuna. Le quattro decisioni emerse durante le Fasi 1 e 2 sono state chiuse in revisione il
13 settembre 2026 e sono riportate, con il loro esito, nella sezione 3.

---

*Documento generato con la skill `claude-code-feature`.*
