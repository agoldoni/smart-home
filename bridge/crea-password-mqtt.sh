#!/usr/bin/env bash
# Crea il file di password di mosquitto a partire dalle credenziali in .env.
# Da rieseguire se cambi MQTT_USER o MQTT_PASS.
set -euo pipefail
cd "$(dirname "$0")"

[[ -f .env ]] || { echo "manca .env: copialo da .env.example"; exit 1; }
set -a; . ./.env; set +a
: "${MQTT_USER:?MQTT_USER non impostato in .env}"
: "${MQTT_PASS:?MQTT_PASS non impostato in .env}"

mkdir -p mosquitto/config
: > mosquitto/config/passwd

docker run --rm \
  -v "$PWD/mosquitto/config:/config" \
  eclipse-mosquitto:2 \
  mosquitto_passwd -b /config/passwd "$MQTT_USER" "$MQTT_PASS"

# mosquitto rifiuta il file se e' scrivibile da altri
chmod 600 mosquitto/config/passwd
echo "utente '$MQTT_USER' scritto in mosquitto/config/passwd"
