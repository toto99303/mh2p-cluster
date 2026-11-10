#!/usr/bin/env python3
"""Center the exact candidate 2 package; change only its fixed AA template data.

Usage: python3 modkit/build_centered_package.py candidate2.zip centered.zip
The HMI JAR/hand-back code is unchanged. No stock firmware inputs are needed.
"""
import hashlib
import importlib.util
from pathlib import Path
import sys
import zipfile

BASE_SHA = 'f81d13d7f011c83736c00eb863291adf4bc029e9d9189e8c6bb5ace0963c63d0'
REQUESTED = dict(height_margin=358, margin_top=179, margin_bottom=180,
                 inset_bottom=195, inset_left=160)
HOOK = 'Mods/ClusterIntegration/Update/gal_cluster.so'


def build(src, dst):
    if src.resolve() == dst.resolve():
        raise ValueError('Use a separate output ZIP; input must remain untouched')
    if hashlib.sha256(src.read_bytes()).hexdigest() != BASE_SHA:
        raise ValueError('Expected the exact published candidate 2 ZIP')
    spec = importlib.util.spec_from_file_location('template', Path(__file__).parent / 'template/cluster_template_tool.py')
    tool = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tool)
    # Preserve unspecified values from the verified candidate 2 template.
    settings = dict(tool.DEFAULTS, density=215, real_density=215, inset_left=450)
    original_entry, _, _ = tool.build_fitted(settings)
    settings.update(REQUESTED)
    centered_entry, _, _ = tool.build_fitted(settings)
    with zipfile.ZipFile(src) as zin:
        original = zin.read(HOOK)
        off, size = tool.elf_symbol(original, tool.SYMBOL)
        if original[off:off+size] != original_entry:
            raise ValueError('Unexpected original hook template')
        if size != 54 or len(centered_entry) != size:
            raise ValueError('Template length changed')
        if centered_entry[tool.CODEC_BYTE] != original_entry[tool.CODEC_BYTE]:
            raise ValueError('Codec placeholder moved or changed')
        patched = original[:off] + centered_entry + original[off+size:]
        changes = [i for i, (a, b) in enumerate(zip(original, patched)) if a != b]
        if len(changes) != 2 or any(not off <= i < off+size for i in changes):
            raise ValueError('Expected only the two inset-left varint bytes to change')
        with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                zout.writestr(item, patched if item.filename == HOOK else zin.read(item))
    with zipfile.ZipFile(src) as a, zipfile.ZipFile(dst) as b:
        if a.namelist() != b.namelist() or b.testzip() is not None:
            raise ValueError('Package members or CRC check failed')
        if [n for n in a.namelist() if a.read(n) != b.read(n)] != [HOOK]:
            raise ValueError('Unexpected changed package entry')
    print('Built:', dst)
    print('Only gal_cluster.so template data changed; offsets:', ', '.join(hex(i) for i in changes))
    print('AA JAR, native instructions, decoder, installer and rollback files are unchanged from candidate 2.')
    tool.describe(centered_entry)
    print('SHA256:', hashlib.sha256(dst.read_bytes()).hexdigest())


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    build(Path(sys.argv[1]), Path(sys.argv[2]))
