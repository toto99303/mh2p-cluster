#!/usr/bin/env python3
"""Check candidate5's package and real archived gate without running OEM code."""
import contextlib
import datetime
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "builds/ClusterIntegration_q7_p2711_coldstart_candidate1.zip"
PACKAGE = ROOT / "builds/ClusterIntegration_a6_c8_p2711_readiness_centered_candidate5.zip"
FOUR = ROOT / "builds/ClusterIntegration_a6_c8_p2711_auto_route62_candidate4.zip"
SPEC = importlib.util.spec_from_file_location("candidate5", ROOT / "modkit/build_candidate5_package.py")
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


def fields(data):
    """Small independent protobuf decoder for this pinned display entry."""
    position = 0

    def integer():
        nonlocal position
        value = shift = 0
        while True:
            byte = data[position]
            position += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value
            shift += 7

    result = {}
    while position < len(data):
        key = integer()
        if key & 7 == 0:
            value = integer()
        elif key & 7 == 2:
            size = integer()
            value = data[position:position + size]
            position += size
        else:
            raise AssertionError("Unexpected protobuf wire type")
        result[key >> 3] = value
    return result


class PackageTest(unittest.TestCase):
    def test_only_template_and_instructions_change(self):
        self.assertEqual(hashlib.sha256(BASE.read_bytes()).hexdigest(), BUILDER.BASE_SHA256)
        with zipfile.ZipFile(BASE) as a, zipfile.ZipFile(PACKAGE) as b:
            self.assertIsNone(b.testzip())
            self.assertEqual(a.namelist(), b.namelist())
            self.assertEqual({n for n in a.namelist() if a.read(n) != b.read(n)},
                             {BUILDER.HOOK, "README.md"})
            before, after = a.read(BUILDER.HOOK), b.read(BUILDER.HOOK)
            self.assertEqual(len(before), len(after))
            self.assertEqual([i for i, (x, y) in enumerate(zip(before, after)) if x != y],
                             [0x73b1, 0x73b2])
            self.assertEqual(b.read("README.md"), (ROOT / "audi/a6-c8-p2711/CANDIDATE5.md").read_bytes())
            self.assertEqual(hashlib.sha256(b.read(BUILDER.AA_JAR)).hexdigest(),
                             "95cb9405de7f241183e28ce81e5ddb8f8614dd9d31d1d27026b270ef76b7310b")
            self.assertEqual(hashlib.sha256(b.read(BUILDER.PLAYER)).hexdigest(),
                             "067059acbe3917eba5c718ed6d810d1c2cc6eebe61c607d1ad840ec37cb83477")
            self.assertEqual(b.read(BUILDER.PLAYER)[0x14c10:0x14c12], b"33")
            checksum = Path(str(PACKAGE) + ".sha256").read_text().split()[0]
            self.assertEqual(hashlib.sha256(PACKAGE.read_bytes()).hexdigest(), checksum)
            # Check every executable ELF section, independently of the template tool.
            section_offset = struct.unpack_from("<I", before, 32)[0]
            stride, count = struct.unpack_from("<HH", before, 46)
            executable = 0
            for index in range(count):
                section = section_offset + index * stride
                flags, _, offset, size = struct.unpack_from("<IIII", before, section + 8)
                if flags & 4:
                    executable += 1
                    self.assertEqual(before[offset:offset + size], after[offset:offset + size])
            self.assertGreater(executable, 0)

    def test_screen_settings(self):
        with zipfile.ZipFile(PACKAGE) as z:
            template = z.read(BUILDER.HOOK)[29572:29626]
        self.assertEqual(len(template), 54)
        self.assertEqual(template[9], 1)
        service = fields(fields(template)[1])
        self.assertEqual(service[1], 14)
        sink = fields(service[3])
        video = fields(sink[4])
        self.assertEqual((video[4], video[5], video[9]), (358, 215, 215))
        ui = fields(video[11])
        self.assertEqual(fields(ui[1]), {1: 179, 2: 180})
        self.assertEqual(fields(ui[2]), {2: 195, 3: 160, 4: 160})

    def test_qnx_wc_fallback_detects_player_downgrade(self):
        with zipfile.ZipFile(PACKAGE) as five, zipfile.ZipFile(FOUR) as four:
            script = five.read("Mods/ClusterIntegration/Update/install.sh").decode()
            definitions = "\n".join(re.search(r"^" + name + r"\(\) \{.*?^\}",
                                               script, re.M | re.S).group(0)
                                      for name in ("file_size", "same_content_with", "files_same", "files_identical"))
            # Host stand-ins for ksh built-ins; comparison functions stay exact.
            shell = ('have() { command -v "$1" >/dev/null 2>&1; }\n'
                     'print() { [ "${1-}" != -r ] || shift; '
                     '[ "${1-}" != -- ] || shift; printf "%s\\n" "$*"; }\n') + definitions
            shell += '\nFILE_CMP=wc\nfiles_identical "$1" "$2"\n'
            with tempfile.TemporaryDirectory(prefix="candidate5-downgrade-") as tmp:
                source, installed = Path(tmp) / "candidate5", Path(tmp) / "candidate4"
                source.write_bytes(five.read(BUILDER.PLAYER))
                installed.write_bytes(four.read(BUILDER.PLAYER))
                old_time = datetime.datetime(*four.getinfo(BUILDER.PLAYER).date_time).timestamp()
                new_time = datetime.datetime(*five.getinfo(BUILDER.PLAYER).date_time).timestamp()
                os.utime(source, (old_time, old_time))
                os.utime(installed, (old_time, old_time))
                # Same-size change is missed by the actual wc fallback at equal times.
                command = ["bash", "-O", "extglob", "-c", shell, "fixture", str(source), str(installed)]
                self.assertEqual(subprocess.run(command, capture_output=True, timeout=5).returncode, 0)
                self.assertGreater(new_time, old_time)
                os.utime(source, (new_time, new_time))
                self.assertEqual(subprocess.run(command, capture_output=True, timeout=5).returncode, 1)
                for path in (BUILDER.AA_JAR, BUILDER.PLAYER, BUILDER.HOOK):
                    self.assertGreater(five.getinfo(path).date_time, four.getinfo(path).date_time)

    def test_builder_refuses_wrong_base_and_preserves_existing_output(self):
        with tempfile.TemporaryDirectory(prefix="candidate5-refusal-") as tmp:
            wrong, out = Path(tmp) / "wrong.zip", Path(tmp) / "out.zip"
            wrong.write_bytes(b"not candidate1")
            with self.assertRaises(ValueError):
                BUILDER.build(wrong, out)
            self.assertFalse(out.exists())
            out.write_bytes(b"keep")
            with self.assertRaises(ValueError):
                BUILDER.build(BASE, out)
            self.assertEqual(out.read_bytes(), b"keep")

    def test_build_is_reproducible(self):
        with tempfile.TemporaryDirectory(prefix="candidate5-build-") as tmp:
            out = Path(tmp) / PACKAGE.name
            with contextlib.redirect_stdout(io.StringIO()):
                BUILDER.build(BASE, out)
            self.assertEqual(out.read_bytes(), PACKAGE.read_bytes())

    @unittest.skipUnless(os.environ.get("ECJ_JAR"), "Set ECJ_JAR to test the archived gate")
    def test_exact_archived_gate_against_candidate1_fixtures(self):
        nav = "de/audi/app/terminalmode/smartphone/androidauto2/nav/Mh2pVideoGate.class"
        with tempfile.TemporaryDirectory(prefix="candidate5-gate-") as tmp:
            classes = Path(tmp) / "classes"
            path = classes / nav
            path.parent.mkdir(parents=True)
            with zipfile.ZipFile(PACKAGE) as package:
                with zipfile.ZipFile(io.BytesIO(package.read(BUILDER.AA_JAR))) as jar:
                    path.write_bytes(jar.read(nav))
                    self.assertFalse(any("Mh2pAutoRoute" in n or "Mh2pDmdt" in n
                                         or "Mh2pMapHandover" in n for n in jar.namelist()))
            fixtures = sorted((ROOT / "modkit/test/readiness-fixtures/de").rglob("*.java"))
            fixtures.append(ROOT / "modkit/test/candidate5-fixtures/Candidate1GateTest.java")
            subprocess.run(["java", "-jar", os.environ["ECJ_JAR"], "-1.4", "-source", "1.4",
                            "-target", "1.4", "-nowarn", "-cp", str(classes), "-d", str(classes),
                            *map(str, fixtures)], check=True, timeout=30, capture_output=True)
            result = subprocess.run(["java", "-Xverify:all", "-cp", str(classes), "Candidate1GateTest"],
                                    check=True, timeout=10, capture_output=True, text=True)
            self.assertEqual(result.stdout.count("PASS "), 15, result.stdout)


if __name__ == "__main__":
    unittest.main()
