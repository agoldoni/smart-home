# Tema chiaro, scuro, di sistema — Implementation Plan

**Stato:** Implementato e verificato sul telefono — restano TC-11 su un Android 8-11 e TalkBack
**Autore:** Alberto Goldoni
**Data:** 19 settembre 2026
**Versione:** 1.2 (19 settembre 2026: esito del giro sul telefono, R-12 e la correzione della finestra d'avvio)
**Feature:** `007-tema-chiaro-scuro`

---

## 0. Stato di avanzamento

**19 settembre 2026 — implementato e verificato sul telefono.** T-01…T-16 fatti, T-14
compreso. Restano due verifiche che qui non si possono fare, elencate in fondo.

### Verificato sul PC

- `./gradlew testDebugUnitTest assembleDebug lintDebug` passa: **117 test, 0 falliti**
- **Lint: nessuna segnalazione nuova.** Restano le undici di prima — il tetto dichiarato del
  catalogo delle versioni e due stringhe del registro inutilizzate. Una nuova c'era ed è
  stata chiusa: `UseKtx` su `ColorDrawable`, sostituito da `toDrawable()`
- **T-13, chiusura di R-1:** `grep -rn isSystemInDarkTheme app/src` dà **una sola chiamata**,
  `MainActivity.kt:43`, più il suo import. Erano tre

### Verificato sul telefono, contro `devops/dev`

Xiaomi 23117RA68G, Android 16, sette prese finte, API di debug accesa. Il telefono aveva
già la 1.4.0 con la **vista bloccata**, che è la precondizione che serviva a TC-15.

| TC | Esito | Come |
|---|---|---|
| **TC-15** | ✅ | Installata la 1.5.0 sopra la 1.4.0: `vista.preferences_pb` **non toccato** (stessi byte, stessa data), lucchetto ancora chiuso a schermo. R-8 non è scattato |
| **TC-05** | ✅ | Matrice 2×3 completa. Il caso che conta — telefono **chiaro**, app **scura** — ha lo sfondo della scheda accesa a `#1F5B28`, cioè la variante scura, campionato dal pixel |
| **TC-06** | ✅ | Insetto di debug campionato su sei scatti (pulsa): `#FF5A5A` con tema scuro forzato, `#D63C3D` con tema chiaro forzato su telefono scuro — cioè `#D32F2F` sfumato dall'antialiasing. Le due varianti giuste, dalla parte della scelta e non del telefono |
| **TC-07** | ✅ | Barre di stato e navigazione con il contrasto giusto in tutte le combinazioni, elenco e impostazioni |
| **TC-08** | ✅ **dopo una correzione** | Avvio a freddo filmato e analizzato a 60 fps: **0 fotogrammi chiari su 300** con «Scuro» su telefono chiaro. Prima della correzione erano 130, cioè 2,2 secondi di bianco pieno. Vedi la decisione 9 |
| **TC-09** | ✅ | Rotazione e riapertura dai recenti: scelta invariata, broker connesso |
| **TC-10** | ✅ | Con «Sistema» e app aperta, `cmd uimode night yes` porta `themeEffective` da `light` a `dark`. Con «Scuro» il telefono cambia e l'app non si muove |
| **TC-12** | ✅ | Cinque cambi di tema di fila: `collegamento.tentativi` resta **1**, `sottoscrizioni.aggiunte` resta 42. Il broker non se ne accorge, e non è un'impressione: sono i contatori |
| **TC-13** | ✅ (in parte) | Dal dump di uiautomator i tre segmenti sono 119-121 × **48 dp**, `checkable=true`, e quello attivo ha `checked=true` con classe `RadioButton`. La semantica c'è; **sentirla con TalkBack acceso resta da fare** |
| **TC-14** | ✅ | `debug-api.py --adb state` → `view: {'locked': …, 'theme': 'scuro', 'themeEffective': 'dark'}` |
| **TC-11** | ⚠️ metà | Android 12+ con colori dinamici: fatto, è il telefono della prova. **Un Android 8-11 non c'era**: la strada è la stessa su tutte le versioni, ma la finestra d'avvio no (vedi R-12) |

Il file `vista` alla fine del giro contiene entrambe le chiavi, e il tema **come stringa**:

```
0a0e 0a08 626c 6f63 6361 7461 1202 0801   bloccata = true
0a11 0a04 7465 6d61 1209 2a07 5349 5354   tema = SIST
454d 41                                   EMA
```

### Nove cose decise scrivendo, che il piano non diceva

1. **`ThemeChoice` sta in `data/settings/`, non in `ui/theme/`.** È un dato salvato, sta in
   compagnia di `BrokerSettings` e `RegistrySettings`, e il verso delle dipendenze resta
   quello di tutto il resto dell'app: l'interfaccia legge le impostazioni, non il contrario
2. **`SmartHomeTheme(darkTheme: Boolean)` ha perso il valore predefinito.** Il piano diceva
   «la firma non cambia». Cambiarla è il motivo per cui il criterio di R-1 adesso è una
   proprietà del codice e non una promessa: senza predefinito nessuno può lasciare che un
   pezzo di app segua il telefono per distrazione, perché non compila
3. **Sei stringhe invece di cinque:** la riga di spiegazione cambia con la scelta, perché con
   «Sistema» la cosa da dire è che seguirà il telefono e con le altre due che **non** lo
   seguirà più
4. **I veli della barra di navigazione sono ricopiati a mano** (`0xe6FFFFFF` e `0x801b1b1b`).
   `enableEdgeToEdge()` li mette da sé dove il sistema non sa fare il contrasto — prima di
   Android 10 — ma nella libreria sono privati, e passarne altri cambierebbe l'aspetto della
   barra sui telefoni più vecchi
5. **Lo sfondo della finestra si impone due volte:** prima di `setContent` e dentro il
   `DisposableEffect`
6. **`getColor(...).toDrawable()`** invece di `ColorDrawable(...)`, per tenere la DoD sul lint
7. **La lettura bloccante è protetta da `runCatching`.** Mettendola sul cammino dell'avvio,
   un file di preferenze illeggibile smetterebbe di costare un tema sbagliato e comincerebbe
   a costare un'app che non parte. Il ripiego è `SISTEMA`, che è anche il predefinito
8. **La voce si chiama «Sistema», non «Come il sistema».** Provata sul telefono, la versione
   lunga andava a capo dentro un segmento largo un terzo di schermo e i 48 dp la tagliavano
   a metà. Quello che la voce non dice per esteso lo dice la riga sotto
9. **La finestra d'avvio non è dell'Activity, e il piano lo ignorava.** Vedi R-12 qui sotto:
   è la correzione che ha cambiato TC-08 da 2,2 secondi di bianco a zero fotogrammi chiari

### R-12 — La finestra che si vede prima che il processo esista

*Emerso da TC-08, non previsto in nessuna delle tre fasi.*

`window.setBackgroundDrawable()` in `onCreate` non tocca la finestra d'avvio: quella la
disegna il **sistema**, dal tema dichiarato nel manifest, prima che il processo dell'app
esista. E quel tema segue la configurazione del telefono. Con «Scuro» su un telefono chiaro
si vedeva un rettangolo bianco pieno per tutta la durata dell'avvio — misurata, 2,2 secondi
su 1,9 s di `TotalTime` più il resto.

La correzione è l'unica possibile: da Android 12 si dichiara **oggi il tema del prossimo
avvio**, con `splashScreen.setSplashScreenTheme(...)`, e i due temi
`Theme.SmartHome.Avvio.Chiaro` / `.Scuro` hanno il colore scritto invece di ereditarlo dalla
configurazione. Due conseguenze da sapere, entrambe accettate:

- **una scelta appena cambiata vale dall'avvio dopo**, non da quello immediatamente
  successivo al tocco — il sistema usa quello che gli è stato detto l'ultima volta
- **sotto Android 12 non c'è rimedio**: restano i decimi di secondo della finestra d'avvio
  col colore del telefono. È anche la ragione per cui TC-11 su un Android 8-11 non è una
  formalità

### R-4, misurato

La lettura bloccante del tema costa **~37 ms** (due misure: 37,6 e 35,9) su un avvio a
freddo di **~1,9 s**, cioè il 2%. È il prezzo dei zero fotogrammi sbagliati di TC-08, e si
paga una volta per avvio del processo.

### Da finire

- [ ] **TC-11 su un Android 8-11.** Non è una formalità: è l'unica versione dove la finestra
      d'avvio resta del colore del telefono (R-12)
- [ ] **TC-13 con TalkBack acceso.** La semantica è verificata dal dump — tre `RadioButton`,
      `checked` sul giusto, 48 dp — ma sentirla annunciare è un'altra cosa
- [ ] **Rilettura a distanza di un giorno**, come per le altre feature

---

## 1. Executive Summary

L'app oggi prende il tema dal telefono e basta: chi tiene il sistema in chiaro si prende una
schermata bianca quando apre l'app al buio per spegnere una presa, e l'unico rimedio è
cambiare il tema di tutto il telefono. Questa feature aggiunge una scelta a tre voci —
**Chiaro, Scuro, Come il sistema** — nelle impostazioni dell'app, salvata su quel telefono.

Il grosso del lavoro non è il selettore: sono tre punti dell'app che oggi leggono il tema di
sistema per conto loro — il verde della presa accesa, il rosso del segno di debug e le barre
di stato e navigazione — e che devono seguire la scelta, altrimenti l'app risulta metà scura
e metà chiara. Nessuna libreria nuova, nessun cambio al ponte o al broker, nessuna
migrazione di dati. **Stima: 1,1 giorni/uomo**, rilascio in un pezzo solo con la 1.5.0.

---

## 2. Obiettivo e motivazione

- **Problema che risolve:** l'app si apre al buio e non si può tenere scura senza mettere in
  scuro l'intero telefono. E l'unico modo di vedere come rende l'altra variante — cosa che
  qui conta, perché due colori sono scritti a mano fuori dallo schema — è cambiare le
  impostazioni del sistema operativo.

- **Metriche di successo:**
  - [ ] La scelta si fa in due tocchi dall'elenco e ha effetto **subito**, senza riavviare
        l'app e senza cambiare schermata
  - [ ] Chi aggiorna e non tocca niente **non vede nessuna differenza**: il predefinito è
        «Come il sistema»
  - [ ] La scelta sopravvive a chiusura dell'app, rotazione e riavvio del telefono
  - [ ] **Niente resta indietro:** con «Scuro» su telefono chiaro, verde dell'acceso, rosso
        del debug, barre di sistema e sfondo della finestra sono tutti scuri, in tutte e tre
        le schermate
  - [ ] **Nessun lampeggio** all'avvio a freddo
  - [ ] `grep -rn isSystemInDarkTheme app/src` restituisce **una riga sola** (oggi tre)

- **Legame con il resto del progetto:** è la terza preferenza «di questo telefono», dopo il
  lucchetto della vista (feature 003) e il prefisso del registro. Si comporta come il
  lucchetto: sta in locale, non passa dal registro condiviso, e ogni telefono ha la sua.

---

## 3. Scope

### Incluso

- Terna `SISTEMA` / `CHIARO` / `SCURO` persistita nel file di preferenze `vista`
- Sezione «Aspetto» in fondo alla schermata Impostazioni, tre segmenti a scelta singola
- `SmartHomeTheme` alimentato dal booleano risolto, che lo rimette a disposizione con un
  `CompositionLocal`
- `poweredColors()` e `debugRed()` agganciati a quella stessa fonte
- Barre di sistema e sfondo della finestra coerenti con la scelta, primo fotogramma compreso
- Due chiavi nell'API di debug: `view.theme` e `view.themeEffective`
- Rename `ViewLockStore` → `ViewPrefsStore` (il nome del file su disco resta `vista`)

### Escluso (out of scope)

- **Il configuratore web** — segue `prefers-color-scheme` e resta così: è una pagina che si
  apre dal computer per configurare, non l'app che si guarda al buio
- **L'interruttore dei colori dinamici (Material You)** — restano accesi come oggi: due
  impostazioni vorrebbero dire quattro combinazioni da provare per un guadagno che nessuno
  ha chiesto
- **Temi personalizzati, scelta della tinta, rifacimento della palette** — `LightColors` e
  `DarkColors` restano quelli che sono
- **Sincronizzazione fra telefoni via registro** — il registro porta i dispositivi, non
  l'aspetto
- **Pianificazione oraria** («scuro dal tramonto») — la dà già il sistema, e chi la vuole
  lascia «Come il sistema»
- **Contrasto elevato, dimensione del testo** — altra feature, altri criteri

### Decisioni prese

Nessuna decisione resta aperta. Le sei emerse nelle Fasi 1 e 2:

| # | Decisione | Presa in | Perché |
|---|---|---|---|
| **D-1** | Solo app Android, il configuratore web non si tocca | Fase 1 | Il caso d'uso è l'app al buio; la pagina web si apre dal PC |
| **D-2** | I colori dinamici restano accesi, nessun interruttore | Fase 1 | Una sola impostazione da capire invece di quattro combinazioni da provare |
| **D-3** | **Booleano esplicito**, non configurazione forzata né `UiModeManager` | Fase 1 §8 | Le alternative ricreano l'Activity a ogni cambio (AC-1.5 lo esclude) e poggiano su comportamenti di piattaforma con quirk noti |
| **D-4** | La preferenza va nel file `vista`, accanto al lucchetto | Fase 1 §8 | Stessa categoria: preferenza della vista di questo telefono. Vietato il file del broker, che il driver osserva |
| **D-5** | Rename `ViewLockStore` → `ViewPrefsStore`, prima di tutto il resto | 19 set | Due chiavi scorrelate sotto un nome che dice «lucchetto» invecchiano male. 4 file, 6 righe |
| **D-6** | Le due chiavi di debug vanno in `view()`, non in `identity()` | Fase 2 | `view()` esiste già e contiene `locked`: è la stessa categoria di informazione. Corregge la Fase 1 |

---

## 4. User Stories e criteri di accettazione

### US-001 · Tenere l'app scura su un telefono chiaro
**Priorità:** Must Have

Come persona che apre l'app al buio prima di dormire, voglio poter mettere l'app in scuro
senza cambiare il tema del telefono, per non prendermi una schermata bianca in faccia.

**Criteri di accettazione:**
- [ ] Telefono in chiaro + «Scuro»: elenco, modifica dispositivo e impostazioni sono scure
- [ ] La scheda di una presa accesa mostra il verde della variante scura (`0xFF1F5B28` su
      `0xFFD8F3D9`)
- [ ] Con l'API di debug accesa, l'insetto in barra usa il rosso scuro (`0xFFFF5A5A`)
- [ ] Il caso speculare vale identico: telefono scuro + «Chiaro» → app interamente chiara
- [ ] Cambiando la scelta l'interfaccia si aggiorna **senza uscire dalla schermata** e senza
      che l'app riparta da capo

### US-002 · Tornare al comportamento di sempre
**Priorità:** Must Have

Come persona che ha già il telefono impostato bene, voglio che l'app continui a seguire il
sistema senza configurare niente, per non perdere quello che funzionava.

**Criteri di accettazione:**
- [ ] Aggiornando dalla 1.4.0 senza toccare niente: voce attiva «Come il sistema», aspetto
      identico a prima
- [ ] Il lucchetto della vista salvato prima dell'aggiornamento **è ancora lì** (il rename
      non deve perdere il file `vista`)
- [ ] Con «Come il sistema», cambiando il tema del telefono ad app aperta, l'app segue
- [ ] Con «Chiaro» o «Scuro», cambiare il tema del telefono non ha nessun effetto
- [ ] Riselezionare «Come il sistema» ripristina l'aggancio senza riavviare l'app

### US-003 · Vedere l'altra resa senza cambiare il telefono
**Priorità:** Should Have

Come chi sviluppa questa app, voglio passare da chiaro a scuro da dentro le Impostazioni,
per verificare il verde dell'acceso e il rosso del debug sulle schede vere in pochi secondi.

**Criteri di accettazione:**
- [ ] Il selettore si raggiunge dall'elenco in due tocchi (ingranaggio → «Aspetto»)
- [ ] Fra il tocco e l'interfaccia ridisegnata non c'è nessun caricamento visibile
- [ ] `GET /state` riporta la scelta e il tema effettivo, distinti

### US-004 · Una scelta che resta e che vale per tutto
**Priorità:** Must Have

Come persona che ha scelto un tema, voglio ritrovarlo alla riapertura e voglio che valga per
**tutta** l'interfaccia, per non avere un'app metà scura con la barra di stato illeggibile.

**Criteri di accettazione:**
- [ ] La scelta sopravvive a: chiusura dai recenti, riavvio del telefono, rotazione
- [ ] Avvio a freddo con scelta ≠ sistema: **nessun fotogramma** del tema sbagliato, né
      sfondo della finestra né prima composizione
- [ ] Icone di barra di stato e navigazione con il contrasto giusto, in tutte e tre le
      schermate
- [ ] Con i colori dinamici su Android 12+ la scelta continua a valere: palette dello sfondo
      del telefono, nella variante chiesta
- [ ] Cambiare tema **non tocca il broker**: il collegamento non cade e il contatore dei
      comandi non si azzera

### US-005 · Sceglierlo con TalkBack
**Priorità:** Should Have

Come persona che usa TalkBack, voglio che le tre voci si annuncino come gruppo a scelta
singola con lo stato corrente, per sapere cosa è selezionato prima di cambiare.

**Criteri di accettazione:**
- [ ] Le tre voci sono annunciate come gruppo a scelta singola, con «selezionato» sull'attiva
- [ ] Etichette in italiano comprensibili fuori contesto: «Chiaro», «Scuro», «Come il sistema»
- [ ] Area toccabile di ogni voce ≥ 48 dp — **da imporre**, il segmento Material 3 si
      disegna a 40 dp e non applica la dimensione interattiva minima

---

## 5. Architettura tecnica

### Componenti coinvolti

```
  DataStore "vista"            ┌──────────── MainActivity ─────────────────┐
   chiave "tema"               │                                           │
        │                      │  scelta.scuro(sistemaScuro) = isDark      │
        ▼                      │        │                                  │
  ViewPrefsStore ──────────────┤        ├─► window.setBackgroundDrawable() │  prima di
   (ex ViewLockStore)          │        │      (solo se scelta != SISTEMA) │  setContent
        │                      │        │                                  │
        ▼                      │        ├─► enableEdgeToEdge(auto{isDark}) │  DisposableEffect
  AppContainer.themeChoice ────┤        │                                  │
   StateFlow, primo valore     │        └─► SmartHomeTheme(darkTheme=…)    │
   letto in blocco a freddo    └───────────────────│───────────────────────┘
        │                                          │
        │                                          ├─► LocalDarkTheme ─┬─► poweredColors()
        │                                          │                   └─► debugRed()
        │                                          └─► colori dinamici: dynamicDark/Light
        │                                                 (sceglie gia in base a darkTheme)
        ├─► BrokerSettingsViewModel ─► AspettoSection (3 segmenti)
        └─► DebugReport.view() ─► "theme", "themeEffective"
```

Il punto di tutta l'architettura è la freccia `LocalDarkTheme`: oggi `poweredColors()` e
`debugRed()` chiedono al sistema, domani chiedono al tema. È l'unica cosa che impedisce
all'app di uscire metà chiara e metà scura.

### Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| DataStore `vista` | Modifica (additiva) | Chiave nuova `tema`, stringa. Assente → `SISTEMA`. Il file e la chiave `bloccata` non si toccano |
| `ThemeChoice` | Nuovo | Enum a tre valori + `scuro(sistemaScuro: Boolean)` + parsing tollerante |
| Room / `app/schemas/` | **Nessuna** | Nessuna migrazione: la preferenza non è nel database |
| Registro MQTT / `SCHEMA.md` | **Nessuna** | La preferenza non esce dal telefono |

Il valore è persistito **come stringa e non come ordinale**: un indice cambia significato se
domani si inserisce un valore in mezzo, e questo dato sopravvive agli aggiornamenti.

### Nuove API o endpoint

| Metodo | Path | Descrizione | Auth |
|---|---|---|---|
| GET | `/state` → `view.theme` | La scelta: `sistema` / `chiaro` / `scuro` | No (API di debug, sola lettura, solo da indirizzi privati) |
| GET | `/state` → `view.themeEffective` | Cosa si sta effettivamente vedendo: `dark` / `light` | idem |

Additive, accanto a `view.locked`. Due chiavi e non una perché con «Come il sistema» la
seconda è l'unica informativa. `tools/debug-api.py` non nomina queste chiavi e non va
aggiornato.

### Breaking changes

Nessuno verso l'esterno: nessun topic, nessun payload, nessun contratto del registro, nessun
formato su disco cambia in modo incompatibile. Internamente:

| Componente | Breaking change | Piano di migrazione |
|---|---|---|
| `ViewLockStore` → `ViewPrefsStore` | Rename di classe e proprietà | 6 righe in 4 file (T-01), tutte nello stesso commit. **La stringa `preferencesDataStore(name = "vista")` non si tocca** |
| `BrokerSettingsViewModel` | Costruttore da 3 a 4 parametri | Chiamante unico: `AppViewModelFactory.kt:31`. Nessun test lo istanzia |

---

## 6. Piano di implementazione

| ID | Task | Area | Stima (gg) | Dipende da |
|---|---|---|---|---|
| T-01 | Rename `ViewLockStore` → `ViewPrefsStore`: `ViewLockStore.kt:25`, `AppContainer.kt:16,65,66,74`, `AppViewModelFactory.kt:22`, `DeviceListViewModel.kt:9,55`. **Non toccare `name = "vista"` (riga 23)** | App | 0,05 | — |
| T-02 | `ui/theme/ThemeChoice.kt`: enum, `scuro(sistemaScuro)`, parsing tollerante. Nessun import Compose né Android | App | 0,05 | — |
| T-03 | `ThemeChoiceTest.kt`: i sei casi di §7 | Test | 0,05 | T-02 |
| T-04 | Chiave `tema` in `ViewPrefsStore`: `Flow<ThemeChoice>` + setter sospeso; estendere il commento di testa al secondo inquilino | App | 0,05 | T-01, T-02 |
| T-05 | `AppContainer`: `themeChoice: StateFlow<ThemeChoice>` con valore iniziale da **lettura bloccante** + `scuroOra()` per la diagnostica | App | 0,08 | T-04 |
| T-06 | `Theme.kt`: `LocalDarkTheme` (`staticCompositionLocalOf`), fornito da `SmartHomeTheme`; `poweredColors()` (:76) e `debugRed()` (:90) lo leggono | App | 0,12 | T-02 |
| T-07 | `MainActivity`: lettura prima di `setContent`, `window.setBackgroundDrawable()`, `DisposableEffect(isDark)` con `enableEdgeToEdge(SystemBarStyle.auto(…){ isDark })`, booleano a `SmartHomeTheme` | App | 0,18 | T-05, T-06 |
| T-08 | Cinque stringhe `theme_*` in `strings.xml`, con commento XML sul gruppo | App | 0,03 | — |
| T-09 | `BrokerSettingsViewModel`: quarto parametro, `theme: StateFlow<ThemeChoice>`, `setTheme(...)` — stessa forma di `registry` (:61) e `setFollowRegistry` (:67) | App | 0,04 | T-04 |
| T-10 | `AppViewModelFactory.kt:31`: quarto argomento | App | 0,02 | T-09 |
| T-11 | `AspettoSection` in `BrokerSettingsScreen`, fra riga 179 e 181: `SingleChoiceSegmentedButtonRow`, **altezza imposta a 48 dp**, riga di spiegazione in `bodySmall`/`onSurfaceVariant` | App | 0,12 | T-08, T-09 |
| T-12 | `DebugReport.view()` (:183-185): `theme` e `themeEffective`, calcolati con la stessa funzione pura di T-02 | App | 0,03 | T-05 |
| T-13 | **Chiusura di R-1:** `grep -rn isSystemInDarkTheme app/src` deve dare una riga sola | Test | 0,01 | T-06, T-07 |
| T-14 | Giro manuale: TC-05…TC-15 sul telefono, contro `devops/dev` | Test | 0,20 | T-11, T-12, T-13 |
| T-15 | `README.md`: `### Il tema` in fondo alla sezione «Il broker» (dopo riga 279) | Doc | 0,08 | T-14 |
| T-16 | `versionCode 12 → 13`, `versionName "1.4.0" → "1.5.0"` (`app/build.gradle.kts:30-31`) | Doc | 0,02 | T-14 |

**Stima totale:** 1,03 giorni/uomo, arrotondata a **1,1** per il giro manuale, che è la voce
che sfugge.
**Breakdown:** BE 0 gg · App (FE) 0,77 gg · Test 0,26 gg · Doc 0,10 gg

> La Fase 1 v1.1 dichiarava «≈ 0,7 giorni», ma la sua stessa tabella sommava 0,9: era un
> errore di somma, corretto nella v1.2. La differenza fra 0,9 e 1,03 è il rename D-5 (0,05) e
> la granularità maggiore di questa tabella, che fa emergere righe prima assorbite in altre.

**Ordine e punto di non ritorno:** T-01…T-05 sono invisibili all'utente. Dopo T-07 l'app
cambia tema ma verde, rosso e barre seguono ancora il sistema: **stato non rilasciabile**.
Il primo punto in cui ci si può fermare è **dopo T-13**.

---

## 7. Piano di test

**Strategia generale:** una funzione pura provata per casi, e tutto il resto verificato a
mano sul telefono. Non è una scelta di comodo: il progetto non ha `androidTest`, né
Robolectric, né test di Compose — `app/build.gradle.kts:142-144` dichiara solo tre
`testImplementation`. Aggiungerli per questa feature sarebbe una dipendenza nuova per provare
dei colori, e resterebbe comunque fuori portata la cosa che conta davvero (il lampeggio
all'avvio, che si vede solo filmando lo schermo).

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Unit | `SISTEMA` segue il sistema nei due versi | Alta |
| TC-02 | Unit | `CHIARO` con sistema scuro → chiaro; `SCURO` con sistema chiaro → scuro | Alta |
| TC-03 | Unit | Chiave assente → `SISTEMA` (è ciò che rende invisibile l'aggiornamento) | Alta |
| TC-04 | Unit | Stringa non riconosciuta (`"tramonto"`) → `SISTEMA`, **nessuna eccezione**: è il caso del downgrade, e l'eccezione salterebbe dentro il flusso che alimenta l'interfaccia | Alta |
| TC-05 | Manuale | Matrice 2×3 (sistema chiaro/scuro × tre scelte) su elenco con presa accesa, modifica, impostazioni | Alta |
| TC-06 | Manuale | Insetto di debug rosso nelle due varianti, con API accesa | Alta |
| TC-07 | Manuale | Barre di stato e navigazione in tutte e tre le schermate, per ogni combinazione | Alta |
| TC-08 | Manuale | Avvio a freddo con scelta ≠ sistema: nessun fotogramma sbagliato. **Filmare al rallentatore**, a occhio non si giudica | Alta |
| TC-09 | Manuale | Rotazione, ritorno dai recenti, riavvio del telefono | Alta |
| TC-10 | Manuale | Cambio del tema di sistema ad app aperta: segue con «Sistema», immobile con «Chiaro»/«Scuro» | Alta |
| TC-11 | Manuale | Un Android 12+ con colori dinamici **e** un Android 8-11 senza | Media |
| TC-12 | Manuale | Tre cambi di tema di fila: riga di stato resta «Connesso», contatore dei comandi invariato | Alta |
| TC-13 | Manuale | TalkBack sui tre segmenti + area toccabile ≥ 48 dp | Media |
| TC-14 | Manuale | `python3 tools/debug-api.py state` mostra `theme` e `themeEffective` coerenti fra loro | Media |
| TC-15 | Manuale | **Aggiornamento dalla 1.4.0 con la vista bloccata:** dopo l'aggiornamento il lucchetto è ancora chiuso e la voce attiva è «Come il sistema». È il test che smaschera R-8 | Alta |

TC-15 è il più importante dei manuali, ed è quello che verrebbe saltato: verifica che il
rename non abbia portato via il file di preferenze insieme al nome della classe.

### Definition of Done

- [ ] `./gradlew testDebugUnitTest assembleDebug lintDebug` passa, nessuna segnalazione lint
      nuova
- [ ] `grep -rn isSystemInDarkTheme app/src` → **una riga sola**
- [ ] I test esistenti non sono stati riscritti per farli passare
- [ ] TC-05…TC-15 verificati, con scritto **come** e su quali due telefoni
- [ ] Nessun commento Kotlin nuovo con lettere accentate (convenzione del progetto)
- [ ] `README.md` aggiornato e versione alzata
- [ ] Rilettura a distanza di un giorno

---

## 8. Rischi e mitigazioni

| Rischio | Prob. | Impatto | Mitigazione |
|---|---|---|---|
| **R-1 · Le tinte fuori dallo schema leggono il sistema da sole.** `poweredColors()` e `debugRed()` chiamano `isSystemInDarkTheme()` (`Theme.kt:76,90`): col tema forzato resterebbero dalla parte del telefono | Certa se non si interviene | Alto | T-06 le aggancia al `CompositionLocal`. **T-13 è il test che se ne accorge**: tre occorrenze oggi, una sola dopo |
| **R-8 · Il rename che cancella il lucchetto.** Rinominando la classe è naturale rinominare anche `name = "vista"`: nessun errore, l'app riparte da un file vuoto e chi aveva bloccato la vista se la ritrova aperta | Bassa | Medio | Scritto in T-01 come divieto esplicito, verificato da TC-15 |
| **R-2 · Il primo fotogramma viene dal sistema.** `themes.xml:7` e `values-night/themes.xml:5` scelgono lo sfondo per configurazione | Alta | Medio | T-07, con gli **stessi due colori** di sistema già in uso: nessuna terza tinta inventata. TC-08 al rallentatore |
| **R-3 · Le barre decidono da sole.** `enableEdgeToEdge()` nudo (`MainActivity.kt:13`) | Alta | Medio | T-07: `SystemBarStyle.auto(…){ isDark }`, dove il lambda è il punto in cui la scelta sostituisce la configurazione. TC-07 |
| **R-9 · I 48 dp che il segmento non dà.** Nel bytecode di material3 1.4.0 `SegmentedButtonKt` usa `defaultMinSize` (40 dp) e non chiama `minimumInteractiveComponentSize` | Alta | Basso | Altezza imposta in T-11, verificata da TC-13. Senza, AC di US-005 fallisce per costruzione |
| **R-4 · DataStore è asincrono, la prima composizione no** | Media | Medio | T-05: una lettura bloccante sola, a freddo, su un file che oggi ha **una chiave** |
| **R-11 · Il cambio ricompone tutto il sottoalbero**, comprese le impostazioni da cui si è toccato | Media | Basso | Costo accettato di `staticCompositionLocalOf`. TC-05 guarda che lo scorrimento non salti in cima |
| **R-10 · `themeEffective` fuori dalla composizione** | Media | Basso | T-12 usa la funzione pura di T-02 leggendo `uiMode` dal context: è anche la prova che quella funzione ha due chiamanti veri, non solo i test |
| **R-7 · La resa non è provabile in automatico qui** | Certa | Basso | Costo accettato: TC-05…TC-15 sono espliciti e numerati proprio per non essere saltati |

Fuori tabella, la proprietà che rende questa feature tranquilla: **chi non la usa non se ne
accorge.** Il predefinito è il comportamento di oggi, e il codice nuovo che si esegue con
«Come il sistema» è una `when` a tre rami che restituisce quello che `isSystemInDarkTheme()`
avrebbe restituito comunque.

---

## 9. Rollout e rollback

**Strategia di rilascio:** deploy diretto, un pezzo solo. Niente da coordinare con il ponte,
il broker o il configuratore — questa feature non esce dall'APK.

**Percorso di consegna**

1. `./gradlew testDebugUnitTest` sul PC
2. `./build.sh` e installazione della debug, che punta a `devops/dev` e alle prese finte:
   TC-05…TC-14 si fanno lì, dove un comando partito per sbaglio non accende niente
3. TC-11 sul secondo telefono (quello con Android più vecchio a disposizione)
4. TC-15 **sulla release**, su un telefono che ha la 1.4.0 installata e la vista bloccata:
   è l'unico test che richiede l'app vera, perché verifica un file di preferenze esistente
5. `./install-all.sh` per la release

**Niente feature flag.** Il flag sarebbe la feature: la scelta «Come il sistema» *è*
l'interruttore spento, ed è il predefinito.

**Piano di rollback**

| Se va storto | Cosa fare | Effetto |
|---|---|---|
| Il tema forzato ha un difetto di resa | Selezionare «Come il sistema» | Si torna esattamente al comportamento della 1.4.0, senza disinstallare niente |
| La 1.5.0 va rimessa alla 1.4.0 | Si può, **pulitamente** | Nessuna migrazione Room da invertire: la chiave `tema` resta nel file `vista` e la 1.4.0 semplicemente non la legge. Reinstallando la 1.5.0 la scelta si ritrova |
| Il rename ha perso il file `vista` (R-8) | Non è recuperabile: il file vecchio è stato sostituito | Il lucchetto torna aperto e il tema a «Sistema». È il motivo per cui TC-15 si fa **prima** di `install-all.sh`, non dopo |

---

## 10. Checklist di approvazione

Progetto di una persona sola: la revisione è una rilettura a distanza di un giorno, non il
passaggio a qualcun altro. Le righe restano perché le domande sono le stesse.

| Revisione | Cosa chiede | Stato | Data |
|---|---|---|---|
| Revisione tecnica | D-3 — il booleano esplicito invece della configurazione forzata — regge, sapendo che costa tre chiusure separate (T-06, T-07) invece di una sola riga in `attachBaseContext`? | ⏳ In attesa | — |
| Revisione di prodotto | Tre segmenti dentro «Impostazioni» sono il posto giusto, o la scelta del tema andrebbe raggiunta più in fretta di così? La risposta si dà **usandola** | ⏳ In attesa | — |
| Stima approvata | 1,1 giorni sono accettabili, sapendo che 0,26 sono verifiche manuali e che il rename D-5 ne vale 0,05? | ⏳ In attesa | — |
| Rischi accettati | R-8 (il rename che porta via il lucchetto in silenzio) si accetta con la sola difesa di TC-15? | ⏳ In attesa | — |
| Data di inizio confermata | — | ⏳ In attesa | — |

---

## Domande aperte

Nessuna. Le sei decisioni emerse nelle Fasi 1 e 2 sono chiuse e riportate, con il loro esito
e il punto in cui sono nate, nella sezione 3.

---

*Documento generato con la skill `claude-code-feature`.*
