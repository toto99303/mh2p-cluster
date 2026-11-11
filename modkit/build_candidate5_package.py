#!/usr/bin/env python3
"""Build candidate5 from the exact published candidate1 ZIP, without compiling Java.

Usage: python3 modkit/build_candidate5_package.py candidate1.zip candidate5.zip
Only two bytes in the hook's display-template data change at runtime. Package
instructions and three payload timestamps are refreshed for downgrade detection.
"""
import copy
import hashlib
import importlib.util
from pathlib import Path
import sys
import zipfile


BASE_SHA256 = "99f236136a3615b2dbd819538eecd63e032bb34cbdac98554d95e63964714f22"
HOOK = "Mods/ClusterIntegration/Update/gal_cluster.so"
PLAYER = "Mods/ClusterIntegration/Update/cluster"
AA_JAR = "Mods/ClusterIntegration/Update/aa_only/ClusterIntegration_v0034_beta2_candidate_90d0b76_aa.jar"
PAYLOAD_DATE = (2026, 11, 11, 18, 0, 0)
REQUESTED = dict(height_margin=358, margin_top=179, margin_bottom=180,
                 inset_bottom=195, inset_left=160)


def build(src, dst):
    if src.resolve() == dst.resolve():
        raise ValueError("Use a separate output ZIP; candidate1 must remain untouched")
    if dst.exists():
        raise ValueError("Output already exists; choose a new output filename")
    if hashlib.sha256(src.read_bytes()).hexdigest() != BASE_SHA256:
        raise ValueError("Expected the exact published candidate1 ZIP")
    repo = Path(__file__).resolve().parent.parent
    instructions = (repo / "audi/a6-c8-p2711/CANDIDATE5.md").read_bytes()
    spec = importlib.util.spec_from_file_location(
        "template", repo / "modkit/template/cluster_template_tool.py")
    tool = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tool)
    settings = dict(tool.DEFAULTS, density=215, real_density=215, inset_left=450)
    before, _, _ = tool.build_fitted(settings)
    settings.update(REQUESTED)
    after, _, _ = tool.build_fitted(settings)

    with zipfile.ZipFile(src) as zin:
        original = zin.read(HOOK)
        offset, size = tool.elf_symbol(original, tool.SYMBOL)
        if size != 54 or original[offset:offset + size] != before:
            raise ValueError("Unexpected candidate1 template")
        if len(after) != size or after[tool.CODEC_BYTE] != before[tool.CODEC_BYTE]:
            raise ValueError("Template length or codec placeholder changed")
        patched = original[:offset] + after + original[offset + size:]
        changes = [i for i, (a, b) in enumerate(zip(original, patched)) if a != b]
        if changes != [0x73b1, 0x73b2]:
            raise ValueError("Expected only the two inset-left bytes to change")
        with zipfile.ZipFile(dst, "x") as zout:
            zout.comment = zin.comment
            for item in zin.infolist():
                info = copy.copy(item)
                if info.filename in (HOOK, PLAYER, AA_JAR, "README.md"):
                    info.date_time = PAYLOAD_DATE
                data = (patched if item.filename == HOOK else
                        instructions if item.filename == "README.md" else zin.read(item))
                zout.writestr(info, data)

    with zipfile.ZipFile(src) as base, zipfile.ZipFile(dst) as result:
        if base.namelist() != result.namelist() or result.testzip() is not None:
            raise ValueError("Package member list or CRC check failed")
        changed = [n for n in base.namelist() if base.read(n) != result.read(n)]
        if set(changed) != {HOOK, "README.md"}:
            raise ValueError("Unexpected changed package payload: " + repr(changed))

    digest = hashlib.sha256(dst.read_bytes()).hexdigest()
    Path(str(dst) + ".sha256").write_text(digest + "  " + dst.name + "\n")
    print("Built:", dst)
    print("Runtime changes: inset_left 450 -> 160 at offsets 0x73b1/0x73b2")
    print("Candidate1 JAR, source33 player, installer and rollback payloads preserved.")
    print("JAR/player/hook timestamps refreshed for QNX downgrade detection.")
    tool.describe(after)
    print("SHA256:", digest)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    build(Path(sys.argv[1]), Path(sys.argv[2]))
