#!/bin/sh
# I dispositivi finti dello stack di sviluppo.
#
# Parlano i topic che detta il registro di sviluppo (genera-registro-dev.mjs), e
# i nomi non assomigliano a quelli di casa di proposito: davanti a un elenco si
# deve capire in un istante cosa si sta guardando. Dietro non c'e' nessun ponte
# Tuya — un comando accende una variabile di shell, non un boiler.
#
# Gira dentro l'immagine di mosquitto perche' e' l'unica cosa che le serve:
# mosquitto_pub per raccontarsi e mosquitto_sub per ascoltare i comandi.
set -u

BROKER="${BROKER_HOST:-broker}"
PORTA="${BROKER_PORT:-1883}"
P="${PREFISSO:-dev}"
INTERRUTTORI="${INTERRUTTORI:-alfa bravo charlie delta golf-nome-lungo-per-vedere-dove-taglia}"
DIMMER="${DIMMER:-echo-regolabile}"
SENSORE="${SENSORE:-foxtrot-sensore}"

pub() { mosquitto_pub -h "$BROKER" -p "$PORTA" -u "$MQTT_USER" -P "$MQTT_PASS" -t "$1" -m "$2" $3; }

# Il broker e' nello stesso compose ma puo' partire un istante dopo: si aspetta
# invece di morire, cosi' `up -d` non lascia un servizio spento.
until mosquitto_pub -h "$BROKER" -p "$PORTA" -u "$MQTT_USER" -P "$MQTT_PASS" \
        -t "$P/dispositivi-finti/avvio" -m "attesa" 2>/dev/null; do
  echo "broker non ancora pronto, riprovo fra un secondo"
  sleep 1
done

# I consumi non sono uguali per tutti, di proposito. La scheda li mostra in una
# riga di quattro caselle in ordine fisso — oggi, ieri, settimana, mese — e i due
# casi che la mettono alla prova non si vedono con sette payload identici: uno a
# cui mancano dei valori, e uno con i numeri piu' larghi che il formato produca.
consumi_di() {
  case "$1" in
    delta)
      # Il payload di un ponte non aggiornato. Due caselle restano vuote, e sulla
      # scheda al loro posto si devono vedere due trattini: mai tre numeri che
      # scivolano a sinistra facendo leggere il mese come se fosse la settimana.
      echo '{"kwh_oggi":0.4,"kwh_mese":12.7}' ;;
    golf-*)
      # Il caso peggiore del formato: quattro caratteri per casella, sul nome piu'
      # lungo dell'elenco. Se la riga si tronca da qualche parte, si tronca qui.
      echo '{"kwh_oggi":9.99,"kwh_ieri":99.9,"kwh_settimana":999,"kwh_mese":9999}' ;;
    *)
      echo '{"kwh_oggi":0.42,"kwh_ieri":1.87,"kwh_settimana":6.3,"kwh_mese":12.7}' ;;
  esac
}

for n in $INTERRUTTORI; do
  pub "$P/$n/disponibilita" "online" -r
  pub "$P/$n/stato" '{"stato":"OFF","potenza_w":0.0}' -r
  pub "$P/$n/energia" "$(consumi_di "$n")" -r
done

# La luce regolabile porta il livello nello stesso payload dello stato: e' il
# caso che a casa non esiste, ed e' l'unico modo di provare il cursore.
pub "$P/$DIMMER/disponibilita" "online" -r
pub "$P/$DIMMER/stato" '{"stato":"OFF","livello":0,"potenza_w":0.0}' -r
# Lo zero di ieri non e' una casella vuota: la luce c'era e non ha consumato.
# Sulla scheda dev'essere 0,00 e non un trattino.
pub "$P/$DIMMER/energia" '{"kwh_oggi":0.1,"kwh_ieri":0,"kwh_settimana":0.8,"kwh_mese":2.3}' -r

# Il sensore non si comanda: pubblica un valore e basta.
pub "$P/$SENSORE/disponibilita" "online" -r
pub "$P/$SENSORE/stato" "21,4 °C" -r

echo "dispositivi finti in ascolto su $P/+/comando e $P/+/livello"

# Un valore cambia solo quando arriva un comando: cosi' quello che si vede sullo
# schermo e' sempre la conseguenza di qualcosa che si e' fatto, e una scheda che
# si muove da sola e' un difetto, non rumore dell'ambiente. L'unica eccezione e'
# il sensore, che di mestiere cambia da solo.
(
  while sleep 30; do
    pub "$P/$SENSORE/stato" "$(awk 'BEGIN{srand();printf "%.1f", 18+rand()*6}' | tr . ,) °C" -r
  done
) &

mosquitto_sub -h "$BROKER" -p "$PORTA" -u "$MQTT_USER" -P "$MQTT_PASS" \
    -t "$P/+/comando" -t "$P/+/livello" -F '%t %p' | while read -r topic payload; do
  nome=$(echo "$topic" | cut -d/ -f2)
  coda=$(echo "$topic" | cut -d/ -f3)

  if [ "$coda" = "livello" ]; then
    # Regolare accende: una luce portata al 40% e' accesa, e lasciarla "spenta"
    # al 40% sarebbe una bugia delle stesse che l'app si rifiuta di dire.
    pub "$P/$nome/stato" "{\"stato\":\"ON\",\"livello\":$payload,\"potenza_w\":$((payload * 2)).0}" -r
    echo "$(date +%T) $nome -> livello $payload%"
  elif [ "$payload" = "ON" ]; then
    watt=$(awk 'BEGIN{srand();print int(rand()*1800)+50}')
    if [ "$nome" = "$DIMMER" ]; then
      pub "$P/$nome/stato" "{\"stato\":\"ON\",\"livello\":100,\"potenza_w\":${watt}.0}" -r
    else
      pub "$P/$nome/stato" "{\"stato\":\"ON\",\"potenza_w\":${watt}.0}" -r
    fi
    echo "$(date +%T) $nome -> ON (${watt} W)"
  else
    if [ "$nome" = "$DIMMER" ]; then
      pub "$P/$nome/stato" '{"stato":"OFF","livello":0,"potenza_w":0.0}' -r
    else
      pub "$P/$nome/stato" '{"stato":"OFF","potenza_w":0.0}' -r
    fi
    echo "$(date +%T) $nome -> OFF"
  fi
done
