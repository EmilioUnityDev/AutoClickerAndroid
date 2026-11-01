"""Prepara las capturas de prueba (app/src/test/resources/raid/) a partir de las
originales: deja visibles solo las regiones que leen los detectores de
raid_join.json y tapa nombres/mensajes de jugadores (el repo es público).

    python tools/mask_test_images.py pc <carpeta_capturas_MuMu_900x1600>
    python tools/mask_test_images.py phone <carpeta_capturas_movil_1280x2772>

Las del móvil se enmascaran con las regiones ya mapeadas (escala por el ancho +
`anchor_y` de cada condición, igual que Mapping.kt).
"""
import gzip
import json
import os
import struct
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
D = os.path.join(HERE, "..", "app", "src", "test", "resources", "raid")
PC_SRC = {
    "chat_join.png": "MuMu-20261027-165223-685_eddf76.png",
    "chat_pending.png": "MuMu-20261027-165236-246_3db8dd.png",
    "overview_join.png": "MuMu-20261027-165226-602_c3a7f0.png",
    "overview_pending.png": "MuMu-20261027-165228-836_bf1a7c.png",
    "map.png": "MuMu-20261027-165232-687_2b78d9.png",
    "summary.png": "summary.png",
}
# Franjas con nombres/mensajes que no afectan a las pantallas detectadas.
PC_BLACK = {
    "chat_join.png": [(90, 470, 600, 540), (90, 870, 600, 945), (0, 330, 110, 1500)],
    "chat_pending.png": [(90, 250, 330, 1300), (0, 330, 110, 1500)],
    "overview_join.png": [(0, 880, 900, 1460), (120, 250, 740, 600)],
    "overview_pending.png": [(0, 880, 900, 1460), (120, 250, 740, 600)],
    "map.png": [(0, 0, 900, 240), (200, 300, 750, 1200)],
    "summary.png": [(110, 260, 790, 1050), (370, 1060, 800, 1230)],
}
# Redmi Note 15 Pro (1280×2772), capturas del 2026-11-01.
PHONE_SRC = {
    "phone_chat_join": "Screenshot_2026-11-01-01-20-00-711_com.whatsapp.jpg",
    "phone_chat_pending": "Screenshot_2026-11-01-01-18-21-873_com.farlightgames.pgame.gp.jpg",
    "phone_overview_join": "Screenshot_2026-11-01-01-20-04-827_com.whatsapp.jpg",
    "phone_map": "Screenshot_2026-11-01-01-17-44-473_com.farlightgames.pgame.gp.jpg",
    "phone_summary": "Screenshot_2026-11-01-01-20-18-116_com.whatsapp.jpg",
    "phone_rally_list": "Screenshot_2026-11-01-01-18-26-690_com.farlightgames.pgame.gp.jpg",
}


def conditions(seq):
    out = []

    def walk(steps):
        for s in steps:
            if "condition" in s:
                out.append(s["condition"])
            for k in ("also", "unless"):
                out.extend(s.get(k, []) or [])
            for k in ("steps", "then_steps", "else_steps"):
                walk(s.get(k, []) or [])
    walk(seq["steps"])
    for g in seq.get("guards", []):
        out.extend(g["conditions"])
    return out


def mapped(c, w, h, src=(900, 1600)):
    """Región de la condición en una pantalla w×h (como Mapping.Mode.ANCHOR)."""
    x1, y1, x2, y2 = c["region"]
    s = w / src[0]
    extra = h - src[1] * s
    a = c.get("anchor_y")

    def y(v):
        if a is not None:
            return v * s + a * extra
        return v * s if v < src[1] / 2 else h - (src[1] - v) * s
    return int(x1 * s), int(y(y1)), int(np.ceil(x2 * s)), int(np.ceil(y(y2)))


def save(a, out):
    # Formato de prueba: cabecera (ancho, alto) big-endian + RGB, gzip.
    # (javax.imageio no está disponible en los tests unitarios de Android.)
    with gzip.open(os.path.join(D, out + ".rgb.gz"), "wb", compresslevel=9) as g:
        g.write(struct.pack(">II", a.shape[1], a.shape[0]))
        g.write(a.astype("uint8").tobytes())
    print(out)


def main():
    kind, src_dir = sys.argv[1], sys.argv[2]
    seq = json.load(open(os.path.join(D, "raid_join.json"), encoding="utf-8"))
    conds = conditions(seq)
    if kind == "pc":
        mask = np.zeros((1600, 900), bool)
        for c in conds:
            x1, y1, x2, y2 = c["region"]
            mask[y1:y2, x1:x2] = True
        for out, fn in PC_SRC.items():
            a = np.asarray(Image.open(os.path.join(src_dir, fn)).convert("RGB")).copy()
            a[~mask] = 0
            for x1, y1, x2, y2 in PC_BLACK[out]:
                a[y1:y2, x1:x2] = 0
            save(a, out.replace(".png", ""))
        return
    for out, fn in PHONE_SRC.items():
        a = np.asarray(Image.open(os.path.join(src_dir, fn)).convert("RGB")).copy()
        h, w = a.shape[:2]
        keep = np.zeros((h, w), bool)
        for c in conds:
            x1, y1, x2, y2 = mapped(c, w, h)
            x1, y1 = max(0, x1), max(0, y1)
            m = color_mask(a[y1:y2, x1:x2], c)
            if m.size and m.mean() >= c["min_fraction"]:
                # Solo las regiones que se cumplen en esta pantalla, y de ellas
                # solo los píxeles del color buscado (fuera nombres y textos).
                keep[y1:y2, x1:x2] |= m
        a[~keep] = FILL
        save(a, out)


# Relleno que no coincide con ningún color de los detectores (el negro sí
# coincidiría con «#080808»).
FILL = (255, 0, 0)


def color_mask(sub, c):
    m = np.zeros(sub.shape[:2], bool)
    for h in c["color"].split(","):
        h = h.strip().lstrip("#")
        rgb = np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)])
        m |= (np.abs(sub.astype(int) - rgb) <= c["tolerance"]).all(-1)
    return m

if __name__ == "__main__":
    main()
