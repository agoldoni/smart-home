// I modelli.
//
// E' qui che si recuperano le ore: registrare le sette prese del ponte a mano
// vuol dire scrivere cinquantasei stringhe in cui un carattere sbagliato non da'
// nessun errore — da' un dispositivo che non ricevera' mai niente. Dato il nome,
// i topic li scrive la convenzione.

import { vuoto } from './campi.js';

export const MODELLI = [
  {
    id: 'ponte',
    etichetta: 'Presa del ponte Tuya',
    descrizione: 'Stato, potenza, consumi e disponibilità, come li pubblica bridge/tuya-mqtt.',
    applica: (nome, prefisso) => ({
      tipo: 'SWITCH',
      topic_stato: `${prefisso}/${nome}/stato`,
      campo_stato: 'stato',
      campo_potenza: 'potenza_w',
      topic_comando: `${prefisso}/${nome}/comando`,
      payload_on: 'ON',
      payload_off: 'OFF',
      topic_disponibilita: `${prefisso}/${nome}/disponibilita`,
      payload_disponibile: 'online',
      payload_non_disponibile: 'offline',
      topic_energia: `${prefisso}/${nome}/energia`,
      campo_kwh_oggi: 'kwh_oggi',
      campo_kwh_mese: 'kwh_mese',
      qos: 1,
    }),
  },
  {
    id: 'tasmota',
    etichetta: 'Tasmota',
    descrizione: 'Lo stato dal topic secco stat/…/POWER, e il testamento su tele/…/LWT.',
    applica: (nome) => ({
      tipo: 'SWITCH',
      topic_stato: `stat/${nome}/POWER`,
      campo_stato: '',
      topic_comando: `cmnd/${nome}/POWER`,
      payload_on: 'ON',
      payload_off: 'OFF',
      topic_disponibilita: `tele/${nome}/LWT`,
      payload_disponibile: 'Online',
      payload_non_disponibile: 'Offline',
    }),
  },
  {
    id: 'zigbee2mqtt',
    etichetta: 'Zigbee2MQTT',
    descrizione: 'Payload JSON, disponibilità a parte, e il fondo scala del livello a 254.',
    applica: (nome) => ({
      tipo: 'SWITCH',
      topic_stato: `zigbee2mqtt/${nome}`,
      campo_stato: 'state',
      topic_comando: `zigbee2mqtt/${nome}/set/state`,
      payload_on: 'ON',
      payload_off: 'OFF',
      topic_disponibilita: `zigbee2mqtt/${nome}/availability`,
      payload_disponibile: 'online',
      payload_non_disponibile: 'offline',
      topic_comando_livello: `zigbee2mqtt/${nome}/set/brightness`,
      campo_livello: 'brightness',
      livello_max: 254,
    }),
  },
];

/** Un dispositivo nuovo costruito dal modello, con l'uuid gia' suo. */
export function daModello(modello, nome, prefisso) {
  const pulito = nome.trim().replace(/\s+/g, '-').toLowerCase();
  return { ...vuoto(), nome: pulito, ...modello.applica(pulito, prefisso) };
}
