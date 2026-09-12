#!/usr/bin/env python3
"""Trova l'app Smart Home in rete locale e ne legge lo stato interno.

L'app, nella build debug e con l'interruttore "API di debug" acceso, si annuncia
in broadcast UDP sulla porta 8787 e serve qualche GET sulla stessa porta in TCP.
Questo script fa le due cose insieme: scopre l'indirizzo (che il DHCP cambia
quando gli pare) e interroga l'API.

    python3 tools/debug-api.py                 # scopre e stampa lo stato completo
    python3 tools/debug-api.py discover        # solo la scoperta
    python3 tools/debug-api.py health
    python3 tools/debug-api.py registry
    python3 tools/debug-api.py mqtt --limit 100
    python3 tools/debug-api.py log --since 1757600000000
    python3 tools/debug-api.py get /devices
    python3 tools/debug-api.py --host 192.168.86.33 state
    python3 tools/debug-api.py --adb state     # via adb, se il telefono e collegato al PC

L'indirizzo trovato finisce in una cache: le chiamate successive partono subito
e tornano a scoprire solo se quell'indirizzo ha smesso di rispondere.
"""

import argparse
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

SERVICE = "smart-home-debug"
PROBE = b"SMART-HOME-DEBUG?"
DEFAULT_PORT = 8787
CACHE = os.path.expanduser("~/.cache/smart-home/debug-endpoint")

SHORTCUTS = ("state", "health", "info", "broker", "devices", "registry", "mqtt", "log")


def broadcast_targets():
    """Gli indirizzi di broadcast del PC.

    Su questa macchina ci sono decine di bridge Docker: mandare la richiesta al
    solo 255.255.255.255 la fa uscire dalla rotta predefinita e basta, che non e
    per forza quella di casa. Meglio chiedere a `ip` e provarle tutte.
    """
    targets = {"255.255.255.255"}
    try:
        out = subprocess.run(
            ["ip", "-4", "-o", "addr", "show"],
            capture_output=True, text=True, timeout=5,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return sorted(targets)
    for line in out.splitlines():
        parts = line.split()
        if "brd" in parts:
            targets.add(parts[parts.index("brd") + 1])
    return sorted(targets)


def discover(port=DEFAULT_PORT, timeout=7.0, verbose=True):
    """Raccoglie gli annunci e le risposte finche scade il tempo.

    Si ascolta e si chiede allo stesso tempo: la risposta arriva subito se la
    richiesta passa, l'annuncio arriva comunque entro cinque secondi anche se i
    broadcast in uscita si perdono per strada.
    """
    found = {}
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        sock.bind(("", port))
    except OSError as err:
        print("Porta UDP %d non ascoltabile (%s): resto in attesa delle sole risposte."
              % (port, err), file=sys.stderr)
        sock.bind(("", 0))

    for target in broadcast_targets():
        try:
            sock.sendto(PROBE, (target, port))
        except OSError:
            pass

    deadline = time.time() + timeout
    while time.time() < deadline:
        sock.settimeout(max(0.1, deadline - time.time()))
        try:
            data, sender = sock.recvfrom(4096)
        except socket.timeout:
            break
        except OSError:
            break
        if data.strip() == PROBE:
            continue  # la nostra stessa richiesta, tornata indietro
        try:
            info = json.loads(data.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            continue
        if info.get("service") != SERVICE:
            continue
        # L'indirizzo da cui arriva il pacchetto e quello buono: quelli elencati
        # dentro possono comprendere interfacce che da qui non si raggiungono.
        info["host"] = sender[0]
        key = (sender[0], info.get("port", port))
        if key not in found:
            found[key] = info
            if verbose:
                print("trovata %s %s su %s:%s (%s)" % (
                    info.get("package", "?"), info.get("version", "?"),
                    sender[0], info.get("port", port), info.get("model", "?")),
                    file=sys.stderr)
    sock.close()
    return list(found.values())


def fetch(host, port, path, params=None, timeout=5.0):
    query = ""
    if params:
        query = "?" + "&".join("%s=%s" % (k, v) for k, v in params.items() if v is not None)
    url = "http://%s:%d%s%s" % (host, port, path, query)
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def alive(host, port, timeout=1.5):
    try:
        return fetch(host, port, "/health", timeout=timeout).get("ok") is True
    except (urllib.error.URLError, OSError, ValueError):
        return False


def cached():
    try:
        with open(CACHE, encoding="utf-8") as handle:
            host, port = handle.read().strip().split(":")
            return host, int(port)
    except (OSError, ValueError):
        return None


def remember(host, port):
    try:
        os.makedirs(os.path.dirname(CACHE), exist_ok=True)
        with open(CACHE, "w", encoding="utf-8") as handle:
            handle.write("%s:%d" % (host, port))
    except OSError:
        pass


def adb_forward(port):
    """Porta l'API sul loopback del PC. Utile quando il telefono e attaccato via USB."""
    subprocess.run(["adb", "forward", "tcp:%d" % port, "tcp:%d" % port],
                   capture_output=True, check=True)
    return "127.0.0.1", port


def resolve(args):
    if args.host:
        return args.host, args.port
    if args.adb:
        return adb_forward(args.port)
    env = os.environ.get("SMART_HOME_DEBUG_HOST")
    if env:
        host, _, port = env.partition(":")
        return host, int(port) if port else args.port

    previous = cached()
    if previous and alive(*previous):
        return previous

    for found in discover(args.port, args.timeout):
        host, port = found["host"], found.get("port", args.port)
        if alive(host, port):
            remember(host, port)
            return host, port
    return None, None


def main():
    parser = argparse.ArgumentParser(
        description="Scopre l'app Smart Home in rete locale e ne legge lo stato interno.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("command", nargs="?", default="state",
                        help="discover, get, oppure uno fra: " + ", ".join(SHORTCUTS))
    parser.add_argument("path", nargs="?", help="percorso, per il comando get")
    parser.add_argument("--host", help="salta la scoperta e usa questo indirizzo")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--adb", action="store_true",
                        help="passa da adb forward invece che dalla rete")
    parser.add_argument("--timeout", type=float, default=7.0,
                        help="secondi di ascolto durante la scoperta")
    parser.add_argument("--limit", type=int, help="quante voci per gli elenchi")
    parser.add_argument("--since", type=int,
                        help="solo le voci successive a questo istante, in millisecondi")
    args = parser.parse_args()

    if args.command == "discover":
        found = discover(args.port, args.timeout, verbose=False)
        print(json.dumps(found, indent=2, ensure_ascii=False))
        return 0 if found else 1

    if args.command == "get":
        if not args.path:
            parser.error("il comando get vuole un percorso, per esempio: get /devices")
        path = args.path if args.path.startswith("/") else "/" + args.path
    elif args.command in SHORTCUTS:
        path = "/" + args.command
    else:
        parser.error("comando sconosciuto: %s" % args.command)

    host, port = resolve(args)
    if not host:
        print("Nessuna app trovata in rete locale.\n"
              "Controlla che sia la build debug, che sia aperta e che l'interruttore\n"
              "'API di debug' nelle impostazioni sia acceso.", file=sys.stderr)
        return 1

    try:
        body = fetch(host, port, path, {"limit": args.limit, "since": args.since})
    except urllib.error.HTTPError as err:
        print("%s ha risposto %d: %s" % (host, err.code, err.read().decode("utf-8", "replace")),
              file=sys.stderr)
        return 1
    except (urllib.error.URLError, OSError) as err:
        print("%s non risponde: %s" % (host, err), file=sys.stderr)
        return 1

    print(json.dumps(body, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
