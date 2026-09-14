"""Prove sulla logica che e' facile sbagliare in silenzio.

Il comportamento coperto qui e' quello che, sbagliato, non da' errori: fa solo
commutare un relay piu' volte del dovuto, e te ne accorgi stando accanto alla
presa. Non si puo' verificare contro un dispositivo vero, perche' richiede una
presa irraggiungibile su comando.
"""

import datetime
import json
import os
import shutil
import tempfile
import time
import unittest

import bridge


class FintoMqtt:
    def publish(self, *a, **k):
        pass


class FintaScoperta:
    def indirizzo(self, _):
        return "192.168.0.1"

    def versione(self, _):
        return "3.3"


def presa(**extra):
    cfg = {"nome": "prova", "id": "x" * 22, "chiave": "k" * 16, "versione": "3.3"}
    cfg.update(extra)
    return bridge.Presa(cfg, {}, FintaScoperta(), FintoMqtt(), "casa", 1)


class ComandiNonFannoCoda(unittest.TestCase):

    def test_l_ultimo_comando_sostituisce_i_precedenti(self):
        p = presa()
        for payload in ("OFF", "ON", "OFF", "ON", "OFF"):
            p.accoda_interruttore(payload)
        comando, _ = p._richiesta_corrente()
        self.assertEqual(comando, ("interruttore", False))

    def test_una_sola_richiesta_in_attesa(self):
        p = presa()
        p.accoda_interruttore("ON")
        prima = p._richiesta_corrente()
        p.accoda_interruttore("OFF")
        dopo = p._richiesta_corrente()
        self.assertIsNot(prima, dopo)
        self.assertEqual(dopo[0], ("interruttore", False))

    def test_consumare_una_richiesta_vecchia_non_cancella_quella_nuova(self):
        # Il caso vero: il ciclo prende la richiesta, la presa e' lenta, nel
        # frattempo l'utente tocca di nuovo l'interruttore. Quando il comando
        # vecchio finalmente passa, non deve portarsi via quello nuovo.
        p = presa()
        p.accoda_interruttore("ON")
        vecchia = p._richiesta_corrente()
        p.accoda_interruttore("OFF")
        p._consuma(vecchia)
        self.assertIsNotNone(p._richiesta_corrente())
        self.assertEqual(p._richiesta_corrente()[0], ("interruttore", False))

    def test_consumare_la_richiesta_corrente_la_cancella(self):
        p = presa()
        p.accoda_interruttore("ON")
        corrente = p._richiesta_corrente()
        p._consuma(corrente)
        self.assertIsNone(p._richiesta_corrente())

    def test_toggle_parte_dall_ultimo_stato_pubblicato(self):
        p = presa()
        p._ultimo_stato = json.dumps({"stato": "ON"})
        p.accoda_interruttore("TOGGLE")
        self.assertEqual(p._richiesta_corrente()[0], ("interruttore", False))

    def test_payload_sconosciuto_non_chiede_niente(self):
        p = presa()
        p.accoda_interruttore("forse")
        self.assertIsNone(p._richiesta_corrente())


class StatoParziale(unittest.TestCase):

    def test_lo_stato_riporta_le_letture_scalate(self):
        p = presa(letture={"potenza_w": {"dp": 19, "scala": 10}})
        corpo = json.loads(p._payload_stato({"1": True, "19": 2334}, "1.2.3.4", "3.3"))
        self.assertEqual(corpo["stato"], "ON")
        self.assertEqual(corpo["potenza_w"], 233.4)

    def test_dp_dell_interruttore_assente_significa_spento_non_acceso(self):
        # Il ciclo non deve mai arrivare qui con un frammento, ma se ci
        # arrivasse la lettura prudente e' "spento".
        p = presa()
        corpo = json.loads(p._payload_stato({"20": 2300}, "1.2.3.4", "3.3"))
        self.assertEqual(corpo["stato"], "OFF")



def epoch(iso):
    return (datetime.datetime.strptime(iso, "%Y-%m-%dT%H:%M:%SZ")
            .replace(tzinfo=datetime.timezone.utc).timestamp())


def contatore(**kw):
    """Un contatore che conta dal dp 17: da chiedere, non e' piu' il predefinito."""
    kw.setdefault("dp", "17")
    kw.setdefault("sorgente_preferita", "contatore")
    return bridge.Contatore("prova", **kw)


class ContatoreDalDpCumulativo(unittest.TestCase):
    """Il dp 17 non e' un totale: e' un contatore che si azzera da solo.

    Trattarlo come un totale monotono da' delta negativi il giorno che il cloud
    lo rilegge, e nessuno se ne accorge finche' non guarda le somme di un mese.
    """

    def test_la_prima_lettura_e_solo_un_riferimento(self):
        c = contatore()
        c.aggiorna(1000, {"17": 42})
        self.assertEqual(c.riga()[4], 0.0)

    def test_somma_il_delta_fra_due_letture(self):
        c = contatore()
        c.aggiorna(1000, {"17": 10})
        c.aggiorna(1002, {"17": 13})
        self.assertEqual(c.riga()[4], 3.0)

    def test_un_valore_piu_basso_e_un_azzeramento_non_un_delta_negativo(self):
        c = contatore()
        c.aggiorna(1000, {"17": 100})
        c.aggiorna(1002, {"17": 5})
        self.assertEqual(c.riga()[4], 5.0)

    def test_due_letture_identiche_non_aggiungono_niente(self):
        c = contatore()
        c.aggiorna(1000, {"17": 7})
        c.aggiorna(1002, {"17": 7})
        c.aggiorna(1004, {"17": 7})
        self.assertEqual(c.riga()[4], 0.0)

    def test_un_buco_breve_non_perde_energia(self):
        # La presa ha continuato a contare mentre il ponte non guardava: al
        # ritorno il delta la restituisce. Scartarlo vorrebbe dire sottostimare
        # proprio le prese che stanno peggio in wifi.
        c = contatore()
        c.aggiorna(1000, {"17": 10})
        c.aggiorna(1000 + 600, {"17": 30})
        self.assertEqual(c.riga()[4], 20.0)

    def test_un_buco_lungo_riparte_da_capo(self):
        # Tre giorni di consumi scaricati dentro la riga dell'ora corrente
        # sarebbero un numero falso in un posto preciso: peggio di un buco.
        c = contatore()
        c.aggiorna(1000, {"17": 10})
        righe = c.aggiorna(1000 + 3 * 86400, {"17": 5000})
        self.assertEqual(c.riga()[4], 0.0)
        self.assertTrue(all(r[4] == 0.0 for r in righe))

    def test_il_dp_dell_interruttore_non_e_un_contatore(self):
        # dps.get("17") su una presa che non ce l'ha torna None, e True non e'
        # un numero anche se Python lo somma volentieri.
        c = contatore()
        c.aggiorna(1000, {"1": True})
        self.assertIsNone(c.sorgente)


class ContatoreSenzaDpCumulativo(unittest.TestCase):

    def test_integra_la_potenza_e_lo_dichiara(self):
        c = contatore(dp=None)
        c.aggiorna(1000, {}, potenza_w=3600)
        c.aggiorna(1010, {}, potenza_w=3600)
        self.assertEqual(c.sorgente, "integrale")
        self.assertAlmostEqual(c.riga()[4], 10.0, places=3)

    def test_una_pausa_troppo_lunga_non_si_integra(self):
        c = contatore(dp=None)
        c.aggiorna(1000, {}, potenza_w=3600)
        c.aggiorna(1000 + 600, {}, potenza_w=3600)
        self.assertEqual(c.riga()[4], 0.0)


class SorgentePredefinita(unittest.TestCase):
    """Dal 14/09/2026 si conta integrando la potenza, e il dp 17 va chiesto.

    Il contrario sottostimava di un fattore due senza segnalare niente: le righe
    c'erano, la copertura era piena, e a vederlo era solo il confronto con
    l'app di Tuya.
    """

    def test_il_predefinito_integra_la_potenza_anche_se_il_dp_c_e(self):
        c = bridge.Contatore("prova", dp="17")
        c.aggiorna(1000, {"17": 10}, potenza_w=3600)
        c.aggiorna(1010, {"17": 900}, potenza_w=3600)
        self.assertEqual(c.sorgente, "integrale")
        self.assertAlmostEqual(c.riga()[4], 10.0, places=3)

    def test_il_dp_17_si_conta_solo_se_lo_si_chiede(self):
        c = contatore()
        c.aggiorna(1000, {"17": 10}, potenza_w=3600)
        c.aggiorna(1002, {"17": 13}, potenza_w=3600)
        self.assertEqual(c.sorgente, "dp17")
        self.assertEqual(c.riga()[4], 3.0)

    def test_un_refuso_non_riporta_di_nascosto_al_contatore(self):
        self.assertEqual(bridge.sorgente_valida("Potenza "), "potenza")
        self.assertEqual(bridge.sorgente_valida("contatore"), "contatore")
        self.assertEqual(bridge.sorgente_valida("integrale"), "potenza")
        self.assertEqual(bridge.sorgente_valida(None, "contatore"), "contatore")


class CambioDiSorgente(unittest.TestCase):
    """Il primo avvio dopo il cambio: lo stato salvato parla un'altra lingua."""

    def _salvato(self, sorgente):
        return {"ora": bridge.Contatore._inizio_ora(time.time()),
                "grezzo": 500.0, "coperti": 1200.0,
                "ultimo_valore": 128, "sorgente": sorgente}

    def test_non_si_riprendono_le_tacche_dentro_una_riga_di_Wh(self):
        c = bridge.Contatore("prova", dp="17")
        c.riprendi(self._salvato("dp17"))
        self.assertEqual(c.riga()[4], 0.0)

    def test_dopo_il_cambio_il_contatore_non_resta_muto(self):
        # Il guasto che si vedrebbe solo dalle somme: la sorgente salvata non e'
        # "integrale", quindi il ripiego non scatterebbe mai e la presa
        # conterebbe zero per sempre, senza una riga di log e con copertura piena.
        c = bridge.Contatore("prova", dp="17")
        c.riprendi(self._salvato("dp17"))
        base = bridge.Contatore._inizio_ora(time.time()) + 10
        c.aggiorna(base, {"17": 130}, potenza_w=3600)
        c.aggiorna(base + 10, {"17": 130}, potenza_w=3600)
        self.assertEqual(c.sorgente, "integrale")
        self.assertAlmostEqual(c.riga()[4], 10.0, places=3)

    def test_lo_stato_della_stessa_sorgente_si_riprende(self):
        c = bridge.Contatore("prova", dp="17")
        c.riprendi(self._salvato("integrale"))
        self.assertEqual(c.riga()[4], 500.0)


class FattoreDellOraInCorso(unittest.TestCase):
    """`kwh_parziale` usa il fattore della sorgente, come fa la vista `energia`."""

    def test_la_taratura_delle_tacche_vale_per_il_contatore(self):
        c = contatore(wh_per_tacca=0.5)
        c.aggiorna(1000, {"17": 0})
        c.aggiorna(1002, {"17": 20})
        self.assertAlmostEqual(c.kwh_parziale, 0.010, places=4)

    def test_la_taratura_delle_tacche_non_tocca_l_integrale(self):
        # Integrando, il grezzo e' gia' in Wh: moltiplicarlo per il fattore delle
        # tacche sarebbe una taratura applicata a un numero che non la vuole.
        c = bridge.Contatore("prova", dp="17", wh_per_tacca=0.5)
        c.aggiorna(1000, {}, potenza_w=3600)
        c.aggiorna(1010, {}, potenza_w=3600)
        self.assertAlmostEqual(c.kwh_parziale, 0.010, places=4)


class Copertura(unittest.TestCase):

    def test_un_ora_guardata_tutta_vale_uno(self):
        c = contatore()
        t = 3600.0
        while t < 7200:
            c.aggiorna(t, {"17": 0})
            t += 2
        self.assertGreaterEqual(c.riga()[6], 0.99)

    def test_venti_minuti_di_buco_si_vedono(self):
        c = contatore()
        t = 3600.0
        while t < 7200:
            if not 4800 <= t < 6000:          # venti minuti senza letture
                c.aggiorna(t, {"17": 0})
            t += 2
        self.assertAlmostEqual(c.riga()[6], 0.67, places=2)

    def test_integrando_un_ora_guardata_tutta_vale_uno(self):
        # A potenza zero non si somma energia ma si somma tempo: "vista e ferma"
        # e "non vista" devono restare due cose diverse, e a dirlo e' la
        # copertura. Integrando e' anche la misura di quanta energia manca.
        c = bridge.Contatore("prova", dp="17")
        t = 3600.0
        while t < 7200:
            c.aggiorna(t, {"17": 5}, potenza_w=0.0)
            t += 2
        self.assertGreaterEqual(c.riga()[6], 0.99)
        self.assertEqual(c.riga()[4], 0.0)

    def test_una_presa_mai_vista_non_ha_copertura(self):
        c = contatore()
        c.aggiorna(3600.0)
        c.aggiorna(5400.0)
        self.assertEqual(c.riga()[6], 0.0)


class RolloverLocale(unittest.TestCase):
    """I due giorni l'anno in cui la giornata non ha 24 ore."""

    def _giornata(self, dal, al, giorno):
        c = contatore(dp=None)
        righe, t = [], epoch(dal)
        while t < epoch(al):
            righe += c.aggiorna(t)
            t += 1800
        return [r for r in righe if r[2] == giorno]

    def test_il_giorno_del_ritorno_all_ora_solare_ha_venticinque_ore(self):
        righe = self._giornata("2026-10-24T22:00:00Z", "2026-10-26T02:00:00Z", "2026-10-25")
        self.assertEqual(len(righe), 25)
        self.assertEqual([r[3] for r in righe].count(2), 2)
        self.assertEqual(len({r[1] for r in righe}), 25)

    def test_il_giorno_dell_ora_legale_ne_ha_ventitre(self):
        righe = self._giornata("2026-03-28T22:00:00Z", "2026-03-30T02:00:00Z", "2026-03-29")
        self.assertEqual(len(righe), 23)
        self.assertNotIn(2, [r[3] for r in righe])

    def test_la_riga_porta_data_locale_e_istante_utc(self):
        righe = self._giornata("2026-09-12T15:00:00Z", "2026-09-12T18:00:00Z", "2026-09-12")
        presa, inizio, giorno, ora, _, _, _ = righe[0]
        self.assertEqual(presa, "prova")
        self.assertEqual(inizio, "2026-09-12T15:00:00Z")
        self.assertEqual((giorno, ora), ("2026-09-12", 17))


class RipresaDopoRiavvio(unittest.TestCase):

    def test_l_ora_in_corso_non_si_perde(self):
        c = contatore()
        adesso = time.time()
        c.aggiorna(adesso, {"17": 10})
        c.aggiorna(adesso + 2, {"17": 25})
        dopo = contatore()
        dopo.riprendi(c.stato())
        self.assertEqual(dopo.riga()[4], 15.0)

    def test_uno_stato_vecchio_non_si_riprende(self):
        c = contatore()
        c.aggiorna(time.time() - 7200, {"17": 10})
        c.aggiorna(time.time() - 7198, {"17": 25})
        dopo = contatore()
        dopo.riprendi(c.stato())
        self.assertEqual(dopo.riga()[4], 0.0)

    def test_uno_stato_illeggibile_non_ferma_niente(self):
        c = contatore()
        c.riprendi(None)
        c.riprendi({})
        self.assertEqual(c.riga()[4], 0.0)


class ArchivioSuFile(unittest.TestCase):

    def setUp(self):
        self.cartella = tempfile.mkdtemp()
        self.archivio = bridge.Archivio(os.path.join(self.cartella, "prova.db"))

    def tearDown(self):
        shutil.rmtree(self.cartella, ignore_errors=True)

    def test_le_tacche_diventano_kwh_solo_nella_vista(self):
        self.archivio.dichiara_fattore("boiler", "dp17", 1.0)
        self.archivio.scrivi(("boiler", "2026-09-12T17:00:00Z", "2026-09-12", 19,
                              1850, "dp17", 1.0))
        self.assertAlmostEqual(self.archivio.totale("boiler", "2026-09-12"), 1.85, places=3)
        self.assertAlmostEqual(self.archivio.totale("boiler", "2026-09"), 1.85, places=3)

    def test_ritarare_non_tocca_l_archivio(self):
        self.archivio.dichiara_fattore("boiler", "dp17", 1.0)
        self.archivio.scrivi(("boiler", "2026-09-12T17:00:00Z", "2026-09-12", 19,
                              1850, "dp17", 1.0))
        self.archivio.dichiara_fattore("boiler", "dp17", 0.92)
        self.assertAlmostEqual(self.archivio.totale("boiler", "2026-09-12"), 1.702, places=3)

    def test_la_stessa_ora_scritta_due_volte_non_raddoppia(self):
        self.archivio.dichiara_fattore("boiler", "dp17", 1.0)
        for _ in range(2):
            self.archivio.scrivi(("boiler", "2026-09-12T17:00:00Z", "2026-09-12", 19,
                                  1000, "dp17", 1.0))
        self.assertAlmostEqual(self.archivio.totale("boiler", "2026-09-12"), 1.0, places=3)

    def test_le_due_ore_del_cambio_orario_convivono(self):
        self.archivio.dichiara_fattore("boiler", "dp17", 1.0)
        self.archivio.scrivi(("boiler", "2026-10-25T00:00:00Z", "2026-10-25", 2, 900, "dp17", 1.0))
        self.archivio.scrivi(("boiler", "2026-10-25T01:00:00Z", "2026-10-25", 2, 700, "dp17", 1.0))
        self.assertAlmostEqual(self.archivio.totale("boiler", "2026-10-25"), 1.6, places=3)

    def test_senza_fattore_dichiarato_l_ora_non_sparisce_per_sbaglio(self):
        # La vista e' un JOIN: se il fattore mancasse, la riga non comparirebbe
        # in nessuna somma. Il ponte li dichiara all'avvio proprio per questo.
        self.archivio.scrivi(("ignota", "2026-09-12T17:00:00Z", "2026-09-12", 19,
                              1000, "dp17", 1.0))
        self.assertEqual(self.archivio.totale("ignota", "2026-09-12"), 0)
        self.archivio.dichiara_fattore("ignota", "dp17", 1.0)
        self.assertAlmostEqual(self.archivio.totale("ignota", "2026-09-12"), 1.0, places=3)


class PeriodiInLocale(unittest.TestCase):
    """Ieri e il lunedi', calcolati sulle date e non sui secondi.

    E' la classe che esiste per due giorni l'anno. Un `_ora - 86400` passerebbe
    tutti gli altri trecentosessantatre, e sbaglierebbe proprio nei due in cui
    nessuno andrebbe a controllare.
    """

    def _periodi(self, istante):
        c = contatore()
        c.aggiorna(epoch(istante), {"17": 0})
        return c.periodi()

    def test_prima_della_prima_lettura_valgono_i_periodi_di_adesso(self):
        # `riga()` gia' la pensava cosi'; `periodi()` no, e la differenza si
        # pagava all'avvio. Provata come coerenza fra le due, che e' l'invariante
        # rotta, e non contro una data calcolata a parte nel test.
        c = contatore()
        giorno, _, _, _ = c.periodi()
        self.assertEqual(giorno, c.riga()[2])

    def test_i_quattro_periodi_di_un_giorno_qualsiasi(self):
        # Sabato 12 settembre 2026, le 17 UTC: le 19 in Italia.
        giorno, mese, ieri, lunedi = self._periodi("2026-09-12T17:00:00Z")
        self.assertEqual(giorno, "2026-09-12")
        self.assertEqual(mese, "2026-09")
        self.assertEqual(ieri, "2026-09-11")
        self.assertEqual(lunedi, "2026-09-07")

    def test_di_lunedi_la_settimana_comincia_oggi(self):
        giorno, _, _, lunedi = self._periodi("2026-09-07T08:00:00Z")
        self.assertEqual(giorno, lunedi)

    def test_di_domenica_il_lunedi_e_sei_giorni_indietro(self):
        _, _, _, lunedi = self._periodi("2026-09-13T08:00:00Z")
        self.assertEqual(lunedi, "2026-09-07")

    def test_la_settimana_scavalca_il_mese(self):
        # Mercoledi' 1 ottobre 2026: il lunedi' e' ancora settembre.
        giorno, mese, _, lunedi = self._periodi("2026-10-01T08:00:00Z")
        self.assertEqual((giorno, mese, lunedi), ("2026-10-01", "2026-10", "2026-09-28"))

    def test_ieri_del_primo_del_mese_e_l_ultimo_del_mese_prima(self):
        _, _, ieri, _ = self._periodi("2026-10-01T08:00:00Z")
        self.assertEqual(ieri, "2026-09-30")

    def test_il_giorno_dopo_il_ritorno_all_ora_solare_ieri_e_quello_da_25_ore(self):
        # Lunedi' 26 ottobre, in ora solare: il giorno prima ne e' durato 25, e
        # sottrarre 86400 secondi riporterebbe dentro il 25 invece che al 25.
        giorno, _, ieri, lunedi = self._periodi("2026-10-26T08:00:00Z")
        self.assertEqual((giorno, ieri), ("2026-10-26", "2026-10-25"))
        self.assertEqual(lunedi, "2026-10-26")

    def test_il_giorno_dopo_il_passaggio_all_ora_legale(self):
        # Lunedi' 30 marzo: il 29 e' durato 23 ore.
        giorno, _, ieri, _ = self._periodi("2026-03-30T08:00:00Z")
        self.assertEqual((giorno, ieri), ("2026-03-30", "2026-03-29"))

    def test_a_mezzanotte_italiana_il_giorno_e_gia_cambiato(self):
        # Le 22:30 UTC sono le 00:30 del giorno dopo, in ora legale.
        giorno, _, ieri, _ = self._periodi("2026-09-12T22:30:00Z")
        self.assertEqual((giorno, ieri), ("2026-09-13", "2026-09-12"))


class SommaSuUnIntervallo(unittest.TestCase):
    """La somma che sa distinguere lo zero dal niente."""

    def setUp(self):
        self.cartella = tempfile.mkdtemp()
        self.archivio = bridge.Archivio(os.path.join(self.cartella, "prova.db"))
        self.archivio.dichiara_fattore("boiler", "dp17", 1.0)

    def tearDown(self):
        shutil.rmtree(self.cartella, ignore_errors=True)

    def _ora(self, giorno, ora, grezzo):
        self.archivio.scrivi(("boiler", f"{giorno}T{ora:02d}:00:00Z", giorno, ora,
                              grezzo, "dp17", 1.0))

    def test_nessuna_riga_non_e_zero(self):
        kwh, righe = self.archivio.somma("boiler", "2026-09-11", "2026-09-11")
        self.assertEqual((kwh, righe), (0, 0))

    def test_una_riga_che_vale_zero_e_una_riga(self):
        # La presa c'era e non ha consumato: e' un fatto, non un buco.
        self._ora("2026-09-11", 3, 0)
        kwh, righe = self.archivio.somma("boiler", "2026-09-11", "2026-09-11")
        self.assertEqual((kwh, righe), (0, 1))

    def test_l_intervallo_comprende_gli_estremi(self):
        self._ora("2026-09-07", 10, 1000)
        self._ora("2026-09-13", 10, 2000)
        kwh, righe = self.archivio.somma("boiler", "2026-09-07", "2026-09-13")
        self.assertAlmostEqual(kwh, 3.0, places=3)
        self.assertEqual(righe, 2)

    def test_fuori_dall_intervallo_non_si_somma(self):
        self._ora("2026-09-06", 10, 5000)      # la domenica prima
        self._ora("2026-09-07", 10, 1000)
        kwh, _ = self.archivio.somma("boiler", "2026-09-07", "2026-09-13")
        self.assertAlmostEqual(kwh, 1.0, places=3)

    def test_le_prese_non_si_mescolano(self):
        self.archivio.dichiara_fattore("frigorifero", "dp17", 1.0)
        self.archivio.scrivi(("frigorifero", "2026-09-11T10:00:00Z", "2026-09-11", 10,
                              9000, "dp17", 1.0))
        self._ora("2026-09-11", 10, 1000)
        kwh, righe = self.archivio.somma("boiler", "2026-09-11", "2026-09-11")
        self.assertAlmostEqual(kwh, 1.0, places=3)
        self.assertEqual(righe, 1)


class PayloadDeiConsumi(unittest.TestCase):
    """Cosa finisce sul topic dell'energia, e cosa non ci finisce.

    Si guarda il payload e non i metodi: la regola che conta — ieri non si
    pubblica quando non si sa — e' una proprieta' del messaggio, e provarla
    sul calcolo lascerebbe scoperto proprio il punto in cui si rompe.
    """

    def setUp(self):
        self.cartella = tempfile.mkdtemp()
        self.archivio = bridge.Archivio(os.path.join(self.cartella, "prova.db"))
        self.archivio.dichiara_fattore("prova", "dp17", 1.0)
        self.presa = presa()
        self.presa.archivio = self.archivio
        self.presa.contatore = contatore()
        self.pubblicati = []
        self.presa._pubblica = lambda topic, payload, ritenuto=True: \
            self.pubblicati.append((topic, payload))

    def tearDown(self):
        shutil.rmtree(self.cartella, ignore_errors=True)

    def _ora(self, giorno, ora, grezzo):
        self.archivio.scrivi(("prova", f"{giorno}T{ora:02d}:00:00Z", giorno, ora,
                              grezzo, "dp17", 1.0))

    def _energia(self, istante="2026-09-12T17:00:00Z"):
        """Una lettura, e il payload dei consumi che ne esce."""
        self.presa.contatore.aggiorna(epoch(istante), {"17": 0})
        self.presa._rileggi_totali()
        self.presa._ultima_energia = None
        self.presa._pubblica_energia()
        topic, payload = self.pubblicati[-1]
        self.assertEqual(topic, "casa/prova/energia")
        return json.loads(payload)

    def test_all_avvio_i_totali_si_leggono_prima_della_prima_lettura(self):
        # Il guasto vero, visto in casa il 14/09/2026: al riavvio `Memoria.riprendi`
        # chiama `_rileggi_totali` su un contatore che non ha ancora letto niente.
        # Senza i periodi, i totali restavano a zero fino alla prima ora chiusa e
        # tutte e sette le prese pubblicavano kwh_oggi 0.0 con l'archivio pieno.
        oggi = self.presa.contatore.riga()[2]
        self._ora(oggi, 3, 700)
        self.presa._rileggi_totali()
        self.presa._pubblica_energia()
        corpo = json.loads(self.pubblicati[-1][1])
        self.assertAlmostEqual(corpo["kwh_oggi"], 0.7, places=3)

    def test_i_quattro_valori_e_le_quattro_date(self):
        self._ora("2026-09-11", 10, 2000)      # ieri
        self._ora("2026-09-12", 10, 500)       # oggi, un'ora gia' chiusa
        self._ora("2026-09-07", 10, 1500)      # lunedi'
        corpo = self._energia()
        self.assertAlmostEqual(corpo["kwh_oggi"], 0.5, places=3)
        self.assertAlmostEqual(corpo["kwh_ieri"], 2.0, places=3)
        self.assertAlmostEqual(corpo["kwh_settimana"], 4.0, places=3)
        self.assertAlmostEqual(corpo["kwh_mese"], 4.0, places=3)
        self.assertEqual(corpo["giorno"], "2026-09-12")
        self.assertEqual(corpo["ieri"], "2026-09-11")
        self.assertEqual(corpo["settimana"], "2026-09-07")
        self.assertEqual(corpo["mese"], "2026-09")

    def test_senza_righe_di_ieri_la_chiave_non_c_e(self):
        self._ora("2026-09-12", 10, 500)
        corpo = self._energia()
        self.assertNotIn("kwh_ieri", corpo)
        self.assertIn("kwh_oggi", corpo)

    def test_un_ieri_che_vale_zero_si_pubblica(self):
        # La presa c'era tutto il giorno e non ha consumato: zero e' la risposta,
        # e non va confuso con la chiave assente del test qui sopra.
        self._ora("2026-09-11", 10, 0)
        corpo = self._energia()
        self.assertEqual(corpo["kwh_ieri"], 0)

    def test_la_domenica_prima_non_entra_nella_settimana(self):
        self._ora("2026-09-06", 10, 9000)      # domenica
        self._ora("2026-09-07", 10, 1000)      # lunedi'
        corpo = self._energia()
        self.assertAlmostEqual(corpo["kwh_settimana"], 1.0, places=3)

    def test_di_lunedi_la_settimana_vale_quanto_oggi(self):
        self._ora("2026-09-06", 10, 9000)
        self._ora("2026-09-07", 8, 300)
        corpo = self._energia("2026-09-07T17:00:00Z")
        self.assertEqual(corpo["kwh_settimana"], corpo["kwh_oggi"])

    def test_la_settimana_del_cambio_d_ora_e_la_somma_delle_sue_righe(self):
        # Lunedi' 19 - domenica 25 ottobre 2026: 169 ore, perche' il 25 ne ha 25.
        for giorno, ora in [("2026-10-19", 8), ("2026-10-22", 14),
                            ("2026-10-25", 2), ("2026-10-25", 3)]:
            self._ora(giorno, ora, 1000)
        self._ora("2026-10-18", 12, 7000)      # la domenica prima: fuori
        corpo = self._energia("2026-10-25T20:00:00Z")
        self.assertEqual(corpo["settimana"], "2026-10-19")
        self.assertAlmostEqual(corpo["kwh_settimana"], 4.0, places=3)

    def test_l_ora_in_corso_si_somma_a_oggi_ma_non_a_ieri(self):
        self._ora("2026-09-11", 10, 2000)
        self.presa.contatore.aggiorna(epoch("2026-09-12T17:00:00Z"), {"17": 100})
        self.presa.contatore.aggiorna(epoch("2026-09-12T17:10:00Z"), {"17": 400})
        self.presa._rileggi_totali()
        self.presa._ultima_energia = None
        self.presa._pubblica_energia()
        corpo = json.loads(self.pubblicati[-1][1])
        self.assertAlmostEqual(corpo["kwh_oggi"], 0.3, places=3)
        self.assertAlmostEqual(corpo["kwh_ieri"], 2.0, places=3)
        # L'11 e' un venerdi': sta nella stessa settimana del 12, quindi la
        # settimana porta i 2 kWh di ieri **piu'** l'ora in corso. E' il caso che
        # distingue "ieri non prende il parziale" da "ieri e' escluso da tutto".
        self.assertAlmostEqual(corpo["kwh_settimana"], 2.3, places=3)

    def test_senza_archivio_ieri_non_si_inventa(self):
        self.presa.archivio = None
        corpo = self._energia()
        self.assertNotIn("kwh_ieri", corpo)


if __name__ == "__main__":
    unittest.main(verbosity=2)
