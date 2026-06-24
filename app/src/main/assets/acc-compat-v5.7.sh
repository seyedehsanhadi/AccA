#!/system/bin/sh
# =====================================================================
#  acc-compat v5.7  --  ACC charging-switch compatibility tester
#  RUN (rooted phone):
#    Termux:  termux-setup-storage    # once, to reach /sdcard/Download
#             su -c 'sh /sdcard/Download/acc-compat-v5.7.sh'
#    ADB:     adb shell 'su -c "sh /sdcard/Download/acc-compat-v5.7.sh"'
#    Tip:     plain  sh acc-compat-v5.7.sh  auto-elevates to root for you.
#  Report -> your Download folder (and printed below so you can copy it).
#  No-root self-check (runs anywhere):  sh acc-compat-v5.7.sh --selftest
# =====================================================================

V=5.7
# v5.7: --selftest/--version are PURE (no device I/O). Flag them here so the report/backup
# file setup below is skipped, letting the regression gate run anywhere (dev box, no root).
case "${1:-}" in --selftest|--version) _STONLY=1;; esac

# v5.7: auto-elevate so a user can just `sh acc-compat-v5.7.sh` from Termux or any shell. Selftest
# and --version stay unprivileged (skipped here). Re-exec ONCE under su/tsu/sudo; if none, show how-to.
_uid="$(id -u 2>/dev/null)"
if [ "${_STONLY:-}" != 1 ] && [ -n "$_uid" ] && [ "$_uid" != 0 ] && [ -z "${ACC_REEXEC:-}" ]; then
  for _su in su tsu sudo; do command -v "$_su" >/dev/null 2>&1 && { echo "[*] acc-compat: elevating to root via $_su ..."; exec "$_su" -c "ACC_REEXEC=1 sh '$0' $*"; }; done
  echo "!! acc-compat needs root. Grant root in your root manager, then run:  su -c 'sh $0'"; exit 1
fi

PSY="${PSY:-/sys/class/power_supply}"
# v5.7: pick a WRITABLE work dir so the tester runs as root OR in Termux/any shell. Honors a TMPD=
# override; for --selftest (no device I/O) just default it; otherwise probe root-tmp first, then $HOME.
if [ -n "${TMPD:-}" ]; then :
elif [ "${_STONLY:-}" = 1 ]; then TMPD=/data/local/tmp
else
  for _t in /data/local/tmp "${TMPDIR:-}" "$HOME/.acc-compat" "$HOME" /tmp; do
    [ -n "$_t" ] || continue; mkdir -p "$_t" 2>/dev/null
    [ -d "$_t" ] && ( : > "$_t/.acc_wt" ) 2>/dev/null && { rm -f "$_t/.acc_wt" 2>/dev/null; TMPD="$_t"; break; }
  done
  [ -n "${TMPD:-}" ] || TMPD=/data/local/tmp
fi
BK="${BK:-$TMPD/acc_compat_bk}"
SNAP="$BK/snap.tsv"; DISC="$BK/disc.txt"; SNLIST="$BK/snlist"
SCHG="$BK/s_chg.tsv"; SUNP="$BK/s_unplug.tsv"; SHELD="$BK/s_held.tsv"; GENC="$BK/gen.tsv"
POLL=3; SETTLE=4; TO=5; MAXSEC=1020; MAX_NEW=20; MAX_CURR=10; MAX_GEN=12
DID=0; RESTORED=0; ACTIVE=1; SKIPALL=0; WARN=
BYPASS=; CUT=; DRAIN=; THROTTLE=; LEVELOK=; REASSERT=; WORKING=; NEWHITS=; GENHITS=; ADDLINES=
RESUMES=; STUCKS=; CFG_BYPASS=; CFG_CUT=; CFG_DRAIN=; CFG_LEVEL=
ENGDUMPED=0; OBS_UNPLUG=0; OBS_ENGAGE=0; EXTRA_DIRS=""
CUR_USABLE=1; CUR_FROZEN=0; BLINDV=0; V0=0; VNOISE=0; VDROP=25; VRISE=0; CTYPE0=; PROOF=current
BL_WHY=; BL_ONLINE=1; BL_CT=; BL_VLAST=0
TEACH_P=; TEACH_ON=; TEACH_OFF=; LEARNED=; BUILT=; MAX_TEACH=12; TEACHED=0; NLEARN=0; TBUILT=0
OBSERVED_ONLY=; obs_n=0; GATE_FAILS=0; ACC_SW_NOW=; ACC_IDLE=; ACC_DRAIN=; ACC_IDLE1=; ACC_DRAIN1=; ACC_FALLBACK=
TRUST_RE='charging_enabled|battery_charging_enabled|charge_enabled|charging_enable|charge_enable|enable_charging|enable_charger|input_suspend|battery_input_suspend|op_disable_charge|disable_charging|charge_disable|disable_charger|batt_slate_mode|slate_mode|mmi_charging_enable|smart_charging_interruption|night_charging|bypass_charger|charger_bypass|charging_suspend_en|charger_limit_en|charger_control|force_charger_suspend|force_usb_suspend|charge_pause|step_charging_enabled|charge_control_limit'
SHELD2="$BK/s_held2.tsv"; TEACHC="$BK/teach.tsv"
EFFECT_RE='^(status|charge_type|charging_speed|current_now|current_avg|current_max|input_current_now|voltage_now|voltage_avg|voltage_ocv|capacity|capacity_raw|temp|batt_temp|online|present|health|charge_counter|charge_full|charge_full_design|charge_now|time_to_full_now|time_to_empty_now|cycle_count|power_now|energy_now|resistance|soc|msoc|rsoc)$'
# v5.5: nodes whose "off" can pass the ~15s hold then be re-armed by charger firmware later
# (the current-cap class). These get the two-phase long re-verify in test_switch.
REARM_RE='current_max|constant_charge_current|input_current'
[ "${_STONLY:-}" = 1 ] || mkdir -p "$BK" 2>/dev/null

[ -n "${SRCDIR:-}" ] || case "$0" in */*) SRCDIR="${0%/*}";; esac
# v5.7: Download FIRST (as requested), and cover root + Termux layouts everywhere -- the FUSE /sdcard
# view, the real /data/media/0 behind it, Termux's $HOME/storage, and $EXTERNAL_STORAGE.
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
: > "$BK/combo.tsv" 2>/dev/null; : > "$BK/combo_seen" 2>/dev/null
: > "$SHELD2" 2>/dev/null; : > "$TEACHC" 2>/dev/null; : > "$BK/teach_combo.tsv" 2>/dev/null
rm -f /data/local/tmp/acc-compat-verified 2>/dev/null
fi

log(){ printf '%s\n' "$*"; printf '%s\n' "$*" >> "$OUT" 2>/dev/null; [ "$OUT" = "$OUT2" ] || printf '%s\n' "$*" >> "$OUT2" 2>/dev/null; }
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
rd(){ if [ "$HAVE_TO" = 1 ]; then timeout "$TO" cat "$1" 2>/dev/null; else cat "$1" 2>/dev/null; fi; }
ex(){ [ -e "$1" ]; }
wr(){ ex "$1" || return 1; chmod u+w "$1" 2>/dev/null
  if [ "$HAVE_TO" = 1 ]; then printf '%s\n' "$2" | timeout "$TO" tee "$1" >/dev/null 2>&1
  else { printf '%s\n' "$2" > "$1"; } 2>/dev/null; fi; }
read1(){ rd "$1" 2>/dev/null | sed -n '1p' | cut -d' ' -f1; }
pclean(){ LC_ALL=C tr -dc ' -~'; }
pclean2(){ LC_ALL=C tr -dc ' -~\n'; }
read_st(){ rd "$BATT/status" | sed -n '1p' | pclean; }
san(){ case "$1" in ''|-) echo 0;; -*) v="${1#-}"; case "$v" in ''|*[!0-9]*) echo 0;; *) echo "-$v";; esac;; *) case "$1" in *[!0-9]*) echo 0;; *) echo "$1";; esac;; esac; }
abs(){ v="${1#-}"; case "$v" in ''|*[!0-9]*) echo 0;; *) echo "$v";; esac; }
sgn(){ case "$1" in -*) echo n;; *) echo p;; esac; }

# ===================== v5.5 pure polarity + state core (self-testable) =====================
# New in v5.5: the sign/polarity decision and the universal state classifier are PURE
# functions (no device I/O), so `--selftest` proves them against a synthetic matrix before
# the script ever touches a node. Earlier versions derived the sign inline and silently
# assumed positive=charging when the OS status read Unknown -- the "discharging but +200mA"
# trap on inverted-polarity kernels, which then poisoned every hold-verify. When ACC is
# installed, v5.5 also takes the daemon's already-calibrated polarity from `acca --state`
# instead of guessing.

# Normalise a signed current to "charge-positive" for the device polarity.
#   normal   -> charging current is positive already (pass through)
#   inverted -> charging reads negative; flip so charge becomes positive
_norm(){ case "$2" in
    inverted) case "$1" in -*) printf '%s\n' "${1#-}";; 0) echo 0;; *) printf '%s\n' "-$1";; esac;;
    *) printf '%s\n' "$1";; esac; }

# Which current SIGN means "charging", from one (status, sign) pair.
# Echoes "<dir> <conf>": dir=p|n, conf=high|med|low. Below-threshold current cannot tell
# direction -> low. Unknown status -> best-effort med (flagged, never silently "high").
learn_chgdir(){ case "$3" in 1) ;; *) echo "p low"; return;; esac
  case "$1" in
    Charging|charging) echo "$2 high";;
    Discharging|discharging|"Not charging"|"not charging"|Idle|idle|Full|full)
      [ "$2" = p ] && echo "n high" || echo "p high";;
    *) echo "$2 med";; esac; }

# Universal state primitive (present-first, polarity-correct).
#   $1=present(0/1) $2=online(0/1) $3=signed-current $4=polarity $5=idle-threshold
# -> CHARGING|BYPASS|CUT|DRAIN|DISCHARGING|STANDBY|MISLABEL
classify_state(){ _csc="$(_norm "$3" "$4")"; _csp=0; { [ "$1" = 1 ] || [ "$2" = 1 ]; } && _csp=1; _csm="${_csc#-}"
  if [ "$_csp" = 0 ]; then
    case "$_csc" in -*) echo DISCHARGING; return;; esac
    [ "${_csm:-0}" -gt "$5" ] 2>/dev/null && echo MISLABEL || echo STANDBY; return; fi
  case "$_csc" in
    # v5.5.1: a discharging current while plugged is only a genuine DRAIN when the charger is still
    # ONLINE (input present, yet the battery is losing) -- that is the concerning "can't outpace the
    # load / weak hold" case. When online=0 the charger INPUT is physically cut (input_suspend et al.),
    # so the discharge is just the screen/CPU LOAD running off battery, NOT a charge leak -> it is a
    # clean CUT, not a DRAIN. Previously any discharge>idle was demoted to DRAIN, mislabeling a working
    # input-cut switch under a screen-on test load and burying it below a marginal bypass.
    -*) if [ "$2" = 1 ] && [ "${_csm:-0}" -gt "$5" ] 2>/dev/null; then echo DRAIN; else echo CUT; fi;;
    *)  if [ "${_csm:-0}" -gt "$5" ] 2>/dev/null; then echo CHARGING
        elif [ "$2" = 1 ]; then echo BYPASS; else echo CUT; fi;; esac; }

# reco_pick: the SINGLE source of truth for the reliability-first recommendation order. Given the best
# already-picked label of each class (empty = that class has no clean hit), echo "<label>|<class>" of
# the MOST RELIABLE present one. The order is forced by field OVERCHARGE evidence, not wear: a VERIFIED
# native firmware limit (cannot overshoot) and a hard CUT (can only stop) have never overcharged a phone
# in the field; an unverified BYPASS is the #1 real-world overcharge cause (charge-pump fakes "idle"
# while feeding) so it ranks BELOW cut; a native limit that only ACCEPTS the value (enforcement unproven)
# ranks near the bottom; throttle never holds. RECO, the verdict text and the artifact class ALL consume
# this -- change the order in ONE place here. $1=native-verified $2=cut $3=bypass $4=drain $5=native-accepts $6=throttle
reco_pick(){
  for _rp in "native-level:$1" "cut:$2" "bypass:$3" "drain:$4" "native-accepts:$5" "throttle:$6"; do
    _rpl="${_rp#*:}"
    [ -n "$_rpl" ] && { printf '%s|%s\n' "$_rpl" "${_rp%%:*}"; return 0; }
  done; }

# Split reco_pick's "label|class" output. CRITICAL mksh trap: a bare `|` in a ${var%pat}/${var#pat}
# glob is ALTERNATION in mksh (not a literal), so `${x%|*}` strips NOTHING. Use cut (delimiter-based).
_lblof(){ printf '%s' "$1" | cut -d'|' -f1; }
_clsof(){ printf '%s' "$1" | cut -d'|' -f2; }

# note_for: one-line plain-language trade-off per class, shown to the user next to each alternative
# (the "(CUT)"/"(BYPASS)" parenthetical the user asked for, expanded into why). Pure -> selftest-able.
note_for(){ case "$1" in
  native-level)   echo "firmware limit -- reliable and no battery cycling when it truly enforces";;
  cut)            echo "hard cut -- most reliable, can never overcharge; battery cycles a little";;
  bypass)         echo "gentlest (battery idle), but verify it holds -- charge-pump phones can fake idle";;
  drain)          echo "stops charge but the battery slowly drains while plugged";;
  native-accepts) echo "firmware limit accepts the value but enforcement is unconfirmed -- re-test at your real cap";;
  throttle)       echo "only SLOWS charging -- may not hold a hard cap";;
  *)              echo "";;
esac; }

# path_note: v5.7 path-dependence advisory. A switch's class can change with the charger -- field
# evidence (Motorola Edge 50 Pro): force_usb_suspend HELD on 5W wireless but was DEAD on 60W USB-PD,
# and a node faked idle (overcharge) under PD. So an input-cut/bypass verdict is proven only for the
# charger tested; a VERIFIED firmware %-limit is charger-independent. Pure -> selftest-able. $1=class.
path_note(){ case "$1" in
  level|native-level|native-accepts) echo "";;
  cut|drain|bypass) echo "verified for the charger used in THIS test -- some phones (esp. fast USB-PD) change behaviour by charge path; if charging looks uncapped on another charger, re-run, and prefer a firmware %-limit if your phone has one";;
  *) echo "";;
esac; }

# native_verdict: native-level is the #1 recommendation, so "enforced" must be PROVEN, not assumed.
# A firmware limit is verified-enforcing ONLY if a real current measurement showed charging collapsed
# (samp_last=0, not all samples charging) AND it did not re-arm over the longer overshoot window AND the
# sensor was not blind (status/voltage alone cannot prove a level limit holds -- the fake-native trap).
# Anything else -> accepts (re-test). $1=samp_last $2=samp_n $3=blind(0/1) $4=rearmed(0/1)
native_verdict(){
  [ "$3" = 1 ] && { echo accepts; return; }
  { [ "$1" = 0 ] && [ "${2:-3}" -lt 3 ] 2>/dev/null && [ "$4" = 0 ]; } && { echo verified; return; }
  echo accepts; }

# --selftest: prove the two pure functions above against a synthetic matrix. No device I/O,
# so it runs anywhere and is the regression gate for every future polarity/classify change.
selftest(){ _sp=0; _sf=0
  _ck(){ if [ "$2" = "$3" ]; then _sp=$((_sp+1)); else echo "  FAIL $1: got='$2' want='$3'"; _sf=$((_sf+1)); fi; }
  echo "== acc-compat v$V self-test (pure polarity + state) =="
  _ck chg+pos    "$(learn_chgdir Charging p 1)"        "p high"
  _ck chg+neg    "$(learn_chgdir Charging n 1)"        "n high"
  _ck dis+pos    "$(learn_chgdir Discharging p 1)"     "n high"
  _ck dis+neg    "$(learn_chgdir Discharging n 1)"     "p high"
  _ck notchg+pos "$(learn_chgdir 'Not charging' p 1)"  "n high"
  _ck idle+neg   "$(learn_chgdir Idle n 1)"            "p high"
  _ck full+pos   "$(learn_chgdir Full p 1)"            "n high"
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
  _ck reco_native    "$(reco_pick nat cx bx dx ax tx)"      "nat|native-level"
  _ck reco_cut       "$(reco_pick '' cx bx dx '' '')"       "cx|cut"
  _ck reco_cutbeatsbp "$(reco_pick '' cx bx '' '' '')"      "cx|cut"
  _ck reco_bypass    "$(reco_pick '' '' bx dx '' '')"       "bx|bypass"
  _ck reco_drain     "$(reco_pick '' '' '' dx '' '')"       "dx|drain"
  _ck reco_naccept   "$(reco_pick '' '' '' '' ax '')"       "ax|native-accepts"
  _ck reco_throttle  "$(reco_pick '' '' '' '' '' tx)"       "tx|throttle"
  _ck reco_none      "$(reco_pick '' '' '' '' '' '')"       ""
  _ck note_cut       "$(note_for cut)"     "hard cut -- most reliable, can never overcharge; battery cycles a little"
  _ck note_bypass_y  "$([ -n "$(note_for bypass)" ] && echo y)"  y
  _ck note_unknown   "$(note_for zzz)"     ""
  _ck split_lbl  "$(_lblof 'charge_control_limit=6|bypass')"  "charge_control_limit=6"
  _ck split_cls  "$(_clsof 'charge_control_limit=6|bypass')"  bypass
  _ck nv_verified "$(native_verdict 0 0 0 0)"  verified
  _ck nv_blind    "$(native_verdict 0 0 1 0)"  accepts
  _ck nv_rearm    "$(native_verdict 0 0 0 1)"  accepts
  _ck nv_charging "$(native_verdict 1 3 0 0)"  accepts
  # v5.7 field scenarios (Motorola Edge 50 Pro): qpnp ENFORCED under PD (collapsed, 1 sample) = verified;
  # still-charging in the window = accepts. Plus the path-dependence note fires only for input-cut classes.
  _ck field_enforced "$(native_verdict 0 1 0 0)" verified
  _ck field_accepts  "$(native_verdict 1 1 0 0)" accepts
  _ck pnote_level    "$(path_note level)"  ""
  _ck pnote_cut_y    "$([ -n "$(path_note cut)" ] && echo y)"    y
  _ck pnote_drain_y  "$([ -n "$(path_note drain)" ] && echo y)"  y
  echo "== self-test: $_sp passed, $_sf failed =="
  [ "$_sf" = 0 ]; }

# Early arg dispatch (v5.5): these exit before any device access.
case "${1:-}" in
  --selftest) selftest; exit $?;;
  --version)  echo "acc-compat v$V"; exit 0;;
  --probe)    PROBE=1;;
esac
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
DENY_RE='uevent|/type$|/present$|/status$|/health$|_raw|counter|_now$|_avg$|charge_full|cycle|voltage|/temp|time_to|/power/|subsystem|charge_log|safety_timer|factory|test_mode|/fg_|_fg/|batt_range|update_now|/capacity$|hiz|power_path|store_mode|regulator|/otg|vbus|wireless_boost|cool_mode|cool_down|system_temp|temp_cool|step_charg|restricted|brightness|/led|of_node|/driver/|/module/|/wakeup|/sections/|/notes/|modalias|driver_override|nafg|daemon_disable|/device/modalias|/device/power_supply/'
DIFF_DENY='charge_stats|charge_details|charger_state|charge_stage|/soc$|/msoc$|capacity_level|charge_counter|time_|/online$|input_current_settled|_uv$|_ua$|monotonic|charge_type|charging_speed|charge_deadline|ttf|_dump$|registers|fan_level|dock_'
DDIRS="$PSY /sys/class/qcom-battery /sys/class/oplus_chg /sys/class/oplus_chg/battery /sys/class/hw_power /sys/class/battchg_ext /sys/class/asuslib /sys/class/cms_class /sys/class/nubia_charge /sys/kernel/nubia_charge /proc/mtk_battery_cmd /sys/devices/platform/charger /sys/devices/platform/mt-battery /sys/devices/platform/google,charger /sys/devices/platform/soc/soc:google,charger /sys/devices/platform/lge-unified-nodes /sys/devices/platform/huawei_charger /sys/module/qpnp_adaptive_charge/parameters /sys/module/lge_battery/parameters"
DDIRS_ALL="$DDIRS"

state_dump(){
  : > "$1"
  { for d in $DDIRS_ALL; do ex "$d" || continue
      { if [ "$HAVE_TO" = 1 ]; then timeout "$TO" find -L "$d" -maxdepth 4 -type f 2>/dev/null; else find -L "$d" -maxdepth 4 -type f 2>/dev/null; fi; } | awk '!seen[$0]++' | sed -n '1,300p' | while read -r f; do
        printf '%s' "$f" | grep -Eq "$DENY_RE" && continue
        printf '%s' "$f" | grep -Eq "$DIFF_DENY" && continue
        v="$(rd "$f" | sed -n '1p' | tr '|\t' '__' | pclean | cut -c1-40)"
        printf '%s|%s\n' "$f" "$v"
      done
    done; } | sort -u > "$1"
}
diff_pairs(){ awk -F'|' 'NR==FNR{a[$1]=$2;next} ($1 in a)&&a[$1]!=$2{print $1"|"a[$1]"|"$2}' "$1" "$2" 2>/dev/null; }
DANGER_RE='ship|power_off|poweroff|reboot|shut_down|factory|calib|fw_|firmware|flash|erase|wipe|moisture|water_det|usb_sel|cc_toggle|typec|port_mode|role_|_role|otg|boost_en|vbus|fastchg_fw|reverse|tx_mode|wireless_tx|wls_|usbpd|pd_active|pdo|vconn|cc_orient|jeita|vfloat|float_volt|_fv$|iterm|aging|atest|mtbf|fuelgauge|cw201|max172|nvmem|nvm|sram|otp|profile|model_data|regulator|power_path|hiz'

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
safe_to_write(){ st_bn="${1##*/}"
  printf '%s' "$1" | grep -Eiq "$DANGER_RE" && return 1
  printf '%s' "$st_bn" | grep -Eq "$EFFECT_RE" && return 1
  printf '%s' "$st_bn" | grep -Eq "$TRUST_RE" && return 0
  return 1; }
flag_observe(){ case "|$OBSERVED_ONLY|" in *"|$1 "*) :;; *) OBSERVED_ONLY="$OBSERVED_ONLY|$1 $2"; obs_n=$((obs_n+1));; esac; }

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

restore(){
  [ "$RESTORED" = 1 ] && return; RESTORED=1
  trap '' INT TERM HUP
  ( sleep 130; defaults_native 2>/dev/null; sleep 5; kill -9 $$ 2>/dev/null ) & RWDOG=$!
  if [ "$DID" = 1 ]; then
    log ""; log "===== RESTORING (replaying snapshot to original values) ====="
    defaults_native
    fail=0
    if [ -s "$SNAP" ]; then
      rpass=0
      while [ $rpass -lt 3 ]; do
        rpass=$((rpass+1)); fail=0
      while IFS="	" read -r p v; do
        [ -n "$p" ] || continue
        ex "$p" || continue
        wr "$p" "$v"
        case "$p" in
          */input_current_limited|*/constant_charge_current|*/bcc_parms|*/battery_rm|*/resistance|*/ssoc_details|*/charger_temp|*/die_health) continue;;
        esac
        rb="$(rd "$p" | sed -n '1p')"
        [ "$rb" = "$v" ] || fail=$((fail+1))
      done < "$SNAP"
      [ "$fail" -eq 0 ] && break
      sleep 1
      done
    fi
    defaults_native; sleep 1
    if plugged 2>/dev/null; then
      fst="$(rd "$BATT/status" | sed -n '1p' | pclean)"
      case "$fst" in Charging|Full) :;; *) log "  ! charging did NOT resume after restore (status=$fst) -- a switch may be latched. REBOOT now to clear it.";; esac
    fi
    [ "${ACC_WAS:-0}" = 1 ] && for c in /data/adb/vr25/acc/acca /dev/.vr25/acc/acca acca acc; do command -v "$c" >/dev/null 2>&1 && { "$c" -D restart >/dev/null 2>&1 || "$c" --daemon restart >/dev/null 2>&1 || "$c" -D start >/dev/null 2>&1; break; }; done
    [ "$fail" -gt 0 ] && log "  ! $fail node(s) did not read back to original -- charging re-asserted; REBOOT if it still looks stuck."
    log "===== restored${ACC_WAS:+ + ACC restarted} (REBOOT if charging looks stuck) ====="
  fi
  chmod 0644 "$OUT" 2>/dev/null
  if command -v am >/dev/null 2>&1; then
    am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$OUT" >/dev/null 2>&1
    am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$OUTDIR" >/dev/null 2>&1
  fi
  [ -n "${WATCHDOG-}" ] && kill "$WATCHDOG" 2>/dev/null
  [ -n "${RWDOG-}" ] && kill "$RWDOG" 2>/dev/null
}
trap restore EXIT
trap 'restore; trap - EXIT; exit 130' INT TERM HUP
MYPID=$$
( i=0; lim=$(( (MAXSEC + 120) / 5 )); while [ $i -lt $lim ]; do sleep 5; kill -0 "$MYPID" 2>/dev/null || exit 0; i=$((i+1)); done; kill -TERM "$MYPID" 2>/dev/null ) &
WATCHDOG=$!

log "############ ACC COMPATIBILITY v$V (smart-observe + blind-verify + firmware-teach + verdict card + charger-speed + path-aware) ############"
log "collected in report: phone model, soc, android+kernel build, charge-node names/values, charger driver names. no accounts/imei/serial/location."
log "SAFE MODE: WRITES only well-known, reversible charge switches (ACC's standard enable/suspend set) + native %-limits. Unknown/vendor/learned nodes are READ + reported, NEVER written. No bypass/regulator/OTG/PD/fuel-gauge writes. Battery protection is never disabled. Every write is snapshotted and restored."
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
  [ "$t" -le 100 ] 2>/dev/null && t=$(( t * 10 )); echo "$t"; }
TMAX=450
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
  case "$d" in usb|*usb*|ac|dc|mains|main-charger|mainchg|pc_port|wireless|smb*|*ucsi*|*chg*|*charger*|*glink*) printf '%s\n' "$o";; esac; done; }
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
      usb|*usb*|ac|dc|mains|main-charger|mainchg|pc_port|wireless|smb*|*ucsi*|*chg*|*charger*|*glink*) : ;;
      *) continue ;;
    esac
    seen=1
    pv="$(rd "$i" | sed -n '1p')"
    [ "$pv" = 1 ] && return 0
  done
  if [ "$seen" = 1 ]; then
    online_now && return 0
    return 1
  fi
  online_now
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
log "==== LAYER 2 - stop ACC + reset to NATIVE charging (honest baseline) ===="
ACC_WAS=0; ACCV=; ST_POL=; ST_UNIT=; ST_TRUST=; POLARITY=normal; POL_SRC=
for c in /data/adb/vr25/acc/acca /dev/.vr25/acc/acca acca acc; do
  command -v "$c" >/dev/null 2>&1 || continue
  ACCV="$("$c" --version 2>/dev/null | sed -n '1p')"; [ -n "$ACCV" ] || continue
  ACC_WAS=1
  # v5.5: capture the daemon's already-_DPOL-calibrated polarity/units/trust BEFORE stopping it.
  _accst="$("$c" --state 2>/dev/null)"
  ST_POL="$(printf '%s' "$_accst"  | grep -oE '"polarity":"[a-z]+"'        | sed 's/.*://; s/"//g')"
  ST_UNIT="$(printf '%s' "$_accst" | grep -oE '"currentUnits":"[a-zA-Z]+"' | sed 's/.*://; s/"//g')"
  ST_TRUST="$(printf '%s' "$_accst"| grep -oE '"statusTrust":"[a-z]+"'     | sed 's/.*://; s/"//g')"
  "$c" -D stop >/dev/null 2>&1 || "$c" --daemon stop >/dev/null 2>&1
  break
done
log "ACC installed: ${ACCV:-no}"
PRE_SUSP="$(rd $BATT/input_suspend | sed -n '1p' | pclean)"
log "pre-test input_suspend: ${PRE_SUSP:-na}"
DID=1
defaults_native
sleep $SETTLE

log ""
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
AST=""
if command -v dumpsys >/dev/null 2>&1; then
  astn="$(dumpsys battery 2>/dev/null | sed -n 's/^ *status: *//p' | sed -n '1p' | tr -cd '0-9')"
  case "$astn" in 2) AST=Charging;; 3) AST=Discharging;; 4) AST="Not charging";; 5) AST=Full;; esac
fi
EFFST="$ST"
case "$EFFST" in ''|Unknown|unknown) EFFST="$AST";; esac
# v5.5: charge-direction via the pure learner -- flags Unknown status instead of silently
# assuming positive=charging (the inverted-kernel "+200mA while discharging" trap). When ACC
# is installed, prefer its _DPOL-calibrated polarity unless a live high-confidence Charging
# sample (we just reset to native charging) contradicts it -- the active measurement wins.
[ -n "$ST_UNIT" ] && case "$ST_UNIT" in uA*|microamp*) UNIT=uA; THR=50000; IDLE=10000;; mA*|milliamp*) UNIT=mA; THR=50; IDLE=10;; esac
CS="$(sgn "$RAW")"; _gt=0; [ "$ABSB" -gt "$THR" ] 2>/dev/null && _gt=1
_lc="$(learn_chgdir "$EFFST" "$CS" "$_gt")"; CHGDIR="${_lc%% *}"; SIGN_CONF="${_lc##* }"; POL_SRC=live
if [ -n "$ST_POL" ]; then
  case "$ST_POL" in inverted) _sp=n;; *) _sp=p;; esac
  if [ "$_gt" = 1 ] && [ "$SIGN_CONF" = high ] && [ "$_sp" != "$CHGDIR" ]; then
    warn "polarity: acca --state says $ST_POL but a live high-confidence sample reads chg-sign=$CHGDIR -- trusting the live measurement (daemon _DPOL may be mis-latched)"
  elif [ "$ST_TRUST" = trusted ] || [ "$SIGN_CONF" != high ]; then
    CHGDIR="$_sp"; SIGN_CONF=high; POL_SRC="acca-state:$ST_POL"
  fi
fi
POLARITY=normal; [ "$CHGDIR" = n ] && POLARITY=inverted
log "  charge-direction: chg-sign=$CHGDIR polarity=$POLARITY conf=$SIGN_CONF src=$POL_SRC unit=$UNIT thr=$THR"
# v5.5 weak-charger guard: in the native baseline (no switch) a healthy source charges. If we
# are plugged but the battery is idle/draining here (and not near full), the charger can't
# outpace the load -- every switch will look like it "stops" charging, so verdicts are
# unreliable. Flag it loudly; it is recorded in the artifact as weak_charger.
WEAK_CHARGER=0; _bp=0; present_now && _bp=1; _bo=0; online_now && _bo=1
_bcur="$(med_cur)"; _bcap="$(san "$(rd $BATT/capacity | sed -n '1p')")"
BASE_STATE="$(classify_state "$_bp" "$_bo" "$_bcur" "$POLARITY" "$IDLE")"
if [ "$_bp" = 1 ] && [ "${_bcap:-0}" -lt 95 ] 2>/dev/null; then
  case "$BASE_STATE" in
    CHARGING) ;;
    DRAIN|DISCHARGING) WEAK_CHARGER=1; warn "WEAK CHARGER: plugged at ${_bcap}% but the native baseline is $BASE_STATE (current=$_bcur $UNIT). The source can't outpace the load -- use a stronger wall charger or the switch verdicts below may be wrong.";;
    BYPASS|STANDBY) WEAK_CHARGER=1; warn "native baseline is $BASE_STATE at ${_bcap}% (battery not gaining charge) -- charger may be weak or the battery near full; switch verdicts may be unreliable.";;
  esac
fi
log "  baseline (native, no switch): $BASE_STATE at ${_bcap}% weak_charger=$WEAK_CHARGER"
if [ "${PROBE:-}" = 1 ]; then
  log ""; log "==== PROBE (v5.5 polarity/state, no switch test) ===="
  log "  present=$_bp online=$_bo current=$_bcur unit=$UNIT idle_thr=$IDLE"
  log "  polarity=$POLARITY chg-sign=$CHGDIR conf=$SIGN_CONF src=$POL_SRC"
  log "  classify_state -> $BASE_STATE   weak_charger=$WEAK_CHARGER"
  [ -n "$ST_POL" ] && log "  acca --state: polarity=$ST_POL units=$ST_UNIT trust=$ST_TRUST"
  defaults_native
  [ "${ACC_WAS:-0}" = 1 ] && for c in /data/adb/vr25/acc/acca /dev/.vr25/acc/acca acca acc; do command -v "$c" >/dev/null 2>&1 && { "$c" -D restart >/dev/null 2>&1 || "$c" -D start >/dev/null 2>&1; break; }; done
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
log "native: plugged=$P1 current=$RAW |abs|=$ABSB unit=$UNIT charge-sign=$CHGDIR(conf=$SIGN_CONF) status=$ST$([ "$CUR_FROZEN" = 1 ] && echo "  [FROZEN: identical across 6 reads]")"
[ -n "$VOLTF" ] && log "voltage: V0=${V0}mV baseline (noise +/-${VNOISE}mV, blind cut-threshold ${VDROP}mV, rising=$VRISE); charge_type=${CTYPE0:-na}"
if [ "$P1" = no ]; then warn "still NOT plugged after reset -> active hold-tests SKIPPED."; ACTIVE=0; fi
[ "$CAP" -ge 95 ] && { warn "near-full -> active hold-tests SKIPPED."; ACTIVE=0; }
if [ "$ACTIVE" = 1 ] && [ "$CUR_USABLE" = 0 ]; then
  if proof_available; then
    BLINDV=1; PROOF="status+charge_type+voltage"
    if [ "$CUR_FROZEN" = 1 ]; then warn "current sensor FROZEN (pinned at $RAW $UNIT, zero variance) -> switching to BLIND verification: charging-state + charge_type + voltage. This ROM's current_now is a stub; the active tests still run."
    elif [ -z "$CURF" ]; then warn "no current sensor -> BLIND verification (charging-state + charge_type + voltage)."
    else warn "charge current below measurable threshold ($RAW $UNIT) -> BLIND verification (charging-state + charge_type + voltage). For a current-level proof use a stronger charger at 40-80%."
    fi
    log "  BLIND mode: a switch 'holds' if it flips status off Charging (or charge_type to N/A, or sags voltage >=${VDROP}mV) AND reverts when re-enabled."
  else
    warn "current unmeasurable AND no charging-state/voltage signal (charger looks already idle) -> active hold-tests SKIPPED. Plug a stronger charger at 40-80% and retry."
    ACTIVE=0
  fi
fi
[ "$ACTIVE" = 1 ] && [ "$CUR_USABLE" = 1 ] && [ "$SIGN_CONF" != high ] && warn "charge sign/unit confidence not high -> using status+charge_type+voltage as corroboration."

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
      [ "$ht" = 0 ] && log "  ! battery $(( $(batt_temp) / 10 ))C (>=45C) -- pausing live tests until it cools..."
      sleep 10; ht=$((ht+10))
      [ "$ht" -ge 60 ] && { warn "battery stayed >=45C -- live test skipped for safety"; return 1; }
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
      [ "$(bcharge)" = 1 ] && { V0="$(vmv)"; [ "$V0" -gt 0 ] 2>/dev/null || V0=1; return 0; }
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
  sleep "$POLL"; C1="$(med_cur)"; SAMP_FIRST="$(is_charging "$C1")"; g1="$SAMP_FIRST"
  if [ "$g1" = 1 ] && [ "$(abs "$C1")" -ge "$NEAR" ] 2>/dev/null; then
    SAMP_N=3; SAMP_LAST=1; CL="$C1"; return
  fi
  sleep "$POLL"; c2="$(med_cur)"; g2="$(is_charging "$c2")"
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
  fi; }
st_notchg(){ case "$1" in Charging|charging) return 1;; '') return 1;; *) return 0;; esac; }
chgin_low(){ [ -n "$CHGIN" ] || return 1
  v="$(abs "$(san "$(read1 "$CHGIN")")")"; [ "$v" -le "$IDLE" ] 2>/dev/null; }

classify_held(){
  if [ "$BLINDV" = 1 ]; then
    [ "$BL_ONLINE" = 0 ] && { echo CUT-input; return; }
    case "$(read_st)" in Discharging|discharging) echo DRAIN; return;; esac
    echo CUT; return
  fi
  # v5.5.1: input physically cut -- */online dropped to 0 while still plugged -- is the strongest CUT
  # (CUT-input), so recognize it BEFORE the discharge->DRAIN heuristic. Under a screen-on test load the
  # battery then discharges, but with the input cut that drain is the LOAD, not a charge leak; demoting
  # it to DRAIN mislabeled a working input-cut switch (e.g. input_suspend) and buried it below a marginal
  # bypass. A current-cap or bypass switch keeps online=1, so this never misfires on those classes.
  online_now || { echo CUT-input; return; }
  if [ "$(sgn "$CL")" != "$CHGDIR" ] && [ "$(abs "$CL")" -gt "$IDLE" ] 2>/dev/null; then echo DRAIN; return; fi
  if [ "$(is_idle "$CL")" = 1 ]; then
    onl=1; online_now || onl=0
    cin=0; [ -n "$CHGIN" ] && { v="$(abs "$(san "$(read1 "$CHGIN")")")"; [ "$v" -gt "$IDLE" ] 2>/dev/null && cin=1; }
    if [ "$onl" = 0 ]; then echo CUT-input
    elif [ "$cin" = 1 ]; then echo BYPASS
    else echo CUT; fi
    return
  fi
  echo CUT; }

route_hit(){
  rh_cfg="$(printf '%s' "$3" | sed 's/ (.*$//')"
  case "$1" in
    BYPASS) BYPASS="$BYPASS|$2"; [ -n "$CFG_BYPASS" ] || CFG_BYPASS="$rh_cfg";;
    DRAIN) DRAIN="$DRAIN|$2"; [ -n "$CFG_DRAIN" ] || CFG_DRAIN="$rh_cfg";;
    *) CUT="$CUT|$2"; [ -n "$CFG_CUT" ] || CFG_CUT="$rh_cfg";;
  esac
  WORKING="${WORKING:-$2 ($1)}"
  ADDLINES="$ADDLINES
    $3"; }

resume_check(){
  ri=0; rok=0; rc=
  while [ "$ri" -lt 3 ]; do
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
    else
      rw=12; rok=0; rbrk=0
      while [ "$rw" -lt 90 ]; do
        over && { rbrk=1; break; }
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
  cur="$(rd "$p" | sed -n '1p')"
  case "$cur" in ''|0|1|"0 0"|"0 1"|enabled|disabled|on|off|true|false|[0-9]|[0-9][0-9]|[0-9][0-9][0-9]|[0-9][0-9][0-9][0-9]|[0-9][0-9][0-9][0-9][0-9]|[0-9][0-9][0-9][0-9][0-9][0-9]|[0-9][0-9][0-9][0-9][0-9][0-9][0-9]|[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]) :;; *) return;; esac
  [ -w "$p" ] || { log "  [read-only] $lbl"; return; }
  gate || return
  snap_add "$p"
  printf '%s|%s\n' "$p" "$offv" >> "$BK/tested"
  wr "$p" "$offv" || { log "  [write-fail] $lbl"; return; }
  hold_probe
  held=0; [ "$SAMP_LAST" = 0 ] && [ "$SAMP_N" -lt 3 ] && held=1
  # v5.5 two-phase verify: a re-arm-prone current-cap node that just "held" gets a longer
  # second pass while still off. If charging creeps back, the firmware re-armed it -> demote
  # (never recommend a switch that would silently overcharge after the daemon locks it).
  REARMED=0
  if [ "$held" = 1 ] && printf '%s' "$p" | grep -Eq "$REARM_RE"; then
    rwi=0
    while [ "$rwi" -lt 5 ]; do
      over && break
      sleep 6; rwi=$((rwi+1))
      [ "$(chg_now)" = 1 ] && { REARMED=1; break; }
    done
    if [ "$REARMED" = 1 ]; then
      held=0; REASSERT="$REASSERT|$lbl"
      log "  $lbl -> off=$offv RE-ARMED after ~$(( rwi*6 ))s [no-hold: firmware re-armed late - WOULD OVERCHARGE, demoted]"
    else
      log "  $lbl -> off=$offv long-verify OK (re-arm-prone class held +$(( rwi*6 ))s)"
    fi
  fi
  if [ "$held" = 1 ]; then
    k="$(classify_held)"
    if [ "$BLINDV" = 1 ]; then det="blind[$BL_WHY]"; else det="first=$C1 last=$CL"; fi
    log "  $lbl -> off=$offv $det HELD-15s [$k]"
    case "$k" in
      BYPASS) route_hit BYPASS "$lbl" "$p $onv $offv (BYPASS: battery idle, charger powers phone)";;
      CUT-input) route_hit CUT "$lbl" "$p $onv $offv (CUT-input: drives online->0; resume via *suspend*/*bypass* allowlist or replug)";;
      CUT) route_hit CUT "$lbl" "$p $onv $offv (CUT: charging stops, online stays up)";;
      DRAIN) route_hit DRAIN "$lbl" "$p $onv $offv (CUT but DISCHARGES while plugged)";;
    esac
    [ -z "$TEACH_P" ] && case "$k" in CUT|BYPASS) TEACH_P="$p"; TEACH_ON="${cur:-$onv}"; TEACH_OFF="$offv";; esac
  else
    if [ "$SAMP_FIRST" = 0 ] && [ "$SAMP_LAST" = 1 ]; then
      REASSERT="$REASSERT|$lbl"; log "  $lbl -> off=$offv first=$C1 last=$CL DROPPED-THEN-RESUMED [no-hold: WOULD OVERCHARGE - do NOT use]"
    elif st_notchg "$ST3" && st_notchg "$ST4" && chgin_low; then
      log "  $lbl -> off=$offv first=$C1 last=$CL status=$ST3/$ST4 input~0 HELD-status [CUT]"
      route_hit CUT "$lbl" "$p $onv $offv (CUT, status-verified: current sensor is unsigned on this kernel)"
      [ -z "$TEACH_P" ] && { TEACH_P="$p"; TEACH_ON="${cur:-$onv}"; TEACH_OFF="$offv"; }
    elif [ "$BLINDV" = 0 ] && [ "$(abs "$C1")" -lt "$HALF" ] 2>/dev/null && [ "$(abs "$C1")" -gt "$IDLE" ] 2>/dev/null; then
      THROTTLE="$THROTTLE|$lbl"; log "  $lbl -> off=$offv first=$C1 [THROTTLE: reduced not stopped]"
    else
      log "  $lbl -> off=$offv first=$C1 [no effect]"
    fi
  fi
  wr "$p" "${cur:-$onv}"; rbk="$(rd "$p" | sed -n '1p')"; [ "$rbk" = "${cur:-$onv}" ] || { wr "$p" "${cur:-$onv}"; log "  [restore-retry] $lbl"; }
  if [ "$held" = 1 ]; then resume_check "$lbl"; else sleep 1; fi; }

test_level(){
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
  # v5.7: prove the native limit DECISIVELY. A cap only 2% below SOC let some firmwares ride the
  # hysteresis and not cut within the observe window (Motorola qpnp read "accepts" -> lost the #1
  # slot to a path-dependent drain switch). 5% below SOC forces a clear cut -> enforcement becomes
  # observable -> native_verdict=verified where the firmware limit is the real, charger-independent answer.
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
    # v5.7: a real firmware cut can lag a few seconds past the write. If still charging after the first
    # probe, wait once more before concluding -- a slow-but-real cut must not be misread as unenforced.
    [ "$SAMP_LAST" = 1 ] && { sleep "$SETTLE"; hold_probe; }
    # v5.6: harden native-level (it is the #1 recommendation, so it must be PROVEN). Require a real
    # current collapse (non-blind), then re-check over a longer window to catch a firmware that holds
    # briefly then re-arms past the cap (the "accepts then overshoots" trap, e.g. Pixel multi-path).
    _lvl_blind=0; [ "$BLINDV" = 1 ] && _lvl_blind=1
    _lvl_rearm=0; _li=0
    if [ "$SAMP_LAST" = 0 ] && [ "$SAMP_N" -lt 3 ] && [ "$_lvl_blind" = 0 ]; then
      while [ "$_li" -lt 4 ]; do sleep 6; hold_probe; [ "$SAMP_LAST" = 1 ] && { _lvl_rearm=1; break; }; _li=$((_li+1)); done
    fi
    if [ "$(native_verdict "$SAMP_LAST" "$SAMP_N" "$_lvl_blind" "$_lvl_rearm")" = verified ]; then
      log "    engage stop=${pstop}% at SOC ${CAP}% -> last=$CL ENFORCED + held +$(( _li*6 ))s [native limit VERIFIED]"
      LEVELOK="$LEVELOK|$lbl"; WORKING="${WORKING:-$stop (LEVEL)}"; lvl_enf=1
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

emit_known(){
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
  log "  >>> OPTIONAL SMART STEP: UNPLUG the charger NOW for ~10 seconds, then PLUG IT BACK <<<"
  log "  (this reveals which hidden nodes the firmware itself flips -- skipping automatically in 25s)"
  ui=0; UNP=no
  while [ "$ui" -lt 25 ]; do
    plugged || { UNP=yes; break; }
    sleep 1; ui=$((ui+1))
  done
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
    log "  >>> PLUG THE CHARGER BACK IN NOW <<<"
    ri=0
    while [ "$ri" -lt 60 ]; do
      plugged && break
      sleep 2; ri=$((ri+2))
      [ "$(( ri % 10 ))" = 0 ] && log "  ...waiting for the charger (${ri}s)..."
    done
    plugged && { log "  re-plugged -- thank you!"; sleep "$SETTLE"; } || warn "charger not re-plugged after 60s; later live tests may be skipped"
  else
    log "  (no unplug detected -- skipped. The engage-diff in LAYER 5 still observes the firmware.)"
  fi
else
  log "  (skipped -- no active charging to fingerprint)"
fi

log ""
log "==== LAYER 4 - known charge switches (15s sustained HOLD verify) ===="
[ "$ACTIVE" = 1 ] || log "  (active hold-tests skipped -- see warnings)"
CAND="$BK/cand.tsv"; : > "$CAND"
emit_known | while IFS='|' read -r pat onv offv; do
  [ -n "$pat" ] || continue
  for f in $(expand_paths "$pat"); do
    case "$f" in *current_now*|*voltage*|*temp*|*capacity*|*present*|*status*) continue;; esac
    printf '%s\t%s\t%s\n' "$f" "$onv" "$offv"
  done
done | sort -u > "$CAND"
while IFS="	" read -r f onv offv; do
  [ -n "$f" ] && test_switch "$f" "$f" "$onv" "$offv"
done < "$CAND"

if [ "$ACTIVE" = 1 ]; then
  log ""
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

log ""
log "==== LAYER 5 - native level limits (write+readback+engage, + engage-diff observe) ===="
test_level "google charge_stop_level" /sys/devices/platform/google,charger/charge_stop_level /sys/devices/platform/google,charger/charge_start_level
test_level "google(soc) charge_stop_level" /sys/devices/platform/soc/soc:google,charger/charge_stop_level /sys/devices/platform/soc/soc:google,charger/charge_start_level
for f in $(expand_paths "*/batt_full_capacity"); do test_level "samsung $f" "$f" ""; done
test_level "charge_control_end_threshold" "$PSY/battery/charge_control_end_threshold" "$PSY/battery/charge_control_start_threshold"
test_level "lge charge_stop_level" /sys/module/lge_battery/parameters/charge_stop_level ""
test_level "qpnp upper_limit" /sys/module/qpnp_adaptive_charge/parameters/upper_limit "" -1
if [ "$ACTIVE" = 1 ] && ex "$PSY/battery/voltage_max" && ! over; then
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

log ""
log "==== LAYER 6 - discovery (read-only report of all charge-control nodes) ===="
SAFE_RE='charging_enabled|battery_charging_enabled|charge_enabled|charging_enable|enable_charging|enable_charger|input_suspend|battery_input_suspend|op_disable_charge|disable_charging|charge_disable|batt_slate_mode|mmi_charging_enable|smart_charging_interruption|batt_protect_en|night_charging|bypass_charger|disable_charger|charging_suspend_en|charger_control|force_charger_suspend|force_usb_suspend'
n_disc=0
for d in $DDIRS_ALL; do ex "$d" || continue
  for f in $( { if [ "$HAVE_TO" = 1 ]; then timeout "$TO" find -L "$d" -maxdepth 4 -type f 2>/dev/null; else find -L "$d" -maxdepth 4 -type f 2>/dev/null; fi; } | awk '!seen[$0]++' | sed -n '1,400p' ); do
    printf '%s' "$f" | grep -Eq "$DENY_RE" && continue
    printf '%s' "$f" | grep -Eqi "$NAME_RE" || continue
    grep -qF "$f|" "$DISC" 2>/dev/null && continue
    [ "$n_disc" -ge 120 ] && break
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
  while IFS='|' read -r path w; do
    over && { log "  [deadline] stop new tests"; break; }
    [ "$SKIPALL" = 1 ] && break
    [ "$tested_new" -ge "$MAX_NEW" ] && { log "  [cap] MAX_NEW reached"; break; }
    [ "$w" = RW ] || continue
    grep -qxF "$path" "$SNLIST" 2>/dev/null && continue
    bn="$(basename "$path")"
    cur="$(rd "$path" | sed -n '1p')"
    bool_like "$cur" || continue
    if safe_to_write "$path"; then
      off="$(flip_val "$cur")"; [ -n "$off" ] || continue
      grep -qxF "$path|$off" "$BK/tested" 2>/dev/null && continue
      bH="$BYPASS$CUT$DRAIN"
      test_switch "[NEW] $path=$off" "$path" "$cur" "$off"
      [ "$BYPASS$CUT$DRAIN" != "$bH" ] && NEWHITS="$NEWHITS|$path on=$cur off=$off"
      tested_new=$((tested_new+1))
    elif printf '%s' "$bn" | grep -Eqi 'charg|chg|suspend|enable|disable|bypass|slate|mmi|protect|night' && ! printf '%s' "$path" | grep -Eiq "$DANGER_RE" && ! printf '%s' "$bn" | grep -Eq "$EFFECT_RE"; then
      flag_observe "$path" "=$cur RW switch-like, name not in trusted set -> NOT written"
    fi
  done < "$DISC"
  log "  -> tested $tested_new trusted new switch(es); flagged ${obs_n:-0} unrecognized candidate(s) for manual review (not written)"
fi

log ""
log "==== LAYER 6c - probe GENERATED candidates (learned by WATCHING the firmware) ===="
: > "$GENC"
if [ -s "$SCHG" ]; then
  { [ -s "$SHELD" ] && diff_pairs "$SCHG" "$SHELD" | sed 's/^/6|/'
    [ -s "$SUNP" ] && diff_pairs "$SCHG" "$SUNP" | sed 's/^/4|/'; } 2>/dev/null | sort -t'|' -k1,1nr | awk -F'|' '!seen[$2]++' > "$GENC"
fi
tested_gen=0
if [ "$ACTIVE" = 1 ] && [ -s "$GENC" ]; then
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
      printf '%s' "$path" | grep -Eiq "$DANGER_RE" || printf '%s' "${path##*/}" | grep -Eq "$EFFECT_RE" || flag_observe "$path" "(firmware-moved, name not in trusted set -> NOT written)"
      continue
    fi
    src=unplug; [ "$score" = 6 ] && src=engage
    test_switch "[GEN:$src] $path=$voff" "$path" "$von" "$voff"
    case "|$BYPASS|$CUT|$DRAIN|" in *"|[GEN:$src] $path=$voff|"*) GENHITS="$GENHITS|$path on=$von off=$voff (observed: firmware wrote $voff when charging stopped)";; esac
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
if [ "$ACTIVE" = 1 ] && [ "$SKIPALL" = 0 ] && [ "$cn" -ge 2 ] 2>/dev/null && [ "$cn" -le 6 ] 2>/dev/null && [ "$GEN_SINGLE_HIT" = 0 ] && ! over; then
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

log ""
log "==== LAYER 6e - FIRMWARE TEACHING (induce a known cut, learn every co-moving node, test+verify each as its own switch) ===="
if [ "$ACTIVE" = 1 ] && [ "$SKIPALL" = 0 ] && [ -n "$TEACH_P" ] && ex "$TEACH_P" && ! over && gate; then
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
      else
        printf '%s' "$tp" | grep -Eiq "$DANGER_RE" || printf '%s' "${tp##*/}" | grep -Eq "$EFFECT_RE" || flag_observe "$tp" "(firmware-taught, name not in trusted set -> NOT written)"
      fi
    done < "$TEACHC"
    sort -t'	' -k1,1nr "$BK/teach_rank.tsv" > "$BK/teach_sorted.tsv" 2>/dev/null
    : > "$BK/teach_combo.tsv"; TEACHED=0; TBUILT=0
    while IFS='	' read -r rs tp tcharg theld; do
      over && { log "  [deadline] stop teaching tests"; break; }
      [ "$SKIPALL" = 1 ] && break
      [ "$TEACHED" -ge "$MAX_TEACH" ] && { log "  [cap] MAX_TEACH reached"; break; }
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
[ -n "$WARN" ] && { log "WARNINGS:$WARN"; log ""; }
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
elif [ -n "$CUT" ]; then
  if [ "$BLINDV" = 1 ]; then
    log "YES (blind-verified): charging STOPS when the switch engages -- confirmed by charging-state + charge_type + voltage, then resumes on re-enable. (current_now is frozen on this ROM, so it was not used.)"
  else
    log "YES (most reliable): ACC holds your limit by CUTTING charge -- a hard cut can never overcharge (verified sustained ~15s; resumes on replug)."
  fi
  printf '%s\n' "$CUT" | tr '|' '\n' | sed '/^$/d' | while read -r l; do log "     - $l"; done
elif [ -n "$BYPASS" ]; then
  log "YES: TRUE BYPASS -- battery idle, charger powers the phone (gentlest on the battery), sustained ~15s. Ranked below a hard cut because an unverified bypass can keep feeding on charge-pump phones; prefer the cut unless you specifically want zero battery cycling."
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
[ -n "$OBSERVED_ONLY" ] && { log ""; log "*** OBSERVED-ONLY CANDIDATES (firmware moved them, or they look switch-like, but the NAME is not in the trusted set -> READ-only, NEVER written this run). Review by hand before adding to ACC: ***"; printf '%s\n' "$OBSERVED_ONLY" | tr '|' '\n' | sed '/^$/d' | while read -r l; do log "   ? $l"; done; }
[ -n "$ADDLINES" ] && { log ""; log "==== READY-TO-ADD ctrl-files.sh lines (verified <path> <on> <off>) ===="; printf '%s\n' "$ADDLINES" | sed '/^$/d' | while read -r l; do log "  $l"; done; }
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
# is_usable / pick_usable: looser than is_clean -- a switch is USABLE for the recommendation if it HELD
# the cut and isn't latch/resume-STUCK. It KEEPS switches whose resume is "after-reset" (the daemon
# re-arms them automatically, e.g. input_suspend needs apsd_rerun) or slow/unknown. This is essential
# for reliability-first: a reliable CUT that resumes after ACC re-arms must still outrank a clean BYPASS
# (the cut can never overcharge; the bypass can fake idle on charge-pump phones). is_clean stays for the
# stricter "one-tap perfect" checks elsewhere.
is_usable(){ case "|$STUCKS|" in *"|$1|"*) return 1;; esac
  case "$RESUMES" in *"$1=STUCK"*) return 1;; esac
  return 0; }
pick_usable(){ puf="$BK/pickusable"; printf '%s\n' "$1" | tr '|' '\n' | sed '/^$/d' > "$puf" 2>/dev/null
  pu_pick=
  while IFS= read -r pu_c; do [ -n "$pu_c" ] || continue; is_usable "$pu_c" && { pu_pick="$pu_c"; break; }; done < "$puf"
  printf '%s' "$pu_pick"; }
label_path(){ lpp="$1"
  case "$lpp" in
    "fcc-zero "*) lpp="${lpp#fcc-zero }";;
    "[NEW] "*) lpp="${lpp#\[NEW\] }";;
    "[NEW:safe] "*) lpp="${lpp#\[NEW:safe\] }";;
    "[GEN:"*|"[LEARN"*) lpp="${lpp#*] }";;
  esac
  printf '%s' "${lpp%%=*}"; }
cfg_lookup(){ clp="$(label_path "$1")"; [ -n "$clp" ] || return
  printf '%s\n' "$ADDLINES" | sed 's/^[ 	]*//' | grep -F "$clp " | sed -n '1p' | sed 's/ (.*$//'; }

# acc_current: what ACC's daemon currently has LOCKED, so the report + AccA can show "ACC uses X"
# alongside the verified options (the user wants to compare the engine's pick against the alternatives).
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

# emit_alts: serialize every OTHER working switch (not the recommendation), reliability-ranked, as
# additive altN_* keys (schema=1, ignored by old AccA). All per-switch data was already computed during
# the run -- v5.5 discarded it; v5.6 surfaces it so AccA can show "Other working switches" + per-row Apply.
emit_alts(){
  _TAB="$(printf '\t')"; _rf="$BK/ranked"; : > "$_rf" 2>/dev/null
  _erow(){ printf '%s\n' "$2" | tr '|' '\n' | sed '/^$/d' | while read -r _l; do
    _lc="$(printf '%s' "$_l" | sed 's/(accepts)$//;s/(ro)$//')"; [ -n "$_lc" ] || continue
    printf '%s%s%s\n' "$1" "$_TAB" "$_lc" >> "$_rf"; done; }
  _leE="$(printf '%s\n' "$LEVELOK" | tr '|' '\n' | sed '/^$/d' | grep -vE '\(accepts\)|\(ro\)' | tr '\n' '|')"
  _leA="$(printf '%s\n' "$LEVELOK" | tr '|' '\n' | sed '/^$/d' | grep -E '\(accepts\)' | tr '\n' '|')"
  _erow native-level "$_leE"; _erow cut "$CUT"; _erow bypass "$BYPASS"; _erow drain "$DRAIN"; _erow native-accepts "$_leA"; _erow throttle "$THROTTLE"
  _an=0
  while IFS="$_TAB" read -r _cls _lbl; do
    [ -n "$_lbl" ] || continue
    [ "$_lbl" = "$RECO_LBL" ] && continue
    _ctrl="$(cfg_lookup "$_lbl")"; [ -n "$_ctrl" ] || continue
    _an=$((_an+1))
    _res=ok; case "$RESUMES" in *"|$_lbl=after-reset"*) _res=after-reset;; *"|$_lbl=SLOW"*) _res=slow;; *"|$_lbl=STUCK"*) _res=stuck;; *"|$_lbl=UNKNOWN"*) _res=unknown;; esac
    _lat=no; case "|$STUCKS|" in *"|$_lbl|"*) _lat=yes;; esac
    _cf=verified; case "$_cls" in native-accepts) _cf=accepts;; bypass) case "${DRVS:-}" in *bq2597*|*ln8000*|*ln8410*|*sc854*|*sc855*) _cf=needs-long-test;; esac;; esac
    printf 'alt%s_switch=%s\nalt%s_class=%s\nalt%s_conf=%s\nalt%s_resume=%s\nalt%s_latch=%s\nalt%s_note=%s\n' \
      "$_an" "$_ctrl" "$_an" "$_cls" "$_an" "$_cf" "$_an" "$_res" "$_an" "$_lat" "$_an" "$(note_for "$_cls")"
  done < "$_rf"
  printf 'alt_count=%s\n' "$_an"; }

le_enf="$(printf '%s\n' "$LEVELOK" | tr '|' '\n' | sed '/^$/d' | grep -vE '\(accepts\)|\(ro\)' | sed -n '1p')"
RECO=none; RECO_LATCH=0; RECO_LBL=
rb="$(pick_usable "$BYPASS")"; rc="$(pick_usable "$CUT")"; rdr="$(pick_usable "$DRAIN")"; rt="$(pick_usable "$THROTTLE")"
# reliability-first recommendation via reco_pick (single source of truth): a VERIFIED native limit and
# a hard CUT outrank a BYPASS, because in the field the unverified bypass is the class that overcharges.
_reco="$(reco_pick "$le_enf" "$rc" "$rb" "$rdr" "" "$rt")"
if [ -n "$_reco" ]; then
  RECO_LBL="$(_lblof "$_reco")"
  case "$(_clsof "$_reco")" in
    native-level) RECO="$RECO_LBL (native level limit, verified)";;
    cut)          RECO="$RECO_LBL (CUT)";;
    bypass)       RECO="$RECO_LBL (BYPASS)";;
    drain)        RECO="$RECO_LBL (CUT, discharges while plugged)";;
    throttle)     RECO="$RECO_LBL (throttle only)";;
  esac
fi
if [ -z "$RECO_LBL" ]; then
  if [ -n "$BYPASS$CUT$DRAIN" ]; then
    RECO_LATCH=1
    # latch fallback, same reliability-first order: CUT before BYPASS before DRAIN.
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

SUGGEST=
[ -n "$RECO_LBL" ] && SUGGEST="$(cfg_lookup "$RECO_LBL")"
if [ -z "$SUGGEST" ]; then
  # reliability-first fallback (mirror reco_pick): verified-native > CUT > BYPASS > DRAIN > ACC history.
  if [ -n "$le_enf" ] && [ -n "$CFG_LEVEL" ]; then SUGGEST="$CFG_LEVEL"
  elif [ -n "$CUT" ] && [ -n "$CFG_CUT" ]; then SUGGEST="$CFG_CUT"
  elif [ -n "$BYPASS" ] && [ -n "$CFG_BYPASS" ]; then SUGGEST="$CFG_BYPASS"
  elif [ -n "$DRAIN" ] && [ -n "$CFG_DRAIN" ]; then SUGGEST="$CFG_DRAIN"
  elif [ -n "$ACC_FALLBACK" ]; then SUGGEST="$ACC_FALLBACK"
  fi
fi

log ""
log "############ DECODED: WHAT IS GOING ON IN THIS PHONE ############"
np=0; for o in $(online_f); do case "$(read1 "$o")" in 1) np=$((np+1));; esac; done
log "  charge architecture: $np energized charger path(s); drivers:${DRVS:- unknown}"
vmax="$(rd "$BATT/uevent" | sed -n 's/^POWER_SUPPLY_VOLTAGE_MAX=//p' | sed -n '1p' | tr -dc '0-9')"
[ -n "$vmax" ] && log "  native charge ceiling: VOLTAGE_MAX=${vmax} uV (firmware float/termination voltage -- the natural 100% point)"
log "  current reporting: $UNIT, charging reads $([ "$CHGDIR" = p ] && echo POSITIVE || echo NEGATIVE) (confidence $SIGN_CONF)$([ "$CUR_FROZEN" = 1 ] && echo "  -- SENSOR FROZEN at $RAW; proof done BLIND via status/charge_type/voltage")"
[ -n "$VOLTF" ] && log "  voltage: $(vmv) mV now (baseline V0=${V0}mV, noise +/-${VNOISE}mV, blind cut-threshold ${VDROP}mV)"
log "  verification method: $([ "$BLINDV" = 1 ] && echo "BLIND (charging-state + charge_type + voltage)" || echo "current delta (sensor live)")"
log "  battery now: ${CAP}%  $(( $(batt_temp) / 10 )).$(( $(batt_temp) % 10 ))C  status=$(read_st)"
log "  control classes found on this phone:"
log "    native %-limit : $([ -n "$CFG_LEVEL" ] && echo "YES ($CFG_LEVEL)" || { [ -n "$LEVELOK" ] && echo "accepts values (enforcement unproven this run)" || echo no; })"
log "    battery-idle   : $([ -n "$BYPASS" ] && echo "YES -- true bypass, lowest wear" || echo "not proven")"
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
  [ -n "$BYPASS" ] && log "  acc -s prioritize_batt_idle_mode=true   # this phone supports true battery-idle"
  log "  # optional: acc -s shutdown_capacity=0   # disable low-battery auto-shutdown entirely"
  sugbase="${SUGGEST%% *}"; sugbase="${sugbase##*/}"
  log "  # PIN it: the charging_switch line above LOCKS ACC to this ONE verified switch,"
  log "  #         so ACC will not auto-shift to a different (non-working) switch."
  latchw=0; [ "$RECO_LATCH" = 1 ] && latchw=1
  [ -n "$sugbase" ] && case "$STUCKS" in *"$sugbase"*) latchw=1;; esac
  if [ "$latchw" = 1 ]; then
    log "  # WARNING: the recommended switch LATCHES -- it does not re-arm on its own."
    log "  #          This is the 'charging stops at the limit and never charges again' problem."
    log "  #          ACC must rewrite the ON value on re-plug (native_unlatch). If charging stays stuck:"
    log "  #            acc -s loop_delay=10      # poll faster so re-arm happens sooner"
    log "  #          and reboot once to clear a hard latch."
  fi
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
log "SCRIPT=acc-compat-v$V"
log "DEVICE=$(getprop ro.product.manufacturer 2>/dev/null)_$(getprop ro.product.device 2>/dev/null)"
log "SOC=$(getprop ro.board.platform 2>/dev/null)"
log "ANDROID=$(getprop ro.build.version.release 2>/dev/null)"
log "ACC=${ACCV:-no}"
log "SENSOR=${CURF:-none}"
log "UNITS=$UNIT SIGN=$CHGDIR CONF=$SIGN_CONF BASE=$RAW"
log "SYSFS_STATUS=$ST ANDROID_STATUS=${AST:-na}"
log "BATT_TEMP=$(batt_temp) (0.1C units)  CAPACITY=${CAP}%"
log "IDLE_MODE=$([ -n "$BYPASS" ] && echo yes || echo no)   (battery-idle / 'bypass' charging support)"
log "ACTIVE=$ACTIVE SKIPALL=$SKIPALL"
log "CUR_USABLE=$CUR_USABLE CUR_FROZEN=$CUR_FROZEN BLIND=$BLINDV PROOF=$PROOF"
log "VOLT_mV=$(vmv) V0=$V0 VNOISE=$VNOISE VDROP=$VDROP VRISE=$VRISE"
log "DRIVERS=${DRVS# }"
log "OBS_UNPLUG=$OBS_UNPLUG OBS_ENGAGE=$OBS_ENGAGE"
log "BYPASS=${BYPASS#\|}"
log "CUT=${CUT#\|}"
log "DRAIN=${DRAIN#\|}"
log "THROTTLE=${THROTTLE#\|}"
log "REASSERT=${REASSERT#\|}"
log "LEVELOK=${LEVELOK#\|}"
log "NEWHITS=${NEWHITS#\|}"
log "GENHITS=${GENHITS#\|}"
log "LEARNED=${LEARNED#\|}"
log "BUILT=${BUILT#\|}"
log "TEACH=teacher=${TEACH_P:-none} learned=$NLEARN tested=$TEACHED verified=$TBUILT"
log "OBSERVED_ONLY=${obs_n:-0} (switch-like/firmware-moved nodes READ but NOT written)"
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
log ""
ART=/data/local/tmp/acc-compat-verified
acls=unknown
case "$RECO" in
  # order matters: the drain + native phrasings both contain "CUT"/level words, so match them FIRST.
  *"native level"*) acls=level;;
  *"discharges while plugged"*|*"CUT/drain"*) acls=drain;;
  *BYPASS*) acls=bypass;;
  *CUT*) acls=cut;;
  *throttle*) acls=throttle;;
esac
_pnote="$(path_note "$acls")"; [ -n "$_pnote" ] && { log ""; log "  NOTE (charge path): $_pnote"; }
case "$RECO" in
  none) aconf=none;; *LATCHES*) aconf=latch-needs-rearm;; *"accepts values"*) aconf=unconfirmed;; *history*) aconf=from-ACC-history;; *) aconf=verified;;
esac
case "${DRVS:-}" in *bq2597*|*ln8000*|*ln8410*|*sc854*|*sc855*|*pca94*|*pca12*|*upm672*)
  case "$acls" in bypass|cut|drain) [ "$aconf" = verified ] && aconf=pump-needs-long-test;; esac;; esac
_advc="$(getprop ro.product.device 2>/dev/null)"; [ -n "$_advc" ] || _advc="$(getprop ro.build.product 2>/dev/null)"; [ -n "$_advc" ] || _advc="$(getprop ro.product.name 2>/dev/null)"
_advs="$(getprop ro.board.platform 2>/dev/null)"; [ -n "$_advs" ] || _advs="$(getprop ro.hardware 2>/dev/null)"
# v5.5 artifact extras: resume verdict for the recommended switch, and whether the two-phase
# re-arm verify ran on it. polarity/units/weak_charger come from the live measurement above.
RESUME_OK=na
[ -n "${RECO_LBL:-}" ] && case "$RESUMES" in
  *"|$RECO_LBL=OK"*)          RESUME_OK=ok;;
  *"|$RECO_LBL=after-reset"*) RESUME_OK=after-reset;;
  *"|$RECO_LBL=SLOW"*)        RESUME_OK=slow;;
  *"|$RECO_LBL=STUCK"*)       RESUME_OK=stuck;;
esac
REARM_DONE=no; printf '%s' "${SUGGEST:-}" | grep -Eq "$REARM_RE" && REARM_DONE=yes
# v5.6: ACC's current locked switch + the ranked OTHER working switches, additive before ok=1.
ACCCUR="$(acc_current)"; ALTS="$(emit_alts)"
case "$aconf" in
  verified|pump-needs-long-test)
    if [ -n "${SUGGEST:-}" ] && [ "${SUGGEST:-none}" != none ]; then
      # $() strips trailing newlines from ACCCUR/ALTS, so emit each as its own newline-terminated chunk
      # (guarded on non-empty -> no blank lines) and keep ok=1 strictly last for the sentinel check.
      { printf 'schema=1\ncharging_switch=%s\nclass=%s\nconf=%s\npolarity=%s\nunits=%s\nresume=%s\nweak_charger=%s\nrearm_checked=%s\ndevice=%s\nsoc=%s\nscript=acc-compat-v5.7\nts=%s\n' "$SUGGEST" "$acls" "$aconf" "${POLARITY:-normal}" "${UNIT:-mA}" "$RESUME_OK" "${WEAK_CHARGER:-0}" "$REARM_DONE" "$_advc" "$_advs" "${TS:-}"
        [ -n "$ACCCUR" ] && printf '%s\n' "$ACCCUR"
        [ -n "$ALTS" ] && printf '%s\n' "$ALTS"
        printf 'ok=1\n'; } > "${ART}.tmp" 2>/dev/null
    else
      { printf 'schema=1\nresult=no-switch\nreason=no-pinnable\npolarity=%s\nweak_charger=%s\ndevice=%s\nsoc=%s\nscript=acc-compat-v5.7\n' "${POLARITY:-normal}" "${WEAK_CHARGER:-0}" "$_advc" "$_advs"
        [ -n "$ACCCUR" ] && printf '%s\n' "$ACCCUR"
        printf 'ok=1\n'; } > "${ART}.tmp" 2>/dev/null
    fi ;;
  *) { printf 'schema=1\nresult=no-switch\nreason=%s\npolarity=%s\nweak_charger=%s\ndevice=%s\nsoc=%s\nscript=acc-compat-v5.7\n' "$aconf" "${POLARITY:-normal}" "${WEAK_CHARGER:-0}" "$_advc" "$_advs"
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
log "|  battery: I=${_ib}mA  V=${_vb}mV  (~$((_ib*_vb/1000))mW)   IC cap (CCC)=${_ccc}mA"
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
log "|  VERDICT  (acc-compat v$V)"
log "|  Device:       $(getprop ro.product.model 2>/dev/null) [$_advc / $_advs]"
log "|  Best switch:  ${SUGGEST:-none}"
log "|  Type: $acls    Confidence: $aconf"
case "$aconf" in
  verified) log "|  -> In AccA: tap 'Apply & Lock verified switch' (it re-tests live before pinning)";;
  pump-needs-long-test) log "|  -> Charge-pump device: held 15s but may leak under load -- AccA live-tests; watch for slow drain at the cap";;
  latch-needs-rearm) log "|  -> This switch LATCHES -- AccA will not auto-pin; needs reboot/re-arm";;
  none) log "|  -> No safe switch found this run -- AccA makes no change";;
  *) log "|  -> Unconfirmed -- re-run plugged at 40-80% to verify before pinning";;
esac
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
