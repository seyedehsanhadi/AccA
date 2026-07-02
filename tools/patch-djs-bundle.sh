#!/bin/sh
# Repacks res/raw/djs_bundle with the KernelSU-safe fixes (run from anywhere; commits nothing):
#  R-defensive: the legacy "/sbin symlinks + remount /" convenience block runs ONLY under
#               real Magisk (it predates KernelSU and its assumptions do not hold there).
#  R2:          the daemon starts only after boot_completed AND decrypted user storage are
#               up, fully detached, and inside init's mount namespace when nsenter exists -
#               a long-lived root daemon started early can pin the emulated-storage mounts
#               (field report: boots-but-storage-missing + system slow).
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
(
  i=0
  until [ "$(getprop sys.boot_completed)" = 1 ] && [ -d /storage/emulated/0/Android ]; do
    sleep 5; i=$((i+1)); [ $i -ge 120 ] && break
  done
  if command -v nsenter >/dev/null 2>&1; then
    nsenter -t 1 -m -- /data/adb/vr25/djs/djs.sh > /dev/null 2>&1
  else
    /data/adb/vr25/djs/djs.sh > /dev/null 2>&1
  fi
) &
exit 0
EOF
sh -n "$D/djs/service.sh" && sh -n "$D/djs/djs.sh"

( cd "$T" && tar -czf b2.tgz "$(basename "$D")" )
cp "$T/b2.tgz" "$RAW"
rm -rf "$T"
echo "djs_bundle repacked: $(wc -c < "$RAW") bytes"
