#!/usr/bin/env python3
"""
Crash-safety model + fuzz test for the AccA state-export parser (subsystem A/B).

It models the Kotlin contract the real parser MUST follow:
  - parse the whole JSON inside try/catch  -> any failure => parseOk=False, all UNKNOWN
  - every field read through a safe getter  -> missing / wrong-type / null => UNKNOWN
  - a number that can't be read is UNKNOWN, never 0  (rule S1: absence != evidence)

We compare a NAIVE parser (what crashes today) against the GUARDED one.
Goal: GUARDED must throw 0 times across every nasty input, and must never
turn a failed/absent read into a real-looking value.
"""

import json

UNKNOWN = "unknown"

# ---- the GUARDED reader (models the Kotlin safe-accessor contract) ----

def _safe_int(v):
    # null, missing, string, float-as-text, bool -> UNKNOWN.  NEVER 0.  (rule S1)
    if isinstance(v, bool):           # bool is an int subclass in py; reject it
        return UNKNOWN
    if isinstance(v, int):
        return v
    return UNKNOWN

def _safe_str(v):
    return v if isinstance(v, str) and v != "" else UNKNOWN

def _get(d, *path):
    cur = d
    for k in path:
        if not isinstance(cur, dict) or k not in cur:
            return None
        cur = cur[k]
    return cur

def default_state():
    return {
        "parseOk": False,
        "capacityPct": UNKNOWN,
        "current_mA": UNKNOWN,
        "status": UNKNOWN,
        "switch_locked": UNKNOWN,
        "switch_mode": UNKNOWN,
        "warnings": [],
    }

def guarded_read(raw):
    state = default_state()
    try:
        obj = json.loads(raw)
    except Exception:
        return state                 # torn/garbage -> diagnostics unavailable, all UNKNOWN
    if not isinstance(obj, dict):
        return state                 # array/scalar top-level -> unavailable
    state["parseOk"] = True
    state["capacityPct"]  = _safe_int(_get(obj, "battery", "capacityPct"))
    state["current_mA"]   = _safe_int(_get(obj, "battery", "current_mA"))
    state["status"]       = _safe_str(_get(obj, "battery", "status"))
    state["switch_locked"]= _safe_str(_get(obj, "switch", "locked"))
    state["switch_mode"]  = _safe_str(_get(obj, "switch", "mode"))
    w = _get(obj, "warnings")
    state["warnings"]     = w if isinstance(w, list) else []
    return state

def naive_read(raw):
    # what a strict parser (Gson/Moshi) does: fromJson then assume fields present.
    obj = json.loads(raw)                       # throws on truncated/garbage
    return {
        "capacityPct": obj["battery"]["capacityPct"],   # KeyError/TypeError on missing
        "current_mA":  int(obj["battery"]["current_mA"]),# throws on null/str
    }

# ---- the fuzz battery: every "can't read part of the file" shape ----

GOOD = '{"schemaVersion":1,"ts":1000,"battery":{"capacityPct":73,"current_mA":-470,"status":"Not charging"},"switch":{"locked":"google,charger/charge_stop_level 100 pcap","mode":"hold"},"warnings":[]}'

cases = {
    "empty":              "",
    "whitespace":         "   \n\t ",
    "literal null":       "null",
    "open brace only":    "{",
    "torn mid-object":    '{"battery":{"capacityPct":73,"current_mA":-4',   # write interrupted
    "torn mid-string":    '{"switch":{"locked":"google,char',
    "garbage":            "@#$%^&*() not json",
    "binary junk":        "\x00\x01\x02\x03\xff",
    "array top-level":    "[1,2,3]",
    "scalar top-level":   "42",
    "missing battery":    '{"switch":{"locked":"x","mode":"hold"}}',
    "missing current":    '{"battery":{"capacityPct":73,"status":"Idle"}}',
    "current is null":    '{"battery":{"capacityPct":73,"current_mA":null}}',
    "current is string":  '{"battery":{"capacityPct":73,"current_mA":"minus470"}}',
    "capacity is bool":   '{"battery":{"capacityPct":true,"current_mA":0}}',
    "wrong nesting":      '{"battery":[1,2,3]}',
    "huge truncated":     '{"battery":{"x":"' + ("a" * 200000),
    "extra unknown keys": '{"battery":{"capacityPct":73,"current_mA":0},"future":{"x":1},"deep":{"a":{"b":{"c":1}}}}',
    "good (control)":     GOOD,
}

naive_crashes = 0
guarded_crashes = 0
s1_violations = 0   # a failed/absent current read that became a real number (0)
rows = []

for name, raw in cases.items():
    # naive
    try:
        naive_read(raw); n = "ok"
    except Exception as e:
        n = "CRASH(" + type(e).__name__ + ")"; naive_crashes += 1
    # guarded
    try:
        g = guarded_read(raw)
        gres = f"parseOk={g['parseOk']} cap={g['capacityPct']} mA={g['current_mA']}"
        # S1 check: if current wasn't a real int in the input, it must be UNKNOWN, not 0
        cur_in = None
        try: cur_in = json.loads(raw).get("battery", {}).get("current_mA")
        except Exception: cur_in = None
        if not isinstance(cur_in, int) or isinstance(cur_in, bool):
            if g["current_mA"] != UNKNOWN:
                s1_violations += 1
                gres += "  <-- S1 VIOLATION (fabricated a number)"
    except Exception as e:
        gres = "CRASH(" + type(e).__name__ + ")"; guarded_crashes += 1
    rows.append((name, n, gres))

w1 = max(len(r[0]) for r in rows)
w2 = max(len(r[1]) for r in rows)
print(f"{'case'.ljust(w1)}  {'NAIVE'.ljust(w2)}  GUARDED")
print("-" * (w1 + w2 + 40))
for name, n, g in rows:
    print(f"{name.ljust(w1)}  {n.ljust(w2)}  {g}")

print("\n==== SUMMARY ====")
print(f"inputs tested            : {len(cases)}")
print(f"NAIVE parser crashes     : {naive_crashes}/{len(cases)}")
print(f"GUARDED parser crashes   : {guarded_crashes}/{len(cases)}")
print(f"S1 violations (0 != unk) : {s1_violations}")
ok = (guarded_crashes == 0 and s1_violations == 0)
print(f"\nRESULT: {'PASS - uncrashable, no fabricated values' if ok else 'FAIL'}")
