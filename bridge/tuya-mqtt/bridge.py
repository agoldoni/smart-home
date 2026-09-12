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
import sqlite3
import threading
import time
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

import paho.mqtt.client as mqtt
import tinytuya
import yaml
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

CONFIG = os.environ.get("CONFIG", "/app/dispositivi.yaml")
STATO = os.environ.get("STATO", "/app/stato")

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
# energia: accumulo e archivio
# --------------------------------------------------------------------------

SCHEMA = """
CREATE TABLE IF NOT EXISTS energia_grezza (
    presa      TEXT    NOT NULL,
    inizio_utc TEXT    NOT NULL,
    giorno     TEXT    NOT NULL,
    ora        INTEGER NOT NULL,
    grezzo     REAL    NOT NULL,
    sorgente   TEXT    NOT NULL,
    copertura  REAL    NOT NULL,
    PRIMARY KEY (presa, inizio_utc)
);
CREATE INDEX IF NOT EXISTS energia_per_giorno ON energia_grezza (giorno, presa);

CREATE TABLE IF NOT EXISTS fattori (
    presa        TEXT NOT NULL,
    sorgente     TEXT NOT NULL,
    wh_per_unita REAL NOT NULL,
    PRIMARY KEY (presa, sorgente)
);

CREATE VIEW IF NOT EXISTS energia AS
SELECT g.presa, g.inizio_utc, g.giorno, g.ora, g.copertura, g.sorgente, g.grezzo,
       g.grezzo * f.wh_per_unita / 1000.0 AS kwh
  FROM energia_grezza g
  JOIN fattori f ON f.presa = g.presa AND f.sorgente = g.sorgente;
"""


class Archivio:
    """Le righe orarie su file, e il fattore che le rende energia.

    Nell'archivio finisce solo quello che la presa ha contato: le tacche, non i
    kWh. Quanto valga una tacca e' un'interpretazione, sta in una riga a parte, e
    la vista `energia` fa la moltiplicazione quando qualcuno guarda. Cosi' una
    taratura fatta fra sei mesi aggiusta anche i sei mesi passati cambiando una
    riga, invece di riscriverne cinquantamila — e non puo' riuscire a meta'.
    """

    def __init__(self, percorso):
        self._lock = threading.Lock()
        self._db = sqlite3.connect(percorso, check_same_thread=False)
        # WAL perche' sqlite3 da riga di comando possa leggere mentre il ponte
        # scrive: l'archivio si guarda soprattutto mentre il sistema e' vivo.
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.executescript(SCHEMA)
        self._db.commit()

    def dichiara_fattore(self, presa, sorgente, wh_per_unita):
        """La configurazione e' la verita': all'avvio riallinea la riga."""
        with self._lock:
            self._db.execute(
                "INSERT INTO fattori (presa, sorgente, wh_per_unita) VALUES (?, ?, ?) "
                "ON CONFLICT(presa, sorgente) DO UPDATE SET wh_per_unita = excluded.wh_per_unita",
                (presa, sorgente, wh_per_unita))
            self._db.commit()

    def scrivi(self, riga):
        with self._lock:
            self._db.execute(
                "INSERT INTO energia_grezza "
                "(presa, inizio_utc, giorno, ora, grezzo, sorgente, copertura) "
                "VALUES (?, ?, ?, ?, ?, ?, ?) "
                "ON CONFLICT(presa, inizio_utc) DO UPDATE SET "
                "grezzo = excluded.grezzo, sorgente = excluded.sorgente, "
                "copertura = excluded.copertura",
                riga)
            self._db.commit()

    def totale(self, presa, prefisso):
        """kWh in archivio per un giorno ('2026-09-12') o un mese ('2026-09')."""
        with self._lock:
            somma, = self._db.execute(
                "SELECT COALESCE(SUM(kwh), 0) FROM energia WHERE presa = ? AND giorno LIKE ?",
                (presa, prefisso + "%")).fetchone()
        return round(somma, 4)

    def copertura(self, presa, prefisso):
        with self._lock:
            media, = self._db.execute(
                "SELECT AVG(copertura) FROM energia_grezza WHERE presa = ? AND giorno LIKE ?",
                (presa, prefisso + "%")).fetchone()
        return round(media, 3) if media is not None else None


class Contatore:
    """L'energia di una presa, ora per ora.

    Conta dal dp cumulativo della presa — che e' l'integrazione fatta da lei,
    con le sue misure vere — e non dall'integrale della potenza che pubblichiamo
    noi: quella resta ferma anche venti minuti, e integrarla sarebbe integrare un
    numero vecchio. Il ripiego sull'integrale c'e' solo per le prese che il dp
    cumulativo non ce l'hanno.

    Il contatore della presa si azzera da solo (il cloud Tuya lo legge e lo
    riporta a zero): un valore piu' basso del precedente non e' un errore, e' un
    azzeramento, e quello che si e' letto e' tutto quel che ha contato da li'.
    """

    def __init__(self, nome, dp="17", wh_per_tacca=1.0, fuso="Europe/Rome",
                 buco_massimo=3600, pausa_massima=30):
        self.nome = nome
        self.dp = str(dp) if dp else None
        self.wh_per_unita = float(wh_per_tacca)
        self.fuso = ZoneInfo(fuso)
        # Oltre un'ora di silenzio la lettura fa da riferimento e non si somma:
        # l'ora e' il periodo della riga, e scaricare tre giorni di consumi
        # dentro la riga corrente sarebbe un numero falso in un posto preciso.
        self.buco_massimo = buco_massimo
        # Due letture piu' distanti di cosi' non fanno tempo coperto: la presa
        # c'era anche allora, ma noi non la guardavamo.
        self.pausa_massima = pausa_massima

        self.sorgente = None
        self._ora = None          # inizio dell'ora in corso, epoch
        self._grezzo = 0.0
        self._coperti = 0.0
        self._ultimo_valore = None
        self._ultimo_istante = None

    # -- lettura ----------------------------------------------------------

    @staticmethod
    def _inizio_ora(istante):
        return istante - (istante % 3600)

    def aggiorna(self, adesso, dps=None, potenza_w=None):
        """Una lettura (o un buco, con dps a None). Restituisce le righe chiuse."""
        if self._ora is None:
            self._ora = self._inizio_ora(adesso)
        righe = self._chiudi_fino_a(adesso)

        if dps is None:
            return righe

        # L'orologio di sistema puo' saltare (NTP, il PC che si risveglia). Un
        # intervallo negativo non esiste e uno enorme e' un buco: in entrambi i
        # casi non si somma tempo coperto.
        intervallo = None
        if self._ultimo_istante is not None:
            intervallo = adesso - self._ultimo_istante
            if intervallo < 0:
                intervallo = None

        valore = dps.get(self.dp) if self.dp else None
        if isinstance(valore, (int, float)) and not isinstance(valore, bool):
            self.sorgente = "dp" + self.dp
            if (self._ultimo_valore is not None and intervallo is not None
                    and intervallo <= self.buco_massimo):
                if valore >= self._ultimo_valore:
                    self._somma(valore - self._ultimo_valore, intervallo)
                else:
                    self._somma(valore, intervallo)
            else:
                self._copri(intervallo)
            self._ultimo_valore = valore
        elif self.sorgente is None or self.sorgente == "integrale":
            # Nessun contatore: si integra la potenza, e la riga lo dichiara.
            if potenza_w is not None:
                self.sorgente = "integrale"
                if intervallo is not None and intervallo <= self.pausa_massima:
                    self._somma(potenza_w * intervallo / 3600.0, intervallo)

        self._ultimo_istante = adesso
        return righe

    def _somma(self, quantita, intervallo):
        if quantita > 0:
            self._grezzo += quantita
        self._copri(intervallo)

    def _copri(self, intervallo):
        if intervallo is not None and intervallo <= self.pausa_massima:
            self._coperti += intervallo

    # -- chiusura dell'ora ------------------------------------------------

    def _chiudi_fino_a(self, adesso):
        righe = []
        while adesso >= self._ora + 3600:
            righe.append(self.riga())
            self._ora += 3600
            self._grezzo = 0.0
            self._coperti = 0.0
        return righe

    def riga(self):
        # Prima della prima lettura l'ora in corso e' semplicemente questa: una
        # riga vuota, non un errore. Chiamarla e' lecito da chiunque.
        ora = self._ora if self._ora is not None else self._inizio_ora(time.time())
        inizio = datetime.fromtimestamp(ora, timezone.utc)
        locale = inizio.astimezone(self.fuso)
        return (
            self.nome,
            inizio.strftime("%Y-%m-%dT%H:%M:%SZ"),
            locale.strftime("%Y-%m-%d"),
            locale.hour,
            round(self._grezzo, 4),
            self.sorgente or "ignota",
            round(min(self._coperti / 3600.0, 1.0), 3),
        )

    # -- quel che si pubblica ---------------------------------------------

    @property
    def kwh_parziale(self):
        """L'ora in corso, non ancora in archivio."""
        return self._grezzo * self.wh_per_unita / 1000.0

    def oggi(self):
        if self._ora is None:
            return None, None
        locale = datetime.fromtimestamp(self._ora, timezone.utc).astimezone(self.fuso)
        return locale.strftime("%Y-%m-%d"), locale.strftime("%Y-%m")

    # -- memoria fra un avvio e l'altro -----------------------------------

    def stato(self):
        return {
            "ora": self._ora,
            "grezzo": self._grezzo,
            "coperti": self._coperti,
            "ultimo_valore": self._ultimo_valore,
            "sorgente": self.sorgente,
        }

    def riprendi(self, stato):
        """Riparte da dove era. Un'ora vecchia non si riprende: si riparte puliti."""
        if not stato or stato.get("ora") is None:
            return
        if time.time() - stato["ora"] >= 3600:
            return
        self._ora = stato["ora"]
        self._grezzo = float(stato.get("grezzo") or 0.0)
        self._coperti = float(stato.get("coperti") or 0.0)
        self._ultimo_valore = stato.get("ultimo_valore")
        self.sorgente = stato.get("sorgente")


# --------------------------------------------------------------------------
# una presa
# --------------------------------------------------------------------------

class Presa(threading.Thread):

    daemon = True

    def __init__(self, cfg, opzioni, scoperta, mqttc, prefisso, qos,
                 contatore=None, archivio=None):
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

        # L'energia: il contatore tiene l'ora in corso, l'archivio le ore
        # chiuse. I due totali dell'archivio si rileggono solo quando un'ora si
        # chiude, non a ogni giro: sono una query, e qui si passa ogni 2 secondi.
        self.contatore = contatore
        self.archivio = archivio
        self._ultima_energia = None
        self._archivio_giorno = 0.0
        self._archivio_mese = 0.0
        self._copertura = None

    # -- topic ------------------------------------------------------------

    @property
    def topic_comando(self):
        return f"{self.base}/comando"

    @property
    def topic_dps(self):
        return f"{self.base}/dps/comando"

    @property
    def topic_energia(self):
        return f"{self.base}/energia"

    def _pubblica(self, topic, payload, ritenuto=True):
        self.mqttc.publish(topic, payload, qos=self.qos, retain=ritenuto)

    def _segnala_disponibilita(self, disponibile):
        if disponibile != self._disponibile:
            self._disponibile = disponibile
            self._pubblica(f"{self.base}/disponibilita",
                           "online" if disponibile else "offline")

    # -- traduzione dps -> payload ----------------------------------------

    def _lettura(self, nome, dps):
        spec = self.letture.get(nome)
        if not spec:
            return None
        grezzo = dps.get(str(spec["dp"]))
        if not isinstance(grezzo, (int, float)) or isinstance(grezzo, bool):
            return None
        return grezzo / float(spec.get("scala", 1))

    def _payload_stato(self, dps, ip, versione):
        acceso = dps.get(self.dp_interruttore)
        corpo = {
            "stato": self.payload_on if acceso else self.payload_off,
            "ip": ip,
            "versione": versione,
            "dps": dps,
        }
        for nome in self.letture:
            valore = self._lettura(nome, dps)
            if valore is not None:
                corpo[nome] = valore
        return json.dumps(corpo, separators=(",", ":"))

    # -- energia ----------------------------------------------------------

    def conta(self, dps):
        """Una lettura al contatore, o un buco se dps e' None."""
        if self.contatore is None:
            return
        potenza = self._lettura("potenza_w", dps) if dps is not None else None
        for riga in self.contatore.aggiorna(time.time(), dps, potenza):
            _, inizio, giorno, ora, grezzo, sorgente, copertura = riga
            if self.archivio is not None:
                self.archivio.scrivi(riga)
            log.info("%s: %s ore %02d, %.1f %s, copertura %.0f%%",
                     self.nome, giorno, ora, grezzo,
                     "tacche" if sorgente.startswith("dp") else "Wh",
                     copertura * 100)
            self._rileggi_totali()
        self._pubblica_energia()

    def _rileggi_totali(self):
        giorno, mese = self.contatore.oggi()
        if giorno is None or self.archivio is None:
            return
        self._archivio_giorno = self.archivio.totale(self.nome, giorno)
        self._archivio_mese = self.archivio.totale(self.nome, mese)
        self._copertura = self.archivio.copertura(self.nome, giorno)

    def _pubblica_energia(self):
        giorno, mese = self.contatore.oggi()
        if giorno is None:
            return
        parziale = self.contatore.kwh_parziale
        corpo = {
            "kwh_oggi": round(self._archivio_giorno + parziale, 3),
            "kwh_mese": round(self._archivio_mese + parziale, 3),
            "giorno": giorno,
            "mese": mese,
            "sorgente": self.contatore.sorgente or "ignota",
        }
        if self._copertura is not None:
            corpo["copertura_oggi"] = self._copertura
        payload = json.dumps(corpo, separators=(",", ":"))
        if payload != self._ultima_energia:
            self._ultima_energia = payload
            self._pubblica(self.topic_energia, payload)

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
                # Anche senza letture le ore vanno chiuse: una presa sparita per
                # mezza giornata deve lasciare righe scoperte, non un vuoto che
                # non si distingue da "non ancora scritto".
                self.conta(None)
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
                        self.conta(None)
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
                    self.conta(self._dps)
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


class Memoria(threading.Thread):
    """L'ora in corso di tutte le prese, su file, una volta al minuto.

    Serve a una cosa sola: un riavvio a meta' ora non deve buttare via quello
    che si era gia' contato. Si perde al massimo un minuto, che e' il prezzo di
    non scrivere su disco ogni due secondi per sette prese. Le ore chiuse non
    passano di qui: quelle sono gia' in archivio.
    """

    daemon = True

    def __init__(self, percorso, prese, intervallo=60):
        super().__init__(name="memoria")
        self.percorso = percorso
        self.prese = prese
        self.intervallo = intervallo

    def riprendi(self):
        try:
            with open(self.percorso, encoding="utf-8") as f:
                salvato = json.load(f)
        except FileNotFoundError:
            return
        except Exception as e:
            log.warning("memoria illeggibile (%s): l'ora in corso riparte da zero", e)
            return
        for nome, presa in self.prese.items():
            if presa.contatore is not None:
                presa.contatore.riprendi(salvato.get(nome))
                presa._rileggi_totali()

    def salva(self):
        corpo = {nome: p.contatore.stato()
                 for nome, p in self.prese.items() if p.contatore is not None}
        # Scrittura in due tempi: un'interruzione a meta' lascia il file vecchio
        # intero invece di uno nuovo troncato.
        temporaneo = self.percorso + ".nuovo"
        try:
            with open(temporaneo, "w", encoding="utf-8") as f:
                json.dump(corpo, f)
            os.replace(temporaneo, self.percorso)
        except Exception as e:
            log.warning("memoria non scritta (%s)", e)

    def run(self):
        while True:
            time.sleep(self.intervallo)
            self.salva()


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
    energia = opzioni.get("energia") or {}

    archivio = None
    if energia.get("attiva", True):
        percorso = os.path.join(STATO, energia.get("archivio", "energia.db"))
        try:
            os.makedirs(STATO, exist_ok=True)
            archivio = Archivio(percorso)
            log.info("archivio dell'energia in %s", percorso)
        except Exception as e:
            # Non e' un motivo per non accendere le luci: il ponte continua a
            # fare il ponte, e si limita a non registrare i consumi.
            log.error("archivio non disponibile (%s): l'energia non viene registrata", e)

    scoperta = Scoperta()
    scoperta.start()

    mqttc = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                        client_id=f"ponte-tuya-{os.getpid()}")
    utente = os.environ.get("MQTT_USER")
    if utente:
        mqttc.username_pw_set(utente, os.environ.get("MQTT_PASS", ""))
    mqttc.will_set(f"{prefisso}/ponte/stato", "offline", qos=qos, retain=True)

    fuso = energia.get("fuso") or os.environ.get("TZ") or "Europe/Rome"
    dp_contatore = energia.get("dp_contatore", "17")
    wh_per_tacca = float(energia.get("wh_per_tacca", 1.0))

    prese = {}
    for voce in cfg["dispositivi"]:
        contatore = None
        if archivio is not None:
            contatore = Contatore(
                voce["nome"],
                dp=voce.get("dp_contatore", dp_contatore),
                wh_per_tacca=float(voce.get("wh_per_tacca", wh_per_tacca)),
                fuso=fuso,
            )
            # Le due righe di fattori si dichiarano subito, prima di sapere da
            # quale sorgente contera' questa presa: la vista e' un JOIN, e senza
            # la riga giusta le sue ore sparirebbero dalle somme.
            if contatore.dp:
                archivio.dichiara_fattore(contatore.nome, "dp" + contatore.dp,
                                          contatore.wh_per_unita)
            archivio.dichiara_fattore(contatore.nome, "integrale", 1.0)
        p = Presa(voce, opzioni, scoperta, mqttc, prefisso, qos, contatore, archivio)
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
    memoria = None
    if archivio is not None:
        memoria = Memoria(os.path.join(STATO, "energia.json"), prese)
        memoria.riprendi()
        memoria.start()

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
        if memoria is not None:
            memoria.salva()
        mqttc.publish(f"{prefisso}/ponte/stato", "offline", qos=qos, retain=True)
        mqttc.loop_stop()


if __name__ == "__main__":
    main()
