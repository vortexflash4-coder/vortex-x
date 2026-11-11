#!/usr/bin/env python3
"""Gate-contract fixtures using simulated SDK output; CI also verifies the real APK."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

A0 = "0011cfbf999ddb460d382f89a839a1b5e286dbd515cbe5965500ec4cce115c9f"
PHASE1 = "84970dbb7b74f0f54fee68811e4bedd429393e0667ccb1f82752cc8be6d3706f"
SOURCE = Path(__file__).with_name("verify-apk.py")


class InstallTargetGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        scripts = self.root / "project/.github/scripts"
        scripts.mkdir(parents=True)
        self.script = scripts / "verify-apk.py"
        shutil.copy2(SOURCE, self.script)
        (scripts.parent / "a0-debug-sha256.txt").write_text(A0 + "\n")
        (scripts.parent / "phase1-debug-sha256.txt").write_text(PHASE1 + "\n")
        self.apk = self.root / "fixture.apk"
        self.apk.write_bytes(b"fixture-only-not-an-apk")
        self.sdk = self.root / "sdk"
        tools = self.sdk / "build-tools/35.0.0"
        tools.mkdir(parents=True)
        (tools / "apksigner").write_text(
            "#!/usr/bin/env python3\nimport os\n"
            "value = os.environ['FIXTURE_SIGNER']\n"
            "print('Signer #1 certificate SHA-256 digest: ' + value if value else 'No certificate')\n"
        )
        (tools / "aapt").write_text(
            "#!/usr/bin/env python3\nimport os\n"
            "print(\"package: name='\" + os.environ['FIXTURE_PACKAGE'] + \"' versionCode='\" + os.environ['FIXTURE_VERSION'] + \"'\")\n"
        )
        for tool in tools.iterdir():
            tool.chmod(0o755)

    def verify(self, target=None, signer=A0, package="com.vortexx.player.debug", version="3"):
        run_env = os.environ.copy()
        run_env.update(ANDROID_HOME=str(self.sdk), FIXTURE_SIGNER=signer,
                       FIXTURE_PACKAGE=package, FIXTURE_VERSION=version)
        args = [sys.executable, str(self.script), str(self.apk)]
        if target is not None:
            args += ["--install-target", target]
        return subprocess.run(args, env=run_env, text=True, capture_output=True)

    def test_fresh_install_accepts_pinned_a0_key(self):
        result = self.verify("fresh-install")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Phase 1 upgrade compatibility", result.stdout)
        self.assertIn("NOT claimed", result.stdout)

    def test_phase1_upgrade_still_rejects_a0_key(self):
        result = self.verify("phase1-upgrade")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Phase 1 signing key mismatch", result.stderr)

    def test_phase1_upgrade_accepts_original_phase1_key(self):
        result = self.verify("phase1-upgrade", signer=PHASE1)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_omitted_target_stays_conservative_phase1_upgrade(self):
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Install target: phase1-upgrade", result.stdout)

    def test_fresh_install_rejects_unpinned_or_legacy_key(self):
        for signer in ("a" * 64, PHASE1):
            with self.subTest(signer=signer):
                result = self.verify("fresh-install", signer=signer)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("pinned A0 debug key", result.stderr)

    def test_package_identity_change_is_rejected(self):
        result = self.verify("fresh-install", package="com.example.other")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Package identity/version gate", result.stderr)

    def test_old_version_code_is_rejected(self):
        result = self.verify("fresh-install", version="1")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Package identity/version gate", result.stderr)

    def test_missing_certificate_is_unverified_not_a_pass(self):
        result = self.verify("fresh-install", signer="")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("UNVERIFIED", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
