#!/usr/bin/env python3
"""Require actual JUnit evidence and both allocation gates. Skips cannot pass A0."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("app/build/test-results/testDebugUnitTest")
files = sorted(root.glob("TEST-*.xml"))
if not files:
    raise SystemExit("FAIL: no JVM test reports; tests are NOT verified")
counts = dict(tests=0, failures=0, errors=0, skipped=0)
allocation = {
    "com.vortexx.player.DspChainTest.processingPathDoesNotAllocate": False,
    "com.vortexx.player.VortexAudioProcessorTest.processorPathDoesNotAllocateAcrossSizesFormatsAndModeChanges": False,
}
for file in files:
    suite = ET.parse(file).getroot()
    for key in counts:
        counts[key] += int(suite.get(key, "0"))
    for case in suite.findall("testcase"):
        name = case.get("classname", "") + "." + case.get("name", "")
        bad = any(case.find(tag) is not None for tag in ("failure", "error", "skipped"))
        if name in allocation:
            allocation[name] = not bad
        if bad:
            print(f"NOT PASSED: {name}")
    output = suite.findtext("system-out", "")
    for line in output.splitlines():
        if "allocation" in line.lower() or "headroom" in line.lower():
            print(line)
passed = counts["tests"] - counts["failures"] - counts["errors"] - counts["skipped"]
print(f"JVM: {counts['tests']} tests, {passed} passed, {counts['failures'] + counts['errors']} failed, {counts['skipped']} skipped")
for name, ok in allocation.items():
    print(f"Allocation gate {'PASS' if ok else 'FAIL/UNMEASURED'}: {name}")
if counts["tests"] == 0 or any(counts[k] for k in ("failures", "errors", "skipped")) or not all(allocation.values()):
    raise SystemExit(1)
