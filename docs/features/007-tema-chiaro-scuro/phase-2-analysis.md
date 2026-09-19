# Tema chiaro, scuro, di sistema — Analisi tecnica

**Stato:** Fase 2 — pronta per la Fase 3
**Autore:** Alberto Goldoni
**Data:** 19 settembre 2026
**Versione:** 1.0
**Feature:** `007-tema-chiaro-scuro`
**Base:** Fase 1 v1.1, decisioni §8 (booleano esplicito, file `vista`, `CompositionLocal`,
lettura bloccante a freddo)

---

## Premessa: quanto codice esiste già

Tre cose trovate nella codebase cambiano la forma del lavoro rispetto alla Fase 1, e vanno
dette prima dell'elenco dei file.

1. **`SmartHomeTheme` è già parametrico.** `darkTheme: Boolean = isSystemInDarkTheme()`
   (`Theme.kt:41`) non è un valore fisso ma un predefinito, e l'unico chiamante
   (`MainActivity.kt:16`) lo lascia così com'è. Alimentarlo non richiede nessun cambio di
   firma: è la ragione per cui questa feature costa 0,7 giorni e non 3.
2. **I colori dinamici non sono un problema.** `Theme.kt:48-53` sceglie fra
   `dynamicDarkColorScheme(context)` e `dynamicLightColorScheme(context)` **in base a
   `darkTheme`**, non alla configurazione di sistema. Passando il booleano risolto, AC-4.4
   è soddisfatto senza scrivere una riga: su Android 12+ la palette resta quella dello
   sfondo del telefono, nella variante chiesta.
3. **La diagnostica ha già il posto giusto.** `DebugReport.view()` (`DebugReport.kt:183-185`)
   è una funzione con una chiave sola, `locked`, e il suo commento dice che serve a
   rispondere a *«è rotto o è bloccato?»*. Il tema è esattamente la stessa categoria — una
   preferenza della vista di questo telefono — quindi **corregge la Fase 1**, che lo
   metteva in `identity()`: va in `view()`, accanto al lucchetto.

---

## A. File coinvolti

### Nuovi

| File | Perché |
|---|---|
| `app/src/main/java/it/agoldoni/smarthome/ui/theme/ThemeChoice.kt` | L'enum a tre valori e la funzione pura che risolve `scelta × sistema → scuro`. **File separato da `Theme.kt`, senza import Compose né Android**: è la sola cosa provabile in automatico, e in questo progetto i test sono solo JVM. Stesso motivo e stessa forma di `ui/devices/EnergiaCompatta.kt`, che sta fuori dalla schermata perché «è una regola e non un disegno» |
| `app/src/test/java/it/agoldoni/smarthome/ui/theme/ThemeChoiceTest.kt` | I casi della funzione sopra (§D) |

### Modificati

| File | Modifica | Perché |
|---|---|---|
| `data/settings/ViewLockStore.kt` | Chiave `tema` + `Flow<ThemeChoice>` + setter sospeso. **Il nome del file DataStore resta `"vista"`** (riga 23) | È il file delle preferenze della vista di questo telefono, deciso in Fase 1 §8.1. Il commento in testa (righe 12-22) va esteso: oggi spiega perché il lucchetto non sta col broker, e la stessa spiegazione vale per il tema |
| `di/AppContainer.kt` | `themeChoice: StateFlow<ThemeChoice>` accanto a `viewLocked` (riga 74), con **valore iniziale letto in blocco** | È il punto in cui il progetto già espone «leggibile senza aprire una coroutine», che è quello che serve a `MainActivity` prima di `setContent` e a `DebugReport` |
| `MainActivity.kt` | Legge `container.themeChoice`, imposta lo sfondo della finestra, passa il booleano a `SmartHomeTheme`, `enableEdgeToEdge` con stili espliciti | Le tre superfici di R-2, R-3 e R-4 si chiudono tutte qui |
| `ui/theme/Theme.kt` | `LocalDarkTheme` fornito da `SmartHomeTheme`; `poweredColors()` (riga 76) e `debugRed()` (riga 90) lo leggono | R-1 |
| `ui/settings/BrokerSettingsViewModel.kt` | +1 parametro nel costruttore (riga 43-47), `theme: StateFlow<ThemeChoice>`, `setTheme(...)` | Stessa forma di `registry` (righe 61-71) e `setFollowRegistry` (riga 67): flusso dallo store + setter che lancia sul `viewModelScope` |
| `ui/AppViewModelFactory.kt` | Il quarto argomento alla riga 31 | Unico punto in cui i ViewModel incontrano il contenitore |
| `ui/settings/BrokerSettingsScreen.kt` | `AspettoSection` privata, chiamata fra riga 179 (`DebugApiSection`) e riga 181 (`Spacer(32.dp)`) | Nessuna schermata nuova: la sezione si affianca a broker, registro e diagnostica |
| `diagnostics/DebugReport.kt` | Due chiavi in `view()` (righe 183-185) | Vedi premessa punto 3 |
| `res/values/strings.xml` | 5 stringhe nuove (§B) | Nessun testo dentro le Composable, come ovunque nell'app |
| `README.md` | `### Il tema` in fondo alla sezione «Il broker» (dopo riga 279, prima di `## Architettura`) | Quella sezione è di fatto «le impostazioni»: si apre con *«L'icona in alto a destra apre le impostazioni»* |
| `app/build.gradle.kts` | Al rilascio: `versionCode 12 → 13`, `versionName "1.4.0" → "1.5.0"` (righe 30-31) | La barra dell'elenco mostra la versione e serve a distinguere le installazioni |

### Esplicitamente non toccati

- `res/values/themes.xml` e `res/values-night/themes.xml` — restano il comportamento giusto
  per chi lascia «Sistema»; lo sfondo si impone da codice solo quando la scelta è diversa
- `AndroidManifest.xml` — nessun attributo di tema, nessuna `configChanges`
- `gradle/libs.versions.toml` — **nessuna libreria nuova**; il catalogo ha un tetto
  dichiarato (AGP 8.13.2 / compileSdk 36) che questa feature non tocca
- `data/local/` e `app/schemas/` — nessuna migrazione Room: la preferenza è DataStore
- `bridge/`, `devops/`, `tools/` — la feature vive interamente dentro `app/`

---

## B. Contratti e interfacce da modificare

### B.1 `ThemeChoice` (nuovo)

Tre valori — `SISTEMA`, `CHIARO`, `SCURO` — persistiti **come stringa**, non come indice:
un ordinale cambia significato se domani qualcuno inserisce un valore in mezzo, e questo è
un dato che sopravvive agli aggiornamenti.

Il contratto di lettura ha due regole, entrambe da provare:

- **chiave assente → `SISTEMA`**, che è il comportamento di oggi. È la stessa forma del
  lucchetto (`ViewLockStore.kt:35`: `prefs[KEY_LOCKED] ?: false`) e ciò che rende
  l'aggiornamento invisibile (AC-2.1)
- **stringa non riconosciuta → `SISTEMA`**, non un'eccezione. Serve al caso reale del
  downgrade: una versione futura che scrive un quarto valore, l'APK vecchio reinstallato
  sopra, e `valueOf` che solleverebbe `IllegalArgumentException` dentro una `map {}` di
  DataStore — cioè sul flusso che alimenta l'interfaccia

La funzione pura ha questa forma, ed è l'unico punto dove la scelta diventa un booleano:

```
fun ThemeChoice.scuro(sistemaScuro: Boolean): Boolean = when (this) {
    SISTEMA -> sistemaScuro
    CHIARO  -> false
    SCURO   -> true
}
```

### B.2 `ViewLockStore` → il nome è da decidere

La classe si chiama `ViewLockStore` e ospiterebbe una preferenza che col lucchetto non
c'entra. Il rename a `ViewPrefsStore` tocca **4 file** e 6 righe, tutte meccaniche:
`ViewLockStore.kt:25`, `AppContainer.kt:16,65,66,74`, `AppViewModelFactory.kt:22`,
`DeviceListViewModel.kt:9,55`.

> ⚠️ **Trappola da non sbagliare nel rename:** la stringa `preferencesDataStore(name = "vista")`
> (`ViewLockStore.kt:23`) **non va toccata**. È il nome del file su disco: cambiarlo non dà
> nessun errore, in compilazione né a runtime — semplicemente l'app riparte da un file
> vuoto e chi aveva bloccato la vista se la ritrova sbloccata, in silenzio.

Raccomandazione: fare il rename **prima** di aggiungere la chiave, altrimenti lo si fa due
volte. Se si preferisce non farlo, il costo è solo un nome che mente.

### B.3 `SmartHomeTheme` e `LocalDarkTheme`

La firma di `SmartHomeTheme` **non cambia** (`Theme.kt:40-46`): cambia il chiamante, che
smette di affidarsi al predefinito. Si aggiunge un `CompositionLocal`:

```
val LocalDarkTheme = staticCompositionLocalOf { false }
```

`static` e non dinamico perché il valore cambia raramente — una volta per tocco
dell'utente — e quando cambia deve ricomporre tutto il sottoalbero, che è esattamente ciò
che serve.

`poweredColors()` e `debugRed()` mantengono firma e valori: cambia solo la riga che decide
quale dei due rami prendere. **Nessuna preview di Compose esiste nel progetto**
(`grep @Preview app/src` → nessun risultato), quindi il valore predefinito del local non ha
nessun consumatore fuori da `SmartHomeTheme`.

### B.4 `BrokerSettingsViewModel`

Il costruttore passa da 3 a 4 parametri (riga 43-47). **Chiamante unico**:
`AppViewModelFactory.kt:31`. Nessun test esistente lo istanzia — la cartella
`app/src/test/.../ui/settings/` non esiste — quindi il cambio non rompe niente di verde.

### B.5 API di debug — additivo

`GET /state` → `view` acquisisce due chiavi accanto a `locked`:

```json
"view": { "locked": false, "theme": "scuro", "themeEffective": "dark" }
```

Due e non una perché rispondono a domande diverse: `theme` è cosa ha scelto la persona,
`themeEffective` è cosa sta vedendo — e con «Sistema» la seconda è l'unica informativa.
`tools/debug-api.py` non nomina nessuna di queste chiavi (stampa il JSON che riceve),
quindi non va aggiornato.

### B.6 Cosa **non** è un contratto

Il registro MQTT non trasporta il tema, e nessun topic cambia. Il documento del registro
(`bridge/configuratore/SCHEMA.md`) resta identico: come il lucchetto, questa preferenza non
esce dal telefono.

---

## C. Pattern da rispettare

**C.1 — Le preferenze.** Un file DataStore per categoria, con il *perché* della separazione
scritto in testa alla classe; chiavi in un `private companion object`; `Flow` in lettura,
`suspend fun` in scrittura; predefinito espresso con `?:` sulla chiave assente
(`ViewLockStore.kt:23-43`, `BrokerSettingsStore.kt`). Il vincolo duro, scritto nei commenti
di entrambi i file e in `AppContainer.kt:60-63`: **niente che cambi spesso dentro le
impostazioni del broker**, perché il driver riapre il collegamento a ogni valore diverso
(AC-4.5).

**C.2 — Il contenitore.** Dipendenze a mano, `by lazy`, e `stateIn(applicationScope,
SharingStarted.Eagerly, …)` per ciò che qualcuno deve leggere senza sospendere
(`AppContainer.kt:70-75`, con il commento che spiega proprio questo). La lettura bloccante
iniziale va **lì dentro**, non sparsa: `AppContainer` nasce in `SmartHomeApplication.onCreate`
(`SmartHomeApplication.kt:13`), cioè prima di qualunque Activity.

**C.3 — Le sezioni delle impostazioni.** Il modulo è una `Column` verticale di sezioni, e
ognuna ha la stessa forma: `SectionHeader(titolo)` → il controllo → **una riga di
spiegazione** in `bodySmall` / `onSurfaceVariant` che dice cosa succede in quello stato
(`RegistrySection` righe 194-240, `DebugApiSection` righe 258-293). La sezione «Aspetto»
deve avere anch'essa la sua riga: con «Sistema» dice che segue il telefono.

**C.4 — Le stringhe.** Tutte in `res/values/strings.xml`, in italiano, chiavi con prefisso
di area (`broker_`, `registry_`, `debug_`, `view_locked_`) → qui `theme_`. Commento XML
sopra il gruppo quando il perché non è ovvio, come per `view_locked_*` (righe 22-24).
Cinque stringhe: `theme_title`, `theme_system`, `theme_light`, `theme_dark`, `theme_helper`.

**C.5 — Il segmento a tre voci.** `SingleChoiceSegmentedButtonRow` + `SegmentedButton`
esistono nella versione in uso: la BOM `2026.06.01` fissa `androidx.compose.material3:material3:1.4.0`
(verificato nel POM della BOM in cache) e l'artefatto contiene
`SingleChoiceSegmentedButtonRowScope` e `SegmentedButtonKt`.

> ⚠️ **Dettaglio che AC-5.3 rende obbligatorio:** nel bytecode di material3 1.4.0
> `SegmentedButtonKt` dimensiona i segmenti con `defaultMinSize` (40 dp, il token del
> componente) e **non** chiama `minimumInteractiveComponentSize`. I 48 dp dell'area
> toccabile vanno quindi imposti a mano sulla riga, e verificati — non arrivano dal
> componente.

**C.6 — I commenti.** Nei sorgenti Kotlin i commenti sono **senza accenti** (`perche`,
`e'`, `gia`): è una convenzione osservata in tutto `app/src`, e va seguita. Documenti,
stringhe e `strings.xml` invece gli accenti li hanno.

**C.7 — I test.** JUnit 4, nomi di metodo in backtick e in italiano, una classe per regola,
KDoc in testa che spiega *cosa si rompe in silenzio se la regola salta*
(`EnergiaCompattaTest.kt:10-18` è il modello).

---

## D. Test da creare o aggiornare

### Nuovo — `ThemeChoiceTest` (unit, JVM)

| Caso | Attesa |
|---|---|
| `SISTEMA` con sistema chiaro | non scuro |
| `SISTEMA` con sistema scuro | scuro |
| `CHIARO` con sistema scuro | non scuro — la scelta vince (AC-2.3) |
| `SCURO` con sistema chiaro | scuro — la scelta vince (AC-1.1) |
| chiave assente | `SISTEMA` (AC-2.1) |
| stringa non riconosciuta (`"tramonto"`) | `SISTEMA`, nessuna eccezione (B.1) |

Sei casi, una classe, nessun mock: la funzione è pura e l'enum non ha dipendenze.

### Da aggiornare

Nessuno. I test esistenti — `DeviceListensLikeTest`, `DeviceRegistryTest`,
`RegistryContractTest`, `MqttFailuresTest`, `MqttPayloadsTest`, `DeviceCommandableTest`,
`EnergiaCompattaTest` — non toccano il tema né i costruttori che cambiano.

### Verifiche manuali (R-7: qui non c'è altro modo)

Non esistono `androidTest`, Robolectric o test di Compose nel progetto: `app/build.gradle.kts`
dichiara solo `testImplementation` (junit, coroutines-test, json). Questi criteri si chiudono
sul telefono, e la Fase 3 li deve elencare uno per uno:

1. **Matrice 2×3** — sistema chiaro/scuro × tre scelte, guardando: elenco con una presa
   accesa (il verde), insetto di debug acceso (il rosso), schermata di modifica, schermata
   delle impostazioni
2. **Barre di sistema** in tutte e tre le schermate, per ogni combinazione (AC-4.3)
3. **Avvio a freddo** con scelta ≠ sistema: nessun fotogramma del colore sbagliato
   (AC-4.2) — si vede meglio filmando lo schermo al rallentatore
4. **Rotazione** e ritorno dai recenti (AC-4.1)
5. **Cambio del tema di sistema con l'app aperta**: segue con «Sistema», non si muove con
   «Chiaro»/«Scuro» (AC-2.2, AC-2.3)
6. **Android 12+ con colori dinamici** e uno **Android 8-11** senza: la scelta vale su
   entrambi (AC-4.4)
7. **Il broker non cade** cambiando tema tre volte di fila: la riga di stato resta
   «Connesso» e il contatore dei comandi non si azzera (AC-4.5)
8. **TalkBack** sui tre segmenti (AC-5.1) e area toccabile ≥ 48 dp (AC-5.3, vedi C.5)
9. **`python3 tools/debug-api.py state`** mostra `theme` e `themeEffective` coerenti

---

## E. Rischi tecnici aggiornati

**R-1 — Le tinte fuori dallo schema** · *confermato, tre occorrenze esatte*
`grep -rn isSystemInDarkTheme app/src` restituisce oggi **tre** righe, tutte in `Theme.kt`:
41 (il predefinito di `SmartHomeTheme`), 76 (`poweredColors`), 90 (`debugRed`). Nessun altro
file nell'app legge il tema di sistema. Criterio di chiusura invariato e ora misurabile:
**una riga sola**, quella che risolve `SISTEMA`.

**R-2 — Il primo fotogramma** · *confermato, e il posto è uno solo*
`themes.xml:7` e `values-night/themes.xml:5` fissano `windowBackground` su
`@android:color/background_light` / `background_dark`. Lo sfondo si impone da codice in
`onCreate` **prima di `setContent`**, usando gli stessi due colori di sistema per non
inventare una terza tinta.

**R-3 — Le barre** · *confermato, con un dettaglio di ordine*
`MainActivity.kt:13` chiama `enableEdgeToEdge()` **prima di `super.onCreate`**. La forma
raccomandata da AndroidX per il caso «tema che cambia a runtime» è richiamarlo da un
`DisposableEffect(isDark)` dentro `setContent`, passando
`SystemBarStyle.auto(…) { isDark }` — il lambda `detectDarkMode` è il punto in cui la
scelta sostituisce la configurazione. La chiamata di riga 13 resta come prima applicazione,
quella nell'effetto la aggiorna.

**R-4 — L'asincronia di DataStore** · *confermato, costo misurabile e piccolo*
Il file `vista` ha **una chiave sola** oggi (`bloccata`): la lettura bloccante a freddo è
un file di poche decine di byte. Va fatta una volta, nel costruttore di `AppContainer`, e
il risultato serve sia come valore iniziale dello `StateFlow` sia a `MainActivity` prima di
`setContent`. Da misurare comunque sul telefono più lento disponibile.

**R-5, R-6** — chiusi in Fase 1 §8, nessuna evidenza contraria trovata nel codice.

**R-7 — Niente test di interfaccia** · *confermato*
`app/build.gradle.kts:142-144`: solo `testImplementation`. Nessun `androidTest`, nessuna
dipendenza Compose di test. Mitigazione già scritta in §D.

### Rischi nuovi, emersi dall'analisi

**R-8 — Il rename che cancella il lucchetto** · *probabilità bassa, impatto medio,
danno silenzioso*
Rinominando `ViewLockStore` è facile rinominare anche `name = "vista"` (riga 23) per
coerenza. Non dà nessun errore: l'app legge un file che non esiste, applica i predefiniti e
la vista di chi l'aveva bloccata si ritrova sbloccata. Vedi B.2.

**R-9 — I 48 dp che il segmento non dà** · *probabilità alta, impatto basso*
Vedi C.5: il componente si dimensiona a 40 dp e non applica la dimensione interattiva
minima. Senza un'altezza imposta, AC-5.3 fallisce per costruzione.

**R-10 — `themeEffective` calcolato nel posto sbagliato** · *probabilità media, impatto
basso*
`DebugReport` gira sul thread dell'API di debug, non in composizione: lì
`isSystemInDarkTheme()` non esiste. Il valore va calcolato dalla stessa funzione pura,
leggendo `resources.configuration.uiMode and UI_MODE_NIGHT_MASK` dal context
dell'applicazione — che è anche la prova che quella funzione non è un artificio per i test:
ha due chiamanti veri.

**R-11 — Il tema mentre la schermata è aperta** · *probabilità media, impatto basso*
Il cambio avviene dalla schermata delle impostazioni, che è dentro l'albero che si ricompone
col nuovo tema. Con `staticCompositionLocalOf` il sottoalbero viene ricomposto per intero:
va verificato a mano che lo scorrimento della `Column` (riga 97-99, `verticalScroll`) non
salti in cima e che lo snackbar «Impostazioni salvate» eventualmente aperto non si chiuda
(AC-1.5).

---

## F. Prerequisiti e task bloccanti

**Nessun refactoring bloccante, nessun setup.** Niente librerie nuove, nessuna piattaforma
SDK da installare, nessuna migrazione di dati, nessun cambio al ponte o al broker. La
build attuale (`./gradlew testDebugUnitTest`, `./build.sh`) copre già tutto quello che
serve.

Due sole cose vanno fatte **nell'ordine giusto**, e sono entrambe piccole:

1. **Decidere il rename `ViewLockStore` → `ViewPrefsStore` prima di scrivere la chiave**
   (B.2). Farlo dopo significa rifare gli stessi 4 file due volte; non farlo affatto è
   accettabile, ma va deciso adesso e non a metà
2. **Il quarto parametro di `BrokerSettingsViewModel`** (B.4) va aggiunto insieme alla riga
   31 di `AppViewModelFactory`, o la compilazione si ferma. Un minuto, ma è l'unico punto in
   cui la feature tocca il cablaggio delle dipendenze

L'ordine di attuazione resta quello delle milestone M1-M5 di Fase 1, con una nota: **M2 non
è rilasciabile** (tema cambiato, verde/rosso/barre ancora dalla parte del sistema). Se il
lavoro si interrompe, ci si ferma alla fine di M3, non in mezzo.

---

## Domande aperte

Nessuna. L'unica — **si fa il rename `ViewLockStore` → `ViewPrefsStore`?** — è stata chiusa
il 19 settembre: **sì, prima di tutto il resto**, 4 file e 6 righe, con la trappola di R-8
scritta nel task che lo esegue (Fase 3, T-01) e verificata da TC-15.
