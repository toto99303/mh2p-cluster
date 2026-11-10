#!/usr/bin/env bash
# Host-only fixtures. No stock class or ARM binary is executed.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROUTE_TEST="$(mktemp -d)"; trap 'rm -rf "$ROUTE_TEST"' EXIT
NAV=de/audi/app/terminalmode/smartphone/androidauto2/nav
SRC="$HERE/../../audi/q7-p2711/readiness/$NAV"
java -jar "${ECJ_JAR:?set ECJ_JAR}" -1.4 -source 1.4 -target 1.4 -nowarn -d "$ROUTE_TEST/engine" \
    "$SRC/Mh2pRoutePolicy.java" "$SRC/Mh2pRouteEngine.java" "$SRC/Mh2pDmdt.java" \
    "$HERE/auto-route-fixtures/RouteTest.java" "$HERE/auto-route-fixtures/DmdtTest.java"
java -Xverify:all -cp "$ROUTE_TEST/engine" RouteTest
java -Xverify:all -cp "$ROUTE_TEST/engine" DmdtTest ${DMDT_PRIVATE_INPUT:+"$DMDT_PRIVATE_INPUT"}
java -jar "$ECJ_JAR" -1.4 -source 1.4 -target 1.4 -nowarn -d "$ROUTE_TEST/gate" \
    "$SRC/Mh2pVideoGate.java" "$HERE/auto-route-fixtures/Mh2pAutoRoute.java" \
    "$HERE/readiness-fixtures/GateTest.java" \
    $(find "$HERE/readiness-fixtures/de" -name '*.java')
java -Xverify:all -cp "$ROUTE_TEST/gate" GateTest
