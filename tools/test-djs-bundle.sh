#!/bin/sh
set -u
cd "$(dirname "$0")/.."
RAW=app/src/main/res/raw/djs_bundle
PASS=0; FAIL=0
ok() { PASS=$((PASS+1)); echo "ok  $1"; }
bad() { FAIL=$((FAIL+1)); echo "FAIL $1"; }

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
cp "$RAW" "$T/b.tgz"; ( cd "$T" && tar -xzf b.tgz )
D=$(find "$T" -maxdepth 1 -type d -name 'djs_*')

for f in service.sh djs-boot.sh post-fs-data.sh djs.sh; do
  if [ -f "$D/djs/$f" ] && sh -n "$D/djs/$f" 2>/dev/null; then ok "syntax $f"; else bad "syntax/missing $f"; fi
done
sh -n "$D/install.sh" 2>/dev/null && ok "syntax install.sh" || bad "syntax install.sh"

grep -q 'in inner)' "$D/install.sh" && ok "install.sh cleanup detached (v2)" || bad "install.sh cleanup still inline"
grep -q '/data/adb/magisk' "$D/djs/djs.sh" && ok "djs.sh /sbin magisk gate" || bad "djs.sh /sbin gate missing"
grep -q 'sleep' "$D/djs/service.sh" && bad "service.sh still waits inline" || ok "service.sh has no inline wait"
grep -q 202108261 "$D/module.prop" && ok "module.prop bumped 202108261" || bad "module.prop not bumped"

R="$T/rig"
mkdir -p "$R/bin" "$R/data/logs" "$R/mod" "$R/home" "$R/storage/Android" "$R/magisk"
cat > "$R/bin/getprop" <<'EOF'
#!/bin/sh
echo "${GETPROP_BOOT:-1}"
EOF
cat > "$R/home/djs.sh" <<EOF
#!/bin/sh
touch "$R/started"
EOF
cat > "$R/home/djs-stop.sh" <<EOF
#!/bin/sh
touch "$R/stopped"
EOF
chmod +x "$R/bin/getprop" "$R/home/djs.sh" "$R/home/djs-stop.sh"

env_common() {
  DJS_DATA_DIR="$R/data" DJS_MOD_DIR="$R/mod" DJS_HOME="$R/home" \
  DJS_STORAGE="$R/storage/Android" DJS_MAGISK_DIR="$R/magisk" \
  PATH="$R/bin:$PATH" "$@"
}

rm -f "$R/data/.boot-attempt" "$R/mod/disable"
env_common sh "$D/djs/post-fs-data.sh"
[ -f "$R/data/.boot-attempt" ] && [ ! -f "$R/mod/disable" ] && ok "watchdog arms marker on clean boot" || bad "watchdog arm"

env_common sh "$D/djs/post-fs-data.sh"
[ -f "$R/mod/disable" ] && [ ! -f "$R/data/.boot-attempt" ] && ok "watchdog self-disables + consumes marker" || bad "watchdog trip"

rm -f "$R/mod/disable" "$R/started" "$R/stopped"
touch "$R/data/.boot-attempt"
S0=$(date +%s)
env_common env DJS_GATE_STEP=0 DJS_POST_SLEEP=0 sh "$D/djs/service.sh"
S1=$(date +%s)
[ $((S1-S0)) -le 2 ] && ok "service.sh returns instantly" || bad "service.sh blocked ${S1}-${S0}s"
n=0; while [ ! -f "$R/started" ] && [ $n -lt 50 ]; do sleep 0.2; n=$((n+1)); done
[ -f "$R/started" ] && ok "gate starts djsd when boot+storage up (magisk path)" || bad "djsd not started"
[ ! -f "$R/data/.boot-attempt" ] && ok "gate clears watchdog marker" || bad "marker not cleared"

rm -f "$R/started"
touch "$R/data/.boot-attempt"
env_common env GETPROP_BOOT=0 DJS_GATE_TRIES=2 DJS_GATE_STEP=0 DJS_POST_SLEEP=0 sh "$D/djs/djs-boot.sh" >/dev/null 2>&1
[ ! -f "$R/started" ] && [ -f "$R/data/.boot-attempt" ] && ok "no boot_completed: no start, marker kept for watchdog" || bad "timeout-no-boot behavior"

rm -rf "$R/storage/Android"
env_common env DJS_GATE_TRIES=2 DJS_GATE_STEP=0 DJS_POST_SLEEP=0 sh "$D/djs/djs-boot.sh" >/dev/null 2>&1
[ ! -f "$R/started" ] && [ ! -f "$R/data/.boot-attempt" ] && ok "booted but storage never visible: no start, marker cleared" || bad "timeout-no-storage behavior"
mkdir -p "$R/storage/Android"

rm -f "$R/started"
MG2="$R/nomagisk"
DJS_DATA_DIR="$R/data" DJS_MOD_DIR="$R/mod" DJS_HOME="$R/home" \
DJS_STORAGE="$R/storage/Android" DJS_MAGISK_DIR="$MG2" \
DJS_GATE_STEP=0 DJS_POST_SLEEP=0 PATH="$R/bin:/usr/bin" sh "$D/djs/djs-boot.sh" > "$R/nolog" 2>&1
[ ! -f "$R/started" ] && grep -q "fail-safe" "$R/nolog" && ok "no nsenter off-magisk: fail-safe skip" || bad "wrong-namespace fallback still possible"

rm -f "$R/started" "$R/stopped" "$R/mod/disable"
env_common env DJS_GATE_STEP=0 DJS_POST_SLEEP=1 sh "$D/djs/djs-boot.sh" >/dev/null 2>&1 &
J=$!
sleep 0.3; rm -rf "$R/storage/Android"
wait $J
[ -f "$R/stopped" ] && [ -f "$R/mod/disable" ] && ok "storage break after start: djsd stopped + self-disabled" || bad "storage-break watchdog"
mkdir -p "$R/storage/Android"

echo
echo "PASS=$PASS FAIL=$FAIL"
[ $FAIL -eq 0 ]
