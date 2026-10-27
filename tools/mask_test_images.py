"""Prepara las capturas de prueba (app/src/test/resources/raid/) a partir de las
originales: deja visibles solo las regiones que leen los detectores de
raid_join.json y tapa nombres/mensajes de jugadores (el repo es público).

    python tools/mask_test_images.py <carpeta_capturas_originales>
"""
import json
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
D = os.path.join(HERE, "..", "app", "src", "test", "resources", "raid")
SRC = {
    "chat_join.png": "MuMu-20261027-165223-685_eddf76.png",
    "chat_pending.png": "MuMu-20261027-165236-246_3db8dd.png",
    "overview_join.png": "MuMu-20261027-165226-602_c3a7f0.png",
    "overview_pending.png": "MuMu-20261027-165228-836_bf1a7c.png",
    "map.png": "MuMu-20261027-165232-687_2b78d9.png",
    "summary.png": "summary.png",
}
# Franjas con nombres/mensajes que no afectan a las pantallas detectadas.
BLACK = {
    "chat_join.png": [(90, 470, 600, 540), (90, 870, 600, 945), (0, 330, 110, 1500)],
    "chat_pending.png": [(90, 250, 330, 1300), (0, 330, 110, 1500)],
    "overview_join.png": [(0, 880, 900, 1460), (120, 250, 740, 600)],
    "overview_pending.png": [(0, 880, 900, 1460), (120, 250, 740, 600)],
    "map.png": [(0, 0, 900, 240), (200, 300, 750, 1200)],
    "summary.png": [(110, 260, 790, 1050), (370, 1060, 800, 1230)],
}


def regions(seq):
    out = []

    def walk(steps):
        for s in steps:
            if "condition" in s:
                out.append(s["condition"]["region"])
            for k in ("also", "unless"):
                out.extend(c["region"] for c in s.get(k, []) or [])
            for k in ("steps", "then_steps", "else_steps"):
                walk(s.get(k, []) or [])
    walk(seq["steps"])
    for g in seq.get("guards", []):
        out.extend(c["region"] for c in g["conditions"])
    return out


def main():
    src_dir = sys.argv[1]
    seq = json.load(open(os.path.join(D, "raid_join.json"), encoding="utf-8"))
    mask = np.zeros((1600, 900), bool)
    for x1, y1, x2, y2 in regions(seq):
        mask[y1:y2, x1:x2] = True
    for out, fn in SRC.items():
        a = np.asarray(Image.open(os.path.join(src_dir, fn)).convert("RGB")).copy()
        a[~mask] = 0
        for x1, y1, x2, y2 in BLACK[out]:
            a[y1:y2, x1:x2] = 0
        Image.fromarray(a).save(os.path.join(D, out), optimize=True)
        print(out)


if __name__ == "__main__":
    main()
