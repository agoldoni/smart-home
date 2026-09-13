# Il broker di casa: mosquitto con dentro la sua configurazione.
#
# La configurazione sta nell'immagine e non in un bind mount perche' e' codice,
# non dato: cambia solo quando qualcuno la cambia in git, e allora deve passare
# da una build e da un tag come tutto il resto. Quello che invece resta fuori e'
# il file di password — e' un segreto, e' diverso per installazione, e non ha
# niente da fare dentro un'immagine.
FROM eclipse-mosquitto:2

COPY mosquitto.conf /mosquitto/config/mosquitto.conf
