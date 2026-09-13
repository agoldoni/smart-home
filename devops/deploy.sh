#!/usr/bin/env bash
# Porta lo stack di casa sul Raspberry.
#
# Costruisce le immagini **per arm64** e le manda a destinazione gia' pronte: il
# PC e' x86_64 e il Pi no, quindi un'immagine costruita in modo normale la'
# sopra non parte proprio — `exec format error`, e non si capisce subito perche'.
#
# Il tag e' lo SHA del commit, e lo stesso SHA finisce in una label OCI dentro
# l'immagine. Da li' in poi `docker image inspect` risponde alla domanda che
# prima non aveva risposta: quale revisione sta girando? Il Pi non e' un
# checkout git e non puo' dirlo in nessun altro modo.
#
# Niente registro: il consumatore e' uno solo. `docker save | ssh docker load`
# non chiede di toccare daemon.json sul Pi e non pretende che questa macchina
# sia accesa perche' il Pi possa ripartire.
set -euo pipefail
cd "$(dirname "$0")/.."

REMOTO="${REMOTO:-raspberry}"
DESTINAZIONE="${DESTINAZIONE:-projects/smart-home/bridge}"
SERVIZI="configuratore tuya-mqtt"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "ATTENZIONE: albero di lavoro sporco. Il tag direbbe una revisione che non"
  echo "corrisponde a quello che stai costruendo."
  read -rp "procedo lo stesso? [s/N] " r
  [[ "$r" == "s" ]] || exit 1
fi

TAG="$(git rev-parse --short HEAD)"
echo "==> revisione $TAG"

for s in $SERVIZI; do
  # La cartella del sorgente non si chiama come l'immagine solo per il ponte.
  case "$s" in
    tuya-mqtt) sorgente=bridge/tuya-mqtt ;;
    *)         sorgente="bridge/$s" ;;
  esac
  echo "==> costruisco smart-home/$s:$TAG per linux/arm64"
  docker buildx build --platform linux/arm64 \
    --label "org.opencontainers.image.revision=$TAG" \
    --label "org.opencontainers.image.source=smart-home/$sorgente" \
    -t "smart-home/$s:$TAG" --load "$sorgente"
done

echo "==> trasferisco le immagini su $REMOTO"
# shellcheck disable=SC2086
docker save $(for s in $SERVIZI; do printf 'smart-home/%s:%s ' "$s" "$TAG"; done) \
  | ssh "$REMOTO" 'docker load'

echo "==> aggiorno la descrizione dello stack"
# L'unico file di progetto che il Pi deve avere aggiornato: dice cosa gira, con
# quali porte e quali volumi. I sorgenti no — quelli sono dentro le immagini.
scp -q bridge/compose.yml "$REMOTO:$DESTINAZIONE/compose.yml"

echo "==> aggiorno IMAGE_TAG e riavvio"
# Il tag va nel .env del Pi e non solo nell'ambiente di questo comando: cosi' un
# `docker compose up -d` dato a mano la' sopra riparte con le stesse immagini,
# invece di lamentarsi di una variabile che non c'e'.
ssh "$REMOTO" "cd $DESTINAZIONE \
  && (grep -q '^IMAGE_TAG=' .env && sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=$TAG/' .env || echo 'IMAGE_TAG=$TAG' >> .env) \
  && docker compose up -d"

echo "==> in funzione:"
ssh "$REMOTO" "cd $DESTINAZIONE && docker compose ps --format '{{.Service}}\t{{.Image}}\t{{.Status}}'"
