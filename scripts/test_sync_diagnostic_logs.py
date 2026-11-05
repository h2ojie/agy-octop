"""Isolated regression tests: no real workspace, cloud or NAS is accessed."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("sync_diagnostic_logs.sh")


class ArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="octop-archive-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source with spaces"
        self.local = self.root / "local"
        self.cloud = self.root / "cloud"
        self.nas = self.root / "nas"
        for path in (self.source, self.cloud, self.nas):
            path.mkdir()
        self.env = dict(os.environ, OCTOP_LOG_SOURCE_DIR=str(self.source),
                        OCTOP_LOG_LOCAL_DIR=str(self.local), OCTOP_LOG_GDRIVE_ROOT=str(self.cloud),
                        OCTOP_LOG_NAS_ROOT=str(self.nas), TMPDIR=str(self.root))

    def run_script(self):
        return subprocess.run(["bash", str(SCRIPT)], env=self.env, text=True, capture_output=True)

    def test_original_timestamp_prefix_spaces_newlines_and_idempotence(self):
        names = ["octop_logs_1.zip", "179337_octop_logs_2.zip", "179338_octop_logs_a b\nc.zip"]
        for name in names:
            (self.source / name).write_bytes(b"diagnostics")
        (self.source / "unrelated.zip").write_bytes(b"ignore")
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("found=3 copied=9", result.stdout)
        for destination in (self.local, self.cloud / "octop-logs", self.nas / "octop-logs"):
            for name in names:
                self.assertEqual(b"diagnostics", (destination / name).read_bytes())
        again = self.run_script()
        self.assertEqual(0, again.returncode, again.stderr)
        self.assertIn("copied=0 existing=9", again.stdout)

    def test_missing_mounts_are_skipped_not_created(self):
        self.env["OCTOP_LOG_GDRIVE_ROOT"] = str(self.root / "missing cloud")
        self.env["OCTOP_LOG_NAS_ROOT"] = str(self.root / "missing nas")
        (self.source / "octop_logs_1.zip").write_bytes(b"data")
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("skipped_destinations=2", result.stdout)
        self.assertFalse((self.root / "missing cloud").exists())
        self.assertFalse((self.root / "missing nas").exists())

    def test_new_archives_are_private(self):
        (self.source / "octop_logs_1.zip").write_bytes(b"private")
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        for destination in (self.local, self.cloud / "octop-logs", self.nas / "octop-logs"):
            self.assertEqual(0, destination.stat().st_mode & 0o077)
            self.assertEqual(0, (destination / "octop_logs_1.zip").stat().st_mode & 0o077)

    def test_default_scan_is_limited_to_agent_and_workspace_inbound(self):
        self.env.pop("OCTOP_LOG_SOURCE_DIR")
        self.env["HOME"] = str(self.root)
        for relative, name in [
            ("agents/a/inbound", "octop_logs_agent.zip"),
            ("workspaces/b/inbound/nested", "123_octop_logs_workspace.zip"),
            ("portable/packages/openai", "octop_logs_portable.zip"),
            ("runtime", "octop_logs_runtime.zip"),
            ("backups/agents/a/inbound", "octop_logs_backup.zip"),
            ("workspaces/b/repo", "octop_logs_repo.zip"),
        ]:
            directory = self.root / ".octop" / relative
            directory.mkdir(parents=True, exist_ok=True)
            (directory / name).write_bytes(b"fixture")
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("found=2 copied=6", result.stdout)
        self.assertEqual({"octop_logs_agent.zip", "123_octop_logs_workspace.zip"},
                         {p.name for p in self.local.iterdir()})

    def test_default_scan_without_inbound_does_not_fall_back(self):
        self.env.pop("OCTOP_LOG_SOURCE_DIR")
        self.env["HOME"] = str(self.root)
        runtime = self.root / ".octop" / "portable"
        runtime.mkdir(parents=True)
        (runtime / "octop_logs_ignore.zip").write_bytes(b"ignore")
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("found=0 copied=0", result.stdout)
        self.assertEqual([], list(self.local.iterdir()))

    def test_scan_failure_is_nonzero(self):
        self.env["OCTOP_LOG_SOURCE_DIR"] = str(self.root / "missing source")
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("scan failed", result.stderr)

    def test_directory_failure_is_nonzero(self):
        self.local.write_text("not a directory")
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Cannot create archive directory", result.stderr)

    def test_copy_failure_does_not_claim_success_or_leave_partial_archive(self):
        (self.source / "octop_logs_1.zip").write_bytes(b"data")
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        cp = bin_dir / "cp"
        cp.write_text('#!/bin/bash\nprintf partial > "$2"\nexit 1\n')
        cp.chmod(0o755)
        self.env["PATH"] = str(bin_dir) + os.pathsep + self.env["PATH"]
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("failures=3", result.stdout)
        self.assertNotIn("Copied to", result.stdout)
        for destination in (self.local, self.cloud / "octop-logs", self.nas / "octop-logs"):
            self.assertEqual([], list(destination.iterdir()))

    def setup_ssh_stub(self, body=None):
        bin_dir = self.root / "ssh-bin"
        bin_dir.mkdir()
        stub = bin_dir / "ssh"
        stub.write_text(body or '''#!/usr/bin/env python3
import os, sys
assert "BatchMode=yes" in sys.argv
assert "ConnectTimeout=10" in sys.argv
os.execl("/bin/sh", "sh", "-c", sys.argv[-1])
''')
        stub.chmod(0o755)
        self.env["PATH"] = str(bin_dir) + os.pathsep + self.env["PATH"]
        self.env["OCTOP_LOG_NAS_SSH_TARGET"] = "mock-nas"
        self.remote = self.root / "remote ' quoted"
        self.env["OCTOP_LOG_NAS_SSH_DIR"] = str(self.remote)

    def test_ssh_archive_atomic_idempotent_and_no_mounted_nas_access(self):
        self.setup_ssh_stub()
        name = "123_octop_logs_a ' b\\nc.zip"
        (self.source / name).write_bytes(b"private diagnostics")
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("copied=3", result.stdout)
        self.assertEqual(b"private diagnostics", (self.remote / name).read_bytes())
        self.assertEqual(0, (self.remote / name).stat().st_mode & 0o077)
        self.assertFalse((self.nas / "octop-logs").exists())
        self.assertEqual([name], [p.name for p in self.remote.iterdir()])
        again = self.run_script()
        self.assertEqual(0, again.returncode, again.stderr)
        self.assertIn("existing=3", again.stdout)

    def test_ssh_conflict_preserves_original(self):
        self.setup_ssh_stub()
        name = "octop_logs_1.zip"
        (self.source / name).write_bytes(b"new")
        self.remote.mkdir()
        (self.remote / name).write_bytes(b"old")
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("failures=1", result.stdout)
        self.assertEqual(b"old", (self.remote / name).read_bytes())
        self.assertEqual([name], [p.name for p in self.remote.iterdir()])

    def test_ssh_connection_failure_is_counted(self):
        self.setup_ssh_stub("#!/bin/sh\nexit 255\n")
        (self.source / "octop_logs_1.zip").write_bytes(b"data")
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("failures=1", result.stdout)
        self.assertNotIn("Copied to NAS SSH", result.stdout)

    def test_ssh_timeout_is_counted(self):
        self.setup_ssh_stub("#!/usr/bin/env python3\nimport time\ntime.sleep(10)\n")
        self.env["OCTOP_LOG_NAS_SSH_TIMEOUT"] = "1"
        (self.source / "octop_logs_1.zip").write_bytes(b"data")
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("timed out", result.stderr)
        self.assertIn("failures=1", result.stdout)

    def test_ssh_corrupt_transfer_is_rejected(self):
        self.setup_ssh_stub('''#!/usr/bin/env python3
import subprocess, sys
result = subprocess.run(["/bin/sh", "-c", sys.argv[-1]], input=b"corrupted")
sys.exit(result.returncode)
''')
        (self.source / "octop_logs_1.zip").write_bytes(b"original")
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("checksum mismatch", result.stderr)
        self.assertEqual([], list(self.remote.iterdir()))

    def test_conflicting_existing_archive_is_not_silently_accepted(self):
        (self.source / "octop_logs_1.zip").write_bytes(b"new")
        self.local.mkdir()
        (self.local / "octop_logs_1.zip").write_bytes(b"old")
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("differs from source", result.stderr)
        self.assertEqual(b"old", (self.local / "octop_logs_1.zip").read_bytes())


if __name__ == "__main__":
    unittest.main()
