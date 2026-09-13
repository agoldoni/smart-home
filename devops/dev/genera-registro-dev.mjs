// Il registro dello stack di sviluppo, generato col codice vero della web app:
// stesso modello, stessa validazione, stessa serializzazione del browser.
//
// I nomi non assomigliano a quelli di casa di proposito: davanti a un elenco si
// deve capire in un istante se si sta guardando lo sviluppo o il salotto. Per la
// stessa ragione il prefisso e' `dev` e non `casa` — i topic non si sovrappongono
// nemmeno per sbaglio, e un configuratore puntato al broker sbagliato non trova
// niente da rovinare.
//
// C'e' anche quello che a casa non c'e': una luce regolabile, per provare il
// cursore del livello, un sensore in sola lettura, e un nome lungo per vedere
// dove le schede tagliano.
import { MODELLI, daModello } from '../../bridge/configuratore/web/modelli.js';
import { documento, valida } from '../../bridge/configuratore/web/registro.js';
import { writeFileSync } from 'node:fs';

const PREFISSO = 'dev';
const ponte = MODELLI.find((m) => m.id === 'ponte');

const ELENCO = [
  { nome: 'alfa' },
  { nome: 'bravo' },
  { nome: 'charlie' },
  { nome: 'delta', tipo: 'LIGHT' },
  {
    nome: 'echo-regolabile',
    tipo: 'DIMMER',
    extra: {
      topic_stato_livello: `${PREFISSO}/echo-regolabile/stato`,
      campo_livello: 'livello',
      topic_comando_livello: `${PREFISSO}/echo-regolabile/livello`,
      livello_max: 100,
    },
  },
  {
    nome: 'foxtrot-sensore',
    tipo: 'SENSOR',
    // Un sensore non si comanda: via i topic di comando, e il valore e' il
    // payload cosi' com'e'.
    extra: {
      campo_stato: '',
      topic_comando: '',
      payload_on: '',
      payload_off: '',
      campo_potenza: '',
      topic_energia: '',
      campo_kwh_oggi: '',
      campo_kwh_mese: '',
    },
  },
  { nome: 'golf-nome-lungo-per-vedere-dove-taglia' },
];

const dispositivi = ELENCO.map((voce, i) => {
  const d = daModello(ponte, voce.nome, PREFISSO);
  if (voce.tipo) d.tipo = voce.tipo;
  Object.assign(d, voce.extra ?? {});
  // uuid deterministici: rigenerare non deve produrre dispositivi nuovi agli
  // occhi dell'app, che li riconosce per uuid.
  d.uuid = `dee00000-0000-4000-8000-00000000000${i + 1}`;
  const errori = valida(d);
  if (Object.keys(errori).length) throw new Error(`${voce.nome}: ${JSON.stringify(errori)}`);
  return d;
});

const doc = JSON.parse(documento(dispositivi, 1));
doc.aggiornato = '2026-09-13T20:00:00+02:00';
writeFileSync(process.argv[2] ?? 'registro-dev.json', JSON.stringify(doc, null, 1) + '\n');
console.log(`${dispositivi.length} dispositivi di sviluppo, nessun errore di validazione`);
