// I campi del registro, dichiarati una volta sola.
//
// Da qui si genera il modulo e da qui si legge per costruire il documento: un
// campo aggiunto in questo elenco compare nell'interfaccia e finisce nel JSON
// senza toccare altro. E' anche l'unico posto dove i nomi del contratto —
// quelli di SCHEMA.md — sono scritti.

export const TIPI = [
  { valore: 'SWITCH', etichetta: 'Interruttore' },
  { valore: 'LIGHT', etichetta: 'Luce' },
  { valore: 'DIMMER', etichetta: 'Luce regolabile' },
  { valore: 'SENSOR', etichetta: 'Sensore' },
];

const seDimmer = (d) => d.tipo === 'DIMMER';
const seComandabile = (d) => d.tipo !== 'SENSOR';

export const CAMPI = [
  {
    sezione: 'Identità',
    campi: [
      { chiave: 'nome', etichetta: 'Nome', aiuto: 'Quello che si legge sulla scheda.' },
    ],
  },
  {
    sezione: 'Stato',
    campi: [
      {
        chiave: 'topic_stato',
        etichetta: 'Topic di stato',
        aiuto: 'Dove il dispositivo pubblica il proprio stato. Ammette le wildcard + e #.',
      },
      {
        chiave: 'campo_stato',
        etichetta: 'Campo JSON dello stato',
        aiuto: 'Lascia vuoto se il payload è già il valore. Per Tasmota: POWER.',
      },
      {
        chiave: 'campo_potenza',
        etichetta: 'Campo JSON della potenza',
        aiuto: 'Il campo con i watt assorbiti, per chi li misura. Per le prese del ponte: potenza_w.',
      },
    ],
  },
  {
    sezione: 'Consumi',
    campi: [
      {
        chiave: 'topic_energia',
        etichetta: 'Topic dei consumi',
        aiuto: 'Dove qualcuno pubblica i kWh accumulati. Vuoto se nessuno li tiene.',
      },
      { chiave: 'campo_kwh_oggi', etichetta: 'Campo JSON dei kWh di oggi' },
      { chiave: 'campo_kwh_mese', etichetta: 'Campo JSON dei kWh del mese' },
    ],
  },
  {
    sezione: 'Disponibilità',
    campi: [
      {
        chiave: 'topic_disponibilita',
        etichetta: 'Topic di disponibilità',
        aiuto: 'Dove il dispositivo dichiara di essere vivo. Vuoto se non ne pubblica uno: l’app mostrerà l’ultimo stato senza sapere se è ancora vero.',
      },
      { chiave: 'payload_disponibile', etichetta: 'Payload "c’è"', predefinito: 'online' },
      { chiave: 'payload_non_disponibile', etichetta: 'Payload "non c’è"', predefinito: 'offline' },
    ],
  },
  {
    sezione: 'Comando',
    mostraSe: seComandabile,
    campi: [
      {
        chiave: 'topic_comando',
        etichetta: 'Topic di comando',
        aiuto: 'Dove l’app pubblica i comandi. Niente wildcard: il broker rifiuterebbe il messaggio.',
      },
      { chiave: 'payload_on', etichetta: 'Payload acceso', predefinito: 'ON' },
      { chiave: 'payload_off', etichetta: 'Payload spento', predefinito: 'OFF' },
    ],
  },
  {
    sezione: 'Livello',
    mostraSe: seDimmer,
    campi: [
      { chiave: 'topic_comando_livello', etichetta: 'Topic di comando del livello' },
      {
        chiave: 'topic_stato_livello',
        etichetta: 'Topic di stato del livello',
        aiuto: 'Vuoto se il livello arriva sullo stesso topic dello stato.',
      },
      { chiave: 'campo_livello', etichetta: 'Campo JSON del livello' },
      {
        chiave: 'livello_max',
        etichetta: 'Valore massimo del livello',
        tipo: 'numero',
        predefinito: 100,
        aiuto: 'A cosa corrisponde il 100%: 100 per Tasmota, 254 per Zigbee2MQTT.',
      },
    ],
  },
  {
    sezione: 'Avanzate',
    mostraSe: seComandabile,
    campi: [
      { chiave: 'qos', etichetta: 'QoS', tipo: 'scelta', valori: [0, 1, 2], predefinito: 0 },
      {
        chiave: 'ritenuto',
        etichetta: 'Comandi ritenuti',
        tipo: 'booleano',
        predefinito: false,
        aiuto: 'Solo se il dispositivo deve ritrovare l’ultimo comando quando si riaccende.',
      },
    ],
  },
];

/** Tutti i campi in fila, senza le sezioni. */
export const TUTTI = CAMPI.flatMap((s) => s.campi);

export function vuoto() {
  const d = { uuid: crypto.randomUUID(), tipo: 'SWITCH' };
  for (const campo of TUTTI) {
    d[campo.chiave] = campo.predefinito !== undefined ? campo.predefinito : '';
  }
  return d;
}
