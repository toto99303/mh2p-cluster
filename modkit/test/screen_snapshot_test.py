#!/usr/bin/env python3
"""Host fixtures for the manual read-only QNX single-file snapshot script."""
import os
from pathlib import Path
import subprocess
import tempfile

SCRIPT = Path(__file__).parents[1] / 'diagnostics/capture_mh2p_screen.sh'


def digest_tree(root):
    return {str(p.relative_to(root)): p.read_bytes() for p in root.rglob('*') if p.is_file()}


with tempfile.TemporaryDirectory(prefix='mh2p-screen-test-') as name:
    root = Path(name)
    screen, commands = root/'screen', root/'commands'
    for p in (screen, commands): p.mkdir()
    (screen/'0/win-0').mkdir(parents=True)
    (screen/'0/win-0/win-0').write_text('framebuffer window\n')
    (screen/'0/dpy-2').write_text('display=2\n')
    (screen/'123').mkdir()
    (screen/'123/win-4').write_text('id_string=33\nvisible=1\n')
    (screen/'123/ctx-0').write_bytes(b'CONTROL_CHANNEL_DO_NOT_READ')
    (screen/'123/private.bmp').write_bytes(b'PIXELS_DO_NOT_READ')
    (screen/'not-a-pid').mkdir()
    (screen/'not-a-pid/win-2').write_text('NON_PID_DO_NOT_READ')
    (screen/'requests').write_bytes(b'BINARY_REQUESTS_DO_NOT_READ')
    (screen/'123/win-big').write_bytes(b'x'*100000)
    os.mkfifo(screen/'123/win-fifo')
    # Model QNX's key distinction: property nodes exist but are not regular
    # files. Reject every -f predicate in the shell running the collector.
    named_node_env = root/'qnx-named-node-test.sh'
    named_node_env.write_text('function [ {\n    case "${1-}" in -f) return 1 ;; esac\n    builtin [ "$@"\n}\n')
    (commands/'pidin').write_text('#!/bin/sh\necho "123 navStartup"\n')
    (commands/'pidin').chmod(0o755)
    # Reproduce the device constraint: every mkdir invocation would fail.
    called = root/'mkdir-called'
    (commands/'mkdir').write_text('#!/bin/sh\necho called > "'+str(called)+'"\necho "mkdir: Function not implemented" >&2\nexit 1\n')
    (commands/'mkdir').chmod(0o755)
    env=dict(os.environ, MH2P_SCREEN_ROOT=str(screen),
             PATH=str(commands)+':'+os.environ['PATH'], BASH_ENV=str(named_node_env))
    before = digest_tree(screen)
    out = root/'snapshot.txt'
    result = subprocess.run(['bash',str(SCRIPT),str(out)],env=env,capture_output=True,text=True)
    assert result.returncode == 0, result.stderr
    report = out.read_text()
    assert report.startswith('MH2P_SCREEN_SNAPSHOT_V3_NAMED_NODES\n')
    assert 'framebuffer window' in report and 'id_string=33\nvisible=1' in report
    assert 'display=2' in report and '123 navStartup' in report
    assert 'x'*16384 in report and 'x'*16385 not in report
    assert all(marker not in report for marker in ('CONTROL_CHANNEL_DO_NOT_READ','PIXELS_DO_NOT_READ','NON_PID_DO_NOT_READ','BINARY_REQUESTS_DO_NOT_READ'))
    assert digest_tree(screen)==before and not called.exists()
    assert out.is_file() and len(list(root.glob('snapshot*')))==1
    assert 'PROPERTY: '+str(screen/'123/win-fifo') not in report
    assert 'text window/display snapshots: 4' in report
    print('PASS named-node simulation (-f always false), no mkdir, nested/flat properties, FIFO exclusion, bounds and source preservation')
    previous = out.read_bytes()
    result = subprocess.run(['bash',str(SCRIPT),str(out)],env=env,capture_output=True,text=True)
    assert result.returncode==2 and out.read_bytes()==previous
    print('PASS existing file is not overwritten')
    result = subprocess.run(['bash',str(SCRIPT),str(screen)],env=env,capture_output=True,text=True)
    assert result.returncode==2 and digest_tree(screen)==before
    print('PASS existing directory is refused')
    missing_env=dict(env,MH2P_SCREEN_ROOT=str(root/'missing'))
    result = subprocess.run(['bash',str(SCRIPT),str(root/'missing-screen.txt')],env=missing_env,capture_output=True,text=True)
    assert result.returncode==0 and 'unavailable' in (root/'missing-screen.txt').read_text()
    print('PASS missing debug interface is recorded in the same file')
    result = subprocess.run(['bash',str(SCRIPT),'relative'],env=env,capture_output=True,text=True)
    assert result.returncode==2
    result = subprocess.run(['bash',str(SCRIPT),str(root/'no-parent/snapshot.txt')],env=env,capture_output=True,text=True)
    assert result.returncode==2 and not (root/'no-parent').exists() and not called.exists()
    print('PASS relative filename refused; missing parent produces an error without mkdir')
    many=root/'many/1'
    many.mkdir(parents=True)
    for i in range(140): (many/('win-'+str(i))).write_text('small property\n')
    result = subprocess.run(['bash',str(SCRIPT),str(root/'bounded.txt')],env=dict(env,MH2P_SCREEN_ROOT=str(root/'many')),capture_output=True,text=True)
    assert result.returncode==0
    report=(root/'bounded.txt').read_text()
    assert report.count('=== PROPERTY:')==128 and 'snapshot is incomplete' in report
    print('PASS property count is bounded and partial capture is disclosed')
print('Host bash fixtures only; QNX ksh/device execution remains unverified.')
