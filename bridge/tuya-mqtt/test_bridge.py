"""Prove sulla logica che e' facile sbagliare in silenzio.

Il comportamento coperto qui e' quello che, sbagliato, non da' errori: fa solo
commutare un relay piu' volte del dovuto, e te ne accorgi stando accanto alla
presa. Non si puo' verificare contro un dispositivo vero, perche' richiede una
presa irraggiungibile su comando.
"""

import json
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


if __name__ == "__main__":
    unittest.main(verbosity=2)
