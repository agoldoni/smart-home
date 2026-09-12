# Il registro dei dispositivi

Contratto fra il configuratore web e le app. Non è la serializzazione di una classe: è un
formato che due programmi diversi devono continuare a capire anche quando uno dei due è
fermo a una versione di sei mesi fa.

## Dove

```
<prefisso>/registro/dispositivi
```

Ritenuto, QoS 1. Il prefisso è configurabile e vale `casa` di norma — lo stesso sotto cui
pubblicano i dispositivi che il registro descrive.

Il registro sta **sotto lo stesso prefisso dei dispositivi** apposta: così il prefisso è il
confine di un'istanza, e cambiarlo sposta insieme i dispositivi e l'elenco che li nomina.
Sullo stesso broker possono convivere `casa/` e `ufficio/`, ciascuno con il suo registro, e
un'app sceglie a quale appartiene con una stringa.

Ne discende un nome riservato: dentro un prefisso, **nessun dispositivo può chiamarsi
`registro`**.

## Cosa

```json
{
  "schema": 1,
  "revisione": 12,
  "aggiornato": "2026-09-12T21:04:33+02:00",
  "dispositivi": [
    {
      "uuid": "8f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
      "nome": "frigorifero",
      "tipo": "SWITCH",
      "topic_stato": "casa/frigorifero/stato",
      "campo_stato": "stato",
      "topic_comando": "casa/frigorifero/comando",
      "payload_on": "ON",
      "payload_off": "OFF",
      "campo_potenza": "potenza_w",
      "topic_energia": "casa/frigorifero/energia",
      "campo_kwh_oggi": "kwh_oggi",
      "campo_kwh_mese": "kwh_mese",
      "topic_disponibilita": "casa/frigorifero/disponibilita",
      "payload_disponibile": "online",
      "payload_non_disponibile": "offline",
      "topic_stato_livello": null,
      "topic_comando_livello": null,
      "campo_livello": null,
      "livello_max": 100,
      "qos": 1,
      "ritenuto": false
    }
  ]
}
```

### Testata

| Campo | Tipo | Obbl. | Significato |
|---|---|---|---|
| `schema` | intero | sì | Versione del formato. Oggi `1`. Un lettore rifiuta in blocco quello che non conosce |
| `revisione` | intero | sì | Cresce di uno a ogni pubblicazione. Serve a scartare un documento più vecchio di quello già applicato e ad accorgersi che qualcun altro ha scritto nel frattempo |
| `aggiornato` | stringa ISO-8601 | no | Solo da mostrare. Nessuna decisione dipende da questo campo: viene da un orologio che non controlliamo |
| `dispositivi` | lista | sì | L'insieme **completo**. Ciò che non c'è non esiste |

### Un dispositivo

| Campo | Tipo | Obbl. | Predefinito | Significato |
|---|---|---|---|---|
| `uuid` | stringa | sì | — | Identità nel registro, stabile per sempre. Non è l'id locale dell'app, che è un numero diverso su ogni telefono |
| `nome` | stringa | sì | — | Quello che si legge sulla scheda |
| `tipo` | stringa | sì | — | `SWITCH`, `LIGHT`, `DIMMER`, `SENSOR` |
| `topic_stato` | stringa | sì | — | Dove il dispositivo pubblica. Ammette `+` e `#` |
| `campo_stato` | stringa/null | no | `null` | Campo JSON da leggere, con il punto per i livelli annidati. `null` se il payload è già il valore |
| `topic_comando` | stringa | sì salvo `SENSOR` | `""` | Dove l'app pubblica. **Niente wildcard** |
| `payload_on` / `payload_off` | stringa | no | `ON` / `OFF` | I valori che il dispositivo capisce, e che l'app riconosce nello stato |
| `campo_potenza` | stringa/null | no | `null` | Watt istantanei dentro il payload dello stato |
| `topic_energia` | stringa/null | no | `null` | Consumi accumulati |
| `campo_kwh_oggi` | stringa/null | se c'è `topic_energia` | `null` | — |
| `campo_kwh_mese` | stringa/null | no | `null` | — |
| `topic_disponibilita` | stringa/null | no | `null` | Dove si dichiara vivo. `null` = non lo dichiara, e l'app non finge di saperlo |
| `payload_disponibile` / `payload_non_disponibile` | stringa | no | `online` / `offline` | — |
| `topic_stato_livello` | stringa/null | no | `null` | Solo `DIMMER`. `null` se il livello arriva sul topic dello stato |
| `topic_comando_livello` | stringa/null | sì per `DIMMER` | `null` | Niente wildcard |
| `campo_livello` | stringa/null | no | `null` | — |
| `livello_max` | intero | no | `100` | A cosa corrisponde il 100%: 100 Tasmota, 254 Zigbee2MQTT |
| `qos` | intero 0-2 | no | `0` | — |
| `ritenuto` | booleano | no | `false` | Comandi ritenuti dal broker |

`stanza` **non c'è**: l'app non ordina né raggruppa per stanza, e un campo che nessuno legge
invecchia male. Rimetterlo quando servirà non romperà i lettori vecchi — vedi *Far evolvere
lo schema*.

## Le regole di lettura

Sono la parte che conta più dei nomi dei campi.

| Situazione | Comportamento |
|---|---|
| Payload non JSON, o senza `dispositivi` | **rifiuto in blocco**: resta applicato il registro precedente |
| `schema` maggiore di quello conosciuto | rifiuto in blocco. Non si interpreta a metà quello che si capisce solo in parte |
| `revisione` minore o uguale a quella già applicata | ignorato, senza rumore |
| **Payload vuoto** (messaggio ritenuto cancellato) | il lettore **smette di seguire e tiene quello che ha**. Non cancella niente |
| `tipo` sconosciuto | quel dispositivo **saltato**, gli altri applicati |
| `uuid` mancante, vuoto o duplicato | quel dispositivo saltato |
| `topic_stato` mancante o vuoto | quel dispositivo saltato |
| Campo sconosciuto dentro un dispositivo valido | **ignorato** |
| Campo noto di tipo sbagliato | si usa il predefinito, se ne ha uno; altrimenti il dispositivo è saltato |

Sotto c'è una regola sola, ed è la stessa che l'app applica già ai payload di disponibilità:
**da un valore che non si è capito non si deduce niente.** Un registro incomprensibile non
è un registro vuoto, ed è la differenza fra un errore di battitura e una casa senza
dispositivi.

Il rifiuto è **in blocco** per la testata e **per dispositivo** per il contenuto. La ragione
è che un `schema` sbagliato mette in dubbio ogni campo del documento, mentre un `tipo` che
non si conosce mette in dubbio un dispositivo solo: buttare gli altri sei sarebbe una
punizione senza motivo.

## Cancellare il registro

```bash
mosquitto_pub -h <broker> -u <utente> -P <password> -t casa/registro/dispositivi -r -n
```

Le app **smettono di seguire e tengono i dispositivi che hanno**, e il modulo di
registrazione torna scrivibile. È la scelta prudente: un ritenuto si cancella con un comando
solo, e la lettura opposta — registro cancellato uguale casa senza dispositivi — farebbe di
quel comando un disastro.

## Far evolvere lo schema

**Aggiungere un campo è additivo e non richiede di alzare `schema`**: un lettore vecchio
ignora quello che non conosce, un lettore nuovo trova il predefinito quando il campo non
c'è. È così che `stanza` potrà tornare.

`schema` si alza solo quando qualcosa **si rompe**: un campo che cambia significato, un
obbligo nuovo, un tipo diverso. Quando succede, il lettore nuovo deve saper leggere anche il
formato precedente, perché sul broker c'è un messaggio ritenuto solo e lo scrittore potrebbe
essere più vecchio di chi legge.
