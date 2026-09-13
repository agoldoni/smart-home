# Ponte Tuya → MQTT

Le prese comprate con Smart Life parlano già in locale: ascoltano sulla porta 6668 e si
annunciano da sole in broadcast UDP. Quello che manca per fare a meno del cloud è la
**chiave locale** di ciascuna, che Tuya tiene per sé.

Questo stack fa tre cose: si procura le chiavi una volta sola, traduce il protocollo Tuya
in topic MQTT, e apre una porta di casa per il telefono quando è fuori. L'app Android del
progetto non viene toccata: dopo il ponte le prese sono indistinguibili da un Tasmota o da
uno Zigbee2MQTT, che è il caso che il suo `DeviceDriver` già copre.

```
  fuori casa                      casa
┌──────────────┐          ┌────────────────────────┐
│ app Android  │ WireGuard│  broker mosquitto      │        LAN
│  parla MQTT  ├──────────┤  ponte tinytuya ⇄ MQTT ├──► 7 prese Tuya :6668
└──────────────┘  :51820  └────────────────────────┘
```

## Stato

Broker e ponte funzionano e tutte e sette le prese rispondono in locale, con chiave,
consumi e stato. I nomi vengono dall'account Tuya:

| nome | protocollo | dove |
|---|---|---|
| `boiler` | 3.3 | 192.168.86.101 |
| `depuratore` | 3.3 | 192.168.86.104 |
| `lavastoviglie` | 3.3 | 192.168.86.110 |
| `lavatrice-nuova` | 3.3 | 192.168.86.113 |
| `pompa` | 3.3 | 192.168.86.108 |
| `jacopo-studio` | 3.4 | 192.168.86.102 |
| `frigorifero` | 3.4 | 192.168.86.107 |

Gli indirizzi sono indicativi: il ponte segue le prese per id, non per IP.

Lo stack gira sul Raspberry `rpi4-smarthome` (`192.168.86.2`), che è sempre acceso: il
controllo da remoto vale a qualunque ora, e un telefono nuovo trova il registro quando
capita, invece che solo quando il PC era acceso. Vedi in fondo, *Dove gira*.

## Avvio

```bash
cp .env.example .env          # e cambia MQTT_PASS
bash crea-password-mqtt.sh    # scrive mosquitto/config/passwd
docker compose up -d          # broker + ponte
docker compose logs -f ponte
```

Con la VPN:

```bash
docker compose --profile vpn up -d
```

## Estrarre le chiavi locali

Le chiavi stanno nel cloud Tuya e si tirano giù una volta sola, con un account developer
gratuito collegato a quello di Smart Life. È il passo più noioso di tutti ed è anche
l'unico che dipende da Tuya: dopo, il cloud non serve mai più.

1. Registrati su [iot.tuya.com](https://iot.tuya.com) e crea un **Cloud Project** di tipo
   *Smart Home*, data center **Central Europe**. Segnati `Access ID` e `Access Secret`.
2. Nel progetto, scheda **Devices → Link Tuya App Account**, aggiungi l'account inquadrando
   il QR con l'app Smart Life (*Io → icona in alto a destra → Scansiona*). Le sette prese
   compaiono nell'elenco.
3. Scheda **Service API**: verifica che il progetto abbia `IoT Core` e
   `Authorization Token Management`. Senza il primo il wizard scarica un elenco vuoto.
4. Da qui:

   ```bash
   bash estrai-chiavi.sh     # chiede ID, Secret, regione (eu) e un device id qualsiasi
   python3 applica-chiavi.py # travasa le chiavi in dispositivi.yaml
   docker compose restart ponte
   ```

Il device id da dare al wizard è uno qualunque di quelli in `dispositivi.yaml`: serve solo
a capire in che data center cercare.

Le chiavi restano in `chiavi/` e in `dispositivi.yaml`, entrambi fuori da git. **La chiave
cambia se riassoci la presa con Smart Life**: in quel caso si rifà il giro per quella.

Tre cose che vale la pena sapere prima:

- **Il wizard ha bisogno delle porte di scoperta.** `estrai-chiavi.sh` ferma il ponte per la
  durata della procedura e lo riavvia da solo. Senza, l'ultimo passo muore con
  `Address already in use` — dopo aver comunque salvato le chiavi, quindi è un incidente
  senza conseguenze, ma si perde la verifica finale.
- **Il wizard scarica tutto l'account**, non solo le prese di casa: `applica-chiavi.py`
  prende solo quelle elencate in `dispositivi.yaml` e ignora il resto.
- **Il trial di IoT Core dura un mese**, rinnovabile. Scaduto non rompe niente di quello
  che gira: le chiavi sono già qui. Servirebbe di nuovo solo per una presa riassociata.

## I topic

Un dispositivo di nome `boiler` occupa:

| topic | chi scrive | contenuto |
|---|---|---|
| `casa/boiler/stato` | il ponte | JSON ritenuto, campo `stato` = `ON`/`OFF` |
| `casa/boiler/comando` | l'app | `ON`, `OFF` oppure `TOGGLE` |
| `casa/boiler/disponibilita` | il ponte | `online` / `offline`, ritenuto |
| `casa/boiler/dps/comando` | a mano | JSON grezzo, es. `{"1": true}` |
| `casa/boiler/energia` | il ponte | JSON ritenuto, consumi accumulati. Vedi sotto |

e il ponte nel suo insieme tiene `casa/ponte/stato`, con `offline` lasciato come testamento
al broker.

Lo stato completo è così:

```json
{"stato":"ON","ip":"192.168.86.107","versione":"3.4","corrente_ma":1006,"potenza_w":233.4,
 "tensione_v":229.9,"dps":{"1":true,"17":30,"18":1006,"19":2334,"20":2299,"38":"on"}}
```

Tutte e sette misurano i consumi, e i tre dp che contano sono gli stessi per entrambi i
modelli: 18 la corrente in mA, 19 la potenza in decimi di W, 20 la tensione in decimi di V.
La mappatura sta una volta sola in `letture_predefinite`, non ripetuta per dispositivo.
Torna anche dalla fisica: 229,9 V per 1,006 A fanno i 233,4 W che il frigorifero dichiara.

`dps` resta lì accanto, riportato senza interpretarlo: è quello che la presa dice davvero.
Il dp 17 sembra un contatore di energia cumulata ma l'unità non è confermata, quindi non è
promosso a campo leggibile: meglio un numero grezzo che un'etichetta sbagliata.

Gli aggiornamenti parziali — la presa che manda solo il dp appena cambiato — vengono fusi
con quello che il ponte sa già, e uno stato senza il dp dell'interruttore non viene
pubblicato affatto. Senza questa cura una presa accesa comparirebbe spenta ogni volta che
la tensione oscilla, che è il genere di supposizione che su un interruttore si paga.

## L'energia

Le prese non pubblicano nessun consumo giornaliero, quindi lo accumula il ponte. Il topic
`casa/<nome>/energia` porta i totali correnti:

```json
{"kwh_oggi":0.842,"kwh_mese":27.31,"giorno":"2026-09-12","mese":"2026-09",
 "sorgente":"dp17","copertura_oggi":0.98}
```

`sorgente` dice da dove viene il numero e **non è un dettaglio**:

- **`dp17`** — dal contatore interno della presa, che integra con le sue misure vere. Sei
  prese su sette.
- **`integrale`** — dai campioni di potenza che pubblichiamo noi, integrati nel tempo. Solo
  la pompa, che il dp 17 non ce l'ha. Vale meno: le prese rinfrescano le misure a soglia, e
  una potenza che resta ferma venti minuti integrata dà quello che dà.

`copertura_oggi` è la frazione di giornata in cui il ponte ha davvero visto la presa. Un'ora
in cui era irraggiungibile non va letta come un'ora di consumo basso, e con il dp 17 nemmeno
come energia persa: la presa ha continuato a contare da sola e al ritorno il delta la
restituisce — solo, finisce nell'ora in cui la si legge. Oltre un'ora di silenzio la lettura
riparte da zero: meglio dichiarare persa un'ora che scaricare tre giorni di consumi dentro
una riga sola.

### L'archivio

`stato/energia.db`, una riga per presa e per ora. **Dentro ci sono le tacche, non i kWh:**

```sql
CREATE TABLE energia_grezza (presa, inizio_utc, giorno, ora, grezzo, sorgente, copertura);
CREATE TABLE fattori      (presa, sorgente, wh_per_unita);
CREATE VIEW  energia AS   -- il join che moltiplica: è questo che si interroga
  SELECT g.*, g.grezzo * f.wh_per_unita / 1000.0 AS kwh FROM energia_grezza g JOIN fattori f ...;
```

Il conteggio è un fatto, il fattore che lo trasforma in energia è un'interpretazione, e
stanno in due tabelle diverse. **Quanto valga una tacca del dp 17 non è confermato**: lo
schema Tuya standard dice 1 Wh, le misure fatte qui danno 0,4–0,8. Tararlo dopo non è un
problema perché non tocca l'archivio — si cambia `wh_per_tacca` in `dispositivi.yaml` e
dieci anni di righe cambiano valore insieme:

```bash
# dopo una taratura, per provare prima di metterla in configurazione
sqlite3 stato/energia.db "UPDATE fattori SET wh_per_unita=0.92 WHERE sorgente='dp17'"
```

Per tarare serve un carico **resistivo, stabile e grosso**: il boiler da freddo per dieci
minuti. Resistivo perché V × I = W si verifichi da sé, stabile perché energia = potenza ×
tempo senza integrare niente, grosso perché a 2 kW le tacche arrivano ogni due secondi
mentre a 3 W ne arriva una ogni venti minuti.

### Le domande che si fanno

```bash
# i consumi di oggi, presa per presa
sqlite3 -column stato/energia.db "SELECT presa, ROUND(SUM(kwh),3) FROM energia
  WHERE giorno = date('now','localtime') GROUP BY presa ORDER BY 2 DESC"

# il mese, con quanto ci si può fidare
sqlite3 -column stato/energia.db "SELECT presa, ROUND(SUM(kwh),2) kwh, ROUND(AVG(copertura),2) visto
  FROM energia WHERE giorno LIKE '2026-09%' GROUP BY presa ORDER BY kwh DESC"

# a che ora si consuma, sull'ultimo mese
sqlite3 -column stato/energia.db "SELECT ora, ROUND(SUM(kwh),2) FROM energia
  WHERE presa='boiler' GROUP BY ora ORDER BY ora"

# gli anni, quando ce ne saranno
sqlite3 -column stato/energia.db "SELECT substr(giorno,1,4) anno, ROUND(SUM(kwh),1) FROM energia GROUP BY anno"
```

L'ora in corso non è ancora in archivio: vive in memoria e finisce in `stato/energia.json`
una volta al minuto, così un riavvio del ponte ne perde al massimo sessanta secondi.

> Il ponte gira come `${PUID:-1000}` proprio per questa cartella: da root, i file
> dell'archivio nascerebbero di root dentro il progetto.

## Registrare le prese nell'app

Dal **configuratore web** (sezione qui sotto) basta il nome: si sceglie il modello *Presa
del ponte Tuya* e i quattro topic con i quattro campi JSON li compila la convenzione. È il
modo consigliato, e le sette prese si fanno in un paio di minuti.

A mano, nel modulo *Aggiungi* dell'app, per ciascuna:

| campo | valore |
|---|---|
| Tipo | Interruttore |
| Topic di stato | `casa/boiler/stato` |
| Campo JSON dello stato | `stato` |
| Campo JSON della potenza | `potenza_w` |
| Topic di comando | `casa/boiler/comando` |
| Payload acceso / spento | `ON` / `OFF` |
| Topic dei consumi | `casa/boiler/energia` |
| Campo JSON dei kWh di oggi / del mese | `kwh_oggi` / `kwh_mese` |
| Comandi ritenuti | no |

Nelle impostazioni del broker vanno indirizzo, porta 1883 e le credenziali di `.env`.
Sull'indirizzo vedi la sezione qui sotto: ce n'è uno solo che va bene sia dentro che fuori
casa. **Queste restano per telefono anche con il configuratore**, ed è per forza: sono
quello che serve per *ricevere* il registro, quindi non possono arrivare dentro il registro.

## Il configuratore web

Una pagina da cui si configurano i dispositivi **una volta sola per tutta la casa**. Quello
che si salva finisce sul broker come messaggio ritenuto, e ogni app che si collega lo trova
— adesso, o fra tre settimane su un telefono che ancora non esiste.

```bash
docker compose up -d configuratore     # poi http://<questa macchina>:8080
```

La porta è `CONFIGURATORE_PORT` in `.env`, 8080 di default. La pagina chiede le credenziali
del broker, le stesse dell'app: chi non le ha non entra e non scrive. Restano in memoria per
la durata della scheda, non vengono salvate.

Non c'è nessun server applicativo e nessun database: la pagina è statica e parla direttamente
con Mosquitto in **websocket sulla 9001**, con lo stesso `password_file` del 1883. Il client
MQTT sta dentro l'immagine e non su una CDN — lo stack deve funzionare con la linea di casa
giù, che è esattamente quando si va a guardare perché le prese non rispondono.

### Il registro

| topic | chi scrive | contenuto |
|---|---|---|
| `casa/registro/dispositivi` | il configuratore | JSON ritenuto: l'elenco completo dei dispositivi, con `schema` e `revisione` |

Un documento solo e non un topic per dispositivo, per via delle **cancellazioni**: con un
topic ciascuno, una presa tolta mentre un telefono è spento non verrebbe mai più nominata e
su quel telefono resterebbe per sempre. Il documento unico dice sempre l'insieme completo,
quindi "non c'è più" si legge dalla sua assenza.

Il registro vive sotto lo stesso prefisso dei dispositivi che descrive, e il prefisso è
configurabile da entrambe le parti: è così che sullo stesso broker convivono `casa/` e
`ufficio/`, ognuno con i suoi dispositivi e il suo registro. Ne discende un nome riservato —
dentro un prefisso **nessuna presa può chiamarsi `registro`**.

Il formato e le regole di rifiuto stanno in [`configuratore/SCHEMA.md`](configuratore/SCHEMA.md).
La regola sotto tutte è quella che l'app applica già ai payload di disponibilità: da un
valore che non si è capito non si deduce niente. Un registro incomprensibile non è un
registro vuoto.

Per cancellarlo:

```bash
mosquitto_pub -h localhost -u casa -P ... -t casa/registro/dispositivi -r -n
```

Le app **smettono di seguirlo e tengono i dispositivi che hanno**. La lettura opposta —
registro cancellato uguale casa senza dispositivi — farebbe di un comando solo un disastro.

Il registro non tocca `dispositivi.yaml`: quello dice al *ponte* con chi parlare e contiene
le chiavi locali, il registro dice alle *app* cosa mostrare. Rinominare una presa di qua
senza rinominarla di là dà un bel nome che punta a topic che non esistono; l'app se ne
accorge e lo dice in `/state` — *«non è mai arrivato niente sul topic …»*.

## Da fuori: WireGuard

La catena di casa è a doppio NAT e va bucata a due livelli:

```
Internet ──► ZTE H2640W        192.168.1.1     agoldoni.duckdns.org
               └──► Google Wifi  WAN 192.168.1.101 / LAN 192.168.86.1
                       └──► Raspberry 192.168.86.2
```

1. **Sullo ZTE** (`http://192.168.1.1`): inoltra **UDP 51820 → 192.168.1.101**, e riserva
   quell'indirizzo al Google Wifi, altrimenti al primo riavvio la regola punta al vuoto.
2. **Sul Google Wifi** (app Google Home, *Impostazioni → Rete → Avanzate → Port
   forwarding*): inoltra **UDP 51820 → 192.168.86.2**, e riserva anche questo in DHCP.
3. `docker compose --profile vpn up -d`, poi il QR per il telefono:

   ```bash
   docker compose exec wireguard /app/show-peer telefono
   ```

Il peer nasce con `AllowedIPs = 192.168.86.0/24`: nel tunnel passa solo la LAN di casa, il
resto del traffico del telefono esce normalmente. Il vantaggio è che l'app può puntare
**sempre a `192.168.86.2`**, dentro e fuori casa, e il campo broker non si tocca mai.

Il peer riceve anche `DNS = 192.168.86.1`, che sta dentro la rete instradata: col tunnel
attivo i nomi li risolve il router di casa, e da fuori funzionano anche i `.lan`. Il rovescio
è che se la linea di casa cade mentre il tunnel è su, il telefono resta senza risoluzione
dei nomi finché non lo spegni. L'app non ne soffre, perché al broker ci va per indirizzo.

**In casa il tunnel funziona lo stesso.** L'endpoint è il nome DDNS, cioè l'IP pubblico, e
di solito rientrare da dentro la propria rete richiede l'hairpin NAT, che molti router
domestici non fanno. Questo ZTE lo fa: verificato, tunnel agganciato dal Wi-Fi di casa con
handshake regolare. Si può quindi lasciare WireGuard sempre attivo e non pensarci più.

Il prezzo è un giro a vuoto — il pacchetto esce fino allo ZTE e rientra — per raggiungere
una macchina che sta a due metri. Se dà fastidio, l'app WireGuard di Android sa disattivarsi
da sola sulla rete Wi-Fi di casa: in quel caso l'app punta comunque a `192.168.86.2`, che
in LAN si raggiunge diretta. In un modo o nell'altro l'indirizzo del broker non cambia mai,
ed è tutto il punto di questa scelta.

Il DDNS `agoldoni.duckdns.org` punta già all'IP giusto. Assicurati che qualcosa continui ad
aggiornarlo quando Vodafone cambia indirizzo: se non c'è già un aggiornatore da qualche
parte, il posto naturale è questo stack.

## Dove gira

Sul Raspberry `rpi4-smarthome`, `192.168.86.2`, in `~/projects/smart-home/bridge` —
traslocato dal PC il 13 settembre 2026.

**Là sopra non si costruisce niente e non ci sono i sorgenti.** Le immagini si costruiscono
sulla macchina di sviluppo, per `linux/arm64`, e arrivano già pronte:

```bash
./devops/deploy.sh
```

Lo script fa tre cose, e ognuna risolve un problema preciso:

- **costruisce per arm64 con `buildx`** — il PC è `x86_64` e il Pi no. Un'immagine costruita
  in modo normale là sopra non parte proprio: `exec format error`, e non si capisce subito
  perché;
- **tagga con lo SHA del commit** e lo stampa dentro l'immagine come label OCI. Il Pi non è
  un checkout git e non potrebbe dirlo in nessun altro modo: da qui in poi
  `docker image inspect` risponde a *«quale revisione sta girando?»*;
- **trasferisce con `docker save | ssh docker load`**, senza registro. Il consumatore è uno
  solo: un registro chiederebbe di toccare `daemon.json` sul Pi e pretenderebbe questa
  macchina accesa perché il Pi possa ripartire pulito.

Il tag finisce anche nel `.env` del Pi, così un `docker compose up -d` dato a mano là sopra
riparte con le stesse immagini invece di lamentarsi di una variabile che non c'è.

### Cosa c'è sul Pi

Quattro file, e nient'altro:

| File | Perché |
|---|---|
| `compose.yml` | descrive cosa gira. Lo aggiorna `deploy.sh` |
| `.env` | credenziali del broker, porte, `IMAGE_TAG` |
| `mosquitto/config/passwd` | segreto, generato, diverso per installazione |
| `dispositivi.yaml` | le chiavi locali delle prese |

Tutto il resto è dentro le immagini — `mosquitto.conf` compreso, che è codice e passa da una
build come il resto — oppure dentro **volumi nominati**: `broker-dati` per i messaggi
ritenuti, `ponte-stato` per lo storico dei consumi, `wireguard-config` per le chiavi della
VPN.

Che i dati stiano nei volumi e non in cartelle del progetto **non è ordine, è sicurezza**:
finché erano directory dentro `bridge/`, un `rsync` senza le esclusioni giuste le
sovrascriveva con le copie ferme al giorno del trasloco — cancellando il registro ritenuto e
le chiavi della VPN. Adesso non c'è più niente da escludere, perché non c'è più niente da
sovrascrivere.

Il ponte resta in `network_mode: host` e sulla stessa LAN delle prese: gli annunci in
broadcast non attraversano né un bridge Docker né un router.

Nel trasloco sono passati anche i file che il `.gitignore` tiene fuori, e non per comodità:

- `mosquitto/data/mosquitto.db` porta i **messaggi ritenuti**, cioè il registro
  `casa/registro/dispositivi`. Senza, il registro andrebbe rifatto dal configuratore, e nel
  frattempo le app resterebbero coi dispositivi che hanno
- `wireguard/` porta le **chiavi del server**. La chiave pubblica è quindi la stessa di
  prima e l'endpoint dei peer è il nome DDNS, non un indirizzo: il profilo già sul telefono
  continua a valere, senza QR nuovo
- `stato/energia.db` è l'archivio dei consumi. Va copiato a ponte fermo e col WAL già
  consolidato (`PRAGMA wal_checkpoint(TRUNCATE)`), altrimenti si copia un file a metà

Quello che il trasloco **non** può fare da solo sta tutto fuori dal Pi:

1. Sul Google Wifi, spostare l'inoltro **UDP 51820** sul nuovo indirizzo, altrimenti la VPN
   da fuori non risponde più
2. Sempre sul Google Wifi, **riservare `192.168.86.2` in DHCP**: il Pi lo prende in `auto`,
   e un indirizzo che cambia è un broker che sparisce da tutti i telefoni insieme
3. Su ogni telefono, scrivere il nuovo host del broker. Le coordinate del broker sono quello
   che serve per *ricevere* il registro, quindi non possono arrivare dentro il registro

## Test

```bash
docker run --rm -v "$PWD/tuya-mqtt:/app:ro" -w /app smart-home/tuya-mqtt:latest \
  python -m unittest test_bridge -v
```

Coprono la parte che sbagliata non produce nessun errore: i comandi. **Per un
interruttore i comandi non fanno coda.** Se la presa e' irraggiungibile e qualcuno tocca
l'interruttore cinque volte, una coda glieli applicherebbe tutti alla riconnessione, uno
ogni intervallo di polling, e il relay commuterebbe cinque volte inseguendo comandi ormai
vecchi. Di una fila di accensioni e spegnimenti conta solo l'ultima, quindi c'e' una sola
casella e la richiesta nuova sostituisce quella che non e' ancora passata.

Il caso sottile, che ha un test tutto suo: mentre il ciclo sta provando ad applicare una
richiesta, ne arriva una piu' recente. Quando la vecchia finalmente passa non deve
cancellare la nuova — per questo `_consuma` confronta l'identita' e non svuota e basta.

Non e' verificabile contro una presa vera: servirebbe renderla irraggiungibile a comando.
Una raffica su una presa che risponde non prova niente, perche' il ponte fa in tempo a
consumare ogni comando prima che arrivi il successivo, e li applica tutti — che e' il
comportamento giusto quando la presa e' raggiungibile.

## Quando qualcosa non va

```bash
docker compose logs -f ponte                     # cosa vede il ponte
docker compose exec broker mosquitto_sub -h localhost -t 'casa/#' -v \
  -u casa -P "$(grep MQTT_PASS .env | cut -d= -f2)"
```

- **`manca la chiave locale`** — non hai ancora fatto la sezione delle chiavi.
- **una presa che va e viene** — è segnale Wi-Fi, non software. Il sintomo che conta non è
  la latenza: queste prese rispondono al ping fra i 40 e i 125 ms *tutte*, perché sono ESP
  con risparmio energetico e dormono fra un beacon e l'altro. Il sintomo è sparire. Una
  presa fuori portata perde il 100% dei pacchetti e smette di annunciarsi in broadcast,
  mentre le altre si annunciano ogni cinque secondi: `python3 ascolta.py` conta gli annunci
  ed è la prova più rapida.
  Una delle prese era esattamente in quel caso, e spostarla ha risolto. Il ponte comunque ci
  convive: un comando arrivato mentre il collegamento
  cadeva non viene buttato, resta in attesa e riparte dopo la riconnessione, e solo dopo
  `validita_comando` secondi lo si lascia cadere dicendolo nei log. Senza questo l'app
  resterebbe su "comando inviato" senza che nessuno spieghi perché.
- **l'app ci mette un'eternità ad accorgersi di un cambio fatto a mano sulla presa** — è
  il ciclo di lettura, non l'app. Le prese non avvisano nessuno: è il ponte che chiede, e
  ogni lettura finisce nel log come `lettura in 0.0s`. Oltre `soglia_lettura_lenta`
  secondi (3 di serie) la riga diventa un WARNING e si vede anche a log normale; per
  vederle tutte serve `LIVELLO_LOG=DEBUG`. Il 12/09/2026 fra la pressione del pulsante e
  la pubblicazione MQTT passavano **79 secondi**, e il log non diceva niente:
  `tentativi_socket` era 3, una lettura a vuoto costava 19,2 secondi misurati, e il
  contatore dei fallimenti si azzerava a ogni successo senza mai arrivare ai tre di fila
  che avrebbero fatto scattare un avviso. Con `tentativi_socket: 1` e
  `intervallo_polling: 2` la stessa misura, rifatta con lo stesso metodo, dà **un
  secondo**. Ed è per questo che la lettura si cronometra: senza, un ciclo lento non
  lascia traccia.
- **una scheda che non si aggiorna, e non si capisce se il messaggio non arriva o se
  l'app non lo ha mai chiesto** — scommenta `log_type subscribe` in
  `mosquitto/config/mosquitto.conf`, riavvia il broker e guarda: una riga per
  sottoscrizione, `<client id> <qos> <topic>`. È l'unico modo di vedere da fuori cosa
  ogni app sta davvero ascoltando, e chiude la questione in un colpo solo. Il 12/09/2026
  ha inchiodato una presa rimasta "accesa": l'app si iscriveva a `casa/pompa/stato` e a
  nient'altro, perché nella sua registrazione il topic di disponibilità era vuoto.
- **una presa sempre `offline`** — chiave sbagliata (spesso è quella di un altro
  dispositivo), oppure qualcun altro tiene occupata l'unica connessione che le prese 3.3
  accettano: l'app Smart Life aperta in primo piano, o uno script tinytuya lanciato a mano
  mentre il ponte gira. Chiudi l'una o ferma l'altro — `docker compose stop ponte` — e
  riprova.
- **il ponte non scopre niente** — sei finito in `network_mode: bridge`, o c'è un altro
  processo sulle porte UDP 6666/6667.
- **`Connection Refused: not authorised`** — `mosquitto/config/passwd` non combacia con
  `.env`: rilancia `crea-password-mqtt.sh` e riavvia il broker.

## Le prese offline nell'app

Quando una presa smette di rispondere il ponte pubblica `disponibilita: offline`, e l'app
lo legge: la scheda diventa "non raggiungibile · ultimo: acceso", con l'ultimo stato
presentato come ricordo invece che come fatto presente. Nel modulo di registrazione va
indicato il **topic di disponibilità**, `casa/<nome>/disponibilita`; i due payload restano
`online` e `offline`, che sono già i valori predefiniti.

Vale la pena compilarlo. Senza, una presa staccata resta accesa sulla griglia con la stessa
sicurezza di una viva, e un comando mandato mentre era via lascia la scheda su "comando
inviato" a tempo indeterminato.

## Dopo, se ne vale la pena

`tuya-cloudcutter` riflasha le prese con OpenBeken via OTA, senza saldatore, se il modello
è nel suo elenco. A quel punto parlano MQTT da sole, il ponte per quelle sparisce e il
cloud Tuya non le vede più. Stessi topic, app ancora invariata. Ma è irreversibile e può
brickare: prima si fa funzionare tutto di qui, poi eventualmente una presa di prova.
