#!/bin/bash
# Installa l'APK debug su tutti i dispositivi connessi e apre l'app.
# Uso: ./install-all.sh [--build] [--no-open]
#   --build    compila prima di installare
#   --no-open  installa soltanto, senza portare l'app in primo piano
#
# Uscita: 0 fatto, 1 qualcosa e' andato storto, 2 nessun dispositivo collegato.
# Il 2 sta a parte perche' non e' un errore: chi compila senza telefono attaccato
# non ha sbagliato niente, e build.sh lo tratta come un avviso.

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"

# Il pacchetto della debug, suffisso compreso (vedi applicationIdSuffix in
# app/build.gradle.kts): le due installazioni convivono sullo stesso telefono, e
# aprire quella sbagliata vorrebbe dire guardare una build di ieri.
PACKAGE="it.agoldoni.smarthome.debug"

BUILD=0
OPEN=1
for arg in "$@"; do
    case "$arg" in
        --build)   BUILD=1 ;;
        --no-open) OPEN=0 ;;
        *) echo "[ERRORE] Argomento sconosciuto: $arg"; exit 1 ;;
    esac
done

# adb sta nel PATH solo se qualcuno ce l'ha messo: il posto dove si trova
# sempre e' l'SDK, lo stesso che usa build.sh.
ADB="${ADB:-$(command -v adb || true)}"
if [ -z "$ADB" ]; then
    ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
fi
if [ ! -x "$ADB" ]; then
    echo "[ERRORE] adb non trovato: ne' nel PATH ne' in ${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools."
    exit 1
fi

# Build opzionale (build.sh seleziona la JDK corretta via SDKMAN). NO_INSTALL
# perche' l'installazione la fa gia' questo script: senza, si installerebbe due
# volte, una per parte.
if [ "$BUILD" = "1" ]; then
    echo "=== Build debug ==="
    if ! NO_INSTALL=1 "$PROJECT_DIR/build.sh" debug; then
        echo "Build fallita!"
        exit 1
    fi
fi

if [ ! -f "$APK" ]; then
    echo "APK non trovato. Esegui prima: ./build.sh debug"
    exit 1
fi

# Trova tutti i dispositivi connessi
DEVICES=$("$ADB" devices 2>/dev/null | grep -E "device$" | awk '{print $1}')

if [ -z "$DEVICES" ]; then
    echo "Nessun dispositivo connesso (o nessuno che abbia autorizzato il debug USB)."
    exit 2
fi

# Installa e apre su un dispositivo. L'attivita' non e' scritta qui dentro: la
# chiede al telefono, cosi' un rename nel manifest non lascia indietro uno
# script che punta a una classe che non esiste piu'.
installa_e_apri() {
    local device="$1"
    echo "=== $device: installazione ==="
    if ! "$ADB" -s "$device" install -r "$APK"; then
        echo "=== $device: installazione FALLITA ==="
        return 1
    fi
    [ "$OPEN" = "1" ] || return 0

    local componente
    componente=$("$ADB" -s "$device" shell cmd package resolve-activity --brief "$PACKAGE" 2>/dev/null | tail -n 1 | tr -d '\r')
    if [ -z "$componente" ] || [ "${componente#*/}" = "$componente" ]; then
        echo "=== $device: installata, ma l'attivita' da aprire non si trova ==="
        return 1
    fi
    echo "=== $device: apro $componente ==="
    if ! "$ADB" -s "$device" shell am start -n "$componente" > /dev/null; then
        echo "=== $device: l'app non si e' aperta ==="
        return 1
    fi
}

# Tutti i dispositivi in parallelo, ma ognuno con il suo esito: un'installazione
# fallita in mezzo ad altre riuscite deve farsi sentire, non sparire nel wait.
PIDS=()
for DEVICE in $DEVICES; do
    installa_e_apri "$DEVICE" &
    PIDS+=($!)
done

ESITO=0
for pid in "${PIDS[@]}"; do
    wait "$pid" || ESITO=1
done

if [ "$ESITO" = "0" ]; then
    echo "=== Done ==="
else
    echo "=== Done, ma con errori (sopra) ==="
fi
exit "$ESITO"
