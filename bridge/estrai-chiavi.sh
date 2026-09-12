#!/usr/bin/env bash
# Apre il wizard di tinytuya in un container: chiede le credenziali del
# progetto cloud Tuya e scarica le chiavi locali di tutti i dispositivi
# dell'account. I file finiscono in ./chiavi/ e non escono da qui.
#
# Serve prima un progetto su iot.tuya.com collegato all'account Smart Life:
# la procedura completa e' nel README.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p chiavi

# L'immagine del ponte ha gia' tinytuya dentro; se non e' stata costruita si
# ripiega su un python generico installandolo al volo.
if docker image inspect smart-home/tuya-mqtt:latest >/dev/null 2>&1; then
  IMMAGINE=smart-home/tuya-mqtt:latest
  COMANDO="python -m tinytuya wizard"
else
  IMMAGINE=python:3.12-slim
  COMANDO="pip install --quiet --disable-pip-version-check tinytuya==1.20.0 && python -m tinytuya wizard"
fi

# Il ponte tiene occupate le porte UDP 6666/6667: finche' gira, la scansione
# di verifica del wizard muore con "Address already in use". Si ferma il
# tempo del wizard e si riavvia in ogni caso, anche se qualcosa va storto.
if docker compose ps --status running --services 2>/dev/null | grep -qx ponte; then
  echo "fermo il ponte per lasciare libere le porte di scoperta"
  docker compose stop ponte >/dev/null
  trap 'echo "riavvio il ponte"; docker compose start ponte >/dev/null' EXIT
fi

# --network host: alla fine il wizard interroga le prese in LAN e verifica
# subito che le chiavi appena scaricate funzionino.
docker run --rm -it \
  -v "$PWD/chiavi:/chiavi" -w /chiavi \
  --network host \
  --user "$(id -u):$(id -g)" \
  "$IMMAGINE" \
  sh -c "$COMANDO"

echo
echo "fatto. Ora:  python3 applica-chiavi.py"
