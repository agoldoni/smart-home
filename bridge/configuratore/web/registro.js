// Il documento del registro: come si scrive, come si rilegge, cosa si rifiuta.
//
// Le regole sono quelle di SCHEMA.md e sono le stesse che applica l'app: qui non
// devono essere piu' permissive, altrimenti si pubblicano documenti che dall'altra
// parte vengono scartati in silenzio.

export const SCHEMA = 1;

export function topicRegistro(prefisso) {
  const p = (prefisso || '').trim().replace(/^\/+|\/+$/g, '') || 'casa';
  return `${p}/registro/dispositivi`;
}

/** I campi che, vuoti, vanno scritti come null e non come stringa vuota. */
const NULLABILI = [
  'campo_stato', 'campo_potenza',
  'topic_energia', 'campo_kwh_oggi', 'campo_kwh_ieri',
  'campo_kwh_settimana', 'campo_kwh_mese',
  'topic_disponibilita',
  'topic_stato_livello', 'topic_comando_livello', 'campo_livello',
];

const haWildcard = (s) => typeof s === 'string' && (s.includes('+') || s.includes('#'));

/**
 * Le stesse cinque validazioni del modulo Android.
 *
 * Restituisce una mappa campo -> messaggio, vuota se va tutto bene.
 */
export function valida(d) {
  const errori = {};
  if (!String(d.nome || '').trim()) errori.nome = 'Il nome è obbligatorio';
  if (!String(d.topic_stato || '').trim()) errori.topic_stato = 'Il topic di stato è obbligatorio';

  if (d.tipo !== 'SENSOR') {
    if (!String(d.topic_comando || '').trim()) {
      errori.topic_comando = 'Il topic di comando è obbligatorio';
    } else if (haWildcard(d.topic_comando)) {
      // Le wildcard valgono per chi ascolta: pubblicare su + o # non significa
      // niente, il broker rifiuta il messaggio.
      errori.topic_comando = 'Un topic di comando non può contenere + o #';
    }
  }

  if (d.tipo === 'DIMMER') {
    if (!String(d.topic_comando_livello || '').trim()) {
      errori.topic_comando_livello = 'Serve il topic per regolare il livello';
    } else if (haWildcard(d.topic_comando_livello)) {
      errori.topic_comando_livello = 'Un topic di comando non può contenere + o #';
    }
  }

  // Un topic dei consumi senza il campo da leggerci dentro e' il modo piu'
  // silenzioso di non funzionare: l'app si iscrive, i messaggi arrivano, e la
  // scheda resta vuota senza che niente lo dica.
  if (String(d.topic_energia || '').trim() && !String(d.campo_kwh_oggi || '').trim()) {
    errori.campo_kwh_oggi = 'Serve il campo da leggere nel payload dei consumi';
  }

  return errori;
}

/**
 * Il posto nell'elenco, oppure null. Le regole di SCHEMA.md, in un punto solo.
 *
 * Mai zero per ripiego: zero e' il **primo** posto, e un valore che non si e'
 * capito non puo' portare un dispositivo in cima alla casa. Assente, negativo,
 * con la virgola o scritto come stringa valgono tutti "nessuno lo ha collocato".
 */
export function posizione(d) {
  const v = d ? d.posizione : null;
  if (typeof v !== 'number' || !Number.isInteger(v) || v < 0) return null;
  return v;
}

/**
 * L'ordine dichiarato dal registro: prima chi ha un posto, poi gli altri, e a
 * parita' per nome.
 *
 * E' la stessa regola dell'`ORDER BY` dell'app, e devono restare la stessa cosa:
 * due telefoni che ordinano diversamente sono due case diverse. Lo spareggio sul
 * nome usa `localeCompare` mentre l'app usa `COLLATE NOCASE`, e i due divergono
 * solo su nomi che differiscono per accento o maiuscola — il caso in cui lo
 * spareggio serve davvero e' quello di due posti uguali, che chi scrive non
 * produce mai.
 */
export function ordina(dispositivi) {
  return [...dispositivi].sort((a, b) => {
    const pa = posizione(a);
    const pb = posizione(b);
    if (pa !== pb) {
      if (pa === null) return 1;
      if (pb === null) return -1;
      return pa - pb;
    }
    return String(a.nome ?? '').localeCompare(String(b.nome ?? ''));
  });
}

/**
 * Rinumera tutti da zero, nell'ordine in cui stanno.
 *
 * Si usa **solo dopo un riordino**, ed e' il momento in cui anche chi non aveva
 * un posto ne prende uno: trascinare una riga vuol dire decidere l'ordine di
 * tutto l'elenco, non solo di quella. Fuori di qui le posizioni non si toccano.
 */
export function rinumera(dispositivi) {
  return dispositivi.map((d, i) => ({ ...d, posizione: i }));
}

/**
 * Il posto per un dispositivo nuovo: in fondo, ma **solo se qualcun altro ne ha
 * gia' uno**.
 *
 * La condizione e' tutta la regola. In un registro dove nessuno ha ancora
 * riordinato, dare `posizione: 0` al primo aggiunto lo farebbe schizzare in cima
 * a tutti gli altri — che sono senza posto, quindi in fondo per definizione. Un
 * campo invisibile che riordina la casa di sorpresa e' il difetto peggiore che
 * questo campo possa avere.
 */
export function prossimaPosizione(dispositivi) {
  const posti = dispositivi.map(posizione).filter((p) => p !== null);
  return posti.length ? Math.max(...posti) + 1 : null;
}

function serializza(d) {
  const testo = (k, predefinito = '') => {
    const v = String(d[k] ?? '').trim();
    return v || predefinito;
  };
  const opzionale = (k) => {
    const v = String(d[k] ?? '').trim();
    return v || null;
  };

  const dimmer = d.tipo === 'DIMMER';
  const fuori = {
    uuid: d.uuid,
    nome: testo('nome'),
    tipo: d.tipo,
    topic_stato: testo('topic_stato'),
    campo_stato: opzionale('campo_stato'),
    topic_comando: d.tipo === 'SENSOR' ? '' : testo('topic_comando'),
    payload_on: testo('payload_on', 'ON'),
    payload_off: testo('payload_off', 'OFF'),
    campo_potenza: opzionale('campo_potenza'),
    topic_energia: opzionale('topic_energia'),
    campo_kwh_oggi: opzionale('campo_kwh_oggi'),
    campo_kwh_ieri: opzionale('campo_kwh_ieri'),
    campo_kwh_settimana: opzionale('campo_kwh_settimana'),
    campo_kwh_mese: opzionale('campo_kwh_mese'),
    topic_disponibilita: opzionale('topic_disponibilita'),
    payload_disponibile: testo('payload_disponibile', 'online'),
    payload_non_disponibile: testo('payload_non_disponibile', 'offline'),
    topic_stato_livello: dimmer ? opzionale('topic_stato_livello') : null,
    topic_comando_livello: dimmer ? opzionale('topic_comando_livello') : null,
    campo_livello: dimmer ? opzionale('campo_livello') : null,
    livello_max: Math.min(65535, Math.max(1, Number(d.livello_max) || 100)),
    qos: Math.min(2, Math.max(0, Number(d.qos) || 0)),
    ritenuto: Boolean(d.ritenuto),
    // Non e' un campo del modulo, ma va scritto qui lo stesso: questo oggetto e'
    // costruito da una lista chiusa di chiavi, e cio' che non compare in questa
    // lista non viene ripubblicato. Dimenticarlo cancellerebbe l'ordine della
    // casa al primo salvataggio di un dispositivo qualsiasi.
    posizione: posizione(d),
  };
  for (const k of NULLABILI) if (fuori[k] === '') fuori[k] = null;
  return fuori;
}

/** Il documento intero, pronto da pubblicare ritenuto. */
export function documento(dispositivi, revisione) {
  return JSON.stringify(
    {
      schema: SCHEMA,
      revisione,
      aggiornato: new Date().toISOString(),
      dispositivi: dispositivi.map(serializza),
    },
    null,
    1,
  );
}

/**
 * Rilegge quello che c'e' sul broker.
 *
 * Il rifiuto e' in blocco per la testata e per dispositivo per il contenuto,
 * come fa l'app: uno schema sconosciuto mette in dubbio ogni campo, un tipo
 * sconosciuto mette in dubbio un dispositivo solo.
 */
export function leggiRegistro(payload) {
  const testo = (payload || '').trim();
  if (!testo) return { stato: 'vuoto' };

  let radice;
  try {
    radice = JSON.parse(testo);
  } catch (e) {
    return { stato: 'rifiutato', motivo: `non è JSON: ${e.message}` };
  }
  if (!radice || typeof radice !== 'object' || Array.isArray(radice)) {
    return { stato: 'rifiutato', motivo: 'non è un oggetto JSON' };
  }
  const schema = Number(radice.schema ?? 1);
  if (schema > SCHEMA) {
    return { stato: 'rifiutato', motivo: `schema ${schema}, questa pagina ne conosce ${SCHEMA}` };
  }
  if (!Array.isArray(radice.dispositivi)) {
    return { stato: 'rifiutato', motivo: "manca l'elenco 'dispositivi'" };
  }

  const dispositivi = [];
  const scartati = [];
  const visti = new Set();
  radice.dispositivi.forEach((voce, i) => {
    if (!voce || typeof voce !== 'object') return scartati.push(`voce ${i + 1}: non è un oggetto`);
    const uuid = String(voce.uuid ?? '').trim();
    if (!uuid) return scartati.push(`voce ${i + 1}: manca l'uuid`);
    if (visti.has(uuid)) return scartati.push(`voce ${i + 1} (${uuid}): uuid duplicato`);
    visti.add(uuid);
    // La pagina e' piu' tollerante dell'app nel *leggere*: quello che non capisce
    // lo tiene com'e', cosi' non lo cancella ripubblicando. A rifiutarlo ci pensa
    // chi lo applica.
    dispositivi.push({ ...voce, uuid });
  });

  return {
    stato: 'ok',
    schema,
    revisione: Number(radice.revisione ?? 0),
    aggiornato: radice.aggiornato ?? null,
    dispositivi,
    scartati,
  };
}
