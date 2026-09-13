# Vista principale in sola lettura — Analisi tecnica

**Stato:** Fase 2 — da rivedere
**Autore:** Alberto Goldoni
**Data:** 13 settembre 2026
**Versione:** 1.0
**Feature:** `003-vista-sola-lettura` · [Fase 1](phase-1-requirements.md)

---

## In due righe

La feature entra in un punto solo del codice, e il punto **esiste già**: entrambi i
controlli comandabili hanno `enabled = !item.state.unreachable`. Non c'è da inventare un
meccanismo di blocco — c'è da aggiungere un secondo motivo a quello che c'è.

L'unica decisione che va presa bene è **dove** vive la preferenza, e la risposta non è
quella ovvia: metterla insieme alle impostazioni del broker farebbe **cadere il collegamento
MQTT a ogni tocco del lucchetto**. Il perché è alla sezione E, R-6.

---

## A. File coinvolti

### Nuovi

| File | Perché |
|---|---|
| `app/src/main/java/it/agoldoni/smarthome/data/settings/ViewLockStore.kt` | La preferenza persistita, su un file DataStore suo (`vista`). Ricalca [RegistryStore.kt](../../../app/src/main/java/it/agoldoni/smarthome/data/settings/RegistryStore.kt), che esiste separato per la stessa ragione |
| `app/src/main/res/drawable/ic_lock_open.xml` | **`material-icons-core` non ha `LockOpen`** — verificato, vedi sezione B. Il lucchetto chiuso c'è, quello aperto va disegnato, come già `ic_debug.xml` e i quattro `ic_device_*.xml` |

### Modificati

| File | Modifica |
|---|---|
| [di/AppContainer.kt](../../../app/src/main/java/it/agoldoni/smarthome/di/AppContainer.kt) | Un `viewLockStore by lazy` accanto a `registryStore` (riga 62) |
| [ui/AppViewModelFactory.kt](../../../app/src/main/java/it/agoldoni/smarthome/ui/AppViewModelFactory.kt) | Il nuovo store passato a `DeviceListViewModel` |
| [ui/devices/DeviceListViewModel.kt](../../../app/src/main/java/it/agoldoni/smarthome/ui/devices/DeviceListViewModel.kt) | Lo store fra le dipendenze, `locked` dentro il `combine` e dentro `DeviceListUiState`, un `toggleLock()`, e il presidio in `send()` |
| [ui/devices/DeviceListScreen.kt](../../../app/src/main/java/it/agoldoni/smarthome/ui/devices/DeviceListScreen.kt) | L'icona in barra fra riga 211 e riga 213; `locked` passato a `DeviceCard` (riga 250); `enabled` esteso alle righe 455 e 472 |
| [res/values/strings.xml](../../../app/src/main/res/values/strings.xml) | Le etichette del lucchetto e le descrizioni per TalkBack |
| [diagnostics/DebugReport.kt](../../../app/src/main/java/it/agoldoni/smarthome/diagnostics/DebugReport.kt) | La modalità dentro `/state`, riga 229-231 |
| [app/build.gradle.kts](../../../app/build.gradle.kts) | `versionCode` 9 → 10, `versionName` 1.1.0 → **1.2.0** (righe 18-19) |
| `README.md` | Una riga: cosa blocca e cosa **non** blocca |

### Non toccati, e vale la pena dirlo

- **Nessuna migrazione Room.** La preferenza non è un dato di dominio: non entra in
  `Device`, non tocca lo schema, non aggiunge un `schemas/6.json`. È la differenza grossa
  rispetto alla 002
- **Nessuna modifica al registro condiviso.** Il documento resta a `schema: 1`, il
  configuratore web non cambia, gli altri telefoni non si accorgono di niente
- **Nessun tocco al driver.** `MqttDeviceDriver` non sa che esiste una modalità: il blocco
  sta sopra di lui

---

## B. Contratti e interfacce da modificare

### `DeviceListUiState` — un campo in più

```kotlin
data class DeviceListUiState(
    val devices: List<DeviceUi> = emptyList(),
    val connection: ConnectionState = ConnectionState.NotConfigured,
    val loading: Boolean = true,
    val commandsSent: Int = 0,
    val locked: Boolean = false,    // nuovo
)
```

Contratto interno, nessun consumatore fuori dalla schermata.

### `DeviceListViewModel` — la firma del costruttore

Da `(DeviceRepository, DeviceDriver, RegistrySync)` a `(…, ViewLockStore)`. **Unico punto
di costruzione**: [AppViewModelFactory.kt:16-19](../../../app/src/main/java/it/agoldoni/smarthome/ui/AppViewModelFactory.kt#L16). Non è un contratto pubblico e non ci sono
test che lo istanziano.

### `DeviceCard` — composable privato

`private fun DeviceCard(item, onPower, onLevel, onEdit)` → un `locked: Boolean` in più.
Privato al file, un solo punto di chiamata (riga 250).

### `/state` — additivo

Una chiave nuova accanto a `registry`, `broker`, `devices` ([DebugReport.kt:229-231](../../../app/src/main/java/it/agoldoni/smarthome/diagnostics/DebugReport.kt#L229)).
`tools/debug-api.py` legge le chiavi che gli servono: aggiungerne una non rompe niente.

### Il vincolo che decide l'icona

**Verificato sull'artefatto, non a memoria.** Il progetto dipende da
`material-icons-core` ([app/build.gradle.kts:92](../../../app/build.gradle.kts#L92)), non
dalla variante *extended*. Aperto l'aar in cache (`material-icons-core-android/1.7.8`), le
icone `filled` disponibili sono 49 e comprendono **`Lock`** — ma **non `LockOpen`**, che
vive solo in `material-icons-extended`.

Due strade, e la seconda è quella giusta:

1. Aggiungere `material-icons-extended`: un artefatto che porta dentro migliaia di vettori
   per usarne uno. Il progetto ha scelto *core* apposta
2. **Disegnare `ic_lock_open.xml`**, come il progetto fa già cinque volte
   (`ic_debug`, `ic_device_switch`, `ic_device_light`, `ic_device_dimmer`, `ic_device_sensor`)

Il lucchetto chiuso può restare `Icons.Default.Lock`, ma per coerenza di tratto conviene
disegnare **entrambi** gli stati come drawable: aperto e chiuso devono differire per
**forma**, non per tinta — è un criterio di accettazione di US-4.

---

## C. Pattern da rispettare

**Le preferenze.** [RegistryStore.kt](../../../app/src/main/java/it/agoldoni/smarthome/data/settings/RegistryStore.kt) è il modello esatto: `preferencesDataStore(name = …)` a
livello di file, una `data class` di lettura, un `Flow` che la mappa, setter `suspend` che
fanno `edit { }`. Da copiare com'è.

**Il contenitore.** Costruzione a mano in `AppContainer`, tutto `by lazy`, nessuna
annotazione. Il commento in testa dice perché, e va rispettato.

**Il commento che spiega il perché, non il cosa.** È la cifra di questo codice. Il blocco
in sola lettura ha un *perché* già scritto a riga 446-451, per un altro motivo:

> *«Su un dispositivo che ha dichiarato di non esserci l'interruttore non si muove: il
> comando non arriverebbe a nessuno, e vederlo scattare racconterebbe un'accensione che non
> è avvenuta.»*

È parola per parola il rischio R-1 della Fase 1, già risolto una volta. La modalità in sola
lettura è **lo stesso ragionamento con un secondo motivo**, e il commento nuovo deve dire
quello.

**Le stringhe.** Tutte in `strings.xml`, prefisso per famiglia (`action_`, `state_`,
`registry_`, `connection_`). Solo italiano, `values` e `values-night`. Attenzione al nome:
`registry_readonly` **esiste già** e parla del modulo di modifica bloccato dal registro —
le stringhe nuove vogliono un prefisso diverso (`view_locked_*`) per non confondere due
cose che non sono la stessa.

**Il tocco in barra.** Il "+" usa `Modifier.size(40.dp)` con un commento che spiega la
scelta: cornice più stretta perché "+" e ingranaggio si leggano come una coppia. Il
lucchetto che si infila **in mezzo** a quella coppia è la decisione presa in revisione, e il
commento esistente a riga 199-205 va aggiornato di conseguenza: descrive un accoppiamento
che non sarà più vero.

---

## D. Test da creare o aggiornare

**Va detto con franchezza: l'infrastruttura di test di questo progetto non arriva a questa
feature.** Le dipendenze sono `junit`, `kotlinx-coroutines-test` e `json`; i quattro test
esistenti sono tutti su logica pura (parser del registro, payload MQTT, classificazione
degli errori). **Non c'è Robolectric, non c'è Compose UI test, non esiste `androidTest`.**

DataStore vuole un `Context` e i Composable vogliono un runtime: nessuno dei due si prova
con quello che c'è. Le strade sono tre, e la terza è quella che propongo.

1. Aggiungere Robolectric e `compose-ui-test`: due dipendenze nuove e un tempo di build più
   lungo, per una feature da un giorno. Sproporzionato
2. Non provare niente in JVM e verificare tutto sul telefono
3. **Rendere pura la regola, provare quella in JVM, e verificare il resto sul telefono** —
   che è esattamente come è stata chiusa la 002 (TC-01 e TC-13 sono test sul campo annotati)

### Unit, in JVM

| Test | File | Cosa verifica |
|---|---|---|
| `DeviceListUiStateTest` | `app/src/test/java/it/agoldoni/smarthome/ui/devices/` (nuovo) | Che la regola di abilitazione sia una funzione pura e dia il risultato giusto nelle quattro combinazioni di `locked` × `unreachable`, compreso il caso "bloccato **e** irraggiungibile" |

Perché funzioni, la regola va estratta da dentro il Composable in una proprietà calcolata —
per esempio `DeviceUi.commandable(locked)`. È un refactoring di tre righe e rende
provabile l'unica cosa che vale la pena provare in JVM.

### Sul campo, sul telefono

| # | Test | Priorità |
|---|---|---|
| TC-01 | **Il contatore fermo.** Bloccata la vista, dieci tocchi sugli interruttori: il contatore dei comandi inviati in barra non si muove di uno. È la sentinella della feature | Alta |
| TC-02 | **L'interruttore non scatta.** Ripreso a video o guardato da vicino: non si muove e non torna. È R-1 | Alta |
| TC-03 | **Persistenza vera.** Bloccata, app chiusa **e processo ucciso** (`adb shell am force-stop`), riaperta: ancora bloccata. Non basta il ritorno da background | Alta |
| TC-04 | **La finestra all'avvio.** App bloccata, avvio a freddo, tocco su un interruttore nel primo istante utile: nessun comando parte. È R-2 | Alta |
| TC-05 | **Aggiornamento dalla 1.1.0** su un telefono che ha già dati: la modalità iniziale è scrittura, i dispositivi sono tutti lì | Alta |
| TC-06 | **Sblocco e comando**: un tocco, poi un comando che arriva davvero alla presa. Nessuna riconnessione nel mezzo — da guardare in `/state`, il `clientIdInUse` non deve cambiare | Alta |
| TC-07 | **La barra guardata davvero**, con i nomi lunghi delle prese e l'insetto di debug acceso: tre icone più il badge ci stanno? È R-4, e sulla 002 è esattamente il controllo che era saltato | Media |
| TC-08 | **TalkBack**: il lucchetto annuncia stato e azione; un interruttore bloccato si annuncia disabilitato | Media |
| TC-09 | **Build release (R8)**: la preferenza si legge e si scrive anche offuscata. DataStore usa chiavi per stringa, quindi il rischio è basso — ma la 002 ha insegnato che il rischio basso non provato resta non provato | Media |

### Regressione

- `./gradlew testDebugUnitTest assembleDebug lintDebug` deve restare verde: **66 test, 0
  falliti** è la linea di partenza
- In scrittura, il giro dei comandi deve comportarsi **identico alla 1.1.0**

---

## E. Rischi tecnici aggiornati

| # | Rischio | Stato dopo l'analisi |
|---|---|---|
| R-1 | L'interruttore scatta e torna | **Ridimensionato.** `Switch` (riga 455) e `Slider` (riga 472) hanno già `enabled = !item.state.unreachable`: basta aggiungere `&& !locked`. Il meccanismo esiste, è collaudato, ed è già commentato con lo stesso ragionamento |
| R-2 | La finestra fra primo frame e valore letto | **Ha una soluzione strutturale.** `uiState` è un `combine` di quattro flussi con `initialValue = DeviceListUiState()` e `loading = true`, e finché `loading` è vero la schermata mostra il *progress* invece dell'elenco (riga 233). Mettendo il flusso della preferenza **dentro lo stesso `combine`**, l'elenco non esiste finché la preferenza non è stata letta: non c'è nessun controllo da toccare. Da verificare con TC-04, non da dare per fatto |
| R-3 | Scambiarlo per sicurezza | Invariato. Va scritto nel README |
| R-4 | Affollamento della barra | Invariato, e resta l'unico rischio che si chiude solo guardando lo schermo. Il titolo ha già due righe di servizio e può avere l'insetto rosso accanto al nome |
| R-5 | Comandi fuori dalla vista principale | **Chiuso, e a favore.** `DeviceCommand` compare in tre soli file: `DeviceListViewModel`, l'interfaccia `DeviceDriver` e `MqttDeviceDriver`. **Non esiste nessun percorso che comandi un dispositivo fuori dalla lista** — la schermata di modifica non manda comandi. La decisione «il blocco vale solo per la vista principale» non lascia scoperto niente |
| **R-6** | **La preferenza nel posto sbagliato spegne il broker.** *Nuovo, ed è il rischio serio di questa feature.* Se il flag finisse dentro `BrokerSettings`, ogni tocco del lucchetto chiuderebbe e riaprirebbe il collegamento MQTT: il driver osserva quel flusso e `BrokerSettings` è una `data class`, quindi basta un campo diverso perché `distinctUntilChanged` lasci passare e il driver riconnetta | **Alto impatto, ma già mappato.** È scritto nero su bianco in testa a `RegistryStore.kt`, che esiste separato per questa identica ragione. Mitigazione: file DataStore proprio (`vista`), mai `BrokerSettingsStore`. TC-06 lo verifica guardando `clientIdInUse` in `/state` |
| **R-7** | **Il presidio doppio che mente.** Se si mette la guardia *solo* nel `send()` del ViewModel, i controlli restano vivi e si ricade in R-1; se si mette *solo* nei Composable, una via di comando aggiunta domani la scavalca in silenzio | Servono entrambi, con ruoli dichiarati: il `enabled` è **l'interfaccia**, la guardia in `send()` è **la rete**. Il commento deve dire che la seconda non è ridondanza |

---

## F. Prerequisiti e task bloccanti

**Nessun task bloccante.** Non serve refactoring preliminare, non ci sono migrazioni, non ci
sono dipendenze nuove da approvare — a meno di non voler aggiungere `material-icons-extended`,
che la sezione B sconsiglia.

Due decisioni prese in revisione, 13 settembre 2026:

1. **Come si chiama la cosa** → **`locked`**. Lo store è `ViewLockStore`, il campo è
   `locked: Boolean`, l'azione è `toggleLock()`, le stringhe hanno prefisso `view_locked_`.
   Avevo proposto `readOnly` per non sovraccaricare la parola «bloccato» in un'app che parla
   di connessioni: la scelta è caduta su `locked`, che ha dalla sua di essere la stessa
   parola dell'icona. I nomi in questo documento sono già aggiornati
2. **Il disegno delle due icone** → **approvato**: due drawable, lucchetto aperto e chiuso,
   tratto coerente con `ic_device_*.xml`. Niente `material-icons-extended`

Il refactoring di tre righe per rendere pura la regola di abilitazione (sezione D) non è un
prerequisito: si fa strada facendo, ed è ciò che rende scrivibile l'unico test in JVM.
