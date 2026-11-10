#!/usr/bin/env python3
"""Static/host preflight checks. Never execute the uploaded QNX binary."""
import hashlib
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

SOURCE_SHA = '300aa12be6e94e6017f4cbdd5b5b96996f166875c7cbd9ec008a3089adf0e0f2'
SCRIPT = Path(__file__).parents[1]/'diagnostics/test_dmdt_query_capture.sh'
if len(sys.argv) != 2:
    raise SystemExit('usage: dmdt_query_patch_test.py /PRIVATE/path/to/stock-dmdt')
source = Path(sys.argv[1]).read_bytes()
assert hashlib.sha256(source).hexdigest() == SOURCE_SHA
assert source[6895:6905] == b'nanosleep\0' and source[11788:11790] == b'\x08\x92'
with tempfile.TemporaryDirectory(prefix='mh2p-query-patch-') as d:
    root = Path(d)
    original = root/'dmdt'
    original.write_bytes(source)
    commands = root/'commands'; commands.mkdir()
    # The fake on command INSPECTS the copied ELF; it never executes it.
    on = commands/'on'
    on.write_text('''#!/usr/bin/env python3
import os,sys,pathlib
assert sys.argv[1:3]==['-u','java']
data=pathlib.Path(sys.argv[3]).read_bytes()
assert data[6895:6905]==b'fflush\\0\\0\\0\\0'
assert data[11788:11790]==b'\\0\\x20'
pathlib.Path(os.environ['QUERY_TEST_COPY']).write_bytes(data)
if os.environ.get('QUERY_TEST_FAIL'): sys.exit(1)
print('displaymanager reports query data' if sys.argv[4]=='gs' else 'displaymanager knows 11 displayables:')
''')
    on.chmod(0o755)
    report = root/'report'
    saved = root/'saved-copy'
    prefix = root/'temporary'
    env = dict(os.environ, MH2P_DMDT_SOURCE=str(original), MH2P_QUERY_PREFIX=str(prefix),
               QUERY_TEST_COPY=str(saved), PATH=str(commands)+':'+os.environ['PATH'])
    for shell in ('bash','dash'):
        if report.exists(): report.unlink()
        result = subprocess.run([shell,str(SCRIPT),str(report)],env=env,capture_output=True,text=True)
        assert result.returncode==0,(shell,result.stdout,result.stderr)
        patched=saved.read_bytes()
        assert len(patched)==len(source)
        changed=[i for i,(a,b) in enumerate(zip(source,patched)) if a!=b]
        assert changed and all(6895<=i<6905 or 11788<=i<11790 for i in changed)
        assert original.read_bytes()==source
        assert not Path(str(prefix)+'.bin').exists() and not Path(str(prefix)+'.out').exists()
        assert 'gs exit=0' in report.read_text() and 'gd exit=0' in report.read_text()
        print('PASS',shell,'exact query-copy patches, original preservation, captured output and temporary cleanup')
    previous=report.read_bytes()
    r=subprocess.run(['bash',str(SCRIPT),str(report)],env=env,capture_output=True,text=True)
    assert r.returncode==2 and report.read_bytes()==previous
    print('PASS existing report refused')
    report.unlink()
    Path(str(prefix)+'.bin').write_text('existing private file')
    r=subprocess.run(['bash',str(SCRIPT),str(report)],env=env,capture_output=True,text=True)
    assert r.returncode==2 and Path(str(prefix)+'.bin').read_text()=='existing private file'
    Path(str(prefix)+'.bin').unlink(); report.unlink()
    Path(str(prefix)+'.out').write_text('existing private output')
    r=subprocess.run(['bash',str(SCRIPT),str(report)],env=env,capture_output=True,text=True)
    assert r.returncode==2 and Path(str(prefix)+'.out').read_text()=='existing private output'
    assert not Path(str(prefix)+'.bin').exists()
    Path(str(prefix)+'.out').unlink(); report.unlink()
    print('PASS existing temporary files are preserved')
    r=subprocess.run(['bash',str(SCRIPT),str(report)],env=dict(env,QUERY_TEST_FAIL='1'),capture_output=True,text=True)
    assert r.returncode==1 and original.read_bytes()==source
    assert not Path(str(prefix)+'.bin').exists()
    print('PASS failed query is recorded and temporary executable removed')
    report.unlink()
    broken=bytearray(source);broken[6895]=ord('x');original.write_bytes(broken)
    r=subprocess.run(['bash',str(SCRIPT),str(report)],env=env,capture_output=True,text=True)
    assert r.returncode==2 and not report.exists() and not Path(str(prefix)+'.bin').exists()
    print('PASS unexpected patch site refused before temporary copying/execution')
print('Host tests only; the owner separately confirmed query-copy gs/gd execution as java on QNX.')
