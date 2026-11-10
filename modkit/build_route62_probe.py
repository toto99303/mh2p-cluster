#!/usr/bin/env python3
"""Build the manual P2711 ID-62 routing probe from exact centered candidate 2.

This does not implement automatic display selection. Read ROUTE62_PROBE.md.
Only two player data bytes change; installer/rollback and AA JAR are retained.
"""
import copy
import hashlib
from pathlib import Path
import struct
import sys
import zipfile

BASE_SHA = 'baf5a72c2f15eff8be71b9c79988e4bf497fc8293f31e07330ccd0fcdcacfff2'
PLAYER_SHA = '067059acbe3917eba5c718ed6d810d1c2cc6eebe61c607d1ad840ec37cb83477'
PLAYER = 'Mods/ClusterIntegration/Update/cluster'
HOOK = 'Mods/ClusterIntegration/Update/gal_cluster.so'
ID_OFFSET = 0x14c10
POINTER_OFFSETS = (0x2bd8, 0x5ad4, 0x7068, 0x7ad0, 0x82b8)
# The unchanged installer compares mtime when only wc fingerprints exist.
# Both data patches have unchanged sizes/counts: stamp a different FAT-safe
# mtime so neither the player nor centered hook can be skipped as identical.
PROBE_TIME = (2026, 11, 10, 12, 0, 0)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def patch_player(original):
    if sha(original) != PLAYER_SHA:
        raise ValueError('Expected the exact original Q7 v1.5 player')
    if original[ID_OFFSET:ID_OFFSET+3] != b'33\0':
        raise ValueError('Unexpected ID literal')
    for offset in POINTER_OFFSETS:
        if struct.unpack_from('<I', original, offset)[0] != 0x0805cc10:
            raise ValueError('Unexpected window-ID reference')
    patched = original[:ID_OFFSET] + b'62' + original[ID_OFFSET+2:]
    if [i for i, (a, b) in enumerate(zip(original, patched)) if a != b] != [ID_OFFSET, ID_OFFSET+1]:
        raise ValueError('Unexpected player differences')
    # Verify every executable section rather than assuming the literal is data.
    shoff = struct.unpack_from('<I', original, 32)[0]
    entsize, count = struct.unpack_from('<HH', original, 46)
    covered_by_rodata = False
    for index in range(count):
        _, _, flags, _, offset, size = struct.unpack_from('<6I', original, shoff+index*entsize)
        if flags & 4 and original[offset:offset+size] != patched[offset:offset+size]:
            raise ValueError('Executable instructions changed')
        if offset <= ID_OFFSET and ID_OFFSET+3 <= offset+size:
            covered_by_rodata = bool(flags & 2 and not flags & (1 | 4))
    if not covered_by_rodata:
        raise ValueError('ID literal is not in allocated read-only data')
    return patched


def build(src, dst):
    if src.resolve() == dst.resolve() or dst.exists():
        raise ValueError('Choose a new output filename; preserve existing archives')
    if sha(src.read_bytes()) != BASE_SHA:
        raise ValueError('Expected exact centered candidate 2 ZIP')
    with zipfile.ZipFile(src) as zin:
        patched = patch_player(zin.read(PLAYER))
        with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                info = copy.copy(item)
                if item.filename in (PLAYER, HOOK):
                    info.date_time = PROBE_TIME
                zout.writestr(info, patched if item.filename == PLAYER else zin.read(item))
    with zipfile.ZipFile(src) as a, zipfile.ZipFile(dst) as b:
        if a.namelist() != b.namelist() or b.testzip() is not None:
            raise ValueError('Package member or CRC failure')
        if [n for n in a.namelist() if a.read(n) != b.read(n)] != [PLAYER]:
            raise ValueError('Unexpected changed payload')
        for name in (PLAYER, HOOK):
            if a.getinfo(name).date_time == b.getinfo(name).date_time:
                raise ValueError('Missing mtime change for weak-comparison installers')
    digest = sha(dst.read_bytes())
    Path(str(dst)+'.sha256').write_text(digest+'  '+dst.name+'\n')
    print('Built MANUAL routing probe:', dst)
    print('Only player data bytes 0x14c10/0x14c11 change; executable sections unchanged.')
    print('Player/hook ZIP mtimes refreshed; AA JAR, hook bytes and rollback payload unchanged.')
    print('Automatic AA selection/hand-back is NOT implemented by this probe.')
    print('SHA256:', digest)


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit('usage: build_route62_probe.py centered-candidate2.zip NEW-probe.zip')
    build(Path(sys.argv[1]), Path(sys.argv[2]))
