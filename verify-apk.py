#!/usr/bin/env python3
"""
Pre-ship gate for the AccA APK.

Companion to ACC's build-zip.py. Different failure modes, same discipline: never hand out an
artifact that cannot install or that ships a stale copy of something canonical.

What actually bites here:
  * SIGNING - a debug-signed APK will NOT install over a release-signed one. The user gets
    "App not installed" and has to uninstall first, losing their profiles. This is the AccA
    equivalent of the module zip that dies on KernelSU.
  * STALE BUNDLED ASSETS - AccA ships acc-compat.sh, which also lives in the ACC repo. If the
    two drift, the app runs a different tester than the module. (AccA once also bundled its own
    acc-diag.sh, left unreachable when the menu moved to the module's collector; it shipped in
    every APK and could never run.)

Usage: python verify-apk.py <apk> [--expect-release]
Exit code is non-zero on failure so it can gate a release.
"""
import os, sys, zipfile, hashlib

ACC_REPO = r'C:\Users\PC\Desktop\PROJECTS\ACC'

REQUIRED_ASSETS = ['assets/acc-compat.sh']
# must NOT be present: superseded by the module's diag-collect.sh via `acc --diag`
FORBIDDEN_ASSETS = ['assets/acc-diag.sh']
# bundled asset -> canonical source that must match byte for byte
SYNC = {'assets/acc-compat.sh': os.path.join(ACC_REPO, 'acc-compat.sh')}


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    apk = sys.argv[1]
    expect_release = '--expect-release' in sys.argv
    bad, warn = [], []

    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())

        debug_signed = any(n.startswith('META-INF/') and n.endswith(('.RSA', '.DSA', '.EC'))
                           and 'CERT' in n.upper() for n in names)
        has_sig = any(n.upper().startswith('META-INF/') and n.upper().endswith(('.RSA', '.DSA', '.EC'))
                      for n in names)
        if not has_sig:
            bad.append('APK is NOT SIGNED at all')

        # debug builds carry the AndroidDebugKey; detect via the debug certificate contents
        signer_dbg = False
        for n in names:
            if n.upper().startswith('META-INF/') and n.upper().endswith(('.RSA', '.DSA', '.EC')):
                blob = z.read(n)
                if b'Android Debug' in blob or b'AndroidDebugKey' in blob:
                    signer_dbg = True
        kind = 'DEBUG' if signer_dbg else 'release-or-unknown'
        print(f'signing        : {kind}')
        if expect_release and signer_dbg:
            bad.append('debug-signed but a RELEASE build was expected '
                       '(will not install over a release-signed AccA)')
        elif signer_dbg:
            warn.append('debug-signed: testers with a release-signed AccA must uninstall first '
                        '(they lose saved profiles)')

        for a in REQUIRED_ASSETS:
            if a not in names:
                bad.append(f'missing required asset: {a}')
        for a in FORBIDDEN_ASSETS:
            if a in names:
                bad.append(f'STALE asset shipped (dead code, cannot run): {a}')

        for a, canonical in SYNC.items():
            if a not in names:
                continue
            if not os.path.isfile(canonical):
                warn.append(f'cannot check {a}: canonical source missing at {canonical}')
                continue
            in_apk = hashlib.md5(z.read(a)).hexdigest()
            with open(canonical, 'rb') as f:
                on_disk = hashlib.md5(f.read()).hexdigest()
            if in_apk != on_disk:
                bad.append(f'{a} has DRIFTED from {canonical} (apk={in_apk[:12]} repo={on_disk[:12]})')
            else:
                print(f'asset in sync  : {a} ({in_apk[:12]})')

        dex = sum(1 for n in names if n.endswith('.dex'))
        print(f'dex files      : {dex}')
        if dex == 0:
            bad.append('no dex: this APK contains no code')

    print(f'size           : {os.path.getsize(apk) / 1048576:.1f} MB')
    for w in warn:
        print('WARN  ', w)
    if bad:
        print('FAILED:')
        for b in bad:
            print('  ', b)
        return 1
    print('OK: signed, required assets present and in sync, no stale assets.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
