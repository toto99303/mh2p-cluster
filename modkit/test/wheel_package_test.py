#!/usr/bin/env python3
"""Validate archived candidate6 and its installer changes without OEM execution."""
import hashlib
import io
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "modkit"))
import build_wheel_zoom_package as builder
from verify_readiness_class import methods

BASE = ROOT / "builds/ClusterIntegration_a6_c8_p2711_readiness_centered_candidate5.zip"
PACKAGE = ROOT / "builds/ClusterIntegration_a6_c8_p2711_aa_wheel_candidate6.zip"


class WheelPackageTest(unittest.TestCase):
    def test_exact_baseline_and_payload_scope(self):
        self.assertEqual(hashlib.sha256(BASE.read_bytes()).hexdigest(), builder.BASE_HASH)
        digest = hashlib.sha256(PACKAGE.read_bytes()).hexdigest()
        self.assertEqual(Path(str(PACKAGE) + ".sha256").read_text().split()[0], digest)
        with zipfile.ZipFile(BASE) as a, zipfile.ZipFile(PACKAGE) as b:
            self.assertIsNone(b.testzip())
            self.assertEqual(set(b.namelist()) - set(a.namelist()), {builder.HELPER})
            self.assertFalse(set(a.namelist()) - set(b.namelist()))
            self.assertEqual({n for n in a.namelist() if a.read(n) != b.read(n)}, {
                builder.JAR, builder.UPDATE + "gal", builder.UPDATE + "install.sh", "README.md"})
            for name in (builder.JAR, builder.UPDATE + "gal", builder.UPDATE + "install.sh", builder.HELPER):
                self.assertEqual(b.getinfo(name).date_time, builder.DATE)
            self.assertEqual(b.read("README.md"), (builder.WHEEL / "CANDIDATE6.md").read_bytes())

    def test_original_classes_and_methods_preserved(self):
        with zipfile.ZipFile(BASE) as a, zipfile.ZipFile(PACKAGE) as b:
            before = zipfile.ZipFile(io.BytesIO(a.read(builder.JAR)))
            after = zipfile.ZipFile(io.BytesIO(b.read(builder.JAR)))
            changed = {builder.PREFIX + "AndroidAuto2Subsystem.class",
                       builder.PREFIX + "nav/AndroidAutoClusterIntegration.class"}
            self.assertEqual({n for n in before.namelist() if before.read(n) != after.read(n)}, changed)
            self.assertFalse(set(before.namelist()) - set(after.namelist()))
            extra = set(after.namelist()) - set(before.namelist())
            self.assertTrue(extra)
            self.assertTrue(all(n.startswith(builder.PREFIX + "nav/zoom/") for n in extra))
            for name in extra:
                data = after.read(name)
                self.assertEqual(struct.unpack_from(">H", data, 6)[0], 48)
                self.assertNotIn(b"java/lang/StringBuilder", data)
            for name in changed:
                old, new = methods(before.read(name)), methods(after.read(name))
                integration = "Integration" in name
                self.assertEqual(len(new), len(old) + int(integration))
                for (method, desc), body in old.items():
                    if not integration and method == "<init>":
                        continue
                    key = ("sendMirrorCommandWithoutWheel" if integration and method == "sendMirrorCommandUngated" else method, desc)
                    self.assertEqual(body, new[key], method)

    def test_native_binary_abi(self):
        with zipfile.ZipFile(PACKAGE) as archive, tempfile.TemporaryDirectory() as tmp:
            native = Path(tmp) / "wheel.so"; native.write_bytes(archive.read(builder.HELPER))
            builder.verify_native(native)

    def test_installer_and_wrapper_change_only_addon_blocks(self):
        with zipfile.ZipFile(BASE) as a, zipfile.ZipFile(PACKAGE) as b:
            install = b.read(builder.UPDATE + "install.sh").decode()
            self.assertEqual(install.replace(builder.INSTALL, ""), a.read(builder.UPDATE + "install.sh").decode())
            self.assertLess(install.index(builder.INSTALL), install.index('install_file "$NEW_JAR"'))
            wrapper = b.read(builder.UPDATE + "gal").decode()
            self.assertEqual(wrapper.replace(builder.LAUNCH, '/mnt/app/eso/bin/apps/gal.real "$@"'), a.read(builder.UPDATE + "gal").decode())
            self.assertLess(wrapper.index('"$CLUSTER_BIN" daemon'), wrapper.index(builder.LAUNCH))
            for text in (install, wrapper):
                subprocess.run(["mksh", "-n"], input=text, text=True, check=True)

    def test_helper_failure_aborts_before_java_and_success_records_removal(self):
        # Execute the actual archived add-on block with controlled installer
        # dependencies, not the rest of the unit-specific installer.
        with zipfile.ZipFile(PACKAGE) as archive:
            install = archive.read(builder.UPDATE + "install.sh").decode()
            block = install[install.index("# MH2P_WHEEL_INSTALL_BEGIN"):
                            install.index("# Java classes: on Audi")]
        fixture = '''install_file() { print "helper-copy"; return "$COPY_RC"; }
chmod() { return "$CHMOD_RC"; }
note() { print "manifest $*"; }
''' + block + 'print "JAVA_CAN_START"\n'
        for copy_rc, chmod_rc, success in ((0, 0, True), (1, 0, False), (3, 0, False), (0, 1, False)):
            result = subprocess.run(["mksh"], input=fixture, text=True, capture_output=True,
                env=dict(os.environ, COPY_RC=str(copy_rc), CHMOD_RC=str(chmod_rc),
                         MOD_PATH="/unused/source", CLUSTER_DIR="/owned/cluster"))
            self.assertEqual(result.returncode == 0, success)
            self.assertEqual("JAVA_CAN_START" in result.stdout, success)
            self.assertEqual("manifest add /owned/cluster/mh2p_wheel.so" in result.stdout, success)


if __name__ == "__main__":
    unittest.main()
