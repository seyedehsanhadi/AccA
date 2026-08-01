# acc -t reliability + the AMPS/AccA test-vs-no-test wiring

Reference for the charge-switch verification path across the three layers: ACC's built-in `acc -t`, the AMPS detector (`acc-compat.sh` v7.1.8), and the AccA app. Written after the Pixel 9a "verified switch keeps asking for a broken retest" incident. Line numbers are the state on 2026-07-22.

## 1. What `acc -t` actually does

`acca -t [switch]` runs `test_charging_switch_()` ([acc.sh:153](../../PROJECTS/ACC/install/acc.sh)). The whole test is four steps:

1. `flip_sw off` — write the switch's OFF value ([misc-functions.sh:565](../../PROJECTS/ACC/install/misc-functions.sh)). For `charge_stop_level 100 pcap` the OFF value is `pcap`, which resolves to `pause_capacity` (e.g. 75).
2. Poll `not_charging` up to `_STI` times (default **35**, [batt-interface.sh:50](../../PROJECTS/ACC/install/batt-interface.sh)), roughly one sample per second.
3. `flip_sw on` — restore the ON value (100 for a level node, so it re-arms).
4. Verdict from whether the battery ever went "not charging".

The decisive check is status, not current. `not_charging` reads the battery **status string** and returns true only when it reads `Discharging` or `Idle` ([batt-interface.sh:220-222](../../PROJECTS/ACC/install/batt-interface.sh)). The milliamp figure printed on each line (`off (N/A)  2243mA  Charging`) is display only; the pass/fail turns on the status word alone.

Exit codes, and how AccA reads them ([AccHandler.kt:357](../../PROJECTS/AccA/app/src/main/java/mattecarra/accapp/acc/v202107280/AccHandler.kt)):

| exit | meaning | AccA `switchTestPassed` |
|---|---|---|
| 0 | works, CUT-type (status went Discharging) | pass |
| 15 | works, IDLE/BYPASS (status went Idle) | pass |
| 2 | not charging / plug in | fail |
| 1 / 10 | switch did nothing / malformed | fail |

AccA also passes on the literal stdout `"Switch works"`. Everything else fails. The call is wrapped in `timeout 150` and always followed by `ensureDaemonRunning()`, so a killed test can't leave charging uncontrolled.

One thing `acc -t` does **not** do: it never compares the battery level against the cap. There is a level-settle helper, `sw_holds()` ([misc-functions.sh:331](../../PROJECTS/ACC/install/misc-functions.sh)), that waits up to 4 firmware ticks (40s) for a `charge_stop_level` write to take — but that is the daemon's auto-detect path, and `acc -t` doesn't call it. Even `sw_holds` only covers a slow firmware tick, never the case where the battery sits below the cap.

## 2. Is it reliable? (verdict per switch class)

`acc -t` is a correct, minimal **suspend-switch tester**. It is reliable inside that domain and blind outside it.

| switch class | what "off" does | does status flip? | `acc -t` verdict |
|---|---|---|---|
| CUT / suspend (`input_suspend`, `charging_enabled 0`) | charging halts at once | yes, → Discharging | **reliable** |
| BYPASS / battery-idle | phone runs on the cable, battery ~0 | yes, → Idle (exit 15) | **reliable** |
| DRAIN (input cut, battery discharges) | current reverses | yes, → Discharging | **reliable** |
| LEVEL / %-cap (`charge_stop_level`, `charge_control_limit`) | firmware stops **only at/above the cap %** | no, if SOC < cap | **structurally blind** |
| THROTTLE (lowers current, never stops) | charging continues, slower | no | rejects it — correct |

Two caveats even inside the reliable classes:

- **Status honesty.** The whole test rides on the kernel's status string. On a ROM that keeps reporting `Charging` through a suspend, or reports `Full`/`Not charging` spuriously, `acc -t` will mis-call a switch that actually works. AMPS avoids this by measuring current with sign/unit auto-learn and only falling back to status+voltage when the sensor is dead.
- **Short test for pump chargers.** A charge-pump switch can hold for 35s and still leak under sustained load. `acc -t`'s window is too short to see that; the `pump-needs-long-test` grade exists precisely because the long soak has to happen in AMPS, not here.

**Does it need rework?** No — not inside the module. `acc -t` lives in the frozen rc21 payload, and it is correct for the job it was built for. Reworking it would mean touching `accd.sh`/`acc.sh` in a shipped module for a problem that is better solved one layer up. The right move is reinforcement at the two layers that already wrap it: AMPS pre-verifies with the SOC-aware engage test, and AccA routes the classes `acc -t` can't judge around it. Section 6 lists the one residual gap.

## 3. How AMPS and AccA are wired

AMPS writes one artifact, `/data/local/tmp/acc-compat-verified`, with (among other fields) `class` and `conf`:

- `class` ∈ `level | bypass | cut | drain | throttle`
- `conf` ∈ `verified | needs-test | unconfirmed | pump-needs-long-test | latch-needs-rearm | from-ACC-history | none`

AccA reads it in `VerifiedSwitch.detect()` ([VerifiedSwitch.kt](../../PROJECTS/AccA/app/src/main/java/mattecarra/accapp/acc/VerifiedSwitch.kt)). The mapping is deliberately blunt: `conf == "verified"` becomes `Verified`; every other non-empty conf becomes `NeedsTest` carrying the original conf and class. A device/SoC fingerprint and a node-exists check gate the whole thing so a stale or wrong-phone artifact is dropped.

At apply time, `VerifiedSwitch.applyMode(class, conf)` picks one of three paths (the single source of truth, shared by the editor card and the switch finder):

```
applyMode:
    conf == verified   -> PIN_DIRECT
    class == level      -> needs-test ? PIN_DIRECT : LEVEL_RERUN
    else                -> LIVE_TEST

PIN_DIRECT   pin straight away, no acc -t, no "connect charger"
LEVEL_RERUN  a %-cap that was not engage-proven: acc -t can only false-fail it, so
             ask for a re-run at a lower battery %; nothing is pinned
LIVE_TEST    require charging, run acca -t, pin only on pass
```

A level cap never reaches `acc -t`. It is proven by the AMPS engage-and-hold step regardless of its confidence word (a `needs-test` on a level cap means only that its firmware re-arm was slow, never that it failed to cap), and if enforcement was never proven at all, `acc -t` would just false-fail it (Section 5). This is the 7.1.8/7.1.9 wiring.

## 4. When a test is needed, and when it isn't

The criterion in one sentence:

> Run the live `acc -t` only for a switch whose OFF flips the battery status **now** (cut / suspend / idle) and that AMPS could not already grade `verified`. Skip it whenever AMPS already verified the switch, or the switch is a level %-cap.

Spelled out:

**Skip the test (pin directly):**
- Any class, `conf = verified`. AMPS already ran the current-anchored engage test plus the leak/re-arm soak; a second short test adds nothing and stops the daemon for no reason.
- `class = level`, `conf = needs-test`. Enforcement was proven; only the re-arm was slow. `acc -t` cannot observe a level cap from below it.

**Run the test:**
- A non-level switch (`cut`, `bypass`, `drain`) that AMPS left below `verified` (`needs-test`, `unconfirmed`, `pump-needs-long-test`). Here `acc -t`'s status-flip check is both applicable and a real second opinion on a possibly-changed charger path or ROM.

The full class × conf matrix, with the current outcome:

| class | conf | AccA action | correct? |
|---|---|---|---|
| level | verified | pin direct | yes |
| level | needs-test | pin direct (7.1.8) | yes |
| level | unconfirmed | LEVEL_RERUN: ask for a lower-% re-run, nothing pinned | yes (7.1.9) |
| level | pump-needs-long-test | LEVEL_RERUN | yes (7.1.9) |
| cut | verified | pin direct | yes |
| cut | needs-test / unconfirmed | `acc -t` (status → Discharging) | yes |
| bypass | verified | pin direct | yes |
| bypass | needs-test | `acc -t` (status → Idle, exit 15) | yes |
| bypass | pump-needs-long-test | `acc -t` (short) | confirms engage, not sustained leak (Section 6, gap P) |
| drain | verified / needs-test | pin / `acc -t` (→ Discharging) | yes |
| throttle | any | `acc -t` → fail | correct rejection |
| (any) | none | no card shown | yes |

## 5. Why a level cap can never pass `acc -t`

Worked from the Pixel 9a logs. The switch is `charge_stop_level 100 pcap`; `pause_capacity` is 75; the battery sat at 40-51%.

- `acc -t` writes OFF = `pcap` = 75. The firmware's rule is "stop at 75%." The battery is at 51%, so charging correctly continues.
- Status stays `Charging` for all 35 samples. `not_charging` never returns true.
- Verdict: `Switch doesn't work`, exit fail.

There is no battery level at which a user testing between 40-80% will pass this, because the cap is above the current level by design. The only way to verify a level cap is to set it **below** the current SOC and watch the firmware stop — which is exactly what AMPS Layer 5 does (`engage stop=36% at SOC 41% -> ENFORCED + held +24s`). `acc -t` has no such step, so the two disagree, and before 7.1.8 the disagreement stranded the user.

## 6. Gaps — status (filled 2026-07-22, v7.1.9)

**Gap L — level + unconfirmed reached `acc -t`. CLOSED.** `VerifiedSwitch.applyMode` returns `LEVEL_RERUN` for any level cap that is not pin-eligible: no `acc -t`, no misleading "failed the live test". The user is told the cap can only be checked above its level and asked to re-run the finder lower. No level switch touches `acc -t` on any path (editor card, all-switches picker, switch finder).

**Gap P — pump grade got only the short test. ADDRESSED.** `acc -t` still runs for a pump switch (it correctly confirms the switch engages), but on a pass AccA now shows a leak-watch note, and the pre-apply caveat already flags the load-leak risk. The real soak is AMPS's job; turning `acc -t` into a soak test would mean editing the frozen module for no real gain, so this stays as messaging.

**cfg_lookup vendor-label miss. CLOSED.** `cfg_lookup` falls back to a path-anchored basename match, so a vendor-friendly label like `google charge_stop_level` resolves to its real `/sys/.../google,charger/charge_stop_level` path directly instead of leaning on the class fallback. Exact match still wins; the fallback fires only on a miss.

**DECODED status line. CLARIFIED.** The point-in-time `status=` in the DECODED block is labelled as an instant read that can catch a mid-re-arm value during restore, so a pasted report no longer looks self-contradicting.

**Surprise D — "Deep" still early-exits. OPEN by design.** `have_clean_winner()` doesn't check `MODE`, so Deep still skips the obscure discovery layers once a clean winner is banked; only "Highest accuracy" forces the exhaustive path. That is the intended speed trade-off, not a defect — left as is.

**Watch — charge_counter unit assumption. OPEN.** The DRAIN/LEAK grades lean on `charge_counter` deltas (`-500`, `+1500`, `+4000`) assumed to be µAh, never checked against the device. Low frequency, high consequence. A startup sanity check against `charge_full` would close it; not done yet.

## 7. Thresholds, straight

### acc -t
- `_STI = 35` iterations, ~1s each → up to ~35s per switch. Env-overridable via `-t<N>`.
- Pass = battery status reads `Discharging` or `Idle` within those 35 samples. No current threshold, no SOC check.

### AMPS (`acc-compat.sh` v7.1.8), the values that decide a verdict

Current sign + magnitude:
- `THR` = 50 (mA) / 50000 (µA) — below this, current counts as zero. Unit auto-detected: baseline `|current| >= 16000` raw ⇒ µA scale.
- `IDLE` = 10 (mA) / 10000 (µA) — near-zero band for "battery idle".
- `NEAR` = `baseline - baseline/8` (~87.5%) — "still clearly charging", used for the hold fast-pass.
- `CUR_FROZEN` = all 6 startup samples bit-identical ⇒ sensor treated as dead ⇒ BLIND mode.
- `SIGN_UNSTABLE` = ≥2 positive and ≥2 negative in 6 samples ⇒ sign unreliable ⇒ BLIND mode.
- Coulomb override: `charge_counter` rose ≥150 over ~5s ⇒ charge direction is trusted over the sign convention.

Voltage (BLIND mode only):
- `VNOISE` = spread of 6 startup samples. `VDROP` = `VNOISE + 25` mV (floor 25) — a sag ≥ this counts as "charging stopped". `VRISE` = 1 if `VNOISE ≥ 8` mV.
- A voltage-only or status-only observation never grades higher than `needs-test`; a whole BLIND run downgrades any verified bypass/cut/drain to `needs-test`.

Hold verify:
- `POLL` = 3s (10s if only `current_avg` exists). A switch is "held" when it reads not-charging for the sample window (9s), re-confirmed after a further 6s → the ~15s hold. A holds-alone check then watches ~24s more for a sneaky re-arm.

Resume classify (drives the confidence downgrade):
- `OK` = charging back within ~9s. `after-reset` = back only after a native reset. `SLOW(Ns)` = back within 12-84s. `STUCK` = no resume by 90s (excluded from the recommendation). `UNKNOWN` = the global deadline hit first.

Precondition gates (hard stop, exit 3):
- SOC `>= 85` too high, SOC `< 15` too low, temp `>= 45C` too hot. Ideal test window is 40-80%.

Escape / safety:
- `GATE_HARDFAILS >= 2` (two failed native-charging restores) ⇒ `SKIPALL`, stop live tests and grade from what's verified plus ACC history. A 30s unplug mid-run also sets `SKIPALL`.
- Global deadline: 1020s quick / 1800s complete, with a hard SIGTERM 120s later.

The most fragile of these — where a wrong value most misleads a user — are the `charge_counter` unit assumption, the fixed `VDROP` bar taken from one startup window, the `SIGN_UNSTABLE` 2-of-6 trip, and the 90s STUCK boundary. All four are noted in the AMPS threshold audit and none currently misfires on the two verified devices.

## 8. Noise handling, by failure mode

- **Dead sensor** (all reads identical) → `CUR_FROZEN` → BLIND (status + voltage), verdict capped at `needs-test`.
- **Flipping sign** (marginal sensor) → `SIGN_UNSTABLE` → BLIND.
- **Lying status string** → AMPS ignores it when current is usable; `acc -t` cannot, which is the main reason a `needs-test` cut still gets a second look.
- **Slow firmware re-arm** → handled as `SLOW`, benign for level caps (the 7.1.8 fix), still a downgrade for cut/bypass/drain.
- **Charger renegotiation mid-scan** → `HALF`/`NEAR` rebase on each clean gate pass, so a shifted baseline doesn't fake a throttle; the trade-off is a floating reference.
- **Battery too full/empty/hot** → precondition gate refuses the run rather than grade on no headroom.
- **A tested switch latches the phone** → `reset_native_verify` before each test, and after two unrecoverable resets the run stops and grades from clean data.
