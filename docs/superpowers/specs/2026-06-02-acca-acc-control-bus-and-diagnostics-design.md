# ACC ↔ AccA Control Bus & Diagnostics — Design (Subsystems A + B)

- **Date:** 2026-06-02
- **Status:** Approved design — pending written-spec review
- **Scope:** Subsystem **A** (control bus) + Subsystem **B** (diagnostics & exportable report).
- **Out of scope (separate later cycles):** **C** (per-phone detection/classification engine) and **D** (band enforcement + the two behaviors). Referenced here only where they touch the shared schema, which is designed to be forward-compatible with them.

---

## 1. Problem

Today AccA changes settings with **fire-and-forget** shell calls (`acca -s key=value`) and assumes success. Observed symptom (user, via diagnostics): only charging range, `prioritize_batt_idle_mode`, and `allow_idle_above_pcap` appear to take effect; other parameters seem not to. With fire-and-forget there is no way to tell *write-never-happened* from *write-didn't-apply* from *diagnostics-never-surfaced-it* — three very different root causes that all look identical.

Root cause is structural: **there is no read-back.** AccA never confirms what ACC actually holds after a change, and the diagnostics don't expose the full actual config or the daemon's measured hardware facts.

## 2. Goals / Non-goals

**Goals**
- AccA can set **every** ACC config parameter, and **every** write is verified by read-back (✓/✗ per field). A silent non-apply becomes a visible ✗.
- A single fast-refreshing diagnostics view shows ACC's **actual** state, not AccA's hopes.
- A one-tap **offline** diagnostic report ("super nice log") the user can hand to anyone: device fingerprint + what worked + what didn't + provenance. No network, no phone-home.

**Non-goals (this cycle)**
- The detection/classification engine itself (C) — A+B only define and *carry* the schema slots C will fill.
- Band enforcement and the cycle/hold behaviors (D).
- Shipping. Test build only.

## 3. Core idea — one shared state export

A and B are the **same mechanism**. ACC owns `config.txt` and re-reads it every daemon loop. We add the missing half: ACC **publishes a machine-readable state export** every loop and after every `-s` apply. That export is simultaneously:

- the **read-back** that confirms a write (A),
- the **diagnostics feed** (B),
- the **exportable report** source (B).

One source of truth, closed-loop by construction. No second code path to drift.

**Transport:** ACC writes the export to a file in its tmp dir and exposes it via a new applet command `acca --state` (prints the JSON). AccA already runs a root shell (libsu) and calls `acca -s --print`; it gains `acca --state` for the structured read. No new IPC surface.

## 4. The state export (schema)

`schemaVersion` is bumped on any breaking change; AccA tolerates unknown fields (forward-compat) and treats missing fields as `unknown`.

```
{
  "schemaVersion": 1,
  "ts": <epoch seconds>,
  "acc": { "version": "...", "versionCode": 202505192 },
  "device": {
    "model": "...", "manufacturer": "...", "soc": "...",
    "hardware": "...", "androidRelease": "...", "buildId": "...",
    "fingerprint": "<short stable hash of the above>"
  },
  "battery": { "capacityPct": 73, "current_mA": -470, "voltage_mV": 4012,
               "temp_C": 31.0, "status": "Not charging", "health": "Good" },
  "sensing": {                      // populated progressively; C deepens it
    "currentUnits": "uA|mA|unknown",
    "polarity": "normal|inverted|unknown",
    "statusTrust": "trusted|distrusted|unknown",
    "confidence": "high|medium|low"
  },
  "switch": {
    "locked": "<path on off ...>|none",
    "mode": "bypass|hold|drain|limit|onoff|unknown",
    "autoMode": true,
    "lastVerified": <epoch|null>,
    "candidates": [
      { "path": "...", "exists": true, "tested": true,
        "result": "worked|drain|failed|untested",
        "measuredClass": "bypass|hold|drain|charging|unknown",
        "note": "..." }
    ]
  },
  "config": {                       // every parameter, as ACC actually holds it
    "actual": { "capacity": [5,101,70,75,false], "temperature": [45,50,40,55],
                "chargingSwitch": "...", "allowIdleAbovePcap": true, ... },
    "applied": {                    // per-field write confirmation (A)
      "<field>": { "requested": "...", "actual": "...",
                   "applied": "yes|no|unknown", "ts": <epoch> }
    }
  },
  "band": { "pauseCap": 75, "resumeCap": 70, "mode": "cycle|hold" },
  "warnings": [ { "code": "stuck|drain|breach|...", "msg": "...", "ts": <epoch> } ]
}
```

For A+B, ACC populates everything it already knows (device, battery, actual config, current switch, warnings). `sensing` and the richer `switch.candidates[].measuredClass` are filled by **C**; until then they report `unknown` honestly — never a guess.

## 5. Subsystem A — Control bus (closed loop)

- **Source of truth unchanged:** ACC owns `config.txt`; writes still go through the existing merge (`acca -s key=value`), so no clobber risk.
- **Write flow:** AccA sets a value → ACC merges + applies → ACC records `config.applied[field] = {requested, actual: <re-read>, applied}` and refreshes the state export.
- **Confirm:** AccA reads `acca --state`, diffs `requested` vs `actual`, shows **✓** (match), **✗** (mismatch → loud), or **⏳/unknown** (not yet observed). No field is ever assumed applied.
- **Expose all params:** AccA's editor surfaces every parameter from §4 `config.actual` (not just range/pbim/aiapc), each with its confirmation state. This is the concrete fix for the reported #1 bug; whichever of the three root causes it was, read-back makes it visible and the full-param UI makes it controllable.

## 6. Subsystem B — Diagnostics + exportable report

- **Live panel** (interval ~1–3 s, cheap sysfs reads via the state export — no probing during normal run): level, **signed** current, status, locked switch + measured class, active mode, the 70/75 band, every actual config value, per-field applied ✓/✗, and the sensing-confidence badge.
- **Refresh contract:** reads only; bounded cost; if the export is stale (ts too old) the panel shows "daemon not updating" rather than silently showing old data.
- **The "super nice log" (one tap → offline export):** a self-contained report = the JSON export + a human-readable rendering, containing device fingerprint (model, SoC, platform, Android/build), which control nodes physically exist, what was tested, **what worked + its measured class**, what failed **and why**, current config, and the **provenance/confidence of each fact**. A short **report key** (hash of fingerprint + ts) identifies it when someone sends one in. Shared by the user however they like (file/clipboard/share-sheet). This is the per-device data that grows the overlay map (D-era), gathered by hand, never phoned home.

## 7. Error & UNKNOWN handling (no silent bugs)

Three rules, enforced everywhere:

1. **Three-valued, always.** Every detector/field is YES / NO / **UNKNOWN**. UNKNOWN is first-class — it triggers a safe fallback *and* shows in diagnostics. Never a quietly-guessed default.
2. **Fail-safe direction.** Under uncertainty, bias to *stop/limit charging*, and make the uncertainty **loud** (a `warnings[]` entry + notification), not silent.
3. **Provenance.** Every fact records how it was determined + a confidence, so a wrong reading is visible and debuggable.

Specific A+B handlers: stale export → "daemon not updating"; `acca --state` fails → AccA shows "cannot reach daemon", not blank ✓; a write whose read-back mismatches → ✗ with the actual value shown; malformed/missing field → `unknown`, never a crash (parser is null-safe, mirroring the existing 2025.x hardening).

## 8. Testing

- **Read-back diff:** set each parameter to a known value, assert `applied=yes` and `actual==requested`; set an intentionally-rejected value, assert `✗` surfaces (not a false ✓).
- **Stale/again-unreachable:** kill/pause the daemon, assert the panel shows "not updating"/"cannot reach", never stale-as-fresh.
- **Schema tolerance:** feed AccA an export with extra unknown fields and with missing fields; assert no crash and `unknown` rendering.
- **Report export:** assert the log contains fingerprint, tested-switch results, config, and a stable report key; assert it is produced with no network access.
- On-device smoke on the Pixel 9 (tegu) plus at least one non-Tensor device if available.

## 9. Forward-compatibility with C & D

- `sensing` and `switch.candidates[].measuredClass` are the slots **C** fills; A+B render them as `unknown` until then.
- `band.mode` (cycle/hold) and the bypass/drain semantics are owned by **D**; A+B only display and let the user set them through the same bus.
- The exportable report is the input that builds the **overlay device→switch map** (baseline-in-module + updatable overlay), consumed in the C/D cycles.

## 10. Open questions

- Exact applet name: `acca --state` vs `acca -j` (cosmetic; default `--state`).
- State-export location/permissions inside ACC's tmp dir (root-only; AccA reads via root shell).
- Human-readable report rendering: in-app vs templated text (default: templated text built from the JSON, so one source).
