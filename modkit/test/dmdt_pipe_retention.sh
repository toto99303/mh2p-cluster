#!/usr/bin/env bash
# Reproduce candidate4's bad retention path; success confirms the flaw, not a fix.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PIPE_WORK="$(mktemp -d)"; trap 'rm -rf "$PIPE_WORK"' EXIT
python3 - "$HERE/../../builds/ClusterIntegration_a6_c8_p2711_auto_route62_candidate4.zip" "$PIPE_WORK" <<'PY'
import hashlib,io,pathlib,sys,zipfile
source,work=map(pathlib.Path,sys.argv[1:])
assert hashlib.sha256(source.read_bytes()).hexdigest()=='e340bd30f5a4091974345a22147b4666ad98f2cbe06dc2b01ac9759d0d281d85'
with zipfile.ZipFile(source) as outer:
    names=[n for n in outer.namelist() if '/aa_only/' in n and n.endswith('_aa.jar')]
    assert len(names)==1
    with zipfile.ZipFile(io.BytesIO(outer.read(names[0]))) as jar:
        prefix='de/audi/app/terminalmode/smartphone/androidauto2/nav/Mh2pDmdt'
        for n in (prefix+'.class',prefix+'$Pump.class'):
            p=work/'owned'/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(jar.read(n))
PY
"${JAVAC_HOST:-javac}" -cp "${ASM_JAR:?set ASM_JAR}" -d "$PIPE_WORK/harness" \
    "$HERE/stability-fixtures/DmdtPipeRetention.java"
java -Xverify:all -cp "$PIPE_WORK/harness:$ASM_JAR" DmdtPipeRetention "$PIPE_WORK/owned"
