#!/usr/bin/env python3
"""Fail closed on package/signing mismatch. Never uninstall or modify phone data."""
import glob
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
apk = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "app/build/outputs/apk/debug/app-debug.apk"
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
versions = list((sdk / "build-tools").glob("*/apksigner"))
if not versions:
    raise SystemExit("FAIL: Android build-tools/apksigner unavailable")
signer = max(versions, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.parent.name)))
verified = subprocess.run([str(signer), "verify", "--verbose", "--print-certs", str(apk)], text=True, capture_output=True, check=True)
digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", verified.stdout)
expected = (root / ".github/phase1-debug-sha256.txt").read_text().strip().lower()
assert re.fullmatch(r"[0-9a-f]{64}", expected), "Invalid baseline certificate digest"
badging = subprocess.run([str(signer.parent / "aapt"), "dump", "badging", str(apk)], text=True, capture_output=True, check=True).stdout
identity = re.search(r"package: name='([^']+)' versionCode='(\d+)'", badging)
assert identity is not None, "Package identity unavailable"
print(f"APK: {apk.name}")
print(f"SHA-256: {hashlib.sha256(apk.read_bytes()).hexdigest()}")
print(f"Package: {identity[1]}, versionCode: {identity[2]}")
print(f"Expected Phase 1 signer: {expected}")
print(f"Actual signer(s): {', '.join(digests)}")
if identity[1] != "com.vortexx.player.debug" or int(identity[2]) < 3:
    raise SystemExit("FAIL: Package identity/version gate. Do not install.")
if [d.lower() for d in digests] != [expected]:
    raise SystemExit("FAIL: Phase 1 signing key mismatch. BUILD-ONLY APK; do not uninstall Phase 1. Supply original DEBUG key; never production credentials.")
print("PASS: package/signing compatible with supplied Phase 1 APK. Phone update/data checks still required.")
