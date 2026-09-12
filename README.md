# Smart Home

App Android per **registrare e pilotare i dispositivi di casa via MQTT**, nello spirito di
Smart Life: un elenco di schede a tutta larghezza, un interruttore per ciascuna, e il
dispositivo risponde. Chi è acceso ha la scheda verde, così si legge da lontano senza
mettere a fuoco l'interruttore.

La differenza sta sotto: niente cloud, niente account. L'app parla direttamente con il
broker MQTT di casa. I dispositivi non si "scoprono": si registrano a mano indicando dove
pubblicano il proprio stato e dove ascoltano i comandi. È più lavoro la prima volta, ma
funziona con qualunque cosa parli MQTT — Tasmota, Zigbee2MQTT, ESPHome, Home Assistant o
un ESP32 programmato in casa — senza aspettare che qualcuno ne scriva l'integrazione.

## Come funziona

1. **Un solo collegamento al broker**, aperto per tutta la vita del processo e condiviso da
   tutti i dispositivi. Vive nel contenitore dell'applicazione e non in un ViewModel:
   aprirlo e chiuderlo a ogni cambio di schermata perderebbe i messaggi ritenuti e farebbe
   lampeggiare l'elenco a ogni ritorno indietro.
2. **Registrare un dispositivo** significa dire all'app due cose: il topic su cui il
   dispositivo pubblica il proprio stato e quello su cui ascolta i comandi. L'app si
   iscrive al primo e pubblica sul secondo.
3. **Lo stato non si inventa.** Finché non arriva un messaggio, la scheda dice "in attesa di
   dati" invece di mostrare "spento": su un interruttore una supposizione sbagliata si
   paga. Quando si tocca l'interruttore la scheda si muove subito ma segnala "comando
   inviato", e passa allo stato definitivo solo quando il dispositivo conferma sul proprio
   topic di stato.
4. **Un dispositivo che non c'è lo dice**, e non si lascia comandare. Chi pubblica un
   topic di disponibilità viene mostrato come "non raggiungibile", con accanto l'ultimo
   stato saputo presentato come ricordo e non come fatto presente; interruttore e cursore
   restano fermi dove sono, disattivati, perché un comando mandato a chi non c'è non
   arriva a nessuno e vederli scattare racconterebbe un'accensione mai avvenuta. Vale più di quanto sembri: senza, una presa staccata
   resta "accesa" nell'elenco con la stessa sicurezza di una viva, e un comando mandato
   mentre era via lascerebbe la scheda su "comando inviato" per sempre.
5. **Le riconnessioni** hanno un padrone solo: un ciclo dentro il driver. La
   riconnessione automatica di Paho è spenta di proposito, e non per gusto: vive su un
   timer interno che l'API pubblica non sa fermare, così un client che credevamo chiuso
   tornava a collegarsi da solo con lo stesso client id e rubava la sessione a quello
   nuovo — che gliela riprendeva mezzo secondo dopo, all'infinito, mentre in cima
   all'elenco "connessione persa" appariva e spariva. Adesso il ciclo riprova dopo tre
   secondi, raddoppia l'attesa fino a un minuto e riparte da capo appena il collegamento
   regge: copre sia il primo tentativo andato male — il telefono che rientra sotto la
   rete di casa — sia il collegamento che cade dopo essere riuscito.

## Registrare un dispositivo

Il pulsante **Aggiungi** apre il modulo. I campi che contano:

| Campo | A cosa serve |
|---|---|
| Tipo | Interruttore, Luce, Luce regolabile, Sensore. Cambia i controlli sulla scheda e i campi richiesti. |
| Topic di stato | Dove il dispositivo pubblica. Ammette le wildcard `+` e `#`. |
| Campo JSON dello stato | Da compilare solo se il payload è JSON: il percorso del campo, con il punto per i livelli annidati. |
| Campo JSON della potenza | Il campo con i watt assorbiti, per chi li misura. Compilato, la scheda li mostra accanto allo stato. |
| Topic dei consumi | Dove qualcuno pubblica i kWh accumulati. Sta a parte dallo stato perché è una grandezza con un altro tempo: un totale non si azzera quando la presa si spegne. |
| Campo JSON dei kWh di oggi / del mese | I due campi da leggere in quel payload. La scheda li mostra su una riga sua, sotto lo stato. |
| Topic di disponibilità | Dove il dispositivo dichiara di essere vivo. Facoltativo, ma senza di esso una scheda continua a mostrare l'ultimo stato anche quando il dispositivo non c'è più. |
| Topic di comando | Dove l'app pubblica. Niente wildcard: il broker rifiuterebbe il messaggio. |
| Payload acceso / spento | I due valori che il dispositivo capisce, e che l'app riconosce nello stato. |
| QoS, comandi ritenuti | `retained` solo se il dispositivo deve ritrovare l'ultimo comando quando si riaccende. |

### Esempi

**Tasmota, interruttore**

```
Topic di stato:   stat/cucina_luce/POWER
Topic di comando: cmnd/cucina_luce/POWER
Payload:          ON / OFF
```

Se si preferisce leggere il telemetrico, che è JSON:

```
Topic di stato:          tele/cucina_luce/STATE
Campo JSON dello stato:  POWER
```

**Zigbee2MQTT, presa**

```
Topic di stato:            zigbee2mqtt/presa_studio
Campo JSON dello stato:    state
Topic di comando:          zigbee2mqtt/presa_studio/set/state
Payload:                   ON / OFF
Topic di disponibilità:    zigbee2mqtt/presa_studio/availability
```

La disponibilità di Zigbee2MQTT arriva come `{"state":"online"}`; l'app legge sia questa
forma sia il payload nudo `online`, quindi i due campi di payload si lasciano ai valori
predefiniti. Per Tasmota il topic è `tele/cucina_luce/LWT`, con `Online` e `Offline`.

**Zigbee2MQTT, luce regolabile**

```
Topic di stato:               zigbee2mqtt/luce_salotto
Campo JSON dello stato:       state
Topic di comando:             zigbee2mqtt/luce_salotto/set/state
Topic di comando del livello: zigbee2mqtt/luce_salotto/set/brightness
Campo JSON del livello:       brightness
Valore massimo del livello:   254
```

Il cursore ragiona sempre in percentuale: il **valore massimo** dice a cosa corrisponde il
100% sul dispositivo — 100 per Tasmota, 254 per Zigbee2MQTT. Senza questo campo un dimmer
Zigbee riceverebbe 100 dove si aspetta 254, e resterebbe a poco più di un terzo.

**Sensore**

Basta il topic di stato: la scheda mostra l'ultimo payload ricevuto, senza controlli.

## Le prese Tuya di casa

Le sette prese comprate con Smart Life non parlano MQTT, ma parlano in LAN: `bridge/` le
traduce e le fa comparire all'app come un qualunque dispositivo MQTT, senza che qui dentro
cambi una riga. Lì ci sono anche il broker e la VPN per l'accesso da fuori casa.

Tutte e sette misurano i consumi, e il ponte pubblica i watt nello stesso payload dello
stato. Una si registra così:

```
Topic di stato:            casa/frigorifero/stato
Campo JSON dello stato:    stato
Campo JSON della potenza:  potenza_w
Topic di comando:          casa/frigorifero/comando
Topic di disponibilità:    casa/frigorifero/disponibilita
Topic dei consumi:         casa/frigorifero/energia
Campo JSON kWh oggi/mese:  kwh_oggi / kwh_mese
```

La potenza compare sulla scheda accanto ad "Acceso", e risponde alla domanda che
l'interruttore da solo non risponde: la presa è alimentata, ma l'elettrodomestico attaccato
sta lavorando? Sotto, su una riga sua, i consumi accumulati — `0,84 kWh oggi · 27,3 questo
mese` — che il ponte tiene ora per ora in un archivio interrogabile per giorni, mesi e anni
(vedi `bridge/README.md`). Sotto i dieci watt il numero ha il decimale — fra `0,0 W` e `3,0 W` passa la
differenza fra spento davvero e in attesa — sopra è intero. A presa spenta non si mostra:
a relay aperto i watt sono zero per forza.

## Il broker

L'icona in alto a destra apre le impostazioni: indirizzo, porta, TLS, credenziali e client
id. Il client id vuoto va benissimo — ne viene generato uno a ogni avvio; si compila solo
se il broker applica regole di accesso per client id.

Se lo si compila, **deve essere diverso su ogni telefono**. Per specifica MQTT un id
identifica una sola connessione: quando ne arriva una seconda con lo stesso nome il broker
butta fuori la prima, quella si ricollega e butta fuori la seconda, e le due app si
rimbalzano finché una delle due non si chiude. Il broker lo scrive a chiare lettere,
`session taken over`.

La password finisce in chiaro nei dati dell'app. È leggibile solo dall'app stessa, salvo
root, ed è lo stesso livello di protezione delle app di questo genere; per questo il
manifest disattiva il backup, altrimenti uscirebbe dal telefono.

## Architettura

Il protocollo è l'unica parte destinata a cambiare, quindi è l'unica isolata dietro
un'interfaccia:

```
domain/driver/DeviceDriver.kt     <- cosa sa fare un canale verso i dispositivi
driver/mqtt/MqttDeviceDriver.kt   <- l'unica implementazione, oggi
```

Il resto — persistenza Room, ViewModel, schermate — parla solo di `Device` e
`DeviceCommand` e non va toccato per aggiungere un driver: un eventuale Tuya in LAN o una
REST alla Shelly sono una classe nuova, non un refactor.

Le dipendenze sono costruite a mano in `di/AppContainer.kt`. Con una dozzina di oggetti un
contenitore esplicito resta più leggibile di un framework a annotazioni, e tiene la build
senza processori oltre a quello di Room.

## Guardare dentro l'app

L'app sa raccontare cosa sta facendo: apre una API di sola lettura sul proprio stato interno
e **si annuncia in rete locale**, così non serve sapere in anticipo quale indirizzo le abbia
dato il DHCP. Risponde alle domande che dall'elenco delle schede non si leggono — *è arrivato
qualcosa su quel topic? a cosa sono davvero iscritto? perché il broker si dichiara connesso e
la scheda no?* — senza collegare il telefono e mettersi a leggere il logcat.

**C'è in tutte le build, release compresa.** I guai che vale la pena guardare da dentro
capitano sull'app che si usa davvero, e tenere lo strumento solo nella debug vuol dire non
averlo mai quando serve. Quello che lo rende accettabile non è l'assenza ma i paletti, e
prima di tutto il fatto che si veda: finché l'API è accesa, accanto al nome dell'app c'è un
insetto rosso.

Si accende a mano, da **Impostazioni → Diagnostica**, e a ogni installazione parte da
spenta: due porte aperte non devono esserci se nessuno le ha chieste. Sotto l'interruttore
compare l'indirizzo da aprire dal PC. Porta **8787**, TCP per l'API e UDP per la scoperta.

```bash
python3 tools/debug-api.py               # scopre il telefono e stampa tutto lo stato
python3 tools/debug-api.py discover      # solo: chi c'è e a che indirizzo
python3 tools/debug-api.py health
python3 tools/debug-api.py mqtt --limit 100
python3 tools/debug-api.py log --since 1757600000000
python3 tools/debug-api.py --adb state   # dal telefono collegato via USB, senza passare dalla rete
```

L'indirizzo trovato finisce in una cache, quindi le chiamate successive partono subito e si
torna a cercare solo quando quell'indirizzo smette di rispondere.

| Endpoint | Cosa dice |
|---|---|
| `/state` | tutto insieme, anomalie comprese: è quello da leggere per primo |
| `/health` | vivo o no, in due righe |
| `/broker` | coordinate del broker, stato del collegamento, client Paho |
| `/devices` | i dispositivi registrati, la loro configurazione e il loro stato |
| `/mqtt` | sottoscrizioni attive, contatori, traffico recente topic per topic |
| `/log` | gli eventi interni recenti |
| `/info` | build, telefono, interfacce di rete |

`?limit=` e `?since=` (millisecondi) tagliano gli elenchi: si guarda l'ora, si riproduce il
problema, si chiede `?since=` e si legge solo quello che è successo nel frattempo. Ogni
tempo nella risposta compare due volte, in millisecondi e in chiaro, per non doverlo
convertire a mano.

**`/state` le anomalie se le calcola da solo**, ed è la parte che fa risparmiare tempo: un
dispositivo iscritto a un topic che sul broker non risulta sottoscritto, uno che non ha mai
ricevuto niente, un topic che nessun dispositivo riconosce, il client Paho che si dichiara
connesso mentre la scheda mostra il contrario. Sono tutte deduzioni dai campi lì accanto,
ma qualcuno le fa sempre, invece di confrontare a occhio due elenchi di topic.

### I paletti

- **Parte spenta, e quando è accesa si vede.** L'interruttore è a mano e torna su spento a
  ogni installazione; finché resta acceso la barra del titolo porta un insetto rosso. Una
  cosa del genere lasciata accesa per distrazione deve darsi fastidio da sola.
- **Solo in lettura.** Solo GET, e nessuna richiesta che arrivi lì può accendere un
  dispositivo. Per comandare c'è il broker, che ha le sue regole di accesso.
- **Solo da indirizzi privati.** Il socket ascolta su `0.0.0.0` perché deve rispondere al
  PC, ma chi bussa da un indirizzo pubblico si prende un 403 e non viene nemmeno letto. Il
  tunnel WireGuard passa, perché è casa allungata e per entrarci bisogna già essere dentro.
- **La password del broker non esce.** Delle coordinate del broker si dice tutto — è lì che
  stanno metà dei guai — ma della password solo se c'è.
- **Vive quanto il processo**, come il collegamento al broker: chiusa l'app, non risponde
  più nessuno.

La scoperta è UDP e non mDNS. L'app fa entrambe le cose che servono a farsi trovare: manda
un annuncio in broadcast ogni cinque secondi, così basta mettersi in ascolto, e risponde
subito a chi le manda la parola d'ordine, così non si aspetta il prossimo annuncio. mDNS
avrebbe fatto lo stesso lavoro in modo più ortodosso, ma dipende da un risolutore installato
sul PC e dal fatto che la mesh Wi-Fi inoltri il multicast; un broadcast con dentro del JSON
si legge con quattro righe di Python e non ha niente in mezzo che possa perderlo.

Il registro interno invece parte con l'app, a interruttore spento: accendendo l'API ci si
trova già dentro l'avvio, che è quasi sempre il momento interessante. Tiene le ultime 200
voci per gli eventi e altrettante per i messaggi MQTT, su due anelli separati — sette prese
che pubblicano i consumi ogni due secondi laverebbero via tutto il resto prima che qualcuno
faccia in tempo a leggere perché è caduto il collegamento.

## Build

```bash
./build.sh              # debug
./build.sh release      # pretende keystore e credenziali, vedi sotto
./build.sh clean
./install-all.sh        # installa il debug su tutti i dispositivi adb collegati
./install-all.sh --build
```

La JDK del progetto è fissata in `.sdkmanrc` (21) e la sceglie `build.sh` via SDKMAN: non
serve cambiare quella di sistema, e non va invocato `./gradlew` a mano.

Le versioni in `gradle/libs.versions.toml` sono le più alte che girano su questa macchina:
il passo successivo di AndroidX — Compose 1.12, navigation 2.10, lifecycle 2.11, core-ktx
1.19 — pretende `compileSdk 37` e AGP 9.1+. Salire non è alzare un numero: prima va
installata la platform 37 e migrato ad AGP 9. Lint segnala quelle versioni come
disponibili; è la stessa cosa detta senza il contesto.

Le due build convivono sul telefono: il debug ha `applicationId` con suffisso `.debug` e
l'icona a sfondo **blu**, la release sfondo **verde**. Quale delle due si ha davanti lo
dice anche la barra in alto, dove sotto al nome compare la versione — `v1.0.5` per la
release, `v1.0.5-debug` per l'altra. Il numero è letto da `BuildConfig.VERSION_NAME`,
quindi si alza in un posto solo, `versionName` in `app/build.gradle.kts`, e la schermata
segue.

Per la release servono la chiave e le sue credenziali nell'ambiente:

```bash
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=release          # opzionale, è il valore predefinito
export KEY_PASSWORD=...           # opzionale, senza questa si usa KEYSTORE_PASSWORD
export KEYSTORE_FILE=...          # opzionale, predefinito ~/.android/release-key.jks
```

Senza queste variabili `./build.sh release` si ferma con un messaggio esplicito invece di
produrre un APK non firmato.

## Test

```bash
./gradlew testDebugUnitTest
```

Coprono le funzioni pure del driver: il confronto fra filtro di sottoscrizione e topic (con
`+` e `#`), la lettura di un campo da un payload JSON, e l'interpretazione del payload di
disponibilità. Sono anche le più facili da sbagliare in silenzio — un topic che non combacia
non produce nessun errore, solo un dispositivo perennemente "in attesa di dati" — e per la
disponibilità il caso che conta è il payload incomprensibile, che non deve mai diventare
"non raggiungibile": da un valore che non si è capito non si deduce che il dispositivo sia
sparito.

## Limiti attuali

- **Il collegamento vive col processo.** Chiusa l'app, niente ascolto e niente notifiche.
  Per lo stato in tempo reale a schermo spento servirebbe un foreground service.
- **Nessuna scoperta automatica dei dispositivi.** Si registrano a mano; l'MQTT discovery di
  Home Assistant non è letto.
- **Nessun raggruppamento.** La stanza è solo un'etichetta: non ordina né raccoglie le
  schede.
- **Un solo broker.**
# smart-home
