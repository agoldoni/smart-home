# Tema chiaro, scuro, di sistema — Requisiti

**Stato:** Fase 1 — decisioni sciolte, pronta per la Fase 2
**Autore:** Alberto Goldoni
**Data:** 19 settembre 2026
**Versione:** 1.2 (19 settembre 2026: sciolte le quattro domande aperte, piano
semplificato da 1,2 a 0,9 giorni/uomo; corretto un errore di somma nella tabella di §6,
che dava 0,7 mentre le voci sommavano 0,9)
**Feature:** `007-tema-chiaro-scuro`

---

## 1. Obiettivo e motivazione

L'app oggi non ha un tema: ha quello del telefono. `SmartHomeTheme` nasce con
`darkTheme = isSystemInDarkTheme()` e nessuno gliel'ha mai passato diverso
(`app/src/main/java/it/agoldoni/smarthome/ui/theme/Theme.kt:41`, unico chiamante
`MainActivity.kt:16`). Funziona, ed è il comportamento giusto come predefinito — ma è
l'unico disponibile, e sono due i casi in cui non basta.

Il primo è l'uso reale. Questa è l'app che si apre al buio, in piedi in corridoio, per
spegnere una presa prima di andare a dormire. Chi tiene il telefono in tema chiaro tutto il
giorno si prende in faccia una schermata bianca, e l'unico rimedio disponibile oggi è
cambiare il tema **di tutto il telefono**. Il contrario vale per il telefono fisso sulla
mensola, sempre in scuro, che di giorno vorrebbe una schermata leggibile in controluce.
È una preferenza della vista di questo telefono, esattamente come il lucchetto dei comandi
della feature 003.

Il secondo è la verifica. Questa app ha **due colori scritti a mano fuori dallo schema**, e
non per vezzo: il verde dell'acceso e il rosso del segno di debug sono fissati perché con i
colori dinamici di Android 12+ `primaryContainer` e `colorScheme.error` seguono lo sfondo
del telefono e possono diventare rosa o ocra (`Theme.kt:65-90`). Ciascuno dei due ha una
tonalità per il chiaro e una per lo scuro, scelte a mano. Oggi, per guardare la seconda,
bisogna cambiare il tema del sistema operativo. Con un selettore dentro l'app le due rese si
confrontano in quattro tocchi, sul telefono, con le sette prese vere davanti.

Il costo è basso perché il parametro **esiste già**: `SmartHomeTheme(darkTheme = …)` aspetta
solo che qualcuno gli passi qualcosa di diverso dal valore di sistema. Il lavoro vero non è
la scelta — sono le tre superfici che oggi leggono il sistema per conto proprio e che dopo
questa feature non devono più farlo (vedi R-1, R-2, R-3). È la ragione per cui questa
feature merita un documento invece di un commit da venti righe: la parte visibile è un
selettore, la parte che si può sbagliare è un'app metà chiara e metà scura.

**Metriche di successo:**

- La scelta si fa dalle Impostazioni in **due tocchi** e ha effetto **subito**, senza
  riavviare l'app e senza tornare indietro di schermata
- Chi aggiorna e non tocca niente **non vede nessuna differenza**: il predefinito è
  «Sistema», cioè il comportamento di oggi
- La scelta sopravvive alla chiusura dell'app e al riavvio del telefono
- **Niente resta indietro**: con «Scuro» su un telefono in chiaro, il verde dell'acceso, il
  rosso del debug, le icone delle barre di sistema e lo sfondo della finestra sono tutti
  scuri. Nessuna zona chiara superstite, in nessuna delle tre schermate
- **Nessun lampeggio** all'avvio: aprendo l'app con «Scuro» su telefono chiaro non si vede
  il fotogramma bianco prima di quello scuro

---

## 2. Scope

### Incluso

- **La preferenza**: tre valori — `SISTEMA`, `CHIARO`, `SCURO` — persistiti su questo
  telefono con DataStore (già in uso per broker, registro e lucchetto)
- **Il selettore**: una sezione «Aspetto» in fondo alla schermata Impostazioni esistente,
  accanto a broker, registro e diagnostica, servita dal `BrokerSettingsViewModel` che è già
  lì. Tre segmenti a scelta singola (`SingleChoiceSegmentedButtonRow`), una riga sola
- **L'alimentazione del tema**: `SmartHomeTheme` riceve il booleano risolto invece di
  leggere il sistema, e lo rimette a disposizione con un `CompositionLocal`; `MainActivity`
  legge la preferenza **prima** della prima composizione
- **L'allineamento delle superfici fuori dallo schema**: `poweredColors()` e `debugRed()`
  smettono di chiamare `isSystemInDarkTheme()` per conto loro e seguono la stessa fonte
- **Le barre di sistema**: il contrasto delle icone di stato e navigazione
  (`enableEdgeToEdge()` in `MainActivity.kt:14`) segue la scelta, non la configurazione
- **Lo sfondo della finestra**: il primo fotogramma, che oggi viene da
  `res/values-night/themes.xml` e quindi dal sistema
- **Le stringhe**, in italiano, nello stile delle altre (`strings.xml`)
- **La voce nella API di debug**: due campi in `DebugReport.identity()` — il tema scelto e
  quello effettivo — come tutto il resto dello stato dell'app

### Escluso (out of scope)

- **Il configuratore web.** Deciso il 19 settembre: `bridge/configuratore/web/stile.css:11`
  segue `prefers-color-scheme` e resta così. È una pagina che si apre dal computer per
  configurare, non l'app che si guarda al buio
- **L'interruttore dei colori dinamici (Material You).** Restano accesi come oggi su
  Android 12+: la scelta decide chiaro o scuro, non da dove vengono i colori. Due
  impostazioni vorrebbero dire quattro combinazioni da provare per un guadagno che nessuno
  ha chiesto
- **Temi personalizzati**, scelta della tinta, palette alternative
- **La sincronizzazione della scelta fra telefoni** via registro MQTT: il registro porta i
  dispositivi, non l'aspetto. Come il lucchetto, questa preferenza resta su un telefono solo
- **La pianificazione oraria** («scuro dal tramonto»): la dà già il sistema, e chi la vuole
  lascia «Sistema»
- **Contrasto elevato, dimensione del testo, modalità daltonici**: altra feature, altri
  criteri
- **Il rifacimento della palette.** `LightColors` e `DarkColors` (`Theme.kt:17-38`) restano
  quelli che sono: questa feature sceglie quale delle due usare, non le ridisegna

---

## 3. User Stories

**US-1 — Tenere l'app scura su un telefono chiaro**
> Come persona che apre l'app al buio prima di dormire, voglio poter mettere l'app in scuro
> senza cambiare il tema del telefono, per non prendermi una schermata bianca in faccia.

**US-2 — Tornare al comportamento di sempre**
> Come persona che ha già il telefono impostato bene, voglio che l'app continui a seguire il
> sistema senza che io debba configurare niente, per non perdere quello che funzionava.

**US-3 — Vedere l'altra resa senza cambiare il telefono**
> Come chi sviluppa questa app, voglio passare da chiaro a scuro da dentro le Impostazioni,
> per verificare il verde dell'acceso e il rosso del debug sulle schede vere in pochi
> secondi, senza toccare le impostazioni di sistema.

**US-4 — Una scelta che resta e che si applica tutta**
> Come persona che ha scelto un tema, voglio ritrovarlo alla riapertura dell'app e voglio
> che valga per **tutta** l'interfaccia, per non avere un'app metà scura con la barra di
> stato illeggibile.

**US-5 — Sceglierlo con TalkBack**
> Come persona che usa TalkBack, voglio che le tre voci si annuncino come un gruppo a scelta
> singola con lo stato corrente, per sapere cosa è selezionato prima di cambiare.

---

## 4. Criteri di accettazione

### US-1 — Tenere l'app scura su un telefono chiaro

- [ ] **AC-1.1** Con il telefono in tema chiaro e «Scuro» selezionato, tutte e tre le
      schermate (elenco, modifica dispositivo, impostazioni) sono scure
- [ ] **AC-1.2** La scheda di una presa accesa mostra il verde della variante scura
      (`0xFF1F5B28` su `0xFFD8F3D9`), non quello della chiara
- [ ] **AC-1.3** Con l'API di debug accesa, l'insetto in barra usa il rosso della variante
      scura (`0xFFFF5A5A`)
- [ ] **AC-1.4** Il caso speculare vale identico: telefono scuro + «Chiaro» → app
      interamente chiara, verde e rosso della variante chiara
- [ ] **AC-1.5** Cambiando la scelta l'interfaccia si aggiorna **senza uscire dalla
      schermata** delle impostazioni e senza che l'app riparta da capo

### US-2 — Tornare al comportamento di sempre

- [ ] **AC-2.1** Su un'installazione aggiornata dalla versione precedente, senza toccare
      niente, la voce attiva è «Sistema» e l'aspetto è identico a prima
- [ ] **AC-2.2** Con «Sistema» attivo, cambiando il tema del telefono mentre l'app è aperta,
      l'app segue entro il ritorno in primo piano
- [ ] **AC-2.3** Con «Chiaro» o «Scuro» attivo, cambiare il tema del telefono **non** ha
      effetto sull'app
- [ ] **AC-2.4** Selezionare di nuovo «Sistema» ripristina l'aggancio al telefono senza
      riavviare l'app

### US-3 — Vedere l'altra resa senza cambiare il telefono

- [ ] **AC-3.1** Il selettore è raggiungibile dall'elenco in due tocchi (ingranaggio →
      sezione «Aspetto»)
- [ ] **AC-3.2** Il tempo fra il tocco su una voce e l'interfaccia ridisegnata è
      impercettibile: nessun caricamento, nessuno sfarfallio intermedio
- [ ] **AC-3.3** `GET /info` dell'API di debug riporta la scelta e il tema effettivamente
      applicato, distinti

### US-4 — Una scelta che resta e che si applica tutta

- [ ] **AC-4.1** La scelta sopravvive a: chiusura dell'app dai recenti, riavvio del
      telefono, rotazione dello schermo
- [ ] **AC-4.2** Aprendo l'app a freddo con una scelta diversa dal sistema, **non** compare
      un fotogramma del tema sbagliato: né lo sfondo della finestra, né la prima
      composizione
- [ ] **AC-4.3** Le icone della barra di stato e della barra di navigazione hanno il
      contrasto giusto rispetto al tema scelto (chiare su fondo scuro, scure su fondo
      chiaro), in tutte e tre le schermate
- [ ] **AC-4.4** Con i colori dinamici attivi su Android 12+, la scelta continua a valere:
      la palette resta quella dello sfondo del telefono, ma nella variante chiesta
- [ ] **AC-4.5** La scelta non tocca il collegamento al broker: cambiandola il driver MQTT
      non si disconnette e il contatore dei comandi non si azzera

### US-5 — Sceglierlo con TalkBack

- [ ] **AC-5.1** Le tre voci sono annunciate come gruppo a scelta singola, con
      «selezionato» sulla voce attiva
- [ ] **AC-5.2** Le etichette sono in italiano e comprensibili fuori contesto
      («Chiaro», «Scuro», «Come il sistema»)
- [ ] **AC-5.3** L'area toccabile di ogni voce è di almeno 48 dp. Il segmento Material 3 è
      disegnato alto 40 dp: va verificata l'area toccabile, non l'altezza visibile

---

## 5. Rischi e dipendenze

Le decisioni prese il 19 settembre (§8) chiudono R-5 e R-6 e fissano la mitigazione degli
altri. La strada scelta è **il booleano esplicito**: la scelta viene risolta in un punto
solo e passata a chi la deve seguire. Costa tre chiusure separate invece di una, e in cambio
non ricrea l'Activity e non poggia su nessun comportamento di piattaforma.

### Rischi tecnici

**R-1 — Le due tinte fuori dallo schema leggono il sistema da sole** · *probabilità alta,
impatto alto*
`poweredColors()` (`Theme.kt:76`) e `debugRed()` (`Theme.kt:90`) chiamano
`isSystemInDarkTheme()` direttamente. Sono i due colori che questa feature deve far
cambiare di più — il verde è la cosa che si guarda sulla scheda, il rosso è un segnale di
sicurezza — e con un tema forzato resterebbero dalla parte del telefono: verde chiaro su
schede scure. È **il** rischio della feature, non un dettaglio.
*Mitigazione (decisa):* `SmartHomeTheme` fornisce un `CompositionLocal` con il booleano che
ha già calcolato; le due funzioni lo leggono al posto di `isSystemInDarkTheme()`. Criterio
di chiusura: `grep isSystemInDarkTheme app/src` restituisce **una riga sola**, quella in cui
la scelta «Sistema» viene risolta.

**R-2 — Il primo fotogramma viene dal sistema** · *probabilità alta, impatto medio*
`res/values/themes.xml` e `res/values-night/themes.xml` fissano `windowBackground` su
`background_light` / `background_dark`, scelti da Android in base alla configurazione. Con
«Scuro» su telefono chiaro, la finestra è bianca finché Compose non disegna.
*Mitigazione (decisa):* `window.setBackgroundDrawable()` in `onCreate`, prima di
`setContent`, quando la scelta non è «Sistema». I due file XML non si toccano: restano il
comportamento giusto per chi lascia «Sistema».

**R-3 — Le barre di sistema decidono da sole** · *probabilità alta, impatto medio*
`enableEdgeToEdge()` senza argomenti (`MainActivity.kt:14`) sceglie gli stili
automaticamente **dalla configurazione di sistema**. Icone nere su barra nera è il risultato
tipico, ed è esattamente il tipo di difetto che si nota solo sul telefono.
*Mitigazione (decisa):* `statusBarStyle` e `navigationBarStyle` espliciti, derivati dal
booleano, e riapplicati quando la scelta cambia mentre l'app è aperta.

**R-4 — DataStore è asincrono, la prima composizione no** · *probabilità media, impatto
medio*
La preferenza arriva da un `Flow`. Se `setContent` parte prima del primo valore, l'app
disegna col predefinito e poi si ribalta: è il lampeggio che AC-4.2 vieta.
*Mitigazione (decisa):* **una lettura bloccante sola**, a freddo, prima di `setContent`
(`runBlocking { …first() }` su un file di preferenze locale di poche righe). Da lì in avanti
si osserva il flusso come tutto il resto. Scartata la trattenuta del primo fotogramma: più
codice per lo stesso risultato.

**R-5 — Dove si salva** · **chiuso il 19 settembre**
La preferenza va nel file di preferenze `vista`, accanto al lucchetto dei comandi: è per
definizione «una preferenza della vista di questo telefono». Nessun quarto file. Resta
vietato `BrokerSettingsStore`, che il driver osserva e che al primo valore diverso riapre il
collegamento (vedi il commento in `data/settings/ViewLockStore.kt`, e AC-4.5).

**R-6 — L'Activity ricreata a ogni cambio** · **chiuso il 19 settembre: non si corre**
`UiModeManager.setApplicationNightMode()` (API 31+) e la configurazione forzata via
`applyOverrideConfiguration()` farebbero seguire la scelta a tutto da solo — `Theme.kt`
non si toccherebbe — ma ricreano l'Activity a ogni cambio, cosa che AC-1.5 esclude, e
poggiano su un terreno con quirk noti (è il motivo per cui `AppCompatDelegate` è grosso).
`AppCompatDelegate` non è comunque un'alternativa: l'app non usa AppCompat
(`Theme.SmartHome` discende da `android:Theme.Material`).

**R-7 — La resa non è testabile in automatico qui** · *probabilità certa, impatto basso*
Il progetto ha solo test JVM (JUnit 4, coroutines-test, `org.json`): niente Robolectric,
niente `androidTest`, nessun test di Compose. I criteri di questa feature sono per tre
quarti visivi.
*Mitigazione (decisa):* un test solo, sulla funzione pura `scelta × tema di sistema →
scuro`, tre casi; e la lista esplicita delle verifiche manuali nella Fase 3, come per le
feature 002 e 005.

### Dipendenze

- **Nessuna libreria nuova.** DataStore, Compose Material 3 e il resto sono già nel
  catalogo (`gradle/libs.versions.toml`)
- **Nessun tocco al ponte, al broker o al configuratore**: questa feature vive interamente
  dentro `app/`
- **Nessun cambio di schema Room**, nessuna migrazione: la preferenza è DataStore
- **Nessun file nuovo di preferenze, nessun ViewModel nuovo, nessuna schermata nuova**
- **`minSdk 26`** resta il vincolo: la soluzione scelta è la stessa da Android 8 in su
- **Versione dell'app** da alzare al rilascio (oggi `versionCode 12`, `versionName 1.4.0`):
  la barra dell'elenco mostra la versione e serve a distinguere le installazioni

---

## 6. Stima effort

Progetto a una persona; le giornate sono giornate piene di lavoro effettivo, non di
calendario. La stima è quella **dopo** le semplificazioni di §8: la 1.0 diceva 1,2 giorni.

| Area | Attività | Giorni/uomo |
|---|---|---|
| Backend | Nessuna: nessun ponte, nessun broker, nessun database | — |
| App (FE) | Tipo della scelta + chiave nel file `vista` + lettura eager dal contenitore | 0,10 |
| App (FE) | `SmartHomeTheme`, `CompositionLocal`, verde e rosso agganciati (R-1) | 0,15 |
| App (FE) | Tre segmenti nella sezione «Aspetto» dentro le Impostazioni esistenti + stringhe | 0,10 |
| App (FE) | Barre di sistema, sfondo della finestra, lettura bloccante all'avvio (R-2, R-3, R-4) | 0,20 |
| App (FE) | Due campi in `DebugReport.identity()` | 0,03 |
| Test | Un test JVM sulla risoluzione + giro manuale dei criteri sul telefono | 0,20 |
| Documentazione | `README.md`, documento di Fase 3, nota di versione | 0,12 |
| **Totale** | | **≈ 0,9 giorni/uomo** |

La voce che può ancora sfuggire è la quarta: barre di sistema e primo fotogramma sono il
posto dove si perdono mezze giornate a provare combinazioni sul telefono. È anche l'unica
che non si vede dal diff — si vede solo aprendo l'app al buio.

---

## 7. Milestones

**M1 — La preferenza esiste e resta** *(prerequisito)*
Enum a tre valori, chiave nel file `vista`, lettura eager dal contenitore delle dipendenze,
due campi in `DebugReport.identity()` per vederla da fuori. Nessuna interfaccia: si verifica
dal test e da `GET /info`.

**M2 — Il tema segue la scelta**
Lettura bloccante prima di `setContent`, booleano passato a `SmartHomeTheme`. A questo punto
l'app cambia tema, ma verde, rosso e barre sono ancora dalla parte del sistema: **stato
intermedio noto, non rilasciabile.**

**M3 — Niente resta indietro** *(chiude R-1, R-2, R-3)*
`CompositionLocal` fornito dal tema, `poweredColors()` e `debugRed()` agganciati, stili
delle barre espliciti, sfondo della finestra imposto. È la milestone che vale la feature:
da qui in poi l'app è coerente in entrambi i temi. Criterio di chiusura di R-1: un solo
`isSystemInDarkTheme()` in tutto `app/src`.

**M4 — Si può scegliere**
Sezione «Aspetto» in fondo alla schermata Impostazioni, tre segmenti, stringhe italiane.
La semantica a scelta singola arriva dal componente: US-5 non richiede codice suo.

**M5 — Verificato e scritto**
Test JVM sulla risoluzione, giro manuale dei criteri di accettazione sul telefono nelle
quattro combinazioni (telefono chiaro/scuro × scelta chiaro/scuro), aggiornamento del
`README.md` e alzata di versione.

---

## 8. Decisioni prese

Le quattro domande aperte della 1.0, sciolte il 19 settembre.

1. **Dove vive la preferenza** → chiave nel file di preferenze `vista`, accanto al
   lucchetto. Nessun quarto file: è la stessa categoria di cosa, una preferenza della vista
   di questo telefono. (R-5)
2. **Che forma ha la fonte unica** del «siamo in scuro» → un `CompositionLocal` fornito da
   `SmartHomeTheme`, che il booleano lo calcola già. Scartata la derivazione dalla luminanza
   dello schema: stessa resa, ma implicita e da spiegare. (R-1)
3. **Come si evita il lampeggio** → una lettura bloccante sola, a freddo, prima di
   `setContent`, più `window.setBackgroundDrawable()` quando la scelta non è «Sistema». I
   due `themes.xml` non si toccano. (R-2, R-4)
4. **Configurazione forzata o `UiModeManager` su API 31+** → **no.** Farebbero seguire la
   scelta a tutto da soli, ma ricreano l'Activity a ogni cambio (AC-1.5 lo esclude) e
   poggiano su comportamenti di piattaforma con quirk noti. Si paga con tre chiusure
   separate invece di una, tutte visibili nel diff. (R-6)

Semplificazioni adottate nella stessa sessione, già dentro §6 e §7: sezione «Aspetto» dentro
`BrokerSettingsScreen`/`BrokerSettingsViewModel` invece di una schermata e un ViewModel
nuovi, come hanno fatto registro e diagnostica; tre segmenti
(`SingleChoiceSegmentedButtonRow`) invece di tre righe con radio; la voce nell'API di debug
ridotta da milestone a due campi in `identity()`; un test solo.

**Niente resta aperto: la Fase 2 può partire.**
