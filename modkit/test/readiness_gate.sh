#!/usr/bin/env bash
# Host fixture tests. Never load or execute the stock HMI JAR.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TASK_TEST="$(mktemp -d)"; trap 'rm -rf "$TASK_TEST"' EXIT
NAV=de/audi/app/terminalmode/smartphone/androidauto2/nav
bash "$HERE/auto_route62.sh"
# Retained historical helper check; candidate4 no longer calls this helper.
java -jar "${ECJ_JAR:?set ECJ_JAR}" -1.4 -source 1.4 -target 1.4 -nowarn \
    -d "$TASK_TEST" "$HERE/../../audi/q7-p2711/readiness/$NAV/Mh2pMapHandover.java" \
    $(find "$HERE/readiness-fixtures/de" -name '*.java') "$HERE/readiness-fixtures/MapHandoverTest.java"
java -Xverify:all -cp "$TASK_TEST" MapHandoverTest
