#!/system/bin/sh

# AMPS - Adaptive Multi-device Probe & Selector.
# Root-only universal charge-control switch finder: probes any device, leak-verifies
# bypass/cut/drain switches + native %-limits, snapshots every write, restores on exit.
V=7.1.0
# Force C locale so status words, sort, and text tooling are deterministic regardless of the device locale.
export LC_ALL=C LANG=C
case "${1:-}" in --selftest|--version) _STONLY=1;; esac
SUPER=1; UNKNOWN=0
case "$*" in *--unknown*) UNKNOWN=1; SUPER=1;; esac
MODE=quick
case "$*" in *--complete*|*--all*|*--deep*) MODE=complete;; *--quick*|*--fast*) MODE=quick;; esac
WANT_UNPLUG=0; case "$*" in *--unplug*|*--accurate*) WANT_UNPLUG=1;; esac
[ "${UNKNOWN:-0}" = 1 ] && MODE=complete

# Bootstrap: detect root and re-exec via su/tsu/sudo (selftest/version skip elevation).
_uid="$(id -u 2>/dev/null)"
if [ "${_STONLY:-}" != 1 ] && [ -n "$_uid" ] && [ "$_uid" != 0 ] && [ -z "${ACC_REEXEC:-}" ]; then
  for _su in su tsu sudo; do command -v "$_su" >/dev/null 2>&1 && { echo "[*] AMPS: elevating to root via $_su ..."; exec "$_su" -c "ACC_REEXEC=1 sh '$0' $*"; }; done
  echo "!! AMPS needs root. Grant root in your root manager, then run:  su -c 'sh $0'"; exit 1
fi

PSY="${PSY:-/sys/class/power_supply}"
if [ -n "${TMPD:-}" ]; then :
elif [ "${_STONLY:-}" = 1 ]; then TMPD=/data/local/tmp
else
  for _t in /data/local/tmp "${TMPDIR:-}" "$HOME/.acc-compat" "$HOME" /tmp; do
    [ -n "$_t" ] || continue; mkdir -p "$_t" 2>/dev/null
    [ -d "$_t" ] && ( : > "$_t/.acc_wt" ) 2>/dev/null && { rm -f "$_t/.acc_wt" 2>/dev/null; TMPD="$_t"; break; }
  done
  [ -n "${TMPD:-}" ] || TMPD=/data/local/tmp
fi
# Shared plumbing paths - AccA reads these by name (artifact, cancel stop-flag, backup dir),
# so the on-disk identifiers stay "acc-compat" even though the engine is branded AMPS.
BK="${BK:-$TMPD/acc_compat_bk}"
STOPF="/data/local/tmp/.acc-compat-stop"
ART="/data/local/tmp/acc-compat-verified"
SNAP="$BK/snap.tsv"; DISC="$BK/disc.txt"; SNLIST="$BK/snlist"
SCHG="$BK/s_chg.tsv"; SUNP="$BK/s_unplug.tsv"; SHELD="$BK/s_held.tsv"; GENC="$BK/gen.tsv"
POLL=3; SETTLE=4; TO=5; MAXSEC=1020; MAX_NEW=20; MAX_CURR=10; MAX_GEN=12
DID=0; RESTORED=0; ACTIVE=1; SKIPALL=0; WARN=
BYPASS=; BYPASS_HELD=; LONGOK=; CUT=; DRAIN=; THROTTLE=; LEVELOK=; REASSERT=; WORKING=; NEWHITS=; GENHITS=; SUPERHITS=; ADDLINES=
STABMAP=; LEAKY=; STAB=
RESUMES=; STUCKS=; CFG_BYPASS=; CFG_CUT=; CFG_DRAIN=; CFG_LEVEL=
ENGDUMPED=0; OBS_UNPLUG=0; OBS_ENGAGE=0; EXTRA_DIRS=""
CUR_USABLE=1; CUR_FROZEN=0; BLINDV=0; V0=0; VNOISE=0; VDROP=25; VRISE=0; CTYPE0=; PROOF=current
BL_WHY=; BL_ONLINE=1; BL_CT=; BL_VLAST=0
TEACH_P=; TEACH_ON=; TEACH_OFF=; LEARNED=; BUILT=; MAX_TEACH=12; TEACHED=0; NLEARN=0; TBUILT=0
DISC_CAP=120
[ "${MODE:-quick}" = complete ] && { MAXSEC=1800; MAX_NEW=40; MAX_GEN=24; MAX_TEACH=24; MAX_CURR=16; DISC_CAP=300; }
OBSERVED_ONLY=; obs_n=0; GATE_FAILS=0; ACC_SW_NOW=; ACC_IDLE=; ACC_DRAIN=; ACC_IDLE1=; ACC_DRAIN1=; ACC_FALLBACK=; INCONC=
TRUST_RE='charging_enabled|battery_charging_enabled|charge_enabled|charging_enable|charge_enable|enable_charging|enable_charger|input_suspend|battery_input_suspend|op_disable_charge|disable_charging|charge_disable|disable_charger|batt_slate_mode|slate_mode|mmi_charging_enable|smart_charging_interruption|night_charging|bypass_charger|charger_bypass|charging_suspend_en|charger_limit_en|charger_control|force_charger_suspend|force_usb_suspend|charge_pause|charge_control_limit|restrict_chg|StartCharging_Test|StopCharging_Test|charging_call_state|slowly_charging|battery_charging_state'
SHELD2="$BK/s_held2.tsv"; TEACHC="$BK/teach.tsv"
EFFECT_RE='^(status|charge_type|charging_speed|current_now|current_avg|current_max|input_current_now|input_current_limited|voltage_now|voltage_avg|voltage_ocv|capacity|capacity_raw|temp|batt_temp|online|present|health|charge_counter|charge_full|charge_full_design|charge_now|time_to_full_now|time_to_empty_now|cycle_count|power_now|energy_now|resistance|soc|msoc|rsoc)$'
REARM_RE='current_max|constant_charge_current|input_current'
[ "${_STONLY:-}" = 1 ] || mkdir -p "$BK" 2>/dev/null

[ -n "${SRCDIR:-}" ] || case "$0" in */*) SRCDIR="${0%/*}";; esac
pick_outdir(){
  for d in \
    /sdcard/Download /storage/emulated/0/Download /data/media/0/Download \
    "${EXTERNAL_STORAGE:-}/Download" "$HOME/storage/downloads" "$HOME/storage/shared/Download" \
    /sdcard/Documents /sdcard /storage/emulated/0 /storage/self/primary \
    "${SRCDIR:-}" "$HOME" "$TMPD"; do
    [ -n "$d" ] && [ -d "$d" ] || continue
    if ( : > "$d/.acc_wtest" ) 2>/dev/null; then rm -f "$d/.acc_wtest" 2>/dev/null; printf '%s' "$d"; return; fi
  done
  printf '%s' "$TMPD"
}
if [ "${_STONLY:-}" != 1 ]; then
SRCDIR="${SRCDIR%/}"
OUTDIR="$(pick_outdir)"
TS="$(date +%Y%m%d-%H%M%S 2>/dev/null | tr -cd '0-9-')"
[ -n "$TS" ] || TS="$(cat /proc/uptime 2>/dev/null | cut -d. -f1 | tr -cd '0-9')"
[ -n "$TS" ] || TS=report
OUTBASE="acc-compat-report-$TS.txt"
OUT="$OUTDIR/$OUTBASE"
n=1; while [ -e "$OUT" ]; do OUTBASE="acc-compat-report-$TS-$n.txt"; OUT="$OUTDIR/$OUTBASE"; n=$((n+1)); done
OUT2="$TMPD/$OUTBASE"
: > "$OUT" 2>/dev/null; : > "$OUT2" 2>/dev/null; : > "$SNAP" 2>/dev/null; : > "$DISC" 2>/dev/null; : > "$SNLIST" 2>/dev/null
: > "$SCHG" 2>/dev/null; : > "$SUNP" 2>/dev/null; : > "$SHELD" 2>/dev/null; : > "$GENC" 2>/dev/null; : > "$BK/tested" 2>/dev/null
: > "$BK/graded" 2>/dev/null
: > "$BK/dead" 2>/dev/null; : > "$BK/stab" 2>/dev/null
REG="$BK/switches.tsv"; : > "$REG" 2>/dev/null
: > "$BK/combo.tsv" 2>/dev/null; : > "$BK/combo_seen" 2>/dev/null
: > "$SHELD2" 2>/dev/null; : > "$TEACHC" 2>/dev/null; : > "$BK/teach_combo.tsv" 2>/dev/null
rm -f /data/local/tmp/acc-compat-verified 2>/dev/null
fi

# Logging + timeout-guarded sysfs read/write helpers (rd/wr fall back to plain cat if no timeout binary).
log(){ [ "${PROFILE:-0}" = 1 ] && case "$*" in *"===="*) set -- "[t+$(( $(san "$(now)") - ${START:-0} ))s] $*";; esac; printf '%s\n' "$*"; printf '%s\n' "$*" >> "$OUT" 2>/dev/null; [ "$OUT" = "$OUT2" ] || printf '%s\n' "$*" >> "$OUT2" 2>/dev/null; }
canon(){ case "$1" in
    /sdcard/*) [ -d /storage/emulated/0 ] && printf '/storage/emulated/0/%s' "${1#/sdcard/}" || printf '%s' "$1";;
    /sdcard) [ -d /storage/emulated/0 ] && printf '/storage/emulated/0' || printf '%s' "$1";;
    *) printf '%s' "$1";; esac; }
friendly(){ case "$1" in
    /sdcard/*) printf 'Internal phone storage > %s' "$(printf '%s' "${1#/sdcard/}" | tr '/' '>')";;
    /storage/emulated/0/*) printf 'Internal phone storage > %s' "$(printf '%s' "${1#/storage/emulated/0/}" | tr '/' '>')";;
    /storage/self/primary/*) printf 'Internal phone storage > %s' "$(printf '%s' "${1#/storage/self/primary/}" | tr '/' '>')";;
    /sdcard|/storage/emulated/0|/storage/self/primary) printf 'Internal phone storage';;
    /data/local/tmp*) printf 'a hidden system folder -- use the COPY-TEXT method below instead';;
    *) printf '%s' "$1";; esac; }
warn(){ WARN="$WARN
  - $*"; log "  ! $*"; }
HAVE_TO=0; command -v timeout >/dev/null 2>&1 && HAVE_TO=1
acc_to(){ if [ "$HAVE_TO" = 1 ]; then timeout 15 "$@"; else "$@"; fi; }
rd(){ if [ "$HAVE_TO" = 1 ]; then timeout "$TO" cat "$1" 2>/dev/null; else cat "$1" 2>/dev/null; fi; }
rd1(){ if [ "$HAVE_TO" = 1 ]; then timeout 1 cat "$1" 2>/dev/null; else cat "$1" 2>/dev/null; fi; }
ex(){ [ -e "$1" ]; }
wr(){ ex "$1" || return 1; chmod u+w "$1" 2>/dev/null
  if [ "$HAVE_TO" = 1 ]; then printf '%s\n' "$2" | timeout "$TO" tee "$1" >/dev/null 2>&1
  else { printf '%s\n' "$2" > "$1"; } 2>/dev/null; fi; }
read1(){ rd "$1" 2>/dev/null | sed -n '1p' | cut -d' ' -f1; }
pclean(){ LC_ALL=C tr -dc ' -~'; }
pclean2(){ LC_ALL=C tr -dc ' -~\n'; }
read_st(){ rd "$BATT/status" | sed -n '1p' | pclean; }
# Numeric sanitizers: strip +/- sign and leading zeros (avoid octal in $(( )) ), magnitude, sign, polarity-normalize.
san(){ _s="$1"; case "$_s" in +*) _s="${_s#+}";; esac; _sg=; case "$_s" in -*) _sg=-; _s="${_s#-}";; esac; case "$_s" in ''|*[!0-9]*) echo 0; return;; esac; while :; do case "$_s" in 0[0-9]*) _s="${_s#0}";; *) break;; esac; done; [ "$_s" = 0 ] && _sg=; echo "$_sg$_s"; }
abs(){ v="${1#-}"; case "$v" in ''|*[!0-9]*) echo 0;; *) echo "$v";; esac; }
sgn(){ case "$1" in -*) echo n;; *) echo p;; esac; }

_norm(){ case "$2" in
    inverted) case "$1" in -*) printf '%s\n' "${1#-}";; 0) echo 0;; *) printf '%s\n' "-$1";; esac;;
    *) printf '%s\n' "$1";; esac; }

# Pure decision logic (no device writes): learn charge direction, classify charge state from sign+status,
# detect throttle/cut, rank and recommend the safest switch, cross-check polarity. All asserted in selftest.
learn_chgdir(){ case "$3" in 1) ;; *) echo "p low"; return;; esac
  case "$1" in
    Charging|charging) echo "$2 high";;
    Discharging|discharging|"Not charging"|"not charging")
      [ "$2" = p ] && echo "n high" || echo "p high";;
    *) echo "$2 med";; esac; }

classify_state(){ _csc="$(_norm "$3" "$4")"; _csp=0; { [ "$1" = 1 ] || [ "$2" = 1 ]; } && _csp=1; _csm="${_csc#-}"
  if [ "$_csp" = 0 ]; then
    case "$_csc" in -*) echo DISCHARGING; return;; esac
    [ "${_csm:-0}" -gt "$5" ] 2>/dev/null && echo MISLABEL || echo STANDBY; return; fi
  case "$_csc" in
    -*) if [ "$2" = 1 ] && [ "${_csm:-0}" -gt "$5" ] 2>/dev/null; then echo DRAIN; else echo CUT; fi;;
    *)  if [ "${_csm:-0}" -gt "$5" ] 2>/dev/null; then echo CHARGING
        elif [ "$2" = 1 ]; then echo BYPASS; else echo CUT; fi;; esac; }

classify_unheld(){
  _cuf="${1#-}"; case "$_cuf" in ''|*[!0-9]*) _cuf=0;; esac
  _cul="${2#-}"; case "$_cul" in ''|*[!0-9]*) _cul=0;; esac
  _cu3=0; case "$3" in Discharging|discharging|"Not charging"|"not charging") _cu3=1;; esac
  _cu4=0; case "$4" in Discharging|discharging|"Not charging"|"not charging") _cu4=1;; esac
  [ "$_cu3" = 1 ] && [ "$_cu4" = 1 ] && [ "$5" = 1 ] && { echo CUT; return; }
  [ "$8" = 1 ] && { echo NONE; return; }
  _cumax="$_cuf"; [ "$_cul" -gt "$_cumax" ] 2>/dev/null && _cumax="$_cul"
  _cumin="$_cuf"; [ "$_cul" -lt "$_cumin" ] 2>/dev/null && _cumin="$_cul"
  [ "$_cumax" -lt "$6" ] 2>/dev/null && [ "$_cumin" -gt "$7" ] 2>/dev/null && { echo THROTTLE; return; }
  echo NONE; }

reco_pick(){
  for _rp in "native-level:$1" "bypass:$2" "bypass:$4" "cut:$3" "drain:$5" "native-accepts:$6" "throttle:$7"; do
    _rpl="${_rp#*:}"
    [ -n "$_rpl" ] && { printf '%s|%s\n' "$_rpl" "${_rp%%:*}"; return 0; }
  done; }

_lblof(){ printf '%s' "$1" | cut -d'|' -f1; }
_clsof(){ printf '%s' "$1" | cut -d'|' -f2; }
# Finalist stress verdict (pure): from firmware-override count / hammer count / capacity delta while
# the level pick is engaged, decide LEAK (charged while held) > REARM (firmware kept overriding) > CLEAN.
fstress_verdict(){ _fov="${1:-0}"; _fn="${2:-0}"; _fcapd="${3:-0}"
  case "$_fov" in ''|*[!0-9]*) _fov=0;; esac
  case "$_fn" in ''|*[!0-9]*) _fn=0;; esac
  case "$_fcapd" in ''|*[!0-9-]*) _fcapd=0;; esac
  [ "$_fcapd" -gt 0 ] 2>/dev/null && { printf LEAK; return; }
  { [ "$_fov" -ge 3 ] 2>/dev/null && [ "$(( _fov * 3 ))" -ge "$_fn" ] 2>/dev/null; } && { printf REARM; return; }
  printf CLEAN; }
# Thermal-level node check (pure): a node whose _max sibling is a tiny integer (1..10) is the kernel's
# thermal charge-control interface (levels, not a switch) -- firmware owns it and re-arms it whenever
# the thermal engine is active, even if a short hammer on a cool phone reads clean.
fstress_thermal(){ case "${1:-}" in ''|*[!0-9]*) return 1;; esac
  [ "$1" -ge 1 ] 2>/dev/null && [ "$1" -le 10 ] 2>/dev/null; }
# Finalist stress-test: the switch that WINS the recommendation can still be an intermittently
# re-arming firmware node that happened to hold through its short passive window (the Mi A3's
# charge_control_limit passes as BYPASS one run and re-arms minutes later). Whatever class won
# (native-level, bypass, cut, drain), re-hammer its OFF value to provoke that re-arm; demote it
# (STUCKS, so pick_usable skips it on the re-pick) if the firmware overrides the writes or the
# pack keeps charging while engaged. Demote-only + snapshot-restored, so the worst case is the
# 2nd-best pick. Every signal here is sensor-independent (node readback, coulomb capacity, _max
# sibling), so this runs in BLIND sessions too - lying-sensor phones need it most. A cfg whose
# node does not appear in the label (a class-default fallback from cfg_lookup missing the entry)
# is SKIPPED rather than hammered: stressing the wrong node once read ccl's _max against
# input_suspend and demoted the best switch. Args: $1=winner label, $2="node on off" cfg line.
finalist_stress(){
  _fs_lbl="$1"; _fs_cfg="$2"
  [ -n "$_fs_lbl" ] && [ -n "$_fs_cfg" ] && [ "${STRESS_FINALIST:-1}" = 1 ] \
    && [ "${ACC_DEFER:-0}" = 0 ] && [ "$CAP" -ge 12 ] 2>/dev/null && [ "$CAP" -lt 96 ] 2>/dev/null && ! over || return 0
  case " ${_FS_SEEN:-} " in *" $_fs_lbl "*) return 0;; esac
  _FS_SEEN="${_FS_SEEN:-} $_fs_lbl"
  set -- $_fs_cfg
  _fs_node="$1"; _fs_ev="${3:-}"
  [ "$_fs_ev" = pcap ] && { _fs_ev=$(( CAP - 5 )); [ "$_fs_ev" -ge 96 ] 2>/dev/null && _fs_ev=95; }
  case "$_fs_ev" in ''|*[!0-9-]*) return 0;; esac
  [ -n "$_fs_node" ] && [ -w "$_fs_node" ] || return 0
  case "$_fs_lbl" in *"${_fs_node##*/}"*) ;; *) return 0;; esac
  _fs_orig="$(read1 "$_fs_node")"
  log ""
  log "==== FINALIST STRESS-TEST (re-hammer the winning pick to catch an intermittent re-arm) ===="
  snap_add "$_fs_node"
  recover_online 8 >/dev/null 2>&1; sleep 2
  _fs_c0="$(san "$(read1 "$BATT/capacity")")"; _fs_ov=0; _fs_i=0
  while [ "$_fs_i" -lt "${STRESS_HITS:-12}" ]; do
    stop_check; wr "$_fs_node" "$_fs_ev"; sleep 1
    [ "$(read1 "$_fs_node")" != "$_fs_ev" ] && _fs_ov=$(( _fs_ov + 1 ))
    _fs_i=$(( _fs_i + 1 )); over && break
  done
  _fs_c1="$(san "$(read1 "$BATT/capacity")")"; _fs_capd=$(( _fs_c1 - _fs_c0 ))
  [ -n "$_fs_orig" ] && wr "$_fs_node" "$_fs_orig"; recover_online 8 >/dev/null 2>&1
  _fs_v="$(fstress_verdict "$_fs_ov" "$_fs_i" "$_fs_capd")"
  log "  $_fs_lbl: hammered ${_fs_i}x -> firmware overrode ${_fs_ov}/${_fs_i}, capacity ${_fs_capd}% -> $_fs_v"
  if [ "$_fs_v" = CLEAN ] && fstress_thermal "$(read1 "${_fs_node}_max")"; then
    _fs_v="THERMAL-LEVEL (${_fs_node##*/}_max=$(read1 "${_fs_node}_max"): firmware-owned thermal levels, re-arms when the thermal engine is active even though the hammer read clean on this cool run)"
    log "  $_fs_lbl: $_fs_v"
  fi
  if [ "$_fs_v" != CLEAN ]; then
    log "    -> DEMOTED: does not hold under load ($_fs_v). Re-picking from the remaining finalists."
    REASSERT="$REASSERT|$_fs_lbl"; STUCKS="$STUCKS|$_fs_lbl"; RESUMES="$RESUMES|$_fs_lbl=STUCK"
    if [ "$_fs_lbl" = "$le_enf" ]; then
      LEVELOK="$(printf '%s\n' "$LEVELOK" | tr '|' '\n' | grep -vxF "$le_enf" 2>/dev/null | sed '/^$/d' | tr '\n' '|')"
      le_enf=; LVL_BY_ACC=0
    fi
    return 1
  fi
  log "    -> CONFIRMED: held under load; keeping it as the top pick."
  return 0; }

note_for(){ case "$1" in
  native-level)   echo "firmware limit -- reliable and no battery cycling when it truly enforces";;
  cut)            echo "hard cut -- most reliable, can never overcharge; battery cycles a little";;
  bypass)         echo "gentlest (battery idle), but verify it holds -- charge-pump phones can fake idle";;
  drain)          echo "stops charge but the battery slowly drains while plugged";;
  native-accepts) echo "firmware limit accepts the value but enforcement is unconfirmed -- re-test at your real cap";;
  throttle)       echo "only SLOWS charging -- may not hold a hard cap";;
  leaky)          echo "re-arms even when re-applied every poll -- can overcharge; not recommended";;
  reassert)       echo "re-arms (the daemon must keep re-applying it to hold)";;
  *)              echo "";;
esac; }

path_note(){ case "$1" in
  level|native-level|native-accepts) echo "";;
  cut|drain|bypass) echo "verified for the charger used in THIS test -- some phones (esp. fast USB-PD) change behaviour by charge path; if charging looks uncapped on another charger, re-run, and prefer a firmware %-limit if your phone has one";;
  *) echo "";;
esac; }

native_verdict(){
  [ "$3" = 1 ] && { echo accepts; return; }
  { [ "$1" = 0 ] && [ "${2:-3}" -lt 3 ] 2>/dev/null && [ "$4" = 0 ]; } && { echo verified; return; }
  echo accepts; }

is_pump(){ case "$1" in *bq2597*|*ln8000*|*ln8410*|*sc854*|*sc855*|*pca94*|*pca12*|*upm672*|*mp2762*|*nu2105*) return 0;; *) return 1;; esac; }
pump_conf(){
  [ "$1" = verified ] || { echo "$1"; return; }
  case "$2" in bypass|cut|drain) ;; *) echo verified; return;; esac
  [ "${4:-0}" = 1 ] && { echo verified; return; }
  is_pump "$3" && { echo pump-needs-long-test; return; }
  echo verified; }

artifact_kind(){
  [ "$1" = none ] && { echo no-switch; return; }
  { [ -n "$2" ] && [ "$2" != none ]; } && echo switch || echo no-switch; }

acca_sign(){ case "$1" in inverted) echo n;; normal) echo p;; esac; }
pol_conflict(){
  [ -n "$1" ] && [ "$3" = 1 ] && [ "$4" = high ] && [ "$1" != "$2" ] && { echo 1; return; }; echo 0; }

base_state_safe(){
  case "$2" in Charging|charging)
    case "$1" in DRAIN|DISCHARGING|CUT) echo CHARGING; return;; esac;;
  esac
  printf '%s' "$1"; }

defer_to_acc(){
  [ -n "$1" ] && return
  [ -n "$2" ] || return
  _dn="${2##*/}"
  case "|$3|$4|" in *"$_dn"*) return;; esac
  printf '%s' "$2"; }

# Self-test: pure polarity/state/pick assertions, zero device I/O. Run with --selftest; exits nonzero on any fail.
selftest(){ _sp=0; _sf=0
  _ck(){ if [ "$2" = "$3" ]; then _sp=$((_sp+1)); else echo "  FAIL $1: got='$2' want='$3'"; _sf=$((_sf+1)); fi; }
  echo "== AMPS v$V self-test (pure polarity + state) =="
  _ck chg+pos    "$(learn_chgdir Charging p 1)"        "p high"
  _ck chg+neg    "$(learn_chgdir Charging n 1)"        "n high"
  _ck dis+pos    "$(learn_chgdir Discharging p 1)"     "n high"
  _ck dis+neg    "$(learn_chgdir Discharging n 1)"     "p high"
  _ck notchg+pos "$(learn_chgdir 'Not charging' p 1)"  "n high"
  _ck idle+neg   "$(learn_chgdir Idle n 1)"            "n med"
  _ck full+pos   "$(learn_chgdir Full p 1)"            "p med"
  _ck unk+pos    "$(learn_chgdir Unknown p 1)"         "p med"
  _ck belowthr   "$(learn_chgdir Charging p 0)"        "p low"
  _ck n_charge   "$(classify_state 1 1 500 normal 10)"    CHARGING
  _ck n_cut      "$(classify_state 1 0 0 normal 10)"      CUT
  _ck n_bypass   "$(classify_state 1 1 0 normal 10)"      BYPASS
  _ck n_drain    "$(classify_state 1 1 -500 normal 10)"   DRAIN
  _ck n_dischg   "$(classify_state 0 0 -500 normal 10)"   DISCHARGING
  _ck n_standby  "$(classify_state 0 0 0 normal 10)"      STANDBY
  _ck n_mislabel "$(classify_state 0 0 500 normal 10)"    MISLABEL
  _ck i_charge   "$(classify_state 1 1 -500 inverted 10)" CHARGING
  _ck i_drain    "$(classify_state 1 1 500 inverted 10)"  DRAIN
  _ck i_dischg   "$(classify_state 0 0 500 inverted 10)"  DISCHARGING
  _ck i_mislabel "$(classify_state 0 0 -500 inverted 10)" MISLABEL
  _ck plus200dis "$(classify_state 1 1 200 inverted 10)"  DRAIN
  _ck cutinload  "$(classify_state 1 0 -500 normal 10)"   CUT
  _ck icutinload "$(classify_state 1 0 500 inverted 10)"  CUT
  _ck cutinload0 "$(classify_state 1 0 -5 normal 10)"     CUT
  _ck u_throttle   "$(classify_unheld -600 -700 Charging Charging 0 1000 10 0)"      THROTTLE
  _ck u_transient  "$(classify_unheld -300 -2000 Charging Charging 0 1000 10 0)"     NONE
  _ck u_noeffect   "$(classify_unheld -1900 -2000 Charging Charging 0 1000 10 0)"    NONE
  _ck u_statuscut  "$(classify_unheld 5 5 Discharging 'Not charging' 1 1000 10 0)"   CUT
  _ck u_blind_cut  "$(classify_unheld 0 0 'Not charging' 'Not charging' 1 1000 10 1)" CUT
  _ck u_blind_nthr "$(classify_unheld -600 -700 Charging Charging 0 1000 10 1)"      NONE
  _ck u_idlefloor  "$(classify_unheld -600 -5 Charging Charging 0 1000 10 0)"        NONE
  _ck reco_native     "$(reco_pick nat bx cx ux dx ax tx)"     "nat|native-level"
  _ck reco_bypassV    "$(reco_pick '' bx cx ux dx ax tx)"      "bx|bypass"
  _ck reco_cut        "$(reco_pick '' '' cx '' dx ax tx)"      "cx|cut"
  _ck reco_unverif_bp "$(reco_pick '' '' '' ux dx ax tx)"      "ux|bypass"
  _ck reco_drain      "$(reco_pick '' '' '' '' dx ax tx)"      "dx|drain"
  _ck reco_naccept    "$(reco_pick '' '' '' '' '' ax tx)"      "ax|native-accepts"
  _ck reco_throttle   "$(reco_pick '' '' '' '' '' '' tx)"      "tx|throttle"
  _ck reco_none       "$(reco_pick '' '' '' '' '' '' '')"      ""
  _ck reco_bypassV_beats_cut       "$(reco_pick '' bx cx '' '' '' '')"  "bx|bypass"
  _ck reco_bypass_beats_cut        "$(reco_pick '' '' cx bx '' '' '')"  "bx|bypass"
  _ck reco_unverified_bypass_only  "$(reco_pick '' '' '' bx '' '' '')"  "bx|bypass"
  _ck note_cut       "$(note_for cut)"     "hard cut -- most reliable, can never overcharge; battery cycles a little"
  _ck note_bypass_y  "$([ -n "$(note_for bypass)" ] && echo y)"  y
  _ck note_unknown   "$(note_for zzz)"     ""
  _ck split_lbl  "$(_lblof 'charge_control_limit=6|bypass')"  "charge_control_limit=6"
  _ck split_cls  "$(_clsof 'charge_control_limit=6|bypass')"  bypass
  _ck nv_verified "$(native_verdict 0 0 0 0)"  verified
  _ck nv_blind    "$(native_verdict 0 0 1 0)"  accepts
  _ck nv_rearm    "$(native_verdict 0 0 0 1)"  accepts
  _ck nv_charging "$(native_verdict 1 3 0 0)"  accepts
  _ck field_enforced "$(native_verdict 0 1 0 0)" verified
  _ck field_accepts  "$(native_verdict 1 1 0 0)" accepts
  _ck pnote_level    "$(path_note level)"  ""
  _ck pnote_cut_y    "$([ -n "$(path_note cut)" ] && echo y)"    y
  _ck pnote_drain_y  "$([ -n "$(path_note drain)" ] && echo y)"  y
  _ck pump_bypass_longok  "$(pump_conf verified bypass ln8000_charger 1)"      verified
  _ck pump_bypass_short   "$(pump_conf verified bypass ln8000_charger 0)"      pump-needs-long-test
  _ck pump_cut_short      "$(pump_conf verified cut bq2597x 0)"               pump-needs-long-test
  _ck pump_cut_longok     "$(pump_conf verified cut bq2597x 1)"               verified
  _ck pump_nonpump_cut    "$(pump_conf verified cut qcom,qpnp-smb5 0)"        verified
  _ck pump_nonpump_byp    "$(pump_conf verified bypass qti_battery_charger 0)" verified
  _ck pump_level_passthru "$(pump_conf verified native-level ln8000 0)"       verified
  _ck art_verified    "$(artifact_kind verified 'x 0 1')"               switch
  _ck art_needstest   "$(artifact_kind needs-test 'x 0 1')"             switch
  _ck art_pump        "$(artifact_kind pump-needs-long-test 'x 0 1')"   switch
  _ck art_latch       "$(artifact_kind latch-needs-rearm 'x 0 1')"      switch
  _ck art_unconf      "$(artifact_kind unconfirmed 'x 0 1')"            switch
  _ck art_history     "$(artifact_kind from-ACC-history 'x 0 1')"       switch
  _ck art_none        "$(artifact_kind none 'x 0 1')"                   no-switch
  _ck art_nosuggest   "$(artifact_kind verified '')"                    no-switch
  _ck art_suggnone    "$(artifact_kind needs-test none)"               no-switch
  _ck pol_oneplus_quick "$(pol_conflict n p 1 high)"   1
  _ck pol_oneplus_deep  "$(pol_conflict p n 1 high)"   1
  _ck pol_agree         "$(pol_conflict p p 1 high)"   0
  _ck pol_lowcur        "$(pol_conflict n p 0 high)"   0
  _ck pol_lowconf       "$(pol_conflict n p 1 low)"    0
  _ck pol_noacca        "$(pol_conflict '' p 1 high)"  0
  _ck accasign_inv      "$(acca_sign inverted)"        n
  _ck accasign_normal   "$(acca_sign normal)"          p
  _ck accasign_unknown  "x$(acca_sign unknown)"        x
  _ck accasign_empty    "x$(acca_sign '')"             x
  _ck accasign_garbage  "x$(acca_sign weird-value)"    x
  _ck bss_drain_chg     "$(base_state_safe DRAIN Charging)"        CHARGING
  _ck bss_disch_full    "$(base_state_safe DISCHARGING Full)"      DISCHARGING
  _ck bss_cut_chg       "$(base_state_safe CUT Charging)"          CHARGING
  _ck bss_drain_disch   "$(base_state_safe DRAIN Discharging)"     DRAIN
  _ck bss_charging_chg  "$(base_state_safe CHARGING Charging)"     CHARGING
  _ck bss_bypass_chg    "$(base_state_safe BYPASS Charging)"       BYPASS
  _ck bss_drain_unknown "$(base_state_safe DRAIN Unknown)"         DRAIN
  _ck defer_real        "x$(defer_to_acc 1 /sys/x/mmi_charging_enable '' '')"   x
  _ck defer_noacc       "x$(defer_to_acc '' '' '' '')"                          x
  _ck defer_fires       "$(defer_to_acc '' /sys/x/mmi_charging_enable '' '')"   /sys/x/mmi_charging_enable
  _ck defer_stuck       "x$(defer_to_acc '' /sys/x/mmi_charging_enable '|mmi_charging_enable=STUCK' '')"  x
  _ck defer_reassert    "x$(defer_to_acc '' /sys/x/input_suspend '' '|input_suspend')"                    x
  _ck san_octal      "$(san 00500)"   500
  _ck san_octal2     "$(san 0010)"    10
  _ck san_plus       "$(san +5)"      5
  _ck san_negz       "$(san -00040)"  -40
  _ck san_zero       "$(san 0)"       0
  _ck san_plain      "$(san 435486)"  435486
  _ck san_neg        "$(san -250)"    -250
  _ck lcd_full_p     "$(learn_chgdir Full p 1)"          "p med"
  _ck lcd_disch_p    "$(learn_chgdir Discharging p 1)"   "n high"
  _ck lcd_charging   "$(learn_chgdir Charging p 1)"      "p high"
  _ck fs_clean       "$(fstress_verdict 0 12 0)"    CLEAN
  _ck fs_clean_stray "$(fstress_verdict 1 12 0)"    CLEAN
  _ck fs_clean_short "$(fstress_verdict 2 12 0)"    CLEAN
  _ck fs_rearm_all   "$(fstress_verdict 12 12 0)"   REARM
  _ck fs_rearm_qtr   "$(fstress_verdict 4 12 0)"    REARM
  _ck fs_rearm_min   "$(fstress_verdict 3 12 0)"    CLEAN
  _ck fs_leak_flat   "$(fstress_verdict 0 12 1)"    LEAK
  _ck fs_leak_over   "$(fstress_verdict 12 12 2)"   LEAK
  _ck fs_leak_neg    "$(fstress_verdict 0 12 -1)"   CLEAN
  _ck fs_bad_input   "$(fstress_verdict x y z)"     CLEAN
  _ck ft_a3_ccl      "$(fstress_thermal 6 && echo T || echo F)"    T
  _ck ft_edge_lo     "$(fstress_thermal 1 && echo T || echo F)"    T
  _ck ft_edge_hi     "$(fstress_thermal 10 && echo T || echo F)"   T
  _ck ft_pctcap      "$(fstress_thermal 100 && echo T || echo F)"  F
  _ck ft_above       "$(fstress_thermal 11 && echo T || echo F)"   F
  _ck ft_zero        "$(fstress_thermal 0 && echo T || echo F)"    F
  _ck ft_empty       "$(fstress_thermal '' && echo T || echo F)"   F
  _ck ft_garbage     "$(fstress_thermal x9 && echo T || echo F)"   F
  echo "== self-test: $_sp passed, $_sf failed =="
  [ "$_sf" = 0 ]; }

case "${1:-}" in
  --selftest) selftest; exit $?;;
  --version)  echo "AMPS v$V (Adaptive Multi-device Probe & Selector)"; exit 0;;
  --probe)    PROBE=1;;
esac
# Misc helpers: 3-sample median, pre-write snapshot, value-class checks, runtime/deadline clock.
med3(){ a="$1"; b="$2"; c="$3"
  if [ "$a" -le "$b" ]; then
    if [ "$b" -le "$c" ]; then echo "$b"; elif [ "$a" -le "$c" ]; then echo "$c"; else echo "$a"; fi
  else
    if [ "$a" -le "$c" ]; then echo "$a"; elif [ "$b" -le "$c" ]; then echo "$c"; else echo "$b"; fi
  fi; }
snap_add(){ ex "$1" || return 0; grep -qxF "$1" "$SNLIST" 2>/dev/null && return 0
  printf '%s\n' "$1" >> "$SNLIST"; printf '%s\t%s\n' "$1" "$(rd "$1" | sed -n '1p')" >> "$SNAP"; }
now(){ rd /proc/uptime | cut -d. -f1; }
START="$(san "$(now)")"
over(){ n="$(san "$(now)")"; [ "$n" -gt 0 ] || return 1; [ $(( n - START )) -ge "$MAXSEC" ]; }

NAME_RE='charg|enable|suspend|disable|bypass|ichg|fcc|aicr|input_current|constant_charge|current_max|chg_en|wired_chg|charge_disable|charging_enable|batt_full|stop_level|start_level|control_(end|start)|upper_limit|mmi|slate_mode|night_charg|charger_limit|chg_enable|smart_charging|batt_protect|charging_state|charge_pause|slow_charg'
DENY_RE='uevent|/type$|/present$|/status$|/health$|_raw|counter|_now$|_avg$|charge_full|cycle|voltage|temp|therm|time_to|/power/|subsystem|charge_log|safety_timer|factory|test_mode|/fg_|_fg/|batt_range|update_now|/capacity$|hiz|power_path|store_mode|regulator|/otg|vbus|wireless_boost|cool_mode|cool_down|system_temp|temp_cool|step_charg|restricted|brightness|/led|of_node|/driver/|/module/|/wakeup|/sections/|/notes/|modalias|driver_override|nafg|daemon_disable|/device/modalias|/device/power_supply/'
DIFF_DENY='charge_stats|charge_details|charger_state|charge_stage|/soc$|/msoc$|capacity_level|charge_counter|time_|/online$|input_current_settled|_uv$|_ua$|monotonic|charge_type|charging_speed|charge_deadline|ttf|_dump$|registers|fan_level|dock_'
DDIRS="$PSY /sys/class/qcom-battery /sys/class/oplus_chg /sys/class/oplus_chg/battery /sys/class/hw_power /sys/class/battchg_ext /sys/class/asuslib /sys/class/cms_class /sys/class/nubia_charge /sys/kernel/nubia_charge /proc/mtk_battery_cmd /sys/devices/platform/charger /sys/devices/platform/mt-battery /sys/devices/platform/google,charger /sys/devices/platform/soc/soc:google,charger /sys/devices/platform/lge-unified-nodes /sys/devices/platform/huawei_charger /sys/module/qpnp_adaptive_charge/parameters /sys/module/lge_battery/parameters"
DDIRS_ALL="$DDIRS"

state_dump(){
  : > "$1"
  { for d in $DDIRS_ALL; do ex "$d" || continue
      { if [ "$HAVE_TO" = 1 ]; then timeout "$TO" find -L "$d" -maxdepth 2 -type f 2>/dev/null; else find -L "$d" -maxdepth 2 -type f 2>/dev/null; fi; } | awk '!seen[$0]++' | sed -n '1,300p' | while read -r f; do
        printf '%s' "$f" | grep -Eq "$DENY_RE" && continue
        printf '%s' "$f" | grep -Eq "$DIFF_DENY" && continue
        v="$(rd1 "$f" | sed -n '1p' | tr '|\t' '__' | pclean | cut -c1-40)"
        printf '%s|%s\n' "$f" "$v"
      done
    done; } | sort -u > "$1"
}
diff_pairs(){ awk -F'|' 'NR==FNR{a[$1]=$2;next} ($1 in a)&&a[$1]!=$2{print $1"|"a[$1]"|"$2}' "$1" "$2" 2>/dev/null; }
DANGER_RE='ship|power_off|poweroff|reboot|shut_down|factory|calib|fw_|firmware|flash|erase|wipe|moisture|water_det|usb_sel|cc_toggle|typec|port_mode|role_|_role|otg|boost|vbus|fastchg_fw|reverse|tx_mode|wireless_tx|wls_|usbpd|pd_active|pdo|vconn|cc_orient|jeita|vfloat|float_volt|_fv$|iterm|aging|atest|mtbf|fuelgauge|cw201|max172|nvmem|nvm|sram|otp|profile|model_data|regulator|power_path|hiz|parallel_disable'

val_class(){ case "$1" in
    on|off|enabled|disabled|true|false) return 0;;
    ''|*[!0-9]*) return 1;;
    [0-9]|[0-9][0-9]|[0-9][0-9][0-9]|[0-9][0-9][0-9][0-9]|[0-9][0-9][0-9][0-9][0-9]|[0-9][0-9][0-9][0-9][0-9][0-9]|[0-9][0-9][0-9][0-9][0-9][0-9][0-9]|[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]) return 0;;
    *) return 1;; esac; }
rel_score(){ case "$1" in
    *charging_enabled*|*battery_charging_enabled*|*charge_enabled*|*charging_enable*|*charge_enable*|*enable_charg*|*input_suspend*|*disable_charg*|*charge_disable*|*batt_slate*|*mmi_charging*|*force_charger_suspend*|*force_usb_suspend*|*bypass_charg*|*charger_bypass*) echo 4;;
    *charg*|*chg*) echo 3;;
    *suspend*|*enable*|*disable*|*slate*|*mmi*|*protect*|*bypass*) echo 2;;
    *) echo 1;; esac; }
bool_like(){ case "$1" in 0|1|on|off|enabled|disabled|true|false|low|high) return 0;; *) return 1;; esac; }
flip_val(){ case "$1" in 0) echo 1;; 1) echo 0;; on) echo off;; off) echo on;; enabled) echo disabled;; disabled) echo enabled;; true) echo false;; false) echo true;; low) echo high;; high) echo low;; *) echo "";; esac; }
# Write-safety gates: a candidate node must clear DANGER/DENY/SUPERDENY/EFFECT and match NAME/TRUST
# before AMPS ever writes it. Unknown vendor nodes are reported, never written (--unknown refuses the name DB).
safe_to_write(){ st_bn="${1##*/}"
  [ "${UNKNOWN:-0}" = 1 ] && return 1
  printf '%s' "$1" | grep -Eiq "$DANGER_RE" && return 1
  printf '%s' "$st_bn" | grep -Eq "$EFFECT_RE" && return 1
  printf '%s' "$st_bn" | grep -Eq "^($TRUST_RE)$" && return 0
  return 1; }
SUPERDENY_RE='cc_soc|/soc$|_soc$|msoc|rsoc|soc_now|soc_ajust|soc_adjust|coulomb|/bms|_bms|-bms|/fuel|gauge|/fg_|_fg$|cc_step|cv_step|usb_role|typec|moisture|/lpd|pd_disabled|pd_current|pd_curr|pdo_|pps_|apdo|/vreg|temp|therm|batt_verify|pagenumber|authen'
SHAPEDENY_RE='reset|reboot|shutdown|poweroff|/display|panel|backlight|/lcd|modem|/radio|baseband|/fan|/gpio|/led|/key|vibrat|haptic|camera|torch|/cpu|/clk|/dma|/rtc|/nfc|/wlan|/wifi|/bt_|bluetooth|/sim|sdcard|/sec_|sysrq|crash|panic|/dump|recovery|fastboot|download|/audio|/sound|/sensor|proximity|fingerprint|host_mode|dual_role|peripheral|latch|fuse|efuse|oneshot|one_shot|write_protect|wprotect|_lock$|/lock|perm_|_perm$|align|sbu|force_epp|contaminant|accessor|sink_current|sdp_enum|update_sdp|qien|aacp|aafv|aacr|cal_mode|cal_state|sr_state|_filtered|has_wlc|txdone|rxdone|txbusy|aicl_delay|aicl_icl|irq_hpd|trickle_dry|trickle_version|trickle_cnt_thr|/count$|usb_limit|/compatibility|log_current|resistance_id|/wireless/device/|/device/frs|gpp_enhanced|mitigate_threshold|negopower|rxlen|dc_icl|short_c_|call_mode|time_zone|em_mode|endurance|bcc_|ufcs|voocchg|ppschg|quick_mode|chg_i2c|i2c_err|notify_code|batt_cb|smartchg|parallel_chg'
super_safe(){ _ssbn="${1##*/}"
  printf '%s' "$1"     | grep -Eiq "$DANGER_RE"    && return 1
  printf '%s' "$_ssbn" | grep -Eq  "$EFFECT_RE"    && return 1
  printf '%s' "$1"     | grep -Eq  "$DENY_RE"      && return 1
  printf '%s' "$1"     | grep -Eiq "$SUPERDENY_RE" && return 1
  printf '%s' "$1"     | grep -Eiq "$SHAPEDENY_RE" && return 1
  printf '%s' "$_ssbn" | grep -Eqi "$NAME_RE"      || return 1
  return 0; }
shape_safe(){ _shpb="${1##*/}"
  printf '%s' "$1"     | grep -Eiq "$DANGER_RE"    && return 1
  printf '%s' "$_shpb" | grep -Eq  "$EFFECT_RE"    && return 1
  printf '%s' "$1"     | grep -Eq  "$DENY_RE"      && return 1
  printf '%s' "$1"     | grep -Eiq "$SUPERDENY_RE" && return 1
  printf '%s' "$1"     | grep -Eiq "$SHAPEDENY_RE" && return 1
  return 0; }
flag_observe(){ case "|$OBSERVED_ONLY|" in *"|$1 "*) :;; *) OBSERVED_ONLY="$OBSERVED_ONLY|$1 $2"; obs_n=$((obs_n+1));; esac; }

# Native-charging restore engine: re-assert factory charging, recover a charger that dropped offline,
# and snapshot-restore every touched node on exit (trap-driven, with a watchdog backstop).
emit_native(){
cat <<EOF
$PSY/battery/input_suspend 0
$PSY/usb/input_suspend 0
$PSY/dc/input_suspend 0
/sys/class/qcom-battery/input_suspend 0
$PSY/battery/battery_input_suspend 0
/sys/class/battchg_ext/*input_suspend 0
$PSY/battery/charging_enabled 1
$PSY/battery/battery_charging_enabled 1
/sys/class/qcom-battery/charging_enabled 1
$PSY/battery/mmi_charging_enable 1
/sys/class/oplus_chg/battery/mmi_charging_enable 1
/sys/oplus/battery/mmi_charging_enable 1
$PSY/battery/op_disable_charge 0
$PSY/battery/batt_slate_mode 0
$PSY/battery/night_charging 0
/sys/class/qcom-battery/night_charging 0
$PSY/battery_ext/smart_charging_interruption 0
/sys/class/cms_class/disable_charge 0
/proc/oplus-votable/CHG_DISABLE/force_active 0
/sys/class/hw_power/charger/charge_data/enable_charger 1
/sys/devices/platform/huawei_charger/enable_charger 1
/sys/class/asuslib/charging_suspend_en 0
/sys/class/asuslib/charger_limit_en 0
/sys/devices/platform/charger/bypass_charger 0
/sys/devices/platform/mt-battery/disable_charger 0
/sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,battery_charger/force_charger_suspend 0
/sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:mmi,qti-glink-charger/force_usb_suspend 0
/sys/devices/platform/google,charger/charge_start_level 99
/sys/devices/platform/soc/soc:google,charger/charge_start_level 99
/sys/devices/platform/google,charger/charge_stop_level 100
/sys/devices/platform/soc/soc:google,charger/charge_stop_level 100
$PSY/battery/charge_control_end_threshold 100
$PSY/battery/charge_control_limit 0
$PSY/charger/charge_control_limit 0
$PSY/battery/batt_full_capacity 100
/sys/module/qpnp_adaptive_charge/parameters/upper_limit -1
/sys/module/lge_battery/parameters/charge_stop_level 100
$PSY/usb/apsd_rerun 1
/sys/class/qcom-battery/apsd_rerun 1
$PSY/battery/rerun_aicl 1
EOF
}
defaults_native(){
  emit_native | while read -r dn_p dn_v; do
    [ -n "$dn_p" ] || continue
    for dn_f in $dn_p; do ex "$dn_f" || continue; snap_add "$dn_f"; wr "$dn_f" "$dn_v"; done
  done
  ex /proc/mtk_battery_cmd/current_cmd && { snap_add /proc/mtk_battery_cmd/current_cmd; wr /proc/mtk_battery_cmd/current_cmd "0 0"; }
  ex /proc/mtk_battery_cmd/en_power_path && { snap_add /proc/mtk_battery_cmd/en_power_path; wr /proc/mtk_battery_cmd/en_power_path 1; }
  return 0
}

recover_online(){
  _ro_budget="${1:-40}"; _ro_force="${2:-0}"; _ro_i=0; _ro_round=0
  present_now || return 1
  online_now && return 0
  while [ "$_ro_i" -lt "$_ro_budget" ]; do
    [ "$_ro_force" = 1 ] || { [ "$_ro_round" -ge 1 ] && over && break; }
    _ro_round=$((_ro_round+1))
    for _rsp in $BATT/input_suspend $PSY/usb/input_suspend $PSY/dc/input_suspend $BATT/battery_input_suspend /sys/class/qcom-battery/input_suspend $BATT/charge_control_limit; do
      [ -w "$_rsp" ] && wr "$_rsp" 0 2>/dev/null
    done
    for _ren in $BATT/charging_enabled $BATT/battery_charging_enabled $BATT/mmi_charging_enable /sys/class/oplus_chg/battery/mmi_charging_enable; do
      [ -w "$_ren" ] && wr "$_ren" 1 2>/dev/null
    done
    for _rcd in $BATT/charge_disable $PSY/sm7250_bms/charge_disable; do
      [ -w "$_rcd" ] && wr "$_rcd" 0 2>/dev/null
    done
    if [ "$_ro_round" -ge 2 ] && [ -w "$BATT/input_suspend" ]; then
      wr "$BATT/input_suspend" 1 2>/dev/null; sleep 1; wr "$BATT/input_suspend" 0 2>/dev/null; _ro_i=$((_ro_i+1))
    fi
    for _ron in $PSY/usb/apsd_rerun /sys/class/qcom-battery/apsd_rerun $BATT/rerun_aicl /sys/class/qcom-battery/rerun_aicl; do
      [ -w "$_ron" ] && wr "$_ron" 1 2>/dev/null
    done
    _roj=0
    while [ "$_roj" -lt 8 ] && [ "$_ro_i" -lt "$_ro_budget" ]; do
      online_now && return 0
      sleep 1; _roj=$((_roj+1)); _ro_i=$((_ro_i+1))
    done
  done
  [ -w "$BATT/input_suspend" ] && { _rfin="$(read1 "$BATT/input_suspend")"; [ "$_rfin" = 1 ] && wr "$BATT/input_suspend" 0 2>/dev/null; }
  online_now
}

restore(){
  [ "$RESTORED" = 1 ] && return; RESTORED=1
  trap '' INT TERM HUP
  ( sleep 130; defaults_native 2>/dev/null; sleep 5; kill -9 $$ 2>/dev/null ) >/dev/null 2>&1 & RWDOG=$!
  if [ "$DID" = 1 ]; then
    log ""; log "===== RESTORING (replaying snapshot to original values) ====="
    defaults_native
    fail=0; rbfails=
    if [ -s "$SNAP" ]; then
      rpass=0
      while [ $rpass -lt 3 ]; do
        rpass=$((rpass+1)); fail=0; rbfails=
      while IFS="	" read -r p v; do
        [ -n "$p" ] || continue
        ex "$p" || continue
        wr "$p" "$v"
        case "$p" in
          */input_current_limited|*/bcc_parms|*/battery_rm|*/resistance|*/ssoc_details|*/charger_temp|*/die_health) continue;;
        esac
        rb="$(rd "$p" | sed -n '1p')"
        [ "$rb" = "$v" ] || { fail=$((fail+1)); rbfails="$rbfails ${p##*/}(want=$v got=$rb)"; }
      done < "$SNAP"
      [ "$fail" -eq 0 ] && break
      sleep 1
      done
    fi
    defaults_native; sleep 1
    if plugged 2>/dev/null; then
      fst="$(rd "$BATT/status" | sed -n '1p' | pclean)"
      case "$fst" in
        Charging|Full) :;;
        *) recover_online 45 1 >/dev/null 2>&1
           fst="$(rd "$BATT/status" | sed -n '1p' | pclean)"
           case "$fst" in
             Charging|Full) log "  charging re-onlined after an APSD/AICL re-kick (firmware had dropped to online=0)";;
             *) log "  ! charging did NOT resume after restore (status=$fst) -- the charger may have dropped negotiation. Try a SLOW/standard USB charger, or REBOOT to clear it.";;
           esac ;;
      esac
    fi
    [ "${ACC_WAS:-0}" = 1 ] && for c in /data/adb/vr25/acc/acca /dev/.vr25/acc/acca acca acc; do command -v "$c" >/dev/null 2>&1 && { acc_to "$c" -D restart >/dev/null 2>&1 || acc_to "$c" --daemon restart >/dev/null 2>&1 || acc_to "$c" -D start >/dev/null 2>&1; break; }; done
    [ "${DJS_WAS:-0}" = 1 ] && for _dc in "${DJS_BIN:-}" /data/adb/vr25/djs/djs /dev/.vr25/djs/djs djs; do [ -n "$_dc" ] && command -v "$_dc" >/dev/null 2>&1 && { acc_to "$_dc" --daemon start >/dev/null 2>&1 || acc_to "$_dc" start >/dev/null 2>&1 || acc_to "$_dc" --daemon restart >/dev/null 2>&1; break; }; done
    if [ "$fail" -gt 0 ]; then
      _swbn="${SUGGEST%% *}"; _swbn="${_swbn##*/}"
      case " $rbfails " in
        *" $_swbn("*)
          log "  ! $fail node(s) did not read back to original:${rbfails} -- charging re-asserted; REBOOT if it still looks stuck."
          [ -f "$ART" ] && [ -n "$_swbn" ] && { sed -i 's/^conf=verified$/conf=needs-test/' "$ART" 2>/dev/null; log "  ! recommended switch ($_swbn) restore unclean -> verified downgraded to needs-test (AccA re-tests before locking)"; } ;;
        *)
          log "  note: $fail firmware-volatile node(s) kept the charger's own values:${rbfails} -- these actuators follow the charge state (normal, harmless); the recommended switch restored clean, artifact left verified." ;;
      esac
    fi
    if plugged 2>/dev/null; then
      _fsusp="$(read1 "$BATT/input_suspend" 2>/dev/null)"; _fst2="$(rd "$BATT/status" | sed -n '1p' | pclean)"
      case "$_fst2" in
        Charging|Full) :;;
        *) if [ "${_fsusp:-0}" = 1 ]; then
             log "  note: it shows 'not charging' because ACC is back in control and is HOLDING your %-cap (input_suspend=1) -- this is normal ACC behaviour, NOT a fault and NOT the tester; ACC resumes on its own per your pause/resume capacity (here ~resume_capacity%)."
           else
             log "  note: charger is plugged but not charging (input_suspend=0) -- this charger can need a physical UNPLUG + RE-PLUG to re-negotiate; do that once, or reboot, if it stays this way."
           fi ;;
      esac
    fi
    log "===== restored${ACC_WAS:+ + ACC restarted} (REBOOT if charging looks stuck) ====="
  fi
  chmod 0644 "$OUT" 2>/dev/null
  if command -v am >/dev/null 2>&1; then
    am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$OUT" >/dev/null 2>&1
    am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$OUTDIR" >/dev/null 2>&1
  fi
  # Disarm both watchdogs INCLUDING their forked sleep children: killing only the subshell
  # leaves its running sleep orphaned with an inherited copy of our stdout, and a pipe reader
  # (AccA's live log stream) then waits the full sleep (~2 min of frozen UI) before seeing EOF.
  for _wd in "${WATCHDOG-}" "${RWDOG-}"; do
    [ -n "$_wd" ] || continue
    for _wc in $(pgrep -P "$_wd" 2>/dev/null); do kill -9 "$_wc" 2>/dev/null; done
    kill -9 "$_wd" 2>/dev/null
  done
}
trap restore EXIT
trap 'restore; trap - EXIT; exit 130' INT TERM HUP
MYPID=$$
( i=0; lim=$(( (MAXSEC + 120) / 5 )); while [ $i -lt $lim ]; do sleep 5; kill -0 "$MYPID" 2>/dev/null || exit 0; i=$((i+1)); done; kill -TERM "$MYPID" 2>/dev/null ) >/dev/null 2>&1 &
WATCHDOG=$!
rm -f "$STOPF" 2>/dev/null
# Runtime guards: cooperative cancel (AccA writes the stop-flag), ACC hold-off, thermal/precondition
# gate, battery temperature/voltage reads, and multi-path charger-presence detection.
stop_check(){ [ -e "$STOPF" ] && { rm -f "$STOPF" 2>/dev/null; log ""; log "===== CANCELLED by user -- restoring native charging + ACC ====="; exit 130; }; return 0; }
acc_hold_off(){ [ "${ACC_WAS:-0}" = 1 ] && [ -n "${ACC_BIN:-}" ] && acc_to "$ACC_BIN" -D stop >/dev/null 2>&1; return 0; }
q_more(){ [ "${MODE:-quick}" = complete ] && return 0; [ -z "${BYPASS:-}${BYPASS_HELD:-}${CFG_LEVEL:-}" ]; }

log "############ AMPS v$V - Adaptive Multi-device Probe & Selector (smart-observe + blind-verify + firmware-teach + verdict card + charger-speed + path-aware + auto-reonline + fast-regrade + fast-scan) ############"
log "collected in report: phone model, soc, android+kernel build, charge-node names/values, charger driver names. no accounts/imei/serial/location."
log "SAFE MODE: WRITES only well-known, reversible charge switches (ACC's standard enable/suspend set) + native %-limits. Unknown/vendor/learned nodes are READ + reported (paste them back so we add the real ones to the DB), NEVER written. No bypass/regulator/OTG/PD/fuel-gauge writes. Battery protection is never disabled. Every write is snapshotted and restored."
log "TIME: ~2-6 minutes (Deep skips redundant re-grades + re-onlines a dropped charger automatically). Keep the charger plugged in + the screen on; it restores everything automatically at the end."
log ""
log "==== LAYER 0 - root + battery interface ===="
if [ "$(id -u 2>/dev/null)" != 0 ]; then log "STOP: not root. ACC needs root (Magisk/KernelSU/APatch). Re-run: su -c 'sh ...'"; exit 0; fi
log "root: yes   timeout-guard: $([ "$HAVE_TO" = 1 ] && echo on || echo off)"
log "device: $(getprop ro.product.manufacturer 2>/dev/null) $(getprop ro.product.model 2>/dev/null) ($(getprop ro.product.device 2>/dev/null))"
log "soc: $(getprop ro.board.platform 2>/dev/null)/$(getprop ro.hardware 2>/dev/null)  android: $(getprop ro.build.version.release 2>/dev/null)  kernel: $(uname -r 2>/dev/null)"
log "rom: $(getprop ro.build.version.incremental 2>/dev/null)"
BATT=
for b in $PSY/*; do [ "$(read1 "$b/type")" = Battery ] && ex "$b/capacity" && { BATT="$b"; break; }; done
[ -n "$BATT" ] || { ex "$PSY/battery/capacity" && BATT="$PSY/battery"; }
[ -n "$BATT" ] || for b in $PSY/*; do ex "$b/capacity" && { BATT="$b"; break; }; done
[ -n "$BATT" ] || { log "STOP: no battery power_supply at $PSY/*/capacity."; exit 0; }
log "battery supply: $BATT"
CURF="$BATT/current_now"; CURAVG=0; ex "$CURF" || { ex "$BATT/current_avg" && { CURF="$BATT/current_avg"; CURAVG=1; } || CURF=; }
[ -n "$CURF" ] && log "current sensor: $CURF" || warn "no current_now/current_avg -- cannot verify HOLD; results unreliable."
TEMPF=; for tf in "$BATT/temp" "$BATT/batt_temp" "$PSY/bms/temp"; do ex "$tf" && { TEMPF="$tf"; break; }; done
batt_temp(){ [ -n "$TEMPF" ] || { echo 250; return; }
  t="$(san "$(read1 "$TEMPF")")"; t="${t#-}"
  if [ "$t" -ge 1000 ] 2>/dev/null; then t=$(( t / 100 ))
  elif [ "$t" -le 60 ] 2>/dev/null; then t=$(( t * 10 )); fi
  case "$t" in ''|*[!0-9]*) t=250;; esac; echo "$t"; }
MAXTEMP_C=45; TMAX=$(( MAXTEMP_C * 10 ))
[ -n "$TEMPF" ] && log "temp sensor: $TEMPF ($(batt_temp) = $(( $(batt_temp) / 10 )).$(( $(batt_temp) % 10 ))C)" || log "temp sensor: none (temp guard disabled)"
THERMOK=0; [ -n "$TEMPF" ] && THERMOK=1
[ "$THERMOK" = 1 ] || warn "no battery temp sensor -> thermal guard off; tests STILL run (every test write is charge-STOP direction = non-heating, and battery protection is left intact) -- just watch the phone temperature manually."
[ "$CURAVG" = 1 ] && { POLL=10; warn "only current_avg present (slow-moving) -> using longer ${POLL}s polls; a real cut may still read as 'no effect'."; }
VOLTF=; for vf in "$BATT/voltage_now" "$BATT/voltage_avg" "$PSY/bms/voltage_now"; do ex "$vf" && { VOLTF="$vf"; break; }; done
vmv(){ [ -n "$VOLTF" ] || { echo 0; return; }
  va="$(san "$(read1 "$VOLTF")")"; vb="$(san "$(read1 "$VOLTF")")"; vc="$(san "$(read1 "$VOLTF")")"; vm="$(med3 "$va" "$vb" "$vc")"
  vma="${vm#-}"; [ "$vma" -ge 1000000 ] 2>/dev/null && vm=$(( vm / 1000 )); echo "$vm"; }
chg_type(){ rd "$BATT/charge_type" | sed -n '1p' | pclean; }
ctype_charging(){ case "$1" in ''|0|1|N/A|n/a|None|none|Unknown|unknown|Not*|not*) echo 0;; *) echo 1;; esac; }
inp_online(){ online_now && echo 1 || echo 0; }
proof_available(){
  [ "$(read_st)" = Charging ] && return 0
  ex "$BATT/charge_type" && [ "$(ctype_charging "$(chg_type)")" = 1 ] && return 0
  if [ -n "$CHGIN" ]; then pv="$(abs "$(san "$(read1 "$CHGIN")")")"; [ "$pv" -gt "${IDLE:-10000}" ] 2>/dev/null && return 0; fi
  [ "${VRISE:-0}" = 1 ] && return 0
  return 1; }
[ -n "$VOLTF" ] && log "voltage sensor: $VOLTF"

online_f(){ for o in $PSY/*/online; do ex "$o" || continue
  d="$(basename "$(dirname "$o")")"
  case "$d" in battery|*battery*) continue;; esac
  case "$d" in usb|*usb*|ac|dc|mains|main-charger|mainchg|pc_port|wireless|smb*|*ucsi*|*chg*|*charger*|*glink*|*tcpm*|*tcpc*|*pd*|*source*|*wls*|*dcin*) printf '%s\n' "$o";; esac; done; }
online_now(){
  any=0
  for o in $(online_f); do
    any=1
    ov="$(rd "$o" | sed -n '1p' | tr -cd 0-9)"
    [ -n "$ov" ] && [ "$ov" -gt 0 ] 2>/dev/null && return 0
  done
  if [ "$any" = 1 ]; then return 1; fi
  for o in $PSY/usb/online $PSY/ac/online; do
    ov="$(rd "$o" | sed -n '1p' | tr -cd 0-9)"
    [ -n "$ov" ] && [ "$ov" -gt 0 ] 2>/dev/null && return 0
  done
  return 1
}
present_now(){
  seen=0
  for i in $PSY/*/present; do
    ex "$i" || continue
    d="$(basename "$(dirname "$i")")"
    case "$d" in battery|*battery*) continue;; esac
    case "$d" in
      usb|*usb*|ac|dc|mains|main-charger|mainchg|pc_port|wireless|smb*|*ucsi*|*chg*|*charger*|*glink*|*tcpm*|*tcpc*|*pd*|*source*|*wls*|*dcin*) : ;;
      *) continue ;;
    esac
    seen=1
    pv="$(rd "$i" | sed -n '1p' | tr -cd 0-9)"
    [ "$pv" = 1 ] && return 0
  done
  online_now && return 0
  [ -n "${CHGIN:-}" ] && { _pcv="$(abs "$(san "$(read1 "$CHGIN")")")"; [ "$_pcv" -gt "${IDLE:-10}" ] 2>/dev/null && return 0; }
  case "$(read_st)" in Charging|charging|Full|full) return 0;; esac
  return 1
}
plugged(){ present_now; }
chgin_node(){ for n in $PSY/usb/input_current_now $PSY/usb/current_now $PSY/main-charger/current_now $PSY/dc/current_now $PSY/wireless/current_now; do ex "$n" && { printf '%s' "$n"; return; }; done
  for n in $PSY/*/input_current_now; do ex "$n" && { printf '%s' "$n"; return; }; done; }
CHGIN="$(chgin_node)"

med_cur(){ [ -n "$CURF" ] || { echo 0; return; }
  x="$(san "$(read1 "$CURF")")"; y="$(san "$(read1 "$CURF")")"; z="$(san "$(read1 "$CURF")")"; med3 "$x" "$y" "$z"; }

log ""
log "==== LAYER 1 - baseline ===="
CAP="$(san "$(read1 $BATT/capacity)")"; [ "$CAP" -gt 0 ] 2>/dev/null || CAP=100
ST="$(rd $BATT/status | sed -n '1p' | pclean)"
P0=no; plugged && P0=yes
log "capacity: ${CAP}%   status: ${ST:-?}   plugged: $P0   charger-in node: ${CHGIN:-none}"
[ "$CAP" -ge 100 ] && warn "battery FULL -- discharge to 40-80% and re-run."
[ "$CAP" -ge 95 ] && [ "$CAP" -lt 100 ] && warn "battery ${CAP}% -- little headroom; 40-80% gives cleaner active tests."
if [ "$P0" = no ]; then
  log ""
  log "  >>> PLEASE PLUG THE CHARGER IN NOW <<<"
  log "  (a wall charger is best. I'll wait and continue the moment you plug in...)"
  i=0
  while [ "$i" -lt 120 ]; do
    if plugged; then P0=yes; break; fi
    sleep 2; i=$(( i+2 ))
    [ "$(( i % 10 ))" = 0 ] && log "  ...still waiting for charger (${i}s)... plug it in."
  done
  if [ "$P0" = yes ]; then log "  charger detected -- thank you! continuing."
  else warn "no charger after ${i}s -- continuing, but the real charge tests will be SKIPPED. Plug in and run again for a full result."; fi
fi

log ""
GATE_TEMP_C="$(( $(batt_temp) / 10 ))"
GATE_MSG=
if   [ "$CAP" -ge 85 ] 2>/dev/null; then GATE_MSG="Battery is ${CAP}% -- too high for a clean test. There is no charging headroom left, so every switch looks like it does nothing. Discharge to 40-80% and run the test again."
elif [ "$CAP" -lt 15 ] 2>/dev/null; then GATE_MSG="Battery is ${CAP}% -- too low; the brief stop/drain tests need a little headroom. Charge to ~20% or more (30-80% is ideal) and run again."
elif [ "$GATE_TEMP_C" -ge "$MAXTEMP_C" ] 2>/dev/null; then GATE_MSG="Battery is ${GATE_TEMP_C}C -- at/above the ${MAXTEMP_C}C ceiling. Heat makes the firmware throttle charging on its own and can fake a 'working switch'. Let the phone cool below ${MAXTEMP_C}C and run the test again."
fi
if [ -n "$GATE_MSG" ]; then
  warn "PRECONDITION not met: $GATE_MSG"
  _gvc="$(getprop ro.product.device 2>/dev/null)"; _gvs="$(getprop ro.board.platform 2>/dev/null)"
  { printf 'schema=1\nresult=precondition\nreason=%s\ncapacity=%s\ntemp_c=%s\ndevice=%s\nsoc=%s\nscript=acc-compat\ntester_version=%s\nok=0\n' \
      "$GATE_MSG" "$CAP" "$GATE_TEMP_C" "$_gvc" "$_gvs" "$V"; } > "${ART}.tmp" 2>/dev/null
  mv -f "${ART}.tmp" "$ART" 2>/dev/null
  log ""; log "===== STOPPED: precondition not met. Nothing was changed; ACC is still running. ====="
  exit 3
fi
[ "$CAP" -ge 80 ] && [ "$CAP" -lt 85 ] && warn "battery ${CAP}% is a little high -- 40-75% gives the clearest result. Continuing."
[ "$CAP" -ge 15 ] && [ "$CAP" -lt 30 ] 2>/dev/null && warn "battery ${CAP}% is low -- you're plugged in so charging offsets the short drain tests, but 30-80% gives the cleanest result. Continuing."
[ "$GATE_TEMP_C" -ge 35 ] && [ "$GATE_TEMP_C" -lt "$MAXTEMP_C" ] && warn "battery ${GATE_TEMP_C}C is warm (under the ${MAXTEMP_C}C ceiling) -- cooler is better; any firmware thermal-throttle here is still classified THROTTLE (not a real switch) by the verify step. Continuing."
log "==== LAYER 2 - stop ACC + reset to NATIVE charging (honest baseline) ===="
ACC_WAS=0; ACC_BIN=; DJS_WAS=0; DJS_BIN=; ACCV=; ST_POL=; ST_UNIT=; ST_TRUST=; POLARITY=normal; POL_SRC=
for c in /data/adb/vr25/acc/acca /dev/.vr25/acc/acca acca acc; do
  command -v "$c" >/dev/null 2>&1 || continue
  ACCV="$(acc_to "$c" --version 2>/dev/null | sed -n '1p')"; [ -n "$ACCV" ] || continue
  ACC_WAS=1; ACC_BIN="$c"
  _accst="$(acc_to "$c" --state 2>/dev/null)"
  ST_POL="$(printf '%s' "$_accst"  | grep -oE '"polarity":"[a-z]+"'        | sed 's/.*://; s/"//g')"
  ST_UNIT="$(printf '%s' "$_accst" | grep -oE '"currentUnits":"[a-zA-Z]+"' | sed 's/.*://; s/"//g')"
  ST_TRUST="$(printf '%s' "$_accst"| grep -oE '"statusTrust":"[a-z]+"'     | sed 's/.*://; s/"//g')"
  acc_to "$c" -D stop >/dev/null 2>&1 || acc_to "$c" --daemon stop >/dev/null 2>&1
  break
done
for _dc in /data/adb/vr25/djs/djs /dev/.vr25/djs/djs djs; do
  command -v "$_dc" >/dev/null 2>&1 || continue
  acc_to "$_dc" --daemon stop >/dev/null 2>&1 || acc_to "$_dc" stop >/dev/null 2>&1
  DJS_WAS=1; DJS_BIN="$_dc"; break
done
[ "$DJS_WAS" = 1 ] && log "  DJS scheduler paused for the test window (will be restored at the end)"
log "ACC installed: ${ACCV:-no}"
PRE_SUSP="$(rd $BATT/input_suspend | sed -n '1p' | pclean)"
log "pre-test input_suspend: ${PRE_SUSP:-na}"
DID=1
defaults_native
_accsw="$(grep -m1 '^chargingSwitch=' /data/adb/vr25/acc-data/config.txt 2>/dev/null | sed -e 's/^chargingSwitch=(//' -e 's/).*$//' -e 's/ *--$//')"
if [ -n "$_accsw" ]; then
  set -- $_accsw
  while [ "$#" -ge 3 ]; do
    for _pf in $1; do ex "$_pf" && { snap_add "$_pf"; wr "$_pf" "$2"; }; done
    shift 3
  done
  [ "$#" -ge 2 ] && for _pf in $1; do ex "$_pf" && { snap_add "$_pf"; wr "$_pf" "$2"; }; done
fi
sleep $SETTLE

log ""
stop_check; acc_hold_off
log "==== LAYER 3 - current sign + unit auto-learn (ACC method) ===="
P1=no; plugged && P1=yes
ST="$(rd $BATT/status | sed -n '1p' | pclean)"
RAW=0; CUR_FROZEN=0
if [ -n "$CURF" ]; then
  cb1="$(san "$(read1 "$CURF")")"; sleep 1
  cb2="$(san "$(read1 "$CURF")")"; sleep 1
  cb3="$(san "$(read1 "$CURF")")"; sleep 1
  cb4="$(san "$(read1 "$CURF")")"; sleep 1
  cb5="$(san "$(read1 "$CURF")")"; sleep 1
  cb6="$(san "$(read1 "$CURF")")"
  RAW="$(med3 "$cb2" "$cb4" "$cb6")"
  clo="$cb1"; chi="$cb1"
  for cq in "$cb2" "$cb3" "$cb4" "$cb5" "$cb6"; do
    [ "$cq" -lt "$clo" ] 2>/dev/null && clo="$cq"
    [ "$cq" -gt "$chi" ] 2>/dev/null && chi="$cq"
  done
  cspread=$(( chi - clo )); [ "$cspread" -lt 0 ] && cspread=$(( 0 - cspread ))
  [ "$cspread" -eq 0 ] && CUR_FROZEN=1
fi
ABSB="$(abs "$RAW")"
UNIT=mA; THR=50; IDLE=10
if [ "$ABSB" -ge 16000 ] 2>/dev/null; then UNIT=uA; THR=50000; IDLE=10000; fi
if [ "$UNIT" = mA ]; then
  for ucf in "$BATT/constant_charge_current" "$BATT/constant_charge_current_max" $PSY/*/current_max $PSY/*/input_current_limit; do
    ex "$ucf" || continue
    uv="$(san "$(read1 "$ucf")")"
    [ "${uv#-}" -ge 100000 ] 2>/dev/null && { UNIT=uA; THR=50000; IDLE=10000; log "  unit corrected to uA (ctrl node $ucf=$uv)"; break; }
  done
fi
SIGN_UNSTABLE=0
if [ -n "$CURF" ]; then
  _sgp=0; _sgn=0
  for cq in "$cb1" "$cb2" "$cb3" "$cb4" "$cb5" "$cb6"; do
    [ "$cq" -gt "$IDLE" ] 2>/dev/null && _sgp=$((_sgp+1))
    [ "$cq" -lt "-${IDLE}" ] 2>/dev/null && _sgn=$((_sgn+1))
  done
  [ "$_sgp" -ge 2 ] && [ "$_sgn" -ge 2 ] && SIGN_UNSTABLE=1
fi
AST=""
if command -v dumpsys >/dev/null 2>&1; then
  astn="$(dumpsys battery 2>/dev/null | sed -n 's/^ *status: *//p' | sed -n '1p' | tr -cd '0-9')"
  case "$astn" in 1) AST=Unknown;; 2) AST=Charging;; 3) AST=Discharging;; 4) AST="Not charging";; 5) AST=Full;; esac
fi
EFFST="$ST"
case "$EFFST" in ''|Unknown|unknown) EFFST="$AST";; esac
[ -n "$ST_UNIT" ] && case "$ST_UNIT" in uA*|microamp*) UNIT=uA; THR=50000; IDLE=10000;; mA*|milliamp*) UNIT=mA; THR=50; IDLE=10;; esac
CS="$(sgn "$RAW")"; _gt=0; [ "$ABSB" -gt "$THR" ] 2>/dev/null && _gt=1
_lc="$(learn_chgdir "$EFFST" "$CS" "$_gt")"; CHGDIR="${_lc%% *}"; SIGN_CONF="${_lc##* }"; POL_SRC=live
POL_CONFLICT=0
if [ -n "$ST_POL" ]; then
  _sp="$(acca_sign "$ST_POL")"
  if [ -n "$_sp" ] && [ "${SIGN_UNSTABLE:-0}" = 1 ] && [ "$(pol_conflict "$_sp" "$CHGDIR" "$_gt" "$SIGN_CONF")" = 1 ]; then
    POL_CONFLICT=1
    warn "polarity CONFLICT: acca --state says $ST_POL but live samples flip sign within the read window (chg-sign=$CHGDIR) -- the current sign is unreliable on this phone (oplus/MTK latch it per charge session), so a single-sample polarity can mis-read working switches as 'no effect' -> verifying BLIND (status/charge_type/voltage), which is sign-independent."
  elif [ -n "$_sp" ] && [ "$SIGN_CONF" != high ]; then
    CHGDIR="$_sp"; SIGN_CONF=high; POL_SRC="acca-state:$ST_POL"
  fi
fi
POLARITY=normal; [ "$CHGDIR" = n ] && POLARITY=inverted
log "  charge-direction: chg-sign=$CHGDIR polarity=$POLARITY conf=$SIGN_CONF src=$POL_SRC unit=$UNIT thr=$THR"
WEAK_CHARGER=0; SS_CONFLICT=0; _bp=0; present_now && _bp=1; _bo=0; online_now && _bo=1
_bcur="$(med_cur)"; _bcap="$(san "$(rd $BATT/capacity | sed -n '1p')")"
_base_pol="$POLARITY"
[ "${POL_CONFLICT:-0}" = 1 ] && [ -n "${_sp:-}" ] && case "$_sp" in n) _base_pol=inverted;; p) _base_pol=normal;; esac
BASE_STATE="$(classify_state "$_bp" "$_bo" "$_bcur" "$_base_pol" "$IDLE")"
_bss="$(base_state_safe "$BASE_STATE" "$(read_st)")"
[ "$_bss" != "$BASE_STATE" ] && { warn "current SIGN vs charger STATUS disagree (signed baseline=$BASE_STATE but status=$(read_st)) -- the current sign is unreliable on this phone; trusting status=CHARGING and verifying BLIND."; BASE_STATE="$_bss"; SIGN_CONF=low; SS_CONFLICT=1; }
if [ "$_bp" = 1 ] && [ "${_bcap:-0}" -lt 95 ] 2>/dev/null; then
  case "$BASE_STATE" in
    CHARGING) ;;
    DRAIN|DISCHARGING) WEAK_CHARGER=1; warn "WEAK CHARGER: plugged at ${_bcap}% but the native baseline is $BASE_STATE (current=$_bcur $UNIT). The source can't outpace the load -- use a stronger wall charger or the switch verdicts below may be wrong.";;
    BYPASS|STANDBY) WEAK_CHARGER=1; warn "native baseline is $BASE_STATE at ${_bcap}% (battery not gaining charge) -- charger may be weak or the battery near full; switch verdicts may be unreliable.";;
  esac
fi
log "  baseline (native, no switch): $BASE_STATE at ${_bcap}% weak_charger=$WEAK_CHARGER"
if [ "$_bp" = 1 ] && [ "${_bcap:-0}" -lt 95 ] 2>/dev/null && [ "$BASE_STATE" != CHARGING ]; then
  log "  not natively charging ($BASE_STATE) while plugged -- forcing a super-native charging baseline..."
  _sni=0
  while [ "$_sni" -lt 3 ]; do
    defaults_native
    for _n in $PSY/usb/apsd_rerun /sys/class/qcom-battery/apsd_rerun $BATT/rerun_aicl /sys/class/qcom-battery/rerun_aicl; do [ -w "$_n" ] && echo 1 > "$_n" 2>/dev/null; done
    sleep 5; stop_check; acc_hold_off
    _bp=0; present_now && _bp=1; _bo=0; online_now && _bo=1; _bcur="$(med_cur)"
    BASE_STATE="$(classify_state "$_bp" "$_bo" "$_bcur" "$POLARITY" "$IDLE")"
    [ "$BASE_STATE" = CHARGING ] && break
    [ "$_bp" = 1 ] || break
    _sni=$((_sni+1))
  done
  if [ "$BASE_STATE" = CHARGING ]; then
    WEAK_CHARGER=0; log "  super-native OK -- native charging re-established (baseline=$BASE_STATE)."
  elif present_now && { [ "${POL_CONFLICT:-0}" = 1 ] || [ "${SS_CONFLICT:-0}" = 1 ]; }; then
    WEAK_CHARGER=1; SIGN_CONF=low
    warn "baseline still reads $BASE_STATE, but the current SIGN is unreliable on this phone (it flips per charge session) -- a 'DRAIN' here can be a flipped-sign CHARGING. NOT stopping; proceeding with BLIND verification (status/voltage). For a current-anchored result re-run Highest-accuracy (--unplug)."
  elif present_now; then
    warn "could NOT reach native charging (baseline=$BASE_STATE). The charger likely dropped its negotiation -- UNPLUG and RE-PLUG (try another cable/port), then run again. Stopping so the result is not built on a dead baseline."
    _snd="$(getprop ro.product.device 2>/dev/null)"
    { printf 'schema=1\nresult=precondition\nreason=could not reach native charging (baseline=%s) -- replug the charger and retry\ncapacity=%s\ndevice=%s\nscript=acc-compat\ntester_version=%s\nok=0\n' "$BASE_STATE" "${_bcap:-?}" "$_snd" "$V"; } > "${ART}.tmp" 2>/dev/null
    mv -f "${ART}.tmp" "$ART" 2>/dev/null
    log ""; log "  -- read-only candidate switches (paste back; even without a live baseline we can map them) --"
    for _cd in $DDIRS; do ex "$_cd" || continue
      for _cf in $( { if [ "$HAVE_TO" = 1 ]; then timeout "$TO" find -L "$_cd" -maxdepth 4 -type f 2>/dev/null; else find -L "$_cd" -maxdepth 4 -type f 2>/dev/null; fi; } | sed -n '1,200p' ); do
        printf '%s' "$_cf" | grep -Eq "$DENY_RE" && continue
        printf '%s' "$_cf" | grep -Eqi "$NAME_RE" || continue
        [ -w "$_cf" ] || continue
        _cv="$(rd "$_cf" | sed -n '1p' | pclean | cut -c1-24)"
        bool_like "$_cv" && log "    [RW bool] $_cf = $_cv"
      done
    done
    log ""; log "===== STOPPED: not native-charging. Replug + re-run (the candidates above are usable). ====="
    exit 3
  fi
fi
if [ "${PROBE:-}" = 1 ]; then
  log ""; log "==== PROBE (v5.5 polarity/state, no switch test) ===="
  log "  present=$_bp online=$_bo current=$_bcur unit=$UNIT idle_thr=$IDLE"
  log "  polarity=$POLARITY chg-sign=$CHGDIR conf=$SIGN_CONF src=$POL_SRC"
  log "  classify_state -> $BASE_STATE   weak_charger=$WEAK_CHARGER"
  [ -n "$ST_POL" ] && log "  acca --state: polarity=$ST_POL units=$ST_UNIT trust=$ST_TRUST"
  defaults_native
  [ "${ACC_WAS:-0}" = 1 ] && for c in /data/adb/vr25/acc/acca /dev/.vr25/acc/acca acca acc; do command -v "$c" >/dev/null 2>&1 && { acc_to "$c" -D restart >/dev/null 2>&1 || acc_to "$c" -D start >/dev/null 2>&1; break; }; done
  exit 0
fi
[ "$ABSB" -gt 0 ] 2>/dev/null || ABSB=1
HALF=$(( ABSB/2 )); [ "$HALF" -gt 0 ] || HALF=1
NEAR=$(( ABSB - ABSB/8 )); [ "$NEAR" -gt 0 ] || NEAR=1
CTYPE0="$(chg_type)"
if [ -n "$VOLTF" ]; then
  vlo=; vhi=; vi=0
  while [ "$vi" -lt 6 ]; do
    vv="$(vmv)"
    [ -n "$vlo" ] || { vlo="$vv"; vhi="$vv"; }
    [ "$vv" -lt "$vlo" ] 2>/dev/null && vlo="$vv"
    [ "$vv" -gt "$vhi" ] 2>/dev/null && vhi="$vv"
    sleep 1; vi=$((vi+1))
  done
  V0="$vhi"; VNOISE=$(( vhi - vlo )); VDROP=$(( VNOISE + 25 )); [ "$VDROP" -ge 25 ] || VDROP=25
  VRISE=0; [ "$VNOISE" -ge 8 ] 2>/dev/null && VRISE=1
fi
CUR_USABLE=1
[ -z "$CURF" ] && CUR_USABLE=0
[ "$CUR_FROZEN" = 1 ] && CUR_USABLE=0
[ "$ABSB" -le "$THR" ] 2>/dev/null && CUR_USABLE=0
[ "${POL_CONFLICT:-0}" = 1 ] && CUR_USABLE=0
[ "${SIGN_UNSTABLE:-0}" = 1 ] && CUR_USABLE=0
[ "${SS_CONFLICT:-0}" = 1 ] && CUR_USABLE=0
log "native: plugged=$P1 current=$RAW |abs|=$ABSB unit=$UNIT charge-sign=$CHGDIR(conf=$SIGN_CONF) status=$ST$([ "$CUR_FROZEN" = 1 ] && echo "  [FROZEN: identical across 6 reads]")"
[ -n "$VOLTF" ] && log "voltage: V0=${V0}mV baseline (noise +/-${VNOISE}mV, blind cut-threshold ${VDROP}mV, rising=$VRISE); charge_type=${CTYPE0:-na}"
if [ "$P1" = no ]; then warn "still NOT plugged after reset -> active hold-tests SKIPPED."; ACTIVE=0; fi
[ "$CAP" -ge 95 ] && { warn "near-full -> active hold-tests SKIPPED."; ACTIVE=0; }
if [ "$ACTIVE" = 1 ] && [ "$CUR_USABLE" = 0 ]; then
  if proof_available; then
    BLINDV=1; PROOF="status+charge_type+voltage"
    if [ "$CUR_FROZEN" = 1 ]; then warn "current sensor FROZEN (pinned at $RAW $UNIT, zero variance) -> switching to BLIND verification: charging-state + charge_type + voltage. This ROM's current_now is a stub; the active tests still run."
    elif [ -z "$CURF" ]; then warn "no current sensor -> BLIND verification (charging-state + charge_type + voltage)."
    elif [ "${POL_CONFLICT:-0}" = 1 ] || [ "${SIGN_UNSTABLE:-0}" = 1 ] || [ "${SS_CONFLICT:-0}" = 1 ]; then warn "current SIGN is unreliable on this phone (it flips/latches per charge session) -> BLIND verification (status/charge_type/voltage), which is sign-independent. This is the ACCURATE path here: a flipping sign would otherwise read working switches as 'no effect'."
    else warn "charge current below measurable threshold ($RAW $UNIT) -> BLIND verification (charging-state + charge_type + voltage). For a current-level proof use a stronger charger at 40-80%."
    fi
    log "  BLIND mode: a switch 'holds' if it flips status off Charging (or charge_type to N/A, or sags voltage >=${VDROP}mV) AND reverts when re-enabled."
  else
    warn "current unmeasurable AND no charging-state/voltage signal (charger looks already idle) -> active hold-tests SKIPPED. Plug a stronger charger at 40-80% and retry."
    ACTIVE=0
  fi
fi
[ "$ACTIVE" = 1 ] && [ "$CUR_USABLE" = 1 ] && [ "$SIGN_CONF" != high ] && warn "charge sign/unit confidence not high -> using status+charge_type+voltage as corroboration."

# Live charge-state sampling during tests: is-charging / idle checks, blind-mode voltage-rise proof,
# and the thermal gate that pauses active probing when the battery runs hot.
is_charging(){ c="$1"; case "$c" in ''|*[!0-9-]*) echo 1; return;; esac
  m="${c#-}"; [ "$m" -gt "$THR" ] 2>/dev/null || { echo 0; return; }
  [ "$(sgn "$c")" = "$CHGDIR" ] && echo 1 || echo 0; }
is_idle(){ m="$(abs "$1")"; [ "$m" -le "$IDLE" ] 2>/dev/null && echo 1 || echo 0; }
bcharge(){
  bs="$(read_st)"
  st_notchg "$bs" && { echo 0; return; }
  if ex "$BATT/charge_type"; then bct="$(chg_type)"; [ "$(ctype_charging "$bct")" = 0 ] && [ "$(ctype_charging "$CTYPE0")" = 1 ] && { echo 0; return; }; fi
  if [ -n "$VOLTF" ] && [ "$V0" -gt 0 ] 2>/dev/null; then bv="$(vmv)"; bd=$(( V0 - bv )); [ "$bd" -ge "$VDROP" ] 2>/dev/null && { echo 0; return; }; fi
  echo 1; }
chg_now(){ if [ "$BLINDV" = 1 ]; then bcharge; else cc="$(med_cur)"; { [ "$(is_charging "$cc")" = 1 ] || [ "$(read_st)" = Charging ]; } && echo 1 || echo 0; fi; }
resume_desc(){ if [ "$BLINDV" = 1 ]; then printf 'status=%s %smV' "$(read_st)" "$(vmv)"; else med_cur; fi; }

gate(){
  [ "$SKIPALL" = 0 ] || return 1
  [ "$ACTIVE" = 1 ] || return 1
  if [ -n "$TEMPF" ]; then
    ht=0
    while [ "$(batt_temp)" -ge "$TMAX" ] 2>/dev/null; do
      [ "$ht" = 0 ] && log "  ! battery $(( $(batt_temp) / 10 ))C (>=${MAXTEMP_C}C) -- pausing live tests until it cools..."
      sleep 10; ht=$((ht+10))
      [ "$ht" -ge 60 ] && { warn "battery stayed >=${MAXTEMP_C}C -- live test skipped for safety"; return 1; }
    done
  fi
  if ! plugged; then
    log "  ! charger OFFLINE -- waiting up to 30s for re-plug..."
    gi=0; while [ "$gi" -lt 30 ]; do sleep 2; gi=$((gi+2)); plugged && break; done
    plugged || { warn "charger unplugged mid-run -- remaining live tests skipped"; SKIPALL=1; return 1; }
  fi
  if [ "$BLINDV" = 1 ]; then
    gt=0
    while [ "$gt" -lt 4 ]; do
      [ "$(bcharge)" = 1 ] && { _gv="$(vmv)"; [ "${_gv:-0}" -gt "${V0:-0}" ] 2>/dev/null && V0="$_gv"; [ "${V0:-0}" -gt 0 ] 2>/dev/null || V0=1; return 0; }
      [ "$gt" = 2 ] && defaults_native
      sleep 2; gt=$((gt+1))
    done
    log "  [skip: charging-state did not return between tests]"
    return 1
  fi
  gt=0
  while [ "$gt" -lt 4 ]; do
    gc="$(med_cur)"; gm="$(abs "$gc")"
    if [ "$(sgn "$gc")" = "$CHGDIR" ] && [ "$gm" -gt "$THR" ] 2>/dev/null; then
      ABSB="$gm"
      HALF=$(( ABSB/2 )); [ "$HALF" -gt 0 ] || HALF=1
      NEAR=$(( ABSB - ABSB/8 )); [ "$NEAR" -gt 0 ] || NEAR=1
      return 0
    fi
    [ "$gt" = 2 ] && defaults_native
    sleep 2; gt=$((gt+1))
  done
  GATE_FAILS=$((GATE_FAILS+1))
  if [ "$CUR_USABLE" = 1 ] && [ "$GATE_FAILS" -ge 2 ] && [ "$(read_st)" = Charging ]; then
    BLINDV=1; CUR_USABLE=0; PROOF="status+charge_type+voltage"
    [ "$V0" -gt 0 ] 2>/dev/null || V0="$(vmv)"; [ "$V0" -gt 0 ] 2>/dev/null || V0=1
    warn "current-sign readings unstable (the |current| sign keeps flipping -- common on MediaTek) -> switching to BLIND verification (status/charge_type/voltage) for the rest of the run instead of skipping."
    return 0
  fi
  log "  [skip: charging did not resume between tests]"
  return 1
}

SAMP_N=0; SAMP_LAST=1; SAMP_FIRST=1; C1=0; CL=0; ST3=Charging; ST4=Charging
hold_probe(){
  stop_check
  SAMP_N=0; SAMP_FIRST=1; SAMP_LAST=1; C1=0; CL=0; ST3=Charging; ST4=Charging
  if [ "$BLINDV" = 1 ]; then
    sleep "$POLL"; bg1="$(bcharge)"; SAMP_FIRST="$bg1"; C1="$bg1"
    sleep "$POLL"; bg2="$(bcharge)"
    sleep "$POLL"; bg3="$(bcharge)"
    SAMP_N=$(( bg1 + bg2 + bg3 )); SAMP_LAST="$bg3"; CL="$bg3"; ST3="$(read_st)"; ST4="$ST3"
    if [ "$SAMP_LAST" = 0 ]; then
      sleep 4; bg4="$(bcharge)"; CL="$bg4"; SAMP_LAST="$bg4"; [ "$bg4" = 1 ] && SAMP_N=3; ST4="$(read_st)"
    fi
    BL_ONLINE="$(inp_online)"; BL_CT="$(chg_type)"; BL_VLAST="$(vmv)"; BL_WHY="status=$ST4 ct=$BL_CT v=${BL_VLAST}mV online=$BL_ONLINE"
    return
  fi
  _xs=0; { [ "${WEAK_CHARGER:-0}" = 1 ] || [ "${WANT_UNPLUG:-0}" = 1 ]; } && _xs=1
  sleep "$POLL"; C1="$(med_cur)"; SAMP_FIRST="$(is_charging "$C1")"; g1="$SAMP_FIRST"
  if [ "$_xs" = 0 ] && [ "$g1" = 1 ] && [ "$(abs "$C1")" -ge "$NEAR" ] 2>/dev/null; then
    SAMP_N=3; SAMP_LAST=1; CL="$C1"; return
  fi
  sleep "$POLL"; c2="$(med_cur)"; g2="$(is_charging "$c2")"
  if [ "$_xs" = 0 ] && [ "$g1" = 0 ] && [ "$g2" = 0 ]; then _hs="$(read_st)"; st_notchg "$_hs" && { SAMP_N=0; SAMP_LAST=0; CL="$c2"; ST3="$_hs"; ST4="$_hs"; return; }; fi
  sleep "$POLL"; c3="$(med_cur)"; g3="$(is_charging "$c3")"
  CL="$c3"; SAMP_LAST="$g3"; SAMP_N=$(( g1 + g2 + g3 ))
  ST3="$(read_st)"; ST4="$ST3"
  if { [ "$SAMP_LAST" = 0 ] && [ "$SAMP_N" -lt 3 ]; } || { st_notchg "$ST3" && [ "$SAMP_LAST" = 1 ]; }; then
    sleep 6; c4="$(med_cur)"; g4="$(is_charging "$c4")"
    if [ "$SAMP_LAST" = 0 ]; then
      CL="$c4"; SAMP_LAST="$g4"
      [ "$g4" = 1 ] && SAMP_N=3
    fi
    ST4="$(read_st)"
  fi
  if [ "$_xs" = 1 ]; then
    sleep 1; _e1="$(med_cur)"; sleep 1; _e2="$(med_cur)"; sleep 1; _e3="$(med_cur)"
    CL="$(med3 "$_e1" "$_e2" "$_e3")"; SAMP_LAST="$(is_charging "$CL")"
    _em=$(( $(is_charging "$_e1") + $(is_charging "$_e2") + $(is_charging "$_e3") ))
    [ "$_em" -ge 2 ] && { SAMP_LAST=1; [ "$SAMP_N" -lt 2 ] && SAMP_N=3; }
    [ "$_em" -le 1 ] && SAMP_LAST=0
  fi; }
st_notchg(){ case "$1" in Discharging|discharging|"Not charging"|"not charging"|NotCharging|notcharging) return 0;; *) return 1;; esac; }
chgin_low(){ [ -n "$CHGIN" ] || return 1
  v="$(abs "$(san "$(read1 "$CHGIN")")")"; [ "$v" -le "$IDLE" ] 2>/dev/null; }

classify_held(){
  if [ "$BLINDV" = 1 ]; then
    [ "$BL_ONLINE" = 0 ] && { echo CUT-input; return; }
    if ex "$BATT/charge_counter" && [ -n "$(read1 "$BATT/charge_counter")" ]; then
      _bcc0="$(san "$(read1 "$BATT/charge_counter")")"
      sleep 4
      _bcc1="$(san "$(read1 "$BATT/charge_counter")")"
      _bccd=$(( ${_bcc1:-0} - ${_bcc0:-0} ))
      _bccda="${_bccd#-}"
      [ "$_bccd" -lt -500 ] 2>/dev/null && { echo DRAIN; return; }
      if [ "${_bccda:-99999}" -lt 500 ] 2>/dev/null; then
        if online_now && [ -n "$CHGIN" ] && ! chgin_low; then echo BYPASS; else echo CUT; fi
        return
      fi
    fi
    case "$(read_st)" in Discharging|discharging) [ "${WEAK_CHARGER:-0}" = 1 ] || { echo DRAIN; return; };; esac
    echo CUT; return
  fi
  online_now || { echo CUT-input; return; }
  if [ "${WEAK_CHARGER:-0}" != 1 ] && [ "$(sgn "$CL")" != "$CHGDIR" ] && [ "$(abs "$CL")" -gt "$IDLE" ] 2>/dev/null; then echo DRAIN; return; fi
  if [ "$(is_idle "$CL")" = 1 ]; then
    onl=1; online_now || onl=0
    cin=0; [ -n "$CHGIN" ] && { v="$(abs "$(san "$(read1 "$CHGIN")")")"; [ "$v" -gt "$IDLE" ] 2>/dev/null && cin=1; }
    if [ "$onl" = 0 ]; then echo CUT-input
    elif [ "$cin" = 1 ]; then echo BYPASS
    else echo CUT; fi
    return
  fi
  echo CUT; }

# Working-switch registry + per-switch tests: each found switch is recorded here (class/ctrl/stability/label)
# so emit_alts surfaces it directly (no label re-resolution); then route discovery, sustained leak/re-arm
# hold (catches charge-pump fake-idle), resume verification, and the native %-limit (level) test.
reg_add(){ _rga="$(printf '%s' "$1" | tr 'A-Z' 'a-z')"; case "$_rga" in cut-*) _rga=cut;; esac; printf '%s\t%s\t%s\t%s\n' "$_rga" "$2" "$3" "$4" >> "$REG" 2>/dev/null; }
route_hit(){
  rh_cfg="$(printf '%s' "$3" | sed 's/ (.*$//')"
  case "$1" in
    BYPASS) BYPASS="$BYPASS|$2"; [ -n "$CFG_BYPASS" ] || CFG_BYPASS="$rh_cfg";;
    DRAIN) DRAIN="$DRAIN|$2"; [ -n "$CFG_DRAIN" ] || CFG_DRAIN="$rh_cfg";;
    *) CUT="$CUT|$2"; [ -n "$CFG_CUT" ] || CFG_CUT="$rh_cfg";;
  esac
  reg_add "$1" "$rh_cfg" holds-alone "$2"
  WORKING="${WORKING:-$2 ($1)}"
  ADDLINES="$ADDLINES
    $3"; }

route_stab(){
  _rc="$1"; _rl="$2"; _rp="$3"; _ron="$4"; _roff="$5"; _rs="$6"
  printf '%s\t%s\n' "$_rl" "$_rs" >> "$BK/stab" 2>/dev/null
  if [ "$_rs" = leaky ]; then
    LEAKY="$LEAKY|$_rl"; reg_add "$_rc" "$_rp $_ron $_roff" leaky "$_rl"
    ADDLINES="$ADDLINES
    $_rp $_ron $_roff ($_rc, LEAKY: re-arms even when re-applied -- overcharge risk, not recommended)"
    return
  fi
  if [ "$_rs" = inconclusive ]; then
    INCONC="$INCONC|$_rl"; reg_add "$_rc" "$_rp $_ron $_roff" inconclusive "$_rl"
    ADDLINES="$ADDLINES
    $_rp $_ron $_roff ($_rc, INCONCLUSIVE: grade cut short by the run deadline -- re-run Deep to confirm before pinning)"
    return
  fi
  _sn=; [ "$_rs" = daemon-held ] && _sn=", daemon-held (re-arms alone but the daemon holds it flat)"
  case "$_rc" in
    BYPASS) BYPASS="$BYPASS|$_rl"; { [ "$_rs" = holds-alone ] && [ "$BLINDV" = 0 ]; } && BYPASS_HELD="$BYPASS_HELD|$_rl"; [ -n "$CFG_BYPASS" ] || CFG_BYPASS="$_rp $_ron $_roff";;
    DRAIN) DRAIN="$DRAIN|$_rl"; [ -n "$CFG_DRAIN" ] || CFG_DRAIN="$_rp $_ron $_roff";;
    *) CUT="$CUT|$_rl"; [ -n "$CFG_CUT" ] || CFG_CUT="$_rp $_ron $_roff";;
  esac
  reg_add "$_rc" "$_rp $_ron $_roff" "$_rs" "$_rl"
  WORKING="${WORKING:-$_rl ($_rc)}"
  ADDLINES="$ADDLINES
    $_rp $_ron $_roff ($_rc$_sn)"; }

resume_check(){
  ri=0; rok=0; rc=
  while [ "$ri" -lt 3 ]; do
    stop_check
    sleep 3
    if [ "$(chg_now)" = 1 ]; then rok=1; break; fi
    ri=$((ri+1))
  done
  rc="$(resume_desc)"
  if [ "$rok" = 1 ]; then
    RESUMES="$RESUMES|$1=OK"
    log "    resume: OK (charging back, $rc after ~$(( ri*3 + 3 ))s)"
  else
    defaults_native; sleep 3
    if [ "$(chg_now)" = 1 ]; then
      RESUMES="$RESUMES|$1=after-reset"
      log "    resume: only after a full native reset (ACC should rewrite defaults on resume for this switch)"
    elif recover_online 30 && { sleep 2; [ "$(chg_now)" = 1 ]; }; then
      RESUMES="$RESUMES|$1=after-rekick"
      log "    resume: OK after an APSD/AICL re-kick (~the firmware had dropped to online=0; no replug needed)"
    else
      rw=12; rok=0; rbrk=0
      while [ "$rw" -lt 90 ]; do
        over && { rbrk=1; break; }
        stop_check
        sleep 6; rw=$((rw+6))
        if [ "$(chg_now)" = 1 ]; then rok=1; break; fi
      done
      if [ "$rok" = 1 ]; then
        RESUMES="$RESUMES|$1=SLOW(${rw}s)"
        log "    resume: SLOW -- charging returned after ~${rw}s (latch clears via state machine; ACC must re-arm on plug and tolerate the delay)"
        warn "switch $1 re-arms SLOWLY (~${rw}s) after pause -- not instant, but no replug needed"
      elif [ "$rbrk" = 1 ]; then
        RESUMES="$RESUMES|$1=UNKNOWN(deadline-at-${rw}s)"
        log "    resume: UNKNOWN -- run deadline hit after ${rw}s of waiting; re-run to classify (not marked latching)"
      else
        STUCKS="$STUCKS|$1"; RESUMES="$RESUMES|$1=STUCK"
        log "    resume: STUCK -- no re-arm within ~${rw}s (latching switch: needs replug/reboot)"
        warn "switch $1 LATCHES after pause (did not resume in ${rw}s) -- ACC needs a re-arm strategy for it"
      fi
    fi
  fi; }

test_switch(){
  lbl="$1"; p="$2"; onv="$3"; offv="$4"
  [ "$ACTIVE" = 1 ] || return
  over && { log "  [deadline] $lbl"; return; }
  ex "$p" || return
  printf '%s' "$p" | grep -Eiq "$DANGER_RE" && { log "  [danger-skip] $lbl (never written: matches safety deny-list)"; return; }
  printf '%s' "$p" | grep -Eiq "$SUPERDENY_RE" && { log "  [danger-skip] $lbl (never written: protected node family -- fuel-gauge/bms/PD/regulator/thermal)"; return; }
  grep -qxF "$p" "$BK/dead" 2>/dev/null && { log "  [skip dead] $lbl (a prior write to this node was rejected)"; return; }
  cur="$(rd "$p" | sed -n '1p')"
  case "$cur" in ''|0|1|"0 0"|"0 1"|enabled|disabled|on|off|true|false|[0-9]|[0-9][0-9]|[0-9][0-9][0-9]|[0-9][0-9][0-9][0-9]|[0-9][0-9][0-9][0-9][0-9]|[0-9][0-9][0-9][0-9][0-9][0-9]|[0-9][0-9][0-9][0-9][0-9][0-9][0-9]|[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]) :;; *) return;; esac
  [ -w "$p" ] || { log "  [read-only] $lbl"; return; }
  gate || return
  bwi=0
  while [ "$(chg_now)" != 1 ] && [ "$bwi" -lt 12 ]; do over && break; stop_check; sleep 3; bwi=$((bwi+3)); [ "$bwi" = 6 ] && recover_online 12 >/dev/null 2>&1; done
  [ "$bwi" -gt 0 ] && log "  [baseline] $lbl: waited ${bwi}s for clean charging before test"
  snap_add "$p"
  printf '%s|%s\n' "$p" "$offv" >> "$BK/tested"
  wr "$p" "$offv" || { log "  [write-fail] $lbl"; printf '%s\n' "$p" >> "$BK/dead" 2>/dev/null; return; }
  case "$offv" in
    ''|*[!0-9]*) :;;
    *) _rbv="$(rd "$p" | sed -n '1p')"
       [ "$_rbv" = "$offv" ] || { log "  $lbl -> off=$offv reads-back $_rbv [no-stick: driver clamped/rejected -- not a real switch]"; printf '%s\n' "$p" >> "$BK/dead" 2>/dev/null; wr "$p" "${cur:-$onv}" 2>/dev/null; sleep 1; return; }
       ;;
  esac
  hold_probe
  held=0; [ "$SAMP_LAST" = 0 ] && [ "$SAMP_N" -lt 3 ] && held=1
  if [ "$held" = 1 ]; then
    k0="$(classify_held)"
    if [ "$BLINDV" = 1 ]; then det="blind[$BL_WHY]"; else det="first=$C1 last=$CL"; fi
    if [ "${MODE:-quick}" != complete ] && [ -n "${BYPASS_HELD:-}${CFG_LEVEL:-}" ] && [ "$k0" != BYPASS ]; then
      STAB=daemon-held
      log "  $lbl -> off=$offv $det [$k0] (quick: a perfect switch already won -- alternative kept, not deep-graded)"
    else
    _pg="$(awk -F'\t' -v n="$p" '$1==n{v=$2} END{print v}' "$BK/graded" 2>/dev/null)"
    if [ -n "$_pg" ]; then
      _gc0="$(san "$(read1 "$BATT/charge_counter")")"; _gci=0; _gleak=0
      while [ "$_gci" -lt 2 ]; do over && break; stop_check; wr "$p" "$offv" 2>/dev/null; sleep 5; _gci=$((_gci+1))
        _gcn="$(san "$(read1 "$BATT/charge_counter")")"; [ -n "$_gc0" ] && [ "${_gcn:-0}" -gt "$(( ${_gc0:-0} + 4000 ))" ] 2>/dev/null && { _gleak=1; break; }
      done
      if [ "$_gleak" = 1 ]; then STAB=leaky; else STAB="$_pg"; fi
      log "  $lbl -> off=$offv $det [$k0] [$STAB -- same node already graded; short $(( _gci*5 ))s re-confirm]"
    else
    lcap0="$(san "$(read1 "$BATT/capacity")")"; lcc0="$(san "$(read1 "$BATT/charge_counter")")"; rwi=0; rcons=0; rearmed=0; _rdl=0
    while [ "$rwi" -lt 4 ]; do
      over && { _rdl=1; break; }; stop_check; sleep 6; rwi=$((rwi+1))
      if [ "$(chg_now)" = 1 ]; then rcons=$((rcons+1)); [ "$rcons" -ge 2 ] && { rearmed=1; break; }; else rcons=0; fi
      lcapn="$(san "$(read1 "$BATT/capacity")")"; [ "${lcapn:-0}" -gt "${lcap0:-0}" ] 2>/dev/null && { rearmed=1; break; }
      lccn="$(san "$(read1 "$BATT/charge_counter")")"; [ -n "$lcc0" ] && [ "${lccn:-0}" -gt "$(( ${lcc0:-0} + 1500 ))" ] 2>/dev/null && { rearmed=1; break; }
    done
    if [ "$rearmed" = 0 ] && [ "$_rdl" = 1 ]; then
      STAB=inconclusive
      log "  $lbl -> off=$offv $det [$k0] INCONCLUSIVE -- held so far but the run deadline cut the re-arm grade; re-run to confirm (not pinned)"
    elif [ "$rearmed" = 0 ]; then
      STAB=holds-alone; [ "$rwi" -ge 4 ] && LONGOK="$LONGOK|$lbl"
      log "  $lbl -> off=$offv $det [$k0] HELD-ALONE (+$(( rwi*6 ))s passive, no re-arm)"
    else
      dcap0="$(san "$(read1 "$BATT/capacity")")"; dcc0="$(san "$(read1 "$BATT/charge_counter")")"; di=0; leak=0; _flat=0; _ldl=0
      while [ "$di" -lt 8 ]; do
        over && { _ldl=1; break; }; stop_check
        wr "$p" "$offv" 2>/dev/null
        sleep 5; di=$((di+1))
        dcapn="$(san "$(read1 "$BATT/capacity")")"; [ "${dcapn:-0}" -gt "${dcap0:-0}" ] 2>/dev/null && { leak=1; break; }
        dccn="$(san "$(read1 "$BATT/charge_counter")")"
        if [ -n "$dcc0" ] && [ "${dccn:-0}" -gt "$(( ${dcc0:-0} + 4000 ))" ] 2>/dev/null; then leak=1; break; fi
        if [ -n "$dcc0" ] && [ "${dccn:-0}" -le "${dcc0:-0}" ] 2>/dev/null; then _flat=$((_flat+1)); [ "$_flat" -ge 3 ] && break; else _flat=0; fi
      done
      if [ "$leak" = 1 ]; then
        STAB=leaky
        log "  $lbl -> off=$offv $det [$k0] LEAKY (re-arms AND still creeps when re-applied every 5s -- daemon cannot hold it, overcharge risk)"
      elif [ "$leak" = 0 ] && [ "$_ldl" = 1 ] && [ "$_flat" -lt 3 ]; then
        STAB=inconclusive
        log "  $lbl -> off=$offv $det [$k0] INCONCLUSIVE -- re-arms; leak-test hit the run deadline before a verdict; re-run to confirm (not pinned)"
      else
        STAB=daemon-held
        log "  $lbl -> off=$offv $det [$k0] DAEMON-HELD (re-arms alone but held FLAT when re-applied each poll -- usable, the daemon holds it)"
      fi
    fi
    printf '%s\t%s\n' "$p" "$STAB" >> "$BK/graded" 2>/dev/null
    fi
    fi
    route_stab "$k0" "$lbl" "$p" "$onv" "$offv" "$STAB"
    { [ "$STAB" != leaky ] && [ -z "$TEACH_P" ]; } && case "$k0" in CUT*|BYPASS) TEACH_P="$p"; TEACH_ON="${cur:-$onv}"; TEACH_OFF="$offv";; esac
  else
    if [ "$SAMP_FIRST" = 0 ] && [ "$SAMP_LAST" = 1 ]; then
      dcap0="$(san "$(read1 "$BATT/capacity")")"; dcc0="$(san "$(read1 "$BATT/charge_counter")")"; di=0; leak=0; _flat=0
      while [ "$di" -lt 8 ]; do
        over && break; stop_check; wr "$p" "$offv" 2>/dev/null; sleep 5; di=$((di+1))
        dcapn="$(san "$(read1 "$BATT/capacity")")"; [ "${dcapn:-0}" -gt "${dcap0:-0}" ] 2>/dev/null && { leak=1; break; }
        dccn="$(san "$(read1 "$BATT/charge_counter")")"
        if [ -n "$dcc0" ] && [ "${dccn:-0}" -gt "$(( ${dcc0:-0} + 4000 ))" ] 2>/dev/null; then leak=1; break; fi
        if [ -n "$dcc0" ] && [ "${dccn:-0}" -le "${dcc0:-0}" ] 2>/dev/null; then _flat=$((_flat+1)); [ "$_flat" -ge 3 ] && break; else _flat=0; fi
      done
      if [ "$leak" = 0 ]; then
        wr "$p" "$offv"; sleep "$POLL"
        if [ "$BLINDV" = 1 ]; then hold_probe; k0="$(classify_held)"
        else _kon=0; _kdr=0; for _ki in 1 2 3; do online_now && _kon=$((_kon+1)); _kc="$(med_cur)"; { [ "$(sgn "$_kc")" != "$CHGDIR" ] && [ "$(abs "$_kc")" -gt "$IDLE" ] 2>/dev/null; } && _kdr=$((_kdr+1)); sleep 1; done
          if [ "$_kon" -lt 2 ]; then k0=CUT-input; elif [ "$_kdr" -ge 2 ]; then k0=DRAIN; else k0=BYPASS; fi; fi
        STAB=daemon-held
        log "  $lbl -> off=$offv first=$C1 re-arms fast but DAEMON-HELD [$k0]: held FLAT (no net charge +$(( di*5 ))s) when re-applied each poll -- usable, the daemon holds it"
        route_stab "$k0" "$lbl" "$p" "$onv" "$offv" "$STAB"
        { [ -z "$TEACH_P" ]; } && case "$k0" in CUT*|BYPASS) TEACH_P="$p"; TEACH_ON="${cur:-$onv}"; TEACH_OFF="$offv";; esac
      else
        REASSERT="$REASSERT|$lbl"; log "  $lbl -> off=$offv first=$C1 last=$CL DROPPED-THEN-RESUMED + still creeps when re-applied [LEAKY: WOULD OVERCHARGE - do NOT use]"
      fi
    else
      _uv="$(classify_unheld "$C1" "$CL" "$ST3" "$ST4" "$(chgin_low && echo 1 || echo 0)" "$HALF" "$IDLE" "$BLINDV")"
      case "$_uv" in
        CUT)
          log "  $lbl -> off=$offv first=$C1 last=$CL status=$ST3/$ST4 input~0 HELD-status [CUT]"
          route_hit CUT "$lbl" "$p $onv $offv (CUT, status-verified: current sensor is unsigned on this kernel)"
          [ -z "$TEACH_P" ] && { TEACH_P="$p"; TEACH_ON="${cur:-$onv}"; TEACH_OFF="$offv"; } ;;
        THROTTLE)
          THROTTLE="$THROTTLE|$lbl"; reg_add throttle "$p $onv $offv" throttle "$lbl"; log "  $lbl -> off=$offv first=$C1 last=$CL [THROTTLE: reduced not stopped -- sustained across both samples]" ;;
        *)
          log "  $lbl -> off=$offv first=$C1 last=$CL [no effect]" ;;
      esac
    fi
  fi
  wr "$p" "${cur:-$onv}"; rbk="$(rd "$p" | sed -n '1p')"; [ "$rbk" = "${cur:-$onv}" ] || { wr "$p" "${cur:-$onv}"; log "  [restore-retry] $lbl"; }
  if [ "$held" = 1 ]; then resume_check "$lbl"; else sleep 1; fi; }

test_level(){
  [ "${UNKNOWN:-0}" = 1 ] && return
  lbl="$1"; stop="$2"; start="$3"; resume="${4:-100}"; lvl_enf=0
  ex "$stop" || return
  o="$(rd "$stop" | sed -n '1p')"
  case "$o" in ''|*[!0-9-]*) log "  $lbl -> non-numeric ($(printf '%s' "$o" | pclean | cut -c1-40)) [skip]"; return;; esac
  case "$o" in -*) resume="$o";; esac
  [ -w "$stop" ] || { log "  [read-only] $lbl (present, value=$o)"; LEVELOK="$LEVELOK|$lbl(ro)"; return; }
  snap_add "$stop"
  hasstart=0; os=
  [ -n "$start" ] && ex "$start" && { hasstart=1; snap_add "$start"; os="$(rd "$start" | sed -n '1p')"; }
  engage=0; pstop=80
  if [ "$ACTIVE" = 1 ] && [ "$CAP" -ge 12 ] 2>/dev/null && [ "$CAP" -lt 100 ]; then pstop=$(( CAP-5 )); engage=1; fi
  [ "$pstop" -ge 96 ] 2>/dev/null && pstop=95
  [ "$pstop" -le 5 ] 2>/dev/null && { pstop=80; engage=0; }
  [ "$engage" = 1 ] && { gate || engage=0; }
  pstart=$(( pstop-8 )); [ "$pstart" -le 1 ] 2>/dev/null && pstart=1
  [ "$hasstart" = 1 ] && wr "$start" "$pstart"
  wr "$stop" "$pstop"; rb="$(rd "$stop" | sed -n '1p')"
  ok=0
  if [ "$rb" = "$pstop" ]; then ok=1
  else case "$rb" in ''|*[!0-9]*) ok=0;; *) [ "$rb" -gt 0 ] 2>/dev/null && [ "$rb" -lt 100 ] 2>/dev/null && [ "$rb" != "$o" ] && ok=1;; esac; fi
  if [ "$ok" = 0 ]; then
    pstop2=$(( pstop-12 )); [ "$pstop2" -le "$pstart" ] 2>/dev/null && pstop2=$(( pstart+2 ))
    if [ "$pstop2" -gt 1 ] 2>/dev/null && [ "$pstop2" -lt 100 ] 2>/dev/null && [ "$pstop2" != "$pstop" ]; then
      wr "$stop" "$pstop2"; rb2="$(rd "$stop" | sed -n '1p')"
      case "$rb2" in ''|*[!0-9]*) :;; *) [ "$rb2" -gt 0 ] 2>/dev/null && [ "$rb2" -lt 100 ] 2>/dev/null && [ "$rb2" != "$o" ] && { ok=1; rb="$rb2"; pstop="$pstop2"; };; esac
    fi
  fi
  if [ "$ok" = 0 ]; then
    log "  $lbl -> wrote $pstop (start->$pstart) read $(printf '%s' "${rb:-?}" | pclean | cut -c1-40) [driver rejected - unsupported]"
    wr "$stop" "$o"; [ "$hasstart" = 1 ] && wr "$start" "$os"; return
  fi
  log "  $lbl -> write/readback OK: limit accepted (wrote $pstop start->$pstart read $rb)"
  if [ "$engage" = 1 ]; then
    sleep "$POLL"
    hold_probe
    [ "$SAMP_LAST" = 1 ] && { sleep "$SETTLE"; hold_probe; }
    _lvl_blind=0; [ "$BLINDV" = 1 ] && _lvl_blind=1
    _lvl_rearm=0; _li=0
    if [ "$SAMP_LAST" = 0 ] && [ "$SAMP_N" -lt 3 ] && [ "$_lvl_blind" = 0 ]; then
      while [ "$_li" -lt 4 ]; do sleep 6; hold_probe; [ "$SAMP_LAST" = 1 ] && { _lvl_rearm=1; break; }; _li=$((_li+1)); done
    fi
    if [ "$(native_verdict "$SAMP_LAST" "$SAMP_N" "$_lvl_blind" "$_lvl_rearm")" = verified ]; then
      log "    engage stop=${pstop}% at SOC ${CAP}% -> last=$CL ENFORCED + held +$(( _li*6 ))s [native limit VERIFIED]"
      LEVELOK="$LEVELOK|$lbl"; WORKING="${WORKING:-$stop (LEVEL)}"; lvl_enf=1
      reg_add native-level "$stop $resume pcap" holds-alone "$lbl"
      [ -n "$CFG_LEVEL" ] || CFG_LEVEL="$stop $resume pcap"
      [ -z "$TEACH_P" ] && { TEACH_P="$stop"; TEACH_ON="$resume"; TEACH_OFF="$pstop"; }
      ADDLINES="$ADDLINES
    $stop $resume pcap (native level limit; ON/resume value=$resume to re-arm)"
      if [ "$ENGDUMPED" = 0 ] && [ -s "$SCHG" ]; then
        state_dump "$SHELD"; ENGDUMPED=1
        OBS_ENGAGE="$(diff_pairs "$SCHG" "$SHELD" | wc -l | tr -d ' ')"
        log "    observe: $OBS_ENGAGE node value(s) changed while firmware held charging off"
        diff_pairs "$SCHG" "$SHELD" | sed -n '1,10p' | pclean2 | while read -r l; do log "      ~ $l"; done
      fi
    else
      _why="value accepted, enforcement not seen in window"
      [ "$_lvl_rearm" = 1 ] && _why="held then RE-ARMED after ~$(( _li*6 ))s -- accepts-but-overshoots, NOT a verified cap"
      [ "$_lvl_blind" = 1 ] && _why="status/voltage moved but the current sensor is BLIND -- cannot prove a level cap holds"
      log "    engage stop=${pstop}% at SOC ${CAP}% -> last=$CL [$_why]"
      LEVELOK="$LEVELOK|$lbl(accepts)"
      [ "$_lvl_rearm" = 1 ] && REASSERT="$REASSERT|$lbl"
    fi
  else
    LEVELOK="$LEVELOK|$lbl(accepts)"
  fi
  wr "$stop" "$o"; [ "$hasstart" = 1 ] && wr "$start" "$os"
  if [ "$lvl_enf" = 1 ]; then resume_check "$lbl"; else sleep 1; fi; }

# Discovery engine: walk known + expanded candidate paths across vendors (Qualcomm/MTK/Exynos/Pixel/OEM),
# test each node, and feed every working switch into the registry. The bulk of cross-device coverage lives here.
emit_known(){
[ "${UNKNOWN:-0}" = 1 ] && return
cat <<'EOF'
*/input_suspend|0|1
/sys/class/qcom-battery/input_suspend|0|1
/sys/class/battchg_ext/*input_suspend|0|1
battery/battery_input_suspend|0|1
battery/charging_enabled|1|0
battery/battery_charging_enabled|1|0
/sys/class/qcom-battery/charging_enabled|1|0
*/charging_enabled|1|0
*/charging_enable|1|0
*/charge_enabled|1|0
*/enable_charging|1|0
*/enable_charger|1|0
*/charger_control|1|0
*/charging_state|enabled|disabled
battery/op_disable_charge|0|1
battery/batt_slate_mode|0|1
battery/charge_disable|1|0
*/charge_disable|0|1
*/disable_charging|0|1
/sys/oplus/battery/mmi_charging_enable|1|0
/sys/class/oplus_chg/battery/mmi_charging_enable|1|0
battery/mmi_charging_enable|1|0
/sys/devices/platform/soc/soc:oplus,chg_intf/oplus_chg/battery/chg_enable|1|0
/proc/oplus-votable/CHG_DISABLE/force_active|0|1
battery_ext/smart_charging_interruption|0|1
/sys/class/cms_class/disable_charge|0|1
/sys/class/qcom-battery/batt_protect_en|0|1
/sys/class/qcom-battery/night_charging|0|1
battery/night_charging|0|1
/sys/class/hw_power/charger/charge_data/enable_charger|1|0
/sys/devices/platform/huawei_charger/enable_charger|1|0
/sys/devices/platform/lge-unified-nodes/charging_enable|1|0
/sys/devices/platform/lge-unified-nodes/charging_completed|0|1
/sys/devices/platform/charger/bypass_charger|0|1
/sys/devices/platform/mt-battery/disable_charger|0|1
/proc/*disable_chrg|0|1
/sys/module/pm*_charger/parameters/disabled|0|1
/sys/class/asuslib/charging_suspend_en|0|1
/sys/class/asuslib/charger_limit_en|0|1
/sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,battery_charger/force_charger_suspend|0|1
/sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:mmi,qti-glink-charger/force_usb_suspend|0|1
*/device/force_charger_suspend|0|1
*/force_chg_usb_suspend|0|1
/sys/kernel/debug/google_charger/input_suspend|0|1
/sys/kernel/debug/google_charger/chg_suspend|0|1
/sys/kernel/nubia_charge/charger_bypass|off|on
EOF
}

expand_paths(){
  pat="$1"
  case "$pat" in
    /*) for f in $pat; do ex "$f" && printf '%s\n' "$f"; done;;
    *) for f in $PSY/$pat; do ex "$f" && printf '%s\n' "$f"; done;;
  esac; }

log ""
log "==== LAYER S1 - smart-observe: charger drivers, kernel log, votables ===="
DRVS=""
for d in $PSY/*/device/driver; do
  ex "$d" || continue
  dn="$(basename "$(readlink -f "$d" 2>/dev/null)" 2>/dev/null)"
  [ -n "$dn" ] && case " $DRVS " in *" $dn "*) :;; *) DRVS="$DRVS $dn";; esac
done
[ -n "$DRVS" ] && log "  charger drivers:$DRVS"
MODS="$(ls /sys/module 2>/dev/null | grep -iE 'charg|chg|battery|bq[0-9]|rt[0-9]|sgm[0-9]|smb[0-9]|mp2[0-9]|sc8[0-9]|upm[0-9]|aw3[0-9]|pca9' | sed -n '1,12p')"
nx=0
for m in $MODS $DRVS; do
  [ "$nx" -ge 12 ] && break
  pdir="/sys/module/$m/parameters"
  if [ -d "$pdir" ] && [ -n "$(ls "$pdir" 2>/dev/null)" ]; then
    case " $DDIRS_ALL " in *" $pdir "*) :;; *) DDIRS_ALL="$DDIRS_ALL $pdir"; EXTRA_DIRS="$EXTRA_DIRS $pdir"; nx=$((nx+1));; esac
  fi
  for pd in /sys/devices/platform/*"$m"*; do
    [ -d "$pd" ] || continue
    [ "$nx" -ge 12 ] && break
    case " $DDIRS_ALL " in *" $pd "*) :;; *) DDIRS_ALL="$DDIRS_ALL $pd"; EXTRA_DIRS="$EXTRA_DIRS $pd"; nx=$((nx+1));; esac
  done
done
[ -n "$EXTRA_DIRS" ] && log "  extra vendor dirs from driver map:$EXTRA_DIRS"
log "  dmesg (charge lines, last 24):"
{ if [ "$HAVE_TO" = 1 ]; then timeout "$TO" dmesg 2>/dev/null; else dmesg 2>/dev/null; fi; } | grep -iE 'charg|input_susp|batt.*(en|dis)able|fcc|icl' | tail -24 | cut -c1-160 | pclean2 | while read -r l; do log "    $l"; done
if [ -d /proc/oplus-votable ]; then
  log "  oplus votables: $(ls /proc/oplus-votable 2>/dev/null | tr '\n' ' ')"
fi
for vd in /sys/kernel/debug/*votable* /sys/kernel/debug/pmic-votable; do
  [ -d "$vd" ] || continue
  log "  debugfs votable: $vd: $(ls "$vd" 2>/dev/null | sed -n '1,8p' | tr '\n' ' ')"
done

log ""
log "==== LAYER S2 - smart-observe: what does the FIRMWARE toggle? (unplug diff) ===="
if [ "$ACTIVE" = 1 ] && gate; then
  state_dump "$SCHG"
  log "  charging fingerprint: $(wc -l < "$SCHG" | tr -d ' ') node values captured"
  log ""
  UNP=no; S2_FRAGILE=0
  case "${DRVS:-}" in *qpnp-smb*|*smb138*|*smb139*|*smb1390*|*smb1394*|*pmi632*|*pmi8998*|*pm7250*|*pm8550*|*oplus*|*mtk*|*mt[0-9]*) S2_FRAGILE=1;; esac
  if [ "$WANT_UNPLUG" != 1 ]; then
    log "  (not asking you to unplug in this mode -- some chargers drop their negotiation if unplugged"
    log "   mid-scan. LAYER 5's engage-diff still reads the firmware and the by-name layers find your switch"
    log "   without it. Keep the charger plugged in. For the HIGHEST-accuracy scan, re-run with --unplug"
    log "   (Highest accuracy in AccA) -- you'll be asked to unplug, and may need to replug.)"
  else
    log ""
    log "  ========================================================================"
    log "  >>>  HIGHEST ACCURACY: UNPLUG the charger for ~10s then plug it back in"
    log "  ========================================================================"
    log "  Why: when power leaves, the firmware writes its OWN charge-control nodes to their exact off-values"
    log "       (any shape -- 0/1, a number, even a string/opcode), and gives a ground-truth charge-stop"
    log "       signal even with no current sensor. This is the second, independent way to find + verify"
    log "       switches -- the most reliable on an unknown phone. (We only READ here. No writes.)"
    [ "$S2_FRAGILE" = 1 ] && log "  NOTE: your charger may drop fast-charge after this -- if it stops charging, just unplug + replug once more."
    _s2wait=30
    log "  Waiting up to ${_s2wait} sec for the UNPLUG..."
    ui=0
    while [ "$ui" -lt "$_s2wait" ]; do
      plugged || { UNP=yes; break; }
      sleep 1; ui=$((ui+1))
    done
  fi
  if [ "$UNP" = yes ]; then
    log "  charger removed -- reading the firmware's own switch writes..."
    et=0
    while [ "$et" -lt 4 ]; do
      log "    event t=${et}s status=$(read_st) current=$(san "$(read1 "$CURF")") online=$(online_now && echo 1 || echo 0)"
      sleep 1; et=$((et+1))
    done
    state_dump "$SUNP"
    OBS_UNPLUG="$(diff_pairs "$SCHG" "$SUNP" | wc -l | tr -d ' ')"
    log "  observe: $OBS_UNPLUG node value(s) changed when the firmware stopped charging"
    diff_pairs "$SCHG" "$SUNP" | sed -n '1,15p' | pclean2 | while read -r l; do log "    ~ $l"; done
    log ""
    log "  ========================================================================"
    log "  >>>  THANK YOU! NOW PLUG THE CHARGER BACK IN (waiting up to 60s)"
    log "  ========================================================================"
    ri=0
    while [ "$ri" -lt 60 ]; do
      plugged && break
      sleep 2; ri=$((ri+2))
      [ "$(( ri % 10 ))" = 0 ] && log "    ...waiting for charger ($(( 60 - ri ))s left)..."
    done
    if plugged; then
      if online_now; then
        log "  re-plugged -- thank you! Resuming Deep scan..."
      elif recover_online 45; then
        log "  re-negotiated OK (APSD/AICL re-kicked) -- charging is back; resuming Deep scan..."
      else
        warn "charger present but the firmware did NOT re-negotiate (online=0) even after an APSD/AICL re-kick. Use a SLOW/standard USB charger, or physically unplug + re-plug once more; later live tests auto-skip until charging resumes."
      fi
      sleep "$SETTLE"
      if [ "${CUR_USABLE:-1}" = 1 ] && [ -n "${CHGDIR:-}" ]; then
        _stz=0; _stok=0
        while [ "$_stz" -lt 24 ]; do
          over && break
          _stc="$(med_cur)"
          if [ "$(sgn "$_stc")" = "$CHGDIR" ] && [ "$(abs "$_stc")" -gt "$THR" ] 2>/dev/null; then _stok=$((_stok+1)); [ "$_stok" -ge 3 ] && break; else _stok=0; fi
          sleep 2; _stz=$((_stz+2))
        done
        [ "$_stok" -ge 3 ] && log "  charger re-stabilized after replug (~${_stz}s, current steady) -- staying in PRECISE current mode" || log "  charger still ramping after replug (~${_stz}s) -- proceeding"
      fi
    else
      warn "charger not re-plugged after 60s; later live tests may be skipped (charging restored)"
    fi
  else
    log "  (no unplug detected -- skipped. The engage-diff in LAYER 5 still observes the firmware.)"
  fi
else
  log "  (skipped -- no active charging to fingerprint)"
fi

log ""
stop_check; acc_hold_off
log "==== LAYER 4 - known charge switches (adaptive HOLD verify) ===="
[ "$ACTIVE" = 1 ] || log "  (active hold-tests skipped -- see warnings)"
CAND="$BK/cand.tsv"; : > "$CAND"
emit_known | while IFS='|' read -r pat onv offv; do
  [ -n "$pat" ] || continue
  for f in $(expand_paths "$pat"); do
    case "$f" in *current_now*|*voltage*|*temp*|*capacity*|*present*|*status*) continue;; esac
    printf '%s\t%s\t%s\n' "$f" "$onv" "$offv"
  done
done | sort -u > "$CAND"
_paths_on=""
for _s in usb main pc_port dc wireless wls ac; do
  [ -e "$PSY/$_s/online" ] && [ "$(read1 "$PSY/$_s/online" 2>/dev/null)" = 1 ] && _paths_on="$_paths_on $_s"
done
[ -n "$_paths_on" ] && log "  active charger path(s):$_paths_on"
FAST_CHG=0; _utype="$(read1 $PSY/usb/type 2>/dev/null) $(read1 $PSY/usb/real_type 2>/dev/null)"
case "$_utype" in *HVDCP*|*PD*|*PPS*|*DASH*|*WARP*|*SCP*|*VOOC*) FAST_CHG=1;; esac
DEFER_INPUT=0; [ "$FAST_CHG" = 1 ] && [ "${MODE:-quick}" != complete ] && DEFER_INPUT=1
: > "$BK/deferred"
[ "$DEFER_INPUT" = 1 ] && log "  fast charger ($_utype), quick mode -- input-cut switches deferred to last-resort (avoids de-negotiation; Deep maps them all)"
while IFS="	" read -r f onv offv; do
  stop_check
  [ -n "$f" ] || continue
  case "$f" in *input_suspend*|*force*suspend*|*usb_suspend*|*chg_suspend*|*charging_suspend*)
    if [ "$DEFER_INPUT" = 1 ]; then printf '%s\t%s\t%s\n' "$f" "$onv" "$offv" >> "$BK/deferred"; log "  [defer] $f (input-cut on a fast charger -- only tested last, if nothing safer holds)"; continue; fi;; esac
  _ps=""; case "$f" in
    "$PSY"/*) _ps="${f#$PSY/}"; _ps="${_ps%%/*}";;
  esac
  if [ -n "$_ps" ] && [ -n "$_paths_on" ]; then
    case " $_paths_on " in *" $_ps "*) :;;
      *) case "$_ps" in
           wireless|wls|dc) log "  [path-skip] $f (path '$_ps' offline; only:$_paths_on)"; continue;;
         esac;;
    esac
  fi
  test_switch "$f" "$f" "$onv" "$offv"
done < "$CAND"

if [ "$ACTIVE" = 1 ] && [ "${UNKNOWN:-0}" != 1 ]; then
  log ""
  stop_check; acc_hold_off
  log "==== LAYER 4b - ordered groups + current-zero family ===="
  if ex "$PSY/battery/op_disable_charge" && ex "$PSY/battery/charging_enabled"; then
    over || if gate; then snap_add "$PSY/battery/charging_enabled"; snap_add "$PSY/battery/op_disable_charge"
      cce="$(rd $PSY/battery/charging_enabled | sed -n '1p')"; cop="$(rd $PSY/battery/op_disable_charge | sed -n '1p')"
      wr "$PSY/battery/charging_enabled" 0; wr "$PSY/battery/op_disable_charge" 1
      hold_probe; ghit=0
      if [ "$SAMP_LAST" = 0 ] && [ "$SAMP_N" -lt 3 ]; then
        k="$(classify_held)"; ghit=1; log "  oneplus[charging_enabled=0 + op_disable_charge=1] -> last=$CL HELD-15s [$k]"
        route_hit "$k" "oneplus-seq" "battery/charging_enabled 0 0 battery/op_disable_charge 0 1 battery/charging_enabled 1 1 ($k, ordered)"
      elif [ "$SAMP_FIRST" = 0 ] && [ "$SAMP_LAST" = 1 ]; then
        REASSERT="$REASSERT|oneplus-seq"; log "  oneplus[seq] -> DROPPED-THEN-RESUMED [no-hold]"
      else
        log "  oneplus[charging_enabled=0 + op_disable_charge=1] -> last=$CL [no effect]"
      fi
      wr "$PSY/battery/op_disable_charge" "${cop:-0}"; wr "$PSY/battery/charging_enabled" "${cce:-1}"
      [ "$ghit" = 1 ] && resume_check "oneplus-seq" || sleep 1; fi
  fi
  if ex /proc/mtk_battery_cmd/current_cmd; then
    over || if gate; then snap_add /proc/mtk_battery_cmd/current_cmd; ex /proc/mtk_battery_cmd/en_power_path && snap_add /proc/mtk_battery_cmd/en_power_path
      ep=; ex /proc/mtk_battery_cmd/en_power_path && ep="$(rd /proc/mtk_battery_cmd/en_power_path | sed -n '1p')"
      ex /proc/mtk_battery_cmd/en_power_path && wr /proc/mtk_battery_cmd/en_power_path 1
      wr /proc/mtk_battery_cmd/current_cmd "0 1"
      hold_probe; ghit=0
      if [ "$SAMP_LAST" = 0 ] && [ "$SAMP_N" -lt 3 ]; then
        k="$(classify_held)"; ghit=1; log "  mtk[current_cmd '0 1' + en_power_path] -> last=$CL HELD-15s [$k]"
        route_hit "$k" "mtk-current_cmd" "/proc/mtk_battery_cmd/current_cmd 0::0 0::1 /proc/mtk_battery_cmd/en_power_path 1 0 ($k; verify long-term, current_cmd often re-asserts)"
      elif [ "$SAMP_FIRST" = 0 ] && [ "$SAMP_LAST" = 1 ]; then
        REASSERT="$REASSERT|mtk-current_cmd"; log "  mtk[current_cmd '0 1'] -> DROPPED-THEN-RESUMED [no-hold: WOULD OVERCHARGE - do NOT use; prefer input_suspend]"
      else
        log "  mtk[current_cmd '0 1'] -> last=$CL [no effect]"
      fi
      wr /proc/mtk_battery_cmd/current_cmd "0 0"; [ -n "$ep" ] && wr /proc/mtk_battery_cmd/en_power_path "$ep"
      [ "$ghit" = 1 ] && resume_check "mtk-current_cmd" || sleep 1; fi
  fi
  GP=""; gn=0
  for f in "$PSY/battery/constant_charge_current" "$PSY/main-charger/current_max" "$PSY/usb/current_max" "$PSY/gccd/current_max" "$PSY/dc/current_max"; do
    ex "$f" && { GP="$GP $f"; gn=$((gn+1)); }
  done
  if [ "$gn" -ge 2 ] && ! over; then
    if gate; then
      : > "$BK/grp.tsv"
      for f in $GP; do snap_add "$f"; printf '%s\t%s\n' "$f" "$(rd "$f" | sed -n '1p')" >> "$BK/grp.tsv"; done
      for f in $GP; do wr "$f" 0; done
      hold_probe; ghit=0
      if [ "$SAMP_LAST" = 0 ] && [ "$SAMP_N" -lt 3 ]; then
        k="$(classify_held)"; ghit=1; log "  pixel-group[$gn current paths -> 0] -> last=$CL HELD-15s [$k]"
        gl=""; for f in $GP; do gl="$gl$f 5000000 0 "; done
        route_hit "$k" "pixel-group($gn paths)" "$gl($k, GROUPED: one ctrl-files line, all paths together)"
      elif [ "$SAMP_FIRST" = 0 ] && [ "$SAMP_LAST" = 1 ]; then
        REASSERT="$REASSERT|pixel-group"; log "  pixel-group[$gn paths] -> DROPPED-THEN-RESUMED [no-hold]"
      else
        log "  pixel-group[$gn current paths -> 0] -> last=$CL [no effect]"
      fi
      while IFS="	" read -r f v; do [ -n "$f" ] && wr "$f" "$v"; done < "$BK/grp.tsv"
      [ "$ghit" = 1 ] && resume_check "pixel-group" || sleep 1
    fi
  fi
  cz=0
  for d in $PSY/*; do
    [ "$cz" -ge "$MAX_CURR" ] && break
    dn="${d##*/}"
    case "$dn" in battery|usb|ac|dc|main|main-charger|mainchg|gccd|wireless|mtk*|smb*|rt9*|bbc) :;; *) continue;; esac
    for nm in constant_charge_current constant_charge_current_max; do
      [ "$cz" -ge "$MAX_CURR" ] && break
      f="$d/$nm"; ex "$f" || continue
      case "$f" in *parallel*|*bq[0-9]*current_max*) continue;; esac
      cv="$(read1 "$f")"
      case "$cv" in ''|0|*[!0-9]*) continue;; esac
      test_switch "fcc-zero $f" "$f" "$cv" 0
      cz=$((cz+1))
    done
  done
  log "  -> tested $cz battery-FCC current cut(s) (input-side current limits skipped: 0 can mean 'unlimited' on some kernels)"
  if ex "$PSY/battery/charge_control_limit" && ex "$PSY/battery/charge_control_limit_max" && ! over; then
    cclnow="$(read1 "$PSY/battery/charge_control_limit")"; cclmax="$(read1 "$PSY/battery/charge_control_limit_max")"
    case "$cclmax" in ''|*[!0-9]*) :;; *) if [ "$cclmax" -gt 0 ] 2>/dev/null && [ "$cclmax" != "$cclnow" ]; then
      test_switch "charge_control_limit=$cclmax" "$PSY/battery/charge_control_limit" "$cclnow" "$cclmax"
    fi;; esac
  fi
fi

if [ "$ACTIVE" = 1 ] && [ "${MODE:-quick}" = complete ] && [ "${UNKNOWN:-0}" != 1 ]; then
  _chsw=
  for _csf in /dev/.vr25/acc/ch-switches /data/adb/vr25/acc-data/ch-switches; do [ -f "$_csf" ] && { _chsw="$_csf"; break; }; done
  if [ -n "$_chsw" ]; then
    log ""; stop_check; acc_hold_off
    log "==== LAYER 4c - ACC's own auto-detected switches (complete: test every one ACC knows) ===="
    _ac=0
    while IFS= read -r _cl; do
      over && { log "  [deadline] stop ACC-list tests"; break; }
      [ "$_ac" -ge "$MAX_NEW" ] && { log "  [cap] reached"; break; }
      set -f; set -- $_cl; set +f
      [ "$#" -eq 3 ] || continue
      _cp="$1"; _con="$2"; _cof="$3"
      case "$_cp" in /*) :;; *) _cp="$PSY/$_cp";; esac
      ex "$_cp" || continue
      case "$_cof" in ''|*[!0-9]*) continue;; esac
      grep -qxF "$_cp|$_cof" "$BK/tested" 2>/dev/null && continue
      printf '%s' "$_cp" | grep -Eiq "$DANGER_RE" && continue
      printf '%s' "$_cp" | grep -Eiq "$SUPERDENY_RE" && continue
      printf '%s' "${_cp##*/}" | grep -Eq '_now$' && continue
      test_switch "[ACC] $_cp=$_cof" "$_cp" "$_con" "$_cof"
      _ac=$((_ac+1))
    done < "$_chsw"
    log "  -> tested $_ac switch(es) from ACC's own detected list (not already covered above)"
  fi
fi

log ""
stop_check; acc_hold_off
log "==== LAYER 5 - native level limits (write+readback+engage, + engage-diff observe) ===="
test_level "google charge_stop_level" /sys/devices/platform/google,charger/charge_stop_level /sys/devices/platform/google,charger/charge_start_level
test_level "google(soc) charge_stop_level" /sys/devices/platform/soc/soc:google,charger/charge_stop_level /sys/devices/platform/soc/soc:google,charger/charge_start_level
for f in $(expand_paths "*/batt_full_capacity"); do test_level "samsung $f" "$f" ""; done
test_level "charge_control_end_threshold" "$PSY/battery/charge_control_end_threshold" "$PSY/battery/charge_control_start_threshold"
test_level "lge charge_stop_level" /sys/module/lge_battery/parameters/charge_stop_level ""
test_level "qpnp upper_limit" /sys/module/qpnp_adaptive_charge/parameters/upper_limit "" -1
if [ "$ACTIVE" = 1 ] && q_more && [ "${UNKNOWN:-0}" != 1 ] && ex "$PSY/battery/voltage_max" && ! over; then
  vmo="$(read1 "$PSY/battery/voltage_max")"
  case "$vmo" in ''|*[!0-9]*) :;; *)
    cmv="$(vmv)"
    if [ "$vmo" -ge 3900000 ] 2>/dev/null && [ "$cmv" -ge 3650 ] 2>/dev/null; then
      vcap=$(( (cmv - 60) * 1000 ))
      if [ "$vcap" -ge 3500000 ] 2>/dev/null && [ "$vcap" -lt "$vmo" ] 2>/dev/null; then
        test_switch "voltage-cap battery/voltage_max=$vcap" "$PSY/battery/voltage_max" "$vmo" "$vcap"
      fi
    fi ;;
  esac
fi

if [ "$ACTIVE" = 1 ] && [ -s "$BK/deferred" ]; then
  if [ -z "$BYPASS$CUT$DRAIN$LEVELOK$BYPASS_HELD" ]; then
    log ""; stop_check; acc_hold_off
    log "==== LAYER 4d - deferred input-cut switches (last resort -- nothing safer held) ===="
    while IFS="	" read -r f onv offv; do stop_check; [ -n "$f" ] && test_switch "$f" "$f" "$onv" "$offv"; done < "$BK/deferred"
  else
    log "  (input-cut switches stayed deferred -- a battery-side switch already holds, so the USB input was never disturbed)"
  fi
fi

log ""
log "==== LAYER 6 - discovery (read-only report of all charge-control nodes) ===="
SAFE_RE='charging_enabled|battery_charging_enabled|charge_enabled|charging_enable|enable_charging|enable_charger|input_suspend|battery_input_suspend|op_disable_charge|disable_charging|charge_disable|batt_slate_mode|mmi_charging_enable|smart_charging_interruption|batt_protect_en|night_charging|bypass_charger|disable_charger|charging_suspend_en|charger_control|force_charger_suspend|force_usb_suspend'
n_disc=0
for d in $DDIRS_ALL; do ex "$d" || continue
  for f in $( { if [ "$HAVE_TO" = 1 ]; then timeout "$TO" find -L "$d" -maxdepth 2 -type f 2>/dev/null; else find -L "$d" -maxdepth 2 -type f 2>/dev/null; fi; } | awk '!seen[$0]++' | sed -n '1,400p' ); do
    printf '%s' "$f" | grep -Eq "$DENY_RE" && continue
    printf '%s' "$f" | grep -Eqi "$NAME_RE" || continue
    grep -qF "$f|" "$DISC" 2>/dev/null && continue
    [ "$n_disc" -ge "$DISC_CAP" ] && break
    v="$(rd "$f" | sed -n '1p' | tr '\t' ' ' | pclean | cut -c1-48)"; w=ro; [ -w "$f" ] && w=RW
    log "  [$w] $f = $v"; printf '%s|%s\n' "$f" "$w" >> "$DISC"; n_disc=$((n_disc+1))
  done
done
log "  -> $n_disc charge-control node(s) on this device"
if [ -s "$DISC" ]; then
  while IFS='|' read -r dpath dw; do
    [ -n "$dpath" ] || continue
    dd="$(dirname "$dpath")"
    case " $DDIRS_ALL " in *" $dd "*) :;; *) DDIRS_ALL="$DDIRS_ALL $dd";; esac
  done < "$DISC"
fi

tested_new=0
if [ "$ACTIVE" = 1 ] && [ -s "$DISC" ]; then
  log ""
  log "  -- LAYER 6b - test NEW *trusted-named* switches ACC's list missed (unknown names are reported, not written) --"
  SUPER_RUN=0; { [ "${UNKNOWN:-0}" = 1 ] || [ "${MODE:-quick}" = complete ] || [ -z "$BYPASS$CUT$DRAIN$LEVELOK$BYPASS_HELD" ]; } && SUPER_RUN=1
  [ "$SUPER_RUN" = 1 ] && [ "${UNKNOWN:-0}" != 1 ] && log "  (SUPER fallback ON: the DB found no switch yet -- discovering + testing unknown candidates by shape)"
  while IFS='|' read -r path w; do
    over && { log "  [deadline] stop new tests"; break; }
    [ "$SKIPALL" = 1 ] && break
    [ "$tested_new" -ge "$MAX_NEW" ] && { log "  [cap] MAX_NEW reached"; break; }
    [ "$w" = RW ] || continue
    { [ "${UNKNOWN:-0}" != 1 ] && grep -qxF "$path" "$SNLIST" 2>/dev/null; } && continue
    bn="$(basename "$path")"
    cur="$(rd "$path" | sed -n '1p')"
    bool_like "$cur" || continue
    if q_more && safe_to_write "$path"; then
      off="$(flip_val "$cur")"; [ -n "$off" ] || continue
      grep -qxF "$path|$off" "$BK/tested" 2>/dev/null && continue
      bH="$BYPASS$CUT$DRAIN"
      test_switch "[NEW] $path=$off" "$path" "$cur" "$off"
      [ "$BYPASS$CUT$DRAIN" != "$bH" ] && NEWHITS="$NEWHITS|$path on=$cur off=$off"
      tested_new=$((tested_new+1))
    elif q_more && [ "${SUPER:-1}" = 1 ] && [ "${SUPER_RUN:-0}" = 1 ] && super_safe "$path"; then
      off="$(flip_val "$cur")"; [ -n "$off" ] || continue
      grep -qxF "$path|$off" "$BK/tested" 2>/dev/null && continue
      bH="$BYPASS$CUT$DRAIN"
      test_switch "[SUPER] $path=$off" "$path" "$cur" "$off"
      [ "$BYPASS$CUT$DRAIN" != "$bH" ] && SUPERHITS="$SUPERHITS|$path on=$cur off=$off"
      tested_new=$((tested_new+1))
    elif printf '%s' "$bn" | grep -Eqi 'charg|chg|suspend|enable|disable|bypass|slate|mmi|protect|night' && ! printf '%s' "$path" | grep -Eiq "$DANGER_RE" && ! printf '%s' "$bn" | grep -Eq "$EFFECT_RE"; then
      flag_observe "$path" "=$cur RW switch-like, name not in trusted set -> NOT written"
    fi
  done < "$DISC"
  log "  -> tested $tested_new trusted new switch(es); flagged ${obs_n:-0} unrecognized candidate(s) for manual review (not written)"
fi

if ! q_more; then
  log ""
  log "  [quick early-exit] a holds-alone bypass / verified %-limit is already proven on this phone --"
  log "                     skipping deep-discovery layers 6f/6c/6d/6e (run with Deep/--complete for the"
  log "                     exhaustive sweep: ACC-list 4c, value-sweep 6f, firmware combos 6c/6d, teaching 6e)."
fi
log ""
log "==== LAYER 6f - value-sweep for numeric charge-cap nodes (single-node numeric discovery; runs after 6b, before 6c) ===="
if [ "$ACTIVE" = 1 ] && q_more && [ -s "$DISC" ]; then
  _f5a_n=0; _f5a_max=4
  while IFS='|' read -r path w; do
    over && { log "  [deadline] stop value-sweep"; break; }
    [ "$SKIPALL" = 1 ] && break
    [ "$_f5a_n" -ge "$_f5a_max" ] && { log "  [cap] F5a sweep limit reached"; break; }
    [ "$w" = RW ] || continue
    bn="$(basename "$path")"
    case "$bn" in
      *temp*|*therm*) continue;;
      *_limit_max|*_control_limit_max) continue;;
      *_limit|*_level|*_cap|*_max|*_threshold|*current_max|*voltage_max) :;;
      *) continue;;
    esac
    cur="$(rd "$path" | sed -n '1p')"
    bool_like "$cur" && continue
    val_class "$cur" || continue
    if safe_to_write "$path"; then :;
    elif [ "${SUPER:-1}" = 1 ] && super_safe "$path"; then :;
    else continue; fi
    _maxp="${path}_max"
    [ -f "$_maxp" ] || _maxp="${path%/*}/${bn%_*}_max"
    _maxv=""; [ -f "$_maxp" ] && _maxv="$(rd "$_maxp" | sed -n '1p')"
    _f5a_attempt=0; _f5a_per=2
    for _v in "$_maxv" 1; do
      [ "$_f5a_attempt" -ge "$_f5a_per" ] && break
      [ -z "$_v" ] && continue
      val_class "$_v" || continue
      [ "$_v" = "$cur" ] && continue
      case "$bn" in *current*|*voltage*|*constant_charge*|*_fcc*|*_icl*) [ "$_v" = 1 ] && continue;; esac
      grep -qxF "$path|$_v" "$BK/tested" 2>/dev/null && continue
      bH="$BYPASS$CUT$DRAIN"
      test_switch "[F5a] $path=$_v" "$path" "$cur" "$_v"
      _f5a_attempt=$((_f5a_attempt+1))
      [ "$BYPASS$CUT$DRAIN" != "$bH" ] && { SUPERHITS="$SUPERHITS|$path on=$cur off=$_v (value-sweep)"; break; }
    done
    _f5a_n=$((_f5a_n+1))
  done < "$DISC"
  log "  -> swept $_f5a_n numeric-cap candidate(s)"
else
  log "  (skipped: quick mode, or no discovered nodes)"
fi

log ""
log "==== LAYER 6g - name-agnostic shape discovery (Deep: deny-clean bool switches the name-DB missed) ===="
if [ "$ACTIVE" = 1 ] && [ "${MODE:-quick}" = complete ]; then
  _sh_n=0; _shexam=0; SHAPE_SCAN_CAP=250
  for _shd in $DDIRS_ALL; do
    ex "$_shd" || continue
    over && break; [ "$SKIPALL" = 1 ] && break
    [ "$_sh_n" -ge "$MAX_NEW" ] && break
    [ "$_shexam" -ge "$SHAPE_SCAN_CAP" ] && break
    for _shf in $( { if [ "$HAVE_TO" = 1 ]; then timeout "$TO" find -L "$_shd" -maxdepth 2 -type f 2>/dev/null; else find -L "$_shd" -maxdepth 2 -type f 2>/dev/null; fi; } | awk '!seen[$0]++' | sed -n '1,300p' ); do
      over && break
      [ "$_sh_n" -ge "$MAX_NEW" ] && break
      [ "$_shexam" -ge "$SHAPE_SCAN_CAP" ] && { log "  [scan-cap] examined ${_shexam} candidate node(s) -- stopping name-agnostic scan (raise cap if a phone needs more)"; break 2; }
      [ -w "$_shf" ] || continue
      printf '%s' "${_shf##*/}" | grep -Eqi "$NAME_RE" && continue
      grep -qxF "$_shf" "$SNLIST" 2>/dev/null && continue
      grep -qF "$_shf|" "$DISC" 2>/dev/null && continue
      shape_safe "$_shf" || continue
      _shexam=$((_shexam+1))
      _shv="$(rd1 "$_shf" | sed -n '1p')"
      bool_like "$_shv" || continue
      _shoff="$(flip_val "$_shv")"; [ -n "$_shoff" ] || continue
      grep -qxF "$_shf|$_shoff" "$BK/tested" 2>/dev/null && continue
      bH="$BYPASS$CUT$DRAIN"
      test_switch "[SHAPE] $_shf=$_shoff" "$_shf" "$_shv" "$_shoff"
      [ "$BYPASS$CUT$DRAIN" != "$bH" ] && SUPERHITS="$SUPERHITS|$_shf on=$_shv off=$_shoff (name-agnostic shape)"
      _sh_n=$((_sh_n+1))
    done
  done
  log "  -> shape-tested $_sh_n deny-clean bool node(s) the name-DB did not recognize (examined ${_shexam})"
else
  log "  (Deep mode only)"
fi

log ""
log "==== LAYER 6c - probe GENERATED candidates (learned by WATCHING the firmware) ===="
: > "$GENC"
if [ -s "$SCHG" ]; then
  { [ -s "$SHELD" ] && diff_pairs "$SCHG" "$SHELD" | sed 's/^/6|/'
    [ -s "$SUNP" ] && diff_pairs "$SCHG" "$SUNP" | sed 's/^/4|/'; } 2>/dev/null | sort -t'|' -k1,1nr | awk -F'|' '!seen[$2]++' > "$GENC"
fi
tested_gen=0
if [ "$ACTIVE" = 1 ] && q_more && [ -s "$GENC" ]; then
  while IFS='|' read -r score path von voff; do
    over && { log "  [deadline] stop generated tests"; break; }
    [ "$SKIPALL" = 1 ] && break
    [ "$tested_gen" -ge "$MAX_GEN" ] && { log "  [cap] MAX_GEN reached"; break; }
    [ -n "$path" ] && [ -w "$path" ] || continue
    grep -qxF "$path|$voff" "$BK/tested" 2>/dev/null && continue
    case "$path" in */current_cmd) continue;; esac
    val_class "$von" && val_class "$voff" || continue
    [ "$von" = "$voff" ] && continue
    if ! safe_to_write "$path"; then
      if [ "${SUPER:-1}" = 1 ] && super_safe "$path"; then :; else
        printf '%s' "$path" | grep -Eiq "$DANGER_RE" || printf '%s' "${path##*/}" | grep -Eq "$EFFECT_RE" || flag_observe "$path" "(firmware-moved, name not in trusted set -> NOT written)"
        continue
      fi
    fi
    src=unplug; [ "$score" = 6 ] && src=engage
    test_switch "[GEN:$src] $path=$voff" "$path" "$von" "$voff"
    case "|$BYPASS|$CUT|$DRAIN|" in *"|[GEN:$src] $path=$voff|"*) GENHITS="$GENHITS|$path on=$von off=$voff (observed: firmware wrote $voff when charging stopped)"; SUPERHITS="$SUPERHITS|$path on=$von off=$voff (firmware-taught)";; esac
    tested_gen=$((tested_gen+1))
  done < "$GENC"
  log "  -> attempted $tested_gen firmware-observed candidate(s)"
else
  if [ -s "$SCHG" ]; then
    log "  (observe ran, but no firmware-toggled candidate passed the filters this run)"
  else
    log "  (no observe data -- no active charging to fingerprint)"
  fi
fi

log ""
log "==== LAYER 6d - COMBO engine (firmware-observed nodes tested TOGETHER) ===="
COMBO="$BK/combo.tsv"
if [ -s "$GENC" ]; then
  while IFS='|' read -r score path von voff; do
    [ -n "$path" ] && [ -w "$path" ] || continue
    safe_to_write "$path" || continue
    case "$path" in */current_cmd) continue;; esac
    val_class "$von" && val_class "$voff" || continue
    [ "$von" = "$voff" ] && continue
    grep -qxF "$path" "$BK/combo_seen" 2>/dev/null && continue
    printf '%s\n' "$path" >> "$BK/combo_seen"
    printf '%s\t%s\t%s\n' "$path" "$von" "$voff" >> "$COMBO"
  done < "$GENC"
fi
cn=0; [ -s "$COMBO" ] && cn="$(wc -l < "$COMBO" | tr -d ' ')"
GEN_SINGLE_HIT=0; case "|$BYPASS|$CUT|" in *"[GEN:"*) GEN_SINGLE_HIT=1;; esac
if [ "$ACTIVE" = 1 ] && q_more && [ "$SKIPALL" = 0 ] && [ "$cn" -ge 2 ] 2>/dev/null && [ "$cn" -le 6 ] 2>/dev/null && [ "$GEN_SINGLE_HIT" = 0 ] && ! over; then
  if gate; then
    while IFS="	" read -r p von voff; do snap_add "$p"; done < "$COMBO"
    while IFS="	" read -r p von voff; do wr "$p" "$voff"; done < "$COMBO"
    hold_probe; cheld=0
    if [ "$SAMP_LAST" = 0 ] && [ "$SAMP_N" -lt 3 ]; then
      k="$(classify_held)"; cheld=1
      log "  combo[$cn observed nodes together] -> last=$CL HELD-15s [$k] -- minimizing..."
      minimal=""
      while IFS="	" read -r p von voff; do
        over && { minimal="$minimal$p $von $voff "; continue; }
        wr "$p" "$von"
        sleep 6
        if [ "$(chg_now)" = 1 ]; then
          wr "$p" "$voff"; minimal="$minimal$p $von $voff "
          log "    $p : REQUIRED (re-enabling it resumed charging)"
          sleep 2
        else
          log "    $p : not needed (hold persists without it)"
        fi
      done < "$COMBO"
      [ -n "$minimal" ] || { minimal="$(awk -F'\t' '{printf "%s %s %s ", $1, $2, $3}' "$COMBO")"; log "    (minimization inconclusive -- keeping the full set)"; }
      route_hit "$k" "firmware-combo($cn)" "$minimal($k, COMBO learned by watching the firmware -- one grouped ctrl-files line)"
      GENHITS="$GENHITS|COMBO: $minimal($k)"
    elif [ "$SAMP_FIRST" = 0 ] && [ "$SAMP_LAST" = 1 ]; then
      REASSERT="$REASSERT|firmware-combo"; log "  combo[$cn nodes] -> DROPPED-THEN-RESUMED [no-hold]"
    else
      log "  combo[$cn observed nodes together] -> last=$CL [no effect]"
    fi
    while IFS="	" read -r p von voff; do wr "$p" "$von"; done < "$COMBO"
    if [ "$cheld" = 1 ]; then resume_check "firmware-combo"; else sleep 1; fi
  fi
else
  if [ "$cn" -gt 0 ] 2>/dev/null; then log "  (combo skipped: $cn candidate(s), a single observed switch already worked: $GEN_SINGLE_HIT)"; else log "  (no combo candidates)"; fi
fi

if [ "$ACTIVE" = 1 ] && [ "${MODE:-quick}" = complete ] && [ "$SKIPALL" = 0 ] && ! over \
   && [ -z "${BYPASS_HELD:-}" ] && [ "${cn:-0}" -ge 3 ] && [ "${cheld:-0}" != 1 ]; then
  log ""
  log "  -- LAYER 6d.2 widened pair-sweep (firmware-observed nodes tested in 2-pairs) --"
  if gate; then
    _p_n=0; _p_max=5
    awk -F'\t' 'NR<=4' "$COMBO" > "$BK/combo_top.tsv" 2>/dev/null
    while IFS='	' read -r p1 von1 voff1; do
      while IFS='	' read -r p2 von2 voff2; do
        [ "$p1" = "$p2" ] && continue
        over && break
        [ "$_p_n" -ge "$_p_max" ] && break 2
        [ "$SKIPALL" = 1 ] && break 2
        safe_to_write "$p1" || super_safe "$p1" || continue
        safe_to_write "$p2" || super_safe "$p2" || continue
        bH="$BYPASS$CUT$DRAIN"
        snap_add "$p1"; snap_add "$p2"
        wr "$p1" "$voff1"; wr "$p2" "$voff2"
        hold_probe
        if [ "$SAMP_LAST" = 0 ] && [ "$SAMP_N" -lt 3 ]; then
          k="$(classify_held)"
          log "  pair[$p1=$voff1 + $p2=$voff2] -> last=$CL HELD [$k]"
          route_hit "$k" "pair-combo($p1+$p2)" "$p1 $von1 $voff1; $p2 $von2 $voff2 ($k, COMBO 2-node pair from firmware-observed set)"
          GENHITS="$GENHITS|PAIR-COMBO: $p1+$p2 ($k)"
        fi
        wr "$p1" "$von1"; wr "$p2" "$von2"; sleep 2
        _p_n=$((_p_n+1))
        [ "$BYPASS$CUT$DRAIN" != "$bH" ] && break 2
      done < "$BK/combo_top.tsv"
    done < "$BK/combo_top.tsv"
    log "  -> tried $_p_n pair(s) from the firmware-observed candidate set"
  fi
fi

if [ "$ACTIVE" = 1 ] && [ -z "$TEACH_P" ] && [ "${MODE:-quick}" = complete ] && ! over && gate; then
  log ""
  log "  (no switch held yet -- trying a name-independent inducer to generate the firmware frame)"
  for _ip in "$BATT/constant_charge_current_max" "$PSY/main/constant_charge_current_max" "$PSY/usb/current_max" "$PSY/main/current_max"; do
    ex "$_ip" || continue; [ -w "$_ip" ] || continue
    super_safe "$_ip" || continue
    _iv="$(rd "$_ip" | sed -n '1p')"; case "$_iv" in ''|0|*[!0-9]*) continue;; esac
    snap_add "$_ip"; wr "$_ip" 0; sleep "$SETTLE"
    if [ "$(chg_now)" = 0 ]; then TEACH_P="$_ip"; TEACH_ON="$_iv"; TEACH_OFF=0; log "    inducer: $_ip=0 stops charging -> generating the firmware frame with it"; wr "$_ip" "$_iv"; sleep 2; break; else wr "$_ip" "$_iv"; sleep 1; fi
  done
fi
log ""
log "==== LAYER 6e - FIRMWARE TEACHING (induce a known cut, learn every co-moving node, test+verify each as its own switch) ===="
if [ "$ACTIVE" = 1 ] && q_more && [ "$SKIPALL" = 0 ] && [ -n "$TEACH_P" ] && ex "$TEACH_P" && ! over && gate; then
  log "  teacher = $TEACH_P (on=$TEACH_ON off=$TEACH_OFF) -- inducing a real charge-stop, then watching every node the firmware moves."
  state_dump "$BK/teach_chg.tsv"
  snap_add "$TEACH_P"; wr "$TEACH_P" "$TEACH_OFF"
  ts=0; while [ "$ts" -lt 10 ] && [ "$(chg_now)" = 1 ]; do sleep 1; ts=$((ts+1)); done
  if [ "$(chg_now)" = 1 ]; then
    log "  (teacher did not stop charging this attempt -- skipping teach; the diff layers above still apply)"
    wr "$TEACH_P" "$TEACH_ON"
  else
    state_dump "$SHELD2"
    wr "$TEACH_P" "$TEACH_ON"; sleep "$SETTLE"
    tr=0; while [ "$tr" -lt 8 ] && [ "$(chg_now)" = 0 ]; do sleep 2; tr=$((tr+2)); [ "$tr" = 4 ] && defaults_native; done
    diff_pairs "$BK/teach_chg.tsv" "$SHELD2" > "$TEACHC"
    NLEARN="$(wc -l < "$TEACHC" | tr -d ' ')"
    log "  firmware moved $NLEARN node(s) while charging was held off (the learning set):"
    sed -n '1,24p' "$TEACHC" | pclean2 | while read -r l; do log "    ~ $l"; done
    : > "$BK/teach_rank.tsv"
    while IFS='|' read -r tp tcharg theld; do
      [ -n "$tp" ] && [ -w "$tp" ] || continue
      case "$tp" in */current_cmd) continue;; esac
      val_class "$theld" && val_class "$tcharg" || continue
      [ "$theld" = "$tcharg" ] && continue
      grep -qxF "$tp|$theld" "$BK/tested" 2>/dev/null && continue
      if safe_to_write "$tp"; then
        printf '%s\t%s\t%s\t%s\n' "$(rel_score "$tp")" "$tp" "$tcharg" "$theld" >> "$BK/teach_rank.tsv"
      elif [ "${SUPER:-1}" = 1 ] && super_safe "$tp"; then
        printf '%s\t%s\t%s\t%s\n' "$(rel_score "$tp")" "$tp" "$tcharg" "$theld" >> "$BK/teach_rank.tsv"
        log "    [F5c] $tp -> queued for daemon-test (firmware moved it + gate clean)"
      else
        printf '%s' "$tp" | grep -Eiq "$DANGER_RE" || printf '%s' "${tp##*/}" | grep -Eq "$EFFECT_RE" || flag_observe "$tp" "(firmware-taught, name not in trusted set -> NOT written)"
      fi
    done < "$TEACHC"
    sort -t'	' -k1,1nr "$BK/teach_rank.tsv" > "$BK/teach_sorted.tsv" 2>/dev/null
    : > "$BK/teach_combo.tsv"; TEACHED=0; TBUILT=0
    _l6e_t0=$(date +%s 2>/dev/null); _l6e_budget=60; [ "${MODE:-quick}" = complete ] && _l6e_budget=150
    while IFS='	' read -r rs tp tcharg theld; do
      over && { log "  [deadline] stop teaching tests"; break; }
      [ "$SKIPALL" = 1 ] && break
      [ "$TEACHED" -ge "$MAX_TEACH" ] && { log "  [cap] MAX_TEACH reached"; break; }
      _l6e_now=$(date +%s 2>/dev/null); [ -n "$_l6e_t0" ] && [ "$(( _l6e_now - _l6e_t0 ))" -ge "$_l6e_budget" ] && { log "  [time-box] L6e ${_l6e_budget}s budget reached after $TEACHED node(s)"; break; }
      grep -qxF "$tp|$theld" "$BK/tested" 2>/dev/null && continue
      gate || break
      bH="$BYPASS$CUT$DRAIN"
      test_switch "[LEARN r$rs] $tp=$theld" "$tp" "$tcharg" "$theld"
      TEACHED=$((TEACHED+1))
      if [ "$BYPASS$CUT$DRAIN" != "$bH" ]; then
        LEARNED="$LEARNED|$tp on=$tcharg off=$theld (firmware-taught + independently verified ALONE)"
        TBUILT=$((TBUILT+1))
      else
        printf '%s\t%s\t%s\n' "$tp" "$tcharg" "$theld" >> "$BK/teach_combo.tsv"
      fi
    done < "$BK/teach_sorted.tsv"
    log "  -> taught+tested $TEACHED node(s); $TBUILT verified as an independent working switch"
    lc=0; [ -s "$BK/teach_combo.tsv" ] && lc="$(wc -l < "$BK/teach_combo.tsv" | tr -d ' ')"
    if [ "$lc" -ge 2 ] 2>/dev/null && [ "$lc" -le 6 ] 2>/dev/null && ! over && gate; then
      log "  $lc taught node(s) moved with the cut but did not stop charging alone -- testing them TOGETHER (building a combo switch)..."
      while IFS='	' read -r p von voff; do snap_add "$p"; done < "$BK/teach_combo.tsv"
      while IFS='	' read -r p von voff; do wr "$p" "$voff"; done < "$BK/teach_combo.tsv"
      hold_probe; tcheld=0
      if [ "$SAMP_LAST" = 0 ] && [ "$SAMP_N" -lt 3 ]; then
        k="$(classify_held)"; tcheld=1
        log "  teach-combo[$lc] -> HELD [$k] -- minimizing to the essential line(s)..."
        minimal=""
        while IFS='	' read -r p von voff; do
          over && { minimal="$minimal$p $von $voff "; continue; }
          wr "$p" "$von"; sleep 6
          if [ "$(chg_now)" = 1 ]; then wr "$p" "$voff"; minimal="$minimal$p $von $voff "; log "    $p : REQUIRED (re-enabling it resumed charging)"; sleep 2
          else log "    $p : not needed (hold persists without it)"; fi
        done < "$BK/teach_combo.tsv"
        [ -n "$minimal" ] || minimal="$(awk -F'	' '{printf "%s %s %s ", $1,$2,$3}' "$BK/teach_combo.tsv")"
        route_hit "$k" "teach-combo($lc)" "$minimal($k, BUILT from firmware-taught co-moving nodes -- one grouped ctrl line)"
        BUILT="$BUILT|$minimal($k)"
      else
        log "  teach-combo[$lc] -> no hold (those nodes were effects, not causes)"
      fi
      while IFS='	' read -r p von voff; do wr "$p" "$von"; done < "$BK/teach_combo.tsv"
      [ "$tcheld" = 1 ] && resume_check "teach-combo" || sleep 1
    fi
  fi
else
  if [ -z "$TEACH_P" ]; then log "  (no confirmed switch yet to teach from -- the engine needs one working CUT/level switch to induce + learn; none confirmed above)"
  elif [ "$ACTIVE" = 0 ]; then log "  (skipped -- active tests are off this run)"
  else log "  (skipped -- charging not active right now / deadline)"; fi
fi

log ""
log "==== LAYER 7 - inventory + Android view + ACC internals ===="
for d in $PSY/*; do
  log "  supply: ${d##*/}  type=$(read1 "$d/type")  online=$(read1 "$d/online")  present=$(read1 "$d/present")  status=$(rd "$d/status" | sed -n '1p' | pclean)"
done
log "  battery uevent (charge keys):"
rd "$BATT/uevent" | grep -E 'POWER_SUPPLY_(NAME|STATUS|HEALTH|CHARGE_TYPE|CAPACITY|CURRENT_NOW|CONSTANT_CHARGE|INPUT_|VOLTAGE_MAX|BATT_)' | sed -n '1,20p' | pclean2 | while read -r l; do log "    $l"; done
if command -v dumpsys >/dev/null 2>&1; then
  log "  Android (dumpsys battery):"
  dumpsys battery 2>/dev/null | sed -n '2,12p' | pclean2 | while read -r l; do log "    $l"; done
fi
if command -v logcat >/dev/null 2>&1; then
  log "  logcat (battery/charge events, last 20):"
  { if [ "$HAVE_TO" = 1 ]; then timeout "$TO" logcat -d -t 400 2>/dev/null; else logcat -d -t 400 2>/dev/null; fi; } | grep -iE 'charg|battery|BatteryService|healthd|power_supply' | tail -20 | cut -c1-160 | pclean2 | while read -r l; do log "    $l"; done
  log "  logcat events buffer (battery, last 10):"
  { if [ "$HAVE_TO" = 1 ]; then timeout "$TO" logcat -d -b events -t 200 2>/dev/null; else logcat -d -b events -t 200 2>/dev/null; fi; } | grep -iE 'battery|power|charg' | tail -10 | cut -c1-160 | pclean2 | while read -r l; do log "    $l"; done
fi
for al in /dev/.vr25/acc/accd-*.log; do
  ex "$al" || continue
  log "  ACC daemon log tail ($al):"
  tail -20 "$al" 2>/dev/null | cut -c1-160 | pclean2 | while read -r l; do log "    $l"; done
  break
done
if [ -n "$ACCV" ]; then
  acc_cs="$(grep '^chargingSwitch=' /data/adb/vr25/acc-data/config.txt 2>/dev/null)"
  log "  ACC charging_switch: $acc_cs"
  ACC_SW_NOW="$(printf '%s' "$acc_cs" | sed -n 's/^chargingSwitch=(*//;s/).*//p' | awk '{print $1}')"
  ACC_SW_NOW_FULL="$(printf '%s' "$acc_cs" | sed -n 's/^chargingSwitch=(*//;s/).*//p' | awk '{print $1, $2, $3}')"
  log "  ACC ch-switches (what ACC auto-detected):"
  sed -n '1,40p' /dev/.vr25/acc/ch-switches 2>/dev/null | pclean2 | while read -r l; do log "    $l"; done
  log "  ACC session blacklist: $(cat /dev/.vr25/acc/.sw-blacklist 2>/dev/null | tr '\n' ';')"
  log "  ACC working-switches.log:"
  sed -n '1,20p' /data/adb/vr25/acc-data/logs/working-switches.log 2>/dev/null | pclean2 | while read -r l; do log "    $l"; done
  ACC_IDLE="$(sed -n 's/^\[i\] *//p' /data/adb/vr25/acc-data/logs/working-switches.log 2>/dev/null | awk '{print $1}' | tr '\n' ' ')"
  ACC_DRAIN="$(sed -n 's/^\[d\] *//p' /data/adb/vr25/acc-data/logs/working-switches.log 2>/dev/null | awk '{print $1}' | tr '\n' ' ')"
  ACC_IDLE1="$(sed -n 's/^\[i\] *//p' /data/adb/vr25/acc-data/logs/working-switches.log 2>/dev/null | sed -n '1p' | sed 's/ {.*//')"
  ACC_DRAIN1="$(sed -n 's/^\[d\] *//p' /data/adb/vr25/acc-data/logs/working-switches.log 2>/dev/null | sed -n '1p' | sed 's/ {.*//')"
  log "  ACC write.log tail:"
  tail -15 /data/adb/vr25/acc-data/logs/write.log 2>/dev/null | pclean2 | while read -r l; do log "    $l"; done
fi

log ""
log "############ VERDICT ############"
[ -n "$WARN" ] && [ "${DEBUG:-0}" = 1 ] && { log "WARNINGS:$WARN"; log ""; }
if [ "$ACTIVE" = 0 ]; then
  log "INCONCLUSIVE: active hold-tests skipped (see warnings). Re-run plugged in, battery 40-80%."
  log "Discovery + level-limit write/readback above still show what this phone exposes."
  [ "$CUR_FROZEN" = 1 ] && log "Note: current_now is frozen on this ROM; blind verification needs the charger actively charging (status=Charging) at 40-80%."
  if [ -n "$LEVELOK" ]; then
    log ""
    log "GOOD NEWS: a native firmware charge-limit node is present and accepts values (Pixel/Samsung style):"
    printf '%s\n' "$LEVELOK" | tr '|' '\n' | sed '/^$/d' | while read -r l; do log "     - $l"; done
    log "  -> ACC can almost certainly cap charging on this phone. Re-run plugged in (40-80%) to confirm enforcement."
  fi
elif [ -n "$LEVELOK" ] && printf '%s\n' "$LEVELOK" | tr '|' '\n' | sed '/^$/d' | grep -vqE '\(accepts\)|\(ro\)'; then
  log "YES (BEST): native firmware level-limit works, enforcement confirmed -- the most reliable cap (firmware can't overshoot); ACC drives it and the battery holds flat."
  printf '%s\n' "$LEVELOK" | tr '|' '\n' | sed '/^$/d' | while read -r l; do log "     - $l"; done
elif [ -n "${BYPASS_HELD:-}" ]; then
  log "YES (BEST bypass): verified-held TRUE BYPASS -- battery idle while the charger powers the phone, and it held through the long re-arm/leak test (no creep, no fake-idle). Gentlest on the battery with cut-grade reliability, so it is preferred over a hard cut here."
  printf '%s\n' "$BYPASS_HELD" | tr '|' '\n' | sed '/^$/d' | while read -r l; do log "     - $l"; done
elif [ -n "$CUT" ]; then
  if [ "$BLINDV" = 1 ]; then
    log "YES (blind-verified): charging STOPS when the switch engages -- confirmed by charging-state + charge_type + voltage, then resumes on re-enable. (current_now is frozen on this ROM, so it was not used.)"
  else
    log "YES (most reliable): ACC holds your limit by CUTTING charge -- a hard cut can never overcharge (verified sustained ~15s; resumes on replug)."
  fi
  printf '%s\n' "$CUT" | tr '|' '\n' | sed '/^$/d' | while read -r l; do log "     - $l"; done
elif [ -n "$BYPASS" ]; then
  log "YES: TRUE BYPASS -- battery idle, charger powers the phone (gentlest on the battery), sustained ~15s. NOT leak-verified this run, so ranked below a hard cut (charge-pump phones can fake idle while still feeding); prefer the cut, or re-run the long test to promote this bypass to verified-held."
  printf '%s\n' "$BYPASS" | tr '|' '\n' | sed '/^$/d' | while read -r l; do log "     - $l"; done
elif [ -n "$DRAIN" ]; then
  log "PARTIAL: only switches that DISCHARGE while plugged hold. Usable but battery drains slowly when capped."
  printf '%s\n' "$DRAIN" | tr '|' '\n' | sed '/^$/d' | while read -r l; do log "     - $l"; done
elif [ -n "$LEVELOK" ]; then
  log "MAYBE: native limit node ACCEPTS values but enforcement not observed at this SOC. Likely works at the real threshold (Pixel/Samsung)."
  printf '%s\n' "$LEVELOK" | tr '|' '\n' | sed '/^$/d' | while read -r l; do log "     - $l"; done
elif [ -n "$THROTTLE" ]; then
  log "MAYBE: only THROTTLE nodes responded (reduce, not stop). ACC may not hold a hard limit."
elif [ -n "$ACC_IDLE1$ACC_DRAIN1" ]; then
  log "Tester found no switch hold THIS run, BUT ACC's own history has a verified switch -- see RECOMMENDED below. Re-run plugged at 40-80% to confirm it fresh."
else
  log "NO usable switch -- charge control is firmware-locked (e.g. Xiaomi HyperOS). Needs a custom kernel."
fi
log ""
log "---- Can the phone run on the cable WITHOUT charging the battery? ----"
if [ -n "$BYPASS" ]; then
  log "  YES (lowest wear): TRUE BYPASS -- charger powers the phone directly, battery stays IDLE (no charge, no discharge). Gentlest on the battery, though a hard CUT is the more reliable cap (see RECOMMENDED)."
elif [ -n "$LEVELOK" ] && printf '%s\n' "$LEVELOK" | tr '|' '\n' | sed '/^$/d' | grep -vqE '\(accepts\)|\(ro\)'; then
  log "  YES (native limit): at/above the set limit the firmware holds the battery flat and the phone keeps running on the charger."
elif [ -n "$CUT" ]; then
  log "  YES (charge-cut): charging stops but the phone keeps running on the charger; battery stays ~flat (only tiny self-discharge)."
elif [ -n "$DRAIN" ]; then
  log "  PARTIAL: the phone runs while plugged, but the battery slowly DISCHARGES (input is cut -- not a true bypass). Works as a cap, more wear than bypass."
elif [ "$ACTIVE" = 0 ]; then
  log "  UNKNOWN: charge tests were skipped (plug in at 40-80% and re-run to find out)."
else
  log "  NO: could not confirm the phone runs on the cable without charging the battery."
fi
log "---------------------------------------------------------------------"
[ -n "$REASSERT" ] && { log ""; log "*** REJECTED (dropped current briefly then firmware RE-ENABLED -> would OVERCHARGE, NOT recommended):"; printf '%s\n' "$REASSERT" | tr '|' '\n' | sed '/^$/d' | while read -r l; do log "     x $l"; done; }
[ -n "$NEWHITS" ] && { log ""; log "*** NEW WORKING NODE(S) ACC's list missed -- ADD THESE:"; printf '%s\n' "$NEWHITS" | tr '|' '\n' | sed '/^$/d' | while read -r l; do log "   $l"; done; }
[ -n "$GENHITS" ] && { log ""; log "*** FIRMWARE-OBSERVED WORKING NODE(S) (found by WATCHING, no name-list) -- ADD THESE:"; printf '%s\n' "$GENHITS" | tr '|' '\n' | sed '/^$/d' | while read -r l; do log "   $l"; done; }
[ -n "$LEARNED" ] && { log ""; log "*** FIRMWARE-TAUGHT WORKING NODE(S) (induced a REAL cut, watched the firmware, each VERIFIED ALONE) -- ADD THESE:"; printf '%s\n' "$LEARNED" | tr '|' '\n' | sed '/^$/d' | while read -r l; do log "   $l"; done; }
[ -n "$BUILT" ] && { log ""; log "*** BUILT COMBO SWITCH(ES) (firmware-taught nodes that only hold TOGETHER, minimized + verified):"; printf '%s\n' "$BUILT" | tr '|' '\n' | sed '/^$/d' | while read -r l; do log "   $l"; done; }
[ -n "$OBSERVED_ONLY" ] && { log ""; log "*** UNKNOWN switch-like candidates (firmware moved them, or they look switch-like, but the NAME is not in our DB -> READ-only, NEVER written). PLEASE PASTE THESE BACK TO US so we can verify + add the real ones to the DB for your phone: ***"; printf '%s\n' "$OBSERVED_ONLY" | tr '|' '\n' | sed '/^$/d' | while read -r l; do log "   ? $l"; done; }
[ -n "$ADDLINES" ] && { log ""; log "==== READY-TO-ADD ctrl-files.sh lines (verified <path> <on> <off>) ===="; printf '%s\n' "$ADDLINES" | sed '/^$/d' | while read -r l; do log "  $l"; done; }
# Selection + artifact: rank working switches (native level > verified bypass > cut > ... ), pick the safest
# verified one, and emit the machine artifact ($ART) that AccA reads to offer Apply & Lock.
pick1(){ printf '%s' "$1" | sed 's/^|//' | cut -d'|' -f1; }
pick_best(){ pbf="$BK/pickbest"; printf '%s\n' "$1" | tr '|' '\n' | sed '/^$/d' > "$pbf" 2>/dev/null
  pb_first=; pb_pick=
  while IFS= read -r pb_c; do
    [ -n "$pb_c" ] || continue
    [ -z "$pb_first" ] && pb_first="$pb_c"
    case "|$STUCKS|" in *"|$pb_c|"*) continue;; esac
    pb_pick="$pb_c"; break
  done < "$pbf"
  printf '%s' "${pb_pick:-$pb_first}"; }
is_clean(){ case "|$STUCKS|" in *"|$1|"*) return 1;; esac
  case "$RESUMES" in *"$1=after-reset"*|*"$1=SLOW"*|*"$1=STUCK"*|*"$1=UNKNOWN"*) return 1;; esac
  return 0; }
pick_clean(){ pcf="$BK/pickclean"; printf '%s\n' "$1" | tr '|' '\n' | sed '/^$/d' > "$pcf" 2>/dev/null
  pc_pick=
  while IFS= read -r pc_c; do [ -n "$pc_c" ] || continue; is_clean "$pc_c" && { pc_pick="$pc_c"; break; }; done < "$pcf"
  printf '%s' "$pc_pick"; }
is_usable(){ case "|$STUCKS|" in *"|$1|"*) return 1;; esac
  case "$RESUMES" in *"$1=STUCK"*) return 1;; esac
  return 0; }
pick_usable(){ puf="$BK/pickusable"; printf '%s\n' "$1" | tr '|' '\n' | sed '/^$/d' > "$puf" 2>/dev/null
  pu_pick=
  _pu_ha(){ case "$(awk -F'\t' -v l="$1" '$1==l{v=$2} END{print v}' "$BK/stab" 2>/dev/null)" in daemon-held|leaky) return 1;; *) return 0;; esac; }
  _pu_pref(){ _pp=0
    case "$1" in *mmi_charging_enable*|*battery_charging_enabled*|*charging_enabled*|*charge_enabled*) _pp=2;; esac
    [ -n "${ACC_SW_NOW:-}" ] && case "$1" in *"$ACC_SW_NOW"*) _pp=$(( _pp + 3 ));; esac
    printf '%s' "$_pp"; }
  for _pu_pass in haok ha ok any; do
    _pu_best=-1
    while IFS= read -r pu_c; do [ -n "$pu_c" ] || continue
      is_usable "$pu_c" || continue
      _pu_ok=0
      case "$_pu_pass" in
        haok) _pu_ha "$pu_c" && case "$RESUMES" in *"|$pu_c=OK"*) _pu_ok=1;; esac;;
        ha)   _pu_ha "$pu_c" && _pu_ok=1;;
        ok)   case "$RESUMES" in *"|$pu_c=OK"*) _pu_ok=1;; esac;;
        any)  _pu_ok=1;;
      esac
      [ "$_pu_ok" = 1 ] || continue
      _pu_p="$(_pu_pref "$pu_c")"
      [ "$_pu_p" -gt "$_pu_best" ] 2>/dev/null && { pu_pick="$pu_c"; _pu_best="$_pu_p"; }
    done < "$puf"
    [ -n "$pu_pick" ] && break
  done
  printf '%s' "$pu_pick"; }
label_path(){ lpp="$1"
  case "$lpp" in
    "fcc-zero "*) lpp="${lpp#fcc-zero }";;
    "voltage-cap "*) lpp="${lpp#voltage-cap }";;
    "[ACC] "*) lpp="${lpp#\[ACC\] }";;
    "[NEW] "*) lpp="${lpp#\[NEW\] }";;
    "[NEW:safe] "*) lpp="${lpp#\[NEW:safe\] }";;
    "[GEN:"*|"[LEARN"*) lpp="${lpp#*] }";;
  esac
  printf '%s' "${lpp%%=*}"; }
cfg_lookup(){ clp="$(label_path "$1")"; [ -n "$clp" ] || return
  printf '%s\n' "$ADDLINES" | sed 's/^[ 	]*//' | grep -F "$clp " | sed -n '1p' | sed 's/ (.*$//'; }

acc_current(){
  _accsw="$(grep -m1 '^chargingSwitch=' /data/adb/vr25/acc-data/config.txt 2>/dev/null | sed -e 's/^chargingSwitch=(//' -e 's/).*$//' -e 's/  *--$//')"
  [ -n "$_accsw" ] || _accsw="$(sed -n '1p' /data/adb/vr25/acc-data/.last-good-switch 2>/dev/null)"
  [ -n "$_accsw" ] || return 0
  _acccls=cut
  case "$_accsw" in
    *charge_stop_level*|*charge_control_limit*|*batt_full_capacity*) _acccls=level;;
    *current_max*|*constant_charge_current*|*input_current*)         _acccls=current-cap;;
    *night_charging*|*store_mode*|*bms*charge_disable*)              _acccls=bypass;;
  esac
  printf 'acc_current_switch=%s\nacc_current_class=%s\n' "$_accsw" "$_acccls"; }

emit_alts(){
  _an=0; [ -s "$REG" ] || { printf 'alt_count=0\n'; return; }
  _TAB="$(printf '\t')"; _seen="|"
  while IFS="$_TAB" read -r _cls _ctrl _stab _lbl; do
    [ -n "$_ctrl" ] || continue
    [ -n "$_lbl" ] && [ "$_lbl" = "$RECO_LBL" ] && continue
    [ -n "$SUGGEST" ] && [ "$_ctrl" = "$SUGGEST" ] && continue
    case "$_seen" in *"|$_ctrl|"*) continue;; esac
    _seen="$_seen$_ctrl|"
    _an=$((_an+1))
    _res=ok; case "$RESUMES" in *"|$_lbl=after-reset"*) _res=after-reset;; *"|$_lbl=SLOW"*) _res=slow;; *"|$_lbl=STUCK"*) _res=stuck;; *"|$_lbl=UNKNOWN"*) _res=unknown;; esac
    _lat=no; case "|$STUCKS|" in *"|$_lbl|"*) _lat=yes;; esac; [ "$_stab" = leaky ] && _lat=yes
    case "$_cls" in
      native-accepts) _cf=accepts;;
      *) case "$_stab" in
           leaky|reassert) _cf=unstable;; inconclusive) _cf=needs-recheck;;
           *) _lo=0; case "|${LONGOK:-}|" in *"|$_lbl|"*) _lo=1;; esac; _cf="$(pump_conf verified "$_cls" "${DRVS:-}" "$_lo")";;
         esac;;
    esac
    printf 'alt%s_switch=%s\nalt%s_class=%s\nalt%s_conf=%s\nalt%s_stability=%s\nalt%s_resume=%s\nalt%s_latch=%s\nalt%s_note=%s\n' \
      "$_an" "$_ctrl" "$_an" "$_cls" "$_an" "$_cf" "$_an" "$_stab" "$_an" "$_res" "$_an" "$_lat" "$_an" "$(note_for "$_cls")"
  done < "$REG"
  printf 'alt_count=%s\n' "$_an"; }

le_enf="$(printf '%s\n' "$LEVELOK" | tr '|' '\n' | sed '/^$/d' | grep -vE '\(accepts\)|\(ro\)' | sed -n '1p')"
LVL_BY_ACC=0
if [ -z "$le_enf" ]; then
  _lvlacc="$(printf '%s\n' "$LEVELOK" | tr '|' '\n' | sed '/^$/d' | grep -v '(ro)' | sed -n '1p' | sed 's/(accepts)$//')"
  if [ -n "$_lvlacc" ]; then
    _accnow="$(grep -m1 '^chargingSwitch=' /data/adb/vr25/acc-data/config.txt 2>/dev/null | sed -e 's/^chargingSwitch=(//' -e 's/).*$//')"
    case "$_accnow" in
      *charge_stop_level*|*charge_control_limit*|*batt_full_capacity*|*pcap*) le_enf="$_lvlacc"; LVL_BY_ACC=1;;
    esac
  fi
fi
compute_reco(){
RECO=none; RECO_LATCH=0; RECO_LBL=; RECO_CLS=
rb="$(pick_usable "$BYPASS")"; rc="$(pick_usable "$CUT")"; rdr="$(pick_usable "$DRAIN")"; rt="$(pick_usable "$THROTTLE")"
rbh="$(pick_usable "${BYPASS_HELD:-}")"
_reco="$(reco_pick "$le_enf" "$rbh" "$rc" "$rb" "$rdr" "" "$rt")"
if [ -n "$_reco" ]; then
  RECO_LBL="$(_lblof "$_reco")"
  RECO_CLS="$(_clsof "$_reco")"
  case "$RECO_CLS" in
    native-level) [ "${LVL_BY_ACC:-0}" = 1 ] && RECO="$RECO_LBL (native level limit, confirmed in use by ACC)" || RECO="$RECO_LBL (native level limit, verified)";;
    cut)          RECO="$RECO_LBL (CUT)";;
    bypass)       RECO="$RECO_LBL (BYPASS)";;
    drain)        RECO="$RECO_LBL (CUT, discharges while plugged)";;
    throttle)     RECO="$RECO_LBL (throttle only)";;
  esac
fi
if [ -z "$RECO_LBL" ]; then
  if [ -n "$BYPASS$CUT$DRAIN" ]; then
    RECO_LATCH=1
    if [ -n "$CUT" ]; then RECO_LBL="$(pick1 "$CUT")"; RECO="$RECO_LBL (CUT, but LATCHES -- needs re-arm)"
    elif [ -n "$BYPASS" ]; then RECO_LBL="$(pick1 "$BYPASS")"; RECO="$RECO_LBL (BYPASS, but LATCHES -- needs re-arm)"
    else RECO_LBL="$(pick1 "$DRAIN")"; RECO="$RECO_LBL (CUT/drain, but LATCHES -- needs re-arm)"; fi
  else
    la="$(printf '%s\n' "$LEVELOK" | tr '|' '\n' | sed '/^$/d' | sed -n '1p')"
    [ -n "$la" ] && { RECO="$la (native level limit, accepts values -- re-run at the real threshold to confirm enforcement)"; RECO_LBL="$la"; }
  fi
fi
if [ "$RECO" = none ] && [ -n "$ACC_IDLE1$ACC_DRAIN1" ]; then
  if [ -n "$ACC_IDLE1" ]; then ACC_FALLBACK="$ACC_IDLE1"; RECO="${ACC_IDLE1%% *} (ACC-verified IDLE from ACC's own history -- tester found no hold this run)"; RECO_LBL="${ACC_IDLE1%% *}"
  else ACC_FALLBACK="$ACC_DRAIN1"; RECO="${ACC_DRAIN1%% *} (ACC-verified drain from ACC's own history -- tester found no hold this run)"; RECO_LBL="${ACC_DRAIN1%% *}"; fi
fi
ACC_DEFER=0; _real_found=; [ -n "${BYPASS}${CUT}${DRAIN}${le_enf}" ] && _real_found=1
_dacc="$(defer_to_acc "$_real_found" "${ACC_SW_NOW:-}" "${STUCKS:-}" "${REASSERT:-}")"
if [ -n "$_dacc" ]; then
  ACC_DEFER=1; RECO_LBL="$_dacc"
  RECO="$_dacc (ACC-confirmed BYPASS -- ACC already runs this switch on this phone; the tester could not independently confirm a hard switch in this fast mode. Run Highest-accuracy/--unplug to verify with a current anchor.)"
fi

SUGGEST=
if [ "${ACC_DEFER:-0}" = 1 ] && [ -n "${ACC_SW_NOW_FULL:-}" ]; then SUGGEST="$ACC_SW_NOW_FULL"
elif [ -n "$RECO_LBL" ]; then SUGGEST="$(cfg_lookup "$RECO_LBL")"; fi
if [ -z "$SUGGEST" ]; then
  if [ -n "$le_enf" ] && [ -n "$CFG_LEVEL" ]; then SUGGEST="$CFG_LEVEL"
  elif [ -n "${BYPASS_HELD:-}" ] && [ -n "$CFG_BYPASS" ]; then SUGGEST="$CFG_BYPASS"
  elif [ -n "$CUT" ] && [ -n "$CFG_CUT" ]; then SUGGEST="$CFG_CUT"
  elif [ -n "$BYPASS" ] && [ -n "$CFG_BYPASS" ]; then SUGGEST="$CFG_BYPASS"
  elif [ -n "$DRAIN" ] && [ -n "$CFG_DRAIN" ]; then SUGGEST="$CFG_DRAIN"
  elif [ -n "$ACC_FALLBACK" ]; then SUGGEST="$ACC_FALLBACK"
  fi
fi
}
compute_reco
_fsr=0
while [ "$_fsr" -lt 2 ] && [ -n "$RECO_LBL" ] && [ "$RECO_LATCH" = 0 ]; do
  case "$RECO_CLS" in native-level|cut|bypass|drain) ;; *) break;; esac
  _fs_sug="${SUGGEST%%" ("*}"
  if finalist_stress "$RECO_LBL" "$_fs_sug"; then break; fi
  _fsr=$(( _fsr + 1 ))
  compute_reco
done

log ""
log "############ DECODED: WHAT IS GOING ON IN THIS PHONE ############"
np=0; for o in $(online_f); do case "$(read1 "$o")" in 1) np=$((np+1));; esac; done
log "  charge architecture: $np energized charger path(s); drivers:${DRVS:- unknown}"
vmax="$(rd "$BATT/uevent" | sed -n 's/^POWER_SUPPLY_VOLTAGE_MAX=//p' | sed -n '1p' | tr -dc '0-9')"
[ -n "$vmax" ] && log "  native charge ceiling: VOLTAGE_MAX=${vmax} uV (firmware float/termination voltage -- the natural 100% point)"
log "  current reporting: $UNIT, charging reads $([ "$CHGDIR" = p ] && echo POSITIVE || echo NEGATIVE) (confidence $SIGN_CONF)$([ "$CUR_FROZEN" = 1 ] && echo "  -- SENSOR FROZEN at $RAW; proof done BLIND via status/charge_type/voltage")"
[ -n "$VOLTF" ] && log "  voltage: $(vmv) mV now (baseline V0=${V0}mV, noise +/-${VNOISE}mV, blind cut-threshold ${VDROP}mV)"
log "  verification method: $([ "$BLINDV" = 1 ] && echo "BLIND (charging-state + charge_type + voltage)" || echo "current delta (sensor live)")"
log "  battery now: $(rd $BATT/capacity | sed -n '1p' | pclean)% (started ${CAP}%)  $(( $(batt_temp) / 10 )).$(( $(batt_temp) % 10 ))C  status=$(read_st)"
log "  control classes found on this phone:"
log "    native %-limit : $([ -n "$CFG_LEVEL" ] && echo "YES ($CFG_LEVEL)" || { [ -n "$LEVELOK" ] && echo "accepts values (enforcement unproven this run)" || echo no; })"
log "    bypass (idle)  : $([ -n "$BYPASS" ] && echo "YES -- true battery idle, lowest wear" || echo "not proven")"
log "    charge-cut     : $([ -n "$CUT" ] && echo "YES ($(pick1 "$CUT"))" || echo no)"
log "    input-cut/drain: $([ -n "$DRAIN" ] && echo "YES ($(pick1 "$DRAIN")) -- discharges while held" || echo no)"
log "    throttle-only  : $([ -n "$THROTTLE" ] && echo "yes (reduce, not stop)" || echo no)"
rj="$(printf '%s' "$REASSERT" | sed 's/^|//' | tr '|' ',')"
sj="$(printf '%s' "$STUCKS" | sed 's/^|//' | tr '|' ',')"
log "    fake (flicker) : $([ -n "$REASSERT" ] && echo "REJECTED: $rj" || echo "none seen")"
log "    firmware-taught: $({ [ -n "$LEARNED" ] || [ -n "$BUILT" ] || [ -n "$GENHITS" ]; } && echo "YES -- ${TBUILT} verified-alone from a learned set of ${NLEARN} node(s) (induced+watched); +$OBS_UNPLUG unplug/$OBS_ENGAGE engage diffs" || echo "none this run")"
log "  resume behavior  : $([ -n "$STUCKS" ] && echo "LATCHING switch(es): $sj -- need re-arm strategy" || echo "every working switch resumed cleanly")"

if [ -n "$SUGGEST" ]; then
  log ""
  log "==== SUGGESTED ACC SETUP (tested on THIS phone -- copy-paste) ===="
  log "  acc -s charging_switch=\"$SUGGEST\""
  log "  acc -s pause_capacity=75 resume_capacity=70"
  log "  acc -s cooldown_temp=45 max_temp=50 resume_temp=40"
  [ -n "$BYPASS" ] && log "  acc -s prioritize_batt_idle_mode=true   # this phone supports bypass (battery idle)"
  case "$SUGGEST" in *soc:google,charger*) log "  note: on this Pixel family ACC 6.5.1+ drives the stop/start pair itself and verifies the hold against the fuel gauge at boot -- expect 'Draining to N%' while it lowers to the limit";; esac
  sugbase="${SUGGEST%% *}"; sugbase="${sugbase##*/}"
  log "  (this pins ACC to the one verified switch -- it will not auto-shift to a broken one)"
  latchw=0; [ "$RECO_LATCH" = 1 ] && latchw=1
  [ -n "$sugbase" ] && case "$STUCKS" in *"$sugbase"*) latchw=1;; esac
  [ "$latchw" = 1 ] && log "  WARNING: this switch LATCHES (no self re-arm) -- add 'acc -s loop_delay=10' and reboot once if charging stays stuck."
fi

log ""
log "==== ACC CROSS-CHECK (tester findings vs ACC's own history) ===="
if [ -n "$ACCV" ]; then
  rbn="${RECO_LBL##*/}"; rbn="${rbn%% *}"; rbn="${rbn%%=*}"
  if [ -n "$rbn" ] && case " $ACC_IDLE $ACC_DRAIN " in *"$rbn"*) true;; *) false;; esac; then
    log "  DOUBLE-CONFIRMED: ACC's own working-switches log already lists '$rbn' as working -> highest confidence."
  elif [ -n "$ACC_IDLE$ACC_DRAIN" ]; then
    log "  ACC previously found working: idle=[${ACC_IDLE:-none}] drain=[${ACC_DRAIN:-none}]"
    log "  Tester recommends: ${RECO}. If these differ, trust the tester's freshly-verified switch and PIN it."
  else
    log "  (ACC has no working-switches history yet -- the tester result is the primary source.)"
  fi
  if [ -n "$ACC_SW_NOW" ]; then
    asn="${ACC_SW_NOW##*/}"
    if case "|$STUCKS|$REASSERT|" in *"$asn"*) true;; *) false;; esac; then
      log "  ! ACC is CURRENTLY set to '$ACC_SW_NOW', which the tester found LATCHES/flickers."
      log "    -> likely cause of 'charging stops and never resumes' AND the switch 'auto-shifting' to non-working."
      log "    -> FIX: pin the tester's recommended switch (the acc -s charging_switch line above)."
    else
      log "  ACC is currently using: $ACC_SW_NOW"
    fi
  fi
else
  log "  (ACC not installed -- install it and re-run to cross-validate against ACC's own detection history.)"
fi

log ""
log "==== DEEP-TEST LAYER RECAP (each layer ran + verified) ===="
log "  L0-3 setup/baseline      : OK ($([ "$BLINDV" = 1 ] && echo "BLIND verify: status/charge_type/voltage" || echo "current-delta verify"))"
log "  L4/4b known + groups     : tested (hits routed above)"
log "  L5  native level-limits  : $([ -n "$LEVELOK" ] && echo "present / accepts values" || echo "none")"
log "  S2  unplug fingerprint   : ${OBS_UNPLUG:-0} node(s) moved"
log "  L6  discovery            : ${n_disc:-0} charge-control node(s)"
log "  L6b new-line auto-test   : ${tested_new:-0} trusted switch(es) tested; ${obs_n:-0} unrecognized flagged read-only"
log "  write policy             : trusted reversible switches + native %-limits ONLY; unknown/vendor/learned nodes observed, never written"
log "  L6c firmware-observed    : ${tested_gen:-0} candidate(s) tested"
log "  L6d combo engine         : ${cn:-0} grouped candidate(s)"
log "  L6e firmware-teaching    : teacher=${TEACH_P:-none}; learned $NLEARN, tested $TEACHED, VERIFIED $TBUILT new switch(es)$([ -n "$BUILT" ] && echo " + built 1 combo")"
log ""
log "=====SUMMARY====="
log "AMPS v$V (Adaptive Multi-device Probe & Selector)"
log "DEVICE=$(getprop ro.product.manufacturer 2>/dev/null)_$(getprop ro.product.device 2>/dev/null)"
log "SOC=$(getprop ro.board.platform 2>/dev/null)"
log "ANDROID=$(getprop ro.build.version.release 2>/dev/null)"
log "ACC=${ACCV:-no}"
log "SENSOR=${CURF:-none}"
log "UNITS=$UNIT SIGN=$CHGDIR CONF=$SIGN_CONF BASE=$RAW"
log "SYSFS_STATUS=$ST ANDROID_STATUS=${AST:-na}"
log "BATT_TEMP=$(batt_temp) (0.1C units)  CAPACITY=${CAP}% (end $(rd $BATT/capacity | sed -n '1p' | pclean)%)"
log "IDLE_MODE=$([ -n "$BYPASS" ] && echo yes || echo no)   (bypass / battery-idle charging support)"
log "ACTIVE=$ACTIVE SKIPALL=$SKIPALL"
log "CUR_USABLE=$CUR_USABLE CUR_FROZEN=$CUR_FROZEN BLIND=$BLINDV PROOF=$PROOF"
log "VOLT_mV=$(vmv) V0=$V0 VNOISE=$VNOISE VDROP=$VDROP VRISE=$VRISE"
log "DRIVERS=${DRVS# }"
log "OBS_UNPLUG=$OBS_UNPLUG OBS_ENGAGE=$OBS_ENGAGE"
log "BYPASS=${BYPASS#\|}"
log "CUT=${CUT#\|}"
log "DRAIN=${DRAIN#\|}"
log "THROTTLE=${THROTTLE#\|}"
log "BYPASS_HELD=${BYPASS_HELD#\|}"
log "REASSERT=${REASSERT#\|}"
log "LEVELOK=${LEVELOK#\|}"
log "NEWHITS=${NEWHITS#\|}"
log "GENHITS=${GENHITS#\|}"
log "SUPERHITS=${SUPERHITS#\|}"
log "LEARNED=${LEARNED#\|}"
log "BUILT=${BUILT#\|}"
log "TEACH=teacher=${TEACH_P:-none} learned=$NLEARN tested=$TEACHED verified=$TBUILT"
log "OBSERVED_ONLY=${obs_n:-0} (switch-like/firmware-moved nodes READ but NOT written)"
log "WARN_DIAG=$(printf '%s' "$WARN" | tr '\n' ' ' | sed 's/^ *//;s/  */ /g')"
log "WRITE_POLICY=trusted-switches+native-limits-only"
log "RESUME=${RESUMES#\|}"
log "STUCK=${STUCKS#\|}"
log "SUGGEST_SWITCH=${SUGGEST:-none}"
log "FIRST_RESPONDING=${WORKING:-none}"
log "RECOMMENDED=$RECO"
log "PATH_SENSITIVE=$(case "$RECO" in *"native level"*) echo no;; *CUT*|*BYPASS*|*DRAIN*|*drain*|*discharges*) echo yes;; *) echo no;; esac)"
log "=====END====="
log ""
log "recommended switch: $RECO"
if [ "${MODE:-quick}" = complete ]; then
  log ""
  log "================ ALL WORKING SWITCHES (best first) ================"
  [ -n "${RECO_LBL:-}" ] && log "  * RECOMMENDED   $RECO"
  printf '%s\n' "$BYPASS|$LEVELOK|$CUT|$DRAIN" | tr '|' '\n' | sed '/^$/d' | awk '!s[$0]++' | while read -r _sl; do
    [ -n "$_sl" ] || continue
    case "${RECO_LBL:-}" in *"$_sl"*) continue;; esac
    case "$_sl" in *"${RECO_LBL:-zzz_none}"*) continue;; esac
    _st="$(awk -F'\t' -v l="$_sl" '$1==l{v=$2} END{print v}' "$BK/stab" 2>/dev/null)"
    case "$_st" in
      daemon-held) log "  ~ daemon-held   $_sl   (re-arms alone, but the daemon holds it flat -- usable)";;
      *)           log "  + also works    $_sl";;
    esac
  done
  if [ -n "$LEAKY$REASSERT$THROTTLE" ]; then
    printf '%s\n' "$LEAKY|$REASSERT|$THROTTLE" | tr '|' '\n' | sed '/^$/d' | awk '!s[$0]++' | while read -r _sl; do
      [ -n "$_sl" ] && log "  ! risky         $_sl"
    done
    log "    (! = re-arms EVEN when re-applied each poll, or only throttles -- can overcharge; pick only if you accept it)"
  fi
  log "  Best bypass first, then cuts.  ~ daemon-held = works but the daemon must keep re-applying it."
  log "==================================================================="
fi
log ""
ART=/data/local/tmp/acc-compat-verified
acls=unknown
case "$RECO" in
  *"native level"*) acls=level;;
  *"discharges while plugged"*|*"CUT/drain"*) acls=drain;;
  *BYPASS*) acls=bypass;;
  *CUT*) acls=cut;;
  *throttle*) acls=throttle;;
esac
_pnote="$(path_note "$acls")"; [ -n "$_pnote" ] && { log ""; log "  NOTE (charge path): $_pnote"; }
case "$RECO" in
  none) aconf=none;; *LATCHES*) aconf=latch-needs-rearm;; *"accepts values"*) aconf=unconfirmed;; *history*|*ACC-confirmed*) aconf=from-ACC-history;; *) aconf=verified;;
esac
LONG_PICK=0
[ -n "${RECO_LBL:-}" ] && case "|${LONGOK:-}|" in *"|$RECO_LBL|"*) LONG_PICK=1;; esac
aconf="$(pump_conf "$aconf" "$acls" "${DRVS:-}" "$LONG_PICK")"
[ "${BLINDV:-0}" = 1 ] && case "$acls" in bypass|cut|drain) [ "$aconf" = verified ] && aconf=needs-test;; esac
_advc="$(getprop ro.product.device 2>/dev/null)"; [ -n "$_advc" ] || _advc="$(getprop ro.build.product 2>/dev/null)"; [ -n "$_advc" ] || _advc="$(getprop ro.product.name 2>/dev/null)"
_advs="$(getprop ro.board.platform 2>/dev/null)"; [ -n "$_advs" ] || _advs="$(getprop ro.hardware 2>/dev/null)"
RESUME_OK=na
[ -n "${RECO_LBL:-}" ] && case "$RESUMES" in
  *"|$RECO_LBL=OK"*)          RESUME_OK=ok;;
  *"|$RECO_LBL=after-reset"*) RESUME_OK=after-reset;;
  *"|$RECO_LBL=SLOW"*)        RESUME_OK=slow;;
  *"|$RECO_LBL=STUCK"*)       RESUME_OK=stuck;;
esac
case "$RESUME_OK" in after-reset|slow|stuck) [ "$aconf" = verified ] && aconf=needs-test;; esac
REARM_DONE=no; printf '%s' "${SUGGEST:-}" | grep -Eq "$REARM_RE" && REARM_DONE=yes
ACCCUR="$(acc_current)"; ALTS="$(emit_alts)"
_recstab="$(awk -F'\t' -v l="${RECO_LBL:-}" '$1==l{v=$2} END{print v}' "$BK/stab" 2>/dev/null)"; [ -n "$_recstab" ] || _recstab=holds-alone
_reclat=no; case "$RECO" in *LATCHES*) _reclat=yes;; esac; case "|${STUCKS:-}|" in *"|${RECO_LBL:-_NONE_}|"*) _reclat=yes;; esac
case "$(artifact_kind "$aconf" "${SUGGEST:-}")" in
  switch)
    { printf 'schema=1\ncharging_switch=%s\nclass=%s\nconf=%s\nrec_stability=%s\nrec_latch=%s\npolarity=%s\nunits=%s\nresume=%s\nweak_charger=%s\nrearm_checked=%s\ndevice=%s\nsoc=%s\nscript=acc-compat\ntester_version=%s\nts=%s\n' "$SUGGEST" "$acls" "$aconf" "$_recstab" "$_reclat" "${POLARITY:-normal}" "${UNIT:-mA}" "$RESUME_OK" "${WEAK_CHARGER:-0}" "$REARM_DONE" "$_advc" "$_advs" "$V" "${TS:-}"
      [ -n "$ACCCUR" ] && printf '%s\n' "$ACCCUR"
      [ -n "$ALTS" ] && printf '%s\n' "$ALTS"
      printf 'ok=1\n'; } > "${ART}.tmp" 2>/dev/null ;;
  *)
    _nsr=no-pinnable; [ "$aconf" = none ] && _nsr=none
    { printf 'schema=1\nresult=no-switch\nreason=%s\npolarity=%s\nweak_charger=%s\ndevice=%s\nsoc=%s\nscript=acc-compat\ntester_version=%s\n' "$_nsr" "${POLARITY:-normal}" "${WEAK_CHARGER:-0}" "$_advc" "$_advs" "$V"
      [ -n "$ACCCUR" ] && printf '%s\n' "$ACCCUR"
      printf 'ok=1\n'; } > "${ART}.tmp" 2>/dev/null ;;
esac
if [ -s "${ART}.tmp" ] && [ "$(tail -n1 "${ART}.tmp" 2>/dev/null)" = ok=1 ]; then
  mv -f "${ART}.tmp" "$ART" 2>/dev/null && chmod 0644 "$ART" 2>/dev/null
else
  rm -f "${ART}.tmp" 2>/dev/null
fi
_csr(){ cat "$1" 2>/dev/null | sed -n '1p'; }
_csn(){ _v=$(_csr "$1"); _v=${_v#-}; case "$_v" in ''|*[!0-9]*) echo 0;; *) [ "$_v" -gt 100000 ] && echo $((_v/1000)) || echo "$_v";; esac; }
_ct="$(_csr $PSY/battery/charge_type)"; _stt="$(_csr $PSY/battery/status)"
_imax=0; _iin=0; _vbus=0; _src=none
for _u in usb main dc wireless pc_port; do
  [ -e $PSY/$_u/online ] || continue
  [ "$(_csr $PSY/$_u/online)" = 1 ] || continue
  _src=$_u; _imax=$(_csn $PSY/$_u/current_max); _vbus=$(_csn $PSY/$_u/voltage_now)
  _iin=$(_csn $PSY/$_u/input_current_now); [ "$_iin" = 0 ] && _iin=$(_csn $PSY/$_u/current_now)
  break
done
_ccc=$(_csn $PSY/battery/constant_charge_current_max); [ "$_ccc" = 0 ] && _ccc=$(_csn $PSY/main/constant_charge_current_max)
_ib=$(_csn $PSY/battery/current_now); _vb=$(_csn $PSY/battery/voltage_now)
log "+----------------------------------------------------"
log "|  CHARGER / SPEED"
log "|  source=$_src  charge_type=${_ct:-?}  status=${_stt:-?}"
log "|  input:   Imax=${_imax}mA  Iin=${_iin}mA  Vbus=${_vbus}mV"
_cccd="${_ccc}mA"; [ "$_ccc" -gt 0 ] 2>/dev/null || _cccd="n/a"
log "|  battery: I=${_ib}mA  V=${_vb}mV  (~$((_ib*_vb/1000))mW)   IC cap (CCC)=${_cccd}"
if [ "$_src" = none ]; then
  log "|  -> not plugged / no input supply reports online"
elif [ "$_imax" -gt 0 ] 2>/dev/null && [ "$_imax" -le 510 ] 2>/dev/null; then
  log "|  -> INPUT-CAPPED ~${_imax}mA = USB SDP (PC port / data tether / weak cable). NOT a phone/ACC limit;"
  log "|     the IC can pull ${_ccc}mA. Use a wall charger + good cable. If it stays low there, the kernel"
  log "|     is not negotiating fast-charge (APSD) -- the real 'custom ROM charges slower' cause."
elif [ "$_ccc" -gt 0 ] 2>/dev/null && [ "$_imax" -gt 0 ] 2>/dev/null && [ "$_ccc" -lt "$_imax" ] 2>/dev/null; then
  log "|  -> IC/THERMAL-CAPPED: CCC ${_ccc}mA < input ${_imax}mA. The charge IC or thermal mitigation is the ceiling, not the charger."
else
  log "|  -> input ~${_imax}mA / battery ~${_ib}mA -- source+IC are delivering; compare against the stock-ROM number for the fast-charge target."
fi
log "+===================================================="
log "|  VERDICT  (AMPS v$V)"
log "|  Device:       $(getprop ro.product.model 2>/dev/null) [$_advc / $_advs]"
log "|  Best switch:  ${SUGGEST:-none}"
log "|  Type: $acls    Confidence: $aconf"
case "$aconf" in
  verified) log "|  -> In AccA: tap 'Apply & Lock' -- pins this proven switch directly (no re-test, no 'connect charger')";;
  pump-needs-long-test) log "|  -> Charge-pump device: held 15s but may leak under load -- AccA live-tests; watch for slow drain at the cap";;
  latch-needs-rearm) log "|  -> This switch LATCHES -- AccA will not auto-pin; needs reboot/re-arm";;
  none) log "|  -> No safe switch found this run -- AccA makes no change";;
  *) log "|  -> Unconfirmed -- re-run plugged at 40-80% to verify before pinning";;
esac
if [ "${WANT_UNPLUG:-0}" != 1 ]; then case "$aconf" in
  verified) :;;
  *) log "|"
     log "|  TIP: didn't nail it? For the deepest scan, run Deep with 'Highest accuracy' --"
     log "|       it will ASK YOU TO UNPLUG the charger, which reveals hidden vendor switches"
     log "|       the firmware only writes when power leaves (works even with no current sensor).";;
esac; fi
log "+===================================================="
log ""
log "=================================================="
log "  YOUR REPORT IS SAVED HERE (open your Files app):"
log "    $(friendly "$OUTDIR") > $OUTBASE"
log "    full path: $(canon "$OUT")"
log "    (each run makes a NEW dated file -- nothing is overwritten)"
[ "$OUT" = "$OUT2" ] || log "    backup copy: $OUT2"
log "    don't see it? open Files > Internal storage > Download and pull down to refresh."
log ""
log "  EASIEST OF ALL: just COPY ALL THIS TEXT from the"
log "  terminal and paste it back to us -- no file needed."
log "=================================================="
log "(restoring now -- wait for 'restored' below, then you're done)"
exit 0
