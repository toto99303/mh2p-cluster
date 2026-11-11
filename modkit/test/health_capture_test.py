#!/usr/bin/env python3
"""Host fixtures for manual collector bounds and failure behavior, not QNX APIs."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "diagnostics/capture_mh2p_health.sh"


class CaptureTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="mh2p-health-test-")
        self.root = Path(self.tmp.name)
        self.trace = self.root / "trace"
        for name in ("pidin", "date", "ls", "sloginfo", "sleep"):
            tool = self.root / name
            tool.write_text(
                "#!/bin/sh\n"
                f"printf '%s\\n' '{name}'\" $*\" >> \"$TRACE\"\n"
                f"if [ '{name}' = sleep ] && [ \"${{SLEEP_FAIL:-0}}\" = 1 ]; then exit 1; fi\n"
                f"if [ '{name}' = pidin ] && [ \"$*\" = '-p j9 fds' ]; then\n"
                "  if [ \"${QUERY_FAIL:-0}\" = 1 ]; then echo unsupported; exit 3; fi\n"
                "fi\n"
                f"echo 'fixture {name}'\n"
            )
            tool.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.root), TRACE=str(self.trace))

    def tearDown(self):
        self.tmp.cleanup()

    def capture(self, shell, *args, **env):
        return subprocess.run(
            [shutil.which(shell), str(SCRIPT), *args],
            env=dict(self.env, **env), text=True, capture_output=True, timeout=5,
        )

    def test_finite_sequential_capture(self):
        for shell in ("bash", "dash"):
            with self.subTest(shell=shell):
                self.trace.unlink(missing_ok=True)
                result = self.capture(shell, "2", "30")
                self.assertEqual(result.returncode, 0, result.stderr)
                rows = self.trace.read_text().splitlines()
                self.assertEqual(rows.count("pidin info"), 2)
                self.assertEqual(rows.count("pidin -p j9 threads"), 2)
                self.assertEqual(rows.count("pidin -p j9 fds"), 2)
                self.assertEqual(rows.count("sleep 30"), 1)
                self.assertEqual(rows.count("sloginfo "), 2)
                self.assertIn("COMPLETE samples=2 command_failures=0", result.stdout)

    def test_invalid_arguments_do_no_queries(self):
        for args in (("",), ("0",), ("121",), ("01",), ("x",), ("1", ""), ("1", "0"),
                     ("1", "29"), ("1", "301"), ("1", "030"),
                     ("1", "30", "extra"), ("99999999999999999999",)):
            with self.subTest(args=args):
                result = self.capture("dash", *args)
                self.assertEqual(result.returncode, 2)
                self.assertFalse(self.trace.exists())

    def test_failed_pause_stops_queries(self):
        result = self.capture("dash", "2", "30", SLEEP_FAIL="1")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.trace.read_text().splitlines().count("pidin info"), 1)
        self.assertNotIn("CAPTURE_COMPLETE", result.stdout)

    def test_unsupported_query_is_reported_and_capture_continues(self):
        result = self.capture("dash", "2", "30", QUERY_FAIL="1")
        self.assertEqual(result.returncode, 1)
        self.assertIn("=== EXIT: 3 ===", result.stdout)
        self.assertIn("COMPLETE samples=2 command_failures=2", result.stdout)

    def test_missing_pidin_fails_before_any_capture(self):
        (self.root / "pidin").unlink()
        result = self.capture("dash", "1", "30")
        self.assertEqual(result.returncode, 1)
        self.assertIn("pidin unavailable", result.stderr)
        self.assertFalse(self.trace.exists())


if __name__ == "__main__":
    unittest.main()
