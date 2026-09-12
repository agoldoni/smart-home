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
    kw.setdefault("dp", "17")
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


if __name__ == "__main__":
    unittest.main(verbosity=2)
