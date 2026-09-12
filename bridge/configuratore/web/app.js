// Il configuratore.
//
// Non tiene stato proprio: tutto quello che sa lo ha letto dal messaggio
// ritenuto sul broker, e tutto quello che cambia lo riscrive li'. Chiudere la
// pagina non perde niente, e riaprirla non richiede di ricordarsi niente.

import { CAMPI, TIPI, vuoto } from './campi.js';
import { MODELLI, daModello } from './modelli.js';
import { documento, leggiRegistro, topicRegistro, valida } from './registro.js';

const stato = {
  client: null,
  prefisso: 'casa',
  topic: topicRegistro('casa'),
  dispositivi: [],
  revisione: 0,
  esiste: false,
  scartati: [],
  // Il documento aperto nel modulo, e la revisione da cui veniva: se nel
  // frattempo ne arriva una piu' alta, qualcun altro ha scritto.
  modifica: null,
  revisioneBase: null,
  errori: {},
};

const $ = (id) => document.getElementById(id);

/** Svuota un nodo e ci mette dentro i figli, array annidati compresi. */
function riempi(nodo, ...figli) {
  nodo.replaceChildren(
    ...figli.flat(Infinity).filter((f) => f !== null && f !== undefined && f !== false),
  );
}

function el(tag, attrs = {}, ...figli) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') n.className = v;
    else if (k.startsWith('on')) n.addEventListener(k.slice(2), v);
    else if (v !== null && v !== undefined && v !== false) n.setAttribute(k, v);
  }
  for (const f of figli.flat()) {
    if (f === null || f === undefined || f === false) continue;
    n.append(typeof f === 'string' || typeof f === 'number' ? String(f) : f);
  }
  return n;
}

// -- collegamento -------------------------------------------------------

function collega(evento) {
  evento.preventDefault();
  const host = $('host').value.trim();
  const porta = $('porta').value.trim() || '9001';
  const utente = $('utente').value.trim();
  const password = $('password').value;
  stato.prefisso = $('prefisso').value.trim() || 'casa';
  stato.topic = topicRegistro(stato.prefisso);

  if (!host) return avviso('collegamento', 'Serve l’indirizzo del broker.');
  ricorda({ host, porta, utente, prefisso: stato.prefisso });

  avviso('collegamento', `Collegamento a ${host}:${porta}…`);
  // Client id casuale a ogni apertura. Per specifica MQTT un id identifica una
  // connessione sola: due schede con lo stesso nome si butterebbero fuori a
  // vicenda, all'infinito.
  const client = mqtt.connect(`ws://${host}:${porta}/mqtt`, {
    username: utente || undefined,
    password: password || undefined,
    clientId: `configuratore-${Math.random().toString(16).slice(2, 10)}`,
    clean: true,
    reconnectPeriod: 4000,
    connectTimeout: 8000,
  });

  client.on('connect', () => {
    stato.client = client;
    avviso('collegamento', `Collegato a ${host}:${porta}.`, 'ok');
    client.subscribe(stato.topic, { qos: 1 }, (err) => {
      if (err) avviso('collegamento', `Sottoscrizione fallita: ${err.message}`, 'errore');
    });
    document.body.dataset.collegato = 'si';
    disegna();
  });

  client.on('message', (topic, payload) => {
    if (topic !== stato.topic) return;
    arrivato(payload.toString());
  });

  client.on('error', (err) => {
    // Con credenziali sbagliate il broker chiude: si dice cosa ha risposto,
    // invece di lasciare una pagina vuota che sembra un caricamento lento.
    avviso('collegamento', `Il broker ha rifiutato: ${err.message}`, 'errore');
    client.end(true);
    stato.client = null;
    document.body.dataset.collegato = '';
  });

  client.on('close', () => {
    if (stato.client) avviso('collegamento', 'Collegamento chiuso.', 'errore');
  });
}

function arrivato(payload) {
  const letto = leggiRegistro(payload);

  if (letto.stato === 'vuoto') {
    stato.esiste = false;
    stato.dispositivi = [];
    stato.revisione = 0;
    stato.scartati = [];
    avviso('registro', 'Nessun registro su questo topic. Il primo salvataggio lo crea.');
    return disegna();
  }
  if (letto.stato === 'rifiutato') {
    avviso('registro', `Il documento sul broker non si legge (${letto.motivo}). Salvare lo riscriverebbe da capo.`, 'errore');
    return disegna();
  }

  const eraAperto = stato.modifica !== null;
  const cambiataSottoIlNaso = eraAperto && letto.revisione !== stato.revisioneBase;

  stato.esiste = true;
  stato.dispositivi = letto.dispositivi;
  stato.revisione = letto.revisione;
  stato.scartati = letto.scartati;

  if (cambiataSottoIlNaso) {
    avviso(
      'registro',
      `Qualcun altro ha salvato nel frattempo (ora è la revisione ${letto.revisione}). ` +
        'Chiudi e riapri il dispositivo per ripartire da quello che c’è adesso.',
      'errore',
    );
  } else {
    if (!eraAperto) stato.revisioneBase = letto.revisione;
    avviso(
      'registro',
      `Revisione ${letto.revisione}, ${letto.dispositivi.length} dispositivi.` +
        (letto.scartati.length ? ` ${letto.scartati.length} scartati: ${letto.scartati.join('; ')}` : ''),
      letto.scartati.length ? 'errore' : 'ok',
    );
  }
  disegna();
}

// -- scrittura ----------------------------------------------------------

function pubblica(dispositivi) {
  if (!stato.client) return avviso('registro', 'Non sei collegato.', 'errore');
  if (stato.modifica && stato.revisione !== stato.revisioneBase) {
    return avviso('registro', 'Il registro è cambiato nel frattempo: ricarica prima di sovrascrivere.', 'errore');
  }
  const prossima = stato.revisione + 1;
  stato.client.publish(
    stato.topic,
    documento(dispositivi, prossima),
    { qos: 1, retain: true },
    (err) => {
      if (err) return avviso('registro', `Pubblicazione fallita: ${err.message}`, 'errore');
      stato.revisioneBase = prossima;
    },
  );
}

function salva() {
  const d = stato.modifica;
  stato.errori = valida(d);
  if (Object.keys(stato.errori).length) return disegnaModulo();

  const altri = stato.dispositivi.filter((x) => x.uuid !== d.uuid);
  pubblica([...altri, d].sort((a, b) => String(a.nome).localeCompare(String(b.nome))));
  chiudiModulo();
}

function elimina(uuid) {
  const d = stato.dispositivi.find((x) => x.uuid === uuid);
  if (!d) return;
  if (!confirm(`Togliere "${d.nome}" dal registro?\n\nSparirà da tutte le app che lo seguono.`)) return;
  pubblica(stato.dispositivi.filter((x) => x.uuid !== uuid));
}

function duplica(uuid) {
  const d = stato.dispositivi.find((x) => x.uuid === uuid);
  if (!d) return;
  apriModulo({ ...d, uuid: crypto.randomUUID(), nome: `${d.nome}-copia` });
}

// -- modulo -------------------------------------------------------------

function apriModulo(d) {
  stato.modifica = { ...vuoto(), ...d };
  stato.revisioneBase = stato.revisione;
  stato.errori = {};
  disegna();
}

function chiudiModulo() {
  stato.modifica = null;
  stato.errori = {};
  disegna();
}

function campoInput(campo) {
  const d = stato.modifica;
  const errore = stato.errori[campo.chiave];
  const aggiorna = (v) => {
    d[campo.chiave] = v;
    delete stato.errori[campo.chiave];
  };

  let controllo;
  if (campo.tipo === 'booleano') {
    controllo = el('input', {
      type: 'checkbox', id: `c-${campo.chiave}`,
      checked: d[campo.chiave] ? 'checked' : false,
      onchange: (e) => aggiorna(e.target.checked),
    });
  } else if (campo.tipo === 'scelta') {
    controllo = el('select', { id: `c-${campo.chiave}`, onchange: (e) => aggiorna(Number(e.target.value)) },
      campo.valori.map((v) => el('option', { value: v, selected: Number(d[campo.chiave]) === v ? 'selected' : false }, v)));
  } else {
    controllo = el('input', {
      type: campo.tipo === 'numero' ? 'number' : 'text',
      id: `c-${campo.chiave}`,
      value: d[campo.chiave] ?? '',
      spellcheck: 'false', autocapitalize: 'off', autocomplete: 'off',
      oninput: (e) => aggiorna(campo.tipo === 'numero' ? Number(e.target.value) : e.target.value),
    });
  }

  return el('div', { class: `campo${errore ? ' in-errore' : ''}` },
    el('label', { for: `c-${campo.chiave}` }, campo.etichetta),
    controllo,
    errore ? el('p', { class: 'errore' }, errore) : (campo.aiuto ? el('p', { class: 'aiuto' }, campo.aiuto) : null));
}

function disegnaModulo() {
  const d = stato.modifica;
  const pannello = $('modulo');
  if (!d) return riempi(pannello);

  riempi(
    pannello,
    el('div', { class: 'testata' },
      el('h2', {}, d.nome ? `Modifica ${d.nome}` : 'Nuovo dispositivo'),
      el('div', { class: 'azioni' },
        el('button', { class: 'principale', onclick: salva }, 'Salva'),
        el('button', { onclick: chiudiModulo }, 'Annulla'))),
    el('div', { class: 'campo' },
      el('label', {}, 'Tipo'),
      el('div', { class: 'pastiglie' },
        TIPI.map((t) => el('button', {
          class: `pastiglia${d.tipo === t.valore ? ' scelta' : ''}`,
          onclick: () => { d.tipo = t.valore; disegnaModulo(); },
        }, t.etichetta)))),
    CAMPI.filter((s) => !s.mostraSe || s.mostraSe(d)).map((sezione) =>
      el('section', {}, el('h3', {}, sezione.sezione), sezione.campi.map(campoInput))),
    el('p', { class: 'aiuto uuid' }, `uuid ${d.uuid}`),
  );
}

// -- elenco -------------------------------------------------------------

function disegnaElenco() {
  const lista = $('elenco');
  riempi(lista);

  if (!stato.dispositivi.length) {
    lista.append(el('p', { class: 'niente' },
      stato.esiste
        ? 'Il registro è vuoto. Aggiungi il primo dispositivo, o parti da un modello.'
        : 'Nessun registro su questo topic: il primo salvataggio lo crea.'));
    return;
  }

  for (const d of [...stato.dispositivi].sort((a, b) => String(a.nome).localeCompare(String(b.nome)))) {
    lista.append(el('article', { class: 'scheda' },
      el('div', { class: 'riga' },
        el('strong', {}, d.nome || '(senza nome)'),
        el('span', { class: 'tipo' }, (TIPI.find((t) => t.valore === d.tipo) || {}).etichetta || d.tipo)),
      el('code', {}, d.topic_stato || '(nessun topic di stato)'),
      el('div', { class: 'azioni' },
        el('button', { onclick: () => apriModulo(d) }, 'Modifica'),
        el('button', { onclick: () => duplica(d.uuid) }, 'Duplica'),
        el('button', { class: 'pericolo', onclick: () => elimina(d.uuid) }, 'Elimina'))));
  }
}

function disegnaModelli() {
  const dove = $('modelli');
  riempi(
    dove,
    el('p', { class: 'aiuto' }, 'Parti da un modello: scrivi il nome e i topic li compila la convenzione.'),
    MODELLI.map((m) => el('div', { class: 'modello' },
      el('div', {},
        el('strong', {}, m.etichetta),
        el('p', { class: 'aiuto' }, m.descrizione)),
      el('button', {
        onclick: () => {
          const nome = prompt(`${m.etichetta} — nome del dispositivo`, '');
          if (nome && nome.trim()) apriModulo(daModello(m, nome, stato.prefisso));
        },
      }, 'Usa'))),
  );
}

function disegna() {
  $('topic-corrente').textContent = stato.topic;
  document.body.dataset.modulo = stato.modifica ? 'si' : '';
  disegnaElenco();
  disegnaModelli();
  disegnaModulo();
}

// -- contorno -----------------------------------------------------------

function avviso(dove, testo, tono = '') {
  const n = $(`avviso-${dove}`);
  n.textContent = testo;
  n.className = `avviso ${tono}`;
}

/**
 * Indirizzo, porta, utente e prefisso si ricordano. La password no, e non e'
 * una dimenticanza: e' la credenziale del broker di casa, e resta in memoria
 * per la durata della scheda.
 */
function ricorda(v) {
  try {
    localStorage.setItem('configuratore', JSON.stringify(v));
  } catch (e) { /* privata, o storage negato: si riscrive a mano */ }
}

function riprendi() {
  try {
    const v = JSON.parse(localStorage.getItem('configuratore') || '{}');
    if (v.host) $('host').value = v.host;
    if (v.porta) $('porta').value = v.porta;
    if (v.utente) $('utente').value = v.utente;
    if (v.prefisso) $('prefisso').value = v.prefisso;
  } catch (e) { /* come sopra */ }
}

$('collegamento').addEventListener('submit', collega);
$('aggiungi').addEventListener('click', () => apriModulo(vuoto()));
riprendi();
disegna();
