# Dispositivi in rete

Censimento della LAN di casa — `192.168.86.0/24`, router Google Wifi su `192.168.86.1` —
fatto il **12 settembre 2026**. Serve a sapere che cosa c'è già in casa prima di decidere
che cosa far passare dal ponte: metà dei dispositivi interessanti non è Tuya e parla
protocolli suoi.

La fotografia è di quel giorno: gli indirizzi arrivano dal DHCP del router e i telefoni
entrano ed escono di continuo. Quello che non cambia è il MAC, quindi è quella la colonna
da guardare per riconoscere un dispositivo che è cambiato di indirizzo.

## Come è stata fatta

Nessun singolo metodo li trova tutti, e infatti quattro dispositivi sono comparsi solo
all'ultimo passaggio:

| Metodo | Comando | Che cosa aggiunge |
|---|---|---|
| Ping sweep + ARP | `ping` su tutto il /24, poi `ip neigh` | Chi risponde al ping, con il MAC |
| Produttore dal MAC | `/usr/share/nmap/nmap-mac-prefixes` | Il nome del produttore dal prefisso OUI |
| mDNS | `avahi-browse -a -r -t` | Nomi e servizi annunciati: Epson, Bosch, Sonoff |
| Reverse DNS | `dig -x <ip> @192.168.86.1` | I nomi che il router conosce, anche di chi è spento |
| SSDP / UPnP | `M-SEARCH` in broadcast su `239.255.255.250:1900` | Il router e il suo MiniUPnPd |
| Discovery BroadLink | pacchetto di hello in broadcast su UDP/80 | Il RM4, che non ha **nessuna** porta TCP aperta |

Il BroadLink è il caso che spiega perché servono tutti: non risponde su nessuna porta TCP
dei primi 100, non annuncia niente in mDNS, e senza la sua discovery proprietaria sarebbe
rimasto una riga di ARP senza nome.

## Tutti i dispositivi, in ordine di IP

| IP | Dispositivo | MAC | Produttore | Note |
|---|---|---|---|---|
| `.1` | Router Google Wifi | `38:8b:59:e2:7c:68` | Google | Gateway, DNS (PowerDNS 5.3.8), MiniUPnPd su 5000, portal famiglia su 8080/8081 |
| `.7` | `redmi-note-13-pro` | `fa:a7:62:ac:ac:35` | — | Telefono. MAC randomizzato (bit locale acceso): cambia a ogni riconnessione |
| `.10` | `desktop-7i4mvqh` | — | — | PC Windows. Noto al router, spento durante la scansione |
| `.20` | `redminote7-redminote` | `58:20:59:16:e5:65` | Xiaomi | Telefono. In mDNS come `Android-2.local`, `_mi-connect._udp` su 56666 |
| `.45` | `pc-alberto` | `f0:2f:74:f6:f4:60` | ASUSTek | Questo PC: ci girano il broker Mosquitto e il ponte Tuya |
| `.101` | Presa Tuya **boiler** | `fc:67:1f:56:66:30` | Tuya Smart | Già nel ponte |
| `.102` | Presa Tuya **jacopo-studio** | `a8:80:55:69:52:35` | Tuya (¹) | Già nel ponte |
| `.103` | **BroadLink RM4** | `ec:0b:ae:9e:81:b9` | Hangzhou BroadLink | Telecomando IR/RF. Vedi sotto |
| `.104` | Presa Tuya **depuratore** | `fc:67:1f:56:d8:0c` | Tuya Smart | Già nel ponte |
| `.106` | Stampante/scanner **Epson** | `d4:80:8b:12:e8:cf` | Epson (¹) | `epson12e8cf`. IPP su 631, eSCL su 443, web su 80 |
| `.107` | Presa Tuya **frigorifero** | `a8:80:55:67:c7:40` | Tuya (¹) | Già nel ponte |
| `.108` | Presa Tuya **pompa** | `cc:8c:bf:a2:a7:75` | Tuya Smart | Già nel ponte. Al router si presenta come `wlan0` |
| `.110` | Presa Tuya **lavastoviglie** | `fc:67:1f:56:66:77` | Tuya Smart | Già nel ponte. Misura la presa, non la macchina: vedi `.116` |
| `.111` | `redmi-note-14` | — | — | Telefono. Noto al router, offline durante la scansione |
| `.112` | `redmi-note-15` | — | — | Telefono. Noto al router, offline durante la scansione |
| `.113` | Presa Tuya **lavatrice-nuova** | `fc:67:1f:56:5d:e0` | Tuya Smart | Già nel ponte |
| `.115` | `wlan0` | — | — | Offline. Stesso nome di fabbrica della presa Tuya `.108`: probabile ottava presa spenta |
| `.116` | **Lavastoviglie Bosch** | `c8:d7:78:40:98:f0` | BSH Hausgeräte | Home Connect. Vedi sotto |
| `.117` | **Presa Sonoff / eWeLink** | `d0:27:04:a5:31:aa` | Sonoff (¹) | Parla Matter. Vedi sotto |
| `.242` | Nodo mesh Google Wifi | `08:b4:b1:78:c5:4f` | Google | Secondo punto della mesh, firmware 14150.376.32 |
| `.250` | **SwitchBot Hub Mini** | `08:3a:8d:ee:a4:8d` | Espressif | Hub IR e gateway BLE. Vedi sotto |

(¹) I prefissi `a8:80:55`, `d4:80:8b` e `d0:27:04` non sono nel database OUI di nmap.
Il produttore è dedotto: per `a8:80:55` dalle prese che risultano già configurate in
`bridge/dispositivi.yaml`, per gli altri due dal nome annunciato in mDNS
(`EPSON12E8CF`, `sonoffsmartplug-31aa`, quest'ultimo confermato dall'header
`Server: SONOFF` sulla porta 8081).

## I quattro candidati non Tuya

### Presa Sonoff / eWeLink — `.117`

Il candidato migliore, e di parecchio. Annuncia **due** servizi in mDNS:

- `_ewelink._tcp` sulla porta 8081 — l'API LAN di eWeLink, che però ha `encrypt=true`
  nel record TXT. Una `POST /zeroconf/info` senza la chiave del dispositivo non risponde,
  e quella chiave si estrae solo dall'account eWeLink.
- `_matter._tcp` sulla porta 5540 — **Matter**, locale e documentato, senza chiavi da
  rubare a un cloud.

Se si estende il ponte, si parte da qui e si passa da Matter.

### Lavastoviglie Bosch — `.116`

`bosch-dishwasher-402080517584036397`, `_homeconnect._tcp` sulla porta 443. È il caso più
interessante perché **si sovrappone a una presa già nel ponte**: la `lavastoviglie` su
`.110` misura i watt assorbiti e da lì si deduce se il ciclo è in corso. Home Connect
darebbe programma, fase e fine ciclo veri, che è un'altra cosa rispetto a una soglia sui
consumi.

### BroadLink RM4 — `.103`

Telecomando IR/RF universale, `devtype 0x5216`, nome di fabbrica `智能遥控`
("telecomando intelligente"). Nessuna porta TCP aperta fra le prime cento: parla solo
UDP/80 con un protocollo proprietario cifrato. Risponde alla discovery in broadcast, e da
lì in poi si usa `python-broadlink` — ma serve rifare il pairing a mano per ottenere la
chiave, che è la ragione per cui è il più laborioso dei quattro.

### SwitchBot Hub Mini — `.250`

`switchbot-hubmini-eea48d`, MAC Espressif (dentro c'è un ESP). Fa da hub IR e da gateway
BLE per i sensori SwitchBot: vale meno per sé che per quello che ha attaccato via
Bluetooth. Si comanda dall'API cloud di SwitchBot o in BLE locale.

## Rifare la scansione

Gli script non sono nel repo perché sono quattro comandi che si ricordano a mente, ma
l'ordine conta — il ping sweep va fatto per primo, perché è quello che riempie la tabella
ARP che tutti gli altri passaggi leggono:

```bash
# 1. chi risponde, con il MAC
for i in $(seq 1 254); do ping -c1 -W1 192.168.86.$i >/dev/null 2>&1 & done; wait
ip neigh show dev enp7s0 | grep -v INCOMPLETE

# 2. chi si annuncia
avahi-browse -a -r -t

# 3. i nomi che il router conosce, anche di chi è spento
for i in $(seq 1 254); do dig +short -x 192.168.86.$i @192.168.86.1; done
```

Per il BroadLink serve la sua discovery proprietaria: il modo più rapido è
`python3 -c "import broadlink; print(broadlink.discover(timeout=5))"` con
`pip install broadlink`.
