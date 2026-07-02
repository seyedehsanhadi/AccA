#!/bin/sh
set -e
cd "$(dirname "$0")/.."
RAW=app/src/main/res/raw/djs_bundle
[ -f "$RAW" ] || { echo "ERROR: $RAW not found"; exit 1; }
T=$(mktemp -d)
cp "$RAW" "$T/b.tgz"; ( cd "$T" && tar -xzf b.tgz )
D=$(find "$T" -maxdepth 1 -type d -name 'djs_*')
[ -n "$D" ] || { echo "ERROR: bundle layout unexpected"; exit 1; }

sed -i 's|^if \[ -d /sbin \]; then$|if [ -d /sbin ] \&\& [ -d /data/adb/magisk ]; then|' "$D/djs/djs.sh"
grep -q '\-d /data/adb/magisk' "$D/djs/djs.sh" || { echo "ERROR: /sbin gate not applied"; exit 1; }

cat > "$D/djs/service.sh" <<'EOF'
#!/system/bin/sh
B=${0%/*}
D=${DJS_DATA_DIR:-/data/adb/vr25/djs-data}
M=${DJS_MOD_DIR:-/data/adb/modules/djs}
MG=${DJS_MAGISK_DIR:-/data/adb/magisk}
mkdir -p "$D/logs" 2>/dev/null
mk="$D/.boot-attempt"
bid=${DJS_BOOT_ID:-$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)}
if [ -n "$bid" ] && [ -f "$mk" ] && [ ".$(cat "$mk" 2>/dev/null)" != ".$bid" ]; then
  rm -f "$mk" 2>/dev/null
  touch "$M/disable" 2>/dev/null
  echo "$(date 2>/dev/null) watchdog(service): previous boot did not complete with djs enabled, module self-disabled (re-enable to retry once)" >> "$D/logs/boot-watchdog.log" 2>/dev/null
  exit 0
fi
[ -n "$bid" ] && echo "$bid" > "$mk" 2>/dev/null
if [ ! -d "$MG" ] && command -v nsenter > /dev/null 2>&1; then
  if command -v setsid > /dev/null 2>&1; then
    setsid nsenter -t 1 -m -- sh "$B/djs-boot.sh" < /dev/null >> "$D/logs/boot.log" 2>&1 &
  else
    nsenter -t 1 -m -- sh "$B/djs-boot.sh" < /dev/null >> "$D/logs/boot.log" 2>&1 &
  fi
else
  if command -v setsid > /dev/null 2>&1; then
    setsid sh "$B/djs-boot.sh" < /dev/null >> "$D/logs/boot.log" 2>&1 &
  else
    sh "$B/djs-boot.sh" < /dev/null >> "$D/logs/boot.log" 2>&1 &
  fi
fi
exit 0
EOF

cat > "$D/djs/djs-boot.sh" <<'EOF'
#!/system/bin/sh
D=${DJS_DATA_DIR:-/data/adb/vr25/djs-data}
M=${DJS_MOD_DIR:-/data/adb/modules/djs}
H=${DJS_HOME:-/data/adb/vr25/djs}
S=${DJS_STORAGE:-/data/media/0/Android}
SW=${DJS_STORAGE_WATCH:-/storage/emulated/0/Android}
MG=${DJS_MAGISK_DIR:-/data/adb/magisk}
V=${DJS_DEV_DIR:-/dev/.vr25/djs}
N=${DJS_GATE_TRIES:-120}
P=${DJS_GATE_STEP:-5}
W=${DJS_POST_SLEEP:-60}
K=${DJS_START_WAIT:-30}
E=${DJS_SETTLE:-10}
bid=${DJS_BOOT_ID:-$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)}
log() { echo "$(date '+%m-%d %H:%M:%S' 2>/dev/null) $*"; }
log "gate: waiting for boot_completed + user storage"
i=0
while :; do
  bc=$(getprop sys.boot_completed 2>/dev/null)
  [ ".$bc" = .1 ] && [ -d "$S" ] && break
  i=$((i+1))
  if [ "$i" -ge "$N" ]; then
    if [ ".$bc" = .1 ]; then
      log "gate timeout: system booted but user storage never became visible, djsd not started, marker kept so the watchdog can self-disable"
    else
      log "gate timeout: boot_completed never reached, djsd not started, watchdog decides next boot"
    fi
    exit 0
  fi
  sleep "$P"
done
L="$D/.gate-${bid:-x}"
if ! mkdir "$L" 2>/dev/null; then
  log "another starter already owns this boot, exiting"
  exit 0
fi
for o in "$D"/.gate-*; do [ "$o" = "$L" ] || rm -rf "$o" 2>/dev/null; done
sleep "$E"
rm -f "$D/.boot-attempt" 2>/dev/null
log "gate passed: boot complete, user storage up"
how=
start_djs() {
  if [ -d "$MG" ]; then
    how=magisk
    sh "$H/djs.sh" >> "$D/logs/djs-start.log" 2>&1 &
  elif command -v nsenter > /dev/null 2>&1; then
    how="init mount namespace"
    nsenter -t 1 -m -- sh "$H/djs.sh" >> "$D/logs/djs-start.log" 2>&1 &
  fi
}
wait_djs() {
  j=0
  while :; do
    [ -e "$V/djsc" ] && return 0
    [ "$j" -ge "$K" ] && return 1
    sleep 1; j=$((j+1))
  done
}
start_djs
if [ -z "$how" ]; then
  log "nsenter unavailable on this root solution, djsd not started (fail-safe)"
  exit 0
fi
if ! wait_djs; then
  log "daemon not up after ${K}s, retry"
  start_djs
  if ! wait_djs; then
    log "djs.sh ran but the daemon did not come up within ${K}s after a retry, see logs/djs-start.log"
    exit 0
  fi
fi
log "djsd started ($how)"
sleep "$W"
if [ ! -d "$SW" ]; then
  sh "$H/djs-stop.sh" > /dev/null 2>&1
  touch "$M/disable" 2>/dev/null
  log "user storage disappeared after djsd start: djsd stopped, module self-disabled"
fi
exit 0
EOF

cat > "$D/djs/post-fs-data.sh" <<'EOF'
#!/system/bin/sh
D=${DJS_DATA_DIR:-/data/adb/vr25/djs-data}
M=${DJS_MOD_DIR:-/data/adb/modules/djs}
mkdir -p "$D/logs" 2>/dev/null
mk="$D/.boot-attempt"
bid=${DJS_BOOT_ID:-$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)}
[ -n "$bid" ] || exit 0
if [ -f "$mk" ] && [ ".$(cat "$mk" 2>/dev/null)" != ".$bid" ]; then
  rm -f "$mk" 2>/dev/null
  touch "$M/disable" 2>/dev/null
  echo "$(date 2>/dev/null) watchdog: previous boot did not complete with djs enabled, module self-disabled (re-enable to retry once)" >> "$D/logs/boot-watchdog.log" 2>/dev/null
else
  echo "$bid" > "$mk" 2>/dev/null
fi
exit 0
EOF

if ! grep -q 'in inner)' "$D/install.sh"; then
  S_LN=$(grep -n 'echo "#!/system/bin/sh' "$D/install.sh" | head -1 | cut -d: -f1)
  E_LN=$(grep -n 'cleanup\.sh$' "$D/install.sh" | head -1 | cut -d: -f1)
  [ -n "$S_LN" ] && [ -n "$E_LN" ] && [ "$S_LN" -lt "$E_LN" ] || { echo "ERROR: cleanup anchors not found"; exit 1; }
  cat > "$T/cleanup.new" <<'EOF'
    echo "#!/system/bin/sh
      case \"\$1\" in inner) :;; *) sh \"\$0\" inner < /dev/null > /dev/null 2>&1 & exit 0;; esac
      until test -d /sdcard/Android && test .\$(getprop sys.boot_completed) = .1; do sleep 60; done
      sleep 60
      [ -e $accaFiles/$id ] || rm -rf \"\$0\" /data/adb/$domain/$id /data/adb/modules/$id 2>/dev/null
      exit 0" | sed 's/^      //' > /data/adb/service.d/${id}-cleanup.sh
EOF
  awk -v s="$S_LN" -v e="$E_LN" -v r="$T/cleanup.new" '
    NR==s { while ((getline line < r) > 0) print line; next }
    NR>s && NR<=e { next }
    { print }
  ' "$D/install.sh" > "$T/install.new"
  mv "$T/install.new" "$D/install.sh"
  grep -q 'in inner)' "$D/install.sh" || { echo "ERROR: cleanup splice failed"; exit 1; }
fi

if ! grep -q 'realMagisk' "$D/install.sh"; then
  awk '
    { print }
    index($0, "&& magisk=true") { print "[ -d /data/adb/magisk ] && realMagisk=true || realMagisk=false" }
  ' "$D/install.sh" > "$T/i1"
  awk '
    index($0, "! $magisk || {") && !done {
      getline nxt
      if (index(nxt, "symlink executables")) { print "! $realMagisk || {"; print nxt; done=1; next }
      print; print nxt; next
    }
    { print }
  ' "$T/i1" > "$T/i2"
  awk '
    { print }
    index($0, "cp $srcDir/module.prop $installDir/") {
      print "case $installDir in /data/adb/modules*) $realMagisk || touch $installDir/skip_mount;; esac"
    }
  ' "$T/i2" > "$T/i3"
  mv "$T/i3" "$D/install.sh"
  grep -q 'realMagisk=true' "$D/install.sh" || { echo "ERROR: realMagisk insert failed"; exit 1; }
  grep -q 'skip_mount' "$D/install.sh" || { echo "ERROR: skip_mount insert failed"; exit 1; }
  grep -q '! $realMagisk || {' "$D/install.sh" || { echo "ERROR: symlink gate failed"; exit 1; }
fi

sed -i 's/^versionCode=2021082[0-9][0-9]$/versionCode=202108262/' "$D/module.prop"
grep -q 'versionCode=202108262' "$D/module.prop" || { echo "ERROR: module.prop bump failed"; exit 1; }

sh -n "$D/djs/service.sh" && sh -n "$D/djs/djs-boot.sh" && sh -n "$D/djs/post-fs-data.sh" && sh -n "$D/djs/djs.sh" && sh -n "$D/install.sh"

( cd "$T" && tar -czf b2.tgz "$(basename "$D")" )
cp "$T/b2.tgz" "$RAW"
rm -rf "$T"
echo "djs_bundle repacked: $(wc -c < "$RAW") bytes"
