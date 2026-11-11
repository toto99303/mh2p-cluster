#!/bin/ksh
# Manual, read-only QNX process capture. Stream stdout to a file on the PC.
# No dmdt, Screen access, signals, background workers or vehicle file writes.
# Licensed under CC BY-NC-SA 4.0, matching this repository.
set -u
PATH="${PATH:-/bin:/usr/bin}:/mnt/app/armle/usr/bin:/mnt/app/armle/bin"

usage() {
    echo "usage: ksh $0 [samples:1..120] [pause_seconds:30..300]" >&2
    echo "default: 40 samples, 30 seconds between completed samples" >&2
    exit 2
}
[ "$#" -le 2 ] || usage
samples="${1-40}"
pause_seconds="${2-30}"
# Reject leading zeroes (shell arithmetic can interpret them as octal).
for value in "$samples" "$pause_seconds"; do
    case "$value" in ''|0*|*[!0-9]*) usage ;; esac
    [ "${#value}" -le 3 ] || usage
done
[ "$samples" -ge 1 ] && [ "$samples" -le 120 ] || usage
[ "$pause_seconds" -ge 30 ] && [ "$pause_seconds" -le 300 ] || usage
if ! command -v pidin >/dev/null 2>&1; then
    echo "ERROR: pidin unavailable; no process evidence collected" >&2
    exit 1
fi

failures=0
run() {
    echo "=== COMMAND: $* ==="
    "$@" 2>&1
    result=$?
    echo "=== EXIT: $result ==="
    if [ "$result" -ne 0 ]; then failures=$((failures + 1)); fi
}
crash_listing() {
    # These paths are confirmed in this A6 C8's dumper/JVM command lines.
    # List only; never open or copy a potentially large core file here.
    run ls -lt /mnt/crashlogs/java /mnt/crashlogs/core
}
system_log() {
    if command -v sloginfo >/dev/null 2>&1; then
        run sloginfo
    else
        echo "sloginfo unavailable: system log not captured"
    fi
}

echo "MH2P_HEALTH_CAPTURE_V1"
echo "samples=$samples pause_seconds=$pause_seconds"
echo "Sequential observations; elapsed capture time includes command execution."
echo "A blocked command stops progress; no further queries are started behind it."
echo "No per-command timeout. Interrupt SSH if a sample stalls for over a minute."
echo "=== START ==="
run date
crash_listing
system_log

sample=1
while [ "$sample" -le "$samples" ]; do
    echo "=== SAMPLE $sample OF $samples START ==="
    run date
    run pidin info
    run pidin ar
    # j9 is the JVM process name; 'java' is its account name on this unit.
    run pidin -p j9 threads
    run pidin -p j9 pmem
    run pidin -p j9 fds
    run pidin -p j9 times
    for process in gal cluster displaymanager; do
        run pidin -p "$process" pmem
    done
    echo "=== SAMPLE $sample OF $samples END ==="
    if [ "$sample" -lt "$samples" ]; then
        if ! sleep "$pause_seconds"; then
            echo "ERROR: sampling pause failed; stopping to avoid rapid queries" >&2
            exit 1
        fi
    fi
    sample=$((sample + 1))
done

echo "=== FINISH ==="
run date
crash_listing
system_log
echo "MH2P_HEALTH_CAPTURE_COMPLETE samples=$samples command_failures=$failures"
echo "An absent process or unsupported query can return empty output even with exit 0."
if [ "$failures" -ne 0 ]; then exit 1; fi
exit 0
