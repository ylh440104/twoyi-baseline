#!/usr/bin/env bash
# Collect everything needed to diagnose the guest on a real device, in one run.
#
# Usage:
#   ./tools/device_probe.sh                 # probe the one attached device
#   ./tools/device_probe.sh -s SERIAL       # pick a device when several are attached
#   ./tools/device_probe.sh --loader PATH   # also push and run the loader diagnostic
#   ./tools/device_probe.sh --dry-run       # validate the script without touching adb
#
# The probe only reads: it never installs, wipes or starts anything. The single
# output file can be pasted back or attached to a report.

set -uo pipefail

OUT="device-report.txt"
SERIAL=""
LOADER=""
DRY_RUN=0
PACKAGE="${TWOYI_PACKAGE:-io.twoyi}"

while [ $# -gt 0 ]; do
    case "$1" in
        -o|--out) OUT="$2"; shift 2 ;;
        -s|--serial) SERIAL="$2"; shift 2 ;;
        --loader) LOADER="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [ "$DRY_RUN" -eq 1 ]; then
    echo "dry run: script parses, adb is not touched"
    echo "would write: $OUT"
    exit 0
fi

ADB="adb"
if [ -n "$SERIAL" ]; then
    ADB="adb -s $SERIAL"
fi

if ! command -v adb >/dev/null 2>&1; then
    echo "adb is not on PATH" >&2
    exit 1
fi

: > "$OUT"

section() {
    printf '\n## %s\n' "$1" >> "$OUT"
}

run() {
    # Record the command and its output; failures are recorded, not fatal.
    printf '$ %s\n' "$*" >> "$OUT"
    "$@" >> "$OUT" 2>&1 || printf '(exited %s)\n' "$?" >> "$OUT"
}

run_shell() {
    printf '$ adb shell %s\n' "$1" >> "$OUT"
    $ADB shell "$1" >> "$OUT" 2>&1 || printf '(exited %s)\n' "$?" >> "$OUT"
}

printf '# twoyi device probe\n' >> "$OUT"
printf 'date: %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" >> "$OUT"
printf 'package: %s\n' "$PACKAGE" >> "$OUT"

section "1. device"
run $ADB devices -l
run_shell 'getprop ro.product.model'
run_shell 'getprop ro.product.manufacturer'
run_shell 'getprop ro.build.version.release'
run_shell 'getprop ro.build.version.sdk'
run_shell 'getprop ro.product.cpu.abi'
run_shell 'getprop ro.product.cpu.abilist'
run_shell 'getprop ro.build.type'
run_shell 'uname -a'

section "2. root and package"
run_shell 'id'
run_shell "su 0 id"
run_shell "pm list packages | grep -i twoyi"
run_shell "dumpsys package $PACKAGE | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime|primaryCpuAbi|codePath'"

section "3. guest data layout"
run_shell "su 0 ls -la /data/data/$PACKAGE | head -40"
run_shell "su 0 ls -la /data/data/$PACKAGE/rootfs 2>/dev/null | head -40"
run_shell "su 0 ls -la /data/data/$PACKAGE/slots 2>/dev/null"
run_shell "su 0 ls -la /data/data/$PACKAGE/rootfs/dev/input 2>/dev/null"
run_shell "su 0 ls -la /data/data/$PACKAGE/rootfs/gfxstream-vulkan 2>/dev/null"

section "4. guest init identity"
# The ELF header decides whether the image can load at all.
run_shell "su 0 head -c 64 /data/data/$PACKAGE/rootfs/init 2>/dev/null | od -An -tx1 | head -4"
run_shell "su 0 ls -l /data/data/$PACKAGE/rootfs/init 2>/dev/null"
run_shell "su 0 cat /data/data/$PACKAGE/rootfs/system/build.prop 2>/dev/null | grep -E 'ro.build.(fingerprint|version.release|version.sdk|type)'"

section "5. what the app recorded about the last attempt"
run_shell "su 0 cat /data/data/$PACKAGE/files/preflight.txt 2>/dev/null"
run_shell "su 0 tail -n 60 /data/data/$PACKAGE/rootfs/log.txt 2>/dev/null"
run_shell "su 0 cat /data/data/$PACKAGE/rootfs/last_kmsg.txt 2>/dev/null | tail -n 40"

section "6. processes and recent crashes"
run_shell "ps -A | grep -E 'io.twoyi|guest'"
run_shell "logcat -d -t 400 -b crash"

if [ -n "$LOADER" ]; then
    section "7. loader diagnostic"
    if [ ! -f "$LOADER" ]; then
        printf 'loader %s not found\n' "$LOADER" >> "$OUT"
    else
        printf 'local hash: %s\n' "$(sha256sum "$LOADER" 2>/dev/null | cut -d' ' -f1)" >> "$OUT"
        run $ADB push "$LOADER" /data/local/tmp/miniloader-plan
        run $ADB shell chmod 755 /data/local/tmp/miniloader-plan
        run $ADB shell sha256sum /data/local/tmp/miniloader-plan
        # A binary that is on the device but cannot run reports why here.
        run_shell 'ls -lZ /data/local/tmp/miniloader-plan'
        run_shell 'cd /data/local/tmp && ./miniloader-plan /system/bin/toybox 4096 --check'
        run_shell 'cd /data/local/tmp && ./miniloader-plan /system/bin/toybox 4096 --map'
        run_shell "su 0 /data/local/tmp/miniloader-plan /data/data/$PACKAGE/rootfs/init 4096 --check"
        run $ADB shell /data/local/tmp/miniloader-plan 2>&1 | head -3
    fi
else
    section "7. loader diagnostic"
    printf 'skipped: pass --loader PATH to include it\n' >> "$OUT"
fi

section "8. what this report means"
cat >> "$OUT" <<'NOTES'
- section 4 tells whether the installed guest image is a loadable AArch64 binary.
- section 5 is what the app itself concluded; "preflight.txt" is the newest line first.
- section 7 runs the self-built loader diagnostic on the device:
    --check  prints the verdict and which imports this device can serve
    --map    maps the image and prints the entry bytes
- Paste this file back; nothing in it is secret beyond file listings.
NOTES

echo "wrote $OUT"
echo
echo "Next: send $OUT back, or attach it to the report."
