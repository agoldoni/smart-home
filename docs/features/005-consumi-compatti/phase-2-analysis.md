# Consumi compatti — Analisi tecnica

**Stato:** Fase 2 — in attesa di conferma per la Fase 3
**Autore:** Alberto Goldoni
**Data:** 14 settembre 2026
**Versione:** 1.0
**Feature:** `005-consumi-compatti`
**Riferimento:** [`phase-1-requirements.md`](phase-1-requirements.md)

---

## A. File coinvolti

### Il ponte

| File | Tipo | Cosa e perché |
|---|---|---|
| `bridge/tuya-mqtt/bridge.py` | modifica | Quattro punti, e nessuno di più: `Archivio` (:160) prende un metodo per sommare su un **intervallo** di giorni e per dire quante righe l'hanno prodotto; `Contatore` (:216) impara a calcolare **ieri e il lunedì** in locale; `Presa._rileggi_totali()` (:488) rilegge due totali in più; `Presa._pubblica_energia()` (:496) li scrive nel payload |
| `bridge/tuya-mqtt/test_bridge.py` | modifica | Tre classi nuove. Il file ne ha già dieci e la decima, `ArchivioSuFile` (:270), è il modello da seguire: monta un archivio vero su file temporaneo |

**Nessuna dipendenza nuova.** `datetime` e `ZoneInfo` sono già importati (:27-28); serve in più `timedelta`, che sta nello stesso modulo.

### Il contratto e il configuratore

| File | Tipo | Cosa e perché |
|---|---|---|
| `bridge/configuratore/SCHEMA.md:82-84` | modifica | Due righe nella tabella del dispositivo. La sezione *Far evolvere lo schema* autorizza già l'aggiunta senza alzare `schema` |
| `bridge/configuratore/web/campi.js:53-54` | modifica | Due voci nella sezione «Consumi». È il posto da cui si genera il modulo |
| `bridge/configuratore/web/modelli.js:26-28` | modifica | Il modello `ponte` precompila i due nomi |
| `bridge/configuratore/web/registro.js:17` e `:146-147` | modifica | **Due punti separati e tutti e due obbligatori**: la lista `NULLABILI` e la lista chiusa dentro `serializza()`. Vedi R-10: è il trabocchetto principale della feature |
| `tools/genera-registro.mjs` | invariato | Non si tocca, ma **va rieseguito**: produce `app/src/test/resources/registro-ponte.json` con il codice vero della web app |
| `app/src/test/resources/registro-ponte.json` | rigenerato | Il documento su cui gira il test del contratto |

### L'app

| File | Tipo | Cosa e perché |
|---|---|---|
| `app/src/main/java/it/agoldoni/smarthome/domain/model/Device.kt:66-70` | modifica | Due campi `String?` accanto a `energyTodayJsonKey` e `energyMonthJsonKey`. `subscriptions` (:128-135) **non cambia**: il topic è sempre uno solo |
| `.../domain/model/DeviceState.kt:27-28` | modifica | `kwhYesterday` e `kwhWeek`, `Double?`, accanto a `kwhToday` e `kwhMonth` |
| `.../data/local/DeviceEntity.kt:27-29, :55-57, :82-84` | modifica | Tre punti: i campi dell'entity, `toDomain()` e `toEntity()`. Dimenticarne uno compila lo stesso e perde il dato in silenzio |
| `.../data/local/SmartHomeDatabase.kt:8` e coda | modifica | `version = 7` e `MIGRATION_6_7` sul modello di `MIGRATION_3_4` (:47-56), che aveva aggiunto proprio queste tre colonne |
| `.../di/AppContainer.kt:11, :46` | modifica | L'import e `addMigrations(...)`: la migrazione va **registrata**, o Room cade all'avvio |
| `app/schemas/7.json` | generato | `exportSchema = true`: lo scrive Room alla compilazione e va committato, come i sei che ci sono |
| `.../driver/mqtt/MqttDeviceDriver.kt:549-559` | modifica | Il ramo del topic dell'energia legge due campi in più. Vedi R-11: qui c'è una decisione da prendere, non solo due righe da copiare |
| `.../domain/registry/DeviceRegistry.kt:181-183` | modifica | Due `voce.testo(...)` in più. `testo()` (:204) restituisce già null per assente, vuoto e `JSONObject.NULL` |
| `.../diagnostics/DebugReport.kt:353, :362-363, :384-385` | modifica | L'API di debug espone il topic, le chiavi JSON e i due valori. **Non è un contorno:** è lo strumento con cui le verifiche sul campo si fanno con `curl` invece che con gli occhi sullo schermo, e senza i due campi nuovi TC-03 non si potrebbe provare |
| `.../ui/devices/DeviceEditViewModel.kt:30-32, :201-203, :228-230` | modifica | Il form, `toForm()` e `toDevice()`. La validazione (:169-173) **resta com'è**: obbligatorio è solo il campo di oggi |
| `.../ui/devices/DeviceEditScreen.kt:191-209` | modifica | Da una `Row` di due campi a due righe da due, dentro lo stesso `if (form.energyTopic.isNotBlank())` |
| `.../ui/devices/DeviceListScreen.kt:534-542, :615-631` | modifica | `energyLine()` e `formatKwh()`. Vedi R-12: `energyLine` va **estratta** prima di essere riscritta |
| `.../ui/devices/EnergiaCompatta.kt` | **nuovo** | La costruzione della riga come funzione pura, testabile senza Compose |
| `app/src/main/res/values/strings.xml:49-50` | modifica | Le due stringhe di oggi diventano una sola più il segnaposto del trattino |
| `app/src/main/res/values/strings.xml:75-76` | modifica | Due etichette nuove per i campi del modulo |
| `app/build.gradle.kts:30-31` | modifica | `versionCode = 12`, `versionName = "1.4.0"` |

### I test dell'app

| File | Tipo | Cosa |
|---|---|---|
| `app/src/test/.../driver/mqtt/MqttPayloadsTest.kt:132-146` | modifica | Le sottoscrizioni non cambiano: serve solo che il test resti verde |
| `app/src/test/.../domain/registry/RegistryContractTest.kt:61-75` | modifica | Il test `la presa porta i quattro topic e i quattro campi che il ponte usa` diventa a sei campi |
| `app/src/test/.../domain/registry/DeviceRegistryTest.kt` | modifica | Un caso nuovo: registro **senza** i due campi → i due valori restano null |
| `app/src/test/.../domain/model/DeviceListensLikeTest.kt` | modifica | I due campi nuovi cambiano l'ascolto: va dichiarato in un test, non lasciato all'esclusione |
| `app/src/test/.../ui/devices/EnergiaCompattaTest.kt` | **nuovo** | Il formato a tre scalini, i trattini, la riga assente |

### Lo stack di sviluppo

| File | Tipo | Cosa |
|---|---|---|
| `devops/dev/prese-finte/prese-finte.sh:33, :40` | modifica | Quattro valori; **e una presa che ne pubblica due**, per vedere i trattini senza costruirli a mano |
| `devops/dev/genera-registro-dev.mjs:69-71` | modifica | I due campi nuovi, e il sensore che li azzera come gli altri |
| `devops/dev/registro-dev.json` | rigenerato | Lo produce lo script qui sopra |

### La documentazione

| File | Tipo | Cosa |
|---|---|---|
| `bridge/README.md:130-145` | modifica | Il payload dell'energia con i quattro valori, e cosa vuol dire «settimana» |
| `bridge/README.md:223-224` | modifica | La tabella dei campi da scrivere nel modulo |
| `README.md:71-72` | modifica | La riga della tabella dei campi |
| `README.md:143-149` | modifica | L'esempio dei campi **e la legenda dell'ordine**: è l'unico posto dove quell'ordine sarà scritto |

---

## B. Contratti e interfacce da modificare

### B.1 Il payload MQTT dell'energia — additivo

Prodotto da `Presa._pubblica_energia()` (`bridge.py:496-513`), ritenuto sul topic
`casa/<nome>/energia`.

```jsonc
// oggi
{"kwh_oggi":0.842,"kwh_mese":27.31,"giorno":"2026-09-12","mese":"2026-09",
 "sorgente":"dp17","copertura_oggi":0.98}

// dopo
{"kwh_oggi":0.842,"kwh_ieri":2.104,"kwh_settimana":9.77,"kwh_mese":27.31,
 "giorno":"2026-09-12","ieri":"2026-09-11","settimana":"2026-09-08","mese":"2026-09",
 "sorgente":"dp17","copertura_oggi":0.98}
```

**Nessun breaking change:** i campi esistenti non cambiano nome, tipo né significato. Chi
legge per chiave — l'app, con `campo_kwh_oggi` — non si accorge di niente.

**`kwh_ieri` può mancare** (D-4). È l'unico dei quattro che può: `kwh_oggi`, `kwh_settimana` e
`kwh_mese` contengono sempre l'ora in corso, che è una misura vera appena il contatore parte,
e `_pubblica_energia()` esce subito (`if giorno is None: return`) finché quella misura non
c'è. «Ieri» invece è fatto di sole ore chiuse, e per una presa aggiunta stamattina quelle ore
non esistono.

Ne discende una semplificazione che vale la pena dichiarare: **la distinzione fra somma zero
e nessuna riga serve a un campo solo.** Gli altri tre possono continuare a usare il
`COALESCE(...,0)` che `Archivio.totale()` ha già.

### B.2 Il registro — additivo, `schema` resta `1`

Due campi nuovi per dispositivo, stringa o `null`, facoltativi:

| Campo | Tipo | Obbl. | Predefinito |
|---|---|---|---|
| `campo_kwh_ieri` | stringa/null | no | `null` |
| `campo_kwh_settimana` | stringa/null | no | `null` |

Le quattro direzioni di compatibilità, tutte e quattro necessarie:

| Chi scrive | Chi legge | Cosa succede |
|---|---|---|
| registro nuovo | app 1.3.0 | Campi sconosciuti **ignorati** (regola già scritta in `SCHEMA.md`). Mostra due numeri come sempre |
| registro vecchio | app 1.4.0 | `voce.testo(...)` → null → due caselle col trattino |
| ponte nuovo | app 1.3.0 | Chiavi JSON che nessuno legge |
| ponte vecchio | app 1.4.0 | Due valori su quattro, due trattini. È US-7 |

### B.3 Lo schema locale — migrazione 6→7

```sql
ALTER TABLE devices ADD COLUMN energyYesterdayJsonKey TEXT;
ALTER TABLE devices ADD COLUMN energyWeekJsonKey TEXT;
```

Due colonne nullable **senza `DEFAULT`**, esattamente come le tre di `MIGRATION_3_4`. Nullo
qui dice una verità — «di questo dispositivo nessuno conta ieri» — ed è vera per ogni riga
già nel database.

### B.4 Le interfacce Kotlin

`Device` e `DeviceState` sono `data class` con valori predefiniti su tutti i campi nuovi:
nessun chiamante da aggiornare, nessuna costruzione posizionale da correggere. I test che
costruiscono `Device(...)` per nome continuano a compilare.

**Un contratto cambia davvero, ed è `Device.listensLike()`** (`Device.kt:123-127`). È scritto
come esclusione — `anonimo()` azzera id, uuid, nome, stanza e posizione — quindi i due campi
nuovi **entrano da soli**, ed è giusto: cambiano davvero *come si legge* il payload
dell'energia. La conseguenza è in R-7.

---

## C. Pattern da rispettare

**C.1 — Il nullo dice una verità, non ripiega.** È la regola che attraversa tutto il
progetto: `DeviceState.reachable` (`DeviceState.kt:40`), `Device.position`
(`Device.kt:104`), le cinque migrazioni, `readAvailability()`
(`MqttPayloads.kt:72-79`), `JSONObject.posizione()` (`DeviceRegistry.kt:219-227`). Qui si
declina in due punti: la chiave che manca nel payload e la casella col trattino.

**C.2 — Le migrazioni si scrivono a mano e sono additive.** Nessun
`fallbackToDestructiveMigration` in `AppContainer.kt`: nel database ci sono dispositivi
inseriti uno per uno, e perderli per una colonna sarebbe un pessimo scambio. Ogni migrazione
porta un commento che spiega **perché quel default** — o perché non ce n'è.

**C.3 — I campi del registro si dichiarano in `campi.js`, ma si serializzano in
`registro.js`.** Il commento in testa a `campi.js` dice «un campo aggiunto in questo elenco
compare nell'interfaccia e finisce nel JSON senza toccare altro», e **non è del tutto vero**:
`serializza()` (`registro.js:125-166`) costruisce l'oggetto da una **lista chiusa di chiavi**.
Lo dice il commento su `posizione` (:158-161), che è lì proprio perché qualcuno ci è già
inciampato: *«ciò che non compare in questa lista non viene ripubblicato»*. Vedi R-10.

**C.4 — Il test del contratto gira sul documento vero.** `RegistryContractTest` legge
`app/src/test/resources/registro-ponte.json`, che **non è scritto a mano**: lo produce
`tools/genera-registro.mjs` con il modello, la validazione e la serializzazione della web
app. È l'unico test che verifica che i due programmi si capiscano invece di capirsi ognuno
con sé stesso. Dopo aver toccato `modelli.js` o `registro.js` va rigenerato:

```bash
node tools/genera-registro.mjs app/src/test/resources/registro-ponte.json
```

**C.5 — Niente stringhe nell'interfaccia.** Tutto in `strings.xml`, compreso il trattino:
un `–` scritto in Kotlin sarebbe l'unico pezzo di testo dell'app fuori dalle risorse.

**C.6 — I nomi del payload sono in italiano, minuscoli, con l'underscore.** `kwh_oggi`,
`kwh_mese`, `copertura_oggi`. `kwh_ieri` e `kwh_settimana` non hanno alternative sensate.

**C.7 — Il ponte non prende dipendenze.** `requirements.txt` ha quattro righe e ci resta:
il lunedì si calcola con `timedelta`, non con una libreria di calendari.

**C.8 — I commenti dicono il perché.** Il codice di questo progetto spiega le decisioni nel
punto in cui si vedono. Le due che meritano un commento sul posto: perché ieri si calcola
sulla **data locale** e non sull'epoch (C.9), e perché `kwh_ieri` può mancare.

**C.9 — L'aritmetica dei giorni si fa sulle date, mai sui secondi.** `Contatore` tiene
`self._ora` come epoch e la converte in locale solo per scrivere la riga
(`bridge.py:317-330`). Calcolare ieri come `_ora - 86400` sarebbe sbagliato **due giorni
l'anno**: quelli da 23 e 25 ore. Va fatto su `date`:

```python
locale = datetime.fromtimestamp(self._ora, timezone.utc).astimezone(self.fuso)
giorno = locale.date()
ieri = giorno - timedelta(days=1)
lunedi = giorno - timedelta(days=giorno.weekday())   # weekday(): lunedì = 0
```

`timedelta` su un `date` conta giorni di calendario, non secondi: è esattamente quello che
serve, ed è il motivo per cui la conversione in locale va fatta **prima** della sottrazione.

---

## D. Test da creare o aggiornare

### D.1 Il ponte — `bridge/tuya-mqtt/test_bridge.py`

Girano con `cd bridge/tuya-mqtt && python -m unittest test_bridge -v`.

| Classe | Tipo | Cosa prova |
|---|---|---|
| `TotaliDiIeri` | unit su file | Archivio con righe di ieri e di oggi: `kwh_ieri` somma solo ieri e **non cambia** quando arrivano letture di oggi |
| `IeriSenzaRighe` | unit su file | Archivio con solo le righe di oggi: il metodo restituisce `None` e la chiave **non compare** nel payload. È il test di D-4, e va scritto guardando il payload, non il metodo |
| `SettimanaDiCalendario` | unit | Righe da domenica a mercoledì: la somma parte dal **lunedì** e la domenica precedente resta fuori. Un caso con `weekday() == 0` (lunedì: settimana = oggi) e uno con la settimana a cavallo di un mese |
| `SettimanaConCambioDOra` | unit | La settimana del 25 ottobre 2026 (25 ore): la somma pubblicata coincide con la somma delle righe di quei sette giorni. È il caso che C.9 esiste per non sbagliare |

Da riusare: `ArchivioSuFile` (:270) monta già un `Archivio` su file temporaneo e
`RolloverLocale` (:214) sa già costruire un `Contatore` con un fuso e farlo attraversare la
mezzanotte.

### D.2 L'app — `./gradlew testDebugUnitTest`

| File | Cosa |
|---|---|
| `EnergiaCompattaTest.kt` **nuovo** | I tre scalini (`0,42` / `12,7` / `439`); i quattro valori uniti da `/`; una casella mancante che diventa trattino **senza spostare le altre**; zero che resta `0,00` e non diventa trattino; nessuno dei quattro → `null`, nessuna riga |
| `RegistryContractTest.kt:61-75` | I due campi nuovi letti dal documento vero, dopo averlo rigenerato |
| `DeviceRegistryTest.kt` | Un registro **senza** i due campi: i due valori restano null e il dispositivo **non** viene saltato |
| `DeviceListensLikeTest.kt` | Cambiare `energyYesterdayJsonKey` **cambia** l'ascolto; cambiare il nome no. Il primo caso è nuovo, il secondo c'è già |
| `MqttPayloadsTest.kt` | La lettura dei due campi dal payload, e il caso della chiave assente secondo la decisione di R-11 |

### D.3 Quello che nessun test automatico copre

Resta a mano, contro `devops/dev`:

- la larghezza della riga sullo schermo vero, con il caso peggiore costruito a posta
  (`9,99/99,9/999/9999`)
- il modulo di registrazione con i due campi nuovi
- la scheda con due valori su quattro (la presa finta che ne pubblica due)
- l'app **1.3.0** contro il registro nuovo: sette dispositivi, nessuno saltato

---

## E. Rischi tecnici aggiornati

I rischi della Fase 1, con le evidenze trovate nel codice, più tre nuovi.

| # | Stato dopo l'analisi |
|---|---|
| **R-1** *riga senza etichette* | Invariato. È il costo accettato di D-1 |
| **R-2, R-3** *formato e larghezza* | **Chiusi da D-5.** `formatKwh` (`DeviceListScreen.kt:630-631`) è usata **solo** dalla riga dei consumi: cambiarla non tocca nient'altro |
| **R-4** *i totali si rileggono solo alla chiusura di un'ora* | **Verificato e regge.** `conta()` è chiamata su tutti e tre i rami del ciclo: presa non ancora vista in rete (:574, poi 10s di attesa), lettura fallita (:608), lettura riuscita (:627). Anche la riconnessione con backoff (:647, max 60s) rientra dal ramo :574. Quindi le ore si chiudono comunque, la mezzanotte è una chiusura d'ora, e `_rileggi_totali()` (:485) gira **prima** di `_pubblica_energia()` (:486). L'invariante da non rompere è scritta nel commento a :568-571, e va lasciata dov'è |
| **R-5** *ritenuto vecchio sul broker* | Invariato: è US-7 |
| **R-6** *zero contro assente* | **Ridimensionato.** Riguarda `kwh_ieri` e basta (vedi B.1): `Archivio.totale()` (:200) resta com'è per giorno e mese, e accanto nasce un metodo che restituisce anche il numero di righe |
| **R-7** *i due campi cambiano `listensLike`* | **Confermato, e innocuo.** `track()` (`MqttDeviceDriver.kt:208`) confronta con `listensLike` e risottoscrive i cambiati. Alla prima applicazione del registro nuovo le sette prese risultano cambiate → una risottoscrizione. `planRegistry` le marca `updated` → una scrittura. Entrambe **una volta sola**: dalla seconda pubblicazione i campi coincidono. Da verificare che sia davvero una e non una per pubblicazione |
| **R-8** *stack di sviluppo* | Invariato |
| **R-9** *costo delle query* | **Chiuso.** L'indice `energia_per_giorno (giorno, presa)` (`bridge.py:143`) ha `giorno` come prima colonna: un `BETWEEN` su `giorno` più l'uguaglianza su `presa` lo usa. Sette prese, una volta l'ora |

### I tre rischi nuovi

| # | Rischio | Impatto | Mitigazione |
|---|---|---|---|
| **R-10** | **La lista chiusa di `serializza()`.** `registro.js:125-166` costruisce il documento da un elenco esplicito di chiavi. Un campo aggiunto in `campi.js` **compare nel modulo, si compila, si salva in memoria — e sparisce alla pubblicazione**, senza nessun errore. Il commento su `posizione` (:158-161) è lì perché è già successo | **Alto** — è il modo più silenzioso di non funzionare, e si scopre dal telefono che non mostra niente | Toccare i **tre** punti di `registro.js` insieme: `NULLABILI` (:17), `serializza()` (:146-147) e, se serve, `valida()` (:55). Poi rigenerare `registro-ponte.json` e far girare `RegistryContractTest`: è il test che se ne accorge |
| **R-11** | **`readNumber` non azzera mai.** `MqttDeviceDriver.kt:553-558` usa `?.let { next = next.copy(...) }`: se la chiave sparisce dal payload, **resta l'ultimo valore letto**. Con D-4 — che fa mancare `kwh_ieri` quando l'archivio non ha righe — una presa mostrerebbe per sempre un «ieri» vecchio invece del trattino | Medio | Il topic dell'energia è una **fotografia completa** pubblicata da un produttore solo, non una toppa: va letto come tale. **Raccomandazione:** chiave assente → il valore torna a null (trattino); chiave presente ma illeggibile → si tiene l'ultimo, perché da un valore che non si è capito non si deduce niente. Serve distinguere i due casi, che oggi `extractJson` (`MqttPayloads.kt:32-49`) confonde entrambi in `null`. È la decisione aperta della Fase 3 |
| **R-12** | **La riga non è testabile dov'è.** `energyLine()` (`DeviceListScreen.kt:619-627`) è `private` e `@Composable`: chiama `stringResource`, quindi un unit test non la può eseguire. Il formato a quattro caselle con i trattini è **proprio** la parte che va provata per casi | Medio — senza estrazione, D-5 e i trattini restano verificati solo a occhio | Estrarre in `EnergiaCompatta.kt` una funzione **pura**: prende i quattro `Double?` e il testo del trattino, restituisce `String?`. Alla Composable restano i due `stringResource`. È lo stesso taglio che il progetto ha già fatto fra `MqttPayloads.kt` (puro, testato) e il driver |

---

## F. Prerequisiti e task bloccanti

**Nessun refactoring bloccante.** Le tre cose da fare prima di scrivere il codice vero sono
piccole e tutte dentro il perimetro della feature.

1. **Decidere R-11** — chiave assente: si azzera o si tiene? È l'unica decisione di
   progettazione rimasta, tocca il contratto di lettura del payload, e va presa **prima** di
   scrivere il ramo dell'energia nel driver. La raccomandazione è in R-11.

2. **Estrarre la riga dei consumi** (R-12) in `EnergiaCompatta.kt`, a comportamento
   invariato, **prima** di cambiarne il formato. Due passi separati: se il test nuovo
   fallisce, si sa se è colpa dell'estrazione o del formato.

3. **Il metodo dell'archivio prima dei chiamanti.** `Archivio.totale()` (:200) non può
   restituire `None`: `_archivio_giorno` e `_archivio_mese` finiscono dentro una somma
   (:502-503). Il metodo nuovo nasce **accanto**, non al posto suo.

### L'ordine che conta

L'ordine delle milestone della Fase 1 regge, con una precisazione trovata nell'analisi: il
punto 4 («l'app dal basso») va spezzato, perché `app/schemas/7.json` lo scrive il compilatore
e va committato insieme alla migrazione, non dopo.

E resta la regola di dispiegamento: **il ponte prima dell'app.** Il contrario funziona
comunque — sono i due trattini di US-7 — ma non c'è motivo di farli vedere.

### Comandi

```bash
cd bridge/tuya-mqtt && python -m unittest test_bridge -v     # il ponte
node tools/genera-registro.mjs app/src/test/resources/registro-ponte.json
node devops/dev/genera-registro-dev.mjs                       # il registro di sviluppo
./gradlew testDebugUnitTest                                   # l'app
```

### Cosa non serve

- Nessuna modifica a `dispositivi.yaml` né alla configurazione del ponte: la settimana non è
  configurabile, è una regola
- Nessuna modifica allo schema di `energia.db`: `energia_grezza` e la vista `energia` restano
  identiche, si aggiungono due `SELECT`
- Nessuna modifica a `DeviceDao`, `DeviceRepository`, `RegistrySync`, `RegistryPlan`: leggono
  e scrivono `Device` interi e i campi nuovi passano da soli
- `DebugReport.kt:326-328` avvisa già di un topic dei consumi senza campo da leggere: la
  regola non cambia, perché obbligatorio resta il solo campo di oggi
- Nessun `compose.yml` da toccare, né in `bridge/` né in `devops/dev/`
