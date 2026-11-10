#!/bin/ksh
# Manual, parked ER P2711 probe; NOT a boot/Persist script or automatic fix.
# The probe player must already be installed and AA must be decoding on ID62.
# Licensed under CC BY-NC-SA 4.0, matching this repository.
set -u
if [ "$#" -ne 0 ]; then
    echo "usage: ksh $0 (parked; AA connected; route62 probe player installed)" >&2
    exit 2
fi
PATH="${PATH:-/bin:/usr/bin}:/mnt/app/armle/usr/bin:/mnt/app/armle/bin"
PROBE_SCREEN="${MH2P_SCREEN_ROOT:-/dev/screen}"
PROBE_DMDT="${MH2P_DMDT:-/eso/bin/apps/dmdt}"
PROBE_LOCK="${MH2P_ROUTE62_LOCK:-/tmp/mh2p-route62-probe.lock}"
PROBE_ARMED=0
PROBE_CLEANUP_STATUS=0
if [ ! -x "$PROBE_DMDT" ]; then
    echo "ERROR: stock dmdt is not executable: $PROBE_DMDT" >&2
    exit 2
fi
for PROBE_TOOL in dd awk sleep rm; do
    command -v "$PROBE_TOOL" >/dev/null 2>&1 || {
        echo "ERROR: required tool unavailable: $PROBE_TOOL" >&2
        exit 2
    }
done
# /tmp is flat on this QNX system; never mkdir or assume property nodes are
# regular files. Read only these two known text-property aliases, up to 16KiB.
if ! PROBE_NATIVE=$(dd if="$PROBE_SCREEN/navStartup/33" bs=1024 count=16 2>/dev/null); then
    echo "REFUSED: cannot read Audi's native window 33; no display change." >&2
    exit 2
fi
if ! printf '%s\n' "$PROBE_NATIVE" | awk '
    /\(navStartup\)$/ { owner=1 }
    /^id string = 33$/ { id=1 }
    /^parent = .*\(displaymanager\)$/ { parent=1 }
    /^buffer size = 1920x600$/ { size=1 }
    /^streams = / && $0 != "streams = (none)" { stream=1 }
    END { exit !(owner && id && parent && size && stream) }
'; then
    echo "REFUSED: Audi's 1920x600 source 33 is not attached as expected; no display change." >&2
    exit 2
fi
if ! PROBE_AA=$(dd if="$PROBE_SCREEN/cluster/62" bs=1024 count=16 2>/dev/null); then
    echo "REFUSED: AA window 62 is unavailable. Check installation/decoder; no display change." >&2
    exit 2
fi
if ! printf '%s\n' "$PROBE_AA" | awk '
    /\(cluster\)$/ { owner=1 }
    /^id string = 62$/ { id=1 }
    /^parent = .*\(displaymanager\)$/ { parent=1 }
    /^buffer size = 1920x722$/ { size=1 }
    /^streams = / && $0 != "streams = (none)" { stream=1 }
    /^flags = / && index($0,"WIN_FLAG_VISIBLE") { admitted=1 }
    END { exit !(owner && id && parent && size && stream && admitted) }
'; then
    echo "REFUSED: AA's source 62 is not attached/admitted with the expected geometry; no display change." >&2
    exit 2
fi
if ! (set -C; echo "$$" > "$PROBE_LOCK") 2>/dev/null; then
    echo "REFUSED: another probe or stale lock exists: $PROBE_LOCK" >&2
    exit 2
fi
probe_cleanup() {
    if [ "$PROBE_ARMED" -eq 1 ]; then
        PROBE_ARMED=0
        echo "Releasing the temporary override to the current HMI-buffered cockpit layout."
        "$PROBE_DMDT" sb 4
        PROBE_RESTORE=$?
        echo "sb exit=$PROBE_RESTORE (request status; verify the physical display)."
        if [ "$PROBE_RESTORE" -ne 0 ]; then
            PROBE_CLEANUP_STATUS=1
            echo "Restore request failed. Run /eso/bin/apps/dmdt sb 4 manually; an MMI restart clears this memory-only override." >&2
        fi
    fi
    rm -f "$PROBE_LOCK"
    return "$PROBE_CLEANUP_STATUS"
}
trap 'probe_cleanup' 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
echo "MH2P_ROUTE62_MANUAL_PROBE_V1: both native 33 and AA 62 are attached."
echo "Selecting [41,62] on cockpit display 4 for 12 seconds. Stay on the navigation view."
# Arm cleanup before issuing the request: a failed command might have applied.
PROBE_ARMED=1
if ! "$PROBE_DMDT" sd 4 41 62; then
    echo "AA selection request failed; releasing the override." >&2
    exit 1
fi
sleep 12 || exit 1
if ! probe_cleanup; then
    trap - 0
    exit 1
fi
trap - 0
echo "Probe finished. Disconnect the phone, wait 10 seconds, and check Audi's map."
