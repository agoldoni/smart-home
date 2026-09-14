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

// Con `--senza-posizioni` esce il documento com'era prima della feature 004:
// serve a provare che un registro vecchio ordina ancora per nome, che e' il
// comportamento promesso a chi non riordina niente.
const SENZA_POSIZIONI = process.argv.includes('--senza-posizioni');

/**
 * L'ordine dell'elenco sullo schermo, e **non e' quello alfabetico**.
 *
 * E' deliberato: se le posizioni non arrivassero o non venissero lette, l'elenco
 * tornerebbe in ordine di nome — cioe' `alfa, bravo, charlie, ...` — e la
 * differenza si vede in un istante invece di passare inosservata. In cima c'e'
 * il nome lungo, che e' anche quello da guardare per vedere dove taglia.
 */
const ORDINE = [
  'golf-nome-lungo-per-vedere-dove-taglia',
  'echo-regolabile',
  'alfa',
  'foxtrot-sensore',
  'delta',
  'charlie',
  'bravo',
];

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
      campo_kwh_ieri: '',
      campo_kwh_settimana: '',
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
  if (!SENZA_POSIZIONI) {
    const posto = ORDINE.indexOf(voce.nome);
    if (posto < 0) throw new Error(`${voce.nome}: manca da ORDINE`);
    d.posizione = posto;
  }
  const errori = valida(d);
  if (Object.keys(errori).length) throw new Error(`${voce.nome}: ${JSON.stringify(errori)}`);
  return d;
});

// La revisione, e **non basta che salga a ogni feature**.
//
// Un'app che ha gia' applicato la revisione N ignora *senza rumore* un documento
// di revisione minore o uguale: i campi nuovi non arrivano mai e sembra che la
// feature non funzioni. Il numero qui sotto non puo' essere l'unica verita',
// perche' sul broker di sviluppo scrive anche il **configuratore web**, che a
// ogni salvataggio incrementa per conto suo — il 13/09/2026 era arrivato a 16
// mentre questo file diceva ancora 2.
//
// Quindi: il predefinito e' il numero della feature piu' recente, ma quando sul
// broker ci ha messo mano un configuratore va passato a mano un numero piu' alto
// di quello che il telefono ha gia' applicato. Lo si legge da
// `python3 tools/debug-api.py --adb registry`, campo `revision`.
//
//     node devops/dev/genera-registro-dev.mjs registro-dev.json --revisione 17
const REVISIONE = (() => {
  const i = process.argv.indexOf('--revisione');
  if (i < 0) return 3;
  const n = Number(process.argv[i + 1]);
  if (!Number.isInteger(n) || n < 0) throw new Error('--revisione vuole un intero >= 0');
  return n;
})();

const doc = JSON.parse(documento(dispositivi, REVISIONE));
doc.aggiornato = '2026-09-14T09:00:00+02:00';
writeFileSync(process.argv[2] ?? 'registro-dev.json', JSON.stringify(doc, null, 1) + '\n');
console.log(
  `${dispositivi.length} dispositivi di sviluppo alla revisione ${REVISIONE}, ` +
    'nessun errore di validazione' +
    (SENZA_POSIZIONI ? ' — senza posizioni, come un registro di prima della 004' : ''),
);
