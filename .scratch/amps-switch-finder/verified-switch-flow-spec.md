# Verified-switch → Apply → Locked: the complete flow spec

The "detect a charging switch, apply it, lock it" journey, defined as a state machine so every screen has a defined start, end, and in-between. Written because the current card has no terminal state — it shows the pre-apply caveat forever, even after you have applied and locked the switch, which reads as "nothing happened."

Two surfaces run this same flow:
- the **Config-editor card** (`AccConfigEditorActivity.setupVerifiedSwitchCard` + `applyVerifiedSpec`)
- the **Switch-finder result card** (`SwitchFinderActivity.onRunFinished` + `onApplyFoundClick`)

They must behave identically. Today they drift (different strings, the finder hides its button on some states the editor doesn't, etc.).

## 1. The inputs the card reads

On entry (activity resume, or finder completion) the card has four facts, and today it uses only the first:

| input | source | today |
|---|---|---|
| `art` — the detect result | `VerifiedSwitch.detect()` → Verified / NeedsTest(switch,klass,conf,alts) / Precondition(reason) / DeviceMismatch / NoSwitch / None | used |
| `pinned` — the node ACC currently uses | `config.txt` chargingSwitch | **not read here** |
| `locked` — is that switch user-locked | chargingSwitch ends in ` --` | **not read here** |
| `mode` — what Apply will do | `VerifiedSwitch.applyMode(klass, conf)` → PIN_DIRECT / LIVE_TEST / LEVEL_RERUN | used for the caveat only |

The missing read of `pinned`+`locked` is the whole bug: the card cannot tell "this is a fresh suggestion" from "this is the switch you already locked," so it always shows the suggestion.

## 2. State selection (run this on every entry)

```
art = detect()
if art in {None, NoSwitch}                    -> HIDDEN
if art == DeviceMismatch                      -> HIDDEN (+ optional one-line "from another device")
if art == Precondition                        -> S_PRECONDITION
if art in {Verified, NeedsTest}:
    if sameNode(art.switch, pinned) && locked -> S_LOCKED          # <-- new terminal, fixes the report
    else switch on applyMode(klass, conf):
        PIN_DIRECT                            -> S_READY
        LIVE_TEST                             -> S_NEEDS_TEST
        LEVEL_RERUN                           -> S_RERUN
```

`sameNode(a,b)` compares the first `/`-path token of each (the control node), so `battery/input_suspend 0 1` and `/sys/class/power_supply/battery/input_suspend 0 1 --` match.

## 3. The states (start point → what shows → actions → next)

Each state defines: title, body, hint/caveat, button, and the transitions out. Wording is exact so it is unambiguous.

### HIDDEN
Card `gone`. Nothing found for this device, or the artifact is for another phone. End of flow (user can still run the finder from the capacity card's "Find my charging switch").

### S_PRECONDITION
The tester stopped before touching anything (battery too hot / high / low).
- title: "Charging switch"
- body: `reason` (e.g. "Battery is 88% — too high for a clean test. Discharge to 40-80% and run again.")
- hint: none · button: **Re-scan** (opens the finder)
- out: user fixes the precondition, re-runs → back to selection.

### S_READY  (conf = verified)
AMPS current-anchored and hold-proved this exact switch on this device.
- title: "Recommended charging switch"
- body: "**{node}** ({class}) — verified for your device."
- hint: none (no caveat — this is the point of `verified`)
- button: **Apply & Lock**
- out: tap → S_APPLYING (no live test) → S_LOCKED.

### S_NEEDS_TEST  (mode = LIVE_TEST: cut/bypass/drain, conf ≠ verified)
A real switch that AMPS graded below "verified" (a slow/odd resume, or a pump grade). Apply runs a short `acc -t` first.
- title: "Recommended charging switch"
- body: "**{node}** ({class})"
- hint, by conf (NOT one scary string for all):
  - default: "Apply & Lock runs a quick live test (a few seconds) and then pins this switch."
  - `pump-needs-long-test`: "This is a charge-pump device — Apply & Lock live-tests it, but watch for slow drain at the cap under load."
- button: **Apply & Lock**
- out: tap → require charging (else "Plug in the charger to test") → S_APPLYING(test) → pass = S_LOCKED, fail = S_FAILED.

### S_RERUN  (mode = LEVEL_RERUN: a %-cap not engage-proven)
`acc -t` cannot judge a %-cap from below its cap, so we do not offer a doomed test.
- title: "Recommended charging switch"
- body: "**{node}** — a percentage cap"
- hint: "This cap can only be confirmed while the battery is above it. Re-run the finder at 40-80% to verify, then apply."
- button: **Re-run finder**
- out: opens the finder.

### S_APPLYING  (transient)
- keep title/body, hide the hint, disable the button, show the spinner row.
- status text: "Testing the switch live…" (LIVE_TEST) or "Locking…" (PIN_DIRECT).
- out: success → S_LOCKED · failure → S_FAILED · unplugged mid-test → S_NEEDS_TEST + a "plug in" dialog.

### S_LOCKED  (terminal — the missing state)
The switch is now ACC's active, user-locked switch. This is where the flow ENDS, and it is what the report is missing.
- title: "Your charging switch"
- body: "✓ **{node}** ({class}) — active and locked."
- hint: none. The caveat is gone because there is nothing left to do.
- buttons: **Change switch** (opens the all-switches picker) · **Re-scan** (finder)
- entry: reached two ways — (a) right after a successful Apply, transition the card IN PLACE, and (b) on any later open, via the `sameNode && locked` check in state selection. Both must land here. Today neither does.

### S_FAILED  (live test did not hold)
- title: "Recommended charging switch"
- body: "**{node}** ({class})"
- hint: "The live test didn't hold, so it was not applied. Pick another switch or re-scan."
- buttons: **See all switches** (if alts exist) · **Re-scan**
- out: picker or finder. Today this is only a toast + a re-enabled button, so the user is left on the same scary caveat with no next step.

## 4. The all-switches picker (a sub-flow, both surfaces)

Opened from S_LOCKED "Change switch" or the "see all N switches" affordance.
- lists the recommended + every alt, each tagged `CLASS · conf · stability · latches`.
- picking a row: update the card headline, caveat, AND the `verifiedSwitch` field to the picked row (so a later Apply targets what the card shows — already fixed this session), then run that row's `applyMode` (verified/level-needs-test = direct, else live-test).
- after the pick applies → S_LOCKED for the picked switch.

## 5. Current code vs this spec — the gaps

| # | gap | where | effect the user sees |
|---|---|---|---|
| G1 | no S_LOCKED terminal state | `setupVerifiedSwitchCard` never checks pinned+locked; `applyVerifiedSpec` success only toasts | the caveat "not fully verified, needs a live test" stays on screen after you apply+lock — "it's not going away" |
| G2 | apply success is transient | success sets a status line + toast, no card transition | re-opening the editor re-shows the suggestion as if nothing happened |
| G3 | one caveat for all needs-test | `verified_switch_needs_test_caveat` is generic + mentions pump-leak for every case | a routine cut switch is described as risky/unverified |
| G4 | S_FAILED not defined | live-test failure re-enables the button + toasts | dead end — same caveat, no "try another / re-scan" |
| G5 | S_PRECONDITION has no action | editor shows the reason but no re-scan button | user reads why, but the card gives no way forward |
| G6 | the two surfaces drift | editor vs finder use different strings + button rules | inconsistent feel between "Find my switch" and the editor card |

## 6. Wording set (so every state reads cleanly)

- title.suggest = "Recommended charging switch"
- title.locked = "Your charging switch"
- body.verified = "%1$s (%2$s) — verified for your device."
- body.plain = "%1$s (%2$s)"
- body.locked = "✓ %1$s (%2$s) — active and locked."
- hint.livetest = "Apply & Lock runs a quick live test (a few seconds), then pins this switch."
- hint.pump = "Charge-pump device — Apply & Lock live-tests it; watch for slow drain at the cap under load."
- hint.rerun = "This cap can only be confirmed above its level. Re-run the finder at 40-80%%, then apply."
- hint.failed = "The live test didn't hold, so it was not applied. Pick another switch or re-scan."
- btn.apply = "Apply & Lock" · btn.change = "Change switch" · btn.rescan = "Re-scan" · btn.rerun = "Re-run finder" · btn.seeall = "See all %1$d switches"

## 7. The one-line summary of the fix

Read `pinned`+`locked` on entry and add the **S_LOCKED** terminal state; transition the card in place after a successful Apply instead of toasting; split the caveat by conf; give S_FAILED and S_PRECONDITION a forward action. That closes "the caveat won't go away" and makes every screen have a start, an end, and a next step.
