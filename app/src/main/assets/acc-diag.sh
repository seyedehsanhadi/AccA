#!/system/bin/sh
# acc-diag -- passive charge-control diagnostic for the ACC/AccA project.
# OBSERVE-ONLY: never stops ACC, never toggles a switch, never writes a charge node. It reads the
# device + charging architecture, records a short flight-recorder timeline, pulls ACC's own logs, and
# COMPUTES a failure verdict (overcharge / fake-idle / polarity / re-arm / drain / stuck / no-switch),
# then bundles it all -- PII-stripped, structured -- so the devs know exactly what is wrong and how to
# patch this device class. Run: sh acc-diag.sh   (or --selftest for the pure detector matrix).
DV=1
PSY=/sys/class/power_supply
DD=/data/adb/vr25/acc-data
ACCA=/dev/.vr25/acc/acca
TS="$(date +%Y%m%d-%H%M%S 2>/dev/null || echo report)"
OUT=/data/local/tmp/acc-diag-$TS.txt

rd(){ cat "$1" 2>/dev/null | sed -n '1p'; }
num(){ case "${1:-x}" in ''|*[!0-9-]*) echo 0;; *) echo "$1";; esac; }

# ---- diag_flag: per-sample anomaly detector (PURE, selftested). norm_cur is CHARGE-POSITIVE
# (positive = current INTO the battery). The same logic powers daemon self-heal.
# $1=cap $2=pause $3=status(chg/dis/idle/notchg) $4=norm_cur(signed) $5=idle-thr $6=present(0/1)
diag_flag(){
  [ "$1" -gt $(( $2 + 2 )) ] 2>/dev/null && { echo overcharge; return; }
  if [ "$6" = 1 ]; then case "$3" in
    dis|idle|notchg) [ "$4" -gt "$5" ] 2>/dev/null && { echo fake-idle; return; };;
  esac; fi
  case "$3" in
    chg) [ "$4" -lt "$(( 0 - $5 ))" ] 2>/dev/null && { echo polarity; return; };;
    dis) [ "$6" = 1 ] && [ "$4" -gt "$5" ] 2>/dev/null && { echo polarity; return; };;
  esac
  echo ok; }

_selftest(){ _p=0; _f=0
  _ck(){ if [ "$2" = "$3" ]; then _p=$((_p+1)); else echo "  FAIL $1: got=$2 want=$3"; _f=$((_f+1)); fi; }
  _ck overcharge  "$(diag_flag 80 74 chg 500 10 1)"     overcharge
  _ck overchg_dis "$(diag_flag 90 74 dis -50 10 1)"     overcharge
  _ck fakeidle    "$(diag_flag 74 74 notchg 500 10 1)"  fake-idle
  _ck fakeidle_id "$(diag_flag 74 74 idle 800 10 1)"    fake-idle
  _ck polarity_c  "$(diag_flag 74 74 chg -500 10 1)"    polarity
  _ck fakeidle_ds "$(diag_flag 74 74 dis 500 10 1)"     fake-idle
  _ck ok_charge   "$(diag_flag 70 74 chg 500 10 1)"     ok
  _ck ok_cutload  "$(diag_flag 74 74 dis -50 10 1)"     ok
  _ck ok_unplug   "$(diag_flag 70 74 dis -500 10 0)"    ok
  _ck ok_idlecut  "$(diag_flag 74 74 notchg 3 10 1)"    ok
  echo "acc-diag selftest: $_p passed, $_f failed"; [ "$_f" = 0 ]; }

case "${1:-}" in --selftest) _selftest; exit $?;; --version) echo "acc-diag v$DV"; exit 0;; esac

# ---- status normalization ----
norm_status(){ case "$(rd "$PSY/battery/status")" in
  Charging|charging) echo chg;; Discharging|discharging) echo dis;;
  Not?charging|not?charging) echo notchg;; Full|full) echo full;; *) echo idle;; esac; }

# ---- polarity: prefer ACC's calibrated value, else learn from a Charging sample ----
POL=normal; UNIT=uA
_st="$($ACCA --state 2>/dev/null)"
case "$_st" in *'"polarity":"inverted"'*) POL=inverted;; esac
case "$_st" in *'"currentUnits":"mA"'*) UNIT=mA;; esac
CURNODE=""; for c in "$PSY/battery/current_now" "$PSY/battery/current_avg" "$PSY/bms/current_now"; do
  [ -f "$c" ] && { CURNODE="$c"; break; }; done
norm_cur(){ _r="$(num "$(rd "$CURNODE")")"
  case "$POL" in inverted) echo "$(( 0 - _r ))";; *) echo "$_r";; esac; }
present_now(){ for p in "$PSY"/*/present; do [ "$(rd "$p")" = 1 ] && { echo 1; return; }; done; echo 0; }
online_now(){ for o in "$PSY"/*/online; do [ "$(rd "$o")" = 1 ] && { echo 1; return; }; done; echo 0; }

# idle threshold in the current unit (uA needs a bigger floor than mA)
IDLE=20000; [ "$UNIT" = mA ] && IDLE=20
# ACC stores capacity=(shutdown cooldown RESUME PAUSE mask) -- parse by position (the pause_capacity=
# form is only the print-config alias, so the raw config needs the array).
_capl="$(grep -m1 '^capacity=' "$DD/config.txt" 2>/dev/null | sed -e 's/^capacity=(//' -e 's/).*$//')"
# shellcheck disable=SC2086
set -- $_capl
RESUME="$(num "${3:-0}")"; PAUSE="$(num "${4:-0}")"
[ "$PAUSE" -ge 1 ] 2>/dev/null || PAUSE="$(num "$(grep -oE 'pause_capacity=[0-9]+' "$DD/config.txt" 2>/dev/null | grep -oE '[0-9]+' | head -1)")"
[ "$PAUSE" -ge 1 ] 2>/dev/null || PAUSE=$(num "$(rd "$PSY/battery/capacity")")
SW="$(grep -m1 '^chargingSwitch=' "$DD/config.txt" 2>/dev/null | sed -e 's/^chargingSwitch=(//' -e 's/).*$//')"

emit(){ printf '%s\n' "$*" >> "$OUT"; }
: > "$OUT" 2>/dev/null

emit "=== ACC DIAG v$DV ($TS) ==="
emit "schema=diag-$DV"
emit "privacy=no imei/serial/accounts/location collected"
emit ""
emit "## DEVICE"
emit "device=$(getprop ro.product.device 2>/dev/null) model=$(getprop ro.product.model 2>/dev/null) brand=$(getprop ro.product.brand 2>/dev/null)"
emit "soc=$(getprop ro.board.platform 2>/dev/null)/$(getprop ro.hardware 2>/dev/null) android=$(getprop ro.build.version.release 2>/dev/null) sdk=$(getprop ro.build.version.sdk 2>/dev/null)"
emit "kernel=$(uname -r 2>/dev/null)"
emit "root=$([ -d /data/adb/ksu ] && echo KernelSU; [ -d /data/adb/ap ] && echo APatch; [ -d /data/adb/magisk ] && echo Magisk)$(grep -m1 '^version=' /data/adb/modules/acc/module.prop 2>/dev/null | sed 's/^version=/ acc=/')"
emit "batt_design_uah=$(rd $PSY/battery/charge_full_design)"
emit ""
emit "## ARCHITECTURE"
_sup=; for s in "$PSY"/*; do [ -d "$s" ] || continue; _sup="$_sup ${s##*/}($(rd "$s/type"))"; done
emit "supplies:$_sup"
DRV="$(getprop ro.hardware.charger 2>/dev/null) $(ls -d /sys/class/power_supply/*/device/driver 2>/dev/null | xargs -n1 readlink 2>/dev/null | sed 's#.*/##' | tr '\n' ' ')"
emit "charger_drivers=$DRV"
PUMP=none; case "$DRV $_sup" in *bq2597*|*ln8000*|*ln8410*|*sc854*|*sc855*|*smb1390*|*upm672*|*hl7139*|*nu2115*) PUMP=PRESENT;; esac
emit "charge_pump=$PUMP  (PRESENT = charge-pump can fake idle -> trust input_suspend, not current/status alone)"
emit "sensor: node=$CURNODE unit=$UNIT polarity=$POL idle_threshold=$IDLE"
_nl=; for n in battery/charge_control_limit battery/charge_control_limit_max battery/charge_stop_level battery/batt_full_capacity; do [ -f "$PSY/$n" ] && _nl="$_nl $n=$(rd "$PSY/$n")"; done
[ -e /sys/devices/platform/google,charger/charge_stop_level ] && _nl="$_nl google,charger/charge_stop_level=$(rd /sys/devices/platform/google,charger/charge_stop_level)"
emit "native_limit_nodes:$_nl"
emit "control_node_inventory (EVERY charge-control-relevant node, known or not -> unknown nodes here are what we ADD to support a new phone):"
_invd(){ for _f in "$1"/*; do [ -f "$_f" ] || continue
  case "${_f##*/}" in
    *suspend*|*charg*|*disable*|*enable*|*_limit*|*current_max*|*constant_charge*|*input_current*|*level*|*bypass*|*night*|*slate*|*store_mode*|*mmi*|*protect*|*float_volt*|*voltage_max*|*step_charg*|*restrict*|*lrc*|*scenario_fcc*|*cool_*|*siop*)
      _w=ro; [ -w "$_f" ] && _w=rw; emit "  ${_f#/sys/}=$(rd "$_f" | cut -c1-40) $_w";;
  esac; done; }
for s in "$PSY"/*; do [ -d "$s" ] && _invd "$s"; done
for vp in /sys/class/qcom-battery /sys/class/asuslib /sys/class/battchg_ext /sys/class/oplus_chg/battery /sys/class/hw_power/charger/charge_data /sys/devices/platform/google,charger /sys/devices/platform/charger /sys/devices/platform/mt-battery; do
  [ -d "$vp" ] && _invd "$vp"; done
for vp in /proc/mtk_battery_cmd /proc/driver; do [ -d "$vp" ] && for _f in "$vp"/*; do [ -f "$_f" ] && emit "  ${_f}=$(rd "$_f" | cut -c1-40)"; done; done
emit ""
emit "## ACC CONFIG"
emit "switch=$SW"
emit "pause=$PAUSE resume=$RESUME userlocked=$([ -f $DD/.user-locked ] && echo yes || echo no) daemon_pid=$(pgrep -f accd | head -1)"
emit "artifact=$([ -f /data/local/tmp/acc-compat-verified ] && echo present || echo none)"
emit ""
emit "## FLIGHT RECORDER (60s, observe-only)"
emit "t cap cur(norm,$UNIT) volt status isp online present flag"
N=0; FLAGS=; CAP0=; CAPL=; CUR_IDLE_SEEN=0; CUR_ROSE=0
while [ "$N" -lt 7 ]; do
  _cap="$(num "$(rd $PSY/battery/capacity)")"; _cur="$(norm_cur)"; _volt="$(num "$(rd $PSY/battery/voltage_now)")"
  _stt="$(norm_status)"; _isp="$(rd $PSY/battery/input_suspend)"; _on="$(online_now)"; _pr="$(present_now)"
  _flag="$(diag_flag "$_cap" "$PAUSE" "$_stt" "$_cur" "$IDLE" "$_pr")"
  emit "$(( N*10 )) $_cap $_cur $_volt $_stt ${_isp:-?} $_on $_pr $_flag"
  FLAGS="$FLAGS $_flag"
  [ -z "$CAP0" ] && CAP0="$_cap"; CAPL="$_cap"
  _ac="${_cur#-}"; [ "$_ac" -le "$IDLE" ] 2>/dev/null && CUR_IDLE_SEEN=1
  [ "$CUR_IDLE_SEEN" = 1 ] && [ "$_cur" -gt "$IDLE" ] 2>/dev/null && CUR_ROSE=1
  N=$((N+1)); [ "$N" -lt 7 ] && sleep 10
done
emit ""
emit "## ACC LOGS (the daemon's own records -- written at zero extra cost as it runs)"
emit "flight_recorder=$([ -f "$DD/logs/flight.log" ] && echo "present ($(wc -l < "$DD/logs/flight.log" 2>/dev/null) lines)" || echo "absent (daemon flight-recorder not yet enabled -- only a live snapshot below)")"
[ -f "$DD/logs/flight.log" ] && { emit "-- flight.log (last 60) --"; tail -n 60 "$DD/logs/flight.log" 2>/dev/null | while read -r l; do emit "  $l"; done; }
for lg in write.log working-switches.log early-cap.log; do
  [ -f "$DD/logs/$lg" ] && { emit "-- $lg --"; tail -n 25 "$DD/logs/$lg" 2>/dev/null | while read -r l; do emit "  $l"; done; }
done
emit "-- init.log (tail 30; daemon init + actions) --"
tail -n 30 "$DD/logs/init.log" 2>/dev/null | while read -r l; do emit "  $l"; done
emit "init_logerr=$(grep -ciE 'error|fatal' "$DD/logs/init.log" 2>/dev/null)"
emit ""
emit "## VERDICT"
V=ok; HINT=
case " $FLAGS " in
  *' overcharge '*) V=overcharge; HINT="battery went past the cap while the switch was engaged -- the switch is not truly holding (faked/throttle/re-arm). Capture the control_node_inventory + charge_pump above to pick a hard cut.";;
  *' fake-idle '*) V=fake-idle; HINT="status says stopped but current still flows INTO the battery -- charge-pump fake-idle (pump=$PUMP). FIX: force input_suspend on this SoC; do not trust status/current of a bypass here.";;
  *' polarity '*) V=polarity; HINT="status and current sign disagree -- _DPOL/polarity miscalibrated; the limit may silently not enforce. FIX: recalibrate polarity from a steady Charging sample.";;
esac
if [ "$V" = ok ]; then
  if [ "$_pr" = 1 ] && [ -n "$CAP0" ] && [ "$CAPL" -lt "$(( CAP0 - 1 ))" ] 2>/dev/null; then
    if [ "$CAPL" -lt "$RESUME" ] 2>/dev/null; then V=stuck; HINT="cut + below resume + plugged but not charging back -- latch/charger de-negotiation. FIX: apsd_rerun/rerun_aicl on resume; check resume gate uses present not online."
    else V=passthrough-drain; HINT="battery falling while plugged -- the switch cuts USB passthrough (Sony-class). FIX: prefer battery_charging_enabled / lrc_enable; de-prioritise plain charging_enabled."; fi
  elif [ "$CUR_ROSE" = 1 ]; then V=rearm; HINT="charging current went idle then ROSE while the switch was engaged -- firmware re-armed (would overcharge). FIX: longer hold-verify + overshoot re-check; demote this switch."
  elif [ -z "$SW" ]; then V=no-switch; HINT="no charging switch configured/holding -- send the control_node_inventory; unknown nodes here are candidates to add."
  fi
fi
emit "failure=$V"
emit "evidence_flags=$(echo $FLAGS | tr ' ' ',' | sed 's/^,//;s/,,*/,/g')"
emit "cap_window=$CAP0->$CAPL pause=$PAUSE resume=$RESUME"
emit "fix_hint=$HINT"
emit "ok=1"
# make SHARING dead-simple: copy the bundle into Downloads (visible in any file manager / the Files app)
# so the user can share it in two taps -- no root, no adb. Also fix perms so a normal app can read it.
chmod 0644 "$OUT" 2>/dev/null || :
SHARED=""
for _dl in /sdcard/Download /storage/emulated/0/Download /sdcard/Downloads; do
  [ -d "$_dl" ] || continue
  cp "$OUT" "$_dl/" 2>/dev/null && { chmod 0644 "$_dl/${OUT##*/}" 2>/dev/null || :; SHARED="$_dl/${OUT##*/}"; break; }
done
echo ""
echo "==================================================================="
echo "  DIAGNOSTIC READY   ->   verdict: $V"
echo "  full bundle: $OUT"
[ -n "$SHARED" ] && echo "  IN YOUR DOWNLOADS: $SHARED"
echo ""
echo "  HOW TO SEND IT TO THE DEVS (pick one):"
[ -n "$SHARED" ] && echo "   - Open Files -> Downloads -> long-press 'acc-diag-$TS.txt' -> Share"
echo "   - Or copy ALL the text above and paste it to us"
echo "  No personal data is in it (no IMEI / serial / accounts / location)."
echo "==================================================================="
