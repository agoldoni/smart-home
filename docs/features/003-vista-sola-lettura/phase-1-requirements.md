# Vista principale in sola lettura — Requisiti

**Stato:** Fase 1 — da rivedere
**Autore:** Alberto Goldoni
**Data:** 13 settembre 2026
**Versione:** 1.0
**Feature:** `003-vista-sola-lettura`

---

## 1. Obiettivo e motivazione

La vista principale è una colonna di interruttori, uno per dispositivo, larghi quanto lo
schermo e messi lì apposta perché «l'interruttore cada sotto il pollice». Funziona: è anche
il motivo per cui un tocco distratto accende il boiler.

Oggi non esiste un modo di **guardare** l'app. Aperta, è sempre armata. Chi la apre per
vedere quanto ha consumato la lavastoviglie, o per far vedere a qualcuno com'è fatta, tiene
in mano sette comandi che agiscono su cose fisiche di casa.

Che il problema sia reale lo dice il codice stesso: in cima alla barra c'è un contatore dei
comandi inviati, e il commento che lo accompagna spiega perché — *«il contatore risponde alla
domanda che ci si fa davanti all'elenco: quel dispositivo che si è mosso, l'ho mosso io? Se
il numero non cambia, la risposta è no»*. Quel contatore è la **diagnosi a posteriori** di
un tocco involontario. Questa feature è la prevenzione: un interruttore in barra che rende
inerti i comandi, e che si ricorda com'era stato lasciato.

**Metriche di successo:**

- Un tocco su un interruttore, in sola lettura, **non produce nessun messaggio MQTT**: il
  contatore dei comandi inviati non si muove
- Lo stato sopravvive alla chiusura dell'app: riaperta, la vista è com'era stata lasciata
- Si capisce **dal solo colpo d'occhio** in quale delle due modalità si è, senza toccare
  niente e senza aprire le Impostazioni
- Zero regressioni sul giro dei comandi in scrittura: sbloccata, l'app si comporta
  esattamente come la 1.1.0

---

## 2. Scope

### Incluso

**Il comportamento**
- Un interruttore a due stati — **scrittura** e **sola lettura** — che vale per la sola
  vista principale
- In sola lettura sono inerti **i comandi ai dispositivi**: l'interruttore di accensione e
  il cursore del livello. Nient'altro
- Lo stato è **persistito fra un lancio e l'altro**
- Alla prima installazione si parte in **scrittura**: la 1.1.0 si comporta così, e un
  aggiornamento non deve cambiare il comportamento sotto i piedi

**L'interfaccia**
- Icona nella barra in alto, **fra il "+" e l'ingranaggio delle impostazioni**
- Un tocco blocca, un tocco sblocca. Nessuna conferma, nessuna pressione lunga
- I controlli bloccati **si vedono** che sono bloccati: devono apparire inerti *prima* del
  tocco, non ignorarlo dopo
- L'icona dichiara lo stato anche a TalkBack

**Diagnostica**
- La modalità corrente compare in `/state`, come già fanno le altre impostazioni

### Escluso (out of scope)

- **Qualsiasi protezione più forte di un tocco**: niente PIN, niente pressione lunga, niente
  biometria. È una protezione dal tocco distratto, non dalle persone
- **Blocco per singolo dispositivo**: il blocco è della vista, non della presa
- **Blocco della modifica**: la scheda di un dispositivo si apre lo stesso, il "+" resta
  dov'è, le Impostazioni pure. Si blocca ciò che ha effetto sul mondo fisico
- **Sincronizzazione fra telefoni**: è una preferenza locale, non entra nel registro
  condiviso. Due telefoni possono essere in modalità diverse, ed è giusto così
- **Qualunque effetto sui dispositivi veri**: le prese restano comandabili dagli altri
  telefoni, dal ponte e dall'app Tuya. Questa feature non tocca il broker

---

## 3. User Stories

**US-1 — Guardare senza toccare**
> Come chi apre l'app con le mani occupate, voglio mettere la vista in sola lettura, per
> leggere consumi e stato delle prese senza rischiare di spegnere il boiler con il pollice.

**US-2 — Passare il telefono a qualcuno**
> Come chi fa vedere l'app a un ospite o a un figlio, voglio che gli interruttori siano
> inerti finché non li riabilito io, per mostrare com'è fatta casa senza che succeda niente.

**US-3 — Ritrovarla come l'ho lasciata**
> Come chi tiene l'app normalmente bloccata, voglio che lo stato sopravviva alla chiusura,
> per non dover rimettere il blocco a ogni apertura.

**US-4 — Capire perché non risponde**
> Come chi tocca un interruttore che non si muove, voglio vedere dalla barra che la vista è
> bloccata, per non pensare che sia caduto il collegamento al broker.

**US-5 — Tornare operativo subito**
> Come chi deve accendere davvero qualcosa, voglio sbloccare con un tocco solo e comandare
> subito, per non pagare il blocco ogni volta che mi serve l'app per quello che fa.

---

## 4. Criteri di accettazione

### US-1 — Guardare senza toccare
- [ ] In sola lettura, toccare l'interruttore di un dispositivo **non invia nessun
      messaggio**: il contatore dei comandi inviati resta fermo
- [ ] L'interruttore **non cambia posizione** al tocco, nemmeno per un istante
- [ ] Il cursore del livello non si sposta e non invia
- [ ] Lo stato dei dispositivi continua ad aggiornarsi: le schede restano vive, i consumi
      si muovono, il banner di connessione funziona

### US-2 — Passare il telefono
- [ ] Il blocco vale per tutti i dispositivi in elenco, compresi quelli aggiunti dopo
- [ ] Nessun percorso alternativo comanda un dispositivo dalla vista principale mentre è
      bloccata

### US-3 — Persistenza
- [ ] Bloccata l'app, chiusa e riaperta: è ancora bloccata
- [ ] Sbloccata, chiusa e riaperta: è ancora sbloccata
- [ ] Sopravvive alla morte del processo, non solo al ritorno da background
- [ ] **Durante il caricamento della preferenza, la vista non è comandabile**: un tocco nei
      primi frame non deve passare per il solo fatto che il valore non è ancora arrivato
- [ ] All'aggiornamento da 1.1.0 la modalità iniziale è **scrittura**

### US-4 — Leggibilità dello stato
- [ ] L'icona in barra distingue i due stati **senza bisogno di colore** (forma diversa, non
      solo tinta): si legge anche da chi non distingue i colori
- [ ] I controlli bloccati appaiono disabilitati prima del tocco
- [ ] TalkBack annuncia lo stato corrente e cosa fa il tocco
- [ ] L'icona sta fra il "+" e l'ingranaggio, e il tocco resta di almeno 48dp

### US-5 — Ritorno in scrittura
- [ ] Un tocco solo riporta in scrittura, da qualunque punto dell'elenco
- [ ] Subito dopo lo sblocco un comando parte e arriva: nessuna riconnessione, nessuna
      attesa
- [ ] `/state` riporta la modalità corrente

---

## 5. Rischi e dipendenze

| # | Rischio | Probabilità | Impatto | Mitigazione |
|---|---|---|---|---|
| R-1 | **L'interruttore si muove e torna indietro.** Se il blocco intercetta il comando a valle invece di disabilitare il controllo, l'utente vede l'interruttore scattare e tornare: sembra un guasto, non un blocco | Alta | Medio | Lo stato deve arrivare al controllo e disabilitarlo, non filtrare il comando dopo il tocco. È il criterio di US-1 |
| R-2 | **La finestra fra il primo frame e il valore letto.** La preferenza arriva asincrona: se il valore iniziale è "scrittura", per qualche frame la vista è armata anche quando doveva essere bloccata | Media | Alto | Nessuno stato comandabile finché la preferenza non è stata letta davvero. È un criterio esplicito di US-3 |
| R-3 | **Scambiare il blocco per una misura di sicurezza.** Non protegge niente: le prese restano comandabili da ogni altro telefono, dal ponte e dall'app Tuya | Media | Basso | Detto nello scope e nella documentazione. Il blocco è contro il tocco distratto |
| R-4 | **Affollamento della barra.** Diventano tre icone più l'insetto rosso dell'API di debug, su un titolo che ha già due righe di servizio | Media | Basso | Da guardare su schermo vero a nomi lunghi, prima di chiudere la feature |
| R-5 | **Il blocco copre solo la vista principale.** Dalla scheda di un dispositivo si potrebbe comandare lo stesso, e sarebbe una promessa non mantenuta | Bassa | Medio | Da verificare in Fase 2: se esistono comandi fuori dalla lista, o rientrano nel blocco o vanno detti nello scope |

**Dipendenze:** nessuna esterna. La persistenza si appoggia a DataStore, già in uso
nell'app per le impostazioni del broker e del registro.

---

## 6. Stima effort

| Area | Giorni/uomo | Cosa |
|---|---|---|
| Dati e persistenza | 0,25 | Preferenza persistita, lettura senza finestra scoperta |
| Interfaccia | 0,50 | Icona in barra, stati dei controlli, accessibilità |
| Test | 0,35 | Unit sul filtro dei comandi e sulla persistenza, verifica sul telefono |
| Documentazione | 0,15 | README e nota in `/state` |
| **Totale** | **1,25** | |

La feature è piccola e tutta dentro l'app: nessun tocco al broker, al ponte o al
configuratore, e nessun cambio al documento del registro. Il costo vero non è scrivere il
blocco — è farlo apparire prima del tocco invece che dopo (R-1) e chiudere la finestra
all'avvio (R-2).

**Versione:** 1.1.0 → **1.2.0** (versionCode 10). Aggiunge un comportamento, non corregge.

---

## 7. Milestones

1. **La preferenza** — stato persistito e letto, con un valore iniziale che non lascia la
   vista armata durante il caricamento
2. **Il filtro** — i comandi della vista principale passano solo in scrittura, verificato
   dal contatore fermo
3. **L'icona in barra** — fra "+" e ingranaggio, due stati distinguibili per forma, tocco
   da 48dp, etichette per TalkBack
4. **I controlli inerti** — interruttori e cursori disabilitati alla vista, non solo
   silenziosi al tocco
5. **`/state`** — la modalità corrente nella diagnostica
6. **Verifica sul telefono** — i due stati, la chiusura e riapertura, l'aggiornamento da
   1.1.0, e la barra guardata davvero con i nomi lunghi
7. **Documentazione e versione** — README, `versionCode`/`versionName`

---

## Domande aperte

Chiuse entrambe in revisione, 13 settembre 2026.

1. ~~**Il blocco deve valere anche fuori dalla vista principale?**~~ **No.** Il blocco è
   della vista principale e basta. Se in Fase 2 emergono comandi altrove, restano fuori e
   la cosa va detta nel README, non estesa.
2. ~~**L'interruttore va ripetuto in Impostazioni?**~~ **No.** Vive solo in barra. Una
   seconda copia sarebbe una seconda verità da tenere allineata, per un interruttore che si
   raggiunge già in un tocco da dove serve.
