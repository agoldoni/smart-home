// Genera il documento con il codice vero della web app: stesso modello, stessa
// validazione, stessa serializzazione che girera' nel browser.
import { MODELLI, daModello } from '../bridge/configuratore/web/modelli.js';
import { documento, valida } from '../bridge/configuratore/web/registro.js';
import { writeFileSync } from 'node:fs';

const PRESE = ['boiler', 'depuratore', 'lavastoviglie', 'lavatrice-nuova',
               'jacopo-studio', 'frigorifero', 'pompa'];
const ponte = MODELLI.find((m) => m.id === 'ponte');

const dispositivi = PRESE.map((nome, i) => {
  const d = daModello(ponte, nome, 'casa');
  // uuid deterministici: il file finisce in git e deve avere una diff stabile.
  d.uuid = `0000000${i + 1}-0000-4000-8000-00000000000${i + 1}`;
  const errori = valida(d);
  if (Object.keys(errori).length) throw new Error(`${nome}: ${JSON.stringify(errori)}`);
  return d;
});

const doc = documento(dispositivi, 1);
// L'istante di generazione renderebbe la diff rumorosa a ogni rigenerazione.
const fisso = JSON.parse(doc);
fisso.aggiornato = '2026-09-12T21:04:33+02:00';
writeFileSync(process.argv[2], JSON.stringify(fisso, null, 1) + '\n');
console.log(`${dispositivi.length} prese, nessun errore di validazione`);
