# Fase 1 — Requisiti: configuratore web e registro condiviso dei dispositivi

**Feature:** `002-configuratore-web`
**Data:** 12 settembre 2026
**Stack:** pagine statiche servite da nginx + MQTT su WebSocket, broker Mosquitto, app Android Compose
**Tipo di progetto:** componenti di casa che si parlano solo via MQTT — nessun server applicativo, nessun database nuovo
**Team:** una persona

---

## 1. Obiettivo e motivazione

Registrare un dispositivo è oggi un lavoro che si rifà **per intero su ogni telefono**.
`Device` ha 21 campi; una presa del ponte ne vuole otto compilati a mano — stato, comando,
disponibilità, energia e i quattro campi JSON — e le prese sono sette.

| Quanto costa oggi | Conto |
|---|---|
| Campi non banali per una presa del ponte | 8 (4 topic + 4 chiavi JSON) |
| Prese da registrare | 7 |
| Campi digitati a mano, un telefono | ~56 |
| Campi digitati a mano, due telefoni | ~112, la metà dei quali topic |

E un topic sbagliato **non dà nessun errore**. Il README lo dice già a proposito dei test:
«un topic che non combacia non produce nessun errore, solo un dispositivo perennemente "in
attesa di dati"». Ripetere a mano centoventi stringhe di cui metà falliscono in silenzio è
il modo peggiore di distribuire una configurazione.

Il resto del progetto si è già dato la risposta. I messaggi ritenuti sono il motivo per cui
l'app trova subito lo stato delle prese quando riapre: il broker tiene l'ultimo valore e lo
consegna a chi si iscrive, senza che nessuno debba essere acceso nel frattempo. **La stessa
meccanica porta la configurazione.** Si scrive una volta, resta sul broker, e ogni app che
si collega — adesso o fra tre settimane, su un telefono che ancora non esiste — la riceve
appena si iscrive.

Da qui la forma della feature: una pagina web che scrive il registro dei dispositivi in un
messaggio ritenuto, e app che lo leggono. Non serve un server applicativo, non serve un
database: **il broker è già il database**, ed è l'unico pezzo che deve essere acceso.

### Decisioni prese prima di scrivere questo documento

| Decisione | Scelta | Perché |
|---|---|---|
| Stack | Pagine statiche + MQTT su WebSocket | Nessun processo server, nessun DB, nessun linguaggio in più. Un container `nginx:alpine` e due righe in `mosquitto.conf` |
| Fonte di verità | La web app, sola | Un dispositivo esiste se è nel registro. Niente regole di merge, niente conflitti fra telefoni |
| Accesso | Credenziali del broker | L'autorizzazione è quella che c'è già. Password sbagliata, il broker chiude: non c'è niente da mostrare e niente da pubblicare |

---

## 2. Scope

### Incluso

**Sul broker e nello stack**

- Un listener **websockets** su Mosquitto (porta 9001), autenticato come il 1883: stesso
  `password_file`, `allow_anonymous false`
- Un servizio `configuratore` in `bridge/compose.yml`: `nginx:alpine` che serve una
  cartella statica. Nessuna build, nessun processo applicativo
- Il client MQTT JavaScript **dentro l'immagine**, non da CDN: lo stack deve funzionare con
  la linea di casa giù, che è esattamente quando si va a guardare perché le prese non
  rispondono

**Il registro**

- Vive in **un solo messaggio ritenuto** su `casa/registro/dispositivi`, QoS 1:

  > ✅ **Precisato il 12/09/2026:** il topic è `<prefisso>/registro/dispositivi`, con il
  > prefisso configurabile (predefinito `casa`). Tenere il registro sotto lo stesso prefisso
  > dei dispositivi che descrive fa del prefisso il confine di un'istanza: una seconda casa
  > nasce cambiando una stringa. Vedi la decisione 7 del documento di implementazione.

  ```json
  {"schema": 1, "revisione": 12, "aggiornato": "2026-09-12T21:04:33+02:00",
   "dispositivi": [ { "uuid": "…", "nome": "frigorifero", "…": "…" } ]}
  ```

- Un documento unico e non un topic per dispositivo. La ragione è la **cancellazione**:
  con un topic per dispositivo, una presa cancellata mentre un telefono è spento non viene
  mai più nominata, e su quel telefono resta per sempre. Il documento unico dice sempre
  l'insieme completo, quindi "non c'è più" si legge dalla sua assenza. Costa la
  riscrittura di tutto a ogni modifica: 7 dispositivi sono ~5 KB, cento ne farebbero 60 —
  numeri che per MQTT non esistono
- `revisione` cresce di uno a ogni pubblicazione, e serve a **accorgersi** di una modifica
  fatta da un'altra finestra

**La web app**

- Elenco dei dispositivi, creazione, modifica, duplicazione, eliminazione
- Gli stessi campi del modulo Android, con le stesse regole: wildcard `+` e `#` ammesse nel
  topic di stato, **vietate** nel topic di comando (il broker rifiuterebbe il messaggio),
  campi obbligatori che cambiano col tipo
- **Modelli precompilati**, che è il punto in cui si recuperano le ore: dato un nome,
  riempiono da soli tutti i topic secondo la convenzione
  - *presa del ponte* → `casa/<nome>/stato`, `/comando`, `/disponibilita`, `/energia` con
    `stato`, `potenza_w`, `kwh_oggi`, `kwh_mese`
  - *Tasmota* → `stat|cmnd|tele/<nome>/POWER`, LWT con `Online`/`Offline`
  - *Zigbee2MQTT* → `zigbee2mqtt/<nome>` + `/set/state`, `/availability`, `levelMax` 254
- Collegamento con indirizzo del broker, utente e password. La password sta **in memoria per
  la durata della scheda**, non in `localStorage`
- **Client id casuale a ogni apertura.** Il README documenta già cosa succede altrimenti:
  due client con lo stesso id si buttano fuori a vicenda, `session taken over`
- Avviso di conflitto: se la revisione ricevuta non è più quella caricata, la pagina lo dice
  e non sovrascrive finché non si ricarica

**L'app Android**

- Sottoscrizione a `casa/registro/dispositivi` appena il broker è collegato, indipendente
  dai dispositivi seguiti (oggi le sottoscrizioni nascono solo da loro)
- Applicazione al database locale **per `uuid`**: aggiunti, modificati, rimossi
- `uuid` nuovo su `Device` e migrazione Room **4 → 5**, con **adozione** dei dispositivi già
  registrati: alla prima sincronizzazione un dispositivo locale che ha lo stesso topic di
  stato di uno del registro ne prende l'uuid invece di essere cancellato e riaggiunto
- **La prima applicazione chiede conferma**, e mostra il conto — *«il registro porta 7
  dispositivi; 2 dei tuoi non ci sono e verranno rimossi»*. Dopo, il registro si applica da
  solo
- Modulo di registrazione in **sola lettura** finché l'app segue un registro, con
  l'indicazione di dove si modifica. Finché un registro non è mai arrivato l'app si comporta
  come oggi: chi non accende il configuratore non resta senza modo di aggiungere un
  dispositivo
- Interruttore **"segui il registro"** in Impostazioni, con revisione e ora dell'ultimo
  ricevuto. Spegnerlo restituisce il modulo scrivibile
- Stato del registro dentro `/state` della API di diagnostica, anomalie comprese (registro
  mai ricevuto, registro rifiutato, revisione più vecchia di quella applicata)

**Documentazione**

- `README.md`: come cambia la registrazione dei dispositivi
- `bridge/README.md`: il nuovo servizio, il listener websockets, i topic del registro
- Lo schema del documento, con i campi e la regola sulle versioni

### Escluso (out of scope)

- **La configurazione del ponte.** `dispositivi.yaml` — id Tuya, chiavi locali, dp, scale —
  resta dov'è e si modifica a mano. Sono due cose diverse: il registro dice alle *app* cosa
  mostrare, `dispositivi.yaml` dice al *ponte* con chi parlare, e contiene le chiavi, che
  non devono passare da una pagina web
- **Le impostazioni del broker.** Indirizzo, porta, credenziali, client id restano per
  telefono: sono ciò che serve per *ricevere* il registro, quindi non possono arrivarci
  dentro
- **Scoperta dei topic attivi** — la pagina iscritta a `casa/#` che propone «qui pubblica
  qualcosa e nessun dispositivo lo mostra». È il seguito naturale e costa poco, ma non è
  necessario a far funzionare il registro
- **Proposte dal ponte**: il ponte che pubblica da sé la configurazione delle prese che
  traduce, pronte da aggiungere con un tocco. Stessa ragione
- **Bidirezionale**: l'app non scrive sul registro. È la decisione presa, non una mancanza
- **Storico e rollback** del registro: una revisione sola, quella corrente
- **MQTT discovery di Home Assistant**
- **HTTPS e TLS**: la pagina è servita in chiaro sulla LAN, come il broker sul 1883
- **Utenti e permessi**: un solo utente del broker, come oggi
- **Raggruppamento per stanza**: resta un'etichetta che non ordina niente, come oggi

---

## 3. User stories

1. **Come chi ha appena montato una presa** voglio registrarla una volta sola da un browser
   **per** non ridigitare gli stessi otto topic su ogni telefono di casa.

2. **Come chi registra le sette prese del ponte** voglio un modello che compili i topic dal
   nome **per** non scrivere a mano cinquantasei stringhe in cui un carattere sbagliato non
   dà errore ma un dispositivo che non riceve niente.

3. **Come chi installa l'app su un telefono nuovo** voglio trovarci dentro i dispositivi di
   casa appena collego il broker **per** non rifare la registrazione da capo.

4. **Come chi ha rinominato o tolto una presa** voglio che la modifica arrivi a tutti i
   telefoni, anche a quelli spenti in quel momento **per** non ritrovarmi una scheda che
   comanda un topic che non esiste più.

5. **Come chi tiene in piedi il sistema** voglio che un registro incomprensibile non
   cancelli i dispositivi che funzionano **per** non perdere la configurazione di casa per
   un errore di battitura o una versione più nuova del formato.

6. **Come chi apre il configuratore da fuori casa** voglio che chieda le credenziali del
   broker **per** sapere che chi non le ha non può riscrivere la configurazione di casa.

---

## 4. Criteri di accettazione

### Story 1 — registrare da browser

- [ ] La pagina si collega al broker via WebSocket con utente e password, e con credenziali
      sbagliate mostra il rifiuto del broker invece di una pagina vuota
- [ ] Un dispositivo creato compare nel messaggio ritenuto su `casa/registro/dispositivi`
      entro un secondo, con `revisione` incrementata
- [ ] `mosquitto_sub -t casa/registro/dispositivi -C 1` restituisce subito il documento
      completo, anche a pagina chiusa
- [ ] Chiudendo e riaprendo la pagina l'elenco si ripopola dal broker: la pagina non tiene
      stato proprio
- [ ] La password non finisce in `localStorage` né in `sessionStorage`

### Story 2 — modelli

- [ ] Scelto *presa del ponte* e scritto `frigorifero`, i quattro topic e i quattro campi
      JSON risultano compilati e coincidono carattere per carattere con quelli pubblicati
      dal ponte
- [ ] I modelli Tasmota e Zigbee2MQTT producono le configurazioni degli esempi del README
- [ ] Un topic di comando con `+` o `#` è rifiutato dal modulo, con il motivo

### Story 3 — un telefono nuovo

- [ ] App appena installata: configurato il broker, i dispositivi del registro compaiono
      senza toccare "Aggiungi"
- [ ] Le schede si popolano di stato, potenza ed energia come se fossero state registrate a
      mano: il registro non porta uno stato, porta una configurazione
- [ ] Il modulo di registrazione è in sola lettura e dice dove si modifica

### Story 4 — modifiche e cancellazioni

- [ ] Un dispositivo rinominato sul configuratore cambia nome nell'app senza perdere lo
      stato già ricevuto
- [ ] Un dispositivo eliminato sparisce dall'app anche se il telefono era **spento** al
      momento dell'eliminazione: lo si legge dalla sua assenza nel documento
- [ ] Cambiare un topic di stato produce l'annullamento della vecchia sottoscrizione e la
      nuova, verificabile da `/mqtt` della API di diagnostica
- [ ] Due finestre aperte: la seconda che pubblica su una revisione superata viene fermata
      con l'avviso, e non sovrascrive

### Story 5 — registro sbagliato o più nuovo

- [ ] Un payload non JSON, o senza `dispositivi`, viene **rifiutato in blocco**: il registro
      applicato in precedenza resta, e l'app continua a funzionare
- [ ] Un `schema` maggiore di quello conosciuto viene rifiutato, non interpretato a metà
- [ ] Un dispositivo con un `kind` sconosciuto viene saltato, e gli altri applicati
- [ ] Campi sconosciuti dentro un dispositivo noto vengono ignorati senza errore
- [ ] Ogni rifiuto compare in `/state` fra le anomalie, con il motivo
- [ ] La **prima** applicazione su un'app che ha già dispositivi propri mostra il conto e
      aspetta conferma prima di rimuovere qualcosa
- [ ] Un dispositivo locale con lo stesso topic di stato di uno del registro viene adottato,
      non duplicato

### Story 6 — accesso

- [ ] Senza credenziali valide la pagina non mostra il registro e non pubblica
- [ ] Il client id è diverso a ogni apertura, e due schede aperte insieme non si buttano
      fuori a vicenda
- [ ] La pagina raggiunta attraverso il tunnel WireGuard funziona come in LAN

---

## 5. Rischi e dipendenze

### Rischi tecnici

| Rischio | Impatto | Mitigazione |
|---|---|---|
| **Ultima scrittura vince.** MQTT non ha compare-and-swap: due finestre che pubblicano insieme si sovrascrivono | Una modifica persa in silenzio | `revisione` confrontata con quella ritenuta al momento di pubblicare. Non è atomico — due invii nello stesso istante passano entrambi — ma copre il caso vero, che è la scheda lasciata aperta ieri |
| **Il registro è distruttivo per definizione.** Fonte di verità unica significa che ciò che non c'è viene rimosso | Dispositivi registrati a mano cancellati alla prima sincronizzazione | Adozione per topic di stato + conferma esplicita alla prima applicazione, con il conto di cosa verrà rimosso |
| **Registro malformato o più nuovo del lettore** | Casa senza dispositivi per un errore di battitura | Rifiuto in blocco e conservazione del precedente. Campo `schema` esplicito. Regola: quello che non si capisce non si applica — la stessa del payload di disponibilità già nei test |
| **Chiunque abbia le credenziali del broker può riscrivere il registro.** Oggi c'è un utente solo e nessuna ACL | Un ospite sul Wi-Fi con la password del broker riconfigura casa | Accettato per ora, e documentato. La strada, se servirà, è un secondo utente con una `acl_file` che dà `write` su `casa/registro/#` solo a lui |
| **WebSocket in chiaro** (`ws://`) | Password del broker leggibile da chi è sulla LAN | Coerente col 1883, che è già in chiaro. Da fuori si passa dal tunnel WireGuard, che cifra. Da rivedere solo insieme al TLS del broker |
| **Mixed content.** Se un giorno la pagina va in HTTPS, il browser blocca `ws://` | La pagina smette di collegarsi, senza errore visibile in interfaccia | Documentato: o entrambi in chiaro o entrambi cifrati. Errore di connessione mostrato sempre, mai un'attesa muta |
| **Il documento cresce con i dispositivi** | Riscrittura di tutto a ogni modifica | ~700 byte a dispositivo: 7 fanno 5 KB, 100 ne farebbero 60. Mosquitto non ha limiti in gioco a queste dimensioni |
| **Il client MQTT del browser** è un pezzo nuovo, e la variante sbagliata di Paho è già costata al progetto (quella `android.service`) | Libreria abbandonata da manutenere | MQTT.js, che è quello mantenuto, servito dall'immagine e non da CDN |
| **`uuid` su dispositivi che non ce l'hanno** | Migrazione Room che duplica o perde dispositivi | Migrazione 4→5 scritta a mano, come le tre precedenti: colonna nuova, uuid generato riga per riga, nessuna tabella ricreata |
| **Le sottoscrizioni nascono oggi dai dispositivi.** `syncSubscriptions()` deriva l'elenco da `devices` | Il registro ha bisogno di una sottoscrizione che esista anche a zero dispositivi | Da risolvere in fase 2: una sottoscrizione di sistema accanto a quelle dei dispositivi |

### Dipendenze

- **Nessun componente nuovo oltre a nginx.** Il broker c'è, il ponte non viene toccato,
  l'app c'è
- `mosquitto.conf`: due righe per il listener websockets, e la porta 9001 esposta in
  `compose.yml`. Il broker va riavviato una volta
- `compose.yml`: un servizio in più. Il ponte è in `network_mode: host` e resta com'è
- **Il PC che ospita lo stack si spegne la sera** (`bridge/README.md`). Il registro è
  ritenuto, quindi vale anche a broker spento — per chi lo ha già ricevuto. Un telefono
  nuovo acceso con la casa spenta non trova niente, ed è la stessa condizione in cui oggi
  non troverebbe nemmeno le prese

  > ✅ **Superato il 12/09/2026:** lo stack si sposta su un Raspberry sempre acceso. Il
  > registro è disponibile a qualunque ora, e con esso la premessa della user story 3.
- **Nessuna dipendenza nuova nell'app**: il registro è JSON, e `org.json` è già in uso nel
  driver

---

## 6. Stima effort

Giorni/uomo per una persona sola, su una codebase che si conosce.

| Area | Giorni | Cosa |
|---|---|---|
| Web app (FE) | 2,0 | Collegamento e login, elenco, modulo a 21 campi con validazione per tipo, modelli, pubblicazione ritenuta, rilevamento conflitto |
| Stack (BE) | 0,25 | Listener websockets, servizio nginx, client MQTT nell'immagine |
| App Android | 1,5 | Sottoscrizione di sistema, parsing e rifiuto, diff e applicazione per uuid, migrazione 4→5 con adozione, modulo in sola lettura, Impostazioni, `/state` |
| Test | 0,5 | Unit sulle funzioni pure: parsing del registro, rifiuto, diff, adozione per topic |
| Documentazione | 0,25 | I due README e lo schema del documento |
| **Totale** | **~4,5** | |

Il grosso è la web app, ed è quasi tutto il modulo: ventuno campi, obbligatorietà che cambia
col tipo, e le stesse regole sui topic che l'app applica già — è lavoro di interfaccia, non
di protocollo.

---

## 7. Milestones

1. **Broker parlante con il browser** — listener websockets, porta esposta, verifica con una
   pagina di quattro righe che si collega e riceve `casa/+/stato`. È il prerequisito di
   tutto e si chiude in mezz'ora
2. **Schema del documento** — campi, tipi, `schema`/`revisione`, regola su cosa si ignora e
   cosa si rifiuta. Scritto prima del codice, perché è il contratto fra due componenti che
   nessuno dei due possiede
3. **Lettore, prima dello scrittore** — l'app che riceve il registro, lo rifiuta se non lo
   capisce, e lo applica al Room per uuid. Con il documento pubblicato a mano da
   `mosquitto_pub`: si prova il pezzo difficile senza avere ancora un'interfaccia
4. **Migrazione Room 4 → 5 e adozione** — uuid sui dispositivi esistenti, dispositivi locali
   riconosciuti dal topic di stato, conferma alla prima applicazione
5. **Web app: leggere** — collegamento, login, elenco dei dispositivi del registro. Sola
   lettura: se qui si sbaglia non si rompe niente
6. **Web app: scrivere** — modulo, validazione, pubblicazione ritenuta, revisione e
   conflitto
7. **Modelli** — ponte, Tasmota, Zigbee2MQTT. È il pezzo che rende la feature utile il primo
   giorno invece che il secondo
8. **App: sola lettura e Impostazioni** — modulo non scrivibile mentre segue il registro,
   stato e interruttore in Impostazioni, registro dentro `/state`
9. **Documentazione**
10. **Verifica sul campo** — le sette prese registrate dal configuratore, due telefoni che
    le ricevono, uno dei due spento durante una cancellazione e riacceso dopo

---

> Il seguito naturale, fuori da questa feature: la pagina iscritta a `casa/#` che confronta
> ciò che pubblica con ciò che è registrato e propone il resto. Il registro deve esistere
> prima che abbia senso proporre di riempirlo.
