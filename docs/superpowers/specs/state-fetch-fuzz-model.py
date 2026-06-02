#!/usr/bin/env python3
"""
Crash-safety + warning model for the AccA state FETCH layer (daemon side).

Distinct from the parser test: here the daemon itself may be missing, dead,
root-denied, hung, on an old ACC without `--state`, or returning junk.
Contract:
  - the shell call is wrapped (libsu can throw)         -> never propagates
  - a hung daemon hits a timeout                         -> Unreachable, not a frozen UI
  - every non-OK outcome yields a DISTINCT, actionable warning
  - non-OK never shows a panel (no blank/zeroed "all good"); OK/STALE show data,
    STALE also warns. Nothing is ever rendered as fresh-when-it-isn't.
"""

import json

UNKNOWN = "unknown"

# ---------- guarded JSON reader (same contract as the parser test) ----------
def _safe_int(v):
    if isinstance(v, bool): return UNKNOWN
    return v if isinstance(v, int) else UNKNOWN
def _safe_str(v):
    return v if isinstance(v, str) and v else UNKNOWN
def _get(d, *p):
    cur = d
    for k in p:
        if not isinstance(cur, dict) or k not in cur: return None
        cur = cur[k]
    return cur
def guarded_read(raw):
    st = {"parseOk": False, "ts": UNKNOWN, "capacityPct": UNKNOWN, "current_mA": UNKNOWN}
    try:
        obj = json.loads(raw)
    except Exception:
        return st
    if not isinstance(obj, dict): return st
    st["parseOk"] = True
    st["ts"]          = _safe_int(_get(obj, "ts"))
    st["capacityPct"] = _safe_int(_get(obj, "battery", "capacityPct"))
    st["current_mA"]  = _safe_int(_get(obj, "battery", "current_mA"))
    return st

# ---------- simulated libsu shell outcome ----------
class Shell:
    def __init__(self, out="", code=0, throws=None, hang=False):
        self.out, self.code, self.throws, self.hang = out, code, throws, hang

def run_with_timeout(shell, timeout_s=3):
    # models withTimeout(timeout) on an IO dispatcher
    if shell.throws is not None: raise shell.throws
    if shell.hang: raise TimeoutError("timed out")
    return shell

# ---------- the FETCH contract under test ----------
def fetch_state(shell, now, max_loop_delay=9):
    try:
        r = run_with_timeout(shell)
    except TimeoutError:
        return ("UNREACHABLE", None, "ACC not responding (timed out) — is the daemon running?")
    except Exception as e:
        return ("UNREACHABLE", None, f"Cannot reach ACC ({type(e).__name__}) — is root granted?")

    out = (r.out or "").strip()
    low = out.lower()

    if "permission denied" in low or "not allowed" in low:
        return ("NO_ROOT", None, "Root permission denied — grant root access to AccA")
    if "not found" in low or "unknown option" in low or "inaccessible" in low:
        return ("UNSUPPORTED", None, "This ACC has no --state; showing limited info. Update ACC.")
    if out == "":
        return ("UNREACHABLE", None, "ACC returned no data — daemon not running?")
    if r.code != 0:
        return ("UNREACHABLE", None, "ACC reported an error — daemon not running?")

    g = guarded_read(out)
    if not g["parseOk"]:
        return ("UNREACHABLE", None, "ACC sent unreadable data — diagnostics unavailable")
    ts = g["ts"]
    if isinstance(ts, int) and (now - ts) > 3 * max_loop_delay:
        return ("STALE", g, f"ACC last updated {now - ts}s ago — daemon may be stopped")
    return ("OK", g, None)

# ---------- render: prove no blank panel is shown as 'fresh' ----------
def render(result):
    status, payload, warning = result
    show_panel = status in ("OK", "STALE")            # STALE shows last data, dimmed + warned
    banner = None if status == "OK" else ("WARN", warning)
    return {"status": status, "panelShown": show_panel, "banner": banner}

# ---------- fuzz battery of daemon failure modes ----------
NOW = 100000
FRESH = json.dumps({"ts": NOW - 2, "battery": {"capacityPct": 73, "current_mA": -470}})
STALEJ = json.dumps({"ts": NOW - 600, "battery": {"capacityPct": 73, "current_mA": -470}})

cases = {
    "libsu throws":          Shell(throws=RuntimeError("shell died")),
    "daemon hangs (timeout)":Shell(hang=True),
    "root denied":           Shell(out="su: permission denied", code=1),
    "daemon down (empty,err)":Shell(out="", code=1),
    "acca not installed":    Shell(out="/system/bin/sh: acca: not found", code=127),
    "old ACC (no --state)":  Shell(out="--state: unknown option", code=2),
    "empty output ok-exit":  Shell(out="", code=0),
    "garbage output":        Shell(out="@@@ not json @@@", code=0),
    "torn json":             Shell(out='{"battery":{"capacityPct":7', code=0),
    "stale but valid":       Shell(out=STALEJ, code=0),
    "fresh valid (control)": Shell(out=FRESH, code=0),
}

crashes = 0
silent_blanks = 0     # a non-OK that still showed a panel with no warning
missing_warn = 0      # a non-OK with no warning text
rows = []
for name, shell in cases.items():
    try:
        res = fetch_state(shell, NOW)
        view = render(res)
        status, _, warn = res
        if status != "OK":
            if view["banner"] is None: missing_warn += 1
            if view["panelShown"] and status != "STALE": silent_blanks += 1
        warn_txt = (warn or "").strip()
        rows.append((name, status, "yes" if view["panelShown"] else "no",
                     warn_txt if warn_txt else "-"))
    except Exception as e:
        crashes += 1
        rows.append((name, "CRASH(" + type(e).__name__ + ")", "-", "-"))

w1 = max(len(r[0]) for r in rows); w2 = max(len(r[1]) for r in rows)
print(f"{'failure mode'.ljust(w1)}  {'result'.ljust(w2)}  panel  warning")
print("-" * (w1 + w2 + 60))
for n, s, p, w in rows:
    print(f"{n.ljust(w1)}  {s.ljust(w2)}  {p.ljust(5)}  {w}")

print("\n==== SUMMARY ====")
print(f"failure modes tested      : {len(cases)}")
print(f"AccA crashes              : {crashes}/{len(cases)}")
print(f"non-OK with NO warning    : {missing_warn}")
print(f"silent blank panels shown : {silent_blanks}")
ok = (crashes == 0 and missing_warn == 0 and silent_blanks == 0)
print(f"\nRESULT: {'PASS - never crashes, every failure warns, no blank-as-fresh' if ok else 'FAIL'}")
