#!/usr/bin/env bash
# Build only owned adapter classes against the private stock HMI (never run it).
set -euo pipefail
[[ $# == 4 ]] || { echo 'usage: build_auto_route62.sh stock-lsd.jar candidate3.zip NEW-output.zip ecj.jar' >&2; exit 2; }
ROUTE_STOCK="$1"; ROUTE_BASE="$2"; ROUTE_OUT="$3"; ROUTE_ECJ="$4"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROUTE_WORK="$(mktemp -d)"; trap 'rm -rf "$ROUTE_WORK"' EXIT
python3 - "$ROUTE_STOCK" "$ROUTE_BASE" "$ROUTE_OUT" "$ROUTE_WORK" <<'PY'
import hashlib, pathlib, sys, zipfile
stock, base, out, work = map(pathlib.Path, sys.argv[1:])
for p, sha in [(stock,'96a49c355e5704db7713e4d5485495b631a5b54859d136f5d8ae9067ece718d8'),
               (base,'3fa266640b748be0c363d080225b379bc194826c85b0606e99f17a7917b9bf74')]:
    if hashlib.sha256(p.read_bytes()).hexdigest() != sha: raise SystemExit('Unverified input: '+str(p))
if out.exists() or out.resolve() in (stock.resolve(),base.resolve()): raise SystemExit('Choose a new output path')
with zipfile.ZipFile(base) as z:
    names=[n for n in z.namelist() if '/aa_only/' in n and n.endswith('_aa.jar')]
    if len(names)!=1: raise SystemExit('Expected one AA JAR')
    (work/'base.jar').write_bytes(z.read(names[0]))
PY
NAV=de/audi/app/terminalmode/smartphone/androidauto2/nav
java -jar "$ROUTE_ECJ" -1.4 -source 1.4 -target 1.4 -g:lines,source -nowarn \
    -cp "$ROUTE_WORK/base.jar:$ROUTE_STOCK" -d "$ROUTE_WORK/classes" \
    "$HERE/../audi/q7-p2711/readiness/$NAV/"*.java
python3 - "$ROUTE_BASE" "$ROUTE_WORK/base.jar" "$ROUTE_WORK/classes" "$ROUTE_OUT" <<'PY'
import copy, hashlib, io, pathlib, struct, sys, zipfile
base, jar, classes, out = map(pathlib.Path, sys.argv[1:])
prefix='de/audi/app/terminalmode/smartphone/androidauto2/nav/'
allowed=('KombiMapGuard','Mh2pVideoGate','Mh2pMapHandover','Mh2pAutoRoute','Mh2pDmdt','Mh2pRouteEngine','Mh2pRoutePolicy')
replacements={}
for p in classes.rglob('*.class'):
    name=p.relative_to(classes).as_posix(); data=p.read_bytes()
    if not name.startswith(prefix) or name[len(prefix):].split('$')[0].split('.')[0] not in allowed:
        raise SystemExit('Unexpected compiled class: '+name)
    if struct.unpack('>H',data[6:8])[0]!=48 or b'java/lang/StringBuilder' in data:
        raise SystemExit('Not compatible with Java 1.4: '+name)
    replacements[name]=data
buf=io.BytesIO()
with zipfile.ZipFile(jar) as a, zipfile.ZipFile(buf,'w',zipfile.ZIP_DEFLATED) as b:
    for i in a.infolist(): b.writestr(i,replacements.pop(i.filename,a.read(i)))
    for n,data in sorted(replacements.items()):
        info=zipfile.ZipInfo(n,(2026,11,11,12,0,0)); info.compress_type=zipfile.ZIP_DEFLATED
        b.writestr(info,data)
with zipfile.ZipFile(jar) as a, zipfile.ZipFile(io.BytesIO(buf.getvalue())) as b:
    for n in a.namelist():
        if not n.startswith(prefix) or n[len(prefix):].split('$')[0].split('.')[0] not in allowed:
            assert a.read(n)==b.read(n),n
    # Includes the exact previously instrumented release integration, not recompiled.
    assert a.read(prefix+'AndroidAutoClusterIntegration.class')==b.read(prefix+'AndroidAutoClusterIntegration.class')
with zipfile.ZipFile(base) as a, zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as b:
    for i in a.infolist():
        info=copy.copy(i); data=a.read(i)
        if '/aa_only/' in i.filename and i.filename.endswith('_aa.jar'):
            data=buf.getvalue(); info.date_time=(2026,11,11,12,0,0)
        b.writestr(info,data)
with zipfile.ZipFile(base) as a, zipfile.ZipFile(out) as b:
    assert a.namelist()==b.namelist() and b.testzip() is None
    changed=[n for n in a.namelist() if a.read(n)!=b.read(n)]
    assert len(changed)==1 and '/aa_only/' in changed[0],changed
digest=hashlib.sha256(out.read_bytes()).hexdigest()
pathlib.Path(str(out)+'.sha256').write_text(digest+'  '+out.name+'\n')
print('Built:',out)
print('Only changed package payload:',changed[0])
print('Player62, centered hook, installer and ALL rollback payloads identical to candidate3.')
print('SHA256:',digest)
PY
