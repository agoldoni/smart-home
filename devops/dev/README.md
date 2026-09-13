# Stack di sviluppo

Gira sul PC, in Docker, e serve a sviluppare senza toccare casa. Quello di casa
sta in [`bridge/`](../../bridge/) e gira sul Raspberry `192.168.86.2`.

```bash
cd devops/dev
cp .env.example .env        # la prima volta: scegli una password
./crea-password-mqtt.sh     # genera mosquitto/config/passwd
docker compose up -d
```

## Cosa c'è dentro

| Servizio | Dove | Cosa fa |
|---|---|---|
| `broker` | 1883, websocket 9001 | Mosquitto, stessa configurazione di casa ma **senza persistenza**: ogni avvio riparte pulito |
| `prese-finte` | — | Sette dispositivi finti, che rispondono ai comandi come farebbe l'hardware vero |
| `configuratore` | [:8080](http://localhost:8080) | La stessa immagine di casa, costruita dagli stessi file di `bridge/configuratore` |

**Quello che non c'è è il punto: non c'è il ponte Tuya.** Nessun comando può
raggiungere una presa vera. Per lo stesso motivo le credenziali sono diverse da
quelle di casa — una configurazione sbagliata deve fallire, non collegarsi al
broker di produzione.

## L'app Android ci si collega da sola

La build **debug** nasce puntata qui. Le coordinate stanno in `local.properties`
(fuori da git) e `app/build.gradle.kts` le impacchetta in `BuildConfig`:

```properties
dev.broker.host=192.168.86.45
dev.broker.port=1883
dev.registry.prefix=dev
dev.broker.user=dev
dev.broker.pass=...
```

Sono **valori predefiniti**, non forzature: valgono finché nessuno ha salvato le
impostazioni su quel telefono. Appena tocchi le impostazioni — anche per
cancellare l'indirizzo — spariscono per sempre su quell'installazione. La build
release non ne ha nessuno: il broker di casa si scrive a mano, una volta.

Quindi: installare la debug su un telefono pulito basta. Se l'app era già
installata e configurata, `adb shell pm clear it.agoldoni.smarthome.debug` la
riporta al primo avvio.

## I dispositivi

I nomi **non assomigliano a quelli di casa**, ed è il punto: davanti a un elenco
si deve capire in un istante se si sta guardando lo sviluppo o il salotto. Per la
stessa ragione il prefisso dei topic è `dev` e non `casa` — i due insiemi non si
sovrappongono, quindi nemmeno un configuratore puntato al broker sbagliato
troverebbe qualcosa da rovinare.

| Dispositivo | Tipo | Perché c'è |
|---|---|---|
| `alfa`, `bravo`, `charlie` | Interruttore | Il caso normale, come le prese di casa |
| `delta` | Luce | Si comporta come un interruttore, cambia l'icona |
| `echo-regolabile` | Luce regolabile | **A casa non esiste**: è l'unico modo di provare il cursore del livello |
| `foxtrot-sensore` | Sensore | Sola lettura, nessun interruttore. Cambia valore ogni 30s da solo |
| `golf-nome-lungo-per-vedere-dove-taglia` | Interruttore | Per vedere dove le schede tagliano i nomi |

L'elenco si rigenera con il codice vero della web app — stesso modello, stessa
validazione, stessa serializzazione del browser:

```bash
node devops/dev/genera-registro-dev.mjs devops/dev/registro-dev.json
cd devops/dev && set -a && . ./.env && set +a
mosquitto_pub -h localhost -u "$MQTT_USER" -P "$MQTT_PASS" \
  -t dev/registro/dispositivi -r -q 1 -f registro-dev.json
```

Gli uuid sono deterministici: rigenerare non produce dispositivi nuovi agli occhi
dell'app, che li riconosce per uuid e non per nome.

## Il registro

Il broker non ha persistenza, quindi il registro **va ripubblicato a ogni
`docker compose up`**: è il comando `mosquitto_pub` qui sopra. Il configuratore su
<http://localhost:8080> lo legge e lo riscrive come farebbe a casa, ed è anche il
modo di provare il configuratore stesso.

E c'è una trappola che discende da lì. Il broker riparte dalla revisione del file,
ma **il telefono ricorda l'ultima che ha applicato** — quel numero sta nel suo
DataStore e un riavvio del broker non lo tocca. Dopo qualche giro di prove il
telefono può essere avanti: allora ogni riordino fatto dal configuratore arriva
davvero, e viene scartato con

```
registro   revisione 8 gia' applicata, ignorata
```

che si legge in `tools/debug-api.py --adb get /log`. Non è un difetto — è la
regola che impedisce a un documento vecchio di sovrascriverne uno nuovo — ma da
fuori sembra un riordino che non arriva. Si rimette a posto in due modi:
ripubblicando il documento con una revisione più alta di quella applicata, oppure
con `adb shell pm clear it.agoldoni.smarthome.debug`, che azzera anche la memoria
del telefono. A casa non succede: lì il broker è persistente.

Per lavorare sui dispositivi *veri* si può copiare il registro di casa, ma allora
i nomi tornano a essere quelli del salotto — da fare sapendo perché lo si sta
facendo, e per il tempo che serve.

## Dispositivi finti

Sono uno script `sh` dentro l'immagine di mosquitto: [`prese-finte/prese-finte.sh`](prese-finte/prese-finte.sh).
All'avvio dichiarano `online`, spente e con un consumo plausibile; poi ascoltano
`casa/+/comando` e **confermano sul topic di stato**, come farebbe una presa
vera. Senza quella conferma l'app resterebbe per sempre su "comando inviato", che
è esattamente il sintomo che non si vuole confondere con un difetto dell'app.

```bash
docker compose logs -f prese-finte     # si vede ogni comando arrivare
```

I valori cambiano **solo** in conseguenza di un comando — l'unica eccezione è il
sensore, che di mestiere cambia da solo. Così una scheda che si muove senza che
tu abbia toccato niente è un difetto, non rumore dell'ambiente.

Per cambiare quali dispositivi esistono: `INTERRUTTORI`, `DIMMER` e `SENSORE`
nell'ambiente del servizio, e l'elenco in `genera-registro-dev.mjs`.
