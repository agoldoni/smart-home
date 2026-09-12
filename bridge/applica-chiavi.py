#!/usr/bin/env python3
"""Travasa le chiavi locali da chiavi/devices.json dentro dispositivi.yaml.

Il wizard di tinytuya scarica tutto l'account; qui si prendono solo i
dispositivi gia' elencati in dispositivi.yaml, accoppiati per id. Le chiavi
sostituiscono le righe "chiave:" senza toccare il resto del file, commenti
compresi: un yaml.dump rigenererebbe il file perdendoli.
"""

import json
import pathlib
import re
import sys

QUI = pathlib.Path(__file__).parent
SORGENTE = QUI / "chiavi" / "devices.json"
DESTINAZIONE = QUI / "dispositivi.yaml"

if not SORGENTE.exists():
    sys.exit(f"manca {SORGENTE}: esegui prima  bash estrai-chiavi.sh")

chiavi = {}
for d in json.loads(SORGENTE.read_text(encoding="utf-8")):
    dev_id = d.get("id")
    chiave = d.get("key") or d.get("local_key")
    if dev_id and chiave:
        chiavi[dev_id] = (chiave, d.get("name", ""))

testo = DESTINAZIONE.read_text(encoding="utf-8")
righe = testo.splitlines(keepends=True)

aggiornati, mancanti, id_corrente = [], [], None
for i, riga in enumerate(righe):
    trovato_id = re.match(r"^(\s*)id:\s*(\S+)\s*$", riga)
    if trovato_id:
        id_corrente = trovato_id.group(2)
        continue
    trovata_chiave = re.match(r'^(\s*)chiave:\s*("?)([^"\n]*)\2\s*$', riga)
    if trovata_chiave and id_corrente:
        indentazione = trovata_chiave.group(1)
        if id_corrente in chiavi:
            chiave, nome_cloud = chiavi[id_corrente]
            righe[i] = f'{indentazione}chiave: "{chiave}"\n'
            aggiornati.append((id_corrente, nome_cloud))
        else:
            mancanti.append(id_corrente)
        id_corrente = None

DESTINAZIONE.write_text("".join(righe), encoding="utf-8")

for dev_id, nome_cloud in aggiornati:
    print(f"  chiave scritta  {dev_id}  {nome_cloud}")
for dev_id in mancanti:
    print(f"  NESSUNA chiave  {dev_id}  (non e' nell'account?)")
print(f"\n{len(aggiornati)} su {len(aggiornati) + len(mancanti)} dispositivi pronti.")
