#!/bin/ksh
# Read-only query preflight for P2711's HMI user. Changes a RAM copy only.
# Never use the temporary query copy for sd/sb/dm or other mutating commands.
# Licensed under CC BY-NC-SA 4.0, matching this repository.
set -u
PATH="${PATH:-/bin:/usr/bin}:/mnt/app/armle/usr/bin:/mnt/app/armle/bin"
if [ "$#" -gt 1 ]; then
    echo "usage: ksh $0 [/tmp/NEW-report.txt]" >&2
    exit 2
fi
QUERY_SOURCE="${MH2P_DMDT_SOURCE:-/eso/bin/apps/dmdt}"
QUERY_PREFIX="${MH2P_QUERY_PREFIX:-/tmp/mh2p-dmdt-capture-$$}"
QUERY_REPORT="${1:-/tmp/mh2p-dmdt-query-probe.txt}"
QUERY_COPY="$QUERY_PREFIX.bin"
QUERY_OUT="$QUERY_PREFIX.out"
case "$QUERY_REPORT" in /*) ;; *) echo "Use an absolute report filename" >&2; exit 2 ;; esac
for QUERY_TOOL in dd wc cp chmod rm on awk cat; do
    command -v "$QUERY_TOOL" >/dev/null 2>&1 || { echo "Missing tool: $QUERY_TOOL" >&2; exit 2; }
done
# These site checks match the owner's uploaded ELF. The automatic helper must
# also pin the full SHA256; this shell preflight checks size and both sites.
QUERY_SIZE=$(wc -c < "$QUERY_SOURCE")
set -- $QUERY_SIZE
if [ "${1:-0}" != 44680 ]; then
    echo "REFUSED: unexpected stock dmdt size" >&2
    exit 2
fi
if [ "$(dd if="$QUERY_SOURCE" bs=1 skip=6895 count=9 2>/dev/null)" != nanosleep ]; then
    echo "REFUSED: unexpected imported nanosleep name" >&2
    exit 2
fi
if [ "$(dd if="$QUERY_SOURCE" bs=1 skip=11788 count=2 2>/dev/null)" != "$(printf '\010\222')" ]; then
    echo "REFUSED: unexpected call-argument instruction" >&2
    exit 2
fi
if ! (set -C; : > "$QUERY_REPORT") 2>/dev/null; then
    echo "REFUSED: choose a new report filename: $QUERY_REPORT" >&2
    exit 2
fi
if ! (set -C; : > "$QUERY_COPY") 2>/dev/null; then
    echo "REFUSED: temporary name already exists: $QUERY_PREFIX" >&2
    exit 2
fi
if ! (set -C; : > "$QUERY_OUT") 2>/dev/null; then
    rm -f "$QUERY_COPY"
    echo "REFUSED: temporary output already exists: $QUERY_OUT" >&2
    exit 2
fi
query_cleanup() { rm -f "$QUERY_COPY" "$QUERY_OUT"; }
trap 'query_cleanup' 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
cp "$QUERY_SOURCE" "$QUERY_COPY" || exit 1
# Reuse the existing import slot for fflush instead of nanosleep. At this
# query-exit call, pass NULL, then retain the original quick_exit(0). This
# flushes output without running global C++ exit destructors or changing ELF
# sizes/dependencies. The lost 50ms pause makes this copy QUERY-ONLY.
printf 'fflush\000\000\000\000' | dd of="$QUERY_COPY" bs=1 seek=6895 conv=notrunc 2>/dev/null || exit 1
printf '\000\040' | dd of="$QUERY_COPY" bs=1 seek=11788 conv=notrunc 2>/dev/null || exit 1
chmod 755 "$QUERY_COPY" || exit 1
echo "MH2P_DMDT_QUERY_CAPTURE_V1: temporary query copy, executed as java" >> "$QUERY_REPORT"
QUERY_FAILED=0
for QUERY_MODE in gs gd; do
    echo "=== $QUERY_MODE ===" >> "$QUERY_REPORT"
    on -u java "$QUERY_COPY" "$QUERY_MODE" > "$QUERY_OUT" 2>&1
    QUERY_STATUS=$?
    cat "$QUERY_OUT" >> "$QUERY_REPORT"
    echo "$QUERY_MODE exit=$QUERY_STATUS" >> "$QUERY_REPORT"
    if [ "$QUERY_STATUS" -ne 0 ] || ! awk '/displaymanager (reports|knows)/ { ok=1 } END { exit !ok }' "$QUERY_OUT"; then
        QUERY_FAILED=1
    fi
done
cat "$QUERY_REPORT"
if [ "$QUERY_FAILED" -ne 0 ]; then
    echo "Query preflight failed; original dmdt and display state were not changed." >&2
    exit 1
fi
echo "Query capture succeeded. Report: $QUERY_REPORT"
