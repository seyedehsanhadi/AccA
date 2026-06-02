#!/usr/bin/env python3
"""
Survival test for the detection engine (subsystem C) across the phone/SoC/OS
universe from both research docs, plus adversarial edge devices.

Every device must reach a DEFINED outcome and never crash:
  LOCKED(node,class) | UNVERIFIED(node) | NONE_WORKED | NO_NODE
Rules enforced:
  - a node read failure => class 'unknown' (never 0 => never silently 'bypass')  [S1]
  - a node access that throws => 'unusable', skipped, never crashes discovery
  - a drain-only device (Sony 10 II) is flagged, never silently adopted for HOLD
  - a no-low-level-node device (Motorola/Nothing/OPPO/Realme) => honest NO_NODE
  - bad/empty/garbage getprops => fingerprint still produced, no crash
"""

# native threshold-style nodes (firmware holds the limit)
THRESHOLD = ("charge_stop_level", "batt_full_capacity", "charge_control_end_threshold")

def safe_prop(props, key):
    v = props.get(key)
    return v if isinstance(v, str) and v.strip() else None

def fingerprint(props):
    parts = [safe_prop(props, k) or "?" for k in
             ("ro.product.model", "ro.board.platform", "ro.hardware", "ro.build.id")]
    # stable, no PII, tolerant of missing/garbage
    return "fp:" + "|".join(parts)[:120]

def measure_class(node, sim, reader):
    """Engage node 'off' and classify by measured current. Never raises."""
    try:
        samples = reader(node, sim)        # may raise, return None (unreadable), or numbers
    except Exception:
        return ("unusable", "na")
    if samples is None:
        return ("unknown", "low")          # S1: unreadable -> unknown, NEVER 0
    avg = sum(samples) / len(samples)
    if avg > 50:   return ("charging", "high")   # node wrote but charging continued -> failed
    if avg < -150: return ("drain", "high")      # stops input incl passthrough -> battery drains
    return ("bypass", "med")                      # ~0 mA while online -> bypass/hold

def reader(node, sim):
    eff = sim["effects"].get(node)
    if eff == "throws":     raise OSError("EACCES")
    if eff == "unreadable": return None
    if eff == "charging":   return [800, 820, 790]
    if eff == "drain":      return [-460, -470, -455]
    # bypass/hold/threshold -> ~0
    return [-5, 0, 3]

PRIORITY = {"bypass": 1, "hold": 2, "unknown": 3, "drain": 4}

def detect(sim):
    """The engine. Returns (outcome, node, klass, warnings[]). Never raises."""
    warnings = []
    fp = fingerprint(sim["props"])
    nodes = [n for n in sim["effects"].keys()]
    if not nodes:
        return ("NO_NODE", None, None,
                ["No controllable charging node on this device (UI-only vendor); cannot cap charging."])
    scored = []
    for n in nodes:
        klass, conf = measure_class(n, sim, reader)
        if klass in ("charging", "unusable"):
            continue                       # didn't stop charging / inaccessible
        is_thresh = any(t in n for t in THRESHOLD)
        pri = PRIORITY.get(klass, 3)
        if is_thresh and klass in ("bypass", "hold"):
            pri = 0                         # native threshold preferred
        scored.append((pri, n, klass, conf))
    if not scored:
        return ("NONE_WORKED", None, None,
                ["Candidate nodes exist but none stopped charging; charging cannot be capped here."])
    scored.sort(key=lambda x: x[0])
    pri, node, klass, conf = scored[0]
    if klass == "drain":
        warnings.append("Only a drain-type switch found: in HOLD mode the battery will "
                        "slowly discharge. Use CYCLE mode, or accept the drain.")
        return ("LOCKED", node, "drain", warnings)
    if klass == "unknown":
        warnings.append("Switch stops charging but current was unreadable; class UNVERIFIED. "
                        "Holding as fail-safe; re-verify next cycle.")
        return ("UNVERIFIED", node, "unknown", warnings)
    # status reliability note for Tensor-like devices
    if safe_prop(sim["props"], "ro.hardware") in ("gs101", "gs201", "zuma", "zumapro"):
        warnings.append("status node unreliable on this SoC; trusting current_now, not status.")
    return ("LOCKED", node, klass, warnings)

# ---------- device matrix (from both research docs) + edge cases ----------
def dev(model, platform, hw, build, effects):
    return {"props": {"ro.product.model": model, "ro.board.platform": platform,
                      "ro.hardware": hw, "ro.build.id": build}, "effects": effects}

devices = {
 "Pixel 9 (Tensor)":        dev("Pixel 9","zuma","zumapro","AP4A",
        {"google,charger/charge_stop_level":"bypass"}),                       # threshold, slight drain ok
 "Pixel 4 (older)":         dev("Pixel 4","msmnile","flame","SP2A",
        {"battery/charging_enabled":"bypass"}),
 "Samsung S22 (QCOM)":      dev("SM-S901","taro","qcom","TP1A",
        {"battery/batt_full_capacity":"bypass","battery/siop_level":"charging"}),# native PD-PPS bypass
 "Samsung Exynos":          dev("SM-G991","exynos2100","exynos","TP1A",
        {"battery/batt_slate_mode":"bypass"}),
 "Xiaomi (firmware OK)":    dev("Mi 9","msmnile","qcom","RKQ1",
        {"battery/input_suspend":"bypass"}),
 "Xiaomi (firmware broke)": dev("Mi 9","msmnile","qcom","V14",
        {"battery/input_suspend":"charging"}),                                 # node exists, no effect
 "OnePlus 8T":              dev("KB2000","kona","qcom","RP1A",
        {"oplus/battery/mmi_charging_enable":"bypass"}),
 "Sony Xperia 10 II":       dev("XQ-AU52","sm6125","qcom","59.1",
        {"battery/charging_enabled":"drain"}),                                 # DANGER: kills passthrough
 "Sony Xperia X (older)":   dev("F5121","msm8956","qcom","34.4",
        {"battery/battery_charging_enabled":"bypass"}),
 "MediaTek device":         dev("Infinix","mt6789","mt6789","TP1A",
        {"/proc/mtk_battery_cmd/current_cmd":"bypass"}),
 "Motorola (no node)":      dev("moto g","sdm632","qcom","RPMS", {}),          # UI-only -> NO_NODE
 "Nothing Phone (no node)": dev("A063","taro","qcom","UKQ1", {}),
 "Realme (no node)":        dev("RMX","mt6877","mt6877","RP1A", {}),
 "Fairphone 4":             dev("FP4","sm7225","qcom","SKQ1",
        {"battery/charging_enabled":"bypass","battery/input_suspend":"bypass"}),# multi-node, rank
 "Unreadable-current dev":  dev("Weird","unknown","unknown","X",
        {"battery/charging_enabled":"unreadable"}),                            # S1: unknown not 0
 "Throwing-node dev":       dev("Weird2","unknown","unknown","X",
        {"battery/charging_enabled":"throws"}),
 "Empty getprops":          {"props":{}, "effects":{"battery/input_suspend":"bypass"}},
 "Garbage getprops":        {"props":{"ro.product.model":"\U0001F4F1<x>\x00","ro.hardware":12345},
                             "effects":{"battery/input_suspend":"bypass"}},
 "Nothing works at all":    dev("Brick","x","x","X",
        {"a/charging_enabled":"charging","b/input_suspend":"charging"}),
}

crashes = 0
undefined = 0
silent_drain = 0     # drain adopted with no warning
rows = []
for name, sim in devices.items():
    try:
        outcome, node, klass, warns = detect(sim)
        if outcome not in ("LOCKED","UNVERIFIED","NONE_WORKED","NO_NODE"):
            undefined += 1
        if klass == "drain" and not warns:
            silent_drain += 1
        warn = (" | ".join(warns))[:70] if warns else "-"
        rows.append((name, outcome, (node or "-")[:34], klass or "-", warn))
    except Exception as e:
        crashes += 1
        rows.append((name, "CRASH("+type(e).__name__+")", "-", "-", str(e)[:40]))

w = [max(len(str(r[i])) for r in rows) for i in range(5)]
hdr = ["device","outcome","locked node","class","warning"]
print("  ".join(h.ljust(w[i]) for i,h in enumerate(hdr)))
print("-"*(sum(w)+12))
for r in rows:
    print("  ".join(str(r[i]).ljust(w[i]) for i in range(5)))

print("\n==== SUMMARY ====")
print(f"devices tested            : {len(devices)}")
print(f"engine crashes            : {crashes}/{len(devices)}")
print(f"undefined outcomes        : {undefined}")
print(f"silently-adopted drains   : {silent_drain}")
ok = crashes==0 and undefined==0 and silent_drain==0
print(f"\nRESULT: {'PASS - every phone reaches a defined, safe outcome, no crash' if ok else 'FAIL'}")
