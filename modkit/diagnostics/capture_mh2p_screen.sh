#!/bin/ksh
# Read-only MH2p Screen snapshot, V2. Run manually over SSH, never at boot.
# /tmp can be a flat shared-memory namespace: create ONE file, no directories.
# Reads process/window/display state; writes only the new output file supplied.
# Licensed under CC BY-NC-SA 4.0, matching this repository.
set -u
if [ "$#" -ne 1 ]; then
    echo "usage: ksh $0 /tmp/mh2p-screen-oem.txt (use a NEW file per snapshot)" >&2
    exit 2
fi
OUT="$1"
case "$OUT" in
    /*) ;;
    *) echo "ERROR: supply an absolute output filename" >&2; exit 2 ;;
esac
if [ -e "$OUT" ]; then
    echo "ERROR: output already exists; choose a new filename: $OUT" >&2
    exit 2
fi
umask 077
# noclobber also protects against an existing file appearing after the check.
if ! (set -C; : > "$OUT") 2>/dev/null; then
    echo "ERROR: cannot create $OUT; use a new filename in an existing writable location" >&2
    exit 2
fi
SCREEN_ROOT="${MH2P_SCREEN_ROOT:-/dev/screen}"
PATH="${PATH:-/bin:/usr/bin}:/mnt/app/armle/usr/bin:/mnt/app/armle/bin"
count=0
attempts=0
limit=128
capture() {
    echo "MH2P_SCREEN_SNAPSHOT_V2_SINGLE_FILE"
    date
    echo "screen root: $SCREEN_ROOT"
    echo "Read-only inspection; no directories, Screen contexts or property changes."
    echo "=== PROCESSES ==="
    if command -v pidin >/dev/null 2>&1; then
        pidin ar
    else
        echo "pidin unavailable"
    fi
    if [ -d "$SCREEN_ROOT" ]; then
        echo "=== SCREEN ROOT LIST ==="
        ls -la "$SCREEN_ROOT"
        for owner in "$SCREEN_ROOT"/[0-9]*; do
            [ -d "$owner" ] || continue
            pid="${owner##*/}"
            case "$pid" in *[!0-9]*|'') continue ;; esac
            echo "=== SCREEN OWNER $pid LIST ==="
            ls -la "$owner"
            for object in "$owner"/win-* "$owner"/dpy-*; do
                label="${object##*/}"
                props="$object"
                # QNX 6.6 may expose win-N/win-N instead of a flat win-N file.
                if [ -d "$props" ]; then props="$props/$label"; fi
                [ -f "$props" ] || continue
                if [ "$attempts" -ge "$limit" ]; then
                    echo "Property-read limit reached ($limit); snapshot is incomplete."
                    return
                fi
                attempts=$((attempts + 1))
                echo "=== PROPERTY: $props ==="
                # Only text properties: at most 16 KiB each, at most 128 files.
                # Never read BMPs, pixel buffers, context/control channels or
                # binary request rings. Read errors stay in this report.
                if dd if="$props" bs=1024 count=16; then
                    count=$((count + 1))
                else
                    echo "read failed: $props"
                fi
                echo ""
                echo "=== END PROPERTY ==="
            done
        done
        echo "text window/display snapshots: $count"
        if [ "$count" -eq 0 ]; then
            echo "No readable window/display properties. Screen debug support or permissions may be unavailable."
        fi
    else
        echo "Screen debug directory unavailable; no window ownership conclusion can be drawn."
    fi
    echo "=== END SNAPSHOT ==="
}
if ! capture >> "$OUT" 2>&1; then
    echo "ERROR: snapshot capture/write failed; inspect $OUT" >&2
    exit 1
fi
echo "Snapshot saved: $OUT"
echo "Download this ONE file using SCP before rebooting."
