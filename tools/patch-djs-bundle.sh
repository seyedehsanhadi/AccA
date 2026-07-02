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
mkdir -p "$D/logs" 2>/dev/null
if command -v setsid > /dev/null 2>&1; then
  setsid sh "$B/djs-boot.sh" < /dev/null >> "$D/logs/boot.log" 2>&1 &
else
  sh "$B/djs-boot.sh" < /dev/null >> "$D/logs/boot.log" 2>&1 &
fi
exit 0
EOF

cat > "$D/djs/djs-boot.sh" <<'EOF'
#!/system/bin/sh
D=${DJS_DATA_DIR:-/data/adb/vr25/djs-data}
M=${DJS_MOD_DIR:-/data/adb/modules/djs}
H=${DJS_HOME:-/data/adb/vr25/djs}
S=${DJS_STORAGE:-/storage/emulated/0/Android}
MG=${DJS_MAGISK_DIR:-/data/adb/magisk}
N=${DJS_GATE_TRIES:-120}
P=${DJS_GATE_STEP:-5}
W=${DJS_POST_SLEEP:-60}
log() { echo "$(date '+%m-%d %H:%M:%S' 2>/dev/null) $*"; }
log "gate: waiting for boot_completed + user storage"
i=0
while :; do
  bc=$(getprop sys.boot_completed 2>/dev/null)
  [ ".$bc" = .1 ] && [ -d "$S" ] && break
  i=$((i+1))
  if [ "$i" -ge "$N" ]; then
    if [ ".$bc" = .1 ]; then
      rm -f "$D/.boot-attempt" 2>/dev/null
      log "gate timeout: system booted but user storage never became visible, djsd not started this boot"
    else
      log "gate timeout: boot_completed never reached, djsd not started, watchdog decides next boot"
    fi
    exit 0
  fi
  sleep "$P"
done
rm -f "$D/.boot-attempt" 2>/dev/null
log "gate passed: boot complete, user storage up"
if [ -d "$MG" ]; then
  sh "$H/djs.sh" > /dev/null 2>&1
  log "djsd started (magisk)"
elif command -v nsenter > /dev/null 2>&1; then
  nsenter -t 1 -m -- sh "$H/djs.sh" > /dev/null 2>&1
  log "djsd started (init mount namespace)"
else
  log "nsenter unavailable on this root solution, djsd not started (fail-safe)"
  exit 0
fi
sleep "$W"
if [ ! -d "$S" ]; then
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
if [ -f "$D/.boot-attempt" ]; then
  rm -f "$D/.boot-attempt" 2>/dev/null
  touch "$M/disable" 2>/dev/null
  echo "$(date 2>/dev/null) watchdog: previous boot did not complete with djs enabled, module self-disabled (re-enable to retry once)" >> "$D/logs/boot-watchdog.log" 2>/dev/null
else
  touch "$D/.boot-attempt" 2>/dev/null
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

sed -i 's/^versionCode=202108260$/versionCode=202108261/' "$D/module.prop"
grep -q 'versionCode=202108261' "$D/module.prop" || { echo "ERROR: module.prop bump failed"; exit 1; }

sh -n "$D/djs/service.sh" && sh -n "$D/djs/djs-boot.sh" && sh -n "$D/djs/post-fs-data.sh" && sh -n "$D/djs/djs.sh" && sh -n "$D/install.sh"

( cd "$T" && tar -czf b2.tgz "$(basename "$D")" )
cp "$T/b2.tgz" "$RAW"
rm -rf "$T"
echo "djs_bundle repacked: $(wc -c < "$RAW") bytes"
