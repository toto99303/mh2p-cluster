#!/usr/bin/env python3
"""Build the experimental P2711 AA wheel add-on over the frozen stable5 ZIP.

The two OEM inputs are hash-checked locally and never copied into the output.
Requires Java17+, ECJ3.20, ASM9, Zig0.13.0 and GNU readelf on the build host.
"""
import argparse
import copy
import hashlib
import io
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile

from verify_readiness_class import methods

ROOT = Path(__file__).resolve().parents[1]
UPDATE = "Mods/ClusterIntegration/Update/"
JAR = UPDATE + "aa_only/ClusterIntegration_v0034_beta2_candidate_90d0b76_aa.jar"
HELPER = UPDATE + "mh2p_wheel.so"
BASE_HASH = "ee5e824e8401366ad138e6b3436003fc2f3fa8aee993fdef6964263487a61da3"
OEM = {
    "gal": "c323411d51359179fc73bf13c069d1541d0e02addf4b63aadf25d77dd048f4a7",
    "autoreceiver": "2916bb7c65ee20c04995a42f22504b61e69eae167aa362fc3f05a7a57740a48d",
}
BUILD_TOOLS = {
    "ecj": "3cb2a284d424a5d6254432cbf7ab724f6f83f6dbd9636b645e3409a5d2dfb0cf",
    "asm": "8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281",
}
DATE = (2026, 11, 11, 23, 0, 0)
PREFIX = "de/audi/app/terminalmode/smartphone/androidauto2/"
WHEEL = ROOT / "audi/a6-c8-p2711/wheel-zoom"
EXPORTS = {
    "_ZN13MessageRouter4initEP14ChannelManager",
    "_ZN13MessageRouter12setupMappingEhh",
    "_ZN13MessageRouter8shutdownEv",
    "_ZN13MessageRouter23handleChannelCloseNotifEhRK24ChannelCloseNotification",
    "_ZN13MessageRouter12closeChannelEh",
    "_ZN13MessageRouter12routeMessageEhRK10shared_ptrI8IoBufferE",
}
IMPORTS = set("dlsym ClockTime getenv setenv unsetenv memcmp memset socket bind getpid "
              "pthread_create close pthread_self pthread_detach fopen fseek ftell fclose "
              "fprintf poll recvfrom memcpy sendto".split())

INSTALL = '''# MH2P_WHEEL_INSTALL_BEGIN: install before changing any Java payload.
install_file "$MOD_PATH/mh2p_wheel.so" "$CLUSTER_DIR/mh2p_wheel.so" || {
    print -u2 "ERROR: wheel helper installation failed; Java update aborted"
    exit 3
}
chmod 755 "$CLUSTER_DIR/mh2p_wheel.so" || exit 3
# Record our owned add-on even on a replacement/reused card.
note "add $CLUSTER_DIR/mh2p_wheel.so"
# MH2P_WHEEL_INSTALL_END

'''
LAUNCH = '''# The decoder above keeps the unchanged baseline preload. Only gal.real
# loads the secondary-input companion. Its constructor restores the baseline
# preload environment for any children subsequently launched by GAL.
if [ -f /mnt/app/eso/bin/apps/cluster/mh2p_wheel.so ]; then
    MH2P_WHEEL_CHILD_PRELOAD="$LD_PRELOAD" \\
    LD_PRELOAD="/mnt/app/eso/bin/apps/cluster/mh2p_wheel.so:$LD_PRELOAD" \\
        /mnt/app/eso/bin/apps/gal.real "$@"
else
    /mnt/app/eso/bin/apps/gal.real "$@"
fi'''


def run(args, **kwargs):
    subprocess.run([str(a) for a in args], check=True, **kwargs)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verify_native(path):
    data = path.read_bytes()
    if data[:8] != b"\x7fELF\x01\x01\x01\x00":
        raise ValueError("Expected ELF32 little-endian SYSV ABI")
    if struct.unpack_from("<HH", data, 16) != (3, 40):
        raise ValueError("Expected ARM shared library")
    flags = struct.unpack_from("<I", data, 36)[0]
    if flags & 0xff000600 != 0x05000200:
        raise ValueError("Expected EABI5 soft-float ABI")
    dynamic = subprocess.check_output(["readelf", "-Wd", str(path)], text=True)
    if "(NEEDED)" in dynamic or "(HASH)" not in dynamic:
        raise ValueError("Unexpected runtime dependency or missing SYSV hash")
    symbols = subprocess.check_output(["readelf", "-W", "--dyn-syms", str(path)], text=True)
    imports, exports = set(), set()
    for line in symbols.splitlines():
        parts = line.split()
        if len(parts) == 8 and parts[4] in ("GLOBAL", "WEAK"):
            (imports if parts[6] == "UND" else exports).add(parts[7])
    if imports != IMPORTS or exports != EXPORTS:
        raise ValueError("Unexpected native ABI: " + repr((imports-IMPORTS, exports-EXPORTS)))
    programs = subprocess.check_output(["readelf", "-Wl", str(path)], text=True)
    relocations = subprocess.check_output(["readelf", "-Wr", str(path)], text=True)
    if re.search(r"\b(INTERP|TLS)\b", programs) or "GLIBC" in symbols:
        raise ValueError("Unexpected loader/TLS/libc dependency")
    kinds = set(re.findall(r"R_ARM_[A-Z0-9_]+", relocations))
    if kinds - {"R_ARM_RELATIVE", "R_ARM_JUMP_SLOT", "R_ARM_GLOB_DAT"}:
        raise ValueError("Unsupported ARM relocation: " + repr(kinds))
    if len(data) > 65536:
        raise ValueError("Unexpected compiler runtime or debug payload")


def instrument_jar(base, temporary, ecj, asm):
    classes, host = temporary / "classes", temporary / "host"
    classes.mkdir(); host.mkdir()
    sources = sorted((WHEEL / "src").rglob("*.java"))
    run(["java", "-jar", ecj, "-1.4", "-source", "1.4", "-target", "1.4",
         "-nowarn", "-d", classes, *sources])
    run(["javac", "-cp", asm, "-d", host, ROOT / "modkit/java/AddWheelBootstrap.java"])
    transforms = {PREFIX + "AndroidAuto2Subsystem.class": "subsystem",
                  PREFIX + "nav/AndroidAutoClusterIntegration.class": "integration"}
    changed = {}
    with zipfile.ZipFile(io.BytesIO(base)) as jar:
        if any(n.startswith(PREFIX + "nav/zoom/") or "AutoRoute62" in n for n in jar.namelist()):
            raise ValueError("Unexpected baseline JAR")
        for name, mode in transforms.items():
            original, output = temporary / (mode + ".class"), temporary / (mode + "-out.class")
            before = jar.read(name); original.write_bytes(before)
            run(["java", "-cp", str(host) + os.pathsep + str(asm), "AddWheelBootstrap",
                 mode, original, output])
            after = output.read_bytes()
            old, new = methods(before), methods(after)
            expected = len(old) + (1 if mode == "integration" else 0)
            if len(new) != expected:
                raise ValueError("Unexpected transformed method count")
            for (method, desc), body in old.items():
                if mode == "subsystem" and method == "<init>":
                    continue
                key = ("sendMirrorCommandWithoutWheel" if mode == "integration"
                       and method == "sendMirrorCommandUngated" else method, desc)
                if new[key] != body:
                    raise ValueError("Existing method changed: " + method)
            changed[name] = after
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w") as result:
            for item in jar.infolist():
                result.writestr(item, changed.get(item.filename, jar.read(item)))
            for path in sorted(classes.rglob("*.class")):
                name, data = path.relative_to(classes).as_posix(), path.read_bytes()
                if not name.startswith(PREFIX + "nav/zoom/"):
                    raise ValueError("Non-wheel class compiled")
                if struct.unpack_from(">H", data, 6)[0] != 48 or b"java/lang/StringBuilder" in data:
                    raise ValueError("Java1.4 compatibility failure")
                info = zipfile.ZipInfo(name, DATE); info.compress_type = zipfile.ZIP_DEFLATED
                result.writestr(info, data)
    return out.getvalue()


def build(args):
    if args.output.exists() or args.output.resolve() == args.base.resolve():
        raise ValueError("Use a new output filename; keep the stable ZIP untouched")
    if sha(args.base.read_bytes()) != BASE_HASH:
        raise ValueError("Expected the exact stable candidate5 ZIP")
    for name in OEM:
        if sha(getattr(args, name).read_bytes()) != OEM[name]:
            raise ValueError("Unsupported P2711 OEM profile: " + name)
    for name in BUILD_TOOLS:
        if sha(getattr(args, name).read_bytes()) != BUILD_TOOLS[name]:
            raise ValueError("Expected pinned build tool: " + name)
    version = subprocess.check_output([str(args.zig), "version"], text=True).strip()
    if version != "0.13.0":
        raise ValueError("Reproducible native build requires Zig0.13.0")
    with tempfile.TemporaryDirectory(prefix="mh2p-wheel-build-") as tmp:
        temporary = Path(tmp)
        helper = temporary / "mh2p_wheel.so"
        run([args.zig, "build-lib", "-dynamic", "-target", "arm-linux-musleabi", "-mcpu=cortex_a9",
             "-O", "ReleaseSmall", "-fPIC", "-fno-compiler-rt", "-fno-stack-protector", "-fstrip",
             "-fallow-shlib-undefined", "--version-script", WHEEL / "native/exports.map",
             "-fsoname=mh2p_wheel.so", "-cflags", "-std=c11", "-mfloat-abi=softfp", "-ffreestanding",
             "-fno-unwind-tables", "-fno-asynchronous-unwind-tables", "-fvisibility=hidden",
             "-Wall", "-Wextra", "-Werror", "--", WHEEL / "native/wheel_companion.c",
             WHEEL / "native/aa_wheel_state.c", "-femit-bin=" + str(helper)])
        verify_native(helper)
        with zipfile.ZipFile(args.base) as base:
            install = base.read(UPDATE + "install.sh").decode()
            marker = "# Java classes: on Audi without enable_carplay.txt"
            if install.count(marker) != 1:
                raise ValueError("Unexpected baseline installer")
            install = install.replace(marker, INSTALL + marker)
            wrapper = base.read(UPDATE + "gal").decode()
            launch = '/mnt/app/eso/bin/apps/gal.real "$@"'
            if wrapper.count(launch) != 1:
                raise ValueError("Unexpected baseline GAL wrapper")
            wrapper = wrapper.replace(launch, LAUNCH)
            changes = {
                JAR: instrument_jar(base.read(JAR), temporary, args.ecj, args.asm),
                UPDATE + "install.sh": install.encode(), UPDATE + "gal": wrapper.encode(),
                "README.md": (WHEEL / "CANDIDATE6.md").read_bytes(),
            }
            for name in (UPDATE + "install.sh", UPDATE + "gal"):
                script = temporary / Path(name).name
                script.write_bytes(changes[name]); run(["mksh", "-n", script])
            with zipfile.ZipFile(args.output, "x") as result:
                result.comment = base.comment
                for item in base.infolist():
                    info = copy.copy(item)
                    if item.filename in changes:
                        info.date_time = DATE
                    result.writestr(info, changes.get(item.filename, base.read(item)))
                info = zipfile.ZipInfo(HELPER, DATE)
                info.compress_type = zipfile.ZIP_DEFLATED; info.external_attr = 0o100755 << 16
                result.writestr(info, helper.read_bytes())
    with zipfile.ZipFile(args.base) as a, zipfile.ZipFile(args.output) as b:
        if b.testzip() or set(b.namelist()) - set(a.namelist()) != {HELPER}:
            raise ValueError("Package member/CRC failure")
        if {n for n in a.namelist() if a.read(n) != b.read(n)} != set(changes):
            raise ValueError("Unexpected package change")
    digest = sha(args.output.read_bytes())
    Path(str(args.output) + ".sha256").write_text(digest + "  " + args.output.name + "\n")
    print("Built experimental wheel package:", args.output)
    print("Stable source33 player, gal_cluster.so, framing and rollback scripts preserved.")
    print("SHA256:", digest)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base", type=Path); parser.add_argument("output", type=Path)
    for name in ("gal", "autoreceiver", "ecj", "asm", "zig"):
        parser.add_argument("--" + name, type=Path, required=True)
    build(parser.parse_args())
