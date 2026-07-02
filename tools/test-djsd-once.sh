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
DJS="$D/djs/djs.sh"

R="$T/rig"; mkdir -p "$R/bin" "$R/tmp"
cat > "$R/bin/start-stop-daemon" <<EOF
#!/bin/sh
echo "\$@" >> "$R/ssd.calls"
while [ \$# -gt 1 ]; do case "\$1" in -bx) sh "\$2" >/dev/null 2>&1; exit 0;; esac; shift; done
EOF
chmod +x "$R/bin/start-stop-daemon"
export PATH="$R/bin:$PATH"

timed_block() {
  sed -n '/if \[ ! -f \$script \] && grep -q "^\$time " \$config; then/,/^  fi$/p' "$DJS" | sed '1s/^if /if true \&\& /'
}
boot_block() {
  sed -n '/^if \[ ! -f \$tmpDir\/djsd-boot.sh \]; then$/,/^fi$/p' "$DJS"
}

run_timed() {
  tmpDir="$R/tmp"; config="$R/config.txt"; time="$1"
  script=$tmpDir/djsd-${time}.sh
  rm -f "$script"
  eval "$(timed_block)"
}

echo "=== extracted blocks non-empty ==="
[ -n "$(timed_block)" ] && ok "timed block extracted" || bad "timed block empty"
[ -n "$(boot_block)" ] && ok "boot block extracted" || bad "boot block empty"

printf '%s\n' \
  '0930 : accaScheduleId5; touch '"$R"'/ran5; : --delete' \
  '0945 : accaScheduleId6; touch '"$R"'/ran6' \
  > "$R/config.txt"
run_timed 0930
[ -f "$R/ran5" ] && ok "once schedule executed" || bad "once schedule did not run"
grep -q accaScheduleId5 "$R/config.txt" && bad "once line NOT deleted from config" || ok "once line self-deleted"
grep -q accaScheduleId6 "$R/config.txt" && ok "other schedule untouched" || bad "other schedule lost"

run_timed 0945
[ -f "$R/ran6" ] && ok "repeat schedule executed" || bad "repeat schedule did not run"
grep -q accaScheduleId6 "$R/config.txt" && ok "repeat line kept" || bad "repeat line wrongly deleted"

printf '%s\n' \
  '0700 until [ -x '"$R"'/bin/start-stop-daemon ]; do sleep 1; done; sleep 0; : accaScheduleId12; touch '"$R"'/ran12; : --delete; : --boot' \
  > "$R/config.txt"
run_timed 0700
grep -q accaScheduleId12 "$R/config.txt" && bad "prefixed once line NOT deleted" || ok "prefixed once line self-deleted"

printf '%s\n' \
  'boot : accaScheduleId20; touch '"$R"'/ran20; : --delete' \
  'boot : accaScheduleId21; touch '"$R"'/ran21' \
  > "$R/config.txt"
rm -f "$R/tmp/djsd-boot.sh"
tmpDir="$R/tmp"; config="$R/config.txt"
eval "$(boot_block)"
grep -q accaScheduleId20 "$R/config.txt" && bad "boot once line NOT deleted" || ok "boot once line self-deleted"
grep -q accaScheduleId21 "$R/config.txt" && ok "boot repeat line kept" || bad "boot repeat line lost"
grep -q ran20 "$R/tmp/djsd-boot.sh" && ok "boot script contains once command" || bad "boot script missing once command"

echo
echo "PASS=$PASS FAIL=$FAIL"
[ $FAIL -eq 0 ]
