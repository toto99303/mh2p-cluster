#!/usr/bin/env python3
"""Host fixtures for the manual read-only QNX snapshot script."""
import os
from pathlib import Path
import subprocess
import tempfile

SCRIPT = Path(__file__).parents[1] / 'diagnostics/capture_mh2p_screen.sh'


def digest_tree(root):
    return {str(p.relative_to(root)): p.read_bytes() for p in root.rglob('*') if p.is_file()}


with tempfile.TemporaryDirectory(prefix='mh2p-screen-test-') as name:
    root = Path(name)
    screen, logs, commands = root/'screen', root/'logs', root/'commands'
    for p in (screen, logs, commands): p.mkdir()
    # A 6.6-style nested window file and a newer flat window file.
    (screen/'0/win-0').mkdir(parents=True)
    (screen/'0/win-0/win-0').write_text('framebuffer window\n')
    (screen/'0/dpy-2').write_text('display=2\n')
    (screen/'123').mkdir()
    (screen/'123/win-4').write_text('id_string=33\nvisible=1\n')
    (screen/'123/ctx-0').write_bytes(b'not a property snapshot')
    (screen/'123/private.bmp').write_bytes(b'pixels must not be collected')
    (screen/'not-a-pid').mkdir()
    (screen/'not-a-pid/win-2').write_text('ignore this directory')
    (screen/'requests').write_bytes(b'binary control data')
    (screen/'123/win-big').write_bytes(b'x'*100000)
    (logs/'cluster.log').write_text('gate READY\n')
    (logs/'wifi-secret').write_text('do not copy unrelated files')
    (commands/'pidin').write_text('#!/bin/sh\necho "123 navStartup"\n')
    (commands/'pidin').chmod(0o755)
    env=dict(os.environ, MH2P_SCREEN_ROOT=str(screen), MH2P_LOG_ROOT=str(logs),
             PATH=str(commands)+':'+os.environ['PATH'])
    before = digest_tree(screen), digest_tree(logs)
    out = root/'snapshot'
    result = subprocess.run(['bash',str(SCRIPT),str(out)],env=env,capture_output=True,text=True)
    assert result.returncode == 0, result.stderr
    assert (out/'screen-0-win-0.txt').read_text()=='framebuffer window\n'
    assert (out/'screen-123-win-4.txt').read_text()=='id_string=33\nvisible=1\n'
    assert (out/'screen-0-dpy-2.txt').read_text()=='display=2\n'
    assert (out/'screen-123-win-big.txt').stat().st_size==65536
    assert (out/'cluster.log').read_text()=='gate READY\n'
    assert (out/'processes.txt').read_text()=='123 navStartup\n'
    names={p.name for p in out.iterdir()}
    assert not any(any(s in n for s in ('bmp','ctx-','wifi','not-a-pid','requests')) for n in names)
    assert (digest_tree(screen),digest_tree(logs))==before
    print('PASS nested/flat windows, displays, bounded reads, process/log capture, filtering, source preservation')
    previous = digest_tree(out)
    result = subprocess.run(['bash',str(SCRIPT),str(out)],env=env,capture_output=True,text=True)
    assert result.returncode==2 and digest_tree(out)==previous
    print('PASS existing snapshot is not overwritten')
    missing_env=dict(env,MH2P_SCREEN_ROOT=str(root/'missing'))
    result = subprocess.run(['bash',str(SCRIPT),str(root/'missing-output')],env=missing_env,capture_output=True,text=True)
    assert result.returncode==0 and 'unavailable' in (root/'missing-output/summary.txt').read_text()
    print('PASS unavailable debug interface is reported without a false diagnosis')
    result = subprocess.run(['bash',str(SCRIPT),'relative'],env=env,capture_output=True,text=True)
    assert result.returncode==2
    print('PASS relative output path refused')
print('Host bash fixtures only; QNX ksh and device debug-interface availability remain unverified.')
