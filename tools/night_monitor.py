"""Vigilancia nocturna del móvil por USB (adb) mientras corre una secuencia.

Registra, para entender quién se come la memoria y quién mata la app:
  * cada INTERVAL s: /proc/meminfo, «dumpsys meminfo» (RAM libre/usada y el
    top de procesos por PSS, con el juego y la app destacados), temperatura y
    batería → snapshots.log, y una fila resumen en memory.csv;
  * en continuo: logcat filtrado (lmkd, kills de ActivityManager/HyperOS,
    am_kill/am_low_memory/am_proc_died, ciclos de vida de la app y del juego)
    → kills.log.
Evita que el PC se suspenda mientras corre. Ctrl+C o crear el fichero STOP
en la carpeta de salida para terminar.

    python tools/night_monitor.py [carpeta_salida] [intervalo_s]
"""
import csv
import ctypes
import datetime as dt
import os
import re
import subprocess
import sys
import threading
import time

ADB = r"C:\Program Files\Netease\MuMuPlayer\nx_main\adb.exe"
PKG = "io.github.emiliounitydev.autoclicker.dev"
GAME = "com.farlightgames.pgame.gp"
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.environ["LOCALAPPDATA"], "autoclicker-night", dt.datetime.now().strftime("%Y%m%d-%H%M"))
INTERVAL = int(sys.argv[2]) if len(sys.argv) > 2 else 120
os.makedirs(OUT, exist_ok=True)
STOP = os.path.join(OUT, "STOP")

LOGCAT_RE = re.compile(
    r"lowmemorykiller|lmkd|am_kill|am_low_memory|am_proc_died|am_proc_start|"
    r"Killing |kill\(|ProcessManager|clean_up_mem|SwipeUpClean|PowerKeeper|"
    r"OomAdjuster|MemoryPressure|psi|onTrimMemory|AutoClicker|autoclicker|"
    r"pgame|Watchdog|thermal|Thermal", re.I)


def now():
    return dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def adb(*args, timeout=60):
    try:
        r = subprocess.run([ADB, *args], capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=timeout)
        return r.stdout
    except Exception as e:  # noqa: BLE001
        return f"<adb error: {e}>"


def keep_awake():
    # ES_CONTINUOUS | ES_SYSTEM_REQUIRED
    ctypes.windll.kernel32.SetThreadExecutionState(0x80000000 | 0x00000001)


def logcat_thread():
    path = os.path.join(OUT, "kills.log")
    while not os.path.exists(STOP):
        with open(path, "a", encoding="utf-8") as f:
            f.write(f"--- {now()} logcat conectado\n")
            f.flush()
            p = subprocess.Popen([ADB, "logcat", "-v", "threadtime", "-b", "main,system,events,crash", "-T", "1"],
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                 encoding="utf-8", errors="replace")
            for line in p.stdout:
                if LOGCAT_RE.search(line):
                    f.write(line)
                    f.flush()
                if os.path.exists(STOP):
                    p.kill()
                    break
            f.write(f"--- {now()} logcat cortado\n")
        time.sleep(10)


def pss_of(meminfo, name):
    total = 0
    for m in re.finditer(r"([\d,]+)K: (\S+) \(pid", meminfo):
        if m.group(2).startswith(name):
            total += int(m.group(1).replace(",", ""))
    return total // 1024


def field(text, key):
    m = re.search(rf"^{key}:\s+(\d+)", text, re.M)
    return int(m.group(1)) // 1024 if m else ""


def snapshot(writer):
    t = now()
    pm = adb("shell", "cat", "/proc/meminfo")
    dm = adb("shell", "dumpsys", "meminfo")
    temp = adb("shell", "dumpsys", "battery")
    free = re.search(r"Free RAM:\s+([\d,]+)K", dm)
    used = re.search(r"Used RAM:\s+([\d,]+)K", dm)
    swap = re.search(r"ZRAM:.*?([\d,]+)K physical used for ([\d,]+)K in swap \(([\d,]+)K total swap\)", dm)
    level = re.search(r"level: (\d+)", temp)
    tmp = re.search(r"temperature: (\d+)", temp)
    toint = lambda m, g=1: int(m.group(g).replace(",", "")) // 1024 if m else ""  # noqa: E731
    row = [t, field(pm, "MemAvailable"), field(pm, "MemFree"), field(pm, "Cached"),
           field(pm, "SwapFree"), toint(free), toint(used), toint(swap, 2) if swap else "",
           pss_of(dm, GAME), pss_of(dm, PKG + ":gestures"), pss_of(dm, PKG) - pss_of(dm, PKG + ":gestures"),
           level.group(1) if level else "", int(tmp.group(1)) / 10 if tmp else ""]
    writer.writerow(row)
    top = re.search(r"Total PSS by process:\n(.*?)\n\n", dm, re.S)
    with open(os.path.join(OUT, "snapshots.log"), "a", encoding="utf-8") as f:
        f.write(f"===== {t}\n")
        f.write("\n".join(l for l in pm.splitlines()
                          if l.split(":")[0] in ("MemTotal", "MemFree", "MemAvailable", "Cached", "SwapTotal",
                                                 "SwapFree", "Shmem", "AnonPages")) + "\n")
        if top:
            f.write("\n".join(top.group(1).splitlines()[:25]) + "\n")
        for k in ("Total RAM", "Free RAM", "Used RAM", "Lost RAM", "ZRAM"):
            m = re.search(rf"^\s*{k}:.*$", dm, re.M)
            if m:
                f.write(m.group(0).strip() + "\n")
    return row


def main():
    print("salida:", OUT, flush=True)
    th = threading.Thread(target=logcat_thread, daemon=True)
    th.start()
    new = not os.path.exists(os.path.join(OUT, "memory.csv"))
    with open(os.path.join(OUT, "memory.csv"), "a", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        if new:
            w.writerow(["hora", "MemAvailable_MB", "MemFree_MB", "Cached_MB", "SwapFree_MB", "FreeRAM_MB",
                        "UsedRAM_MB", "swap_usado_MB", "juego_PSS_MB", "gestures_PSS_MB", "app_PSS_MB",
                        "bateria_%", "temp_C"])
        while not os.path.exists(STOP):
            keep_awake()
            if "device" not in adb("get-state", timeout=10):
                with open(os.path.join(OUT, "snapshots.log"), "a", encoding="utf-8") as f:
                    f.write(f"===== {now()} móvil NO conectado\n")
            else:
                print(snapshot(w), flush=True)
                fh.flush()
            for _ in range(INTERVAL):
                if os.path.exists(STOP):
                    break
                time.sleep(1)
    print("parado", flush=True)


if __name__ == "__main__":
    main()
