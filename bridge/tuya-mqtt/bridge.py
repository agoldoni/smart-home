#!/usr/bin/env python3
"""Ponte fra le prese Tuya in LAN e il broker MQTT di casa.

Ogni presa diventa tre topic:

    casa/<nome>/stato          JSON pubblicato dal ponte, ritenuto
    casa/<nome>/comando        ON / OFF pubblicato dall'app
    casa/<nome>/disponibilita  online / offline, ritenuto

che e' esattamente quello che l'app Android sa gia' leggere: nel modulo di
registrazione basta indicare "stato" come campo JSON dello stato.

Il ponte non si fida degli indirizzi IP scritti a mano: ascolta gli annunci
UDP che i Tuya spargono in broadcast ogni pochi secondi e tiene aggiornata la
mappa id -> indirizzo. Un rinnovo DHCP non lo mette quindi fuori uso.
"""

import hashlib
import json
import logging
import os
import socket
import select
import threading
import time

import paho.mqtt.client as mqtt
import tinytuya
import yaml
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

CONFIG = os.environ.get("CONFIG", "/app/dispositivi.yaml")

# Chiave con cui Tuya cifra gli annunci in broadcast: e' la stessa per tutti i
# dispositivi del mondo, non e' un segreto del singolo apparecchio.
UDP_KEY = hashlib.md5(b"yGAdlopoPVldABfn").digest()

log = logging.getLogger("ponte")


# --------------------------------------------------------------------------
# scoperta in broadcast
# --------------------------------------------------------------------------

class Scoperta(threading.Thread):
    """Tiene aggiornata la mappa id dispositivo -> (ip, versione protocollo)."""

    daemon = True

    def __init__(self):
        super().__init__(name="scoperta")
        self._mappa = {}
        self._lock = threading.Lock()

    def indirizzo(self, dev_id):
        with self._lock:
            voce = self._mappa.get(dev_id)
            return voce[0] if voce else None

    def versione(self, dev_id):
        with self._lock:
            voce = self._mappa.get(dev_id)
            return voce[1] if voce else None

    def noti(self):
        with self._lock:
            return dict(self._mappa)

    @staticmethod
    def _decodifica(dato):
        # I 3.1 mandano JSON in chiaro, i 3.3+ lo cifrano in AES-ECB.
        inizio, fine = dato.find(b"{"), dato.rfind(b"}")
        if 0 <= inizio < fine:
            try:
                return json.loads(dato[inizio:fine + 1])
            except ValueError:
                pass
        try:
            corpo = dato[20:-8]
            d = Cipher(algorithms.AES(UDP_KEY), modes.ECB()).decryptor()
            chiaro = d.update(corpo) + d.finalize()
            return json.loads(chiaro[:-chiaro[-1]].decode())
        except Exception:
            return None

    def run(self):
        prese = []
        for porta in (6666, 6667):
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                s.bind(("", porta))
                prese.append(s)
            except OSError as e:
                log.warning("non riesco ad ascoltare su UDP %s: %s", porta, e)
        if not prese:
            log.error("nessuna porta di scoperta disponibile: "
                      "il ponte usera' solo gli IP scritti in configurazione")
            return

        log.info("scoperta in ascolto su UDP 6666 e 6667")
        while True:
            pronte, _, _ = select.select(prese, [], [], 5)
            for s in pronte:
                try:
                    dato, mittente = s.recvfrom(4096)
                except OSError:
                    continue
                annuncio = self._decodifica(dato)
                if not annuncio or "gwId" not in annuncio:
                    continue
                dev_id = annuncio["gwId"]
                ip = annuncio.get("ip", mittente[0])
                versione = str(annuncio.get("version", "3.3"))
                with self._lock:
                    precedente = self._mappa.get(dev_id)
                    self._mappa[dev_id] = (ip, versione)
                if precedente is None:
                    log.info("scoperto %s a %s (protocollo %s)", dev_id, ip, versione)
                elif precedente[0] != ip:
                    log.info("%s si e' spostato da %s a %s", dev_id, precedente[0], ip)


# --------------------------------------------------------------------------
# una presa
# --------------------------------------------------------------------------

class Presa(threading.Thread):

    daemon = True

    def __init__(self, cfg, opzioni, scoperta, mqttc, prefisso, qos):
        super().__init__(name=cfg["nome"])
        self.nome = cfg["nome"]
        self.dev_id = cfg["id"]
        self.chiave = (cfg.get("chiave") or "").strip()
        self.versione_cfg = str(cfg.get("versione", "")).strip()
        self.ip_cfg = (cfg.get("ip") or "").strip()
        self.dp_interruttore = str(cfg.get("dp_interruttore", "1"))
        self.letture = (cfg.get("letture")
                        or opzioni.get("letture_predefinite") or {})
        self.payload_on = str(cfg.get("payload_on", "ON"))
        self.payload_off = str(cfg.get("payload_off", "OFF"))

        self.intervallo = float(opzioni.get("intervallo_polling", 5))
        self.timeout = float(opzioni.get("timeout_socket", 4))
        self.soglia_offline = int(opzioni.get("tentativi_prima_di_offline", 3))
        self.tentativi_socket = int(opzioni.get("tentativi_socket", 3))
        self.validita_comando = float(opzioni.get("validita_comando", 30))
        self.lettura_lenta = float(opzioni.get("soglia_lettura_lenta", 3))

        self.scoperta = scoperta
        self.mqttc = mqttc
        self.qos = qos
        self.base = f"{prefisso}/{self.nome}"
        # Per un interruttore i comandi non fanno coda: vedi _chiedi().
        self._richiesta = None
        self._lock_richiesta = threading.Lock()
        self._sveglia = threading.Event()

        self._dps = {}
        self._ultimo_stato = None
        self._disponibile = None

    # -- topic ------------------------------------------------------------

    @property
    def topic_comando(self):
        return f"{self.base}/comando"

    @property
    def topic_dps(self):
        return f"{self.base}/dps/comando"

    def _pubblica(self, topic, payload, ritenuto=True):
        self.mqttc.publish(topic, payload, qos=self.qos, retain=ritenuto)

    def _segnala_disponibilita(self, disponibile):
        if disponibile != self._disponibile:
            self._disponibile = disponibile
            self._pubblica(f"{self.base}/disponibilita",
                           "online" if disponibile else "offline")

    # -- traduzione dps -> payload ----------------------------------------

    def _payload_stato(self, dps, ip, versione):
        acceso = dps.get(self.dp_interruttore)
        corpo = {
            "stato": self.payload_on if acceso else self.payload_off,
            "ip": ip,
            "versione": versione,
            "dps": dps,
        }
        for nome, spec in self.letture.items():
            grezzo = dps.get(str(spec["dp"]))
            if isinstance(grezzo, (int, float)):
                corpo[nome] = grezzo / float(spec.get("scala", 1))
        return json.dumps(corpo, separators=(",", ":"))

    # -- ciclo di vita ----------------------------------------------------

    def _leggi(self, d):
        """Una lettura, col cronometro.

        Il tempo va misurato proprio qui, perche' e' l'unico punto dove i
        secondi si perdono senza lasciare traccia. Una lettura a vuoto costa
        quanto il timeout moltiplicato per i tentativi del socket; il contatore
        dei fallimenti si azzera al primo successo; e finche' non ne arrivano
        tre di fila non viene registrato niente. Il ciclo puo' quindi restare
        lento all'infinito senza mai superare la soglia che lo direbbe, ed e'
        esattamente com'e' andata: 79 secondi fra la pressione del pulsante e
        la pubblicazione, con il log muto.
        """
        inizio = time.monotonic()
        risposta = d.status()
        durata = time.monotonic() - inizio
        livello = logging.WARNING if durata > self.lettura_lenta else logging.DEBUG
        log.log(livello, "%s: lettura in %.1fs", self.nome, durata)
        return risposta

    def _crea_dispositivo(self):
        ip = self.scoperta.indirizzo(self.dev_id) or self.ip_cfg
        if not ip:
            return None, None, None
        versione = (self.scoperta.versione(self.dev_id)
                    or self.versione_cfg or "3.3")
        d = tinytuya.OutletDevice(self.dev_id, ip, self.chiave)
        d.set_version(float(versione))
        d.set_socketPersistent(True)
        d.set_socketTimeout(self.timeout)
        d.set_socketRetryLimit(self.tentativi_socket)
        return d, ip, versione

    def _applica(self, d, comando):
        tipo, valore = comando
        if tipo == "interruttore":
            return d.set_value(self.dp_interruttore, valore, nowait=False)
        if tipo == "dps":
            return d.set_multiple_values({str(k): v for k, v in valore.items()},
                                         nowait=False)
        return None

    def run(self):
        if not self.chiave:
            log.error("%s: manca la chiave locale, il dispositivo resta fermo. "
                      "Vedi README, sezione 'Estrarre le chiavi locali'.", self.nome)
            self._segnala_disponibilita(False)
            return

        attesa = 2.0
        while True:
            d, ip, versione = self._crea_dispositivo()
            if d is None:
                log.warning("%s: non ancora visto in rete, riprovo", self.nome)
                self._segnala_disponibilita(False)
                time.sleep(10)
                continue

            log.info("%s: mi collego a %s (protocollo %s)", self.nome, ip, versione)
            self._dps = {}
            self._ultimo_stato = None
            fallimenti = 0
            try:
                while True:
                    # I comandi si eseguono qui e non nel thread MQTT: il
                    # socket verso la presa non e' thread safe. Ne esiste al
                    # massimo uno in attesa, ed e' sempre il piu' recente.
                    risposta = None
                    richiesta = self._richiesta_corrente()
                    if richiesta is not None:
                        comando, scadenza = richiesta
                        if time.time() > scadenza:
                            log.warning("%s: comando %s mai passato in %.0fs, "
                                        "lo lascio cadere", self.nome, comando,
                                        self.validita_comando)
                            self._consuma(richiesta)
                        else:
                            risposta = self._applica(d, comando)
                            if isinstance(risposta, dict) and "dps" in risposta:
                                self._consuma(richiesta)
                            else:
                                log.warning("%s: comando %s non passato (%s), riprovo",
                                            self.nome, comando, str(risposta)[:70])

                    if not isinstance(risposta, dict) or "dps" not in risposta:
                        risposta = self._leggi(d)

                    if not isinstance(risposta, dict) or "dps" not in risposta:
                        fallimenti += 1
                        if fallimenti == 1:
                            log.debug("%s: risposta inattesa %s", self.nome, risposta)
                        if fallimenti >= self.soglia_offline:
                            raise ConnectionError(f"{fallimenti} tentativi a vuoto")
                        time.sleep(1)
                        continue

                    fallimenti = 0
                    attesa = 2.0
                    self._segnala_disponibilita(True)

                    # Le prese mandano anche aggiornamenti parziali, con il
                    # solo dp che e' cambiato. Vanno fusi con quel che si sa
                    # gia': pubblicare il frammento da solo significherebbe
                    # dedurre "spento" dall'assenza del dp dell'interruttore,
                    # che e' esattamente la supposizione che sull'app si paga.
                    self._dps.update(risposta["dps"])
                    if self.dp_interruttore not in self._dps:
                        log.debug("%s: stato ancora parziale, aspetto", self.nome)
                        continue
                    payload = self._payload_stato(self._dps, ip, versione)
                    if payload != self._ultimo_stato:
                        self._ultimo_stato = payload
                        self._pubblica(f"{self.base}/stato", payload)

                    # Un comando arrivato mentre dormivamo sveglia il ciclo.
                    self._sveglia.wait(self.intervallo)
                    self._sveglia.clear()
            except Exception as e:
                log.warning("%s: collegamento perso (%s), riprovo fra %.0fs",
                            self.nome, e, attesa)
                self._segnala_disponibilita(False)
                try:
                    d.close()
                except Exception:
                    pass
                time.sleep(attesa)
                attesa = min(attesa * 2, 60)

    # -- ingresso dal thread MQTT -----------------------------------------

    def _chiedi(self, comando):
        """Registra l'ultima volonta' dell'utente, sostituendo la precedente.

        Una coda qui sarebbe la struttura sbagliata. Se la presa e'
        irraggiungibile e qualcuno tocca l'interruttore cinque volte, alla
        riconnessione il relay commuterebbe cinque volte inseguendo comandi
        ormai vecchi, con l'utente a guardare la presa che sbatte. Di una fila
        di accensioni e spegnimenti conta solo l'ultima.
        """
        with self._lock_richiesta:
            precedente = self._richiesta
            self._richiesta = (comando, time.time() + self.validita_comando)
        if precedente is not None:
            log.info("%s: %s sostituisce %s, mai passato",
                     self.nome, comando, precedente[0])
        self._sveglia.set()

    def _richiesta_corrente(self):
        with self._lock_richiesta:
            return self._richiesta

    def _consuma(self, richiesta):
        # Solo se nel frattempo non ne e' arrivata una piu' recente.
        with self._lock_richiesta:
            if self._richiesta is richiesta:
                self._richiesta = None

    def accoda_interruttore(self, payload):
        testo = payload.strip().upper()
        if testo in (self.payload_on.upper(), "1", "TRUE"):
            self._chiedi(("interruttore", True))
        elif testo in (self.payload_off.upper(), "0", "FALSE"):
            self._chiedi(("interruttore", False))
        elif testo in ("TOGGLE", "INVERTI"):
            corrente = json.loads(self._ultimo_stato or "{}").get("stato")
            self._chiedi(("interruttore", corrente != self.payload_on))
        else:
            log.warning("%s: comando non riconosciuto %r", self.nome, payload)

    def accoda_dps(self, payload):
        try:
            self._chiedi(("dps", json.loads(payload)))
        except ValueError:
            log.warning("%s: dps non in JSON: %r", self.nome, payload)


# --------------------------------------------------------------------------
# avvio
# --------------------------------------------------------------------------

def carica_config():
    with open(CONFIG, encoding="utf-8") as f:
        cfg = yaml.safe_load(f) or {}
    if not cfg.get("dispositivi"):
        raise SystemExit(f"{CONFIG}: nessun dispositivo configurato")
    return cfg


def main():
    logging.basicConfig(
        level=os.environ.get("LIVELLO_LOG", "INFO").upper(),
        format="%(asctime)s %(levelname)-7s %(name)-12s %(message)s",
        datefmt="%H:%M:%S",
    )
    logging.getLogger("tinytuya").setLevel(logging.WARNING)

    cfg = carica_config()
    prefisso = cfg.get("mqtt", {}).get("prefisso", "casa")
    qos = int(cfg.get("mqtt", {}).get("qos", 1))
    opzioni = cfg.get("tuya", {})

    scoperta = Scoperta()
    scoperta.start()

    mqttc = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                        client_id=f"ponte-tuya-{os.getpid()}")
    utente = os.environ.get("MQTT_USER")
    if utente:
        mqttc.username_pw_set(utente, os.environ.get("MQTT_PASS", ""))
    mqttc.will_set(f"{prefisso}/ponte/stato", "offline", qos=qos, retain=True)

    prese = {}
    for voce in cfg["dispositivi"]:
        p = Presa(voce, opzioni, scoperta, mqttc, prefisso, qos)
        prese[p.nome] = p

    def on_connect(client, _userdata, _flags, motivo, _props=None):
        if motivo != 0:
            log.error("broker: connessione rifiutata (%s)", motivo)
            return
        log.info("broker: collegato")
        client.publish(f"{prefisso}/ponte/stato", "online", qos=qos, retain=True)
        for p in prese.values():
            client.subscribe(p.topic_comando, qos=qos)
            client.subscribe(p.topic_dps, qos=qos)

    def on_message(_client, _userdata, msg):
        payload = msg.payload.decode(errors="replace")
        for p in prese.values():
            if msg.topic == p.topic_comando:
                log.info("%s: comando %s", p.nome, payload)
                p.accoda_interruttore(payload)
            elif msg.topic == p.topic_dps:
                log.info("%s: dps %s", p.nome, payload)
                p.accoda_dps(payload)

    mqttc.on_connect = on_connect
    mqttc.on_message = on_message

    host = os.environ.get("MQTT_HOST", "127.0.0.1")
    porta = int(os.environ.get("MQTT_PORT", "1883"))
    log.info("ponte per %d dispositivi, broker %s:%d, prefisso %s/",
             len(prese), host, porta, prefisso)
    mqttc.connect_async(host, porta, keepalive=60)
    mqttc.loop_start()

    for p in prese.values():
        p.start()

    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass
    finally:
        mqttc.publish(f"{prefisso}/ponte/stato", "offline", qos=qos, retain=True)
        mqttc.loop_stop()


if __name__ == "__main__":
    main()
