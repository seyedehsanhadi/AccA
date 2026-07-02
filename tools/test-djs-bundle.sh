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

grep -q 'in inner)' "$D/install.sh" && ok "install.sh cleanup detached" || bad "install.sh cleanup inline"
grep -q 'realMagisk' "$D/install.sh" && ok "install.sh symlink farm gated on real Magisk" || bad "install.sh symlink farm ungated"
grep -q 'skip_mount' "$D/install.sh" && ok "install.sh writes skip_mount off Magisk" || bad "install.sh no skip_mount"
grep -q '/data/adb/magisk' "$D/djs/djs.sh" && ok "djs.sh /sbin magisk gate" || bad "djs.sh /sbin gate missing"
grep -q 'sleep' "$D/djs/service.sh" && bad "service.sh still waits inline" || ok "service.sh has no inline wait"
grep -q 202108262 "$D/module.prop" && ok "module.prop bumped 202108262" || bad "module.prop not bumped"
grep -q 'data/media/0/Android' "$D/djs/djs-boot.sh" && ok "gate oracle is ns-independent backing store" || bad "gate oracle still FUSE path"
grep -q 'djs-start.log' "$D/djs/djs-boot.sh" && ok "djs.sh start is logged, not /dev/null" || bad "djs.sh start swallowed"

R="$T/rig"
mkdir -p "$R/bin" "$R/data/logs" "$R/mod" "$R/home" "$R/media/Android" "$R/fuse/Android" "$R/magisk" "$R/dev"
cat > "$R/bin/getprop" <<'EOF'
#!/bin/sh
echo "${GETPROP_BOOT:-1}"
EOF
cat > "$R/home/djs.sh" <<EOF
#!/bin/sh
touch "$R/started"
mkdir -p "$R/dev"
touch "$R/dev/djsc"
EOF
cat > "$R/home/djs-stop.sh" <<EOF
#!/bin/sh
touch "$R/stopped"
EOF
chmod +x "$R/bin/getprop" "$R/home/djs.sh" "$R/home/djs-stop.sh"

env_common() {
  DJS_DATA_DIR="$R/data" DJS_MOD_DIR="$R/mod" DJS_HOME="$R/home" \
  DJS_STORAGE="$R/media/Android" DJS_STORAGE_WATCH="$R/fuse/Android" \
  DJS_MAGISK_DIR="$R/magisk" DJS_DEV_DIR="$R/dev" \
  DJS_START_WAIT=1 DJS_SETTLE=0 \
  PATH="$R/bin:$PATH" "$@"
}
MK="$R/data/.boot-attempt"

rm -f "$MK" "$R/mod/disable"
env_common env DJS_BOOT_ID=boot-1 sh "$D/djs/post-fs-data.sh"
[ "$(cat "$MK" 2>/dev/null)" = boot-1 ] && [ ! -f "$R/mod/disable" ] && ok "pfd watchdog arms marker with boot id" || bad "pfd arm"

env_common env DJS_BOOT_ID=boot-2 sh "$D/djs/post-fs-data.sh"
[ -f "$R/mod/disable" ] && [ ! -f "$MK" ] && ok "pfd watchdog trips on foreign boot id" || bad "pfd trip"

rm -f "$R/mod/disable" "$R/started" "$R/dev/djsc"
echo boot-2 > "$MK"
env_common env DJS_BOOT_ID=boot-3 DJS_GATE_STEP=0 DJS_POST_SLEEP=0 sh "$D/djs/service.sh"
sleep 1
[ -f "$R/mod/disable" ] && [ ! -f "$R/started" ] && ok "service watchdog trips on foreign boot id, gate not spawned" || bad "service trip"

rm -f "$R/mod/disable" "$R/started" "$R/dev/djsc"
rm -rf "$R/data/.gate-"*
echo boot-3 > "$MK"
S0=$(date +%s)
env_common env DJS_BOOT_ID=boot-3 DJS_GATE_STEP=0 DJS_POST_SLEEP=0 sh "$D/djs/service.sh"
S1=$(date +%s)
[ $((S1-S0)) -le 2 ] && ok "service.sh returns instantly" || bad "service.sh blocked $((S1-S0))s"
n=0; while [ ! -f "$R/started" ] && [ $n -lt 50 ]; do sleep 0.2; n=$((n+1)); done
[ -f "$R/started" ] && [ ! -f "$R/mod/disable" ] && ok "same-boot marker: no trip, gate starts djsd (magisk path)" || bad "same-boot marker handling"
n=0; while [ -f "$MK" ] && [ $n -lt 50 ]; do sleep 0.2; n=$((n+1)); done
[ ! -f "$MK" ] && ok "gate clears watchdog marker" || bad "marker not cleared"
sleep 1
grep -q "djsd started" "$R/data/logs/boot.log" && ok "daemon verified via runtime link" || bad "daemon verify positive"

rm -f "$R/started" "$R/dev/djsc"
env_common env DJS_BOOT_ID=boot-3 DJS_GATE_STEP=0 DJS_POST_SLEEP=0 sh "$D/djs/djs-boot.sh" > "$R/lockout" 2>&1
grep -qi "another starter" "$R/lockout" && [ ! -f "$R/started" ] && ok "per-boot lock: second starter exits without a second daemon" || bad "single-starter lock"

rm -f "$R/started" "$R/dev/djsc" "$R/flag"
rm -rf "$R/data/.gate-"*
: > "$R/data/logs/boot.log"
cat > "$R/home/djs.sh" <<EOF
#!/bin/sh
if [ -f "$R/flag" ]; then
  touch "$R/started"
  mkdir -p "$R/dev"
  touch "$R/dev/djsc"
else
  touch "$R/flag"
fi
EOF
chmod +x "$R/home/djs.sh"
env_common env DJS_BOOT_ID=boot-r DJS_GATE_STEP=0 DJS_POST_SLEEP=0 sh "$D/djs/djs-boot.sh" >> "$R/data/logs/boot.log" 2>&1
grep -q "retry" "$R/data/logs/boot.log" && grep -q "djsd started" "$R/data/logs/boot.log" && [ -f "$R/started" ] && ok "verify-fail retries once then succeeds" || bad "retry path"

rm -f "$R/started" "$R/dev/djsc" "$R/flag"
rm -rf "$R/data/.gate-"*
: > "$R/data/logs/boot.log"
cat > "$R/home/djs.sh" <<'EOF'
#!/bin/sh
:
EOF
chmod +x "$R/home/djs.sh"
env_common env DJS_BOOT_ID=boot-n DJS_GATE_STEP=0 DJS_POST_SLEEP=0 sh "$D/djs/djs-boot.sh" >> "$R/data/logs/boot.log" 2>&1
grep -q "did not come up" "$R/data/logs/boot.log" && ok "daemon verify negative logged honestly" || bad "daemon verify negative"
cat > "$R/home/djs.sh" <<EOF
#!/bin/sh
touch "$R/started"
mkdir -p "$R/dev"
touch "$R/dev/djsc"
EOF
chmod +x "$R/home/djs.sh"

rm -f "$R/started" "$R/dev/djsc"
rm -rf "$R/data/.gate-"*
echo boot-4 > "$MK"
env_common env DJS_BOOT_ID=boot-4 GETPROP_BOOT=0 DJS_GATE_TRIES=2 DJS_GATE_STEP=0 DJS_POST_SLEEP=0 sh "$D/djs/djs-boot.sh" >/dev/null 2>&1
[ ! -f "$R/started" ] && [ -f "$MK" ] && ok "no boot_completed: no start, marker kept" || bad "timeout-no-boot"

rm -rf "$R/media/Android"
env_common env DJS_BOOT_ID=boot-4 DJS_GATE_TRIES=2 DJS_GATE_STEP=0 DJS_POST_SLEEP=0 sh "$D/djs/djs-boot.sh" >/dev/null 2>&1
[ ! -f "$R/started" ] && [ -f "$MK" ] && ok "booted but storage never up: no start, marker kept" || bad "timeout-no-storage must keep marker"
mkdir -p "$R/media/Android"
rm -f "$MK"

rm -f "$R/started" "$R/dev/djsc"
rm -rf "$R/data/.gate-"*
MG2="$R/nomagisk"
DJS_DATA_DIR="$R/data" DJS_MOD_DIR="$R/mod" DJS_HOME="$R/home" \
DJS_STORAGE="$R/media/Android" DJS_STORAGE_WATCH="$R/fuse/Android" \
DJS_MAGISK_DIR="$MG2" DJS_DEV_DIR="$R/dev" DJS_START_WAIT=1 DJS_SETTLE=0 \
DJS_GATE_STEP=0 DJS_POST_SLEEP=0 PATH="$R/bin:/usr/bin" sh "$D/djs/djs-boot.sh" > "$R/nolog" 2>&1
[ ! -f "$R/started" ] && grep -q "fail-safe" "$R/nolog" && ok "no nsenter off-magisk: fail-safe skip" || bad "wrong-namespace fallback possible"

rm -f "$R/started" "$R/stopped" "$R/mod/disable" "$R/dev/djsc"
rm -rf "$R/data/.gate-"*
env_common env DJS_BOOT_ID=boot-5 DJS_GATE_STEP=0 DJS_POST_SLEEP=2 sh "$D/djs/djs-boot.sh" >/dev/null 2>&1 &
J=$!
n=0; while [ ! -f "$R/started" ] && [ $n -lt 30 ]; do sleep 0.1; n=$((n+1)); done
rm -rf "$R/fuse/Android"
wait $J
[ -f "$R/stopped" ] && [ -f "$R/mod/disable" ] && ok "user storage break after start: djsd stopped + self-disabled" || bad "storage-break watchdog"
mkdir -p "$R/fuse/Android"

echo
echo "PASS=$PASS FAIL=$FAIL"
[ $FAIL -eq 0 ]
