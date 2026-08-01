# AMPS Universal Switch Finder — Fix Plan

Target file: `app/src/main/assets/acc-compat.sh` (AMPS v7.1.6), plus one wakelock touch in `SwitchFinderActivity.kt`.
Scope: the "Find my charging switch" scanner only. Does NOT touch the ACC module (rc21) or the AccA version line.
Method: systematic-debugging — root cause confirmed first; each phase is one isolated change with a failing-test-first check on the Mi A3 rig before the next.

---

## 1. Confirmed root cause (evidence, not theory)

**Primary — the poison cascade.** A latching switch engages, stops charging, and cannot self-resume (`STUCK -- no re-arm within ~90s: needs replug/reboot`, acc-compat.sh:1324). The pre-test gate for the NEXT switch waits only ~8s with one weak `defaults_native` (1140-1167) then emits `[skip: charging-state did not return between tests]`. In the deep OnePlus log that skip fires ~12× across Layer 4c — the layer where `mmi_charging_enable` lives — so mmi is never tested and the verdict is built only from switches that ran before the stall. Quick tests few → hits mmi → wins. Deep tests many → hits a latch early → poisons the tail → crowns a latching switch.

**Secondary — runtime.** No partial wakelock; `FLAG_KEEP_SCREEN_ON` (SwitchFinderActivity:93) only holds while the activity is foreground. Screen-off/backgrounded → device suspends → every `sleep` (watchdog at 698 is sleep-count-based; resume-waits are `sleep 6`×15) stretches in wall-time. The 30-min `over()` deadline is wall-clock and caps live tests, but the fixed non-loop waits + a suspend-deferred hard-kill watchdog let total wall-time balloon (observed 10481s; typical 10-15 min is still too long because of the wasted resume-waits).

**Tertiary — the verdict cannot self-correct.** It never carries forward the ACC-confirmed / known-good switch (it PRINTS "ACC previously found working" at 2405-ish but does not promote it), and the finalist stress-test can only rubber-stamp — it cannot reject a fake bypass, so a "bypass that doesn't hold" gets laundered into a recommendation.

---

## 2. Design (from the agreed discussion)

The rhythm: `snapshot golden native (once) → order candidates good-first → [reset_to_native + verify_charging] → grade → restore → repeat → early-exit on a clean winner → stress-GATE the winner`.

Locked decisions (recommendations; change any before build):
1. Hard-latch that won't recover → one-time replug prompt with timeout, then stop-with-verdict.
2. Early-exit: once one switch is clean-held-resumed-stress-passed, stop live engagement even in Deep; continue read-only discovery only.
3. verify_charging signal: real current when the sensor is trustworthy, else BLIND (status + charge_type + voltage-rising over a window).
4. Scope: logic in `acc-compat.sh`; a partial wakelock (`/sys/power/wake_lock` from the script, or a PARTIAL_WAKE_LOCK in AccA) to kill the suspend-stretch.
5. Stress reject → walk down the ranked list, stress each until one passes or the list is exhausted, then honest verdict.

---

## 3. Fix phases (each: failing test → single change → verify on A3)

**P0 — A3 baseline / failing test (measure, change nothing).**
Run current AMPS quick + deep on the A3. Capture: the exact "bypass-that-doesn't-hold" node, whether the A3 re-kick restores charging after it, per-test reset+verify wall-time, and whether the current sensor is trustworthy or flipping. This is the failing test the fix must flip AND the data that calibrates every probability below.

**P1 — the guarantee: `reset_to_native()` + `verify_charging()` before every test.**
Golden snapshot captured once while charging is confirmed healthy (node values + live charge fingerprint). Before each test: replay golden + fire the family-appropriate re-kick (REKICK_KIND already detected), then verify REAL charge resumed (current back near native, or BLIND fallback). Pass → test. Fail → escalate (P4), never test blind.

**P2 — candidate ordering + early-exit.**
Order: ACC-confirmed/known-good → plain bypass → latch-prone (fcc-zero, restrict_chg, voltage_max) LAST. Early-exit once a switch is clean-held-resumed. This alone stops mmi being starved.

**P3 — stress test becomes a rejection GATE.**
Reset+verify between every hammer; if the winner leaks or fails to hold under repeated load, downgrade/reject and fall to the next-best candidate (P2 order), stress that, repeat. Leaves the phone native afterward.

**P4 — hard-latch escape.**
verify fail after full reset → one bounded replug prompt → re-verify → continue; on timeout, stop live tests and emit a verdict from clean data + carried-forward ACC-confirmed switch. Always terminates.

**P5 — wakelock.**
Hold a partial wakelock for the scan duration so a screen-off phone can't stretch the timers. Release on exit/cancel/crash.

---

## 4. Actuarial analysis

### 4a. Sensitivity — the load-bearing assumptions (outcome depends most on these)

| # | Assumption | Sensitivity | If it fails, the failure mode is | Why the design bounds the downside |
|---|---|---|---|---|
| A | `verify_charging` can tell "really charging" from "latched-but-reads-Charging" | **HIGH** | poison returns (false-positive → test on dead phone) | double-signal (current AND voltage-rising); A3 tests it directly |
| B | reset+re-kick restores charging after a *soft* latch on most phones | **HIGH** | more escapes to P4 (less coverage) | downside is coverage, not wrong data — safe degradation |
| C | stress-gate detects a fake bypass under repeated load | **HIGH** | fake recommended (the exact bug we're killing) | A3's known fake is the direct fixture |
| D | ordering good-first reaches a clean switch before latch-prone | MED | test dangerous ones anyway (but reset-verified) | latch-prone last + per-test reset = no poison |
| E | wakelock removes the suspend-stretch | MED | runtime still long on some phones | correctness unaffected; deadline still caps |
| F | golden snapshot + replay does not itself disturb negotiation | MED | reset drops charging → extra recovery | replay = restore, not aggressive writes; + re-kick |

The three HIGH-sensitivity items (A, B, C) are all **directly testable on the A3** — that is the point of the rig. We do not ship a projection we could have measured.

### 4b. Probability estimates (priors — updated by P0 measurement)

| Phase | Delivers its goal | Dominant residual risk |
|---|---|---|
| P1 guarantee | 0.90 | false-positive verify (A) |
| P2 order+early-exit | 0.95 | deterministic; low risk |
| P3 stress-gate | 0.85 | fake undetectable in short load (C) |
| P4 escape | 0.98 | UX only; always terminates |
| P5 wakelock | 0.80 | inherent slow re-negotiation phones |
| **Whole fix correct on A3 + OnePlus class** | **~0.80** | joint of A·B·C |

### 4c. Risk register (expected loss = P(fail) × impact)

| Risk | P(fail) | Impact | Expected loss | Mitigation |
|---|---|---|---|---|
| R1 false-positive verify → poison returns | 0.15 | HIGH | **med-high** | current+voltage double-check; A3 gate before merge |
| R2 reset itself drops charging | 0.20 | MED | med | restore-snapshot (not writes) + re-kick; measure in P0 |
| R3 regression breaks a currently-working phone | 0.15 | HIGH | **med-high** | OnePlus Quick/Deep reports + A3 as regression fixtures; gate behind the same verify quick already passes |
| R4 replug prompt annoys glink-phone users | 0.30 | LOW | low | timeout → auto stop-with-verdict |
| R5 early-exit skips a *better* switch | 0.10 | LOW | low | accept — a held switch is a held switch |

Highest expected-loss items are R1 and R3 → they get the hardest A3/OnePlus gating before anything merges.

### 4d. Projection

- **Runtime:** 10-15 min (or the 2h suspend glitch) → projected **~3-7 min typical** (early-exit usually fires before the latch-prone tail), hard-capped by deadline + wakelock. Calibrated by P0's measured per-test reset+verify time × candidate count.
- **Correctness:** OnePlus-class → recommends mmi (not a latching switch); A3 → the fake bypass is REJECTED by the stress-gate, not recommended. Universal invariant: **never recommend a switch that failed reset-verify or the stress-gate.**
- **Coverage / honest degradation:** glink/UCSI and only-latching-switch phones → "best-effort pick + explicit caveat / replug note" instead of a false clean verdict. Safe, not silent.

---

## 5. Verification (systematic-debugging Phase 4)

Rig: Mi A3 over WiFi (su works). Fixtures: the two OnePlus 8 reports (regression), the A3's own broken switch (the fake-rejection proof).

Assertions to flip from P0-fail to pass:
1. After each switch test, `verify_charging` confirms native BEFORE the next test (log a per-test "native OK" line).
2. The A3's "bypass that doesn't hold" is graded not-held and is NOT the recommendation.
3. No `[skip: charging-state did not return]` cascade — either recovered or a clean stop.
4. Runtime bounded (measure; expect a large drop).
5. Stress-gate rejects the fake and (if present) falls through to a real switch or an honest "no clean switch" verdict.
6. Regression: a phone that already resolved cleanly (OnePlus mmi) still resolves to mmi.

Each phase P1-P5 lands and is A3-verified before the next. No phase merges on a projection alone where a measurement was available.

---

## RESULTS LOG

**P0 (A3 7.1.6 quick baseline):** sensor `CUR_USABLE=1` (current-verify usable), `REKICK=yes` qcom-smb5, fake = `charge_control_limit=6` (reads BYPASS, goes STUCK under stress). `reset_to_native` (clear suspend + re-kick, ACC stopped) revives charging on the A3 → priors A + B confirmed HIGH.

**7.1.7-alpha1 = P1 (reset_native_verify in gate + hard-latch escape) + P5 (wakelock).** A3 deep test (~12m35s, clean exit): 0 poison-cascade skips (was ~12 on OnePlus), every switch tested + recovered ~3s, fake `charge_control_limit` REJECTED ("does not hold under load"), recommendation `input_suspend (CUT)`, charge state clean after. Core fix VALIDATED.

Lesson: never `pgrep -f acc-compat.sh` inside `su -c "..."` — it self-matches the command line (faked a 21-min "still running"). Use a script file or a more specific pattern.

**Still open:** P2 (order good-first + early-exit — cuts the ~12.5 min, avoids engaging flaky switches), P3 refinement (stress-gate already rejected the fake here), broader-device testing (OnePlus-class hard-latch — the A3's switches all recover, so it doesn't exercise the hard-latch escape). Files: `acc-compat.sh` now at V=7.1.7.

## 6. Empirical unknowns to measure in P0 (these calibrate §4)

- A3's exact fake node + why it "reads bypass but doesn't hold" (leak vs latch vs flicker).
- Does the A3 re-kick (or defaults_native) actually restore charging after that node? → sets prior B.
- A3 current-sensor trustworthy or flipping? → sets the verify path (A).
- Measured per-test reset+verify wall-time on the A3 → sets the runtime projection (4d).
