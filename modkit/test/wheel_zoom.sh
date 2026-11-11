#!/usr/bin/env bash
# Fixtures only. No stock HMI class, ARM binary or AA transport is executed.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WHEEL_TEST="$(mktemp -d)"; trap 'rm -rf "$WHEEL_TEST"' EXIT
WHEEL_SRC="$HERE/../../audi/a6-c8-p2711/wheel-zoom/src"
mapfile -t WHEEL_SOURCES < <(rg --files "$WHEEL_SRC" "$HERE/wheel-fixtures" -g '*.java')
java -jar "${ECJ_JAR:?set ECJ_JAR}" -1.4 -source 1.4 -target 1.4 -nowarn \
    -d "$WHEEL_TEST" "${WHEEL_SOURCES[@]}"
java -Xverify:all -cp "$WHEEL_TEST" WheelTest
python3 "$HERE/wheel_wire_test.py" "$WHEEL_TEST"
