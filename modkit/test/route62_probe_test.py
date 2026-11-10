#!/usr/bin/env python3
"""Probe integrity and cleanup tests; run no stock HMI/QNX executable."""
import hashlib
import importlib.util
import os
from pathlib import Path
import signal
import struct
import subprocess
import tempfile
import time
import zipfile

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO/'modkit/diagnostics/test_mh2p_route62.sh'
spec = importlib.util.spec_from_file_location('builder', REPO/'modkit/build_route62_probe.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


def check(condition, message):
    if not condition:
        raise AssertionError(message)
    print('PASS', message)


with tempfile.TemporaryDirectory(prefix='mh2p-route62-test-') as name:
    root = Path(name)
    base = REPO/'builds/ClusterIntegration_q7_p2711_candidate2_centered.zip'
    output = root/'probe.zip'
    builder.build(base, output)
    with zipfile.ZipFile(base) as a, zipfile.ZipFile(output) as b:
        check(a.namelist() == b.namelist(), 'archive inventory preserved')
        check([n for n in a.namelist() if a.read(n) != b.read(n)] == [builder.PLAYER],
              'JAR, centered margins, installer, rollback and all other payloads are byte-identical')
        old, new = a.read(builder.PLAYER), b.read(builder.PLAYER)
        check(len(old) == len(new) and [i for i in range(len(old)) if old[i] != new[i]] == [0x14c10, 0x14c11],
              'only the two window-ID data bytes differ')
        shoff = struct.unpack_from('<I', old, 32)[0]
        step, count = struct.unpack_from('<HH', old, 46)
        for i in range(count):
            _, _, flags, _, offset, size = struct.unpack_from('<6I', old, shoff+i*step)
            if flags & 4:
                check(old[offset:offset+size] == new[offset:offset+size],
                      'executable ELF section %d unchanged' % i)
        check(all(a.getinfo(n).date_time != b.getinfo(n).date_time
                  and b.getinfo(n).date_time[-1] % 2 == 0 for n in (builder.PLAYER, builder.HOOK)),
              'player and hook have distinct FAT-safe mtimes for wc-only installer comparisons')
        for n in a.namelist():
            if n not in (builder.PLAYER, builder.HOOK):
                assert a.getinfo(n).date_time == b.getinfo(n).date_time
    try:
        builder.build(base, output)
        raise AssertionError('existing output overwritten')
    except ValueError:
        pass
    wrong = root/'wrong.zip'
    wrong.write_bytes(base.read_bytes()+b'changed')
    try:
        builder.build(wrong, root/'refused.zip')
        raise AssertionError('wrong baseline accepted')
    except ValueError:
        pass
    check(not (root/'refused.zip').exists(), 'wrong input and existing output are refused without overwrites')

    screen, commands = root/'screen', root/'commands'
    for p in (screen/'navStartup', screen/'cluster', commands):
        p.mkdir(parents=True)
    native = ('win-16/ctx-8/123(navStartup)\nid string = 33\n'
              'parent = win-0/ctx-1/456(displaymanager)\nbuffer size = 1920x600\nstreams = str-17\n')
    aa = ('win-20/ctx-12/789(cluster)\nid string = 62\n'
          'parent = win-0/ctx-1/456(displaymanager)\nbuffer size = 1920x722\n'
          'streams = str-26\nflags = WIN_FLAG_VISIBLE WIN_FLAG_FLOATING\n')
    (screen/'navStartup/33').write_text(native)
    (screen/'cluster/62').write_text(aa)
    log, lock = root/'commands.log', root/'lock'
    dmdt = commands/'dmdt'
    dmdt.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$PROBE_TEST_LOG"\n'
                    'case "$1" in sd) exit "${PROBE_TEST_SD_STATUS:-0}" ;; sb) exit "${PROBE_TEST_SB_STATUS:-0}" ;; esac\nexit 9\n')
    dmdt.chmod(0o755)
    sleeper = commands/'sleep'
    sleeper.write_text('#!/bin/sh\nexit 0\n')
    sleeper.chmod(0o755)
    env = dict(os.environ, MH2P_SCREEN_ROOT=str(screen), MH2P_DMDT=str(dmdt),
               MH2P_ROUTE62_LOCK=str(lock), PROBE_TEST_LOG=str(log),
               PATH=str(commands)+':'+os.environ['PATH'])

    def run(extra=None):
        log.write_text('')
        return subprocess.run(['bash', str(SCRIPT)], env=dict(env, **(extra or {})),
                              capture_output=True, text=True, timeout=5)

    r = run()
    check(r.returncode == 0 and log.read_text() == 'sd 4 41 62\nsb 4\n' and not lock.exists(),
          'one bounded AA selection followed by buffered-layout release and lock cleanup')
    check((screen/'navStartup/33').read_text() == native and (screen/'cluster/62').read_text() == aa,
          'stock property nodes remain untouched')
    for label, path, replacement in (
        ('detached native map', screen/'navStartup/33', native.replace('parent = win-0/ctx-1/456(displaymanager)', 'parent = (none)')),
        ('foreign ID62 owner', screen/'cluster/62', aa.replace('(cluster)', '(another-app)')),
        ('unadmitted AA source', screen/'cluster/62', aa.replace('WIN_FLAG_VISIBLE ', '')),
        ('unexpected AA geometry', screen/'cluster/62', aa.replace('1920x722', '800x480')),
    ):
        old = path.read_text(); path.write_text(replacement)
        r = run()
        check(r.returncode == 2 and log.read_text() == '' and not lock.exists(), label+' refuses every display command')
        path.write_text(old)
    (screen/'cluster/62').unlink()
    r = run()
    check(r.returncode == 2 and not log.read_text(), 'missing source62 refuses the probe')
    (screen/'cluster/62').write_text(aa)
    lock.write_text('another probe\n')
    r = run()
    check(r.returncode == 2 and not log.read_text() and lock.read_text() == 'another probe\n',
          'existing lock is preserved and prevents concurrent selection')
    lock.unlink()
    r = run({'PROBE_TEST_SD_STATUS': '1'})
    check(r.returncode == 1 and log.read_text() == 'sd 4 41 62\nsb 4\n' and not lock.exists(),
          'failed/possibly partial selection still releases the override')
    r = run({'PROBE_TEST_SB_STATUS': '1'})
    check(r.returncode == 1 and log.read_text() == 'sd 4 41 62\nsb 4\n' and 'Restore request failed' in r.stderr,
          'failed cleanup is surfaced as failure without claiming successful restoration')
    # Deliver TERM to both the foreground shell and its sleep child, as an SSH
    # session/process-group interruption can do. No live MMI process is involved.
    sleeper.write_text('#!/bin/sh\nexec /bin/sleep 30\n')
    log.write_text('')
    p = subprocess.Popen(['bash', str(SCRIPT)], env=env, stdout=subprocess.PIPE,
                         stderr=subprocess.PIPE, text=True, start_new_session=True)
    deadline = time.monotonic()+3
    while time.monotonic() < deadline and 'sd 4 41 62' not in log.read_text():
        time.sleep(.01)
    assert p.poll() is None and lock.exists()
    os.killpg(p.pid, signal.SIGTERM)
    out, err = p.communicate(timeout=5)
    check(p.returncode == 143 and log.read_text() == 'sd 4 41 62\nsb 4\n' and not lock.exists(),
          'TERM interruption releases override and removes only the probe lock')

print('Host fixtures only; real QNX/player/geometry/pixels remain unverified.')
