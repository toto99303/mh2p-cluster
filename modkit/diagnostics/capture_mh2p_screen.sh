#!/bin/ksh
# Read-only MH2p Screen snapshot. Run manually over SSH; never installed at boot.
# Reads process/window/display state and copies only existing cluster logs.
# Writes ONLY to the new output directory supplied as its one argument.
# Licensed under CC BY-NC-SA 4.0, matching this repository.
set -u
if [ "$#" -ne 1 ]; then
    echo "usage: ksh $0 /tmp/mh2p-screen-oem (use a NEW directory per snapshot)" >&2
    exit 2
fi
OUT="$1"
case "$OUT" in
    /*) ;;
    *) echo "ERROR: supply an absolute output directory" >&2; exit 2 ;;
esac
if [ -e "$OUT" ]; then
    echo "ERROR: output already exists; choose a new directory: $OUT" >&2
    exit 2
fi
# Private-by-default snapshots; collect only the text window/display properties,
# not pixel buffers, Screen control channels, Wi-Fi files, or the whole /tmp.
umask 077
mkdir -p "$OUT" || exit 2
SCREEN_ROOT="${MH2P_SCREEN_ROOT:-/dev/screen}"
LOG_ROOT="${MH2P_LOG_ROOT:-/tmp}"
PATH="${PATH:-/bin:/usr/bin}:/mnt/app/armle/usr/bin:/mnt/app/armle/bin"
{
    echo "MH2P_SCREEN_SNAPSHOT_V1"
    date
    echo "screen root: $SCREEN_ROOT"
    echo "Read-only source inspection; only snapshot output files are written."
} > "$OUT/summary.txt"
if command -v pidin >/dev/null 2>&1; then
    pidin ar > "$OUT/processes.txt" 2>> "$OUT/errors.txt"
else
    echo "pidin unavailable" >> "$OUT/summary.txt"
fi
if [ -d "$SCREEN_ROOT" ]; then
    ls -la "$SCREEN_ROOT" > "$OUT/screen-root-list.txt" 2>> "$OUT/errors.txt"
    count=0
    for owner in "$SCREEN_ROOT"/[0-9]*; do
        [ -d "$owner" ] || continue
        pid="${owner##*/}"
        # Only numeric process directories, including Screen's own PID 0.
        case "$pid" in *[!0-9]*|'') continue ;; esac
        ls -la "$owner" > "$OUT/screen-$pid-list.txt" 2>> "$OUT/errors.txt"
        for object in "$owner"/win-* "$owner"/dpy-*; do
            label="${object##*/}"
            props="$object"
            # Older Screen versions expose win-N/win-N rather than win-N.
            if [ -d "$props" ]; then props="$props/$label"; fi
            [ -f "$props" ] || continue
            # Limit each textual property snapshot to 64 KiB. Never read BMP,
            # context/request channels or binary ring buffers.
            if dd if="$props" of="$OUT/screen-$pid-$label.txt" bs=1024 count=64 2>> "$OUT/errors.txt"; then
                count=$((count + 1))
            else
                echo "read failed: $props" >> "$OUT/summary.txt"
            fi
        done
    done
    echo "text window/display snapshots: $count" >> "$OUT/summary.txt"
    if [ "$count" -eq 0 ]; then
        echo "No readable window/display properties. Screen debug support or permissions may be unavailable." >> "$OUT/summary.txt"
    fi
else
    echo "Screen debug directory unavailable; no window ownership conclusion can be drawn." >> "$OUT/summary.txt"
fi
for name in cluster.log cluster_daemon.log cluster_daemon_start.log gal_preload.log; do
    if [ -f "$LOG_ROOT/$name" ]; then
        cp "$LOG_ROOT/$name" "$OUT/$name" 2>> "$OUT/errors.txt"
    fi
done
echo "Snapshot complete: $OUT"
echo "Read summary.txt and errors.txt; compare OEM-working and disconnected-black snapshots."
