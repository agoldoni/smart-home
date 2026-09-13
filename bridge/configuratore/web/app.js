// Il configuratore.
//
// Non tiene stato proprio: tutto quello che sa lo ha letto dal messaggio
// ritenuto sul broker, e tutto quello che cambia lo riscrive li'. Chiudere la
// pagina non perde niente, e riaprirla non richiede di ricordarsi niente.

import { CAMPI, TIPI, nuovoUuid, vuoto } from './campi.js';
import { MODELLI, daModello } from './modelli.js';
import {
  documento, leggiRegistro, ordina, posizione, prossimaPosizione, rinumera,
  topicRegistro, valida,
} from './registro.js';

const stato = {
  client: null,
  // Il client esiste anche mentre riprova: `collegato` dice se in questo momento
  // c'e' davvero un broker dall'altra parte, ed e' quello che decide cosa si vede.
  collegato: false,
  broker: '',
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
  // Il riordino: quale riga e' sotto il dito, il timer della pubblicazione che
  // sta per partire, e da quale revisione quel riordino era partito.
  trascinando: null,
  ridisegnaDopo: false,
  attesaOrdine: null,
  ordineBase: null,
};

/**
 * Quanto si aspetta prima di pubblicare un ordine nuovo.
 *
 * Il riordino si pubblica da se', senza un pulsante: ma cinque tocchi sulla
 * freccia sono un solo spostamento agli occhi di chi li fa, e sarebbero cinque
 * documenti sul broker e cinque riletture su ogni telefono. L'attesa li
 * accorpa, e resta sotto la soglia in cui si smette di sembrare immediato.
 */
const RITARDO_ORDINE = 600;

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

  // Un collegamento di prima che stesse ancora riprovando parlerebbe con il
  // broker vecchio mentre questo e' gia' in piedi: si chiude prima di aprire.
  scollega('', '');
  stato.broker = `${host}:${porta}`;

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
  stato.client = client;

  // Ogni gestore guarda prima di tutto se il client e' ancora quello buono: uno
  // appena chiuso puo' emettere un ultimo evento, e non deve toccare il
  // collegamento che gli e' subentrato.
  const mio = () => stato.client === client;

  client.on('connect', () => {
    if (!mio()) return;
    // Ci passa anche ogni riconnessione: la sottoscrizione si rifa', e il
    // messaggio ritenuto ripopola da se' l'elenco che si era buttato.
    stato.collegato = true;
    avviso('collegamento', '');
    client.subscribe(stato.topic, { qos: 1 }, (err) => {
      if (err) avviso('collegamento', `Sottoscrizione fallita: ${err.message}`, 'errore');
    });
    disegna();
  });

  client.on('message', (topic, payload) => {
    if (!mio() || topic !== stato.topic) return;
    arrivato(payload.toString());
  });

  client.on('error', (err) => {
    if (!mio()) return;
    // Con credenziali sbagliate il broker chiude: si dice cosa ha risposto,
    // invece di lasciare una pagina vuota che sembra un caricamento lento.
    scollega(`Il broker ha rifiutato: ${err.message}`);
  });

  client.on('close', () => {
    // Il client riprova da se' ogni reconnectPeriod, quindi non lo si butta: si
    // torna alla sola card del broker e si aspetta li'.
    if (mio() && stato.collegato) perdiCollegamento('Collegamento perso. Riprovo…');
  });
}

/**
 * Quello che si sa del registro vale finche' dura il collegamento.
 *
 * Staccato il broker, l'elenco sullo schermo non e' piu' il registro: e' com'era
 * l'ultima volta che qualcuno lo ha detto. Lasciarlo li' invita a modificarlo, e
 * un riordino gia' in attesa partirebbe su una revisione che nel frattempo puo'
 * essere diventata tre. Si butta tutto e lo si rilegge quando il broker
 * ritorna: il messaggio ritenuto arriva da se', senza chiedere niente.
 */
function dimenticaRegistro() {
  // L'ordine in attesa muore qui, e non e' un dettaglio: pubblicato dopo la
  // caduta sarebbe un documento calcolato su un elenco che non si ha piu'.
  annullaOrdine();
  stato.collegato = false;
  stato.dispositivi = [];
  stato.revisione = 0;
  stato.esiste = false;
  stato.scartati = [];
  avviso('registro', '');
}

/**
 * Il collegamento e' caduto, ma il client sta gia' riprovando.
 *
 * Il dispositivo aperto nel modulo resta dov'e': e' lavoro digitato a mano, e la
 * pagina lo ritrova appena il broker risponde. Nel frattempo non si vede, perche'
 * scollegati non si vede niente che non sia la card del broker.
 */
function perdiCollegamento(testo) {
  dimenticaRegistro();
  avviso('collegamento', testo, 'errore');
  ridisegna();
}

/**
 * Il collegamento si chiude qui, e non riprova nessuno.
 *
 * A differenza di una caduta, questa e' una porta chiusa: il modulo aperto si
 * perde insieme al resto, perche' chi rientra puo' rientrare su un altro
 * prefisso — cioe' su un'altra casa, dove quel dispositivo non voleva andare.
 */
function scollega(testo, tono = 'errore') {
  const client = stato.client;
  stato.client = null;
  if (client) client.end(true);
  stato.modifica = null;
  stato.errori = {};
  stato.revisioneBase = null;
  dimenticaRegistro();
  avviso('collegamento', testo, tono);
  ridisegna();
}

/** Riaprire i campi vuol dire scollegarsi: e' l'unico modo di cambiare broker. */
function cambiaBroker() {
  if (stato.modifica
      && !confirm('C’è un dispositivo aperto: cambiando broker si perde quello che hai scritto.')) return;
  scollega('Collegamento chiuso. Cambia quello che serve e ricollegati.', '');
}

/**
 * Ridisegna, ma non mentre il dito e' giu'.
 *
 * Un registro che arriva a meta' trascinamento ricreerebbe la riga sotto il
 * puntatore, e il gesto morirebbe li'. Si segna che c'e' da ridisegnare e lo si
 * fa appena il dito si alza — ed e' proprio durante un riordino che qualcun
 * altro ha piu' motivo di stare scrivendo.
 */
function ridisegna() {
  if (stato.trascinando) {
    stato.ridisegnaDopo = true;
    return;
  }
  disegna();
}

function arrivato(payload) {
  const letto = leggiRegistro(payload);

  if (letto.stato === 'vuoto') {
    stato.esiste = false;
    stato.dispositivi = [];
    stato.revisione = 0;
    stato.scartati = [];
    avviso('registro', 'Nessun registro su questo topic. Il primo salvataggio lo crea.');
    return ridisegna();
  }
  if (letto.stato === 'rifiutato') {
    avviso('registro', `Il documento sul broker non si legge (${letto.motivo}). Salvare lo riscriverebbe da capo.`, 'errore');
    return ridisegna();
  }

  const eraAperto = stato.modifica !== null;
  const cambiataSottoIlNaso = eraAperto && letto.revisione !== stato.revisioneBase;
  // Un riordino non ancora partito, superato da una revisione di qualcun altro,
  // si butta. Pubblicarlo lo stesso riscriverebbe **tutte** le righe sopra il
  // lavoro appena arrivato: fra le scritture possibili, un riordino e' quella
  // che ha piu' da perdere.
  const ordinePerso = stato.attesaOrdine !== null && letto.revisione !== stato.ordineBase;
  if (ordinePerso) annullaOrdine();

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
  } else if (ordinePerso) {
    avviso(
      'registro',
      `Qualcun altro ha salvato nel frattempo (ora è la revisione ${letto.revisione}): ` +
        'il riordino non è partito. Rifallo su quello che c’è adesso.',
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
  ridisegna();
}

// -- scrittura ----------------------------------------------------------

/**
 * Scrive il registro.
 *
 * [base] e' la revisione da cui si stava partendo, per chi la sa: se nel
 * frattempo ne e' arrivata un'altra, non si sovrascrive. Prima era una
 * condizione scritta qui dentro che guardava `stato.modifica`, cioe' valeva
 * **solo a modulo aperto** — e un riordino non apre nessun modulo, pur essendo
 * la scrittura che tocca tutte le righe insieme.
 */
function pubblica(dispositivi, base = null) {
  if (!stato.collegato) return avviso('registro', 'Non sei collegato.', 'errore');
  if (base !== null && stato.revisione !== base) {
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

  const esistente = stato.dispositivi.find((x) => x.uuid === d.uuid);
  const altri = stato.dispositivi.filter((x) => x.uuid !== d.uuid);
  // La posizione non si digita: chi c'e' gia' tiene la sua, e chi e' nuovo — un
  // duplicato compreso, che nuovo lo e' — va in fondo, ma solo se qualcun altro
  // ha gia' un posto. Vedi SCHEMA.md, *Chi assegna le posizioni*.
  d.posizione = esistente ? posizione(esistente) : prossimaPosizione(stato.dispositivi);
  pubblica(ordina([...altri, d]), stato.revisioneBase);
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
  apriModulo({ ...d, uuid: nuovoUuid(), nome: `${d.nome}-copia` });
}

// -- ordine -------------------------------------------------------------

/**
 * Un ordine nuovo: si vede subito, si pubblica fra un attimo.
 *
 * L'elenco si riordina in locale prima che il broker abbia visto niente —
 * aspettare il giro completo farebbe sembrare lento un gesto che e' istantaneo.
 * Il documento vero parte dopo [RITARDO_ORDINE], cosi' piu' spostamenti di
 * seguito diventano una pubblicazione sola.
 */
function riordina(lista) {
  stato.dispositivi = rinumera(lista);
  if (stato.attesaOrdine === null) stato.ordineBase = stato.revisione;
  clearTimeout(stato.attesaOrdine);
  stato.attesaOrdine = setTimeout(() => {
    const base = stato.ordineBase;
    annullaOrdine();
    pubblica(stato.dispositivi, base);
  }, RITARDO_ORDINE);
  ridisegna();
}

function annullaOrdine() {
  clearTimeout(stato.attesaOrdine);
  stato.attesaOrdine = null;
  stato.ordineBase = null;
}

/** Su e giu' di un posto. E' la strada di chi non puo' trascinare. */
function sposta(uuid, passo) {
  const lista = ordina(stato.dispositivi);
  const i = lista.findIndex((d) => d.uuid === uuid);
  const j = i + passo;
  if (i < 0 || j < 0 || j >= lista.length) return;
  [lista[i], lista[j]] = [lista[j], lista[i]];
  riordina(lista);
}

/**
 * Il trascinamento, con i pointer events e non con il drag-and-drop di HTML5.
 *
 * Il drag-and-drop nativo non emette niente sotto un dito, e questa pagina si
 * apre anche dal telefono. I pointer events coprono mouse, dito e penna con lo
 * stesso codice; la cattura fa arrivare ogni movimento anche quando il puntatore
 * esce da dove il gesto e' cominciato, che e' quasi sempre.
 *
 * La cattura sta sull'**elenco** e non sulla maniglia, e non e' un'inezia:
 * spostare una riga vuol dire spostare il nodo che contiene la maniglia, e un
 * nodo spostato nel DOM **perde la cattura** — il trascinamento si fermava dopo
 * il primo scambio, con il puntatore che si staccava dalla riga. L'elenco invece
 * non si muove mai: sono i suoi figli a muoversi.
 */
function avviaTrascinamento(evento, uuid, scheda) {
  if (evento.button) return; // solo il tasto primario
  evento.preventDefault();

  const lista = $('elenco');
  lista.setPointerCapture(evento.pointerId);
  stato.trascinando = uuid;
  scheda.classList.add('trascinata');

  const muovi = (e) => posizionaTrascinata(lista, scheda, e.clientY);
  const finisci = () => {
    lista.removeEventListener('pointermove', muovi);
    lista.removeEventListener('pointerup', finisci);
    lista.removeEventListener('pointercancel', finisci);
    scheda.classList.remove('trascinata');
    stato.trascinando = null;

    // Un registro arrivato mentre il dito era giu' ha l'ultima parola: si
    // ridisegna quello che c'e' sul broker e il gesto si perde. E' spiacevole,
    // ma l'alternativa e' pubblicare un ordine calcolato su un elenco che non
    // esiste piu'.
    if (stato.ridisegnaDopo) {
      stato.ridisegnaDopo = false;
      avviso('registro', 'È arrivato un registro nuovo durante il trascinamento: l’ordine non è stato cambiato.', 'errore');
      return disegna();
    }
    riordina(ordineDalDom(lista));
  };

  lista.addEventListener('pointermove', muovi);
  lista.addEventListener('pointerup', finisci);
  lista.addEventListener('pointercancel', finisci);
}

/**
 * Sposta la riga trascinata fra le altre, guardando dove sta il puntatore.
 *
 * Si confronta con la **meta'** di ogni altra scheda, non con il suo bordo: e'
 * quello che fa scattare lo scambio a meta' strada invece che all'ultimo pixel.
 * La riga trascinata e' esclusa dal confronto, altrimenti si inseguirebbe.
 */
function posizionaTrascinata(lista, scheda, y) {
  const altre = [...lista.children].filter((n) => n !== scheda && n.classList.contains('scheda'));
  const dopo = altre.find((n) => {
    const r = n.getBoundingClientRect();
    return y < r.top + r.height / 2;
  });
  if (dopo) lista.insertBefore(scheda, dopo);
  else lista.append(scheda);
}

/** L'ordine come sta adesso sullo schermo, riportato sui dispositivi veri. */
function ordineDalDom(lista) {
  const perUuid = new Map(stato.dispositivi.map((d) => [d.uuid, d]));
  const ordinati = [...lista.children].map((n) => perUuid.get(n.dataset.uuid)).filter(Boolean);
  // Chi e' comparso nel registro mentre il dito era giu' non sta nel DOM: va in
  // coda invece di sparire dal documento che stiamo per pubblicare.
  const visti = new Set(ordinati.map((d) => d.uuid));
  return [...ordinati, ...stato.dispositivi.filter((d) => !visti.has(d.uuid))];
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

  const ordinati = ordina(stato.dispositivi);
  ordinati.forEach((d, i) => {
    const nome = d.nome || '(senza nome)';
    // La maniglia e' nascosta a chi legge lo schermo: il trascinamento e' un
    // gesto che non sa fare, e al suo posto ci sono le due frecce, che hanno un
    // nome per esteso invece di una punta disegnata.
    const scheda = el('article', { class: 'scheda', 'data-uuid': d.uuid },
      el('div', { class: 'riga' },
        el('span', { class: 'presa' },
          el('span', {
            class: 'maniglia',
            'aria-hidden': 'true',
            title: 'Trascina per spostare',
            onpointerdown: (e) => avviaTrascinamento(e, d.uuid, scheda),
          }, '⠿'),
          el('strong', {}, nome)),
        el('span', { class: 'tipo' }, (TIPI.find((t) => t.valore === d.tipo) || {}).etichetta || d.tipo)),
      el('code', {}, d.topic_stato || '(nessun topic di stato)'),
      el('div', { class: 'azioni' },
        el('button', {
          class: 'ordine', disabled: i === 0 ? 'disabled' : false,
          'aria-label': `Sposta ${nome} più in alto`, title: 'Più in alto',
          onclick: () => sposta(d.uuid, -1),
        }, '↑'),
        el('button', {
          class: 'ordine', disabled: i === ordinati.length - 1 ? 'disabled' : false,
          'aria-label': `Sposta ${nome} più in basso`, title: 'Più in basso',
          onclick: () => sposta(d.uuid, 1),
        }, '↓'),
        el('button', { onclick: () => apriModulo(d) }, 'Modifica'),
        el('button', { onclick: () => duplica(d.uuid) }, 'Duplica'),
        el('button', { class: 'pericolo', onclick: () => elimina(d.uuid) }, 'Elimina')));
    lista.append(scheda);
  });
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
  // Scollegati non c'e' modulo che tenga, nemmeno se `stato.modifica` e' pieno:
  // il documento aperto aspetta li' dentro che il broker torni.
  document.body.dataset.collegato = stato.collegato ? 'si' : '';
  document.body.dataset.modulo = stato.collegato && stato.modifica ? 'si' : '';
  riempi(
    $('stato-broker'),
    'Collegato a ', el('code', {}, stato.broker),
    ' · prefisso ', el('code', {}, stato.prefisso),
  );
  $('topic-corrente').textContent = stato.topic;
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
$('cambia').addEventListener('click', cambiaBroker);
$('aggiungi').addEventListener('click', () => apriModulo(vuoto()));
riprendi();
disegna();
